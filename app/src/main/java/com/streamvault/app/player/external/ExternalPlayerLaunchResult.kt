package com.streamvault.app.player.external

/**
 * Infer MIME type from a URL string without Android dependencies.
 *
 * - `.m3u8` URLs -> `application/x-mpegURL`
 * - `.ts` URLs   -> `video/mp2t`
 * - Everything else -> the generic video wildcard MIME type
 *
 * Query strings and fragments are stripped before extension detection.
 */
fun inferExternalPlayerMimeType(url: String): String {
    val normalized = url.trim()
    val path = normalized.substringBefore("?").substringBefore("#")
    val lower = path.lowercase()
    return when {
        lower.endsWith(".m3u8") -> "application/x-mpegURL"
        lower.endsWith(".ts")   -> "video/mp2t"
        else                    -> "video/*"
    }
}

/**
 * Result model for external player launch attempts.
 *
 * Each outcome represents a distinct launch state:
 * - Success   - the external player was launched successfully.
 * - InvalidUrl - the URL could not be parsed or is malformed.
 * - NoHandler - no external player handler was available for the URL.
 * - Failed    - the launch was attempted but failed (e.g. player crashed).
 */
sealed interface ExternalPlayerLaunchResult {

    /** Launch succeeded. */
    data class Success(
        val url: String,
        val mimeType: String,
    ) : ExternalPlayerLaunchResult

    /** The URL was invalid or unparseable. */
    data class InvalidUrl(
        val rawUrl: String,
        val reason: String,
    ) : ExternalPlayerLaunchResult

    /** No handler (external player) was available for the URL. */
    data class NoHandler(
        val url: String,
    ) : ExternalPlayerLaunchResult

    /** Launch was attempted but failed (e.g. target player crashed). */
    data class Failed(
        val url: String,
        val errorMessage: String,
    ) : ExternalPlayerLaunchResult

    companion object {
        /** Convenience factory that picks the outcome based on URL analysis. */
        fun fromUrl(url: String): ExternalPlayerLaunchResult {
            val trimmed = url.trim()
            if (trimmed.isBlank()) {
                return InvalidUrl(url, "URL is blank after trimming")
            }
            val mimeType = inferExternalPlayerMimeType(trimmed)
            // Stub: in a real implementation this would check handler availability.
            return Success(trimmed, mimeType)
        }
    }
}
