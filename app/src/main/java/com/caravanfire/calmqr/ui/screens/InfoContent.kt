package com.caravanfire.calmqr.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.data.ContentType
import com.caravanfire.calmqr.data.ContentTypeDetector
import com.caravanfire.calmqr.data.RawDataFormatter
import com.caravanfire.calmqr.ui.Dimens
import com.mudita.mmd.components.snackbar.SnackbarHostMMD
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldDefaultsMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import java.text.DateFormat
import java.util.Date

/**
 * Shared body of the info page, used by both CodeInfoScreen (saved) and
 * ScanInfoScreen (mid-scan, pre-save).
 *
 * @param name editable code name; pass current value
 * @param onNameChange invoked with each edit
 * @param content raw payload string from the QR/barcode
 * @param createdAt epoch ms when the code was first saved; null hides the date row
 *   (e.g., the unsaved path, or pre-existing rows from before the column was added)
 * @param snackbarHostState shared host so the screen wrapper controls snackbar coroutines
 * @param onBack invoked when the back arrow is tapped
 * @param onCopy invoked with the original content when copy is tapped
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoContent(
    name: String,
    onNameChange: (String) -> Unit,
    content: String,
    createdAt: Long?,
    snackbarHostState: SnackbarHostStateMMD,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val contentType = remember(content) { ContentTypeDetector.detect(content) }
    val prettyContent = remember(content, contentType) {
        RawDataFormatter.format(content, contentType)
    }

    Scaffold(
        snackbarHost = { SnackbarHostMMD(hostState = snackbarHostState) },
        topBar = {
            Column {
                TopAppBarMMD(
                    showDivider = false,
                    title = {
                        TextFieldMMD(
                            value = name,
                            onValueChange = onNameChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            colors = TextFieldDefaultsMMD.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    val extra = 29.dp.roundToPx()
                                    val newMaxWidth = constraints.maxWidth + extra
                                    val placeable = measurable.measure(
                                        constraints.copy(
                                            minWidth = newMaxWidth,
                                            maxWidth = newMaxWidth,
                                        ),
                                    )
                                    layout(constraints.maxWidth, placeable.height) {
                                        placeable.placeRelative(0, 0)
                                    }
                                }
                                .offset(x = Dimens.titleOffset),
                        )
                    },
                    navigationIcon = {
                        Box(modifier = Modifier.padding(4.dp)) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                    },
                )
                HorizontalDivider(thickness = 3.dp)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (createdAt != null) {
                InfoRow(
                    label = stringResource(R.string.info_label_date_added),
                    value = remember(createdAt) {
                        DateFormat.getDateTimeInstance(
                            DateFormat.LONG,
                            DateFormat.SHORT,
                        ).format(Date(createdAt))
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
            InfoRow(
                label = stringResource(R.string.info_label_content),
                value = stringResource(contentTypeLabelRes(contentType)),
            )
            Spacer(Modifier.height(16.dp))

            // Raw data label + copy icon row
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextMMD(
                    text = stringResource(R.string.info_label_raw_data),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { onCopy(content) }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.info_action_copy),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Raw content body — monospace, scrollable when long
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextMMD(
                    text = prettyContent,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
            Spacer(Modifier.height(Dimens.bottomSpacing))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        TextMMD(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        TextMMD(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun contentTypeLabelRes(type: ContentType): Int = when (type) {
    ContentType.URL -> R.string.content_type_url
    ContentType.WIFI -> R.string.content_type_wifi
    ContentType.PHONE -> R.string.content_type_phone
    ContentType.EMAIL -> R.string.content_type_email
    ContentType.SMS -> R.string.content_type_sms
    ContentType.VCARD -> R.string.content_type_vcard
    ContentType.GEO -> R.string.content_type_geo
    ContentType.PLAIN_TEXT -> R.string.content_type_plain_text
}
