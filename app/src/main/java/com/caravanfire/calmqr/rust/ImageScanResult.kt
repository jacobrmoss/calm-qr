package com.caravanfire.calmqr.rust

/**
 * What [RustBridge.scanImageFile] reports for one gallery image: a found
 * code, no code, or a file Rust does not decode (anything but JPEG/PNG, or
 * one that failed to decode there) — the caller then decodes it with
 * Android's decoders and calls [RustBridge.scanImagePixels].
 * Constructed from Rust via JNI — kept by proguard rules.
 */
class ImageScanResult(
    @JvmField val status: Int,
    @JvmField val result: DecodeResult?,
) {
    companion object {
        const val NOT_FOUND = 0
        const val FOUND = 1
        const val UNSUPPORTED = 2
    }
}
