package com.caravanfire.calmqr.navigation

/**
 * Every route argument is a numeric Room row id — never scanned content.
 *
 * Routes are URIs; string payloads in routes need escaping contracts that are
 * easy to break (a stray extra decode once crashed the app on content with "%"
 * and corrupted "+" to space). Scans are inserted into the database first and
 * screens load them by id, so no payload ever touches the URI layer.
 * RouteHygieneTest enforces this.
 */
sealed class Screen(val route: String) {

    data object Home : Screen("home")

    data object Scanner : Screen("scanner")

    data object ScanDetail : Screen("scan_detail/{codeId}") {
        fun createRoute(codeId: Long): String = "scan_detail/$codeId"
    }

    data object EinkTransition : Screen("eink_transition/{codeId}") {
        fun createRoute(codeId: Long): String = "eink_transition/$codeId"
    }

    data object CodeDetail : Screen("code_detail/{codeId}") {
        fun createRoute(codeId: Long): String = "code_detail/$codeId"
    }

    data object CodeInfo : Screen("code_info/{codeId}") {
        fun createRoute(codeId: Long): String = "code_info/$codeId"
    }

    data object ScanInfo : Screen("scan_info/{codeId}") {
        fun createRoute(codeId: Long): String = "scan_info/$codeId"
    }
}
