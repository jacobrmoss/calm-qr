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
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.mudita.mmd.components.snackbar.SnackbarDataMMD
import com.mudita.mmd.components.snackbar.SnackbarDefaultsMMD
import com.mudita.mmd.components.snackbar.SnackbarDurationMMD
import com.mudita.mmd.components.snackbar.SnackbarHostMMD
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
import com.mudita.mmd.components.snackbar.SnackbarMMD
import com.mudita.mmd.components.snackbar.SnackbarVisualsMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldDefaultsMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * @param startInUpload open in Image Upload mode (Re-Scan after a scan that
 *        came from a gallery image) instead of the camera.
 * @param onCodeScanned a finished scan; `fromUpload` says which mode found it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    startInUpload: Boolean = false,
    onCodeScanned: (content: String, format: String, qrGrid: String?, fromUpload: Boolean) -> Unit,
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
    // Image Upload: scan a photo from the gallery instead of the camera. The
    // camera preview leaves composition (and unbinds) while this is on.
    var uploadMode by rememberSaveable { mutableStateOf(startInUpload) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(uploadMode) {
        if (!uploadMode && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Image Upload's notices, "No code detected" after a miss, "Scan
    // cancelled" after a cancel and "Scan cancelled. New scan started." after
    // a switch to another photo: the MMD snackbar with its dismiss (x) action,
    // up for 15 s. SnackbarDurationMMD tops out at 10 s (Long), so a notice is
    // shown Indefinite and the timeout cancels it.
    val snackbarHostState = remember { SnackbarHostStateMMD() }
    val snackbarScope = rememberCoroutineScope()
    var snackbarJob by remember { mutableStateOf<Job?>(null) }
    val noCodeNotice = ScanNotice(stringResource(R.string.no_code_detected), Icons.Outlined.ErrorOutline)
    val scanCancelledNotice = ScanNotice(stringResource(R.string.scan_cancelled), Icons.Outlined.Cancel)
    val scanSwitchedNotice = ScanNotice(stringResource(R.string.scan_cancelled_new_started), Icons.Outlined.Cancel)

    fun hideScanNotice() {
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    fun showScanNotice(notice: ScanNotice) {
        hideScanNotice()
        snackbarJob = snackbarScope.launch {
            withTimeoutOrNull(SCAN_NOTICE_MILLIS) {
                snackbarHostState.showSnackbar(notice)
            }
        }
    }

    fun setUploadMode(on: Boolean) {
        if (on) {
            // The camera unbinds with its preview, which drops the torch.
            flashlightOn = false
            camera = null
        } else {
            hideScanNotice()
        }
        uploadMode = on
    }

    Scaffold(
        snackbarHost = {
            SnackbarHostMMD(hostState = snackbarHostState) { data -> ScanNoticeSnackbar(data) }
        },
        topBar = {
            Column {
            TopAppBarMMD(
                showDivider = false,
                title = {
                    val cameraTitle = stringResource(R.string.scanner_title)
                    val imageTitle = stringResource(R.string.image_upload_title)
                    val fullSizeStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal)
                    val textMeasurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // The two titles are drawn at one size: the standard
                        // title size if each clears the icon slots that share
                        // its row (three in camera mode, one in Image Scan),
                        // else as much smaller as the tighter one needs. So a
                        // title never runs under an icon, whatever the
                        // language, and does not change size with the mode.
                        val titleStyle = remember(cameraTitle, imageTitle, fullSizeStyle, maxWidth, density) {
                            fun fit(title: String, iconSlots: Int): TitleFit {
                                val width = textMeasurer
                                    .measure(title, fullSizeStyle, softWrap = false, maxLines = 1)
                                    .size.width
                                val room = with(density) {
                                    (maxWidth - TOOLBAR_BUTTON * iconSlots - TITLE_ICON_GAP - TITLE_TEXT_START).toPx()
                                }
                                return TitleFit(width.toFloat(), room)
                            }
                            val scale = titleScale(listOf(fit(cameraTitle, 3), fit(imageTitle, 1)))
                            fullSizeStyle.copy(fontSize = fullSizeStyle.fontSize * scale)
                        }
                        TextFieldMMD(
                            value = if (uploadMode) imageTitle else cameraTitle,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            textStyle = titleStyle,
                            colors = TextFieldDefaultsMMD.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .shiftStartKeepingEnd(Dimens.titleOffset)
                        )
                        // Three icon slots grouped close together at the end of the
                        // title area, against the rule that sets off the mode
                        // toggle (both in the actions slot; the toggle sits where
                        // Home's "+" does). Camera mode: exact match, viewfinder,
                        // flashlight. Image Upload: exact match alone, in the
                        // flashlight's slot. 40dp buttons + 32dp icons, like every
                        // other toolbar.
                        fun slot(i: Int, nudge: Dp = 0.dp): Modifier {
                            // Adjacent 40dp buttons, like Home's toolbar: 8dp between icons.
                            val fromEnd = TOOLBAR_BUTTON * (3 - i)
                            return Modifier
                                .offset(x = (maxWidth - fromEnd).coerceAtLeast(0.dp) + nudge)
                                .size(TOOLBAR_BUTTON)
                        }
                        run {
                            IconButton(
                                onClick = { exactMatchEnabled = !exactMatchEnabled },
                                // In the flashlight's slot the ring is nudged so its
                                // edge lands where the flashlight's does (see the
                                // constant): same gap to the rule in both modes.
                                modifier = if (uploadMode) slot(2, EQUALS_IN_FLASHLIGHT_SLOT_NUDGE)
                                           else slot(0)
                            ) {
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
                            // Viewfinder crop and torch only mean something to the camera.
                            if (!uploadMode) {
                                IconButton(
                                    onClick = { viewfinderEnabled = !viewfinderEnabled },
                                    modifier = slot(1)
                                ) {
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
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                // Torch: outline glyph when off, solid when on
                                // (fill is the state cue on e-ink).
                                IconButton(
                                    onClick = {
                                        val newState = !flashlightOn
                                        flashlightOn = newState
                                        camera?.cameraControl?.enableTorch(newState)
                                    },
                                    modifier = slot(2)
                                ) {
                                    Icon(
                                        imageVector = if (flashlightOn) Icons.Filled.FlashlightOn
                                                      else Icons.Outlined.FlashlightOff,
                                        contentDescription = if (flashlightOn) stringResource(R.string.disable_flashlight)
                                                             else stringResource(R.string.enable_flashlight),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    Box(modifier = Modifier.padding(4.dp)) {
                        // Back always leaves the Scanner, from either mode.
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
                    // A thin rule marks the toggle as different from the icons
                    // beside it: it switches the whole screen's mode.
                    Box(
                        modifier = Modifier
                            .padding(start = TOOLBAR_RULE_START_PADDING, end = TOOLBAR_RULE_END_PADDING)
                            .size(width = TOOLBAR_RULE_WIDTH, height = TOOLBAR_RULE_HEIGHT)
                            .background(Color.Black)
                    )
                    // Far right, pinned in the exact spot of Home's "+" and in its
                    // look (black square, white lines): camera <-> Image Upload.
                    IconButton(
                        onClick = { setUploadMode(!uploadMode) },
                        modifier = Modifier.size(TOOLBAR_BUTTON)
                    ) {
                        BlackSquareIcon(
                            painter = painterResource(
                                if (uploadMode) R.drawable.ic_camera_glyph
                                else R.drawable.ic_image_upload_glyph
                            ),
                            contentDescription = if (uploadMode) stringResource(R.string.back_to_camera)
                                                 else stringResource(R.string.upload_image)
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
            if (uploadMode) {
                ImageUploadPane(
                    exactMatchEnabled = exactMatchEnabled,
                    onCodeScanned = { content, format, qrGrid ->
                        onCodeScanned(content, format, qrGrid, true)
                    },
                    onNoCodeDetected = { showScanNotice(noCodeNotice) },
                    onScanCancelled = { showScanNotice(scanCancelledNotice) },
                    onScanSwitched = { showScanNotice(scanSwitchedNotice) },
                    onScanStarted = { hideScanNotice() },
                )
            } else if (hasCameraPermission) {
                CameraPreview(
                    viewfinderEnabled = viewfinderEnabled,
                    exactMatchEnabled = exactMatchEnabled,
                    onCodeScanned = { content, format, qrGrid ->
                        onCodeScanned(content, format, qrGrid, false)
                    },
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

/**
 * Where the title's text starts, measured from the start of the title slot:
 * the title field is shifted by Dimens.titleOffset and then pads its text by
 * 16dp (TextFieldMMD's own content padding).
 */
private val TITLE_TEXT_START = Dimens.titleOffset + 16.dp

/** Clear space kept between the end of the title and the first icon's button. */
private val TITLE_ICON_GAP = 4.dp

/** One title: its width at the standard title size, and the room it has, in px. */
internal data class TitleFit(val width: Float, val room: Float)

/**
 * The share of the standard title size at which every one of [fits] fits its
 * room: 1 when they all fit, otherwise what the tightest needs, but never
 * below [MIN_TITLE_SCALE] (smaller than that is not a title any more; the
 * wording has to be shorter instead).
 */
internal fun titleScale(fits: List<TitleFit>): Float =
    fits.minOf { it.room / it.width }.coerceIn(MIN_TITLE_SCALE, 1f)

private const val MIN_TITLE_SCALE = 0.5f

/** How long an Image Upload notice stays up unless dismissed or replaced. */
private const val SCAN_NOTICE_MILLIS = 15_000L

/** Toolbar button size on every screen (Home, detail pages, Scanner). */
private val TOOLBAR_BUTTON = 40.dp
/**
 * Padding around the rule, set so it sits optically centred with ~13dp (17px
 * on the Kompakt) of clear space to each neighbouring glyph:
 * - before it: the flashlight glyph's own ~3.2dp inset in its 32dp box + the
 *   button's 4dp margin + TopAppBarMMD's 4dp title-slot end padding (Material
 *   3's TopAppBarHorizontalPadding) + this 2.25dp;
 * - after it: this 8.25dp + the toggle button's 4dp margin (the black square
 *   fills its box, so it has no inset).
 * Both are multiples of 0.75dp (whole pixels on the Kompakt) and sum to the
 * same 10.5dp, so moving the rule never shifts the toggle or the title slot.
 * Measured on-device 2026-11-19: Image Upload gaps 16px / 18px with 1.5 / 9dp,
 * so the rule moved one pixel right to 17px / 17px.
 */
private val TOOLBAR_RULE_START_PADDING = 2.25.dp
private val TOOLBAR_RULE_END_PADDING = 8.25.dp
/** Rule height: a little shorter than the 32dp icons it separates. */
private val TOOLBAR_RULE_HEIGHT = 28.dp
/**
 * The exact-match ring fills more of its 32dp box than the flashlight glyph
 * (insets ~1.3dp vs ~3.2dp), so centred in the flashlight's slot its edge
 * would sit ~1.9dp nearer the rule. In Image Upload it is nudged LEFT by that
 * difference: its edge lands where the flashlight's is, same gap to the rule.
 */
private val EQUALS_IN_FLASHLIGHT_SLOT_NUDGE = (-1.9).dp
/**
 * The rule's thickness: one pixel lighter than the toolbar's 3dp bottom border
 * (4px) — 2.25dp is exactly 3 whole pixels on the Kompakt, so it stays crisp.
 */
private val TOOLBAR_RULE_WIDTH = 2.25.dp

/**
 * A toolbar icon in the Home screen's "+" frame: a black 32dp square with 4dp
 * corners and a white glyph.
 */
@Composable
private fun BlackSquareIcon(painter: Painter, contentDescription: String?) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Color.Black, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(SQUARE_GLYPH_SIZE)
        )
    }
}

/**
 * Size the white glyph is drawn at inside the black square. 30dp draws the
 * 24-unit photo and camera glyphs 1.25x larger than a standard 24dp icon (the
 * size Home's "+" uses), so each picture fills about two thirds of the square
 * instead of half; their line weight grows with them.
 */
private val SQUARE_GLYPH_SIZE = 30.dp

/**
 * An Image Upload notice for the snackbar: its message and the icon that
 * leads it. Always with the dismiss (x) action, and Indefinite because the
 * screen times it out itself (SCAN_NOTICE_MILLIS).
 */
private class ScanNotice(override val message: String, val icon: ImageVector) : SnackbarVisualsMMD {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDurationMMD = SnackbarDurationMMD.Indefinite
}

/**
 * An Image Upload notice ("No code detected", "Scan cancelled", "Scan
 * cancelled. New scan started."): MMD's
 * SnackbarMMD with the notice's icon leading the message, laid out like
 * MMD's own demo — a white band across the bottom under the library's top
 * line. The band is backed opaque: SnackbarMMD pads its panel with
 * see-through gaps (invisible on MMD's blank demo screen) that let the photo
 * grid show between its line and its text.
 */
@Composable
private fun ScanNoticeSnackbar(data: SnackbarDataMMD) {
    val icon = (data.visuals as? ScanNotice)?.icon ?: Icons.Outlined.ErrorOutline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SnackbarDefaultsMMD.color)
    ) {
        SnackbarMMD(
            modifier = Modifier.padding(vertical = 8.dp),
            dismissAction = {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dismiss))
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = data.visuals.message)
            }
        }
    }
}

/**
 * Moves the start edge by [shift] (negative = left) while keeping the end
 * edge where it was: the element is widened by the same amount. The title
 * uses this instead of a plain offset, which dragged the read-only title
 * field's end in too and clipped "Scanner" once four toolbar icons left it
 * less room.
 */
private fun Modifier.shiftStartKeepingEnd(shift: Dp): Modifier = layout { measurable, constraints ->
    val extra = (-shift).roundToPx().coerceAtLeast(0)
    val widened = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
    )
    val placeable = measurable.measure(widened)
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) {
        placeable.placeRelative(-extra, 0)
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
    // The camera uses this preview bound, so it releases only its own. Calm QR
    // can hold two scanners at once: its own, paused in the background, while
    // another app borrows the scanner (ScanForAppActivity). unbindAll() here
    // released the other one too, which then stayed on "Initializing camera".
    val boundUseCases = remember { java.util.concurrent.atomic.AtomicReference<Array<UseCase>>(emptyArray()) }
    // Set when this preview leaves composition; a camera provider that only
    // answers after that must not bind (nothing would release it).
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    LaunchedEffect(scannedCode) {
        scannedCode?.let { code ->
            onCodeScanned(code.content, code.format, code.qrGrid)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            executor.shutdown()
            // Leaving composition without leaving the screen (switching to
            // Image Upload) must release the camera: it is bound to the
            // screen's lifecycle, which stays alive. Only this preview's own
            // use cases: another scanner may be bound in this process.
            if (cameraProviderFuture.isDone) {
                runCatching {
                    cameraProviderFuture.get().unbind(*boundUseCases.getAndSet(emptyArray()))
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                cameraProviderFuture.addListener({
                    // The screen can be left before the provider future resolves
                    // (fast back press on first open); binding to a destroyed
                    // lifecycle throws.
                    if (disposed.get() ||
                        lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED
                    ) {
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
                                exactMatchRef.get(), manualTapRef.get(),
                                // Frame-to-display rotation: lets the engine
                                // report a code's orientation as the user saw
                                // it in the viewfinder, not as the sensor did.
                                imageProxy.imageInfo.rotationDegrees
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

                    cameraProvider.unbind(*boundUseCases.getAndSet(emptyArray()))
                    val cam = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    boundUseCases.set(arrayOf(preview, imageAnalysis))
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
