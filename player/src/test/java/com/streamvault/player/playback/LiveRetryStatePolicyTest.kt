package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveRetryStatePolicyTest {

    @Test
    fun `live window retry preserves started state after first frame`() {
        assertThat(
            shouldPreservePlaybackStateForRetry(
                category = PlaybackErrorCategory.LIVE_WINDOW,
                playbackStarted = true,
                liveBufferingRecoveryArmed = false
            )
        ).isTrue()
    }

    @Test
    fun `armed live recovery counts as playback started for retry policy`() {
        assertThat(
            hasEffectivePlaybackStarted(
                playbackStarted = false,
                liveBufferingRecoveryArmed = true
            )
        ).isTrue()
    }

    @Test
    fun `live window retry before first frame keeps existing reset behavior`() {
        assertThat(
            shouldPreservePlaybackStateForRetry(
                category = PlaybackErrorCategory.LIVE_WINDOW,
                playbackStarted = false,
                liveBufferingRecoveryArmed = false
            )
        ).isFalse()
    }

    @Test
    fun `non live window retry preserves existing retry state behavior`() {
        assertThat(
            shouldPreservePlaybackStateForRetry(
                category = PlaybackErrorCategory.NETWORK,
                playbackStarted = false,
                liveBufferingRecoveryArmed = false
            )
        ).isTrue()
    }

    @Test
    fun `retry reprepare after first frame carries effective playback started state`() {
        assertThat(
            shouldArmPlaybackStartedRecovery(
                preserveRetryState = true,
                mediaChanged = false,
                playbackStarted = true,
                playbackStartedRecoveryArmed = false
            )
        ).isTrue()
    }

    @Test
    fun `subsequent retry reprepare keeps carried playback started state`() {
        assertThat(
            shouldArmPlaybackStartedRecovery(
                preserveRetryState = true,
                mediaChanged = false,
                playbackStarted = false,
                playbackStartedRecoveryArmed = true
            )
        ).isTrue()
    }

    @Test
    fun `retry reprepare before first frame does not carry playback started state`() {
        assertThat(
            shouldArmPlaybackStartedRecovery(
                preserveRetryState = true,
                mediaChanged = false,
                playbackStarted = false,
                playbackStartedRecoveryArmed = false
            )
        ).isFalse()
    }

    @Test
    fun `retry reprepare for a new media item clears playback started state`() {
        assertThat(
            shouldArmPlaybackStartedRecovery(
                preserveRetryState = true,
                mediaChanged = true,
                playbackStarted = true,
                playbackStartedRecoveryArmed = false
            )
        ).isFalse()
    }
}
