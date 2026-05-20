package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackBufferPoliciesTest {

    @Test
    fun `normal live uses production live buffer baseline`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = true, compatibilityMode = false)

        assertThat(policy.label).isEqualTo("stable-live")
        assertThat(policy.minBufferMs).isEqualTo(8_000)
        assertThat(policy.maxBufferMs).isEqualTo(30_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isTrue()
    }

    @Test
    fun `compatibility live uses larger live buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = true, compatibilityMode = true)

        assertThat(policy.label).isEqualTo("compat-live")
        assertThat(policy.minBufferMs).isEqualTo(15_000)
        assertThat(policy.maxBufferMs).isEqualTo(45_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isTrue()
    }

    @Test
    fun `mpeg ts live prioritizes playable time over byte target`() {
        val policy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
            compatibilityMode = false
        )

        assertThat(policy.label).isEqualTo("mpeg-ts-live")
        assertThat(policy.minBufferMs).isEqualTo(5_000)
        assertThat(policy.maxBufferMs).isEqualTo(10_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
        assertThat(policy.targetBufferBytes).isEqualTo(16 * 1024 * 1024)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isTrue()
    }

    @Test
    fun `vod uses deeper movie buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = false, compatibilityMode = false)

        assertThat(policy.label).isEqualTo("stable-vod")
        assertThat(policy.minBufferMs).isEqualTo(90_000)
        assertThat(policy.maxBufferMs).isEqualTo(240_000)
        assertThat(policy.playbackBufferMs).isEqualTo(8_000)
        assertThat(policy.rebufferMs).isEqualTo(18_000)
        assertThat(policy.targetBufferBytes).isEqualTo(-1)
        assertThat(policy.prioritizeTimeOverSizeThresholds).isTrue()
    }
}
