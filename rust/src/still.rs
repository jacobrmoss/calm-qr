//! Still-image scanning ("Image Upload"): one photo picked from the gallery,
//! scanned once at full strength.
//!
//! Kotlin hands over the encoded file. JPEG and PNG decode here straight to
//! grayscale — a JPEG's Y channel *is* the luma, so no full-colour bitmap ever
//! exists (8 MB for an 8 MP photo instead of 32 MB of ARGB). Anything else
//! (HEIC, WebP, GIF, ...) is reported unsupported and Kotlin decodes it with
//! Android's decoders, handing back RGBA pixels for [`rgba_to_luma`].
//!
//! The scan runs every variant the app has on a small pool, cheapest likely
//! hit first: a resolution pyramid (half, full, quarter — plus a 2x upscale
//! for small images, whose modules may be only a few pixels wide) under both
//! binarizers, each with the normal and the inverted pass. Rotation needs no
//! variants: QR/Aztec/DataMatrix detection is rotation-invariant and the 1D
//! reader retries a quarter-turned copy itself under TryHarder.
//!
//! Acceptance mirrors the camera engine (engine.rs `apply_completion` /
//! `apply_releases`), with "frames" replaced by "variants" — see [`Judge`].
//!
//! A scan can be cancelled (the user taps the photo again, or leaves the
//! screen): see [`CancelLine`]. The scan call then returns "no code" within
//! [`CANCEL_POLL`]; a variant already being decoded cannot be interrupted, so
//! it finishes on its worker thread and is thrown away, and no further
//! variant starts.

use crate::engine::is_1d;
use crate::{
    alog, analyze_frame, app_hints, decode_with_binarizer, format_to_string, rotate_orientation,
    DecodeHit,
};
use jni::objects::{JByteArray, JByteBuffer, JClass, JObject, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use rxing::Luma8LuminanceSource;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc};
use std::thread;
use std::time::{Duration, Instant};

/// Longest side the pyramid's full level may have; bigger images are box-
/// downscaled first (a 50 MP panorama would otherwise cost ~50 MB per copy
/// and seconds per pass, for detail no printed code needs).
const MAX_SIDE: usize = 4096;
/// A level is halved again only while its shorter side stays >= this — the
/// same threshold as `decode_luma`'s downscale variant.
const MIN_DOWNSCALE_SIDE: usize = 480;
/// Images whose longer side is at most this also get a 2x upscale level.
const UPSCALE_MAX_SIDE: usize = 1024;
/// Decode threads: one core stays free for the UI. The camera is off in
/// image-upload mode, so the AF headroom the frame path keeps is not needed.
const WORKERS: usize = 3;
/// How often a scan that is waiting for its variants looks for a cancel.
const CANCEL_POLL: Duration = Duration::from_millis(25);

/// Which gallery scans have been cancelled. Kotlin numbers its scans with
/// tickets that only ever grow and cancels by ticket: every scan up to and
/// including the highest cancelled ticket is cancelled. So a cancel that
/// lands before its scan has reached Rust still stops it, and a scan started
/// afterwards (a higher ticket) is untouched. Tickets start at 1; zero or
/// less means "cannot be cancelled".
pub(crate) struct CancelLine(AtomicI64);

impl CancelLine {
    pub(crate) const fn new() -> Self {
        CancelLine(AtomicI64::new(0))
    }

    pub(crate) fn cancel_up_to(&self, ticket: i64) {
        self.0.fetch_max(ticket, Ordering::Relaxed);
    }

    pub(crate) fn is_cancelled(&self, ticket: i64) -> bool {
        ticket > 0 && ticket <= self.0.load(Ordering::Relaxed)
    }
}

/// The scans cancelled through `RustBridge.cancelImageScans`.
static CANCELLED: CancelLine = CancelLine::new();

/// `ImageScanResult.status` values (see ImageScanResult.kt).
const STATUS_NOT_FOUND: jint = 0;
const STATUS_FOUND: jint = 1;
const STATUS_UNSUPPORTED: jint = 2;

/// A tightly packed (stride == width) grayscale image.
#[derive(Clone, Debug)]
pub(crate) struct Luma {
    pub data: Vec<u8>,
    pub w: usize,
    pub h: usize,
}

pub(crate) enum ImageDecode {
    Ok(Luma),
    /// Neither JPEG nor PNG.
    Unsupported,
    /// Looked like JPEG/PNG but did not decode here (CMYK JPEG, corrupt or
    /// oversized data): Android's decoders get a try too.
    Failed(String),
}

/// Sniff the container by magic bytes and decode to luma.
pub(crate) fn decode_image(bytes: &[u8]) -> ImageDecode {
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        decode_jpeg(bytes)
    } else if bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
        decode_png(bytes)
    } else {
        ImageDecode::Unsupported
    }
}

