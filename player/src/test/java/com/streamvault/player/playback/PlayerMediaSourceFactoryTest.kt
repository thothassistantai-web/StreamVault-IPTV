package com.streamvault.player.playback

import androidx.media3.extractor.ts.TsExtractor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerMediaSourceFactoryTest {

    @Test
    fun `live mpeg ts extractor uses hls mode to avoid finite progressive duration`() {
        val factory = liveMpegTsExtractorsFactory()
        val modeField = factory::class.java.getDeclaredField("tsMode").apply {
            isAccessible = true
        }

        assertThat(modeField.getInt(factory)).isEqualTo(TsExtractor.MODE_HLS)
    }
}
