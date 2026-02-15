package com.caravanfire.calmqr.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caravanfire.calmqr.data.SavedCode
import com.caravanfire.calmqr.ui.Dimens
import com.caravanfire.calmqr.data.SavedCodeDao
import com.caravanfire.calmqr.rust.RustBridge
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldDefaultsMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/** Decode raw pixel data from RustBridge.generateBarcode into an Android Bitmap. */
private fun decodeQrPixelData(data: ByteArray): Bitmap? {
    if (data.size < 8) return null
    val buffer = ByteBuffer.wrap(data)
    val width = buffer.getInt()
    val height = buffer.getInt()
    if (width <= 0 || height <= 0) return null
    val expectedSize = 8 + width * height * 4
    if (data.size < expectedSize) return null
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val offset = 8 + i * 4
        val a = data[offset].toInt() and 0xFF
        val r = data[offset + 1].toInt() and 0xFF
        val g = data[offset + 2].toInt() and 0xFF
        val b = data[offset + 3].toInt() and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeDetailScreen(
    codeId: Long,
    savedCodeDao: SavedCodeDao,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onRequestEinkRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<SavedCode?>(null) }
    var editableName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // E-ink refresh when returning from another app, but not during delete confirmation
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var wasBackgrounded by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> wasBackgrounded = true
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    if (wasBackgrounded) {
                        wasBackgrounded = false
                        showDeleteConfirm = false
                        onRequestEinkRefresh()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(codeId) {
        val loaded = savedCodeDao.getCodeById(codeId)
        code = loaded
        editableName = loaded?.name ?: ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            Column {
            TopAppBarMMD(
                showDivider = false,
                title = {
                    TextFieldMMD(
                        value = editableName,
                        onValueChange = { editableName = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        colors = TextFieldDefaultsMMD.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth().offset(x = Dimens.titleOffset)
                    )
                },
                navigationIcon = {
                    Box(modifier = Modifier.padding(4.dp)) {
                        IconButton(onClick = {
                            if (showDeleteConfirm) {
                                showDeleteConfirm = false
                            } else {
                                scope.launch {
                                    savedCodeDao.updateName(codeId, editableName)
                                }
                                onBack()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            )
            HorizontalDivider(thickness = 3.dp)
            }
        }
    ) { innerPadding ->
        if (showDeleteConfirm) {
            // Delete confirmation content — top bar stays mounted
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                TextMMD(
                    text = "Are you sure?",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp)
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    ButtonMMD(
                        onClick = {
                            scope.launch {
                                savedCodeDao.getCodeById(codeId)?.let {
                                    savedCodeDao.deleteCode(it)
                                }
                                onDeleted()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextMMD(text = "Yes, Delete", style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                    }
                    Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                    OutlinedButtonMMD(
                        onClick = {
                            showDeleteConfirm = false
                            onRequestEinkRefresh()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextMMD(text = "Cancel", style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                    }
                    Spacer(modifier = Modifier.height(Dimens.bottomSpacing))
                }
            }
        } else {
        code?.let { savedCode ->
            val isUrl = savedCode.content.startsWith("http://") ||
                    savedCode.content.startsWith("https://")

            val is1D = savedCode.format in listOf("CODE_128", "CODE_39", "CODE_93", "EAN_13", "EAN_8", "UPC_A", "UPC_E", "ITF", "CODABAR")
            val qrBitmap = remember(savedCode.content, savedCode.format, savedCode.qrImageData) {
                val data = savedCode.qrImageData
                    ?: if (is1D) RustBridge.generateBarcode(savedCode.content, savedCode.format, 512, 200)
                       else RustBridge.generateBarcode(savedCode.content, savedCode.format, 512, 512)
                data?.let { decodeQrPixelData(it) }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                // QR/barcode centered
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(
                                    if (is1D) Dimens.barcodeMaxWidthFraction
                                    else Dimens.qrMaxWidthFraction
                                ),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                if (isUrl) {
                    Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                    ButtonMMD(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(savedCode.content))
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextMMD(text = "Open in Browser", style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                OutlinedButtonMMD(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(text = "Delete", style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
                Spacer(modifier = Modifier.height(Dimens.bottomSpacing))
            }
        }
        }
    }

    }
}
