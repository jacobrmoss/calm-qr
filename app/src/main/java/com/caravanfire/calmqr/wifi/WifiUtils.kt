package com.caravanfire.calmqr.wifi

import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.Log

private const val TAG = "WifiUtils"

/** Result of a WiFi save attempt. */
enum class WifiSaveResult {
    /** Network was handed to the system save dialog. */
    SAVED,
    /** Network was already saved on the device. */
    ALREADY_SAVED,
    /** Save failed. */
    FAILED
}

/**
 * Parsed WiFi credentials from a WiFi QR code.
 * Format: WIFI:T:<auth>;S:<ssid>;P:<password>;H:<hidden>;;
 */
data class WifiCredentials(
    val ssid: String,
    val password: String,
    val authType: String,   // WPA, WEP, nopass, SAE
    val hidden: Boolean
)

/** Returns true if [content] looks like a WiFi QR code payload. */
fun isWifiQrCode(content: String): Boolean =
    content.startsWith("WIFI:", ignoreCase = true)

/**
 * Parse a WiFi QR code string into [WifiCredentials].
 * Returns null if the string is not a valid WiFi QR code.
 */
fun parseWifiQrCode(content: String): WifiCredentials? {
    if (!content.startsWith("WIFI:", ignoreCase = true)) return null

    val body = content.removeRange(0, 5) // strip "WIFI:"
    val params = mutableMapOf<String, String>()
    var remaining = body

    while (remaining.isNotEmpty()) {
        val colonIdx = remaining.indexOf(':')
        if (colonIdx < 0) break

        val key = remaining.substring(0, colonIdx)
        remaining = remaining.substring(colonIdx + 1)

        // Read value until next un-escaped semicolon
        val value = StringBuilder()
        var i = 0
        while (i < remaining.length) {
            when {
                remaining[i] == '\\' && i + 1 < remaining.length -> {
                    value.append(remaining[i + 1])
                    i += 2
                }
                remaining[i] == ';' -> break
                else -> {
                    value.append(remaining[i])
                    i++
                }
            }
        }
        params[key] = value.toString()
        remaining = if (i < remaining.length) remaining.substring(i + 1) else ""
    }

    val ssid = params["S"] ?: return null
    val password = params["P"] ?: ""
    val authType = params["T"] ?: "nopass"
    val hidden = params["H"].equals("true", ignoreCase = true)

    return WifiCredentials(ssid, password, authType, hidden)
}

/**
 * Build a [WifiNetworkSuggestion] for use with ACTION_WIFI_ADD_NETWORKS.
 */
private fun buildSuggestion(creds: WifiCredentials): WifiNetworkSuggestion {
    val builder = WifiNetworkSuggestion.Builder()
        .setSsid(creds.ssid)
        .setIsHiddenSsid(creds.hidden)

    when (creds.authType.uppercase()) {
        "WPA" -> builder.setWpa2Passphrase(creds.password)
        "SAE" -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setWpa3Passphrase(creds.password)
            } else {
                builder.setWpa2Passphrase(creds.password)
            }
        }
        // WEP / nopass → open network
    }

    return builder.build()
}

/**
 * Build an [Intent] to add a WiFi network via [Settings.ACTION_WIFI_ADD_NETWORKS].
 *
 * The returned intent must be launched with [androidx.activity.result.ActivityResultLauncher]
 * (i.e. `startActivityForResult`) so the system dialog can identify the calling app.
 * Using plain `startActivity` causes the dialog to show "null" as the app name.
 *
 * @return the configured [Intent], or `null` if the API level is too low.
 */
fun buildWifiSaveIntent(creds: WifiCredentials): Intent? {
    Log.d(TAG, "buildWifiSaveIntent: ssid=${creds.ssid}, auth=${creds.authType}, hidden=${creds.hidden}")

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        Log.w(TAG, "ACTION_WIFI_ADD_NETWORKS requires API 30+, device is ${Build.VERSION.SDK_INT}")
        return null
    }

    return try {
        val suggestion = buildSuggestion(creds)
        Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST,
                arrayListOf(suggestion)
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to build wifi save intent", e)
        null
    }
}

/**
 * Interpret the result from the [Settings.ACTION_WIFI_ADD_NETWORKS] system dialog.
 */
fun parseWifiSaveResult(resultCode: Int, data: Intent?): WifiSaveResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return WifiSaveResult.FAILED

    val results = data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
    if (results == null || results.isEmpty()) {
        Log.w(TAG, "No result list from wifi save dialog (resultCode=$resultCode)")
        return WifiSaveResult.FAILED
    }

    return when (results[0]) {
        Settings.ADD_WIFI_RESULT_SUCCESS -> {
            Log.i(TAG, "WiFi network saved successfully")
            WifiSaveResult.SAVED
        }
        Settings.ADD_WIFI_RESULT_ALREADY_EXISTS -> {
            Log.i(TAG, "WiFi network already saved")
            WifiSaveResult.ALREADY_SAVED
        }
        else -> {
            Log.w(TAG, "WiFi save returned code: ${results[0]}")
            WifiSaveResult.FAILED
        }
    }
}
