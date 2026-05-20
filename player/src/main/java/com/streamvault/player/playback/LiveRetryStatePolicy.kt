package com.streamvault.player.playback

internal fun shouldPreservePlaybackStateForRetry(
    category: PlaybackErrorCategory,
    playbackStarted: Boolean,
    liveBufferingRecoveryArmed: Boolean
): Boolean =
    category != PlaybackErrorCategory.LIVE_WINDOW ||
        hasEffectivePlaybackStarted(
            playbackStarted = playbackStarted,
            liveBufferingRecoveryArmed = liveBufferingRecoveryArmed
        )

internal fun hasEffectivePlaybackStarted(
    playbackStarted: Boolean,
    liveBufferingRecoveryArmed: Boolean
): Boolean = playbackStarted || liveBufferingRecoveryArmed
