package com.caravanfire.calmqr.rust

/**
 * JNI bridge to the Rust native library.
 *
 * The native functions are implemented in `rust/src/lib.rs`.
 * The shared library is built by `cargo-ndk` and loaded at class init.
 */
object RustBridge {

    init {
        System.loadLibrary("calm_rust")
    }

    /** Returns a greeting string assembled in Rust. */
    external fun greet(name: String): String

    /** Example: add two numbers on the Rust side. */
    external fun add(a: Long, b: Long): Long

    /**
     * Decode a barcode/QR code from raw luminance bytes.
     *
     * @param binarizer 0 = HybridBinarizer (local adaptive), 1 = GlobalHistogramBinarizer.
     * @param cropLeft/cropTop/cropWidth/cropHeight ROI in buffer pixel coords.
     *        Pass a non-positive cropWidth or cropHeight to scan the full frame.
     * @param tryRotate retry once with 90° rotation on failure (useful for 1D codes).
     */
    external fun decodeBarcode(
        lumaBytes: ByteArray,
        width: Int,
        height: Int,
        binarizer: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        tryRotate: Boolean
    ): DecodeResult?

    /** Generate a barcode/QR code image from content. Returns raw pixel data or null on failure. */
    external fun generateBarcode(content: String, format: String, width: Int, height: Int): ByteArray?
}
