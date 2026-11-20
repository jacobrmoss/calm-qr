package com.caravanfire.calmqr.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.rust.DecodeResult
import com.caravanfire.calmqr.rust.ImageScanResult
import com.caravanfire.calmqr.rust.RustBridge
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

// With targetSdk <= 32 this is the gallery permission on every Android version.
private const val PHOTO_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE
private const val COLUMNS = 3
/** Requested thumbnail edge in px: a cell is ~105dp (~140px on the Kompakt). */
private const val THUMB_PX = 192
/** Thumbnails decoded at once — previews arrive progressively, never a CPU spike. */
private const val THUMB_PARALLELISM = 2
/** Larger files skip the in-Rust decode and go to Android's downsampling decoder. */
private const val MAX_RUST_FILE_BYTES = 48L * 1024 * 1024
/** Longest side handed to the scanner from Android's decoder (matches still.rs). */
private const val MAX_SCAN_SIDE = 4096

/** One gallery image: MediaStore id/uri and the clockwise turn that shows it upright. */
private data class GalleryImage(val id: Long, val uri: Uri, val orientation: Int)

private sealed interface Thumb {
    data object Loading : Thumb
    data class Ready(val bitmap: ImageBitmap) : Thumb
    data object Failed : Thumb
}

/**
 * The Scanner screen's Image Upload mode: the phone's photos as a scrollable
 * grid in place of the camera preview. Thumbnails come from Android's
 * ready-made MediaStore thumbnails, loaded only for visible cells, two at a
 * time. Tapping a photo selects it (exactly one) and immediately runs Rust's
 * full-strength still-image scan; a hit goes through [onCodeScanned] like a
 * camera scan, a miss clears the selection and calls [onNoCodeDetected] (the
 * screen shows its "No code detected" snackbar). [onScanStarted] fires as a
 * new scan begins, so a stale snackbar can go away.
 */
@Composable
fun ImageUploadPane(
    exactMatchEnabled: Boolean,
    onCodeScanned: (content: String, format: String, qrGrid: String?) -> Unit,
    onNoCodeDetected: () -> Unit,
    onScanStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(isPhotoPermissionGranted(context)) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        // A denial without a rationale prompt means "don't ask again": only
        // the system settings page can grant it now.
        val activity = context.findActivity()
        permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, PHOTO_PERMISSION)
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(PHOTO_PERMISSION)
    }
    // Coming back from the settings page: pick up a grant made there.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = isPhotoPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextMMD(
                    text = stringResource(R.string.photo_permission_required),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                ButtonMMD(onClick = {
                    if (permanentlyDenied) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        permissionLauncher.launch(PHOTO_PERMISSION)
                    }
                }) {
                    TextMMD(
                        text = stringResource(
                            if (permanentlyDenied) R.string.open_settings else R.string.grant_permission
                        )
                    )
                }
            }
        } else {
            Gallery(
                exactMatchEnabled = exactMatchEnabled,
                onCodeScanned = onCodeScanned,
                onNoCodeDetected = onNoCodeDetected,
                onScanStarted = onScanStarted,
            )
        }
    }
}

