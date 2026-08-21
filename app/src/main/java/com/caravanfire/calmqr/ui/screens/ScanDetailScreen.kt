package com.caravanfire.calmqr.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caravanfire.calmqr.data.SavedCode
import com.caravanfire.calmqr.data.SavedCodeDao
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.ui.Dimens
import com.caravanfire.calmqr.ui.barcodePixelData
import com.caravanfire.calmqr.ui.decodeQrPixelData
import com.caravanfire.calmqr.ui.is1DFormat
import com.caravanfire.calmqr.wifi.WifiSaveResult
import com.caravanfire.calmqr.vcard.buildContactInsertIntent
import com.caravanfire.calmqr.vcard.isVCardQrCode
import com.caravanfire.calmqr.vcard.parseVCard
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
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Post-scan review screen. The scan is already a database row (inserted by the
 * scanner flow with isSaved = false); Save marks it saved, Cancel/Re-scan
 * delete it. Loads by [codeId] — scanned content never travels through
 * navigation (see Screen.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDetailScreen(
    codeId: Long,
    savedCodeDao: SavedCodeDao,
    onSaved: () -> Unit,
    onRescan: () -> Unit,
    onCancel: () -> Unit,
    onRequestInfo: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<SavedCode?>(null) }
    var editableName by remember { mutableStateOf("") }
    var hasBeenTouched by remember { mutableStateOf(false) }
    val untitledDefault = stringResource(R.string.untitled)

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(codeId) {
        val row = savedCodeDao.getCodeById(codeId)
        if (row == null) {
            // Row already deleted (e.g. stale restore after the unsaved sweep)
            onCancel()
            return@LaunchedEffect
        }
        // Decode the code image off the main thread, then publish it together
        // with the row so the screen fills in with one paint (one e-ink refresh)
        val bitmap = withContext(Dispatchers.Default) {
            (row.qrImageData ?: barcodePixelData(row.content, row.format))
                ?.let { decodeQrPixelData(it) }
        }
        qrBitmap = bitmap
        code = row
        editableName = row.name
        // First focus clears the placeholder, but only while the name is untouched
        hasBeenTouched = row.name != untitledDefault
    }

    val loaded = code ?: return

    val is1D = is1DFormat(loaded.format)

    val isUrl = loaded.content.startsWith("http://") || loaded.content.startsWith("https://")
    val isWifi = isWifiQrCode(loaded.content)
    val isVcard = isVCardQrCode(loaded.content)
    val snackbarHostState = remember { SnackbarHostStateMMD() }

    fun discardAnd(next: () -> Unit) {
        scope.launch {
            savedCodeDao.deleteCode(loaded)
            next()
        }
    }

    // System back must not skip the discard — leaving this screen any way other
    // than Save deletes the pending row.
    BackHandler { discardAnd(onCancel) }

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
                                .layout { measurable, constraints ->
                                    val extra = 21.dp.roundToPx()
                                    val newMaxWidth = constraints.maxWidth + extra
                                    val placeable = measurable.measure(
                                        constraints.copy(
                                            minWidth = newMaxWidth,
                                            maxWidth = newMaxWidth,
                                        ),
                                    )
                                    layout(constraints.maxWidth, placeable.height) {
                                        placeable.placeRelative(0, 0)
                                    }
                                }
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
                            IconButton(onClick = { discardAnd(onCancel) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    savedCodeDao.updateName(codeId, editableName)
                                    onRequestInfo(codeId)
                                }
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
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
                        savedCodeDao.markSaved(codeId, editableName)
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextMMD(text = stringResource(R.string.save), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
            }

            if (is1D) {
                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                OutlinedButtonMMD(
                    onClick = { discardAnd(onRescan) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(text = stringResource(R.string.re_scan), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
            }

            if (isUrl) {
                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                ButtonMMD(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(loaded.content))
                            )
                        } catch (_: Exception) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.no_browser_available)
                                )
                            }
                        }
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
                        parseWifiQrCode(loaded.content)?.let { creds ->
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

            if (isVcard) {
                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                ButtonMMD(
                    onClick = {
                        val contact = parseVCard(loaded.content)
                        if (contact != null) {
                            try {
                                context.startActivity(buildContactInsertIntent(contact))
                            } catch (_: Exception) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.contact_save_failed)
                                    )
                                }
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.contact_save_failed)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(text = stringResource(R.string.save_to_contacts), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
            }

            Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
            OutlinedButtonMMD(
                onClick = { discardAnd(onCancel) },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextMMD(text = stringResource(R.string.cancel), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
            }
            Spacer(modifier = Modifier.height(Dimens.bottomSpacing))
        }
    }
}