fn decode_jpeg(bytes: &[u8]) -> ImageDecode {
    use zune_core::bytestream::ZCursor;
    use zune_core::colorspace::ColorSpace;
    use zune_core::options::DecoderOptions;
    use zune_jpeg::JpegDecoder;

    let options = DecoderOptions::default().jpeg_set_out_colorspace(ColorSpace::Luma);
    let mut decoder = JpegDecoder::new_with_options(ZCursor::new(bytes), options);
    let data = match decoder.decode() {
        Ok(d) => d,
        Err(e) => return ImageDecode::Failed(format!("jpeg: {e:?}")),
    };
    let Some(info) = decoder.info() else {
        return ImageDecode::Failed("jpeg: no header info".into());
    };
    let (w, h) = (info.width as usize, info.height as usize);
    // A colorspace the decoder cannot turn into luma (e.g. CMYK) comes back
    // in its own layout — let Android's decoder handle those.
    if w == 0 || h == 0 || data.len() != w * h {
        return ImageDecode::Failed(format!("jpeg: {} bytes for {w}x{h} luma", data.len()));
    }
    ImageDecode::Ok(Luma { data, w, h })
}

fn decode_png(bytes: &[u8]) -> ImageDecode {
    let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    // Palette/low-bit-depth/tRNS expand to 8-bit channels; 16-bit strips to 8.
    decoder.set_transformations(png::Transformations::normalize_to_color8());
    let mut reader = match decoder.read_info() {
        Ok(r) => r,
        Err(e) => return ImageDecode::Failed(format!("png: {e}")),
    };
    let Some(size) = reader.output_buffer_size() else {
        return ImageDecode::Failed("png: image too large".into());
    };
    let mut buf = vec![0u8; size];
    let info = match reader.next_frame(&mut buf) {
        Ok(i) => i,
        Err(e) => return ImageDecode::Failed(format!("png: {e}")),
    };
    let (w, h, stride) = (info.width as usize, info.height as usize, info.line_size);
    let channels = match info.color_type {
        png::ColorType::Grayscale => 1,
        png::ColorType::GrayscaleAlpha => 2,
        png::ColorType::Rgb => 3,
        png::ColorType::Rgba => 4,
        png::ColorType::Indexed => return ImageDecode::Failed("png: palette not expanded".into()),
    };
    if w == 0 || h == 0 || stride < w * channels || buf.len() < (h - 1) * stride + w * channels {
        return ImageDecode::Failed("png: inconsistent frame size".into());
    }
    let mut data = Vec::with_capacity(w * h);
    for y in 0..h {
        let row = &buf[y * stride..y * stride + w * channels];
        match channels {
            1 => data.extend_from_slice(row),
            2 => data.extend(row.chunks_exact(2).map(|p| luma_over_white(p[0], p[0], p[0], p[1]))),
            3 => data.extend(row.chunks_exact(3).map(|p| luma_over_white(p[0], p[1], p[2], 255))),
            _ => data.extend(row.chunks_exact(4).map(|p| luma_over_white(p[0], p[1], p[2], p[3]))),
        }
    }
    ImageDecode::Ok(Luma { data, w, h })
}

/// BT.601 luma of an sRGB pixel composited over white. Generated QR PNGs are
/// often black modules on a transparent background — composited over black
/// (or read without alpha) the whole symbol would vanish.
#[inline]
fn luma_over_white(r: u8, g: u8, b: u8, a: u8) -> u8 {
    let y = (77 * r as u32 + 150 * g as u32 + 29 * b as u32 + 128) >> 8;
    let a = a as u32;
    ((y * a + 255 * (255 - a) + 127) / 255) as u8
}

/// RGBA_8888 pixels (Android `Bitmap.Config.ARGB_8888` memory order) to luma.
pub(crate) fn rgba_to_luma(px: &[u8], w: usize, h: usize, stride: usize) -> Luma {
    let mut data = Vec::with_capacity(w * h);
    for y in 0..h {
        let row = &px[y * stride..y * stride + w * 4];
        data.extend(row.chunks_exact(4).map(|p| luma_over_white(p[0], p[1], p[2], p[3])));
    }
    Luma { data, w, h }
}

/// Integer-factor box-filter downscale.
fn box_downscale(src: &Luma, f: usize) -> Luma {
    let (w, h) = (src.w / f, src.h / f);
    let area = (f * f) as u32;
    let mut data = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            let mut sum = 0u32;
            for dy in 0..f {
                let row = (y * f + dy) * src.w + x * f;
                sum += src.data[row..row + f].iter().map(|&v| v as u32).sum::<u32>();
            }
            data.push(((sum + area / 2) / area) as u8);
        }
    }
    Luma { data, w, h }
}

/// 2x bilinear upscale (pixel-center aligned).
fn upscale_2x(src: &Luma) -> Luma {
    let (w, h) = (src.w * 2, src.h * 2);
    let mut data = vec![0u8; w * h];
    let px = |x: usize, y: usize| src.data[y * src.w + x] as f32;
    for y in 0..h {
        let sy = ((y as f32 + 0.5) * 0.5 - 0.5).max(0.0);
        let y0 = (sy as usize).min(src.h - 1);
        let y1 = (y0 + 1).min(src.h - 1);
        let fy = sy - y0 as f32;
        for x in 0..w {
            let sx = ((x as f32 + 0.5) * 0.5 - 0.5).max(0.0);
            let x0 = (sx as usize).min(src.w - 1);
            let x1 = (x0 + 1).min(src.w - 1);
            let fx = sx - x0 as f32;
            let v = px(x0, y0) * (1.0 - fx) * (1.0 - fy)
                + px(x1, y0) * fx * (1.0 - fy)
                + px(x0, y1) * (1.0 - fx) * fy
                + px(x1, y1) * fx * fy;
            data[y * w + x] = (v + 0.5) as u8;
        }
    }
    Luma { data, w, h }
}

