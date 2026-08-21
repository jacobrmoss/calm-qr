//! The scan engine: every camera frame is fed here raw, and everything that
//! can happen in Rust does — scoring, best-frame selection, gate calibration,
//! decode scheduling on a persistent worker pool, QR grid holds, 1D vote
//! confirmation, focus-nudge policy, and telemetry. Kotlin keeps only what
//! Android forces on it: delivering frames, executing the camera-control AF
//! call, and navigating on a finished scan.
//!
//! Log lines are byte-for-byte the ones the Kotlin scheduler emitted, so the
//! ScanPerf/GridExtract telemetry (and the docs describing it) stay valid.

use crate::{alog, analyze_frame, decode_luma, format_to_string};
use jni::objects::{JByteBuffer, JClass, JObject, JValue};
use jni::sys::{jboolean, jint};
use jni::JNIEnv;
use rxing::Point;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

// ---- Tuning constants ------------------------------------------------------
/// Scene-relative gate: a frame within this fraction of the decayed scene
/// maximum is dispatch-worthy. No learned calibration, no cold start — every
/// scene self-calibrates, and a fresh process's first frame dispatches
/// instantly (it IS the scene max).
const REF_FRACTION: f32 = 0.65;
/// Per-frame decay of the scene maximum (~0.67/s at 20fps): a scene change or
/// a one-frame glare spike stops gating the new reality within ~a second.
const SCENE_MAX_DECAY: f32 = 0.98;
/// Baseline sharpness floor: below this a frame has essentially no structure
/// (every hit ever measured scored >=~900; blank scenes 40-200). Keeps a
/// scene made purely of blur from having its "local best" decoded eagerly —
/// such frames ride the insurance cadence instead.
const BASELINE_SCORE: f32 = 250.0;
const MAX_GATE_WAIT: Duration = Duration::from_millis(700);
const PLATEAU_GATE_WAIT: Duration = Duration::from_millis(400);
const HARD_GATE_WAIT: Duration = Duration::from_millis(1400);
const PLATEAU_FRAMES: u32 = 5;
const PREEMPT_SCORE_RATIO: f32 = 3.0;
const GRID_RETRY: Duration = Duration::from_millis(3000);
const CONFIRM_1D: Duration = Duration::from_millis(1500);
const LOCATE_INTERVAL: Duration = Duration::from_millis(500);
const LOCATE_STABLE_DIST2: f32 = 0.02;
/// After this many consecutive detector-empty frames (~250ms), a sharp scene
/// stops getting eager back-to-back decodes and drops to the paced cadence —
/// full decodes still (never lite while above bar), so the detector-gap
/// classes (tiny QRs, 45° barcodes) keep full-strength attempts, just paced.
const EMPTY_STREAK_LAZY: u32 = 5;

/// A 1D detection earns focus-nudge rights only through persistence: this many
/// consecutive stable sightings (~300ms) with no hit landing is the defocus
/// signature — bar-like, held centered, undecodable. (A text false positive
/// passing this gate means the user is deliberately aiming at it, and
/// focusing there is correct anyway.)
const ONED_NUDGE_STREAK: u32 = 6;
const AF_NUDGE_INTERVAL: Duration = Duration::from_millis(1000);
const NUDGE_STUCK_1: Duration = Duration::from_millis(1500);
const NUDGE_STUCK_2: Duration = Duration::from_millis(3500);
const TELEMETRY_WINDOW: Duration = Duration::from_millis(2000);

const ONE_D_FORMATS: [&str; 10] = [
    "CODE_128", "CODE_39", "CODE_93", "EAN_13", "EAN_8", "UPC_A", "UPC_E", "ITF", "CODABAR",
    "TELEPEN",
];

fn is_1d(format: &str) -> bool {
    ONE_D_FORMATS.contains(&format)
}

// Process-lifetime counters/calibration (survive engine resets, like the
// Kotlin GridStats / ScoreCalibration objects did).
static QR_HITS: AtomicU32 = AtomicU32::new(0);
static GRID_MISSES: AtomicU32 = AtomicU32::new(0);
static GRID_RECOVERIES: AtomicU32 = AtomicU32::new(0);

struct FrameBuf {
    luma: Arc<Vec<u8>>,
    w: u32,
    h: u32,
    score: f32,
    crop: (i32, i32, i32, i32),
}

struct WorkItem {
    luma: Arc<Vec<u8>>,
    w: u32,
    h: u32,
    score: f32,
    crop: (i32, i32, i32, i32),
    binarizer: i32,
    try_rotate: bool,
    format_filter: i32,
    try_inverted: bool,
    lite: bool,
}

