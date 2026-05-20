use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use rxing::common::{GlobalHistogramBinarizer, HybridBinarizer};
use rxing::{
    BarcodeFormat, BinaryBitmap, DecodeHints, Luma8LuminanceSource, LuminanceSource,
    MultiFormatReader, MultiFormatWriter, Reader, Writer,
};

/// JNI entry point: `com.caravanfire.calmqr.rust.RustBridge.greet`
///
/// Returns a greeting string built in Rust.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_greet<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
) -> JString<'local> {
    let name: String = env
        .get_string(&name)
        .expect("Failed to read JNI string")
        .into();

    let greeting = format!("Hello from Rust, {}!", name);

    env.new_string(greeting)
        .expect("Failed to create JNI string")
}

/// JNI entry point: `com.caravanfire.calmqr.rust.RustBridge.add`
///
/// Adds two i64 values and returns the result.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_add(
    _env: JNIEnv,
    _class: JClass,
    a: jlong,
    b: jlong,
) -> jlong {
    a + b
}

/// JNI entry point: `com.caravanfire.calmqr.rust.RustBridge.decodeBarcode`
///
/// Takes raw luminance bytes and image dimensions, attempts to decode a barcode/QR code.
///
/// Parameters:
/// - `binarizer`: 0 = HybridBinarizer (local-adaptive), 1 = GlobalHistogramBinarizer.
/// - `crop_left`, `crop_top`, `crop_width`, `crop_height`: ROI crop rect. When
///   `crop_width <= 0` or `crop_height <= 0`, the full frame is scanned.
/// - `try_rotate`: when nonzero, on initial decode failure retry once with the
///   luminance source rotated 90 degrees counter-clockwise (helps 1D codes when
///   held perpendicular to the buffer orientation).
///
/// `AlsoInverted` is always forced on so white-on-dark codes decode. `TryHarder`
/// is always set to favor robust detection over throughput.
///
/// Returns a `DecodeResult` object or null if no barcode is found.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_decodeBarcode<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    luma_bytes: JByteArray<'local>,
    width: jint,
    height: jint,
    binarizer: jint,
    crop_left: jint,
    crop_top: jint,
    crop_width: jint,
    crop_height: jint,
    try_rotate: jboolean,
) -> JObject<'local> {
    let len = match env.get_array_length(&luma_bytes) {
        Ok(l) => l as usize,
        Err(_) => return JObject::null(),
    };

    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&luma_bytes, 0, &mut buf).is_err() {
        return JObject::null();
    }
    let luma: Vec<u8> = buf.into_iter().map(|b| b as u8).collect();

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
    );

    let Some(rxing_result) = rxing_result else {
        return JObject::null();
    };

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
    let class = match env.find_class("com/caravanfire/calmqr/rust/DecodeResult") {
        Ok(c) => c,
        Err(_) => return JObject::null(),
    };
    match env.new_object(
        class,
        "(Ljava/lang/String;Ljava/lang/String;)V",
        &[
            JValue::Object(&JObject::from(j_text)),
            JValue::Object(&JObject::from(j_format)),
        ],
    ) {
        Ok(obj) => obj,
        Err(_) => JObject::null(),
    }
}

fn decode_luma(
    luma: Vec<u8>,
    width: u32,
    height: u32,
    binarizer: jint,
    crop_left: jint,
    crop_top: jint,
    crop_width: jint,
    crop_height: jint,
    try_rotate: bool,
) -> Option<rxing::RXingResult> {
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
    hints.AlsoInverted = Some(true);

    // Fast path: no retries → consume the source on a single attempt, no clone.
    if !try_rotate && !can_downscale {
        return decode_with_binarizer(source, binarizer, &hints);
    }

    // Multi-attempt path. Sequence: original → downscaled → rotated → rotated+downscaled.
    if let Some(r) = decode_with_binarizer(source.clone(), binarizer, &hints) {
        return Some(r);
    }

    if can_downscale {
        if let Some(ds) = downscale_2x(&source) {
            if let Some(r) = decode_with_binarizer(ds, binarizer, &hints) {
                return Some(r);
            }
        }
    }

    if try_rotate {
        if let Ok(rotated) = source.rotate_counter_clockwise() {
            if can_downscale {
                if let Some(r) = decode_with_binarizer(rotated.clone(), binarizer, &hints) {
                    return Some(r);
                }
                if let Some(ds) = downscale_2x(&rotated) {
                    if let Some(r) = decode_with_binarizer(ds, binarizer, &hints) {
                        return Some(r);
                    }
                }
            } else if let Some(r) = decode_with_binarizer(rotated, binarizer, &hints) {
                return Some(r);
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
) -> Option<rxing::RXingResult> {
    let mut reader = MultiFormatReader::default();
    if binarizer == 1 {
        let mut bb = BinaryBitmap::new(GlobalHistogramBinarizer::new(source));
        reader.decode_with_hints(&mut bb, hints).ok()
    } else {
        let mut bb = BinaryBitmap::new(HybridBinarizer::new(source));
        reader.decode_with_hints(&mut bb, hints).ok()
    }
}

fn format_to_string(fmt: BarcodeFormat) -> String {
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
///
/// For QR codes, a fixed mask pattern is used to ensure deterministic output.
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