/// An accepted scan, ready for DecodeResult.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct StillScan {
    pub text: String,
    pub format: String,
    /// The exact module grid with the camera engine's prefixes
    /// (`[o<k>:][inv:]dim:hex`), or None.
    pub grid: Option<String>,
}

/// One variant's read, reduced to what acceptance needs.
#[derive(Clone, Debug)]
pub(crate) struct Read {
    pub format: String,
    pub text: String,
    pub grid: Option<String>,
}

/// Acceptance across the variants of one still image — the camera engine's
/// rules with "frames" replaced by "variants":
/// - QR: the first read WITH its exact grid wins. A gridless QR is held; at
///   the end normal mode accepts its payload (rendered by re-encoding),
///   exact mode never falls back.
/// - 1D: every variant read is a vote (UPC-A ≡ EAN-13 with a leading 0).
///   Two agreeing votes confirm; exact mode needs a lead of two over any
///   rival. A lone or tied read is the misread signature and is dropped.
///   When the cheap detector sees a QR symbol but no bar structure, 1D reads
///   are texture misreads and cast no vote at all.
/// - Aztec / DataMatrix / PDF417 / Micro QR are RS-protected: first read wins.
pub(crate) struct Judge {
    exact: bool,
    oned_gated: bool,
    held_qr: Option<Read>,
    votes: Vec<(String, String, u32)>,
}

impl Judge {
    pub(crate) fn new(exact: bool, oned_gated: bool) -> Self {
        Judge { exact, oned_gated, held_qr: None, votes: Vec::new() }
    }

    /// Offer one variant's read. `Some` = decided; stop scanning.
    pub(crate) fn offer(&mut self, r: Read) -> Option<StillScan> {
        if r.format == "QR_CODE" {
            if r.grid.is_some() {
                return Some(StillScan { text: r.text, format: r.format, grid: r.grid });
            }
            if self.held_qr.is_none() {
                self.held_qr = Some(r);
            }
            return None;
        }
        if is_1d(&r.format) {
            if self.oned_gated {
                alog("ScanPerf", &format!("still: 1D read ignored — QR in view, no bar structure ({})", r.format));
                return None;
            }
            let (kf, kc) = if r.format == "EAN_13" && r.text.len() == 13 && r.text.starts_with('0') {
                ("UPC_A".to_string(), r.text[1..].to_string())
            } else {
                (r.format, r.text)
            };
            let idx = match self.votes.iter().position(|(f, c, _)| *f == kf && *c == kc) {
                Some(i) => {
                    self.votes[i].2 += 1;
                    i
                }
                None => {
                    self.votes.push((kf, kc, 1));
                    self.votes.len() - 1
                }
            };
            let votes = self.votes[idx].2;
            let rival = self
                .votes
                .iter()
                .enumerate()
                .filter(|(i, _)| *i != idx)
                .map(|(_, v)| v.2)
                .max()
                .unwrap_or(0);
            let confirmed = if self.exact { votes >= 2 && votes - rival >= 2 } else { votes >= 2 };
            if confirmed {
                let (f, c, _) = self.votes[idx].clone();
                return Some(StillScan { text: c, format: f, grid: None });
            }
            return None;
        }
        Some(StillScan { text: r.text, format: r.format, grid: None })
    }

    /// Every variant has run.
    pub(crate) fn finish(self) -> Option<StillScan> {
        if self.exact {
            return None;
        }
        self.held_qr.map(|q| StillScan { text: q.text, format: q.format, grid: None })
    }
}

/// Scan one decoded image with every variant. `rotation_degrees` is the
/// clockwise turn that displays the image upright (MediaStore ORIENTATION);
/// it is composed into the exact grid's presentation orientation so the
/// saved copy looks like the code did in the photo. `cancelled` is asked
/// before the work starts and every [`CANCEL_POLL`] while it runs; once it
/// says yes the scan returns None.
pub(crate) fn scan_still(
    img: Luma,
    rotation_degrees: i32,
    exact: bool,
    cancelled: &dyn Fn() -> bool,
) -> Option<StillScan> {
    let hints = app_hints(0, true);
    let decode = move |level: &Luma, binarizer: jint| {
        Luma8LuminanceSource::new(level.data.clone(), level.w as u32, level.h as u32)
            .ok()
            .and_then(|src| decode_with_binarizer(src, binarizer, &hints, 0))
    };
    scan_still_with(img, rotation_degrees, exact, cancelled, Arc::new(decode))
}

/// One decode attempt: one pyramid level under one binarizer.
type DecodeVariant = dyn Fn(&Luma, jint) -> Option<DecodeHit> + Send + Sync;

