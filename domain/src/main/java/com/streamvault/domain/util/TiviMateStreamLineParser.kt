package com.streamvault.domain.util

data class TiviMateStreamLine(
    val url: String,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Parses TiviMate-style playlist stream lines:
 * `https://example.com/live.m3u8|User-Agent=...|Referer=...|Origin=...`
 */
object TiviMateStreamLineParser {
    fun parse(rawLine: String): TiviMateStreamLine {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) {
            return TiviMateStreamLine(url = "")
        }

        val parts = trimmed.split('|')
        val url = parts.first().trim()
        if (parts.size == 1) {
            return TiviMateStreamLine(url = url)
        }

        var userAgent: String? = null
        val headers = linkedMapOf<String, String>()
        for (part in parts.drop(1)) {
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val key = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            if (key.isBlank() || value.isBlank()) continue
            when (key.lowercase()) {
                "user-agent" -> userAgent = value
                "referer" -> headers["Referer"] = value
                "origin" -> headers["Origin"] = value
            }
        }

        return TiviMateStreamLine(
            url = url,
            userAgent = userAgent,
            headers = headers,
        )
    }
}
