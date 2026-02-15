package com.caravanfire.calmqr.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caravanfire.calmqr.data.SavedCodeDao
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.launch

@Composable
fun DeleteConfirmScreen(
    codeId: Long,
    savedCodeDao: SavedCodeDao,
    onDeleted: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TextMMD(
            text = "Are you sure?",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            ButtonMMD(
                onClick = {
                    scope.launch {
                        savedCodeDao.getCodeById(codeId)?.let {
                            savedCodeDao.deleteCode(it)
                        }
                        onDeleted()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextMMD(text = "Yes, Delete", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButtonMMD(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextMMD(text = "Cancel", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
