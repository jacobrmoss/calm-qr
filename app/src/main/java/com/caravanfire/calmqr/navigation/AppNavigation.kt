package com.caravanfire.calmqr.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.caravanfire.calmqr.data.SavedCodeDao
import com.caravanfire.calmqr.ui.screens.CodeDetailScreen
import com.caravanfire.calmqr.ui.screens.EinkTransitionScreen
import com.caravanfire.calmqr.ui.screens.HomeScreen
import com.caravanfire.calmqr.ui.screens.ScanDetailScreen
import com.caravanfire.calmqr.ui.screens.ScannerScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    savedCodeDao: SavedCodeDao
) {
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
                onCodeScanned = { content, format ->
                    navController.navigate(Screen.ScanDetail.createRoute(content, format)) {
                        popUpTo(Screen.Scanner.route) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.ScanDetail.route,
            arguments = listOf(
                navArgument("content") { type = NavType.StringType },
                navArgument("format") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val content = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("content") ?: "", "UTF-8"
            )
            val format = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("format") ?: "", "UTF-8"
            )
            ScanDetailScreen(
                content = content,
                format = format,
                savedCodeDao = savedCodeDao,
                onSaved = { codeId ->
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                onRescan = {
                    navController.navigate(Screen.Scanner.route) {
                        popUpTo(Screen.ScanDetail.route) { inclusive = true }
                    }
                },
                onCancel = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                }
            )
        }

        composable(
            route = Screen.EinkTransition.route,
            arguments = listOf(
                navArgument("codeId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val codeId = backStackEntry.arguments?.getLong("codeId") ?: 0L
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
            arguments = listOf(
                navArgument("codeId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val codeId = backStackEntry.arguments?.getLong("codeId") ?: 0L

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
                }
            )
        }
    }
}

