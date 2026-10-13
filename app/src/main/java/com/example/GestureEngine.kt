package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Recognizes hand gestures and produces cursor position and action triggers
 * using MediaPipe HandLandmarker.
 */
class GestureEngine(
    private val context: Context,
    private val listener: GestureListener
) {

    enum class GestureType {
        NONE,
        POINTER,
        PINCH_CLICK,
        V_SIGN_BACK,
        FIST,
        OPEN_PALM,
        SWIPE_UP,
        SWIPE_DOWN
    }

    data class GestureState(
        val cursorNormX: Float,
        val cursorNormY: Float,
        val isPinching: Boolean,
        val pinchDistance: Float,
        val gestureType: GestureType,
        val rawLandmarks: List<Point3D> = emptyList(),
        val confidence: Float = 0f
    )

    data class Point3D(val x: Float, val y: Float, val z: Float)

    interface GestureListener {
        fun onGestureUpdate(state: GestureState)
        fun onGestureAction(action: GestureType, cursorXNorm: Float, cursorYNorm: Float)
        fun onError(message: String)
    }

    private var handLandmarker: HandLandmarker? = null

    // Sensitivity and tuning parameters
    var smoothingAlpha: Float = 0.45f // 0.1 (very smooth, slightly laggy) to 0.9 (instant, slightly jittery)
    var pinchThreshold: Float = 0.075f // Distance below which fingers are pinching
    var isMirrored: Boolean = true // Front camera should mirror X

    // State tracking
    private var smoothedCursorX = 0.5f
    private var smoothedCursorY = 0.5f
    private var lastPinchState = false
    private var lastGestureTriggerTime = 0L
    private val gestureCooldownMs = 400L

    // History for swipe detection
    private val positionHistory = mutableListOf<Pair<Long, Pair<Float, Float>>>()

    init {
        setupHandLandmarker()
    }

    fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: HandLandmarkerResult, _: MPImage ->
                    processLandmarkerResult(result)
                }
                .setErrorListener { error: RuntimeException ->
                    Log.e(TAG, "MediaPipe error: ${error.message}", error)
                    listener.onError(error.message ?: "Tracking error")
                }
                .build()


            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe HandLandmarker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize HandLandmarker", e)
            listener.onError("Model initialization failed: ${e.localizedMessage}")
        }
    }

    /**
     * Feeds camera frame to MediaPipe.
     */
    fun processImageProxy(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val processedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            val mpImage: MPImage = BitmapImageBuilder(processedBitmap).build()
            val frameTimeMs = SystemClock.uptimeMillis()
            landmarker.detectAsync(mpImage, frameTimeMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing camera frame", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun processLandmarkerResult(result: HandLandmarkerResult) {
        val landmarksList = result.landmarks()
        if (landmarksList.isEmpty() || landmarksList[0].isEmpty()) {
            val emptyState = GestureState(
                cursorNormX = smoothedCursorX,
                cursorNormY = smoothedCursorY,
                isPinching = false,
                pinchDistance = 1.0f,
                gestureType = GestureType.NONE
            )
            listener.onGestureUpdate(emptyState)
            return
        }

        val landmarks = landmarksList[0]
        val points = landmarks.map { Point3D(it.x(), it.y(), it.z()) }

        val wrist = points[0]
        val thumbTip = points[4]
        val indexTip = points[8]
        val middleTip = points[12]
        val ringTip = points[16]
        val pinkyTip = points[20]

        val indexPip = points[6]
        val middlePip = points[10]
        val ringPip = points[14]
        val pinkyPip = points[18]

        // 1. Raw Cursor coordinates from Index Fingertip
        val rawX = if (isMirrored) 1.0f - indexTip.x else indexTip.x
        val rawY = indexTip.y

        // Exponential smoothing filter
        smoothedCursorX = smoothedCursorX + (rawX - smoothedCursorX) * smoothingAlpha
        smoothedCursorY = smoothedCursorY + (rawY - smoothedCursorY) * smoothingAlpha

        // Clamp cursor within screen bounds
        val cursorX = smoothedCursorX.coerceIn(0.02f, 0.98f)
        val cursorY = smoothedCursorY.coerceIn(0.02f, 0.98f)

        // 2. Pinch calculation (Distance between Thumb Tip and Index Tip)
        val dx = thumbTip.x - indexTip.x
        val dy = thumbTip.y - indexTip.y
        val dz = thumbTip.z - indexTip.z
        val pinchDist = sqrt(dx * dx + dy * dy + dz * dz)

        val isPinching = pinchDist < pinchThreshold

        // 3. Finger extension analysis
        val isIndexExtended = dist(wrist, indexTip) > dist(wrist, indexPip) * 1.15f
        val isMiddleExtended = dist(wrist, middleTip) > dist(wrist, middlePip) * 1.15f
        val isRingExtended = dist(wrist, ringTip) > dist(wrist, ringPip) * 1.15f
        val isPinkyExtended = dist(wrist, pinkyTip) > dist(wrist, pinkyPip) * 1.15f

        // Classify gesture type
        var detectedType = GestureType.POINTER

        if (isPinching) {
            detectedType = GestureType.PINCH_CLICK
        } else if (isIndexExtended && isMiddleExtended && !isRingExtended && !isPinkyExtended) {
            detectedType = GestureType.V_SIGN_BACK
        } else if (!isIndexExtended && !isMiddleExtended && !isRingExtended && !isPinkyExtended) {
            detectedType = GestureType.FIST
        } else if (isIndexExtended && isMiddleExtended && isRingExtended && isPinkyExtended) {
            detectedType = GestureType.OPEN_PALM
        }

        // Check for quick vertical swipes
        val now = System.currentTimeMillis()
        positionHistory.add(Pair(now, Pair(cursorX, cursorY)))
        // Keep only points from last 350ms
        positionHistory.removeAll { now - it.first > 350L }

        if (positionHistory.size >= 4 && !isPinching) {
            val oldest = positionHistory.first().second
            val newest = positionHistory.last().second
            val deltaY = newest.second - oldest.second
            val deltaX = newest.first - oldest.first

            if (abs(deltaY) > 0.25f && abs(deltaY) > abs(deltaX) * 1.8f) {
                if (deltaY < -0.25f) {
                    detectedType = GestureType.SWIPE_UP
                } else if (deltaY > 0.25f) {
                    detectedType = GestureType.SWIPE_DOWN
                }
            }
        }

        // Trigger discrete action callbacks with cooldown
        val canTrigger = (now - lastGestureTriggerTime) > gestureCooldownMs

        // Pinch transition: on transition to pinch, fire CLICK
        if (isPinching && !lastPinchState && canTrigger) {
            lastGestureTriggerTime = now
            listener.onGestureAction(GestureType.PINCH_CLICK, cursorX, cursorY)
        } else if (detectedType == GestureType.V_SIGN_BACK && canTrigger) {
            lastGestureTriggerTime = now
            listener.onGestureAction(GestureType.V_SIGN_BACK, cursorX, cursorY)
        } else if ((detectedType == GestureType.SWIPE_UP || detectedType == GestureType.SWIPE_DOWN) && canTrigger) {
            lastGestureTriggerTime = now
            listener.onGestureAction(detectedType, cursorX, cursorY)
            positionHistory.clear()
        }

        lastPinchState = isPinching

        val state = GestureState(
            cursorNormX = cursorX,
            cursorNormY = cursorY,
            isPinching = isPinching,
            pinchDistance = pinchDist,
            gestureType = detectedType,
            rawLandmarks = points,
            confidence = 0.95f
        )
        listener.onGestureUpdate(state)
    }

    private fun dist(p1: Point3D, p2: Point3D): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        val dz = p1.z - p2.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun close() {
        try {
            handLandmarker?.close()
            handLandmarker = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing hand landmarker", e)
        }
    }

    companion object {
        private const val TAG = "GestureEngine"
    }
}
