package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                GestureAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureAppScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe service states
    val isServiceActive by GestureControlService.isServiceActive.collectAsStateWithLifecycle()
    val isA11yConnected by GestureAccessibilityService.isConnected.collectAsStateWithLifecycle()
    val latestGesture by GestureControlService.latestGestureState.collectAsStateWithLifecycle()

    // Permission checks
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    // Permission launcher for Camera
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Notification launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Refresh permissions when resuming
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showPipBubble by remember { mutableStateOf(false) }

    // Tuning parameters
    var smoothingAlpha by remember { mutableFloatStateOf(0.45f) }
    var pinchThreshold by remember { mutableFloatStateOf(0.075f) }

    // Test playground click counter
    var testClickCount by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isServiceActive) Color(0xFF00E5FF) else Color(0xFF64748B))
                        )
                        Text(
                            text = "Gesture Control",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Dashboard") },
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = "Dashboard") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Live Test") },
                    icon = { Icon(Icons.Default.Videocam, contentDescription = "Live Test") }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("Guide") },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Guide") }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        item {
                            HeroServiceCard(
                                isServiceActive = isServiceActive,
                                hasCamera = hasCameraPermission,
                                hasOverlay = hasOverlayPermission,
                                isA11y = isA11yConnected,
                                onToggleService = {
                                    if (isServiceActive) {
                                        GestureControlService.stop(context)
                                    } else {
                                        if (!hasCameraPermission) {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        } else if (!hasOverlayPermission) {
                                            openOverlaySettings(context)
                                        } else {
                                            GestureControlService.start(context, showPipBubble)
                                        }
                                    }
                                }
                            )
                        }

                        item {
                            PermissionsCard(
                                hasCamera = hasCameraPermission,
                                hasOverlay = hasOverlayPermission,
                                isA11y = isA11yConnected,
                                onRequestCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                onRequestOverlay = { openOverlaySettings(context) },
                                onRequestA11y = { openAccessibilitySettings(context) }
                            )
                        }

                        item {
                            QuickSettingsCard(
                                showPip = showPipBubble,
                                onTogglePip = {
                                    showPipBubble = it
                                    GestureControlService.togglePip(context, it)
                                },
                                smoothing = smoothingAlpha,
                                onSmoothingChange = { smoothingAlpha = it },
                                pinchThreshold = pinchThreshold,
                                onPinchThresholdChange = { pinchThreshold = it }
                            )
                        }
                    }

                    1 -> {
                        item {
                            LivePlaygroundCard(
                                hasCamera = hasCameraPermission,
                                onRequestCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                testClickCount = testClickCount,
                                onTestClick = { testClickCount++ },
                                latestGesture = latestGesture
                            )
                        }
                    }

                    2 -> {
                        item {
                            GestureGuideSection()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeroServiceCard(
    isServiceActive: Boolean,
    hasCamera: Boolean,
    hasOverlay: Boolean,
    isA11y: Boolean,
    onToggleService: () -> Unit
) {
    val gradient = if (isServiceActive) {
        Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF0284C7)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF334155), Color(0xFF1E293B)))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(gradient)
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isServiceActive) "Controller Active" else "Controller Inactive",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (isServiceActive) "Move your hand in front of camera to control cursor" else "Tap start to enable touch-free gestures",
                            fontSize = 13.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (isServiceActive) Color(0xFF00E5FF) else Color(0xFF94A3B8))
                    )
                }

                Button(
                    onClick = onToggleService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("toggle_service_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceActive) Color(0xFFEF4444) else Color(0xFF00E5FF),
                        contentColor = if (isServiceActive) Color.White else Color(0xFF0F172A)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isServiceActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceActive) "Stop Gesture Service" else "Start Gesture Controller",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionsCard(
    hasCamera: Boolean,
    hasOverlay: Boolean,
    isA11y: Boolean,
    onRequestCamera: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestA11y: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Required Permissions",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PermissionRowItem(
                title = "Camera Access",
                description = "Tracks hand gestures & landmarks",
                icon = Icons.Default.CameraAlt,
                isGranted = hasCamera,
                buttonLabel = "Allow",
                onClick = onRequestCamera
            )

            PermissionRowItem(
                title = "Draw Over Other Apps",
                description = "Shows the floating cursor on screen",
                icon = Icons.Default.Layers,
                isGranted = hasOverlay,
                buttonLabel = "Enable",
                onClick = onRequestOverlay
            )

            PermissionRowItem(
                title = "Accessibility Service",
                description = "Performs clicks, taps & scrolls for you",
                icon = Icons.Default.AccessibilityNew,
                isGranted = isA11y,
                buttonLabel = "Settings",
                onClick = onRequestA11y
            )
        }
    }
}

