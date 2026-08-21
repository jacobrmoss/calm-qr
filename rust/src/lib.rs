use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JObject, JString, JValue};
use jni::sys::{jboolean, jfloat, jint};
use jni::JNIEnv;
use std::collections::HashSet;
use std::sync::mpsc;
use std::thread;

mod engine;

/// Minimal logcat writer (no logging-crate dependency).
#[cfg(target_os = "android")]
pub(crate) fn alog(tag: &str, msg: &str) {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
    if let (Ok(tag), Ok(text)) = (CString::new(tag), CString::new(msg)) {
        // 4 = ANDROID_LOG_INFO
        unsafe { __android_log_write(4, tag.as_ptr(), text.as_ptr()) };
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) fn alog(_tag: &str, _msg: &str) {}

/// JNI entry point: `com.caravanfire.calmqr.rust.RustBridge.locateQr`
///
/// Cheap QR *localization* without decoding: subsample the frame 4x, binarize
/// with the fast global-histogram binarizer, and search only for the three
/// finder patterns — large-scale structures that survive defocus long before
/// the symbol is decodable. Lets the scanner aim the camera's focus at the
/// code while the user is still raising the device. Returns
/// `[centerX, centerY]` normalized to the full frame, or null when no
/// pattern triple is found.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_locateQr<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    luma_buffer: JByteBuffer<'local>,
    row_stride: jint,
    width: jint,
    height: jint,
) -> JFloatArray<'local> {
    let w = width.max(0) as usize;
    let h = height.max(0) as usize;
    let stride = row_stride.max(width) as usize;
    if w < 64 || h < 64 {
        return JFloatArray::from(JObject::null());
    }
    let Ok(ptr) = env.get_direct_buffer_address(&luma_buffer) else {
        return JFloatArray::from(JObject::null());
    };
    let Ok(cap) = env.get_direct_buffer_capacity(&luma_buffer) else {
        return JFloatArray::from(JObject::null());
    };
    if cap < (h - 1) * stride + w {
        return JFloatArray::from(JObject::null());
    }
    // SAFETY: direct buffer stays valid for the duration of this call.
    let data = unsafe { std::slice::from_raw_parts(ptr, cap) };

    let Some((cx, cy)) = locate_qr_core(data, stride, w, h) else {
        return JFloatArray::from(JObject::null());
    };

    match env.new_float_array(2) {
        Ok(arr) => {
            if env.set_float_array_region(&arr, 0, &[cx, cy]).is_err() {
                return JFloatArray::from(JObject::null());
            }
            arr
        }
        Err(_) => JFloatArray::from(JObject::null()),
    }
}

/// One cheap pass over a frame: a 4x subsample feeds the sharpness score, the
/// QR finder-pattern search, and the 1D gradient-coherence detector — three
/// results for one buffer traversal.
pub(crate) struct FrameAnalysis {
    pub score: f32,
    pub qr: Option<(f32, f32)>,
    pub oned: Option<(f32, f32)>,
}

