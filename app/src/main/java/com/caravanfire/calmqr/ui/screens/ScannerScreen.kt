package com.caravanfire.calmqr.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.rust.RustBridge
import com.caravanfire.calmqr.ui.Dimens
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldDefaultsMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onCodeScanned: (content: String, format: String, qrGrid: String?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var flashlightOn by remember { mutableStateOf(false) }
    var viewfinderEnabled by rememberSaveable { mutableStateOf(false) }
    var exactMatchEnabled by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            Column {
            TopAppBarMMD(
                showDivider = false,
                title = {
                    TextFieldMMD(
                        value = stringResource(R.string.scanner_title),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                        colors = TextFieldDefaultsMMD.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(x = Dimens.titleOffset)
                    )
                },
                navigationIcon = {
                    Box(modifier = Modifier.padding(4.dp)) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { exactMatchEnabled = !exactMatchEnabled }) {
                        Icon(
                            painter = painterResource(
                                if (exactMatchEnabled) R.drawable.ic_equal_filled
                                else R.drawable.ic_equal_outlined
                            ),
                            contentDescription = if (exactMatchEnabled)
                                stringResource(R.string.disable_exact_match)
                            else
                                stringResource(R.string.enable_exact_match),
                            // Unspecified: the drawables are two-tone (knocked-out
                            // equals in the filled disc); tinting would flatten them
                            tint = Color.Unspecified,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = { viewfinderEnabled = !viewfinderEnabled }) {
                        Icon(
                            imageVector = if (viewfinderEnabled)
                                Icons.Filled.CenterFocusStrong
                            else
                                Icons.Filled.CropFree,
                            contentDescription = if (viewfinderEnabled)
                                stringResource(R.string.disable_viewfinder)
                            else
                                stringResource(R.string.enable_viewfinder),
                            tint = if (viewfinderEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                LocalContentColor.current,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = {
                        val newState = !flashlightOn
                        flashlightOn = newState
                        camera?.cameraControl?.enableTorch(newState)
                    }) {
                        Icon(
                            imageVector = if (flashlightOn) Icons.Filled.FlashlightOn
                                          else Icons.Filled.FlashlightOff,
                            contentDescription = if (flashlightOn) stringResource(R.string.disable_flashlight)
                                                 else stringResource(R.string.enable_flashlight),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            )
            HorizontalDivider(thickness = 3.dp)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (hasCameraPermission) {
                CameraPreview(
                    viewfinderEnabled = viewfinderEnabled,
                    exactMatchEnabled = exactMatchEnabled,
                    onCodeScanned = onCodeScanned,
                    onCameraBound = { camera = it }
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextMMD(
                        text = stringResource(R.string.camera_permission_required),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ButtonMMD(onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        TextMMD(text = stringResource(R.string.grant_permission))
                    }
                }
            }
        }
    }
}

private data class ScannedCode(val content: String, val format: String, val qrGrid: String?)

private data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

@Composable
private fun CameraPreview(
    viewfinderEnabled: Boolean,
    exactMatchEnabled: Boolean,
    onCodeScanned: (content: String, format: String, qrGrid: String?) -> Unit,
    onCameraBound: (Camera) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasScanned by remember { mutableStateOf(false) }
    var scannedCode by remember { mutableStateOf<ScannedCode?>(null) }
    var localCamera by remember { mutableStateOf<Camera?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableStateOf(0) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val manualTapRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val viewfinderEnabledRef = remember { java.util.concurrent.atomic.AtomicBoolean(viewfinderEnabled) }
    LaunchedEffect(viewfinderEnabled) {
        viewfinderEnabledRef.set(viewfinderEnabled)
    }
    val exactMatchRef = remember { java.util.concurrent.atomic.AtomicBoolean(exactMatchEnabled) }
    LaunchedEffect(exactMatchEnabled) {
        exactMatchRef.set(exactMatchEnabled)
    }

    // Fresh native engine session per scanner entry: per-scan state (holds,
    // votes, aim-lock, tap override) clears; score calibration persists.
    remember { RustBridge.engineReset(exactMatchEnabled) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val streamState by previewView.previewStreamState.observeAsState(PreviewView.StreamState.IDLE)
    val isCameraStreaming = streamState == PreviewView.StreamState.STREAMING

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(scannedCode) {
        scannedCode?.let { code ->
            onCodeScanned(code.content, code.format, code.qrGrid)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                cameraProviderFuture.addListener({
                    // The screen can be left before the provider future resolves
                    // (fast back press on first open); binding to a destroyed
                    // lifecycle throws.
                    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                        return@addListener
                    }
                    val cameraProvider = cameraProviderFuture.get()

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setAllowedResolutionMode(
                            ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                        )
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build().also { p ->
                            p.setSurfaceProvider(previewView.surfaceProvider)
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    // Every frame goes straight into the Rust engine, which owns
                    // scoring, scheduling, decoding, holds, votes, nudge policy
                    // and telemetry. Kotlin acts on the returned events: execute
                    // a focus nudge (camera control cannot cross the JNI line)
                    // and finish the scan.
                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        if (!hasScanned) {
                            val plane = imageProxy.planes[0]
                            val w = imageProxy.width
                            val h = imageProxy.height
                            val crop = if (viewfinderEnabledRef.get()) {
                                val side = (kotlin.math.min(w, h) * 0.7f).toInt()
                                CropRect((w - side) / 2, (h - side) / 2, side, side)
                            } else {
                                CropRect(0, 0, 0, 0)
                            }
                            val out = RustBridge.engineSubmitFrame(
                                plane.buffer, plane.rowStride, w, h,
                                crop.left, crop.top, crop.width, crop.height,
                                exactMatchRef.get(), manualTapRef.get()
                            )
                            if (out != null) {
                                if (out.nudgeX >= 0f) {
                                    localCamera?.cameraControl?.startFocusAndMetering(
                                        FocusMeteringAction.Builder(
                                            SurfaceOrientedMeteringPointFactory(1f, 1f, imageAnalysis)
                                                .createPoint(out.nudgeX, out.nudgeY)
                                        ).build()
                                    )
                                }
                                out.result?.let { r ->
                                    hasScanned = true
                                    scannedCode = ScannedCode(r.text, r.format, r.qrGrid)
                                }
                            }
                        }
                        imageProxy.close()
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    cameraProvider.unbindAll()
                    val cam = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    localCamera = cam
                    onCameraBound(cam)
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(localCamera) {
                    detectTapGestures { offset ->
                        val cam = localCamera ?: return@detectTapGestures
                        // Manual focus takes over: the engine stops auto-nudging
                        // for the rest of this scan
                        manualTapRef.set(true)
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        cam.cameraControl.cancelFocusAndMetering()
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).build()
                        )
                        focusPoint = offset
                        focusTick++
                    }
                }
                .pointerInput(localCamera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = localCamera ?: return@detectTransformGestures
                        val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val current = state.zoomRatio
                        val next = (current * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                        cam.cameraControl.setZoomRatio(next)
                    }
                }
        )

        if (viewfinderEnabled) {
            ViewfinderBox()
        }

        FocusRing(position = focusPoint, tick = focusTick, onFinished = { focusPoint = null })

        if (!isCameraStreaming) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicatorMMD()
                    Spacer(modifier = Modifier.height(16.dp))
                    TextMMD(text = stringResource(R.string.initializing_camera))
                }
            }
        }
    }
}
