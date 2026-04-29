package com.caravanfire.calmqr.data

import com.caravanfire.calmqr.wifi.isWifiQrCode

/**
 * Semantic content type derived from the QR/barcode payload string.
 * QR codes don't have a "type" field in the spec — these are conventions
 * recognized by prefix in the content (WIFI:, mailto:, BEGIN:VCARD, etc.).
 */
enum class ContentType {
    URL,
    WIFI,
    PHONE,
    EMAIL,
    SMS,
    VCARD,
    GEO,
    PLAIN_TEXT,
}

/** Pure prefix-based classifier. No Android dependencies. */
object ContentTypeDetector {

    fun detect(content: String): ContentType {
        val trimmed = content.trim()
        return when {
            isWifiQrCode(trimmed) -> ContentType.WIFI
            trimmed.startsWith("http://", ignoreCase = true) -> ContentType.URL
            trimmed.startsWith("https://", ignoreCase = true) -> ContentType.URL
            trimmed.startsWith("tel:", ignoreCase = true) -> ContentType.PHONE
            trimmed.startsWith("mailto:", ignoreCase = true) -> ContentType.EMAIL
            trimmed.startsWith("MATMSG:", ignoreCase = true) -> ContentType.EMAIL
            trimmed.startsWith("sms:", ignoreCase = true) -> ContentType.SMS
            trimmed.startsWith("SMSTO:", ignoreCase = true) -> ContentType.SMS
            trimmed.startsWith("BEGIN:VCARD", ignoreCase = true) -> ContentType.VCARD
            trimmed.startsWith("MECARD:", ignoreCase = true) -> ContentType.VCARD
            trimmed.startsWith("geo:", ignoreCase = true) -> ContentType.GEO
            else -> ContentType.PLAIN_TEXT
        }
    }
}