pub(crate) fn analyze_frame(data: &[u8], stride: usize, w: usize, h: usize) -> FrameAnalysis {
    const STEP: usize = 4;
    if w < 64 || h < 64 || data.len() < (h - 1) * stride + w {
        return FrameAnalysis {
            score: score_luma(data, stride, w, h),
            qr: None,
            oned: None,
        };
    }
    let sw = w / STEP;
    let sh = h / STEP;
    let mut small = Vec::with_capacity(sw * sh);
    for y in 0..sh {
        let row = (y * STEP) * stride;
        for x in 0..sw {
            small.push(data[row + x * STEP]);
        }
    }

    // Sharpness score over the central 50% of the subsample. Adjacent pixels
    // here are exactly the old 4-pixel lattice of `score_luma`, so the values
    // are identical — calibration (baseline, ratios) carries over unchanged.
    let (cx0, cx1) = (sw / 4, (sw * 3) / 4);
    let (cy0, cy1) = (sh / 4, (sh * 3) / 4);
    let mut acc: u64 = 0;
    let mut n: u64 = 0;
    for y in cy0..cy1.saturating_sub(1) {
        let row = y * sw;
        let row_down = (y + 1) * sw;
        for x in cx0..cx1.saturating_sub(1) {
            let c = small[row + x] as i64;
            let dx = c - small[row + x + 1] as i64;
            let dy = c - small[row_down + x] as i64;
            acc += (dx * dx + dy * dy) as u64;
            n += 1;
        }
    }
    let score = if n == 0 {
        0.0
    } else {
        (acc as f64 / n as f64) as f32
    };

    // 1D detector: block grid over the subsample; a barcode block has high
    // gradient energy dominated by ONE axis (bars). Axis-aligned bias is
    // deliberate — 45° codes ride the fallback path (rotated decode variant),
    // and this detector only grants priority, never permission.
    const BLOCKS_X: usize = 8;
    const BLOCKS_Y: usize = 6;
    const ANISOTROPY: f32 = 2.5;
    const ENERGY_FLOOR: f32 = 1500.0;
    let bw = sw / BLOCKS_X;
    let bh = sh / BLOCKS_Y;
    let oned = if bw >= 8 && bh >= 8 {
        let mut blocks = [[(0u64, 0u64, 0u64); BLOCKS_X]; BLOCKS_Y]; // (sum|dx|, sum|dy|, energy)
        for y in 0..sh - 1 {
            let row = y * sw;
            let row_down = row + sw;
            let by = (y / bh).min(BLOCKS_Y - 1);
            for x in 0..sw - 1 {
                let c = small[row + x] as i64;
                let dx = (c - small[row + x + 1] as i64).unsigned_abs();
                let dy = (c - small[row_down + x] as i64).unsigned_abs();
                let bx = (x / bw).min(BLOCKS_X - 1);
                let b = &mut blocks[by][bx];
                b.0 += dx;
                b.1 += dy;
                b.2 += dx * dx + dy * dy;
            }
        }
        let px_per_block = (bw * bh) as f32;
        let (mut sx, mut sy, mut sweight) = (0.0f32, 0.0f32, 0.0f32);
        for by in 0..BLOCKS_Y {
            for bx in 0..BLOCKS_X {
                let (adx, ady, energy) = blocks[by][bx];
                let e = energy as f32 / px_per_block;
                if e < ENERGY_FLOOR {
                    continue;
                }
                let (hi, lo) = if adx > ady { (adx, ady) } else { (ady, adx) };
                if (hi as f32) < ANISOTROPY * (lo as f32 + 1.0) {
                    continue;
                }
                let cxn = ((bx as f32 + 0.5) * bw as f32) / sw as f32;
                let cyn = ((by as f32 + 0.5) * bh as f32) / sh as f32;
                sx += cxn * e;
                sy += cyn * e;
                sweight += e;
            }
        }
        if sweight > 0.0 {
            Some((sx / sweight, sy / sweight))
        } else {
            None
        }
    } else {
        None
    };

    let qr = locate_qr_from_small(small, sw, sh, STEP, w, h);

    FrameAnalysis { score, qr, oned }
}

/// Core of the QR localization: finder-pattern triple on a 4x subsample.
/// Returns the normalized code center, or None.
pub(crate) fn locate_qr_core(data: &[u8], stride: usize, w: usize, h: usize) -> Option<(f32, f32)> {
    const STEP: usize = 4;
    if w < 64 || h < 64 || data.len() < (h - 1) * stride + w {
        return None;
    }
    let sw = w / STEP;
    let sh = h / STEP;
    let mut small = Vec::with_capacity(sw * sh);
    for y in 0..sh {
        let row = (y * STEP) * stride;
        for x in 0..sw {
            small.push(data[row + x * STEP]);
        }
    }
    locate_qr_from_small(small, sw, sh, STEP, w, h)
}

fn locate_qr_from_small(
    small: Vec<u8>,
    sw: usize,
    sh: usize,
    step: usize,
    w: usize,
    h: usize,
) -> Option<(f32, f32)> {
    let source = Luma8LuminanceSource::new(small, sw as u32, sh as u32);
    let mut bb = BinaryBitmap::new(GlobalHistogramBinarizer::new(source));
    let hints = DecodeHints::default();
    let normal = {
        let mut finder = FinderPatternFinder::new(bb.get_black_matrix());
        finder.find(&hints).ok()
    };
    // White-on-dark codes: the expensive decode catches them via AlsoInverted,
    // so the cheap detector must too — retry on the flipped matrix (only on
    // misses; ~2ms at this scale).
    let info = match normal {
        Some(i) => i,
        None => {
            let m = bb.get_black_matrix_mut();
            m.flip_self();
            let mut finder = FinderPatternFinder::new(m);
            finder.find(&hints).ok()?
        }
    };
    let tl: Point = info.getTopLeft().into();
    let tr: Point = info.getTopRight().into();
    let bl: Point = info.getBottomLeft().into();
    Some((
        (tl.x + tr.x + bl.x) / 3.0 * step as f32 / w as f32,
        (tl.y + tr.y + bl.y) / 3.0 * step as f32 / h as f32,
    ))
}