struct Completed {
    score: f32,
    ms: u128,
    format_filter: i32,
    lite: bool,
    try_inverted: bool,
    // (result, qr_grid, winning variant, full-frame dims, crop)
    outcome: Option<(rxing::RXingResult, Option<String>, usize)>,
    w: u32,
    h: u32,
    crop: (i32, i32, i32, i32),
}

#[derive(Default)]
struct PendingQr {
    content: Option<String>,
    format: String,
    held_since: Option<Instant>,
    retries: u32,
    nudges: u32,
}

impl PendingQr {
    fn wants_nudge(&self, now: Instant) -> bool {
        let held = match self.held_since {
            Some(t) => now.duration_since(t),
            None => return false,
        };
        (self.nudges == 0 && held > NUDGE_STUCK_1) || (self.nudges == 1 && held > NUDGE_STUCK_2)
    }
}

#[derive(Default)]
struct Pending1D {
    // (format, content, votes) in first-seen order; last_idx = most recent
    counts: Vec<(String, String, u32)>,
    last_idx: Option<usize>,
    held_since: Option<Instant>,
    retries: u32,
    nudges: u32,
    /// A tied window gets ONE extension for a deciding read before the
    /// recency coin-flip is allowed.
    extended: bool,
}

impl Pending1D {
    fn active(&self) -> bool {
        !self.counts.is_empty()
    }
    fn wants_nudge(&self, now: Instant) -> bool {
        let held = match self.held_since {
            Some(t) => now.duration_since(t),
            None => return false,
        };
        (self.nudges == 0 && held > NUDGE_STUCK_1) || (self.nudges == 1 && held > NUDGE_STUCK_2)
    }
    /// Majority candidate index (ties broken toward the most recent read).
    fn majority(&self) -> Option<usize> {
        let max = self.counts.iter().map(|c| c.2).max()?;
        if let Some(li) = self.last_idx {
            if self.counts[li].2 == max {
                return Some(li);
            }
        }
        self.counts.iter().position(|c| c.2 == max)
    }
    fn clear(&mut self) {
        self.counts.clear();
        self.last_idx = None;
        self.extended = false;
    }
}

struct EngineState {
    // Frame selection
    best: Option<FrameBuf>,
    last_dispatch: Instant,
    last_dispatch_score: f32,
    active: u32,
    dispatch_counter: u64,
    insurance_counter: u64,
    // Focus trend
    prev_score: f32,
    rising: u32,
    plateau: u32,
    // Aim-lock metric (stamped on first cheap detection)
    aim_lock: Option<Instant>,
    // Scene-relative reference: decayed maximum of recent frame scores
    scene_max: f32,
    // Cheap detection (per frame); last_locate throttles the log lines only
    last_locate: Instant,
    last_loc: Option<(f32, f32)>,
    qr_detections: u32,
    oned_detections: u32,
    empty_streak: u32,
    last_oned_loc: Option<(f32, f32)>,
    oned_streak: u32,
    oned_since: Option<Instant>,
    oned_nudges: u32,
    qr_since: Option<Instant>,
    qr_aim_nudges: u32,
    center_nudges: u32,
    /// Non-lite decode misses this scan — evidence that decoding is actually
    /// failing (vs. the user still raising the device).
    session_misses: u32,
    // Nudges
    last_nudge: Option<Instant>,
    manual_tap: bool,
    // Holds
    qr: PendingQr,
    oned: Pending1D,
    // Session
    finished: bool,
    exact: bool,
    // Telemetry windows
    seen: u32,
    copied: u32,
    dispatched: u32,
    deferred: u32,
    overlaps: u32,
    win_start: Instant,
    dec_frames: u32,
    dec_total_ms: u128,
    dec_max_ms: u128,
    dec_win_start: Instant,
}

impl EngineState {
    fn new() -> Self {
        let now = Instant::now();
        EngineState {
            best: None,
            last_dispatch: now,
            last_dispatch_score: 0.0,
            active: 0,
            dispatch_counter: 0,
            insurance_counter: 0,
            prev_score: 0.0,
            rising: 0,
            plateau: 0,
            aim_lock: None,
            scene_max: 0.0,
            last_locate: now - LOCATE_INTERVAL,
            last_loc: None,
            qr_detections: 0,
            oned_detections: 0,
            empty_streak: 0,
            last_oned_loc: None,
            oned_streak: 0,
            oned_since: None,
            oned_nudges: 0,
            qr_since: None,
            qr_aim_nudges: 0,
            center_nudges: 0,
            session_misses: 0,
            last_nudge: None,
            manual_tap: false,
            qr: PendingQr::default(),
            oned: Pending1D::default(),
            finished: false,
            exact: false,
            seen: 0,
            copied: 0,
            dispatched: 0,
            deferred: 0,
            overlaps: 0,
            win_start: now,
            dec_frames: 0,
            dec_total_ms: 0,
            dec_max_ms: 0,
            dec_win_start: now,
        }
    }