/// [`scan_still`] with the per-variant decoder passed in.
fn scan_still_with(
    img: Luma,
    rotation_degrees: i32,
    exact: bool,
    cancelled: &dyn Fn() -> bool,
    decode: Arc<DecodeVariant>,
) -> Option<StillScan> {
    let t0 = Instant::now();
    let called_off = || {
        let off = cancelled();
        if off {
            alog("ScanPerf", &format!("still scan cancelled after {}ms", t0.elapsed().as_millis()));
        }
        off
    };
    if img.w < 8 || img.h < 8 || img.data.len() < img.w * img.h {
        return None;
    }
    if called_off() {
        return None;
    }
    let long = img.w.max(img.h);
    let full = if long > MAX_SIDE { box_downscale(&img, long.div_ceil(MAX_SIDE)) } else { img };
    let full = Arc::new(full);
    let half = (full.w.min(full.h) >= MIN_DOWNSCALE_SIDE).then(|| Arc::new(box_downscale(&full, 2)));
    let quarter = half
        .as_ref()
        .filter(|h| h.w.min(h.h) >= MIN_DOWNSCALE_SIDE)
        .map(|h| Arc::new(box_downscale(h, 2)));
    let double = (full.w.max(full.h) <= UPSCALE_MAX_SIDE).then(|| Arc::new(upscale_2x(&full)));

    // The camera's cheap fused detector, on the level nearest a camera
    // frame's size: gates 1D votes exactly as the frame path does.
    let probe = half.as_ref().unwrap_or(&full);
    let sighting = analyze_frame(&probe.data, probe.w, probe.w, probe.h);
    let oned_gated = sighting.qr.is_some() && sighting.oned.is_none();

    // Cheapest likely hit first; HybridBinarizer before GlobalHistogram.
    let mut items: Vec<(String, Arc<Luma>, jint)> = Vec::new();
    for (bin, tag) in [(0, "hybrid"), (1, "global")] {
        if let Some(l) = &half {
            items.push((format!("half/{tag}"), l.clone(), bin));
        }
        items.push((format!("full/{tag}"), full.clone(), bin));
        if let Some(l) = &quarter {
            items.push((format!("quarter/{tag}"), l.clone(), bin));
        }
        if let Some(l) = &double {
            items.push((format!("2x/{tag}"), l.clone(), bin));
        }
    }
    alog(
        "ScanPerf",
        &format!(
            "still scan {}x{}: {} variants, qr sighted={}, bars sighted={}, exact={exact}",
            full.w,
            full.h,
            items.len(),
            sighting.qr.is_some(),
            sighting.oned.is_some()
        ),
    );

    // Building the pyramid of a large photo takes a while: look again.
    if called_off() {
        return None;
    }
    let items = Arc::new(items);
    let next = Arc::new(AtomicUsize::new(0));
    let stop = Arc::new(AtomicBool::new(false));
    let (tx, rx) = mpsc::channel::<(usize, Option<DecodeHit>, u128)>();
    for _ in 0..WORKERS.min(items.len()) {
        let (items, next, stop, decode, tx) =
            (items.clone(), next.clone(), stop.clone(), decode.clone(), tx.clone());
        thread::spawn(move || loop {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            let i = next.fetch_add(1, Ordering::Relaxed);
            let Some((_, level, binarizer)) = items.get(i) else {
                return;
            };
            let t = Instant::now();
            let hit = decode(level, *binarizer);
            // A send error means the scan already decided: stop.
            if tx.send((i, hit, t.elapsed().as_millis())).is_err() {
                return;
            }
        });
    }
    drop(tx);

    let quarter_turns = (rotation_degrees / 90).rem_euclid(4);
    let mut judge = Judge::new(exact, oned_gated);
    loop {
        // A cancel does not wait for the variants in flight: each of them
        // finishes on its own thread, fails to send, and exits.
        if called_off() {
            stop.store(true, Ordering::Relaxed);
            return None;
        }
        let (i, hit, ms) = match rx.recv_timeout(CANCEL_POLL) {
            Ok(done) => done,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        };
        let name = &items[i].0;
        let Some((result, grid, inverted, orientation)) = hit else {
            alog("ScanPerf", &format!("still {name}: miss in {ms}ms"));
            continue;
        };
        let format = format_to_string(*result.getBarcodeFormat());
        let grid = grid.map(|g| {
            let g = if inverted { format!("inv:{g}") } else { g };
            let o = rotate_orientation(orientation, quarter_turns);
            if o != 0 {
                format!("o{o}:{g}")
            } else {
                g
            }
        });
        alog(
            "ScanPerf",
            &format!("still {name}: {format} grid={} inverted={inverted} in {ms}ms", grid.is_some()),
        );
        let read = Read { format, text: result.getText().to_string(), grid };
        if let Some(done) = judge.offer(read) {
            stop.store(true, Ordering::Relaxed);
            alog(
                "ScanPerf",
                &format!("still scan accepted {} after {}ms", done.format, t0.elapsed().as_millis()),
            );
            return Some(done);
        }
    }
    let out = judge.finish();
    alog(
        "ScanPerf",
        &match &out {
            Some(s) => format!("still scan fell back to {} without grid after {}ms", s.format, t0.elapsed().as_millis()),
            None => format!("still scan: no code after {}ms", t0.elapsed().as_millis()),
        },
    );
    out
}

