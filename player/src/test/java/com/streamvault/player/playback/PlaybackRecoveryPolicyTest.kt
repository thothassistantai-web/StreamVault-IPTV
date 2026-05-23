package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRecoveryPolicyTest {

    @Test
    fun `live provider stream after playback start blocks full reprepare`() {
        assertThat(
            shouldAttemptAutomaticRecovery(
                action = AutomaticRecoveryAction.FULL_REPREPARE,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                playbackStarted = true
            )
        ).isFalse()
    }

    @Test
    fun `live provider stream after playback start blocks decoder fallback`() {
        assertThat(
            shouldAttemptAutomaticRecovery(
                action = AutomaticRecoveryAction.DECODER_FALLBACK,
                resolvedStreamType = ResolvedStreamType.HLS,
                playbackStarted = true
            )
        ).isFalse()
    }

    @Test
    fun `startup recovery may still open provider stream`() {
        assertThat(
            shouldAttemptAutomaticRecovery(
                action = AutomaticRecoveryAction.FULL_REPREPARE,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                playbackStarted = false
            )
        ).isTrue()
    }

    @Test
    fun `progressive streams keep automatic recovery after playback start`() {
        assertThat(
            shouldAttemptAutomaticRecovery(
                action = AutomaticRecoveryAction.FULL_REPREPARE,
                resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
                playbackStarted = true
            )
        ).isTrue()
    }

    @Test
    fun `surface fallback remains allowed because it does not force a provider reopen by policy`() {
        assertThat(
            shouldAttemptAutomaticRecovery(
                action = AutomaticRecoveryAction.SURFACE_FALLBACK,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                playbackStarted = true
            )
        ).isTrue()
    }
}
