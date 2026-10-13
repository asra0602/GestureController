package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that overlays hand skeleton landmarks, connections, and pinch meters.
 */
class LandmarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var landmarks: List<GestureEngine.Point3D> = emptyList()
    private var isPinching: Boolean = false
    private var pinchDistance: Float = 1.0f
    private var gestureLabel: String = ""
    private var isMirrored: Boolean = true

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40C4FF") // Neon light blue
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }

    private val indexTipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Electric cyan
        style = Paint.Style.FILL
    }

    private val thumbTipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600") // Amber
        style = Paint.Style.FILL
    }

    private val pinchLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 7f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    // MediaPipe Hand connection pairs
    private val connections = listOf(
        // Palm
        Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4), // Thumb
        Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8), // Index
        Pair(5, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12), // Middle
        Pair(9, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16), // Ring
        Pair(13, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20), // Pinky
        Pair(0, 17) // Wrist to pinky base
    )

    fun updateResults(
        newLandmarks: List<GestureEngine.Point3D>,
        pinching: Boolean,
        dist: Float,
        label: String = "",
        mirrored: Boolean = true
    ) {
        this.landmarks = newLandmarks
        this.isPinching = pinching
        this.pinchDistance = dist
        this.gestureLabel = label
        this.isMirrored = mirrored
        postInvalidate()
    }

    fun clear() {
        this.landmarks = emptyList()
        this.isPinching = false
        this.gestureLabel = ""
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty() || width == 0 || height == 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        fun getScreenX(normX: Float): Float {
            return if (isMirrored) (1.0f - normX) * w else normX * w
        }

        fun getScreenY(normY: Float): Float {
            return normY * h
        }

        // Draw skeleton connections
        for (conn in connections) {
            if (conn.first < landmarks.size && conn.second < landmarks.size) {
                val p1 = landmarks[conn.first]
                val p2 = landmarks[conn.second]
                canvas.drawLine(
                    getScreenX(p1.x), getScreenY(p1.y),
                    getScreenX(p2.x), getScreenY(p2.y),
                    linePaint
                )
            }
        }

        // Draw pinch line between thumb tip (4) and index tip (8)
        if (landmarks.size > 8) {
            val thumb = landmarks[4]
            val index = landmarks[8]

            pinchLinePaint.color = if (isPinching) {
                Color.parseColor("#FF0055") // Active pinch: vibrant hot pink
            } else {
                Color.parseColor("#00E676") // Green ready
            }

            canvas.drawLine(
                getScreenX(thumb.x), getScreenY(thumb.y),
                getScreenX(index.x), getScreenY(index.y),
                pinchLinePaint
            )
        }

        // Draw landmark joints
        for (i in landmarks.indices) {
            val p = landmarks[i]
            val px = getScreenX(p.x)
            val py = getScreenY(p.y)

            when (i) {
                8 -> {
                    // Index tip (Primary cursor)
                    canvas.drawCircle(px, py, 14f, indexTipPaint)
                    canvas.drawCircle(px, py, 18f, linePaint)
                }
                4 -> {
                    // Thumb tip
                    canvas.drawCircle(px, py, 12f, thumbTipPaint)
                }
                else -> {
                    canvas.drawCircle(px, py, 7f, jointPaint)
                }
            }
        }

        // Optional status overlay
        if (gestureLabel.isNotEmpty()) {
            canvas.drawText(gestureLabel, 24f, 50f, textPaint)
        }
    }
}