@Composable
private fun Gallery(
    exactMatchEnabled: Boolean,
    onCodeScanned: (content: String, format: String, qrGrid: String?) -> Unit,
    onNoCodeDetected: () -> Unit,
    onScanStarted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember { ThumbnailLoader(context.contentResolver) }
    var images by remember { mutableStateOf<List<GalleryImage>?>(null) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        images = withContext(Dispatchers.IO) { queryImages(context.contentResolver) }
    }

    fun scan(image: GalleryImage) {
        selectedId = image.id
        scanning = true
        onScanStarted()
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                scanGalleryImage(context.contentResolver, image, exactMatchEnabled)
            }
            if (result != null) {
                // Selection and badge stay up until the detail page replaces us.
                onCodeScanned(result.text, result.format, result.qrGrid)
            } else {
                scanning = false
                selectedId = null
                onNoCodeDetected()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val list = images
        when {
            list == null -> LoadingSpinner(
                description = stringResource(R.string.loading_image),
                modifier = Modifier.align(Alignment.Center)
            )

            list.isEmpty() -> TextMMD(
                text = stringResource(R.string.no_images_found),
                modifier = Modifier.align(Alignment.Center)
            )

            else -> {
                val rows = remember(list) { list.chunked(COLUMNS) }
                LazyColumnMMD(modifier = Modifier.fillMaxSize()) {
                    items(rows.size, key = { rows[it].first().id }) { rowIndex ->
                        val row = rows[rowIndex]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Extra end padding keeps cells clear of the MMD scrollbar.
                                .padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (image in row) {
                                ThumbnailCell(
                                    image = image,
                                    loader = loader,
                                    selected = image.id == selectedId,
                                    scanning = scanning && image.id == selectedId,
                                    enabled = !scanning,
                                    onClick = { scan(image) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                )
                            }
                            repeat(COLUMNS - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCell(
    image: GalleryImage,
    loader: ThumbnailLoader,
    selected: Boolean,
    scanning: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var thumb by remember(image.id) {
        mutableStateOf<Thumb>(loader.cached(image.id)?.let { Thumb.Ready(it) } ?: Thumb.Loading)
    }
    LaunchedEffect(image.id) {
        if (thumb == Thumb.Loading) {
            thumb = loader.load(image)?.let { Thumb.Ready(it) } ?: Thumb.Failed
        }
    }
    // Selection is shape, not colour (e-ink): a heavy frame plus a badge.
    Box(
        modifier = modifier
            .border(width = if (selected) 4.dp else 1.dp, color = MaterialTheme.colorScheme.onBackground)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (val t = thumb) {
            is Thumb.Ready -> Image(
                bitmap = t.bitmap,
                contentDescription = stringResource(R.string.image_thumbnail),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (selected) 4.dp else 1.dp)
            )

            Thumb.Loading -> LoadingSpinner(
                description = stringResource(R.string.loading_image),
                size = 28.dp
            )

            Thumb.Failed -> Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = stringResource(R.string.image_thumbnail),
                modifier = Modifier.size(28.dp)
            )
        }
        if (scanning) {
            // Processing: MMD's loader centred on the photo, on a white disc
            // so it reads over any picture.
            Box(
                modifier = Modifier
                    .size(PROCESSING_DISC)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LoadingSpinner(description = stringResource(R.string.scanning_image))
            }
        } else if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** White disc behind the processing loader: the 32dp loader plus 8dp all round. */
private val PROCESSING_DISC = 48.dp

/**
 * MMD's standard loading symbol: CircularProgressIndicatorMMD, eight spokes
 * with one solid spoke stepping round 45 degrees every 250ms (paced for
 * e-ink). The MMD component draws its icons without a description, so the
 * label goes on the wrapper for TalkBack.
 */
@Composable
private fun LoadingSpinner(
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    CircularProgressIndicatorMMD(
        modifier = modifier.semantics { contentDescription = description },
        size = size,
    )
}

/**
 * Android's ready-made MediaStore thumbnails, cached, fetched at most
 * [THUMB_PARALLELISM] at a time. A cell scrolled away cancels its fetch.
 */
private class ThumbnailLoader(private val resolver: ContentResolver) {
    private val cache = LruCache<Long, ImageBitmap>(120)
    private val gate = Semaphore(THUMB_PARALLELISM)

    fun cached(id: Long): ImageBitmap? = cache.get(id)

    suspend fun load(image: GalleryImage): ImageBitmap? {
        cache.get(image.id)?.let { return it }
        return gate.withPermit {
            cache.get(image.id) ?: fetch(image)?.asImageBitmap()?.also { cache.put(image.id, it) }
        }
    }

    private suspend fun fetch(image: GalleryImage): Bitmap? = withContext(Dispatchers.IO) {
        val signal = CancellationSignal()
        val cancelHook = coroutineContext[Job]?.invokeOnCompletion { signal.cancel() }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Applies the photo's orientation itself.
                resolver.loadThumbnail(image.uri, Size(THUMB_PX, THUMB_PX), signal)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    resolver, image.id, MediaStore.Images.Thumbnails.MINI_KIND, null
                )?.rotated(image.orientation)
            }
        } catch (e: Exception) {
            // Cancelled, unreadable or gone: the cell shows a broken-image glyph.
            null
        } finally {
            cancelHook?.dispose()
        }
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap =
    if (degrees % 360 == 0) this
    else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

/** Every image in the gallery, newest first. */
@SuppressLint("InlinedApi")
private fun queryImages(resolver: ContentResolver): List<GalleryImage> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.ORIENTATION)
    val out = ArrayList<GalleryImage>()
    try {
        resolver.query(
            collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val orientationCol = c.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val orientation = if (orientationCol >= 0 && !c.isNull(orientationCol)) c.getInt(orientationCol) else 0
                out += GalleryImage(id, ContentUris.withAppendedId(collection, id), orientation)
            }
        }
    } catch (e: SecurityException) {
        return emptyList()
    }
    return out
}

/**
 * Rust decodes JPEG/PNG to grayscale itself and scans at full strength;
 * anything else (HEIC, WebP, GIF...) is decoded by Android and handed over as
 * pixels. Blocks for the whole scan.
 */
private fun scanGalleryImage(resolver: ContentResolver, image: GalleryImage, exact: Boolean): DecodeResult? {
    val size = try {
        resolver.openAssetFileDescriptor(image.uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        return null
    }
    if (size in 1..MAX_RUST_FILE_BYTES) {
        val bytes = try {
            resolver.openInputStream(image.uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return null
        val out = RustBridge.scanImageFile(bytes, image.orientation, exact)
        when (out?.status) {
            ImageScanResult.FOUND -> return out?.result
            ImageScanResult.NOT_FOUND, null -> return null
            else -> Unit // UNSUPPORTED: fall through to Android's decoder
        }
    }
    return scanWithAndroidDecoder(resolver, image, exact)
}

private fun scanWithAndroidDecoder(resolver: ContentResolver, image: GalleryImage, exact: Boolean): DecodeResult? {
    val decoded = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, image.uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = maxOf(info.size.width, info.size.height)
            if (long > MAX_SCAN_SIDE) {
                val scale = MAX_SCAN_SIDE.toFloat() / long
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (e: Exception) {
        return null
    }
    val argb = if (decoded.config == Bitmap.Config.ARGB_8888) decoded
    else decoded.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    val pixels = ByteBuffer.allocateDirect(argb.byteCount)
    argb.copyPixelsToBuffer(pixels)
    // ImageDecoder already applied the photo's orientation: rotation 0.
    return RustBridge.scanImagePixels(pixels, argb.width, argb.height, argb.rowBytes, 0, exact)
}

private fun isPhotoPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, PHOTO_PERMISSION) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
