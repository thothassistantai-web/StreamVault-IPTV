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

internal fun shouldArmPlaybackStartedRecovery(
    preserveRetryState: Boolean,
    mediaChanged: Boolean,
    playbackStarted: Boolean,
    playbackStartedRecoveryArmed: Boolean
): Boolean =
    preserveRetryState &&
        !mediaChanged &&
        hasEffectivePlaybackStarted(
            playbackStarted = playbackStarted,
            liveBufferingRecoveryArmed = playbackStartedRecoveryArmed
        )