/// JNI entry point: `com.caravanfire.calmqr.rust.RustBridge.frameScore`
///
/// Cheap sharpness score of the central half of a luminance frame: mean squared
/// gradient over a subsampled lattice (~sub-ms at 1080p, no allocation). Scores
/// are relative — comparable only within a scanning session under similar
/// framing — and let the scanner spend decode slots on the sharpest recent
/// frame instead of the blindly-latest one. Returns 0.0 on any error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_frameScore<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    luma_buffer: JByteBuffer<'local>,
    row_stride: jint,
    width: jint,
    height: jint,
) -> jfloat {
    let w = width.max(0) as usize;
    let h = height.max(0) as usize;
    let stride = row_stride.max(width) as usize;
    if w < 16 || h < 16 {
        return 0.0;
    }
    let Ok(ptr) = env.get_direct_buffer_address(&luma_buffer) else {
        return 0.0;
    };
    let Ok(cap) = env.get_direct_buffer_capacity(&luma_buffer) else {
        return 0.0;
    };
    if cap < (h - 1) * stride + w {
        return 0.0;
    }
    // SAFETY: direct buffer stays valid for the duration of this call (the
    // caller closes the ImageProxy only after it returns).
    let data = unsafe { std::slice::from_raw_parts(ptr, cap) };
    score_luma(data, stride, w, h)
}

/// Core of the sharpness score: mean squared gradient over a subsampled
/// lattice of the central 50% region.
pub(crate) fn score_luma(data: &[u8], stride: usize, w: usize, h: usize) -> f32 {
    if w < 16 || h < 16 || data.len() < (h - 1) * stride + w {
        return 0.0;
    }
    let (x0, x1) = (w / 4, (w * 3) / 4);
    let (y0, y1) = (h / 4, (h * 3) / 4);
    const STEP: usize = 4;
    let mut acc: u64 = 0;
    let mut n: u64 = 0;
    let mut y = y0;
    while y + STEP < y1 {
        let row = y * stride;
        let row_down = (y + STEP) * stride;
        let mut x = x0;
        while x + STEP < x1 {
            let c = data[row + x] as i64;
            let dx = c - data[row + x + STEP] as i64;
            let dy = c - data[row_down + x] as i64;
            acc += (dx * dx + dy * dy) as u64;
            n += 1;
            x += STEP;
        }
        y += STEP;
    }
    if n == 0 {
        return 0.0;
    }
    (acc as f64 / n as f64) as f32
}
use rxing::common::reedsolomon::{
    get_predefined_genericgf, PredefinedGenericGF, ReedSolomonDecoder,
};
use rxing::common::{BitArray, BitMatrix, DetectorRXingResult, GlobalHistogramBinarizer, HybridBinarizer};
use rxing::qrcode::decoder::{BitMatrixParser, DataBlock};
use rxing::qrcode::detector::{Detector, FinderPatternFinder};
use rxing::Point;
use rxing::qrcode::encoder::{matrix_util, ByteMatrix};
use rxing::{
    BarcodeFormat, Binarizer, BinaryBitmap, DecodeHints, Luma8LuminanceSource, LuminanceSource,
    MultiFormatReader, MultiFormatWriter, Reader, Writer,
};

