package com.streamvault.player.playback

internal data class PlaybackBufferPolicy(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
    val targetBufferBytes: Int,
    val prioritizeTimeOverSizeThresholds: Boolean
)

internal object PlaybackBufferPolicies {
    private const val DEFAULT_TARGET_BUFFER_BYTES = -1
    private const val MPEG_TS_LIVE_TARGET_BUFFER_BYTES = 16 * 1024 * 1024
    private const val MPEG_TS_LIVE_MIN_BUFFER_MS = 5_000
    private const val MPEG_TS_LIVE_MAX_BUFFER_MS = 10_000
    private const val LIVE_MIN_BUFFER_MS = 8_000
    private const val LIVE_MAX_BUFFER_MS = 30_000
    private const val COMPAT_LIVE_MIN_BUFFER_MS = 15_000
    private const val COMPAT_LIVE_MAX_BUFFER_MS = 45_000
    private const val VOD_MIN_BUFFER_MS = 90_000
    private const val VOD_MAX_BUFFER_MS = 240_000
    private const val PLAYBACK_BUFFER_MS = 1_500
    private const val REBUFFER_MS = 5_000
    private const val VOD_PLAYBACK_BUFFER_MS = 8_000
    private const val VOD_REBUFFER_MS = 18_000

    fun forPlayback(resolvedStreamType: ResolvedStreamType, compatibilityMode: Boolean): PlaybackBufferPolicy = when {
        resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE ->
            PlaybackBufferPolicy(
                "mpeg-ts-live",
                MPEG_TS_LIVE_MIN_BUFFER_MS,
                MPEG_TS_LIVE_MAX_BUFFER_MS,
                PLAYBACK_BUFFER_MS,
                REBUFFER_MS,
                MPEG_TS_LIVE_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        compatibilityMode && resolvedStreamType.isLive ->
            PlaybackBufferPolicy(
                "compat-live",
                COMPAT_LIVE_MIN_BUFFER_MS,
                COMPAT_LIVE_MAX_BUFFER_MS,
                PLAYBACK_BUFFER_MS,
                REBUFFER_MS,
                DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        resolvedStreamType.isLive ->
            PlaybackBufferPolicy(
                "stable-live",
                LIVE_MIN_BUFFER_MS,
                LIVE_MAX_BUFFER_MS,
                PLAYBACK_BUFFER_MS,
                REBUFFER_MS,
                DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
        else ->
            PlaybackBufferPolicy(
                "stable-vod",
                VOD_MIN_BUFFER_MS,
                VOD_MAX_BUFFER_MS,
                VOD_PLAYBACK_BUFFER_MS,
                VOD_REBUFFER_MS,
                DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = true
            )
    }

    fun forPlayback(isLive: Boolean, compatibilityMode: Boolean): PlaybackBufferPolicy =
        forPlayback(
            resolvedStreamType = if (isLive) ResolvedStreamType.HLS else ResolvedStreamType.PROGRESSIVE,
            compatibilityMode = compatibilityMode
        )

    private val ResolvedStreamType.isLive: Boolean
        get() = this == ResolvedStreamType.HLS ||
            this == ResolvedStreamType.SMOOTH_STREAMING ||
            this == ResolvedStreamType.MPEG_TS_LIVE ||
            this == ResolvedStreamType.RTSP
}
