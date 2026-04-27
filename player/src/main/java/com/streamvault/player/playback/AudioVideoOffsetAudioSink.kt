package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink

@UnstableApi
internal class AudioVideoOffsetAudioSink(
    private val delegate: AudioSink,
    private val offsetUsProvider: () -> Long
) : AudioSink by delegate {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val positionUs = delegate.getCurrentPositionUs(sourceEnded)
        return if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) {
            positionUs
        } else {
            positionUs + offsetUsProvider()
        }
    }
}

