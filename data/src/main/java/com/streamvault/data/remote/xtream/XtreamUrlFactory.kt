package com.streamvault.data.remote.xtream

import android.net.Uri
import com.streamvault.domain.model.ContentType

enum class XtreamStreamKind(val pathSegment: String) {
    LIVE("live"),
    MOVIE("movie"),
    SERIES("series")
}

data class XtreamStreamToken(
    val providerId: Long,
    val kind: XtreamStreamKind,
    val streamId: Long,
    val containerExtension: String? = null
)

object XtreamUrlFactory {
    private const val INTERNAL_SCHEME = "xtream"
    private val queryCredentialRegex = Regex("([?&](?:username|password)=)[^&\\s]+", RegexOption.IGNORE_CASE)
    private val liveMovieSeriesPathRegex = Regex(
        """(https?://[^\s/]+(?:/[^\s/?#]+)*)/(live|movie|series)/[^/\s?]+/[^/\s?]+(/[^\s?#]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val timeshiftPathRegex = Regex(
        """(https?://[^\s/]+(?:/[^\s/?#]+)*)/timeshift/[^/\s?]+/[^/\s?]+(/[^\s?#]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun buildPlayerApiUrl(
        serverUrl: String,
        username: String,
        password: String,
        action: String? = null,
        extraQueryParams: Map<String, String?> = emptyMap()
    ): String {
        return baseBuilder(serverUrl)
            .appendPath("player_api.php")
            .appendQueryParameter("username", username)
            .appendQueryParameter("password", password)
            .apply {
                action?.let { appendQueryParameter("action", it) }
                extraQueryParams.forEach { (key, value) -> value?.let { appendQueryParameter(key, it) } }
            }
            .build()
            .toString()
    }

    fun buildXmltvUrl(serverUrl: String, username: String, password: String): String {
        return baseBuilder(serverUrl)
            .appendPath("xmltv.php")
            .appendQueryParameter("username", username)
            .appendQueryParameter("password", password)
            .build()
            .toString()
    }

    fun buildPlaybackUrl(
        serverUrl: String,
        username: String,
        password: String,
        kind: XtreamStreamKind,
        streamId: Long,
        containerExtension: String? = null
    ): String {
        val ext = when (kind) {
            XtreamStreamKind.LIVE -> "ts"
            XtreamStreamKind.MOVIE, XtreamStreamKind.SERIES -> containerExtension ?: "mp4"
        }
        return serverUrl.trimEnd('/') + "/${kind.pathSegment}/" +
            Uri.encode(username) + "/" +
            Uri.encode(password) + "/" +
            Uri.encode(streamId.toString()) + "." + Uri.encode(ext)
    }

    fun buildCatchUpUrl(
        serverUrl: String,
        username: String,
        password: String,
        durationMinutes: Long,
        formattedStart: String,
        streamId: Long
    ): String {
        return serverUrl.trimEnd('/') + "/timeshift/" +
            Uri.encode(username) + "/" +
            Uri.encode(password) + "/" +
            Uri.encode(durationMinutes.toString()) + "/" +
            Uri.encode(formattedStart) + "/" +
            Uri.encode(streamId.toString()) + ".ts"
    }

    fun buildInternalStreamUrl(
        providerId: Long,
        kind: XtreamStreamKind,
        streamId: Long,
        containerExtension: String? = null
    ): String {
        return Uri.Builder()
            .scheme(INTERNAL_SCHEME)
            .authority(providerId.toString())
            .appendPath(kind.pathSegment)
            .appendPath(streamId.toString())
            .apply {
                containerExtension?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("ext", it) }
            }
            .build()
            .toString()
    }

    fun parseInternalStreamUrl(url: String?): XtreamStreamToken? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals(INTERNAL_SCHEME, ignoreCase = true)) return null
        val providerId = uri.authority?.toLongOrNull() ?: return null
        val kind = uri.pathSegments.getOrNull(0)?.let(::kindFromPathSegment) ?: return null
        val streamId = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        return XtreamStreamToken(providerId, kind, streamId, uri.getQueryParameter("ext"))
    }

    fun kindForContentType(contentType: ContentType): XtreamStreamKind? = when (contentType) {
        ContentType.LIVE -> XtreamStreamKind.LIVE
        ContentType.MOVIE -> XtreamStreamKind.MOVIE
        ContentType.SERIES_EPISODE -> XtreamStreamKind.SERIES
        ContentType.SERIES -> null
    }

    fun sanitizePersistedStreamUrl(url: String, providerId: Long): String {
        val parsed = parseCredentialedStreamUrl(url, providerId) ?: return url
        return buildInternalStreamUrl(parsed.providerId, parsed.kind, parsed.streamId, parsed.containerExtension)
    }

    fun isInternalStreamUrl(url: String?): Boolean = parseInternalStreamUrl(url) != null

    fun sanitizeLogMessage(message: String): String {
        val queryRedacted = queryCredentialRegex.replace(message) { match ->
            match.groupValues[1] + "<redacted>"
        }
        val pathRedacted = liveMovieSeriesPathRegex.replace(queryRedacted) { match ->
            val prefix = match.groupValues[1]
            val type = match.groupValues[2]
            val suffix = match.groupValues[3]
            "$prefix/$type/<redacted>/<redacted>$suffix"
        }
        return timeshiftPathRegex.replace(pathRedacted) { match ->
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[2]
            "$prefix/timeshift/<redacted>/<redacted>$suffix"
        }
    }

    private fun parseCredentialedStreamUrl(url: String, providerId: Long): XtreamStreamToken? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val kind = uri.pathSegments.getOrNull(0)?.let(::kindFromPathSegment) ?: return null
        if (uri.pathSegments.size < 4) return null
        val fileSegment = uri.pathSegments[3]
        val dotIndex = fileSegment.lastIndexOf('.')
        val streamId = fileSegment.substring(0, dotIndex.takeIf { it > 0 } ?: fileSegment.length).toLongOrNull() ?: return null
        val ext = if (dotIndex > 0 && dotIndex < fileSegment.lastIndex) fileSegment.substring(dotIndex + 1) else null
        return XtreamStreamToken(providerId, kind, streamId, ext)
    }

    private fun baseBuilder(serverUrl: String): Uri.Builder {
        val normalized = serverUrl.trimEnd('/') + "/"
        return Uri.parse(normalized).buildUpon().path("")
    }

    private fun kindFromPathSegment(segment: String): XtreamStreamKind? = when (segment.lowercase()) {
        XtreamStreamKind.LIVE.pathSegment -> XtreamStreamKind.LIVE
        XtreamStreamKind.MOVIE.pathSegment -> XtreamStreamKind.MOVIE
        XtreamStreamKind.SERIES.pathSegment -> XtreamStreamKind.SERIES
        else -> null
    }
}