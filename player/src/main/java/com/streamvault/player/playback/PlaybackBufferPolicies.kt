package com.streamvault.player.playback

internal data class PlaybackBufferPolicy(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int
)

internal object PlaybackBufferPolicies {
    private const val LIVE_MIN_BUFFER_MS = 8_000
    private const val LIVE_MAX_BUFFER_MS = 30_000
    private const val COMPAT_LIVE_MIN_BUFFER_MS = 15_000
    private const val COMPAT_LIVE_MAX_BUFFER_MS = 45_000
    private const val VOD_MIN_BUFFER_MS = 50_000
    private const val VOD_MAX_BUFFER_MS = 120_000
    private const val PLAYBACK_BUFFER_MS = 1_500
    private const val REBUFFER_MS = 5_000

    fun forPlayback(isLive: Boolean, compatibilityMode: Boolean): PlaybackBufferPolicy = when {
        compatibilityMode && isLive ->
            PlaybackBufferPolicy("compat-live", COMPAT_LIVE_MIN_BUFFER_MS, COMPAT_LIVE_MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
        isLive ->
            PlaybackBufferPolicy("stable-live", LIVE_MIN_BUFFER_MS, LIVE_MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
        else ->
            PlaybackBufferPolicy("stable-vod", VOD_MIN_BUFFER_MS, VOD_MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
    }
}