    /// New scan session: clears everything. Nothing is learned, so there is
    /// nothing to carry over — out-of-box behavior IS steady-state behavior.
    fn reset(&mut self, exact: bool) {
        *self = EngineState::new();
        self.exact = exact;
    }

    /// The dispatch-worthiness bar: every scene uniquely calibrates it (a
    /// fraction of the decayed scene max), floored by the absolute baseline.
    fn gate_bar(&self) -> f32 {
        (self.scene_max * REF_FRACTION).max(BASELINE_SCORE)
    }

    /// Per-episode nudge budget: one intervention when earned, one more only
    /// if the episode drags past 2.5s, then hands off — an AF sweep blurs the
    /// very frames decoding needs, so repeated nudging is self-sabotage
    /// (measured twice now: hold churn, then 1D aim churn).
    fn episode_wants_nudge(nudges: u32, since: Option<Instant>, now: Instant) -> bool {
        match (nudges, since) {
            (0, _) => true,
            (1, Some(t)) => now.duration_since(t) > Duration::from_millis(2500),
            _ => false,
        }
    }

    /// Central-box + tap + rate-limit gate. Returns true when the nudge should
    /// actually be sent to the camera (and logs it).
    fn nudge_gate(&mut self, now: Instant, cx: f32, cy: f32) -> bool {
        if cx < 0.15 || cx > 0.85 || cy < 0.15 || cy > 0.85 {
            return false;
        }
        if self.manual_tap {
            return false;
        }
        if let Some(t) = self.last_nudge {
            if now.duration_since(t) < AF_NUDGE_INTERVAL {
                return false;
            }
        }
        self.last_nudge = Some(now);
        alog("ScanPerf", &format!("AF nudge at code position ({cx:.2}, {cy:.2})"));
        true
    }
}

/// A finished scan handed back to Kotlin.
pub struct FinishedScan {
    pub text: String,
    pub format: String,
    pub qr_grid: Option<String>,
}

pub struct EngineShared {
    state: Mutex<EngineState>,
    queue: Mutex<VecDeque<WorkItem>>,
    cv: Condvar,
    completed: Mutex<Vec<Completed>>,
}

fn engine() -> &'static Arc<EngineShared> {
    static ENGINE: OnceLock<Arc<EngineShared>> = OnceLock::new();
    ENGINE.get_or_init(|| {
        let shared = Arc::new(EngineShared {
            state: Mutex::new(EngineState::new()),
            queue: Mutex::new(VecDeque::new()),
            cv: Condvar::new(),
            completed: Mutex::new(Vec::new()),
        });
        // Persistent decode workers: 2, matching the measured 2-concurrent
        // cap (each decode internally fans to <=3 variant threads, so the
        // worst-case thread pressure equals the tuned Kotlin design).
        for _ in 0..2 {
            let sh = Arc::clone(&shared);
            thread::spawn(move || loop {
                let item = {
                    let mut q = sh.queue.lock().unwrap();
                    loop {
                        if let Some(it) = q.pop_front() {
                            break it;
                        }
                        q = sh.cv.wait(q).unwrap();
                    }
                };
                let t0 = Instant::now();
                let outcome = decode_luma(
                    (*item.luma).clone(),
                    item.w,
                    item.h,
                    item.binarizer,
                    item.crop.0,
                    item.crop.1,
                    item.crop.2,
                    item.crop.3,
                    item.try_rotate,
                    item.format_filter,
                    item.try_inverted,
                    item.lite,
                );
                let ms = t0.elapsed().as_millis();
                sh.completed.lock().unwrap().push(Completed {
                    score: item.score,
                    ms,
                    format_filter: item.format_filter,
                    lite: item.lite,
                    try_inverted: item.try_inverted,
                    outcome,
                    w: item.w,
                    h: item.h,
                    crop: item.crop,
                });
            });
        }
        shared
    })
}

/// Normalized code center from a decode outcome (non-rotated variants only).
fn outcome_center(
    result: &rxing::RXingResult,
    variant: usize,
    w: u32,
    h: u32,
    crop: (i32, i32, i32, i32),
) -> (f32, f32) {
    if variant > 1 {
        return (-1.0, -1.0);
    }
    let pts: &[Point] = result.getPoints();
    if pts.is_empty() {
        return (-1.0, -1.0);
    }
    let scale = if variant == 1 { 2.0f32 } else { 1.0f32 };
    let (crop_x, crop_y) = if crop.2 > 0 && crop.3 > 0 {
        (crop.0.max(0) as f32, crop.1.max(0) as f32)
    } else {
        (0.0, 0.0)
    };
    let n = pts.len() as f32;
    let sx: f32 = pts.iter().map(|p| p.x).sum();
    let sy: f32 = pts.iter().map(|p| p.y).sum();
    (
        ((sx / n) * scale + crop_x) / w as f32,
        ((sy / n) * scale + crop_y) / h as f32,
    )
}