/// JNI entry point: `com.caravanfire.calmqr.rust.RustBridge.decodeBarcode`
///
/// Reads raw luminance bytes straight out of a direct ByteBuffer (the camera
/// plane buffer — no Java-side copy) and attempts to decode a barcode/QR code.
/// The buffer must stay valid until this call returns: the Kotlin caller may
/// only close the ImageProxy afterwards.
///
/// Parameters:
/// - `row_stride`: bytes per buffer row (>= width; padding is skipped here).
/// - `binarizer`: 0 = HybridBinarizer (local-adaptive), 1 = GlobalHistogramBinarizer.
/// - `crop_left`, `crop_top`, `crop_width`, `crop_height`: ROI crop rect. When
///   `crop_width <= 0` or `crop_height <= 0`, the full frame is scanned.
/// - `try_rotate`: when nonzero, also attempt the frame rotated 90 degrees
///   counter-clockwise (helps 1D codes held perpendicular to the buffer
///   orientation).
/// - `format_filter`: 0 = all formats; 1 = QR only (grid-retry frames — pair
///   with `try_rotate = 0`, the QR detector is rotation-invariant); 2 = 1D
///   only (confirm-read frames — keep `try_rotate` on, 1D needs orientation).
/// - `try_inverted`: sets `AlsoInverted` (white-on-dark support). The caller
///   alternates it across frames — it doubles a miss's cost, so running it on
///   every other frame halves aim-phase work at +1 frame latency for inverted
///   codes. Keep it on for hold retries (an inverted code's grid needs the
///   polarity-normalized pass to decode at all).
/// - `lite`: insurance-decode mode — drop the full-resolution attempts and
///   scan only the downscaled variants (~30% of the cost). Used for sub-bar
///   frames whose measured hit rate is zero; ignored when the source is too
///   small to downscale.
///
/// `AlsoInverted` is always forced on so white-on-dark codes decode. `TryHarder`
/// is always set to favor robust detection over throughput.
///
/// Returns a `DecodeResult(text, format, qrGrid)` object or null if no barcode
/// is found. `qrGrid` is the scanned symbol's exact module grid ("dim:hex",
/// row-major bits, MSB-first) for QR codes, or null (other formats, or when
/// grid reconstruction fails).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_decodeBarcode<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    luma_buffer: JByteBuffer<'local>,
    row_stride: jint,
    width: jint,
    height: jint,
    binarizer: jint,
    crop_left: jint,
    crop_top: jint,
    crop_width: jint,
    crop_height: jint,
    try_rotate: jboolean,
    format_filter: jint,
    try_inverted: jboolean,
    lite: jboolean,
) -> JObject<'local> {
    let w = width.max(0) as usize;
    let h = height.max(0) as usize;
    let stride = row_stride.max(width) as usize;
    if w == 0 || h == 0 {
        return JObject::null();
    }

    let ptr = match env.get_direct_buffer_address(&luma_buffer) {
        Ok(p) => p,
        Err(_) => return JObject::null(),
    };
    let cap = match env.get_direct_buffer_capacity(&luma_buffer) {
        Ok(c) => c,
        Err(_) => return JObject::null(),
    };
    // The last row may be stride-truncated but must still hold `w` pixels.
    if cap < (h - 1) * stride + w {
        return JObject::null();
    }
    // SAFETY: ptr/cap come from a direct ByteBuffer the caller keeps alive for
    // the duration of this call (ImageProxy is closed only after we return).
    let data = unsafe { std::slice::from_raw_parts(ptr, cap) };

    // Single copy into a tight luma vec, dropping any row-stride padding.
    let luma: Vec<u8> = if stride == w {
        data[..w * h].to_vec()
    } else {
        let mut v = Vec::with_capacity(w * h);
        for row in 0..h {
            let start = row * stride;
            v.extend_from_slice(&data[start..start + w]);
        }
        v
    };

    let rxing_result = decode_luma(
        luma,
        width as u32,
        height as u32,
        binarizer,
        crop_left,
        crop_top,
        crop_width,
        crop_height,
        try_rotate != 0,
        format_filter,
        try_inverted != 0,
        lite != 0,
    );

    let Some((rxing_result, qr_grid, variant)) = rxing_result else {
        return JObject::null();
    };

    // Normalized code-center (fractions of the full frame) for focus metering.
    // Derivable only from the non-rotated variants (0 = full, 1 = downscaled);
    // -1 marks "unknown" otherwise.
    let mut center_x = -1.0f32;
    let mut center_y = -1.0f32;
    if variant <= 1 {
        let pts = rxing_result.getPoints();
        if !pts.is_empty() {
            let scale = if variant == 1 { 2.0f32 } else { 1.0f32 };
            let (crop_x, crop_y) = if crop_width > 0 && crop_height > 0 {
                (crop_left.max(0) as f32, crop_top.max(0) as f32)
            } else {
                (0.0, 0.0)
            };
            let n = pts.len() as f32;
            let sx: f32 = pts.iter().map(|p| p.x).sum();
            let sy: f32 = pts.iter().map(|p| p.y).sum();
            center_x = ((sx / n) * scale + crop_x) / w as f32;
            center_y = ((sy / n) * scale + crop_y) / h as f32;
        }
    }

    let text = rxing_result.getText();
    let format = format_to_string(*rxing_result.getBarcodeFormat());

    let j_text = match env.new_string(text) {
        Ok(s) => s,
        Err(_) => return JObject::null(),
    };
    let j_format = match env.new_string(&format) {
        Ok(s) => s,
        Err(_) => return JObject::null(),
    };
    let j_grid = match qr_grid {
        Some(g) => match env.new_string(&g) {
            Ok(s) => JObject::from(s),
            Err(_) => return JObject::null(),
        },
        None => JObject::null(),
    };
    let class = match env.find_class("com/caravanfire/calmqr/rust/DecodeResult") {
        Ok(c) => c,
        Err(_) => return JObject::null(),
    };
    match env.new_object(
        class,
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;FF)V",
        &[
            JValue::Object(&JObject::from(j_text)),
            JValue::Object(&JObject::from(j_format)),
            JValue::Object(&j_grid),
            JValue::Float(center_x),
            JValue::Float(center_y),
        ],
    ) {
        Ok(obj) => obj,
        Err(_) => JObject::null(),
    }
}

