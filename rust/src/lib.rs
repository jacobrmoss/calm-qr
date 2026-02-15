use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use rxing::helpers::detect_in_luma_with_hints;
use rxing::{BarcodeFormat, DecodeHintType, DecodeHintValue, DecodingHintDictionary, MultiFormatWriter, Writer};

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
/// When `try_harder` is true, enables TRY_HARDER hint for more thorough detection.
/// Returns a `DecodeResult` object or null if no barcode is found.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_caravanfire_calmqr_rust_RustBridge_decodeBarcode<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    luma_bytes: JByteArray<'local>,
    width: jint,
    height: jint,
    try_harder: jboolean,
) -> JObject<'local> {
    // Convert JByteArray to Vec<u8>
    let len = match env.get_array_length(&luma_bytes) {
        Ok(l) => l as usize,
        Err(_) => return JObject::null(),
    };

    let mut buf = vec![0i8; len];
    if env.get_byte_array_region(&luma_bytes, 0, &mut buf).is_err() {
        return JObject::null();
    }

    // Reinterpret i8 slice as u8 slice safely
    let luma: Vec<u8> = buf.into_iter().map(|b| b as u8).collect();

    let mut hints = DecodingHintDictionary::new();
    if try_harder != 0 {
        hints.insert(
            DecodeHintType::TRY_HARDER,
            DecodeHintValue::TryHarder(true),
        );
    }

    let result = detect_in_luma_with_hints(
        luma,
        width as u32,
        height as u32,
        None,
        &mut hints,
    );

    match result {
        Ok(rxing_result) => {
            let text = rxing_result.getText();
            let format = match rxing_result.getBarcodeFormat() {
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
                other => format!("{}", other),
            };

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
        Err(_) => JObject::null(),
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
