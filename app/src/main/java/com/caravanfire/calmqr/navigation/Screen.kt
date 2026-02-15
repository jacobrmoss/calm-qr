package com.caravanfire.calmqr.navigation

import java.net.URLEncoder

sealed class Screen(val route: String) {

    data object Home : Screen("home")

    data object Scanner : Screen("scanner")

    data object ScanResult : Screen("scan_result/{content}/{format}") {
        fun createRoute(content: String, format: String): String {
            val encodedContent = URLEncoder.encode(content, "UTF-8")
            val encodedFormat = URLEncoder.encode(format, "UTF-8")
            return "scan_result/$encodedContent/$encodedFormat"
        }
    }

    data object ScanDetail : Screen("scan_detail/{content}/{format}") {
        fun createRoute(content: String, format: String): String {
            val encodedContent = URLEncoder.encode(content, "UTF-8")
            val encodedFormat = URLEncoder.encode(format, "UTF-8")
            return "scan_detail/$encodedContent/$encodedFormat"
        }
    }

    data object EinkTransition : Screen("eink_transition/{codeId}") {
        fun createRoute(codeId: Long): String = "eink_transition/$codeId"
    }

    data object CodeDetail : Screen("code_detail/{codeId}") {
        fun createRoute(codeId: Long): String = "code_detail/$codeId"
    }

    data object DeleteConfirm : Screen("delete_confirm/{codeId}") {
        fun createRoute(codeId: Long): String = "delete_confirm/$codeId"
    }
}