pub(crate) fn decode_luma(
    luma: Vec<u8>,
    width: u32,
    height: u32,
    binarizer: jint,
    crop_left: jint,
    crop_top: jint,
    crop_width: jint,
    crop_height: jint,
    try_rotate: bool,
    format_filter: jint,
    try_inverted: bool,
    lite: bool,
) -> Option<(rxing::RXingResult, Option<String>, usize)> {
    // Third tuple element: the winning variant's priority (0 full, 1 downscaled,
    // 2 rotated, 3 rotated+downscaled) so the caller can map result points
    // back into full-frame coordinates.
    let source = Luma8LuminanceSource::new(luma, width, height);

    let source = if crop_width > 0 && crop_height > 0 {
        let cl = crop_left.max(0) as usize;
        let ct = crop_top.max(0) as usize;
        let max_w = (width as usize).saturating_sub(cl);
        let max_h = (height as usize).saturating_sub(ct);
        let cw = (crop_width as usize).min(max_w);
        let ch = (crop_height as usize).min(max_h);
        if cw == 0 || ch == 0 {
            source
        } else {
            // crop is bounds-checked above; fall back to full frame defensively
            source.crop(cl, ct, cw, ch).unwrap_or(source)
        }
    } else {
        source
    };

    let source_w = source.get_width() as u32;
    let source_h = source.get_height() as u32;
    let can_downscale = source_w >= 480 && source_h >= 480;

    let mut hints = DecodeHints::default();
    hints.TryHarder = Some(true);
    hints.AlsoInverted = Some(try_inverted);
    match format_filter {
        // Grid-retry frames: the payload is already held, only a QR grid is
        // wanted — skip every other format's machinery.
        1 => hints.PossibleFormats = Some(HashSet::from([BarcodeFormat::QR_CODE])),
        // 1D confirm-read frames: only the weak-checksum family needs the
        // repeat read; skip QR/Aztec/DataMatrix/PDF417 machinery.
        2 => {
            hints.PossibleFormats = Some(HashSet::from([
                BarcodeFormat::CODE_128,
                BarcodeFormat::CODE_39,
                BarcodeFormat::CODE_93,
                BarcodeFormat::EAN_13,
                BarcodeFormat::EAN_8,
                BarcodeFormat::UPC_A,
                BarcodeFormat::UPC_E,
                BarcodeFormat::ITF,
                BarcodeFormat::CODABAR,
                BarcodeFormat::TELEPEN,
            ]))
        }
        _ => {}
    }

    // Fast path: no retries → consume the source on a single attempt, no clone.
    if !try_rotate && !can_downscale {
        return decode_with_binarizer(source, binarizer, &hints).map(|(r, g)| (r, g, 0));
    }

    // Multi-attempt path: priority join.
    //
    // Priority mirrors the old sequential order: original(0) → downscaled(1) →
    // rotated(2) → rotated+downscaled(3). Attempts run concurrently, but a QR
    // hit is only returned once every HIGHER-priority attempt has resolved, and
    // a higher-priority hit always wins. This keeps result AND mask semantics
    // byte-identical to the sequential cascade — `qr_exact_grid` fidelity
    // depends on the full-res attempt winning whenever it can decode — while
    // the per-frame wall time drops from the sum of attempts to the slowest
    // pending chain.
    //
    // Non-QR hits return immediately: no module grid is at stake, and waiting
    // ~1s for the full-res QR pass to miss would only delay 1D scans.
    //
    // Thread layout is deliberately 3 workers, not 4 — the rotated pair shares
    // one thread — leaving a core of headroom for the camera HAL's AF/AE loop
    // (saturating all four little cores degrades frame sharpness; measured on
    // the Kompakt, 2026-08).
    // Insurance decodes skip the full-resolution attempts entirely; only
    // meaningful when a downscale exists to carry the load.
    let lite = lite && can_downscale;

    let mut chunks: Vec<Vec<(usize, Luma8LuminanceSource)>> = Vec::with_capacity(3);
    let mut planned: Vec<usize> = Vec::with_capacity(4);

    if can_downscale {
        if let Some(ds) = downscale_2x(&source) {
            chunks.push(vec![(1, ds)]);
            planned.push(1);
        }
    }
    if try_rotate {
        if let Ok(rotated) = source.rotate_counter_clockwise() {
            let mut chunk = Vec::with_capacity(2);
            if can_downscale {
                if let Some(ds) = downscale_2x(&rotated) {
                    if !lite {
                        chunk.push((2, rotated));
                        planned.push(2);
                    }
                    chunk.push((3, ds));
                    planned.push(3);
                } else {
                    chunk.push((2, rotated));
                    planned.push(2);
                }
            } else {
                chunk.push((2, rotated));
                planned.push(2);
            }
            if !chunk.is_empty() {
                chunks.push(chunk);
            }
        }
    }
    if !lite {
        chunks.push(vec![(0, source)]);
        planned.push(0);
    } else if chunks.is_empty() {
        // lite with no viable downscale variants — decode the source directly.
        return decode_with_binarizer(source, binarizer, &hints).map(|(r, g)| (r, g, 0));
    }

    if planned.len() == 1 {
        let (prio, only) = chunks.pop().unwrap().pop().unwrap();
        return decode_with_binarizer(only, binarizer, &hints).map(|(r, g)| (r, g, prio));
    }

    let (tx, rx) = mpsc::channel();
    for chunk in chunks {
        let tx = tx.clone();
        let hints = hints.clone();
        thread::spawn(move || {
            for (priority, variant) in chunk {
                let result = decode_with_binarizer(variant, binarizer, &hints);
                if tx.send((priority, result)).is_err() {
                    // Receiver returned early (a decisive hit); stop working.
                    return;
                }
            }
        });
    }
    drop(tx);

    // States per planned priority: None = pending, Some(None) = miss,
    // Some(Some(hit)) = hit awaiting its turn.
    let max_priority = *planned.iter().max().unwrap();
    let mut states: Vec<Option<Option<(rxing::RXingResult, Option<String>)>>> =
        (0..=max_priority).map(|_| None).collect();
    for p in 0..=max_priority {
        if !planned.contains(&p) {
            states[p] = Some(None); // never scheduled → treat as resolved miss
        }
    }

    while let Ok((priority, result)) = rx.recv() {
        if let Some(hit) = &result {
            if *hit.0.getBarcodeFormat() != BarcodeFormat::QR_CODE {
                return result.map(|(r, g)| (r, g, priority)); // no grid semantics → first hit wins
            }
        }
        states[priority] = Some(result);

        // Decide: walk priorities best-first; a hit whose better-ranked
        // attempts have all missed is final, a pending better rank means wait.
        for p in 0..=max_priority {
            match &states[p] {
                None => break,
                Some(None) => continue,
                Some(Some(_)) => {
                    return states[p].take().unwrap().map(|(r, g)| (r, g, p));
                }
            }
        }
    }

    None
}

