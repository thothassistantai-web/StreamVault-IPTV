package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GatewayPlaybackAudioTest {

    @Test
    fun linearVolume_mapsGainDbToExoPlayerVolume() {
        assertThat(GatewayPlaybackAudio.linearVolume(GatewayPlaybackAudio(amplificationGainDb = 0f)))
            .isWithin(0.001f)
            .of(1f)
        assertThat(GatewayPlaybackAudio.linearVolume(GatewayPlaybackAudio(amplificationGainDb = 6f)))
            .isWithin(0.05f)
            .of(2f)
        assertThat(GatewayPlaybackAudio.linearVolume(GatewayPlaybackAudio(amplificationGainDb = -12f)))
            .isWithin(0.05f)
            .of(0.25f)
    }

    @Test
    fun clampGainDb_limitsOutOfRangeValues() {
        assertThat(GatewayPlaybackAudio.clampGainDb(20f)).isEqualTo(12f)
        assertThat(GatewayPlaybackAudio.clampGainDb(-20f)).isEqualTo(-12f)
    }
}