fn new_decode_result<'local>(env: &mut JNIEnv<'local>, scan: &StillScan) -> Option<JObject<'local>> {
    let text = JObject::from(env.new_string(&scan.text).ok()?);
    let format = JObject::from(env.new_string(&scan.format).ok()?);
    let grid = match &scan.grid {
        Some(g) => JObject::from(env.new_string(g).ok()?),
        None => JObject::null(),
    };
    let class = env.find_class("com/caravanfire/calmqr/rust/DecodeResult").ok()?;
    env.new_object(
        class,
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;FF)V",
        &[
            JValue::Object(&text),
            JValue::Object(&format),
            JValue::Object(&grid),
            JValue::Float(-1.0),
            JValue::Float(-1.0),
        ],
    )
    .ok()
}

fn new_image_scan_result<'local>(
    env: &mut JNIEnv<'local>,
    status: jint,
    result: Option<JObject<'local>>,
) -> JObject<'local> {
    let result = result.unwrap_or_else(|| JObject::null());
    let Ok(class) = env.find_class("com/caravanfire/calmqr/rust/ImageScanResult") else {
        return JObject::null();
    };
    env.new_object(
        class,
        "(ILcom/caravanfire/calmqr/rust/DecodeResult;)V",
        &[JValue::Int(status), JValue::Object(&result)],
    )
    .unwrap_or_else(|_| JObject::null())
}

/// JNI entry point: `RustBridge.scanImageFile(data, rotationDegrees, exactMatch, ticket)`.
///
/// Decodes an encoded image file (JPEG/PNG) to luma and scans it at full
/// strength. Returns an `ImageScanResult`: FOUND with its DecodeResult,
/// NOT_FOUND, or UNSUPPORTED (not JPEG/PNG, or failed to decode here — the
/// caller then decodes with Android and calls `scanImagePixels`). A scan
/// cancelled through `cancelImageScans` reports NOT_FOUND.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_scanImageFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
    rotation_degrees: jint,
    exact: jboolean,
    ticket: jlong,
) -> JObject<'local> {
    let cancelled = || CANCELLED.is_cancelled(ticket);
    // Cancelled before it got here: skip copying and decoding the file.
    if cancelled() {
        alog("ScanPerf", "still: cancelled before decoding");
        return new_image_scan_result(&mut env, STATUS_NOT_FOUND, None);
    }
    let Ok(bytes) = env.convert_byte_array(&data) else {
        return new_image_scan_result(&mut env, STATUS_UNSUPPORTED, None);
    };
    let t = Instant::now();
    let img = match decode_image(&bytes) {
        ImageDecode::Ok(img) => img,
        ImageDecode::Unsupported => {
            alog("ScanPerf", "still: not JPEG/PNG — handing back to Android's decoder");
            return new_image_scan_result(&mut env, STATUS_UNSUPPORTED, None);
        }
        ImageDecode::Failed(why) => {
            alog("ScanPerf", &format!("still: decode failed ({why}) — handing back to Android's decoder"));
            return new_image_scan_result(&mut env, STATUS_UNSUPPORTED, None);
        }
    };
    drop(bytes);
    alog("ScanPerf", &format!("still decode {}x{} luma in {}ms", img.w, img.h, t.elapsed().as_millis()));
    match scan_still(img, rotation_degrees, exact != 0, &cancelled) {
        Some(scan) => {
            let result = new_decode_result(&mut env, &scan);
            let status = if result.is_some() { STATUS_FOUND } else { STATUS_NOT_FOUND };
            new_image_scan_result(&mut env, status, result)
        }
        None => new_image_scan_result(&mut env, STATUS_NOT_FOUND, None),
    }
}

/// JNI entry point: `RustBridge.scanImagePixels(rgba, width, height, rowStride, rotationDegrees, exactMatch, ticket)`.
///
/// The fallback for formats Rust does not decode: RGBA_8888 pixels from an
/// Android-decoded bitmap, in a DIRECT ByteBuffer valid for this call.
/// Returns a DecodeResult, or null (no code, or cancelled).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_scanImagePixels<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rgba: JByteBuffer<'local>,
    width: jint,
    height: jint,
    row_stride: jint,
    rotation_degrees: jint,
    exact: jboolean,
    ticket: jlong,
) -> JObject<'local> {
    let cancelled = || CANCELLED.is_cancelled(ticket);
    if cancelled() {
        alog("ScanPerf", "still: cancelled before converting the pixels");
        return JObject::null();
    }
    let (w, h) = (width.max(0) as usize, height.max(0) as usize);
    let stride = row_stride.max(width * 4).max(0) as usize;
    if w == 0 || h == 0 {
        return JObject::null();
    }
    let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&rgba), env.get_direct_buffer_capacity(&rgba))
    else {
        return JObject::null();
    };
    if cap < (h - 1) * stride + w * 4 {
        return JObject::null();
    }
    // SAFETY: ptr/cap describe a direct ByteBuffer the caller keeps alive for
    // the duration of this call.
    let px = unsafe { std::slice::from_raw_parts(ptr, cap) };
    let img = rgba_to_luma(px, w, h, stride);
    match scan_still(img, rotation_degrees, exact != 0, &cancelled) {
        Some(scan) => new_decode_result(&mut env, &scan).unwrap_or_else(|| JObject::null()),
        None => JObject::null(),
    }
}

