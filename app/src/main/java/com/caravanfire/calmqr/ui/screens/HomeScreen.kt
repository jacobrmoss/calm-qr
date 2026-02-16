package com.caravanfire.calmqr.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.sharp.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.data.SavedCodeDao
import com.caravanfire.calmqr.ui.components.DashedDivider
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.search_bar.SearchBarDefaultsMMD
import com.mudita.mmd.components.search_bar.SearchBarMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import kotlinx.coroutines.launch

private enum class HomeMode { NORMAL, SEARCH, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    savedCodeDao: SavedCodeDao,
    onScanClick: () -> Unit,
    onCodeClick: (Long) -> Unit
) {
    var mode by remember { mutableStateOf(HomeMode.NORMAL) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val scope = rememberCoroutineScope()

    val codes by when (mode) {
        HomeMode.SEARCH -> savedCodeDao.searchCodes(searchQuery)
        else -> savedCodeDao.getAllCodes()
    }.collectAsState(initial = null)

    Scaffold(
        topBar = {
            Column {
            when (mode) {
                HomeMode.NORMAL -> {
                    TopAppBarMMD(
                        showDivider = false,
                        title = { TextMMD(text = stringResource(R.string.app_title), fontWeight = FontWeight.Bold) },
                        actions = {
                            IconButton(
                                onClick = { mode = HomeMode.SEARCH },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search), modifier = Modifier.size(32.dp))
                            }
                            IconButton(
                                onClick = {
                                    mode = HomeMode.DELETE
                                    selectedIds = emptySet()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(32.dp))
                            }
                            IconButton(
                                onClick = onScanClick,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.Black,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = stringResource(R.string.scan),
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                HomeMode.SEARCH -> {
                    TopAppBarMMD(
                        showDivider = false,
                        title = {
                            SearchBarMMD(
                                modifier = Modifier.fillMaxWidth().offset(x = (-12).dp),
                                inputField = {
                                    SearchBarDefaultsMMD.InputField(
                                        query = searchQuery,
                                        onQueryChange = { searchQuery = it },
                                        onSearch = { },
                                        expanded = false,
                                        onExpandedChange = { },
                                        placeholder = { TextMMD(stringResource(R.string.search_codes_placeholder)) },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { searchQuery = "" }) {
                                                    Icon(Icons.Sharp.Clear, contentDescription = null)
                                                }
                                            }
                                        },
                                    )
                                },
                                expanded = false,
                                onExpandedChange = {},
                            ) { }
                        },
                        navigationIcon = {
                            Box(modifier = Modifier.padding(4.dp)) {
                                IconButton(onClick = {
                                    mode = HomeMode.NORMAL
                                    searchQuery = ""
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.close_search),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    )
                }

                HomeMode.DELETE -> {
                    TopAppBarMMD(
                        showDivider = false,
                        title = { TextMMD(text = stringResource(R.string.select_codes), modifier = Modifier.offset(x = (-12).dp)) },
                        navigationIcon = {
                            Box(modifier = Modifier.padding(4.dp)) {
                                IconButton(onClick = {
                                    mode = HomeMode.NORMAL
                                    selectedIds = emptySet()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cancel),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        },
                        actions = {
                            ButtonMMD(
                                onClick = {
                                    scope.launch {
                                        savedCodeDao.deleteCodesByIds(selectedIds.toList())
                                        selectedIds = emptySet()
                                        mode = HomeMode.NORMAL
                                    }
                                },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                TextMMD(
                                    text = stringResource(R.string.delete_count, selectedIds.size),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    )
                }
            }
            HorizontalDivider(thickness = 4.dp)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (codes == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicatorMMD()
                }
            } else if (codes!!.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextMMD(text = stringResource(R.string.no_saved_codes))
                }
            } else {
                LazyColumnMMD(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                items(codes!!, key = { it.id }) { code ->
                    when (mode) {
                        HomeMode.DELETE -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clickable {
                                        selectedIds = if (code.id in selectedIds) {
                                            selectedIds - code.id
                                        } else {
                                            selectedIds + code.id
                                        }
                                    }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CheckboxMMD(
                                    checked = code.id in selectedIds,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) {
                                            selectedIds + code.id
                                        } else {
                                            selectedIds - code.id
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                TextMMD(text = code.name)
                            }
                        }

                        else -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clickable { onCodeClick(code.id) }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextMMD(text = code.name)
                            }
                        }
                    }
                    DashedDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        }
    }
}
