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
    }

    @Test
    fun `compatibility live uses larger live buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = true, compatibilityMode = true)

        assertThat(policy.label).isEqualTo("compat-live")
        assertThat(policy.minBufferMs).isEqualTo(15_000)
        assertThat(policy.maxBufferMs).isEqualTo(45_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
    }

    @Test
    fun `vod uses larger stable buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = false, compatibilityMode = false)

        assertThat(policy.label).isEqualTo("stable-vod")
        assertThat(policy.minBufferMs).isEqualTo(50_000)
        assertThat(policy.maxBufferMs).isEqualTo(120_000)
        assertThat(policy.playbackBufferMs).isEqualTo(1_500)
        assertThat(policy.rebufferMs).isEqualTo(5_000)
    }
}
