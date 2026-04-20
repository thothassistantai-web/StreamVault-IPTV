package com.streamvault.data.remote.stalker

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

enum class StalkerStreamKind(val pathSegment: String) {
    LIVE("live"),
    MOVIE("movie"),
    EPISODE("episode")
}

data class StalkerStreamToken(
    val providerId: Long,
    val kind: StalkerStreamKind,
    val itemId: Long,
    val cmd: String,
    val containerExtension: String? = null
)

object StalkerUrlFactory {
    private const val INTERNAL_SCHEME = "stalker"

    fun normalizePortalUrl(url: String): String =
        url.trim().trimEnd('/')

    fun loadUrlCandidates(portalUrl: String): List<String> {
        val normalized = normalizePortalUrl(portalUrl)
        val direct = normalized.lowercase(Locale.ROOT)
        val candidates = linkedSetOf<String>()
        when {
            direct.endsWith("/server/load.php") || direct.endsWith("/portal.php") -> {
                candidates += normalized
            }

            direct.endsWith("/c") -> {
                val base = normalized.removeSuffix("/c")
                candidates += "$base/server/load.php"
                candidates += "$base/portal.php"
            }

            else -> {
                candidates += "$normalized/server/load.php"
                candidates += "$normalized/portal.php"
                candidates += "${normalized.trimEnd('/')}/stalker_portal/server/load.php"
                candidates += "${normalized.trimEnd('/')}/stalker_portal/portal.php"
            }
        }
        return candidates.toList()
    }

    fun portalReferer(loadUrl: String): String {
        val normalized = normalizePortalUrl(loadUrl)
        return when {
            normalized.lowercase(Locale.ROOT).endsWith("/server/load.php") ->
                normalized.removeSuffix("/server/load.php") + "/c/"
            normalized.lowercase(Locale.ROOT).endsWith("/portal.php") ->
                normalized.removeSuffix("/portal.php") + "/c/"
            else -> normalized.trimEnd('/') + "/c/"
        }
    }

    fun buildInternalStreamUrl(
        providerId: Long,
        kind: StalkerStreamKind,
        itemId: Long,
        cmd: String,
        containerExtension: String? = null
    ): String {
        val query = buildList {
            add("cmd=${encode(cmd)}")
            containerExtension?.trim()
                ?.removePrefix(".")
                ?.takeIf { it.isNotBlank() }
                ?.let { ext -> add("ext=${encode(ext.lowercase(Locale.ROOT))}") }
        }.joinToString("&")
        return "$INTERNAL_SCHEME://$providerId/${kind.pathSegment}/$itemId?$query"
    }

    fun parseInternalStreamUrl(url: String?): StalkerStreamToken? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals(INTERNAL_SCHEME, ignoreCase = true)) return null
        val providerId = uri.authority?.toLongOrNull() ?: return null
        val pathSegments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        val kind = pathSegments.firstOrNull()?.let(::kindFromPathSegment) ?: return null
        val itemId = pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        val query = parseQuery(uri.rawQuery)
        val cmd = query["cmd"] ?: return null
        val ext = query["ext"]?.trim()?.takeIf { it.isNotBlank() }
        return StalkerStreamToken(
            providerId = providerId,
            kind = kind,
            itemId = itemId,
            cmd = cmd,
            containerExtension = ext
        )
    }

    fun isInternalStreamUrl(url: String?): Boolean = parseInternalStreamUrl(url) != null

    private fun kindFromPathSegment(segment: String): StalkerStreamKind? = when (segment.lowercase(Locale.ROOT)) {
        StalkerStreamKind.LIVE.pathSegment -> StalkerStreamKind.LIVE
        StalkerStreamKind.MOVIE.pathSegment -> StalkerStreamKind.MOVIE
        StalkerStreamKind.EPISODE.pathSegment -> StalkerStreamKind.EPISODE
        else -> null
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val key = pair.substringBefore("=", "")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                decode(key) to decode(pair.substringAfter("=", ""))
            }
            .toMap()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