/// JNI entry point: `RustBridge.cancelImageScans(upToTicket)`.
///
/// Cancels every gallery scan whose ticket is `upToTicket` or lower, whether
/// it is running or has not reached Rust yet. Returns at once; the scan calls
/// themselves return within [`CANCEL_POLL`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_cancelImageScans<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    up_to_ticket: jlong,
) {
    alog("ScanPerf", &format!("still: cancel requested up to ticket {up_to_ticket}"));
    CANCELLED.cancel_up_to(up_to_ticket);
}

#[cfg(test)]
mod tests {
    use super::*;
    use rxing::{BarcodeFormat, MultiFormatWriter, Writer};

    /// Render `text` as a QR symbol: `module` px per module, 4-module quiet
    /// zone, dark = 0 (or 255 when `inverted`), on a `bg` background canvas
    /// of `cw` x `ch` with the symbol's top-left at (`ox`, `oy`).
    fn qr_on_canvas(
        text: &str,
        module: usize,
        cw: usize,
        ch: usize,
        ox: usize,
        oy: usize,
        inverted: bool,
    ) -> Luma {
        let m = MultiFormatWriter::default().encode(text, &BarcodeFormat::QR_CODE, 0, 0).unwrap();
        let (mw, mh) = (m.getWidth() as usize, m.getHeight() as usize);
        let (dark, light) = if inverted { (255u8, 0u8) } else { (0u8, 255u8) };
        let mut data = vec![if inverted { 40u8 } else { 215u8 }; cw * ch];
        for my in 0..mh {
            for mx in 0..mw {
                let v = if m.get(mx as u32, my as u32) { dark } else { light };
                for dy in 0..module {
                    let row = (oy + my * module + dy) * cw + ox + mx * module;
                    data[row..row + module].fill(v);
                }
            }
        }
        Luma { data, w: cw, h: ch }
    }

