package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import org.junit.Test

@UnstableApi
class AudioVideoOffsetAudioSinkTest {

    @Test
    fun `positive offset reports clock ahead`() {
        val sink = AudioVideoOffsetAudioSink(
            delegate = fakeAudioSink(positionUs = 1_000_000L),
            offsetUsProvider = { 250_000L }
        )

        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(1_250_000L)
    }

    @Test
    fun `negative offset reports clock behind`() {
        val sink = AudioVideoOffsetAudioSink(
            delegate = fakeAudioSink(positionUs = 1_000_000L),
            offsetUsProvider = { -250_000L }
        )

        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(750_000L)
    }

    @Test
    fun `current position not set is preserved`() {
        val sink = AudioVideoOffsetAudioSink(
            delegate = fakeAudioSink(positionUs = AudioSink.CURRENT_POSITION_NOT_SET),
            offsetUsProvider = { 250_000L }
        )

        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET)
    }

    private fun fakeAudioSink(positionUs: Long): AudioSink {
        return Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentPositionUs" -> positionUs
                "toString" -> "FakeAudioSink"
                else -> defaultValue(method.returnType)
            }
        } as AudioSink
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Void.TYPE -> Unit
        else -> null
    }
}

