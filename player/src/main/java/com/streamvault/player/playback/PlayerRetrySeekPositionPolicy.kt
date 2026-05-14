package com.streamvault.player.playback

import androidx.media3.common.C

internal fun resolveRetrySeekPositionMs(
    category: PlaybackErrorCategory,
    resolvedStreamType: ResolvedStreamType,
    currentPositionMs: Long?,
    durationMs: Long?,
    isCurrentMediaItemLive: Boolean
): Long? {
    val positionMs = currentPositionMs?.takeIf { it > 0L } ?: return null
    if (category == PlaybackErrorCategory.LIVE_WINDOW) return null
    if (isCurrentMediaItemLive) return null

    return when (resolvedStreamType) {
        ResolvedStreamType.PROGRESSIVE -> positionMs
        ResolvedStreamType.HLS,
        ResolvedStreamType.DASH,
        ResolvedStreamType.SMOOTH_STREAMING,
        ResolvedStreamType.UNKNOWN -> positionMs.takeIf { durationMs.isFiniteMediaDuration() }
        ResolvedStreamType.MPEG_TS_LIVE,
        ResolvedStreamType.RTSP -> null
    }
}

internal fun resolveRetryAttemptAfterReady(
    currentAttempt: Int,
    playbackStarted: Boolean,
    isCurrentMediaItemLive: Boolean
): Int {
    if (currentAttempt <= 0) return currentAttempt
    if (!playbackStarted) return currentAttempt
    if (isCurrentMediaItemLive) return currentAttempt
    return 0
}

private fun Long?.isFiniteMediaDuration(): Boolean {
    return this != null && this != C.TIME_UNSET && this > 0L
}
