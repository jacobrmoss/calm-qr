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

    /** Decode a barcode/QR code from raw luminance bytes. Returns null if nothing found. */
    external fun decodeBarcode(lumaBytes: ByteArray, width: Int, height: Int): DecodeResult?

    /** Generate a barcode/QR code image from content. Returns raw pixel data or null on failure. */
    external fun generateBarcode(content: String, format: String, width: Int, height: Int): ByteArray?
}
