package com.caravanfire.calmqr.rust

/**
 * What the Rust scan engine hands back for one submitted frame, when there is
 * anything to act on: a finished scan and/or a focus-nudge request (normalized
 * frame coordinates; -1 when none). Constructed from Rust via JNI — kept by
 * proguard rules.
 */
class EngineOutput(
    @JvmField val result: DecodeResult?,
    @JvmField val nudgeX: Float,
    @JvmField val nudgeY: Float,
)