@Composable
fun PermissionRowItem(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGranted) Color(0xFF10B981).copy(alpha = 0.15f)
                        else Color(0xFFF59E0B).copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = Color(0xFF10B981),
                modifier = Modifier.size(22.dp)
            )
        } else {
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.testTag("grant_permission_${title.lowercase().replace(" ", "_")}"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(text = buttonLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun QuickSettingsCard(
    showPip: Boolean,
    onTogglePip: (Boolean) -> Unit,
    smoothing: Float,
    onSmoothingChange: (Float) -> Unit,
    pinchThreshold: Float,
    onPinchThresholdChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Preferences & Tuning",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Floating Camera Bubble", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(
                        text = "Show mini video bubble on top of apps",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showPip,
                    onCheckedChange = onTogglePip,
                    modifier = Modifier.testTag("toggle_pip_switch")
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Motion Smoothing", fontSize = 13.sp)
                    Text(text = "${(smoothing * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = smoothing,
                    onValueChange = onSmoothingChange,
                    valueRange = 0.1f..0.9f
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Pinch Sensitivity Threshold", fontSize = 13.sp)
                    Text(text = "${(pinchThreshold * 1000).toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = pinchThreshold,
                    onValueChange = onPinchThresholdChange,
                    valueRange = 0.04f..0.12f
                )
            }
        }
    }
}

@Composable
fun LivePlaygroundCard(
    hasCamera: Boolean,
    onRequestCamera: () -> Unit,
    testClickCount: Int,
    onTestClick: () -> Unit,
    latestGesture: GestureEngine.GestureState?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Live Camera Playground",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = "Hold your hand up to the front camera to test cursor tracking and pinch click detection in real-time.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasCamera) {
                Button(
                    onClick = onRequestCamera,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Camera Permission to Test")
                }
            } else {
                // Interactive Camera and Landmark Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val container = FrameLayout(ctx)
                            val previewView = PreviewView(ctx)
                            val overlayView = LandmarkOverlayView(ctx)

                            container.addView(previewView)
                            container.addView(overlayView)

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            val executor = Executors.newSingleThreadExecutor()

                            val engine = GestureEngine(ctx, object : GestureEngine.GestureListener {
                                override fun onGestureUpdate(state: GestureEngine.GestureState) {
                                    overlayView.post {
                                        overlayView.updateResults(
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
                                    // Test click action
                                    if (action == GestureEngine.GestureType.PINCH_CLICK) {
                                        onTestClick()
                                    }
                                }

                                override fun onError(message: String) {}
                            })

                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    cameraProvider.unbindAll()

                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                        .build()

                                    imageAnalysis.setAnalyzer(executor) { proxy ->
                                        engine.processImageProxy(proxy)
                                    }

                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_FRONT_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            container
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Status info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Detected Gesture:",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = latestGesture?.gestureType?.name ?: "READY (LOOKING FOR HAND)",
                                fontWeight = FontWeight.Bold,
                                color = if (latestGesture?.isPinching == true) Color(0xFFF43F5E) else Color(0xFF00E5FF),
                                fontSize = 14.sp
                            )
                        }

                        if (latestGesture != null) {
                            Text(
                                text = "Cursor: X ${(latestGesture.cursorNormX * 100).toInt()}% | Y ${(latestGesture.cursorNormY * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Interactive target button to verify clicks
                Button(
                    onClick = onTestClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_click_target"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color(0xFF0F172A)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Touch or Pinch to Click Target! (Score: $testClickCount)",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun GestureGuideSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Supported Gestures",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        GestureGuideCard(
            title = "Pointer Tracking",
            gestureDescription = "Point your index finger up towards the front camera. The floating cursor on screen follows your fingertip smoothly.",
            gestureTag = "POINT"
        )

        GestureGuideCard(
            title = "Pinch to Click",
            gestureDescription = "Bring your thumb tip and index fingertip together like a pinch. The cursor turns red and executes a touch click at that exact point.",
            gestureTag = "PINCH"
        )

        GestureGuideCard(
            title = "V-Sign (Peace) to Go Back",
            gestureDescription = "Extend your index and middle fingers in a V-sign while folding ring and pinky fingers. Triggers the Android system BACK button.",
            gestureTag = "V-SIGN"
        )

        GestureGuideCard(
            title = "Quick Swipe to Scroll",
            gestureDescription = "Swiftly flick your hand upwards or downwards in front of the camera to scroll lists and web pages without touching the screen.",
            gestureTag = "SWIPE"
        )
    }
}

@Composable
fun GestureGuideCard(
    title: String,
    gestureDescription: String,
    gestureTag: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = gestureTag,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF00E5FF)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = gestureDescription,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun openOverlaySettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    } catch (e: Exception) {
        val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        context.startActivity(fallbackIntent)
    }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
