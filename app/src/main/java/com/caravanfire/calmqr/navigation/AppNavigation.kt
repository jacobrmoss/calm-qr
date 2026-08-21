package com.caravanfire.calmqr.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.caravanfire.calmqr.R
import com.caravanfire.calmqr.data.SavedCode
import com.caravanfire.calmqr.data.SavedCodeDao
import com.caravanfire.calmqr.ui.barcodePixelData
import com.caravanfire.calmqr.ui.screens.CodeDetailScreen
import com.caravanfire.calmqr.ui.screens.CodeInfoScreen
import com.caravanfire.calmqr.ui.screens.EinkTransitionScreen
import com.caravanfire.calmqr.ui.screens.HomeScreen
import com.caravanfire.calmqr.ui.screens.ScanDetailScreen
import com.caravanfire.calmqr.ui.screens.ScannerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun androidx.navigation.NavBackStackEntry.codeId(): Long =
    arguments?.getLong("codeId") ?: 0L

private val codeIdArg = navArgument("codeId") { type = NavType.LongType }

@Composable
fun AppNavigation(
    navController: NavHostController,
    savedCodeDao: SavedCodeDao
) {
    val scope = rememberCoroutineScope()
    val untitled = stringResource(R.string.untitled)

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                savedCodeDao = savedCodeDao,
                onScanClick = {
                    navController.navigate(Screen.Scanner.route)
                },
                onCodeClick = { codeId ->
                    navController.navigate(Screen.EinkTransition.createRoute(codeId))
                }
            )
        }

        composable(Screen.Scanner.route) {
            ScannerScreen(
                onCodeScanned = { content, format, qrGrid ->
                    // Insert first, then navigate by row id. Scanned content never
                    // rides in a route (see Screen.kt).
                    scope.launch {
                        // barcodePixelData renders over JNI — keep it off the
                        // main thread so the scan→detail transition stays smooth
                        val id = withContext(Dispatchers.Default) {
                            savedCodeDao.insertCode(
                                SavedCode(
                                    name = untitled,
                                    content = content,
                                    format = format,
                                    qrImageData = barcodePixelData(content, format, qrGrid),
                                    createdAt = System.currentTimeMillis(),
                                    isSaved = false,
                                )
                            )
                        }
                        navController.navigate(Screen.ScanDetail.createRoute(id)) {
                            popUpTo(Screen.Scanner.route) { inclusive = true }
                        }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.ScanDetail.route,
            arguments = listOf(codeIdArg)
        ) { backStackEntry ->
            ScanDetailScreen(
                codeId = backStackEntry.codeId(),
                savedCodeDao = savedCodeDao,
                onSaved = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                onRescan = {
                    navController.navigate(Screen.Scanner.route) {
                        popUpTo(Screen.ScanDetail.route) { inclusive = true }
                    }
                },
                onCancel = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                onRequestInfo = { codeId ->
                    navController.navigate(Screen.ScanInfo.createRoute(codeId))
                },
            )
        }

        composable(
            route = Screen.EinkTransition.route,
            arguments = listOf(codeIdArg)
        ) { backStackEntry ->
            val codeId = backStackEntry.codeId()
            EinkTransitionScreen(
                onReady = {
                    navController.navigate(Screen.CodeDetail.createRoute(codeId)) {
                        popUpTo(Screen.EinkTransition.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.CodeDetail.route,
            arguments = listOf(codeIdArg)
        ) { backStackEntry ->
            val codeId = backStackEntry.codeId()

            CodeDetailScreen(
                codeId = codeId,
                savedCodeDao = savedCodeDao,
                onBack = {
                    navController.popBackStack()
                },
                onDeleted = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                onRequestEinkRefresh = {
                    navController.navigate(Screen.EinkTransition.createRoute(codeId)) {
                        popUpTo(Screen.CodeDetail.route) { inclusive = true }
                    }
                },
                onRequestInfo = {
                    navController.navigate(Screen.CodeInfo.createRoute(codeId))
                },
            )
        }

        composable(
            route = Screen.CodeInfo.route,
            arguments = listOf(codeIdArg)
        ) { backStackEntry ->
            val codeId = backStackEntry.codeId()
            CodeInfoScreen(
                codeId = codeId,
                savedCodeDao = savedCodeDao,
                onBack = {
                    navController.navigate(Screen.EinkTransition.createRoute(codeId)) {
                        popUpTo(Screen.CodeDetail.route) { inclusive = true }
                    }
                }
            )
        }

        // Info page for a not-yet-saved scan: same screen as CodeInfo, but back
        // pops straight to ScanDetail (no e-ink transition detour).
        composable(
            route = Screen.ScanInfo.route,
            arguments = listOf(codeIdArg)
        ) { backStackEntry ->
            CodeInfoScreen(
                codeId = backStackEntry.codeId(),
                savedCodeDao = savedCodeDao,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
