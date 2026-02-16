package com.caravanfire.calmqr.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.data.SavedCodeDao
import com.caravanfire.calmqr.ui.Dimens
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteConfirmScreen(
    codeId: Long,
    savedCodeDao: SavedCodeDao,
    onDeleted: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var codeName by remember { mutableStateOf("") }

    LaunchedEffect(codeId) {
        codeName = savedCodeDao.getCodeById(codeId)?.name ?: ""
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBarMMD(
                    showDivider = false,
                    title = {
                        TextMMD(
                            text = codeName,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.offset(x = Dimens.titleOffset)
                        )
                    },
                    navigationIcon = {
                        Box(modifier = Modifier.padding(4.dp)) {
                            IconButton(onClick = onCancel) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                )
                HorizontalDivider(thickness = 3.dp)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            TextMMD(
                text = stringResource(R.string.are_you_sure),
                fontSize = 36.sp,
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
                    TextMMD(text = stringResource(R.string.yes_delete), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
                Spacer(modifier = Modifier.height(Dimens.buttonSpacing))
                OutlinedButtonMMD(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextMMD(text = stringResource(R.string.cancel), style = Dimens.buttonTextStyle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.buttonTextPadding))
                }
                Spacer(modifier = Modifier.height(Dimens.bottomSpacing))
            }
        }
    }
}
