package com.caravanfire.calmqr.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size as AndroidSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.caravanfire.calmqr.rust.RustBridge
import com.caravanfire.calmqr.ui.Dimens
import com.caravanfire.calmqr.R
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldDefaultsMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onCodeScanned: (content: String, format: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var focusMode by remember { mutableStateOf(false) }

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
                // Disabled for now — scanner overlay focus mode hidden from users
                // actions = {
                //     IconButton(onClick = { focusMode = !focusMode }) {
                //         Icon(
                //             imageVector = if (focusMode) Icons.Filled.CenterFocusStrong
                //                           else Icons.Filled.CenterFocusWeak,
                //             contentDescription = if (focusMode) "Disable focus mode" else "Enable focus mode",
                //             modifier = Modifier.size(32.dp)
                //         )
                //     }
                // }
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
                CameraPreview(onCodeScanned = onCodeScanned, focusMode = focusMode)
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

private data class ScannedCode(val content: String, val format: String)

@Composable
private fun CameraPreview(
    onCodeScanned: (content: String, format: String) -> Unit,
    focusMode: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasScanned by remember { mutableStateOf(false) }
    var scannedCode by remember { mutableStateOf<ScannedCode?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Handle navigation on the main thread when a code is scanned
    LaunchedEffect(scannedCode) {
        scannedCode?.let { code ->
            onCodeScanned(code.content, code.format)
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
                val previewView = PreviewView(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                AndroidSize(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        if (!hasScanned) {
                            processImage(imageProxy, focusMode) { content, format ->
                                hasScanned = true
                                scannedCode = ScannedCode(content, format)
                            }
                        }
                        imageProxy.close()
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Focus mode overlay: darken everything except center square
        if (focusMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val squareSize = size.minDimension * 0.6f
                val left = (size.width - squareSize) / 2f
                val top = (size.height - squareSize) / 2f

                val cutoutPath = Path().apply {
                    addRect(
                        androidx.compose.ui.geometry.Rect(
                            offset = Offset(left, top),
                            size = Size(squareSize, squareSize)
                        )
                    )
                }

                clipPath(cutoutPath, clipOp = ClipOp.Difference) {
                    drawRect(color = Color.Black.copy(alpha = 0.6f))
                }

                // Draw border around the cutout
                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(squareSize, squareSize),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                )
            }
        }
    }
}

private fun processImage(
    imageProxy: ImageProxy,
    focusMode: Boolean,
    onDecoded: (content: String, format: String) -> Unit
) {
    val plane = imageProxy.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = imageProxy.width
    val height = imageProxy.height

    val fullLuma: ByteArray
    if (rowStride == width) {
        fullLuma = ByteArray(width * height)
        buffer.rewind()
        buffer.get(fullLuma)
    } else {
        fullLuma = ByteArray(width * height)
        buffer.rewind()
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(fullLuma, row * width, width)
        }
    }

    if (focusMode) {
        // Crop center 60% square (matching the overlay)
        val squareSize = minOf(width, height) * 6 / 10
        val cropX = (width - squareSize) / 2
        val cropY = (height - squareSize) / 2
        val croppedLuma = ByteArray(squareSize * squareSize)
        for (row in 0 until squareSize) {
            System.arraycopy(
                fullLuma, (cropY + row) * width + cropX,
                croppedLuma, row * squareSize,
                squareSize
            )
        }
        val result = RustBridge.decodeBarcode(croppedLuma, squareSize, squareSize, false)
        if (result != null) {
            onDecoded(result.text, result.format)
        }
    } else {
        val result = RustBridge.decodeBarcode(fullLuma, width, height, false)
        if (result != null) {
            onDecoded(result.text, result.format)
        }
    }
}
