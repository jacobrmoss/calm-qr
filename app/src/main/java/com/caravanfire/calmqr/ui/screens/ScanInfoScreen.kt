package com.caravanfire.calmqr.ui.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import com.caravanfire.calmqr.R
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
import kotlinx.coroutines.launch

const val PENDING_NAME_KEY = "pendingName"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanInfoScreen(
    initialName: String,
    content: String,
    format: String,
    previousSavedStateHandle: SavedStateHandle?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostStateMMD() }

    var editableName by rememberSaveable { mutableStateOf(initialName) }

    InfoContent(
        name = editableName,
        onNameChange = { newName ->
            editableName = newName
            previousSavedStateHandle?.set(PENDING_NAME_KEY, newName)
        },
        format = format,
        content = content,
        timestamp = null,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onCopy = { raw ->
            clipboard.setText(AnnotatedString(raw))
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    context.getString(R.string.info_copied_to_clipboard)
                )
            }
        },
    )
}
