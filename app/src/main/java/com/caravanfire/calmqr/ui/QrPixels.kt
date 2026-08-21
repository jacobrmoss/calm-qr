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
 * Convert an exact QR module grid from `DecodeResult.qrGrid` ("dim:hex",
 * row-major bits, MSB-first) into the same pixel format, one pixel per module.
 * Rendering this instead of re-encoding keeps the displayed code's pattern
 * identical to the physical code that was scanned. Null on malformed input.
 */
fun qrGridToPixelData(grid: String): ByteArray? {
    val sep = grid.indexOf(':')
    if (sep <= 0) return null
    val dim = grid.substring(0, sep).toIntOrNull() ?: return null
    if (dim !in 21..177) return null
    val hex = grid.substring(sep + 1)
    if (hex.length != ((dim * dim + 7) / 8) * 2) return null
    val out = ByteArray(8 + dim * dim * 4)
    ByteBuffer.wrap(out).putInt(dim).putInt(dim)
    for (i in 0 until dim * dim) {
        val nibble = hex[i / 4].digitToIntOrNull(16) ?: return null
        val black = (nibble shr (3 - i % 4)) and 1 == 1
        val v: Byte = if (black) 0x00 else 0xFF.toByte()
        val offset = 8 + i * 4
        out[offset] = 0xFF.toByte()
        out[offset + 1] = v
        out[offset + 2] = v
        out[offset + 3] = v
    }
    return out
}
