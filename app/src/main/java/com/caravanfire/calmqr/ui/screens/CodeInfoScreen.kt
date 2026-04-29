package com.caravanfire.calmqr.ui.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.data.SavedCode
import com.caravanfire.calmqr.data.SavedCodeDao
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeInfoScreen(
    codeId: Long,
    savedCodeDao: SavedCodeDao,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostStateMMD() }

    var code by remember { mutableStateOf<SavedCode?>(null) }
    var editableName by remember { mutableStateOf("") }

    LaunchedEffect(codeId) {
        val loaded = savedCodeDao.getCodeById(codeId)
        if (loaded == null) {
            // Code was deleted from another path; bail out.
            onBack()
            return@LaunchedEffect
        }
        code = loaded
        editableName = loaded.name
    }

    val loaded = code ?: return

    InfoContent(
        name = editableName,
        onNameChange = { editableName = it },
        format = loaded.format,
        content = loaded.content,
        timestamp = loaded.timestamp,
        snackbarHostState = snackbarHostState,
        onBack = {
            scope.launch {
                savedCodeDao.updateName(codeId, editableName)
            }
            onBack()
        },
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
