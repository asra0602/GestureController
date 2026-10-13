package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground Service that handles camera analysis, screen cursor overlay,
 * and communicates with GestureAccessibilityService for system touch injection.
 */
class GestureControlService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var cameraExecutor: ExecutorService
    private var gestureEngine: GestureEngine? = null

    // Screen dimensions
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    // Floating Cursor view
    private var cursorView: ImageView? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    // Optional floating Camera PiP view
    private var floatingPipView: View? = null
    private var pipParams: WindowManager.LayoutParams? = null
    private var pipPreviewView: PreviewView? = null
    private var pipOverlayView: LandmarkOverlayView? = null

    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cameraExecutor = Executors.newSingleThreadExecutor()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        updateScreenDimensions()
        createNotificationChannel()

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        _isServiceActive.value = true

        initGestureEngine()
        setupFloatingCursor()
        if (_showPipWindow.value) {
            setupFloatingPip()
        }
        startCameraAnalysis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PIP -> {
                val shouldShow = intent.getBooleanExtra(EXTRA_SHOW_PIP, true)
                _showPipWindow.value = shouldShow
                if (shouldShow) {
                    if (floatingPipView == null) setupFloatingPip()
                } else {
                    removeFloatingPip()
                }
            }
        }
        return START_STICKY
    }

    private fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    private fun initGestureEngine() {
        gestureEngine = GestureEngine(this, object : GestureEngine.GestureListener {
            override fun onGestureUpdate(state: GestureEngine.GestureState) {
                _latestGestureState.value = state

                // Update floating cursor position on main thread
                cursorView?.post {
                    updateCursorPosition(state)
                }

                // Update floating PIP overlay if active
                pipOverlayView?.post {
                    pipOverlayView?.updateResults(
                        newLandmarks = state.rawLandmarks,
                        pinching = state.isPinching,
                        dist = state.pinchDistance,
                        label = state.gestureType.name,
                        mirrored = true
                    )
                }
            }

            override fun onGestureAction(
                action: GestureEngine.GestureType,
                cursorXNorm: Float,
                cursorYNorm: Float
            ) {
                handleGestureAction(action, cursorXNorm, cursorYNorm)
            }

            override fun onError(message: String) {
                Log.e(TAG, "Gesture engine error: $message")
            }
        })
    }

    private fun setupFloatingCursor() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlays: permission missing")
            return
        }

        try {
            cursorView = ImageView(this).apply {
                setImageResource(R.drawable.cursor_idle)
            }

            val cursorSizePx = (48 * resources.displayMetrics.density).toInt()

            cursorParams = WindowManager.LayoutParams(
                cursorSizePx,
                cursorSizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth / 2 - cursorSizePx / 2
                y = screenHeight / 2 - cursorSizePx / 2
            }

            windowManager.addView(cursorView, cursorParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating cursor", e)
        }
    }

    private fun updateCursorPosition(state: GestureEngine.GestureState) {
        val view = cursorView ?: return
        val params = cursorParams ?: return

        val cursorSizePx = view.width.takeIf { it > 0 } ?: (48 * resources.displayMetrics.density).toInt()

        params.x = (screenWidth * state.cursorNormX - cursorSizePx / 2).toInt()
        params.y = (screenHeight * state.cursorNormY - cursorSizePx / 2).toInt()

        if (state.isPinching) {
            view.setImageResource(R.drawable.cursor_active)
        } else {
            view.setImageResource(R.drawable.cursor_idle)
        }

        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            // View may have been detached
        }
    }

    private fun handleGestureAction(
        action: GestureEngine.GestureType,
        cursorXNorm: Float,
        cursorYNorm: Float
    ) {
        val targetX = screenWidth * cursorXNorm
        val targetY = screenHeight * cursorYNorm

        triggerHaptic()

        val a11y = GestureAccessibilityService.instance
        if (a11y == null) {
            Log.w(TAG, "AccessibilityService is not connected to perform action: $action")
            return
        }

        when (action) {
            GestureEngine.GestureType.PINCH_CLICK -> {
                Log.i(TAG, "Dispatching CLICK at ($targetX, $targetY)")
                a11y.performClickAt(targetX, targetY)
            }
            GestureEngine.GestureType.V_SIGN_BACK -> {
                Log.i(TAG, "Triggering BACK navigation")
                a11y.triggerBack()
            }
            GestureEngine.GestureType.SWIPE_UP -> {
                Log.i(TAG, "Triggering SCROLL UP")
                a11y.scrollUp(screenWidth, screenHeight)
            }
            GestureEngine.GestureType.SWIPE_DOWN -> {
                Log.i(TAG, "Triggering SCROLL DOWN")
                a11y.scrollDown(screenWidth, screenHeight)
            }
            else -> {}
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun setupFloatingPip() {
        if (!Settings.canDrawOverlays(this) || floatingPipView != null) return

        try {
            val density = resources.displayMetrics.density
            val pipWidth = (120 * density).toInt()
            val pipHeight = (160 * density).toInt()

            val container = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.overlay_bg)
                clipToOutline = true
            }

            pipPreviewView = PreviewView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            pipOverlayView = LandmarkOverlayView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            container.addView(pipPreviewView)
            container.addView(pipOverlayView)

            pipParams = WindowManager.LayoutParams(
                pipWidth,
                pipHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - pipWidth - (16 * density).toInt()
                y = (80 * density).toInt()
            }

            // Drag listener to reposition PiP anywhere on screen
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            container.setOnTouchListener { _, event ->
                val p = pipParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initialX + (event.rawX - initialTouchX).toInt()
                        p.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, p)
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(container, pipParams)
            floatingPipView = container
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup floating PiP", e)
        }
    }

    private fun removeFloatingPip() {
        floatingPipView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        floatingPipView = null
        pipPreviewView = null
        pipOverlayView = null
    }

    private fun startCameraAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    gestureEngine?.processImageProxy(imageProxy)
                }

                // If PIP preview is available, bind preview
                val preview = pipPreviewView?.let { pv ->
                    Preview.Builder().build().also {
                        it.setSurfaceProvider(pv.surfaceProvider)
                    }
                }

                if (preview != null) {
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                } else {
                    cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
                }
                Log.i(TAG, "CameraX bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX lifecycle", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = Intent(this, GestureControlService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_running_title))
            .setContentText(getString(R.string.service_running_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.cursor_idle, getString(R.string.stop_service), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        cursorView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        cursorView = null

        removeFloatingPip()

        gestureEngine?.close()
        gestureEngine = null
        cameraExecutor.shutdown()

        _isServiceActive.value = false
        Log.i(TAG, "GestureControlService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GestureControlService"
        private const val CHANNEL_ID = "gesture_controller_service_channel"
        private const val NOTIFICATION_ID = 4041

        const val ACTION_STOP_SERVICE = "com.example.gesture.STOP_SERVICE"
        const val ACTION_TOGGLE_PIP = "com.example.gesture.TOGGLE_PIP"
        const val EXTRA_SHOW_PIP = "extra_show_pip"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _showPipWindow = MutableStateFlow(false)
        val showPipWindow: StateFlow<Boolean> = _showPipWindow.asStateFlow()

        private val _latestGestureState = MutableStateFlow<GestureEngine.GestureState?>(null)
        val latestGestureState: StateFlow<GestureEngine.GestureState?> = _latestGestureState.asStateFlow()

        fun start(context: Context, showPip: Boolean = false) {
            _showPipWindow.value = showPip
            val intent = Intent(context, GestureControlService::class.java).apply {
                action = ACTION_TOGGLE_PIP
                putExtra(EXTRA_SHOW_PIP, showPip)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GestureControlService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }

        fun togglePip(context: Context, showPip: Boolean) {
            _showPipWindow.value = showPip
            if (_isServiceActive.value) {
                val intent = Intent(context, GestureControlService::class.java).apply {
                    action = ACTION_TOGGLE_PIP
                    putExtra(EXTRA_SHOW_PIP, showPip)
                }
                context.startService(intent)
            }
        }
    }
}
