package com.caravanfire.calmqr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.caravanfire.calmqr.ui.screens.ScannerScreen
import com.mudita.mmd.ThemeMMD

/**
 * Calm QR's scanner, lent to other apps.
 *
 * Another app asks for a scan with the common ZXing request,
 * `Intent("com.google.zxing.client.android.SCAN")`, started for a result.
 * Calm QR then shows only its scanner, with all of its usual controls. When
 * a code is read it hands the text back to the app that asked and closes:
 *
 * - `SCAN_RESULT`: the decoded text
 * - `SCAN_RESULT_FORMAT`: the code type, named as ZXing names it
 *   (`QR_CODE`, `EAN_13`, …)
 *
 * Back (or closing the scanner) answers `RESULT_CANCELED`.
 *
 * A scan made here is not saved in Calm QR's list, and Calm QR does not look
 * at or remember which app asked. Extras of the ZXing request that narrow the
 * scan (`SCAN_FORMATS`, `SCAN_MODE`, `PROMPT_MESSAGE`) are not read: every
 * format Calm QR knows is accepted.
 */
class ScanForAppActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ThemeMMD {
                ScannerScreen(
                    onCodeScanned = { content, format, _, _ ->
                        setResult(
                            RESULT_OK,
                            Intent()
                                .putExtra(EXTRA_RESULT, content)
                                .putExtra(EXTRA_FORMAT, format),
                        )
                        finish()
                    },
                    onBack = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        /** The scanned text (ZXing's name for it). */
        const val EXTRA_RESULT = "SCAN_RESULT"

        /** The code type, e.g. `QR_CODE` (ZXing's name for it). */
        const val EXTRA_FORMAT = "SCAN_RESULT_FORMAT"
    }
}
