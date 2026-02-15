package com.caravanfire.calmqr.ui.components

import android.app.Activity
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * E-ink refresh overlay. Adds a real Android View (opaque black) to the Window,
 * waits briefly, then removes it. This bypasses Compose's rendering pipeline
 * and forces the e-ink display controller to do a full pixel transition.
 *
 * @param trigger Increment this Int each time a flash is needed. 0 means no flash.
 * @param delayMs How long the black overlay stays visible (ms).
 */
@Composable
fun EinkRefreshOverlay(
    trigger: Int,
    delayMs: Long = 150L
) {
    val context = LocalContext.current

    LaunchedEffect(trigger) {
        if (trigger > 0) {
            val activity = context as? Activity ?: return@LaunchedEffect
            val wm = activity.windowManager

            val overlay = View(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )

            withContext(Dispatchers.Main) {
                wm.addView(overlay, params)
            }

            delay(delayMs)

            withContext(Dispatchers.Main) {
                wm.removeView(overlay)
            }
        }
    }
}
