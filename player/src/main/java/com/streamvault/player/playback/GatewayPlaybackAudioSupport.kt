package com.streamvault.player.playback

import com.streamvault.domain.model.GatewayPlaybackAudio
import com.streamvault.player.PlayerEngine

fun PlayerEngine.applyGatewayPlaybackAudio(audio: GatewayPlaybackAudio?) {
    setGatewayGainLinear(
        audio?.let(GatewayPlaybackAudio::linearVolume) ?: GatewayPlaybackAudio.DEFAULT_LINEAR_VOLUME
    )
}
