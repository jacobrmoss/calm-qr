package com.caravanfire.calmqr.rust

/**
 * @param qrGrid exact module grid of the scanned QR symbol ("dim:hex", row-major
 *   bits, MSB-first), or null for non-QR formats / when reconstruction fails.
 * @param centerX/centerY the code's center as fractions of the analysis frame
 *   (0..1), for focus metering — or -1 when unknown (rotated-variant hits).
 *   Constructed from Rust via JNI — kept by proguard rules.
 */
data class DecodeResult(
    val text: String,
    val format: String,
    val qrGrid: String?,
    val centerX: Float,
    val centerY: Float,
)
