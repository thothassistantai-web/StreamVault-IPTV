package com.streamvault.data.util

import java.net.URI
import java.util.Locale

object UrlSecurityPolicy {
    private val secureRemoteSchemes = setOf("https")
    private val localSchemes = setOf("file", "content")
    // IPTV stream/asset URLs may legitimately use plain HTTP or RTSP
    private val streamEntrySchemes = setOf("http", "https", "rtsp", "rtsps", "rtmp", "file", "content")

    fun isSecureRemoteUrl(url: String): Boolean = !containsNewlines(url) && hasAllowedScheme(url, secureRemoteSchemes)

    fun isAllowedImportedUrl(url: String): Boolean =
        !containsNewlines(url) && hasAllowedScheme(url, secureRemoteSchemes + localSchemes)

    /** Validates individual stream/asset entries inside an imported playlist. HTTP is allowed here
     *  because the majority of IPTV providers serve streams over plain HTTP. */
    fun isAllowedStreamEntryUrl(url: String): Boolean =
        !containsNewlines(url) && hasAllowedScheme(url, streamEntrySchemes)

    fun validateXtreamServerUrl(url: String): String? {
        return if (isSecureRemoteUrl(url)) {
            null
        } else {
            "Only HTTPS Xtream server URLs are supported."
        }
    }

    fun validatePlaylistSourceUrl(url: String): String? {
        return if (isAllowedImportedUrl(url)) {
            null
        } else {
            "Playlist sources must use HTTPS or point to a local file."
        }
    }

    fun validateOptionalEpgUrl(url: String): String? {
        return if (url.isBlank() || isSecureRemoteUrl(url)) {
            null
        } else {
            "EPG URLs must use HTTPS."
        }
    }

    fun sanitizeImportedAssetUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() && isAllowedStreamEntryUrl(it) }
    }

    private fun hasAllowedScheme(url: String, allowedSchemes: Set<String>): Boolean {
        val scheme = parseScheme(url) ?: return false
        return scheme in allowedSchemes
    }

    private fun containsNewlines(url: String): Boolean {
        val decoded = url.replace("%0A", "\n", ignoreCase = true)
            .replace("%0D", "\r", ignoreCase = true)
        return decoded.contains('\n') || decoded.contains('\r')
    }

    private fun parseScheme(url: String): String? {
        return runCatching { URI(url.trim()).scheme }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
    }
}