/// Half-resolution 2x2 block-average downscale of a Luma8LuminanceSource.
/// Returns None if the result would be too small to decode usefully.
fn downscale_2x(source: &Luma8LuminanceSource) -> Option<Luma8LuminanceSource> {
    let w = source.get_width();
    let h = source.get_height();
    let new_w = w / 2;
    let new_h = h / 2;
    if new_w < 8 || new_h < 8 {
        return None;
    }
    let matrix = source.get_matrix();
    let mut new_buf = Vec::with_capacity(new_w * new_h);
    for y in 0..new_h {
        let row0 = y * 2 * w;
        let row1 = row0 + w;
        for x in 0..new_w {
            let i = x * 2;
            let avg = (matrix[row0 + i] as u32
                + matrix[row0 + i + 1] as u32
                + matrix[row1 + i] as u32
                + matrix[row1 + i + 1] as u32)
                / 4;
            new_buf.push(avg as u8);
        }
    }
    Some(Luma8LuminanceSource::new(
        new_buf,
        new_w as u32,
        new_h as u32,
    ))
}

fn decode_with_binarizer(
    source: Luma8LuminanceSource,
    binarizer: jint,
    hints: &DecodeHints,
) -> Option<(rxing::RXingResult, Option<String>)> {
    if binarizer == 1 {
        decode_bitmap(BinaryBitmap::new(GlobalHistogramBinarizer::new(source)), hints)
    } else {
        decode_bitmap(BinaryBitmap::new(HybridBinarizer::new(source)), hints)
    }
}

/// Decode one binarized frame. For QR hits, also extract the exact module grid
/// of the scanned symbol (see [`qr_exact_grid`]) so the app can re-render a
/// pixel-identical copy instead of re-encoding (which may pick a different
/// mask/segmentation and change the visual pattern).
fn decode_bitmap<B: Binarizer>(
    mut bb: BinaryBitmap<B>,
    hints: &DecodeHints,
) -> Option<(rxing::RXingResult, Option<String>)> {
    let mut reader = MultiFormatReader::default();
    let result = reader.decode_with_hints(&mut bb, hints).ok()?;
    let grid = if *result.getBarcodeFormat() == BarcodeFormat::QR_CODE {
        qr_exact_grid(bb.get_black_matrix(), hints)
    } else {
        None
    };
    Some((result, grid))
}

/// Reconstruct the exact module grid of a scanned QR symbol, serialized as
/// "dim:hex" (row-major bits, MSB-first, hex-encoded).
///
/// The sampled grid may contain bit errors that Reed-Solomon correction papered
/// over during decoding, so this re-detects the symbol, RS-corrects its
/// codewords, and rebuilds the canonical matrix with the symbol's own version,
/// EC level, and mask. Result: the original's exact pattern — including its
/// encoder's segmentation choices, which a plain re-encode cannot reproduce —
/// minus any scanning damage. Returns None on any failure (caller falls back
/// to regular re-encoding).
// ponytail: mirrored symbols fall out at readVersion/RS and fall back to re-encode;
// transpose the sampled grid first if that ever matters.
fn qr_exact_grid(image: &BitMatrix, hints: &DecodeHints) -> Option<String> {
    match qr_exact_grid_stages(image, hints) {
        Ok(grid) => Some(grid),
        Err(stage) => {
            // Surfaced in logcat so mask fallbacks are measurable and
            // diagnosable (paired with the ScanPerf counter in ScannerScreen).
            alog("GridExtract", &format!("grid extraction failed at stage: {stage}"));
            None
        }
    }
}

