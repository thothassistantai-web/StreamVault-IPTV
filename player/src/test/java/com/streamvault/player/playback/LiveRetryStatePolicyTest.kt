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
}
