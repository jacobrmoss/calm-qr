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

    /**
     * Decode a barcode/QR code from raw luminance bytes.
     *
     * @param lumaBuffer DIRECT ByteBuffer holding the luminance plane (e.g. the
     *        Y plane of a camera frame). Rust reads it in place — no Java-side
     *        copy — so it must remain valid until this call returns; only close
     *        the ImageProxy afterwards.
     * @param rowStride bytes per buffer row (>= width; padding is skipped natively).
     * @param binarizer 0 = HybridBinarizer (local adaptive), 1 = GlobalHistogramBinarizer.
     * @param cropLeft/cropTop/cropWidth/cropHeight ROI in buffer pixel coords.
     *        Pass a non-positive cropWidth or cropHeight to scan the full frame.
     * @param tryRotate retry once with 90° rotation on failure (useful for 1D codes).
     * @param formatFilter 0 = all formats; 1 = QR only (grid-retry frames,
     *        pair with tryRotate=false — the QR detector is rotation-invariant);
     *        2 = 1D only (confirm-read frames, keep tryRotate=true).
     * @param tryInverted enable the white-on-dark (AlsoInverted) pass. Doubles
     *        a miss's cost, so the scanner alternates it across frames; keep
     *        true during hold retries.
     * @param lite insurance-decode mode: downscaled variants only (~30% cost).
     *        For sub-bar frames with a measured zero hit rate; ignored when
     *        the source is too small to downscale.
     */
    external fun decodeBarcode(
        lumaBuffer: java.nio.ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        binarizer: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        tryRotate: Boolean,
        formatFilter: Int,
        tryInverted: Boolean,
        lite: Boolean
    ): DecodeResult?

    /**
     * Cheap sharpness score (mean squared gradient, central 50% of the frame,
     * subsampled — well under 1ms). Relative measure: comparable only within a
     * scanning session. Higher = sharper. Returns 0 on error.
     */
    external fun frameScore(
        lumaBuffer: java.nio.ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): Float

    /**
     * Cheap QR localization without decoding: finds the finder-pattern triple
     * on a subsampled frame — works on frames too blurry to decode. Returns
     * [centerX, centerY] normalized to the frame, or null when none found.
     * Drives focus-at-code during the aiming phase.
     */
    external fun locateQr(
        lumaBuffer: java.nio.ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): FloatArray?

    /**
     * Reset the native scan engine for a new scanner session. Call on every
     * scanner screen entry. Per-scan state (holds, votes, aim-lock, tap
     * override) clears; score calibration persists for the process lifetime.
     */
    external fun engineReset(exactMatch: Boolean)

    /**
     * Feed one camera frame to the native scan engine — the single per-frame
     * call. Everything happens in Rust: scoring, best-frame selection, gated
     * decode scheduling on a persistent worker pool, QR grid holds, 1D vote
     * confirmation, focus-nudge policy, and telemetry (ScanPerf/GridExtract).
     *
     * The buffer must be DIRECT and stays valid only for this call (close the
     * ImageProxy after it returns; the engine copies what it keeps). Returns an
     * [EngineOutput] when there is something to act on — a finished scan to
     * navigate on and/or a focus nudge to execute — else null.
     */
    external fun engineSubmitFrame(
        lumaBuffer: java.nio.ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        exactMatch: Boolean,
        manualTap: Boolean
    ): EngineOutput?

    /** Generate a barcode/QR code image from content. Returns raw pixel data or null on failure. */
    external fun generateBarcode(content: String, format: String, width: Int, height: Int): ByteArray?
}
