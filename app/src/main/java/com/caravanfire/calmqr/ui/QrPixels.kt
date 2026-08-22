package com.caravanfire.calmqr.ui

import android.graphics.Bitmap
import com.caravanfire.calmqr.rust.RustBridge
import java.nio.ByteBuffer

private val ONE_D_FORMATS = setOf(
    "CODE_128", "CODE_39", "CODE_93", "EAN_13", "EAN_8",
    "UPC_A", "UPC_E", "ITF", "CODABAR", "TELEPEN",
)

fun is1DFormat(format: String): Boolean = format in ONE_D_FORMATS

/**
 * Pixel data for a scanned/saved code: the exact scanned QR module grid when
 * available (see [qrGridToPixelData]), else a re-encode via Rust. Null when
 * generation fails.
 */
fun barcodePixelData(content: String, format: String, qrGrid: String? = null): ByteArray? =
    qrGrid?.let { qrGridToPixelData(it) }
        ?: if (is1DFormat(format)) RustBridge.generateBarcode(content, format, 512, 200)
        else RustBridge.generateBarcode(content, format, 512, 512)

/**
 * Helpers for the raw pixel format produced by `RustBridge.generateBarcode`
 * and stored in `SavedCode.qrImageData`:
 * [width: 4B BE][height: 4B BE][ARGB pixels: width*height*4 bytes]
 */

/** Decode raw pixel data into an Android Bitmap. Null on malformed input. */
fun decodeQrPixelData(data: ByteArray): Bitmap? {
    if (data.size < 8) return null
    val buffer = ByteBuffer.wrap(data)
    val width = buffer.getInt()
    val height = buffer.getInt()
    if (width <= 0 || height <= 0) return null
    val expectedSize = 8 + width * height * 4
    if (data.size < expectedSize) return null
    // Stored bytes are big-endian [A][R][G][B] per pixel — exactly the int
    // layout Bitmap wants, so a bulk IntBuffer read replaces a per-pixel loop.
    val pixels = IntArray(width * height)
    ByteBuffer.wrap(data, 8, width * height * 4).asIntBuffer().get(pixels)
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Convert an exact QR module grid from `DecodeResult.qrGrid` into the same
 * pixel format, one pixel per module. Grid syntax: optional prefixes, then
 * "dim:hex" (row-major canonical module bits, MSB-first):
 *  - "inv:"  the physical symbol was white-on-dark — render that polarity
 *  - "o<k>:" orientation as seen in the viewfinder, k = mirror*4 + rot/90:
 *            render = rotate clockwise by rot° of (flip-left-right if mirror)
 * Rendering this instead of re-encoding keeps the displayed code identical to
 * the physical code as the user saw it — pattern, polarity, rotation and
 * handedness. Null on malformed input.
 */
fun qrGridToPixelData(grid: String): ByteArray? {
    var body = grid
    var inverted = false
    var orientation = 0
    while (true) {
        if (body.startsWith("inv:")) {
            inverted = true
            body = body.substring(4)
        } else if (body.length > 3 && body[0] == 'o' && body[1].isDigit() && body[2] == ':') {
            orientation = body[1].digitToInt()
            body = body.substring(3)
        } else {
            break
        }
    }
    val sep = body.indexOf(':')
    if (sep <= 0) return null
    val dim = body.substring(0, sep).toIntOrNull() ?: return null
    if (dim !in 21..177) return null
    val hex = body.substring(sep + 1)
    if (hex.length != ((dim * dim + 7) / 8) * 2) return null
    // Canonical module grid: true = module (a set bit).
    val modules = BooleanArray(dim * dim)
    for (i in 0 until dim * dim) {
        val nibble = hex[i / 4].digitToIntOrNull(16) ?: return null
        modules[i] = (nibble shr (3 - i % 4)) and 1 == 1
    }
    val mirror = orientation >= 4
    val rot = orientation % 4
    val out = ByteArray(8 + dim * dim * 4)
    ByteBuffer.wrap(out).putInt(dim).putInt(dim)
    for (y in 0 until dim) {
        for (x in 0 until dim) {
            // Undo the presentation transform: output (x, y) ← canonical (cx, cy).
            // rotate_cw(rot) maps canonical (cx, cy) to (dim-1-cy, cx) per 90°,
            // so invert it, then undo the left-right flip.
            var cx = x
            var cy = y
            repeat(rot) {
                val nx = cy
                val ny = dim - 1 - cx
                cx = nx
                cy = ny
            }
            if (mirror) cx = dim - 1 - cx
            val module = modules[cy * dim + cx]
            // On an inverted symbol modules are the LIGHT squares.
            val black = module != inverted
            val v: Byte = if (black) 0x00 else 0xFF.toByte()
            val offset = 8 + (y * dim + x) * 4
            out[offset] = 0xFF.toByte()
            out[offset + 1] = v
            out[offset + 2] = v
            out[offset + 3] = v
        }
    }
    return out
}