/// The fallible pipeline behind [`qr_exact_grid`], with each exit labeled so
/// logcat shows WHERE extraction failed instead of just that it did.
fn qr_exact_grid_stages(image: &BitMatrix, hints: &DecodeHints) -> Result<String, &'static str> {
    let detected = Detector::new(image)
        .detect_with_hints(hints)
        .map_err(|_| "detect")?;
    let bits = detected.getBits();

    let mut parser = BitMatrixParser::new(bits.clone()).map_err(|_| "parser-init")?;
    // Read format info before readCodewords, which unmasks the matrix in place
    let format_info = parser.readFormatInformation().map_err(|_| "format-info")?;
    let ec = format_info.getErrorCorrectionLevel();
    let mask = format_info.getDataMask();
    let version = parser.readVersion().map_err(|_| "version")?;
    let codewords = parser.readCodewords().map_err(|_| "codewords")?;

    // RS-correct each de-interleaved block
    let blocks = DataBlock::getDataBlocks(&codewords, version, ec).map_err(|_| "data-blocks")?;
    let rs = ReedSolomonDecoder::new(get_predefined_genericgf(
        PredefinedGenericGF::QrCodeField256,
    ));
    let mut corrected: Vec<(Vec<u8>, usize)> = Vec::with_capacity(blocks.len());
    for block in &blocks {
        let mut cw: Vec<i32> = block.getCodewords().iter().map(|&b| b as i32).collect();
        let num_data = block.getNumDataCodewords() as usize;
        let num_ec = (cw.len() - num_data) as i32;
        rs.decode(&mut cw, num_ec).map_err(|_| "reed-solomon")?;
        corrected.push((cw.into_iter().map(|b| b as u8).collect(), num_data));
    }

    // Re-interleave to transmission order: data codewords round-robin, then EC
    let max_data = corrected
        .iter()
        .map(|(_, nd)| *nd)
        .max()
        .ok_or("no-blocks")?;
    let ec_per_block = corrected[0].0.len() - corrected[0].1;
    let mut stream = BitArray::new();
    for i in 0..max_data {
        for (cw, num_data) in &corrected {
            if i < *num_data {
                stream.appendBits(cw[i] as usize, 8).map_err(|_| "bitstream")?;
            }
        }
    }
    for i in 0..ec_per_block {
        for (cw, num_data) in &corrected {
            stream
                .appendBits(cw[num_data + i] as usize, 8)
                .map_err(|_| "bitstream")?;
        }
    }

    // Rebuild the canonical matrix with the symbol's own version/EC/mask
    let dim = version.getDimensionForVersion();
    let mut matrix = ByteMatrix::new(dim, dim);
    matrix_util::buildMatrix(&stream, &ec, version, mask as i32, &mut matrix)
        .map_err(|_| "rebuild-matrix")?;

    // Pack row-major, MSB-first, hex-encode
    let mut packed = vec![0u8; ((dim * dim) as usize + 7) / 8];
    for y in 0..dim {
        for x in 0..dim {
            if matrix.get(x, y) == 1 {
                let i = (y * dim + x) as usize;
                packed[i / 8] |= 0x80 >> (i % 8);
            }
        }
    }
    let mut out = String::with_capacity(8 + packed.len() * 2);
    out.push_str(&dim.to_string());
    out.push(':');
    for b in packed {
        out.push_str(&format!("{b:02x}"));
    }
    Ok(out)
}

pub(crate) fn format_to_string(fmt: BarcodeFormat) -> String {
    match fmt {
        BarcodeFormat::QR_CODE => "QR_CODE".to_string(),
        BarcodeFormat::CODE_128 => "CODE_128".to_string(),
        BarcodeFormat::CODE_39 => "CODE_39".to_string(),
        BarcodeFormat::CODE_93 => "CODE_93".to_string(),
        BarcodeFormat::EAN_13 => "EAN_13".to_string(),
        BarcodeFormat::EAN_8 => "EAN_8".to_string(),
        BarcodeFormat::UPC_A => "UPC_A".to_string(),
        BarcodeFormat::UPC_E => "UPC_E".to_string(),
        BarcodeFormat::ITF => "ITF".to_string(),
        BarcodeFormat::CODABAR => "CODABAR".to_string(),
        BarcodeFormat::PDF_417 => "PDF_417".to_string(),
        BarcodeFormat::AZTEC => "AZTEC".to_string(),
        BarcodeFormat::DATA_MATRIX => "DATA_MATRIX".to_string(),
        BarcodeFormat::TELEPEN => "TELEPEN".to_string(),
        other => format!("{}", other),
    }
}