/// Marks the scan finished, emitting the aim-lock metric and grid-quality
/// bookkeeping exactly as the Kotlin finish() did.
fn finish_scan(
    st: &mut EngineState,
    now: Instant,
    text: String,
    format: String,
    qr_grid: Option<String>,
) -> FinishedScan {
    if let Some(t) = st.aim_lock {
        alog(
            "ScanPerf",
            &format!("aim-lock to scan: {}ms", now.duration_since(t).as_millis()),
        );
    }
    if format == "QR_CODE" {
        let hits = QR_HITS.fetch_add(1, Ordering::Relaxed) + 1;
        if qr_grid.is_none() {
            let misses = GRID_MISSES.fetch_add(1, Ordering::Relaxed) + 1;
            alog(
                "ScanPerf",
                &format!(
                    "QR finishing without exact grid — mask will be re-encoded ({misses}/{hits} QR scans since launch)"
                ),
            );
        }
    }
    st.finished = true;
    FinishedScan {
        text,
        format,
        qr_grid,
    }
}

/// Per-completed-decode policy: telemetry, calibration, holds, votes.
/// Returns a finished scan and/or a nudge request.
fn apply_completion(
    st: &mut EngineState,
    now: Instant,
    c: Completed,
) -> (Option<FinishedScan>, Option<(f32, f32)>) {
    // Decode timing telemetry (the old ScanPerfStats window)
    st.dec_frames += 1;
    st.dec_total_ms += c.ms;
    st.dec_max_ms = st.dec_max_ms.max(c.ms);
    let dec_win = now.duration_since(st.dec_win_start);
    if dec_win >= TELEMETRY_WINDOW {
        alog(
            "ScanPerf",
            &format!(
                "{} frames in {}ms (avg {}ms, max {}ms)",
                st.dec_frames,
                dec_win.as_millis(),
                st.dec_total_ms / st.dec_frames.max(1) as u128,
                st.dec_max_ms
            ),
        );
        st.dec_frames = 0;
        st.dec_total_ms = 0;
        st.dec_max_ms = 0;
        st.dec_win_start = now;
    }

    let filter_label = match c.format_filter {
        1 => " qrOnly",
        2 => " 1dOnly",
        _ => "",
    };
    let outcome_label = match &c.outcome {
        None => "miss".to_string(),
        Some((r, g, _)) => format!("{} grid={}", format_to_string(*r.getBarcodeFormat()), g.is_some()),
    };
    alog(
        "ScanPerf",
        &format!(
            "decoded score={}{}{}{} → {} in {}ms",
            c.score as i32,
            filter_label,
            if c.lite { " lite" } else { "" },
            if !c.try_inverted { " noInv" } else { "" },
            outcome_label,
            c.ms
        ),
    );

    let Some((result, qr_grid, variant)) = c.outcome else {
        if !c.lite {
            st.session_misses += 1;
        }
        return (None, None);
    };
    let format = format_to_string(*result.getBarcodeFormat());
    let content = result.getText().to_string();
    alog("ScanPerf", &format!("decode hit in {}ms ({format})", c.ms));

    let center = outcome_center(&result, variant, c.w, c.h, c.crop);
    let mut nudge: Option<(f32, f32)> = None;

    if format == "QR_CODE" {
        if let Some(grid) = qr_grid {
            if st.qr.content.as_deref() == Some(content.as_str()) {
                let held = st
                    .qr
                    .held_since
                    .map(|t| now.duration_since(t).as_millis())
                    .unwrap_or(0);
                let rec = GRID_RECOVERIES.fetch_add(1, Ordering::Relaxed) + 1;
                alog(
                    "ScanPerf",
                    &format!(
                        "exact grid recovered on retry after {held}ms ({rec} recoveries since launch)"
                    ),
                );
            }
            return (
                Some(finish_scan(st, now, content, format, Some(grid))),
                None,
            );
        }
        if st.qr.content.as_deref() != Some(content.as_str()) {
            st.qr.content = Some(content);
            st.qr.format = format;
            st.qr.held_since = Some(now);
            st.qr.retries = 0;
            st.qr.nudges = 0;
            alog("ScanPerf", "QR hit without grid — holding for retry");
        }
        // Only a STUCK hold gets focus help (measured: sweeps blur retries).
        if st.qr.wants_nudge(now) && st.nudge_gate(now, center.0, center.1) {
            st.qr.nudges += 1;
            nudge = Some(center);
        }
        return (None, nudge);
    }

    if is_1d(&format) {
        if !st.oned.active() {
            st.oned.held_since = Some(now);
            st.oned.retries = 0;
            st.oned.nudges = 0;
            st.oned.extended = false;
        }
        // A UPC-A is an EAN-13 with a leading 0 — same physical code, one
        // candidate, not rivals.
        let (kf, kc) = if format == "EAN_13" && content.len() == 13 && content.starts_with('0') {
            ("UPC_A".to_string(), content[1..].to_string())
        } else {
            (format.clone(), content.clone())
        };
        let idx = match st
            .oned
            .counts
            .iter()
            .position(|(f, c2, _)| *f == kf && *c2 == kc)
        {
            Some(i) => {
                st.oned.counts[i].2 += 1;
                i
            }
            None => {
                st.oned.counts.push((kf, kc, 1));
                st.oned.counts.len() - 1
            }
        };
        st.oned.last_idx = Some(idx);
        let votes = st.oned.counts[idx].2;
        let rival = st
            .oned
            .counts
            .iter()
            .enumerate()
            .filter(|(i, _)| *i != idx)
            .map(|(_, c2)| c2.2)
            .max()
            .unwrap_or(0);
        let confirmed = if st.exact {
            votes >= 2 && votes - rival >= 2
        } else {
            votes >= 2
        };
        if confirmed {
            alog(
                "ScanPerf",
                &format!(
                    "1D read confirmed ({votes} votes, rival {rival}, {} candidate(s))",
                    st.oned.counts.len()
                ),
            );
            let (wf, wc, _) = st.oned.counts[idx].clone();
            return (Some(finish_scan(st, now, wc, wf, None)), None);
        }
        alog(
            "ScanPerf",
            &format!("1D hit ({format}, {votes} vote(s)) — awaiting confirmation"),
        );
        if st.oned.wants_nudge(now) && st.nudge_gate(now, center.0, center.1) {
            st.oned.nudges += 1;
            nudge = Some(center);
        }
        return (None, nudge);
    }

    // 2D non-QR (Aztec/DataMatrix/PDF417): RS-protected, no confirmation needed.
    (Some(finish_scan(st, now, content, format, None)), None)
}

