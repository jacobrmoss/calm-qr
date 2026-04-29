package com.caravanfire.calmqr.data

import org.json.JSONArray
import org.json.JSONObject

/** Light pretty-print for the info page raw data display. */
object RawDataFormatter {

    fun format(content: String, type: ContentType): String = when (type) {
        ContentType.WIFI -> formatWifi(content)
        ContentType.VCARD -> formatVCard(content)
        ContentType.PLAIN_TEXT -> tryFormatJson(content) ?: content
        else -> content
    }

    private fun formatWifi(content: String): String {
        // Split on un-escaped semicolons; drop the trailing empty segment from "...;;"
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c == '\\' && i + 1 < content.length -> {
                    current.append(c)
                    current.append(content[i + 1])
                    i += 2
                }
                c == ';' -> {
                    if (current.isNotEmpty()) parts.add(current.toString())
                    current.clear()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts.joinToString("\n")
    }

    private fun formatVCard(content: String): String {
        // Normalize CRLF to LF, then collapse runs of blank lines down to a single blank line
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        return normalized.replace(Regex("\n{3,}"), "\n\n")
    }

    private fun tryFormatJson(content: String): String? {
        val trimmed = content.trimStart()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(content).toString(2)
                trimmed.startsWith("[") -> JSONArray(content).toString(2)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