    fn png_bytes(data: &[u8], w: usize, h: usize, color: png::ColorType) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut enc = png::Encoder::new(&mut out, w as u32, h as u32);
            enc.set_color(color);
            enc.set_depth(png::BitDepth::Eight);
            enc.write_header().unwrap().write_image_data(data).unwrap();
        }
        out
    }

    fn read(format: &str, text: &str, grid: Option<&str>) -> Read {
        Read { format: format.into(), text: text.into(), grid: grid.map(Into::into) }
    }

    /// Nobody cancels.
    fn never() -> bool {
        false
    }

    /// A decoder that counts its calls, then hangs until `release` is dropped
    /// (and then reports a miss).
    struct Stuck {
        decode: Arc<DecodeVariant>,
        started: Arc<AtomicUsize>,
        release: mpsc::Sender<()>,
    }

    fn stuck_decoder() -> Stuck {
        let (release, gate) = mpsc::channel::<()>();
        let gate = std::sync::Mutex::new(gate);
        let started = Arc::new(AtomicUsize::new(0));
        let decode: Arc<DecodeVariant> = {
            let started = started.clone();
            Arc::new(move |_: &Luma, _: jint| {
                started.fetch_add(1, Ordering::SeqCst);
                let _ = gate.lock().unwrap().recv();
                None
            })
        };
        Stuck { decode, started, release }
    }

    /// Scan a blank 2 MP photo on another thread with `stuck`'s decoder.
    /// Returns the cancel switch, where the scan's result arrives, and the thread.
    fn start_scan(
        stuck: &Stuck,
    ) -> (Arc<AtomicBool>, mpsc::Receiver<Option<StillScan>>, thread::JoinHandle<()>) {
        let cancel = Arc::new(AtomicBool::new(false));
        let (done, result) = mpsc::channel();
        let scan = {
            let (cancel, decode) = (cancel.clone(), stuck.decode.clone());
            thread::spawn(move || {
                let photo = Luma { data: vec![200u8; 1224 * 1632], w: 1224, h: 1632 };
                let cancelled = || cancel.load(Ordering::SeqCst);
                let _ = done.send(scan_still_with(photo, 0, false, &cancelled, decode));
            })
        };
        (cancel, result, scan)
    }

    fn wait_until(what: &str, done: impl Fn() -> bool) {
        let t = Instant::now();
        while !done() {
            assert!(t.elapsed() < Duration::from_secs(5), "timed out waiting until {what}");
            thread::sleep(Duration::from_millis(1));
        }
    }

    #[test]
    fn small_qr_in_a_large_photo_is_found_with_its_exact_grid() {
        // 8 MP canvas (the Kompakt camera's size), symbol ~4% of the width.
        let img = qr_on_canvas("https://calm.example/photo", 4, 2448, 3264, 1700, 2600, false);
        let s = scan_still(img, 0, true, &never).expect("code found");
        assert_eq!(s.format, "QR_CODE");
        assert_eq!(s.text, "https://calm.example/photo");
        let g = s.grid.expect("exact grid");
        assert!(!g.starts_with("inv:") && !g.starts_with('o'), "upright normal grid, got {g}");
    }

    #[test]
    fn screenshot_sized_qr_is_found() {
        let img = qr_on_canvas("WIFI:S:calm;T:WPA;P:secret;;", 3, 480, 800, 90, 200, false);
        let s = scan_still(img, 0, false, &never).expect("code found");
        assert_eq!(s.text, "WIFI:S:calm;T:WPA;P:secret;;");
        assert!(s.grid.is_some());
    }

    #[test]
    fn inverted_qr_keeps_its_polarity_in_the_grid() {
        let img = qr_on_canvas("inverted", 6, 1200, 1600, 300, 400, true);
        let s = scan_still(img, 0, false, &never).expect("code found");
        assert!(s.grid.unwrap().starts_with("inv:"));
    }

    #[test]
    fn photo_rotation_is_composed_into_the_grid_orientation() {
        let img = qr_on_canvas("turned", 6, 1200, 1600, 300, 400, false);
        let s = scan_still(img, 90, false, &never).expect("code found");
        assert!(s.grid.unwrap().starts_with("o1:"));
    }

    #[test]
    fn blank_photo_has_no_code() {
        let img = Luma { data: vec![200u8; 1224 * 1632], w: 1224, h: 1632 };
        assert_eq!(scan_still(img, 0, false, &never), None);
    }

    #[test]
    fn a_scan_cancelled_before_it_starts_reports_no_code() {
        // `screenshot_sized_qr_is_found` reads this very photo when nobody cancels.
        let img = qr_on_canvas("WIFI:S:calm;T:WPA;P:secret;;", 3, 480, 800, 90, 200, false);
        assert_eq!(scan_still(img, 0, false, &|| true), None);
    }

    #[test]
    fn cancelling_returns_while_its_variants_are_still_running() {
        let stuck = stuck_decoder();
        let (cancel, result, scan) = start_scan(&stuck);
        wait_until("a variant is running", || stuck.started.load(Ordering::SeqCst) > 0);
        cancel.store(true, Ordering::SeqCst);
        let out = result
            .recv_timeout(Duration::from_secs(5))
            .expect("the scan returns although its variants are still running");
        assert_eq!(out, None);
        drop(stuck.release);
        scan.join().unwrap();
    }

    #[test]
    fn after_a_cancel_no_further_variant_is_started() {
        // The photo has six variants (three sizes, two binarizers) for three workers.
        let stuck = stuck_decoder();
        let (cancel, result, scan) = start_scan(&stuck);
        wait_until("every worker is busy", || stuck.started.load(Ordering::SeqCst) == WORKERS);
        cancel.store(true, Ordering::SeqCst);
        result.recv_timeout(Duration::from_secs(5)).expect("the scan returns");
        drop(stuck.release);
        scan.join().unwrap();
        // Each worker holds a handle on the decoder until it exits.
        wait_until("the workers have gone", || Arc::strong_count(&stuck.decode) == 1);
        assert_eq!(stuck.started.load(Ordering::SeqCst), WORKERS, "no variant beyond the three in flight may start");
    }

    #[test]
    fn cancelling_a_ticket_cancels_it_and_the_ones_before_it() {
        let line = CancelLine::new();
        line.cancel_up_to(5);
        assert!(line.is_cancelled(5));
        assert!(line.is_cancelled(1));
    }

    #[test]
    fn a_ticket_issued_after_a_cancel_is_untouched() {
        let line = CancelLine::new();
        assert!(!line.is_cancelled(1), "nothing is cancelled to begin with");
        line.cancel_up_to(5);
        assert!(!line.is_cancelled(6));
    }

    #[test]
    fn an_older_cancel_does_not_undo_a_newer_one() {
        let line = CancelLine::new();
        line.cancel_up_to(5);
        line.cancel_up_to(3);
        assert!(line.is_cancelled(4));
    }

    #[test]
    fn a_scan_without_a_ticket_cannot_be_cancelled() {
        let line = CancelLine::new();
        line.cancel_up_to(7);
        assert!(!line.is_cancelled(0));
        assert!(!line.is_cancelled(-1));
    }

    #[test]
    fn png_file_round_trip_scans() {
        let l = qr_on_canvas("from a png", 5, 600, 600, 150, 150, false);
        let bytes = png_bytes(&l.data, l.w, l.h, png::ColorType::Grayscale);
        let ImageDecode::Ok(img) = decode_image(&bytes) else { panic!("png decodes") };
        assert_eq!(img.data, l.data);
        assert_eq!(scan_still(img, 0, false, &never).unwrap().text, "from a png");
    }

    #[test]
    fn transparent_png_composites_over_white() {
        // Black modules on a fully transparent background.
        let l = qr_on_canvas("transparent", 5, 400, 400, 100, 100, false);
        let rgba: Vec<u8> = l
            .data
            .iter()
            .flat_map(|&v| if v < 128 { [0, 0, 0, 255] } else { [0, 0, 0, 0] })
            .collect();
        let bytes = png_bytes(&rgba, l.w, l.h, png::ColorType::Rgba);
        let ImageDecode::Ok(img) = decode_image(&bytes) else { panic!("png decodes") };
        assert_eq!(img.data[0], 255, "transparent reads as white");
        assert_eq!(scan_still(img, 0, false, &never).unwrap().text, "transparent");
    }

    #[test]
    fn jpeg_file_decodes_to_luma_and_scans() {
        let l = qr_on_canvas("from a jpeg", 6, 800, 1000, 200, 300, false);
        let mut bytes = Vec::new();
        jpeg_encoder::Encoder::new(&mut bytes, 90)
            .encode(&l.data, l.w as u16, l.h as u16, jpeg_encoder::ColorType::Luma)
            .unwrap();
        let ImageDecode::Ok(img) = decode_image(&bytes) else { panic!("jpeg decodes") };
        assert_eq!((img.w, img.h), (800, 1000));
        let s = scan_still(img, 0, false, &never).expect("code found");
        assert_eq!(s.text, "from a jpeg");
    }

    #[test]
    fn colour_jpeg_decodes_to_luma() {
        let (w, h) = (64usize, 48usize);
        let rgb: Vec<u8> = (0..w * h).flat_map(|i| [(i % 256) as u8, 128, 64]).collect();
        let mut bytes = Vec::new();
        jpeg_encoder::Encoder::new(&mut bytes, 95)
            .encode(&rgb, w as u16, h as u16, jpeg_encoder::ColorType::Rgb)
            .unwrap();
        let ImageDecode::Ok(img) = decode_image(&bytes) else { panic!("jpeg decodes") };
        assert_eq!((img.w, img.h, img.data.len()), (w, h, w * h));
    }

    #[test]
    fn unknown_and_broken_files_go_back_to_android() {
        assert!(matches!(decode_image(b"RIFF\0\0\0\0WEBPVP8 "), ImageDecode::Unsupported));
        assert!(matches!(decode_image(&[0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10]), ImageDecode::Failed(_)));
        assert!(matches!(
            decode_image(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0]),
            ImageDecode::Failed(_)
        ));
    }

    #[test]
    fn rgba_pixels_convert_with_alpha_over_white() {
        let px = [0, 0, 0, 255, 0, 0, 0, 0, 255, 255, 255, 255, 0, 0, 0, 128];
        let l = rgba_to_luma(&px, 4, 1, 16);
        assert_eq!(l.data[0], 0);
        assert_eq!(l.data[1], 255);
        assert_eq!(l.data[2], 255);
        assert!((126..=129).contains(&l.data[3]));
    }

    #[test]
    fn judge_qr_with_grid_wins_immediately() {
        let mut j = Judge::new(true, false);
        assert!(j.offer(read("QR_CODE", "a", Some("21:ff"))).is_some());
    }

    #[test]
    fn judge_gridless_qr_falls_back_only_in_normal_mode() {
        let mut j = Judge::new(false, false);
        assert!(j.offer(read("QR_CODE", "a", None)).is_none());
        assert_eq!(j.finish().unwrap().grid, None);
        let mut j = Judge::new(true, false);
        assert!(j.offer(read("QR_CODE", "a", None)).is_none());
        assert_eq!(j.finish(), None);
    }

    #[test]
    fn judge_gridless_qr_upgrades_when_a_later_variant_has_the_grid() {
        let mut j = Judge::new(false, false);
        assert!(j.offer(read("QR_CODE", "a", None)).is_none());
        assert_eq!(j.offer(read("QR_CODE", "a", Some("21:ff"))).unwrap().grid.as_deref(), Some("21:ff"));
    }

    #[test]
    fn judge_lone_1d_read_is_dropped_and_two_agreeing_reads_confirm() {
        let mut j = Judge::new(false, false);
        assert!(j.offer(read("CODE_128", "123", None)).is_none());
        assert_eq!(j.finish(), None);
        let mut j = Judge::new(false, false);
        assert!(j.offer(read("CODE_128", "123", None)).is_none());
        assert_eq!(j.offer(read("CODE_128", "123", None)).unwrap().text, "123");
    }

    #[test]
    fn judge_merges_upc_a_with_zero_led_ean_13() {
        let mut j = Judge::new(false, false);
        assert!(j.offer(read("EAN_13", "0036000291452", None)).is_none());
        let s = j.offer(read("UPC_A", "036000291452", None)).unwrap();
        assert_eq!((s.format.as_str(), s.text.as_str()), ("UPC_A", "036000291452"));
    }

    #[test]
    fn judge_exact_1d_needs_a_lead_of_two() {
        let mut j = Judge::new(true, false);
        assert!(j.offer(read("CODE_39", "A", None)).is_none());
        assert!(j.offer(read("CODE_39", "B", None)).is_none());
        assert!(j.offer(read("CODE_39", "A", None)).is_none()); // 2 vs 1
        assert_eq!(j.offer(read("CODE_39", "A", None)).unwrap().text, "A"); // 3 vs 1
    }

    #[test]
    fn judge_ignores_1d_when_only_a_qr_is_in_view() {
        let mut j = Judge::new(false, true);
        assert!(j.offer(read("ITF", "1234", None)).is_none());
        assert!(j.offer(read("ITF", "1234", None)).is_none());
        assert_eq!(j.finish(), None);
    }

    #[test]
    fn judge_accepts_other_2d_formats_on_one_read() {
        let mut j = Judge::new(true, false);
        assert_eq!(j.offer(read("DATA_MATRIX", "dm", None)).unwrap().text, "dm");
    }
}