/// Window releases: QR grid fallback and 1D majority/discard.
fn apply_releases(st: &mut EngineState, now: Instant) -> Option<FinishedScan> {
    if st.finished {
        return None;
    }
    if let (Some(content), Some(held)) = (st.qr.content.clone(), st.qr.held_since) {
        if !st.exact && now.duration_since(held) >= GRID_RETRY {
            alog(
                "ScanPerf",
                &format!(
                    "grid retry window exhausted ({}ms)",
                    now.duration_since(held).as_millis()
                ),
            );
            let fmt = st.qr.format.clone();
            return Some(finish_scan(st, now, content, fmt, None));
        }
    }
    if st.oned.active() && !st.exact {
        if let Some(held) = st.oned.held_since {
            if now.duration_since(held) >= CONFIRM_1D {
                let total: u32 = st.oned.counts.iter().map(|c| c.2).sum();
                if let Some(mi) = st.oned.majority() {
                    if total >= 2 {
                        let max_votes = st.oned.counts[mi].2;
                        let tied = st
                            .oned
                            .counts
                            .iter()
                            .filter(|c| c.2 == max_votes)
                            .count()
                            > 1;
                        if tied && !st.oned.extended {
                            // A tie is a coin-flip, and we have watched a
                            // systematic misread win votes outright — give the
                            // window ONE extension for a deciding read before
                            // recency is allowed to pick.
                            st.oned.extended = true;
                            st.oned.held_since = Some(now);
                            alog(
                                "ScanPerf",
                                "1D window tied — extending for a deciding read",
                            );
                            return None;
                        }
                        let (mf, mc, mv) = st.oned.counts[mi].clone();
                        alog(
                            "ScanPerf",
                            &format!(
                                "1D unconfirmed after {}ms — accepting majority ({mf}, {mv} of {total} reads)",
                                CONFIRM_1D.as_millis()
                            ),
                        );
                        return Some(finish_scan(st, now, mc, mf, None));
                    }
                    alog(
                        "ScanPerf",
                        "1D single unconfirmed read expired — discarded as likely misread",
                    );
                    st.oned.clear();
                }
            }
        }
    }
    None
}

