package com.streamvault.data.util

import com.streamvault.domain.util.StreamEntryUrlPolicy
import java.net.URI
import java.util.Locale

object UrlSecurityPolicy {
    private val secureRemoteSchemes = setOf("https")
    private val playlistSourceSchemes = setOf("http", "https")
    private val xtreamServerSchemes = setOf("http", "https")
    private val localSchemes = setOf("file", "content")
    // IPTV stream/asset URLs may legitimately use plain HTTP or RTSP
    private val streamEntrySchemes = setOf("http", "https", "rtsp", "rtsps", "rtmp", "file", "content")

    fun isSecureRemoteUrl(url: String): Boolean = !containsNewlines(url) && hasAllowedScheme(url, secureRemoteSchemes)

    fun isAllowedImportedUrl(url: String): Boolean =
        !containsNewlines(url) && hasAllowedScheme(url, secureRemoteSchemes + localSchemes)

    /** Validates individual stream/asset entries inside an imported playlist. HTTP is allowed here
     *  because the majority of IPTV providers serve streams over plain HTTP. */
    fun isAllowedStreamEntryUrl(url: String): Boolean =
        StreamEntryUrlPolicy.isAllowed(url)

    fun validateXtreamServerUrl(url: String): String? {
        return if (!containsNewlines(url) && hasAllowedScheme(url, xtreamServerSchemes)) {
            null
        } else {
            "Xtream server URLs must use HTTP or HTTPS."
        }
    }

    fun validateStalkerPortalUrl(url: String): String? {
        return if (!containsNewlines(url) && hasAllowedScheme(url, xtreamServerSchemes)) {
            null
        } else {
            "Portal URLs must use HTTP or HTTPS."
        }
    }

    fun validateXtreamEpgUrl(url: String): String? {
        return if (!containsNewlines(url) && hasAllowedScheme(url, xtreamServerSchemes)) {
            null
        } else {
            "Xtream EPG URLs must use HTTP or HTTPS."
        }
    }

    fun validatePlaylistSourceUrl(url: String): String? {
        return if (!containsNewlines(url) && hasAllowedScheme(url, playlistSourceSchemes + localSchemes)) {
            null
        } else {
            "Playlist sources must use HTTP, HTTPS, or point to a local file."
        }
    }

    fun validateOptionalEpgUrl(url: String): String? {
        return when {
            url.isBlank() -> null
            url.startsWith("content://") -> null  // SAF local file; validated by OS file picker
            // Allow http:// as well as https:// — many IPTV portals serve their XMLTV
            // EPG endpoint over plain HTTP on non-standard ports (same policy as playlists).
            !containsNewlines(url) && hasAllowedScheme(url, playlistSourceSchemes) -> null
            else -> "EPG URLs must use HTTP, HTTPS, or select a local file."
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
        // Decode up to two percent-encoding layers to catch double-encoded payloads
        // (e.g. %250A → %0A → \n). Also checks %09 (tab) which can split log lines.
        var decoded = url
        repeat(2) {
            decoded = decoded
                .replace("%0A", "\n", ignoreCase = true)
                .replace("%0D", "\r", ignoreCase = true)
                .replace("%09", "\t", ignoreCase = true)
        }
        return decoded.any { it == '\n' || it == '\r' || it == '\t' }
    }

    private fun parseScheme(url: String): String? {
        return runCatching { URI(url.trim()).scheme }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
    }
}
