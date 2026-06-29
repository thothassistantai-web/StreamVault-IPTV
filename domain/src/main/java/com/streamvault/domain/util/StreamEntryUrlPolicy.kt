package com.streamvault.domain.util

import java.net.URI
import java.util.Locale

object StreamEntryUrlPolicy {
    private val allowedSchemes = setOf("http", "https", "rtsp", "rtsps", "rtmp", "file", "content")

    fun isAllowed(url: String): Boolean {
        val normalized = url.substringBefore("|").trim()
        return !containsControlSeparators(url) && hasAllowedScheme(normalized)
    }

    private fun hasAllowedScheme(url: String): Boolean {
        val scheme = runCatching { URI(url.trim()).scheme }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?: return false
        return scheme in allowedSchemes
    }

    private fun containsControlSeparators(url: String): Boolean {
        var decoded = url
        repeat(2) {
            decoded = decoded
                .replace("%0A", "\n", ignoreCase = true)
                .replace("%0D", "\r", ignoreCase = true)
                .replace("%09", "\t", ignoreCase = true)
        }
        return decoded.any { it == '\n' || it == '\r' || it == '\t' }
    }
}