/// The one per-frame entry point. Returns (finished, nudge).
pub fn submit_frame(
    data: &[u8],
    stride: usize,
    w: usize,
    h: usize,
    crop: (i32, i32, i32, i32),
    exact: bool,
    manual_tap: bool,
) -> (Option<FinishedScan>, Option<(f32, f32)>) {
    let sh = engine();
    let now = Instant::now();

    // Drain completions outside the state lock ordering concerns (short locks).
    let done: Vec<Completed> = sh.completed.lock().unwrap().drain(..).collect();

    let mut st = sh.state.lock().unwrap();
    st.exact = exact;
    st.manual_tap |= manual_tap;
    st.active = st.active.saturating_sub(done.len() as u32);

    let mut finished: Option<FinishedScan> = None;
    let mut nudge: Option<(f32, f32)> = None;

    for c in done {
        if st.finished {
            break;
        }
        let (f, n) = apply_completion(&mut st, now, c);
        if f.is_some() {
            finished = f;
        }
        if n.is_some() {
            nudge = n;
        }
    }
    if finished.is_none() {
        finished = apply_releases(&mut st, now);
    }
    if st.finished {
        return (finished, nudge);
    }

    // ---- One fused cheap pass: score + QR finder + 1D coherence ----
    let fa = analyze_frame(data, stride, w, h);
    let score = fa.score;
    st.seen += 1;
    if score > st.prev_score * 1.15 {
        st.rising += 1;
    } else {
        st.rising = 0;
    }
    if st.prev_score > 0.0 && score <= st.prev_score * 1.15 && score >= st.prev_score * 0.85 {
        st.plateau += 1;
    } else {
        st.plateau = 0;
    }
    st.prev_score = score;

    // Scene-relative reference: rises instantly to the sharpest recent frame,
    // decays within ~a second — every scene uniquely calibrates the bar.
    st.scene_max = (st.scene_max * SCENE_MAX_DECAY).max(score);
    let bar = st.gate_bar();

    let is_better = st.best.as_ref().map(|b| score > b.score).unwrap_or(true);
    let copied_this_frame = is_better;
    if is_better {
        let mut luma = Vec::with_capacity(w * h);
        if stride == w {
            luma.extend_from_slice(&data[..w * h]);
        } else {
            for row in 0..h {
                let start = row * stride;
                luma.extend_from_slice(&data[start..start + w]);
            }
        }
        st.best = Some(FrameBuf {
            luma: Arc::new(luma),
            w: w as u32,
            h: h as u32,
            score,
            crop,
        });
        st.copied += 1;
    }

    // ---- Cheap detection every frame (never during holds) ----
    // Detection outranks the sharpness bar as the "worth decoding" signal: a
    // sighted candidate triggers an immediate expensive decode of THIS frame.
    let hold_active = st.qr.content.is_some() || st.oned.active();
    let mut detected = false;
    if !hold_active {
        if let Some((cx, cy)) = fa.qr {
            detected = true;
            st.qr_detections += 1;
            if st.aim_lock.is_none() {
                st.aim_lock = Some(now);
            }
            if st.qr_since.is_none() {
                st.qr_since = Some(now);
                st.qr_aim_nudges = 0;
            }
            let stable = st
                .last_loc
                .map(|(lx, ly)| {
                    let dx = cx - lx;
                    let dy = cy - ly;
                    dx * dx + dy * dy < LOCATE_STABLE_DIST2
                })
                .unwrap_or(false);
            st.last_loc = Some((cx, cy));
            if now.duration_since(st.last_locate) >= LOCATE_INTERVAL {
                st.last_locate = now;
                alog(
                    "ScanPerf",
                    &format!(
                        "locate: QR at ({cx:.2}, {cy:.2}){}",
                        if stable { " stable" } else { "" }
                    ),
                );
            }
            if stable
                && EngineState::episode_wants_nudge(st.qr_aim_nudges, st.qr_since, now)
                && st.nudge_gate(now, cx, cy)
            {
                st.qr_aim_nudges += 1;
                nudge = Some((cx, cy));
            }
        } else {
            st.last_loc = None;
            st.qr_since = None;
        }
        if let Some((cx, cy)) = fa.oned {
            detected = true;
            st.oned_detections += 1;
            if st.aim_lock.is_none() {
                st.aim_lock = Some(now);
            }
            if st.oned_since.is_none() {
                st.oned_since = Some(now);
                st.oned_nudges = 0;
            }
            let stable = st
                .last_oned_loc
                .map(|(lx, ly)| {
                    let dx = cx - lx;
                    let dy = cy - ly;
                    dx * dx + dy * dy < LOCATE_STABLE_DIST2
                })
                .unwrap_or(false);
            st.oned_streak = if stable { st.oned_streak + 1 } else { 1 };
            st.last_oned_loc = Some((cx, cy));
            if now.duration_since(st.last_locate) >= LOCATE_INTERVAL {
                st.last_locate = now;
                alog("ScanPerf", &format!("locate: 1D at ({cx:.2}, {cy:.2})"));
            }
            // Persistent stable sighting with no hit = the defocus signature:
            // this 1D detection has earned a focus nudge (episode-budgeted).
            if st.oned_streak >= ONED_NUDGE_STREAK
                && EngineState::episode_wants_nudge(st.oned_nudges, st.oned_since, now)
                && st.nudge_gate(now, cx, cy)
            {
                st.oned_nudges += 1;
                nudge = Some((cx, cy));
            }
        } else {
            st.last_oned_loc = None;
            st.oned_streak = 0;
            st.oned_since = None;
        }
        if detected {
            st.empty_streak = 0;
        } else {
            st.empty_streak = st.empty_streak.saturating_add(1);
        }
    }

    // ---- Dispatch policy ----
    let waited = now.duration_since(st.last_dispatch);
    let above_bar = st.best.as_ref().map(|b| b.score >= bar).unwrap_or(false);

    // Sharp scene + detectors empty + nothing landing = foreground AF capture
    // (the classic case: focus locked on the user's thumb, code defocused
    // behind it). One exploratory center refocus per scan dislodges it —
    // codes are aimed at the center, foreground intruders usually are not.
    if !hold_active
        && st.center_nudges == 0
        && st.empty_streak >= 20
        && above_bar
        && st.session_misses >= 2
        && st.nudge_gate(now, 0.5, 0.5)
    {
        st.center_nudges = 1;
        alog(
            "ScanPerf",
            "exploratory center refocus (sharp scene, no detections)",
        );
        nudge = Some((0.5, 0.5));
    }
    let steady = st.plateau >= PLATEAU_FRAMES;
    let wait_cap = if steady { PLATEAU_GATE_WAIT } else { MAX_GATE_WAIT };
    let mut fire = false;
    let mut fire_current = false;
    let mut is_insurance = false;
    let mut is_preempt = false;
    // With a moving scene-relative bar, a dispatch that was above-bar at
    // launch can look retroactively sub-bar as the scene brightens — require
    // it to be CLEARLY stale before overlapping (measured: without the margin
    // overlaps fire chronically, 1-2 per window).
    let stale_running = st.last_dispatch_score < bar * 0.8;
    if detected {
        // Detection-driven dispatch of the current frame.
        if st.active == 0 {
            fire = true;
            fire_current = true;
        } else if st.active == 1 && stale_running {
            fire = true;
            fire_current = true;
            is_preempt = true;
        }
    }
    // Detector-empty laziness: a sharp scene the detectors call empty stops
    // getting eager back-to-back decodes and drops to the paced cadence
    // (full decodes, never lite while above bar).
    let eager = st.empty_streak < EMPTY_STREAK_LAZY;
    if !fire && st.best.is_some() {
        if st.active == 0 {
            if above_bar && eager {
                fire = true;
            } else if waited >= wait_cap {
                if !above_bar && st.rising >= 2 && waited < HARD_GATE_WAIT {
                    st.deferred += 1;
                } else {
                    fire = true;
                    is_insurance = !above_bar && !steady;
                }
            }
        } else if st.active == 1 && above_bar {
            let best_score = st.best.as_ref().map(|b| b.score).unwrap_or(0.0);
            if stale_running || best_score >= PREEMPT_SCORE_RATIO * st.last_dispatch_score {
                fire = true;
                is_preempt = true;
            }
        }
    }

    if fire {
        let frame = if fire_current && !copied_this_frame {
            // Compact-copy the frame the detector just sighted.
            let mut luma = Vec::with_capacity(w * h);
            if stride == w {
                luma.extend_from_slice(&data[..w * h]);
            } else {
                for row in 0..h {
                    let start = row * stride;
                    luma.extend_from_slice(&data[start..start + w]);
                }
            }
            FrameBuf {
                luma: Arc::new(luma),
                w: w as u32,
                h: h as u32,
                score,
                crop,
            }
        } else if fire_current {
            // The best slot holds THIS frame (it was just admitted) — reuse
            // the copy instead of making another.
            st.best.take().unwrap()
        } else {
            st.best.take().unwrap()
        };
        st.last_dispatch = now;
        st.last_dispatch_score = frame.score;
        st.dispatched += 1;
        if is_preempt {
            st.overlaps += 1;
        }
        let binarizer = (st.dispatch_counter % 2) as i32;
        let cycle = st.dispatch_counter % 4;
        st.dispatch_counter += 1;
        let format_filter = if st.qr.content.is_some() {
            let r = st.qr.retries;
            st.qr.retries += 1;
            if r % 3 != 2 { 1 } else { 0 }
        } else if st.oned.active() {
            let r = st.oned.retries;
            st.oned.retries += 1;
            if r % 3 != 2 { 2 } else { 0 }
        } else {
            0
        };
        let try_inverted = format_filter != 0 || cycle == 1 || cycle == 2;
        let lite = is_insurance && format_filter == 0 && {
            let i = st.insurance_counter;
            st.insurance_counter += 1;
            i % 3 != 0
        };
        st.active += 1;
        let item = WorkItem {
            luma: Arc::clone(&frame.luma),
            w: frame.w,
            h: frame.h,
            score: frame.score,
            crop: frame.crop,
            binarizer,
            try_rotate: format_filter != 1,
            format_filter,
            try_inverted,
            lite,
        };
        sh.queue.lock().unwrap().push_back(item);
        sh.cv.notify_one();
    }

    // ---- Sparse gate telemetry ----
    let win = now.duration_since(st.win_start);
    if win >= TELEMETRY_WINDOW {
        alog(
            "ScanPerf",
            &format!(
                "gate: {} frames seen, {} copied, {} decoded, {} deferred, {} overlap, {} qdet, {} bdet, ref={}",
                st.seen, st.copied, st.dispatched, st.deferred, st.overlaps,
                st.qr_detections, st.oned_detections, bar as i32
            ),
        );
        st.seen = 0;
        st.copied = 0;
        st.dispatched = 0;
        st.deferred = 0;
        st.overlaps = 0;
        st.qr_detections = 0;
        st.oned_detections = 0;
        st.win_start = now;
    }

    (finished, nudge)
}

