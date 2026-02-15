package com.caravanfire.calmqr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * A fully black screen used as an e-ink refresh transition.
 * Shows solid black for [delayMs], then calls [onReady] to proceed
 * to the actual destination. This forces the e-ink controller to do
 * a full pixel transition, clearing all ghosting.
 */
@Composable
fun EinkTransitionScreen(
    delayMs: Long = 100L,
    onReady: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    )

    LaunchedEffect(Unit) {
        delay(delayMs)
        onReady()
    }
}
