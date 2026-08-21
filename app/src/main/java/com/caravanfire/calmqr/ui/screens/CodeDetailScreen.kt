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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.caravanfire.calmqr.data.SavedCode
import com.caravanfire.calmqr.ui.Dimens
import com.caravanfire.calmqr.ui.barcodePixelData
import com.caravanfire.calmqr.ui.decodeQrPixelData
import com.caravanfire.calmqr.ui.is1DFormat
import com.caravanfire.calmqr.data.SavedCodeDao
import com.caravanfire.calmqr.R
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeDetailScreen(
    codeId: Long,
    savedCodeDao: SavedCodeDao,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onRequestEinkRefresh: () -> Unit = {},
    onRequestInfo: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<SavedCode?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editableName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
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
        // Decode the code image off the main thread, then publish it together
        // with the row so the screen fills in with one paint (one e-ink refresh)
        qrBitmap = loaded?.let { row ->
            withContext(Dispatchers.Default) {
                (row.qrImageData ?: barcodePixelData(row.content, row.format))
                    ?.let { decodeQrPixelData(it) }
            }
        }
        code = loaded
        editableName = loaded?.name ?: ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
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
                            .offset(x = Dimens.titleOffset),
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
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (code != null) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    savedCodeDao.updateName(codeId, editableName)
                                }
                                onRequestInfo()
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
                    text = stringResource(R.string.are_you_sure),
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
                        TextMMD(text = stringResource(R.string.yes_delete), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                    }
                    Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                    OutlinedButtonMMD(
                        onClick = {
                            showDeleteConfirm = false
                            onRequestEinkRefresh()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextMMD(text = stringResource(R.string.cancel), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                    }
                    Spacer(modifier = Modifier.height(Dimens.bottomSpacing))
                }
            }
        } else {
        code?.let { savedCode ->
            val isUrl = savedCode.content.startsWith("http://") ||
                    savedCode.content.startsWith("https://")
            val isWifi = isWifiQrCode(savedCode.content)
            val isVcard = isVCardQrCode(savedCode.content)

            val is1D = is1DFormat(savedCode.format)

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
                            contentDescription = stringResource(R.string.qr_code_image),
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
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(savedCode.content))
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
                            parseWifiQrCode(savedCode.content)?.let { creds ->
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
                            val contact = parseVCard(savedCode.content)
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
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(text = stringResource(R.string.delete), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
                Spacer(modifier = Modifier.height(Dimens.bottomSpacing))
            }
        }
        }
    }

    }
}