pub fn reset(exact: bool) {
    let sh = engine();
    // Discard any stale work/results from a previous scan session.
    sh.queue.lock().unwrap().clear();
    sh.completed.lock().unwrap().clear();
    sh.state.lock().unwrap().reset(exact);
}

// ---- JNI ------------------------------------------------------------------

/// `RustBridge.engineReset(exactMatch)` — call on every scanner screen entry.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_engineReset<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    exact_match: jboolean,
) {
    reset(exact_match != 0);
}

/// `RustBridge.engineSubmitFrame(...)` — the single per-frame call. Returns an
/// EngineOutput when there is something for Kotlin to act on (a finished scan
/// and/or a focus nudge), else null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_engineSubmitFrame<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    luma_buffer: JByteBuffer<'local>,
    row_stride: jint,
    width: jint,
    height: jint,
    crop_left: jint,
    crop_top: jint,
    crop_width: jint,
    crop_height: jint,
    exact_match: jboolean,
    manual_tap: jboolean,
) -> JObject<'local> {
    let w = width.max(0) as usize;
    let h = height.max(0) as usize;
    let stride = row_stride.max(width) as usize;
    if w < 16 || h < 16 {
        return JObject::null();
    }
    let Ok(ptr) = env.get_direct_buffer_address(&luma_buffer) else {
        return JObject::null();
    };
    let Ok(cap) = env.get_direct_buffer_capacity(&luma_buffer) else {
        return JObject::null();
    };
    if cap < (h - 1) * stride + w {
        return JObject::null();
    }
    // SAFETY: the direct buffer stays valid for this call (the Kotlin caller
    // closes the ImageProxy only after it returns); the engine copies what it
    // keeps before returning.
    let data = unsafe { std::slice::from_raw_parts(ptr, cap) };

    let (finished, nudge) = submit_frame(
        data,
        stride,
        w,
        h,
        (crop_left, crop_top, crop_width, crop_height),
        exact_match != 0,
        manual_tap != 0,
    );

    if finished.is_none() && nudge.is_none() {
        return JObject::null();
    }

    let result_obj: JObject = match finished {
        Some(f) => {
            let Ok(j_text) = env.new_string(&f.text) else {
                return JObject::null();
            };
            let Ok(j_format) = env.new_string(&f.format) else {
                return JObject::null();
            };
            let j_grid = match &f.qr_grid {
                Some(g) => match env.new_string(g) {
                    Ok(s) => JObject::from(s),
                    Err(_) => return JObject::null(),
                },
                None => JObject::null(),
            };
            let Ok(class) = env.find_class("com/caravanfire/calmqr/rust/DecodeResult") else {
                return JObject::null();
            };
            match env.new_object(
                class,
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;FF)V",
                &[
                    JValue::Object(&JObject::from(j_text)),
                    JValue::Object(&JObject::from(j_format)),
                    JValue::Object(&j_grid),
                    JValue::Float(-1.0),
                    JValue::Float(-1.0),
                ],
            ) {
                Ok(o) => o,
                Err(_) => return JObject::null(),
            }
        }
        None => JObject::null(),
    };

    let (nx, ny) = nudge.unwrap_or((-1.0, -1.0));
    let Ok(out_class) = env.find_class("com/caravanfire/calmqr/rust/EngineOutput") else {
        return JObject::null();
    };
    match env.new_object(
        out_class,
        "(Lcom/caravanfire/calmqr/rust/DecodeResult;FF)V",
        &[
            JValue::Object(&result_obj),
            JValue::Float(nx),
            JValue::Float(ny),
        ],
    ) {
        Ok(o) => o,
        Err(_) => JObject::null(),
    }
}
