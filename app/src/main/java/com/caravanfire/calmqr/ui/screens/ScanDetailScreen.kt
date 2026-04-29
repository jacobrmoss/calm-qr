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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.caravanfire.calmqr.data.SavedCode
import com.caravanfire.calmqr.ui.Dimens
import com.caravanfire.calmqr.data.SavedCodeDao
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.rust.RustBridge
import com.caravanfire.calmqr.wifi.WifiSaveResult
import com.caravanfire.calmqr.wifi.buildWifiSaveIntent
import com.caravanfire.calmqr.wifi.parseWifiSaveResult
import com.caravanfire.calmqr.wifi.isWifiQrCode
import com.caravanfire.calmqr.wifi.parseWifiQrCode
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.snackbar.SnackbarHostMMD
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
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
fun ScanDetailScreen(
    content: String,
    format: String,
    savedCodeDao: SavedCodeDao,
    onSaved: (Long) -> Unit,
    onRescan: () -> Unit,
    onCancel: () -> Unit,
    onRequestInfo: (currentName: String) -> Unit = {},
    savedStateHandle: SavedStateHandle? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editableName by remember { mutableStateOf("") }
    var hasBeenTouched by remember { mutableStateOf(false) }
    val untitledDefault = stringResource(R.string.untitled)

    LaunchedEffect(Unit) {
        editableName = untitledDefault
    }

    // If returning from ScanInfoScreen with an edited name, adopt it.
    // Note: savedStateHandle.get(...) is not a Compose-tracked read — the snapshot is
    // taken whenever the composable re-enters (which happens on pop back from ScanInfo).
    val pendingName = savedStateHandle?.get<String>(PENDING_NAME_KEY)
    LaunchedEffect(pendingName) {
        if (pendingName != null) {
            editableName = pendingName
            hasBeenTouched = true
            savedStateHandle.remove<String>(PENDING_NAME_KEY)
        }
    }

    val is1D = format in listOf("CODE_128", "CODE_39", "CODE_93", "EAN_13", "EAN_8", "UPC_A", "UPC_E", "ITF", "CODABAR", "TELEPEN")

    val qrData = remember(content, format) {
        if (is1D) RustBridge.generateBarcode(content, format, 512, 200)
        else RustBridge.generateBarcode(content, format, 512, 512)
    }
    val qrBitmap = remember(qrData) { qrData?.let { decodeQrPixelData(it) } }

    val isUrl = content.startsWith("http://") || content.startsWith("https://")
    val isWifi = isWifiQrCode(content)
    val snackbarHostState = remember { SnackbarHostStateMMD() }

    // Track the SSID for the snackbar message after the system dialog returns
    var pendingWifiSsid by remember { mutableStateOf<String?>(null) }
    val wifiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val ssid = pendingWifiSsid ?: return@rememberLauncherForActivityResult
        val result = parseWifiSaveResult(activityResult.resultCode, activityResult.data)
        scope.launch {
            snackbarHostState.showSnackbar(
                when (result) {
                    WifiSaveResult.SAVED -> context.getString(R.string.wifi_saved, ssid)
                    WifiSaveResult.ALREADY_SAVED -> context.getString(R.string.wifi_already_saved, ssid)
                    WifiSaveResult.FAILED -> context.getString(R.string.wifi_save_failed)
                }
            )
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHostMMD(hostState = snackbarHostState)
        },
        topBar = {
            Column {
                TopAppBarMMD(
                    showDivider = false,
                    title = {
                        TextFieldMMD(
                            value = editableName,
                            onValueChange = { editableName = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            colors = TextFieldDefaultsMMD.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(x = Dimens.titleOffset)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused && !hasBeenTouched) {
                                        hasBeenTouched = true
                                        editableName = ""
                                    }
                                }
                        )
                    },
                    navigationIcon = {
                        Box(modifier = Modifier.padding(4.dp)) {
                            IconButton(onClick = onCancel) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { onRequestInfo(editableName) }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.info_action_info),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                )
                HorizontalDivider(thickness = 4.dp)
            }
        }
    ) { innerPadding ->
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (is1D) {
                            TextMMD(
                                text = stringResource(R.string.barcode_verify_warning),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.qr_code_image),
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(
                                        if (is1D) 0.7f
                                        else 0.5f
                                    ),
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.None
                            )
                        }
                    }
                }

            Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
            ButtonMMD(
                onClick = {
                    scope.launch {
                        val id = savedCodeDao.insertCode(
                            SavedCode(
                                name = editableName,
                                content = content,
                                format = format,
                                qrImageData = qrData
                            )
                        )
                        onSaved(id)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextMMD(text = stringResource(R.string.save), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
            }

            if (is1D) {
                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                OutlinedButtonMMD(
                    onClick = onRescan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(text = stringResource(R.string.re_scan), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
            }

            if (isUrl) {
                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                ButtonMMD(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(content))
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(text = stringResource(R.string.open_in_browser), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
            }

            if (isWifi) {
                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                ButtonMMD(
                    onClick = {
                        parseWifiQrCode(content)?.let { creds ->
                            val intent = buildWifiSaveIntent(creds)
                            if (intent != null) {
                                pendingWifiSsid = creds.ssid
                                wifiLauncher.launch(intent)
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.wifi_save_failed)
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(text = stringResource(R.string.save_wifi), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
            }

            Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
            OutlinedButtonMMD(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextMMD(text = stringResource(R.string.cancel), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
            }
            Spacer(modifier = Modifier.height(Dimens.bottomSpacing))
        }
    }
}
