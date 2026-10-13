package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AccessibilityService that dispatches gestures (taps, clicks, scrolls, swipes)
 * and executes global system actions (Back, Home, Recents) triggered by Hand Gestures.
 */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "GestureAccessibilityService connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for active gesture injection
    }

    override fun onInterrupt() {
        Log.w(TAG, "GestureAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            _isConnected.value = false
        }
        Log.i(TAG, "GestureAccessibilityService destroyed")
    }

    /**
     * Dispatches a single tap at (x, y)
     */
    fun performClickAt(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        val clickPath = Path().apply {
            moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
        }
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Click gesture succeeded at ($x, $y)")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Click gesture cancelled at ($x, $y)")
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Dispatches a drag/swipe between two coordinates
     */
    fun performSwipeBetween(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 250,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(swipePath, 0, durationMs.coerceAtLeast(50))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Scrolls screen up (content moves up)
     */
    fun scrollUp(screenWidth: Int, screenHeight: Int) {
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.75f
        val endY = screenHeight * 0.25f
        performSwipeBetween(startX, startY, startX, endY, 300)
    }

    /**
     * Scrolls screen down (content moves down)
     */
    fun scrollDown(screenWidth: Int, screenHeight: Int) {
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.25f
        val endY = screenHeight * 0.75f
        performSwipeBetween(startX, startY, startX, endY, 300)
    }

    /**
     * Executes global navigation actions
     */
    fun triggerBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun triggerHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun triggerRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun triggerNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    companion object {
        private const val TAG = "GestureA11yService"

        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun isServiceRunning(): Boolean = instance != null
    }
}
