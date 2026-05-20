package com.streamvault.player.playback

import androidx.media3.common.C

internal fun resolveRetrySeekPositionMs(
    category: PlaybackErrorCategory,
    resolvedStreamType: ResolvedStreamType,
    currentPositionMs: Long?,
    durationMs: Long?,
    isCurrentMediaItemLive: Boolean,
    playbackStarted: Boolean
): Long? {
    val positionMs = currentPositionMs?.takeIf { it > 0L } ?: return null
    if (category == PlaybackErrorCategory.LIVE_WINDOW) return null
    if (isCurrentMediaItemLive) return null

    return when (resolvedStreamType) {
        ResolvedStreamType.PROGRESSIVE -> positionMs.takeIf { playbackStarted }
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
    playbackStarted: Boolean
): Int {
    if (currentAttempt <= 0) return currentAttempt
    if (!playbackStarted) return currentAttempt
    return 0
}

internal fun resolveRetryAttemptAfterPlaybackStarted(currentAttempt: Int): Int {
    if (currentAttempt <= 0) return currentAttempt
    return 0
}

internal fun resolveRetryAttemptForCategory(
    currentAttempt: Int,
    lastCategory: PlaybackErrorCategory?,
    nextCategory: PlaybackErrorCategory
): Int {
    if (currentAttempt <= 0) return 1
    if (lastCategory != nextCategory) return 1
    return currentAttempt + 1
}

private fun Long?.isFiniteMediaDuration(): Boolean {
    return this != null && this != C.TIME_UNSET && this > 0L
}
