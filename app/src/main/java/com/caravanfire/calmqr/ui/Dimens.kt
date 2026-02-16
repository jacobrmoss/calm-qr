package com.caravanfire.calmqr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Shared spacing and style values used across screens. */
object Dimens {
    /** Vertical gap between buttons. */
    val buttonSpacing = 14.dp

    /** Vertical padding inside button labels. */
    val buttonTextPadding = 6.dp

    /** Bottom spacer below the last button on a screen. */
    val bottomSpacing = 1.dp

    /** Horizontal offset for top bar title text. */
    val titleOffset = (-21).dp

    /** Max width fraction for QR code images. */
    const val qrMaxWidthFraction = 0.75f

    /** Max width fraction for barcode images. */
    const val barcodeMaxWidthFraction = 0.85f

    /** Text style for button labels. */
    val buttonTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineSmall
}