/// JNI entry point: `com.caravanfire.calmqr.rust.RustBridge.generateBarcode`
///
/// Takes a content string, format string, and desired pixel dimensions, generates
/// a barcode/QR code using rxing, and returns the pixel data as a byte array.
/// Format: [width: 4 bytes BE][height: 4 bytes BE][ARGB pixels: width*height*4 bytes]
/// Returns null if encoding fails.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_generateBarcode<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    content: JString<'local>,
    format: JString<'local>,
    width: jint,
    height: jint,
) -> JByteArray<'local> {
    let content: String = match env.get_string(&content) {
        Ok(s) => s.into(),
        Err(_) => return JByteArray::from(JObject::null()),
    };

    let format_str: String = match env.get_string(&format) {
        Ok(s) => s.into(),
        Err(_) => return JByteArray::from(JObject::null()),
    };

    let barcode_format = match format_str.as_str() {
        "QR_CODE" => BarcodeFormat::QR_CODE,
        "CODE_128" => BarcodeFormat::CODE_128,
        "CODE_39" => BarcodeFormat::CODE_39,
        "CODE_93" => BarcodeFormat::CODE_93,
        "EAN_13" => BarcodeFormat::EAN_13,
        "EAN_8" => BarcodeFormat::EAN_8,
        "UPC_A" => BarcodeFormat::UPC_A,
        "UPC_E" => BarcodeFormat::UPC_E,
        "ITF" => BarcodeFormat::ITF,
        "CODABAR" => BarcodeFormat::CODABAR,
        "PDF_417" => BarcodeFormat::PDF_417,
        "AZTEC" => BarcodeFormat::AZTEC,
        "DATA_MATRIX" => BarcodeFormat::DATA_MATRIX,
        "TELEPEN" => BarcodeFormat::TELEPEN,
        _ => BarcodeFormat::QR_CODE, // fallback to QR
    };

    let w = width as i32;
    let h = height as i32;

    let writer = MultiFormatWriter::default();

    let bit_matrix = if barcode_format == BarcodeFormat::QR_CODE {
        writer.encode(&content, &barcode_format, w, w)
    } else {
        writer.encode(&content, &barcode_format, w, h)
    };

    let bit_matrix = match bit_matrix {
        Ok(m) => m,
        Err(_) => return JByteArray::from(JObject::null()),
    };

    let bm_width = bit_matrix.getWidth() as usize;
    let bm_height = bit_matrix.getHeight() as usize;

    // Trim the quiet zone (leading/trailing white rows and columns)
    let mut min_x = bm_width;
    let mut max_x = 0usize;
    let mut min_y = bm_height;
    let mut max_y = 0usize;
    for y in 0..bm_height {
        for x in 0..bm_width {
            if bit_matrix.get(x as u32, y as u32) {
                if x < min_x { min_x = x; }
                if x > max_x { max_x = x; }
                if y < min_y { min_y = y; }
                if y > max_y { max_y = y; }
            }
        }
    }
    // Fallback if no black pixels found (shouldn't happen)
    if min_x > max_x || min_y > max_y {
        min_x = 0;
        max_x = bm_width.saturating_sub(1);
        min_y = 0;
        max_y = bm_height.saturating_sub(1);
    }

    let crop_w = max_x - min_x + 1;
    let crop_h = max_y - min_y + 1;

    // Build result: [width: 4B BE][height: 4B BE][ARGB pixels]
    let pixel_count = crop_w * crop_h;
    let total_len = 8 + pixel_count * 4;
    let mut result = Vec::with_capacity(total_len);
    result.extend_from_slice(&(crop_w as i32).to_be_bytes());
    result.extend_from_slice(&(crop_h as i32).to_be_bytes());

    for y in min_y..=max_y {
        for x in min_x..=max_x {
            if bit_matrix.get(x as u32, y as u32) {
                // Black pixel (ARGB)
                result.extend_from_slice(&[0xFFu8, 0x00, 0x00, 0x00]);
            } else {
                // White pixel (ARGB)
                result.extend_from_slice(&[0xFF, 0xFF, 0xFF, 0xFF]);
            }
        }
    }

    let result_i8: Vec<i8> = result.into_iter().map(|b| b as i8).collect();
    match env.new_byte_array(result_i8.len() as i32) {
        Ok(arr) => {
            if env.set_byte_array_region(&arr, 0, &result_i8).is_err() {
                return JByteArray::from(JObject::null());
            }
            arr
        }
        Err(_) => JByteArray::from(JObject::null()),
    }
}
