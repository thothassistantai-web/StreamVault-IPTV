package com.streamvault.player.playback

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import java.net.URI

enum class PlayerTimeoutProfile(
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val writeTimeoutMs: Long
) {
    /** Loopback StepDaddy Gateway proxy on 127.0.0.1:3000 — fail fast on local HTTP. */
    LOOPBACK_LIVE(
        connectTimeoutMs = 3_000L,
        readTimeoutMs = 12_000L,
        writeTimeoutMs = 12_000L
    ),
    LIVE(
        connectTimeoutMs = 12_000L,
        readTimeoutMs = 20_000L,
        writeTimeoutMs = 20_000L
    ),
    VOD(
        connectTimeoutMs = 15_000L,
        readTimeoutMs = 45_000L,
        writeTimeoutMs = 30_000L
    ),
    PROGRESSIVE(
        connectTimeoutMs = 5_000L,
        readTimeoutMs = 10_000L,
        writeTimeoutMs = 30_000L
    ),
    PRELOAD(
        connectTimeoutMs = 10_000L,
        readTimeoutMs = 15_000L,
        writeTimeoutMs = 15_000L
    );

    companion object {
        fun resolve(
            streamInfo: StreamInfo,
            resolvedStreamType: ResolvedStreamType,
            preload: Boolean
        ): PlayerTimeoutProfile {
            if (preload) return PRELOAD
            val liveProfile = if (isLoopbackGatewayUrl(streamInfo.url)) LOOPBACK_LIVE else LIVE
            if (streamInfo.streamType == StreamType.RTSP) return liveProfile
            return when {
                resolvedStreamType == ResolvedStreamType.HLS -> liveProfile
                resolvedStreamType == ResolvedStreamType.SMOOTH_STREAMING -> liveProfile
                resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE -> liveProfile
                resolvedStreamType == ResolvedStreamType.RTSP -> liveProfile
                resolvedStreamType == ResolvedStreamType.PROGRESSIVE -> PROGRESSIVE
                streamInfo.streamType == StreamType.PROGRESSIVE -> PROGRESSIVE
                else -> VOD
            }
        }

        private fun isLoopbackGatewayUrl(url: String): Boolean {
            val normalized = url.substringBefore('|').trim()
            if (normalized.isBlank()) return false
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
            val host = uri.host?.lowercase().orEmpty()
            val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
            if (!loopback) return false
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("http", ignoreCase = true) -> 80
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> -1
            }
            return port == 3000
        }
    }
}
