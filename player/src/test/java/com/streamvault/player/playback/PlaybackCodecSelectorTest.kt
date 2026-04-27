package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@UnstableApi
class PlaybackCodecSelectorTest {

    @Test
    fun `software codec names are recognized`() {
        assertThat(PlaybackCodecSelector.isSoftwareCodec("OMX.google.h264.decoder")).isTrue()
        assertThat(PlaybackCodecSelector.isSoftwareCodec("c2.android.hevc.decoder")).isTrue()
        assertThat(PlaybackCodecSelector.isSoftwareCodec("OMX.ffmpeg.avc.decoder")).isTrue()
    }

    @Test
    fun `vendor codec names are not software`() {
        assertThat(PlaybackCodecSelector.isSoftwareCodec("OMX.qcom.video.decoder.avc")).isFalse()
        assertThat(PlaybackCodecSelector.isSoftwareCodec("c2.mtk.hevc.decoder")).isFalse()
        assertThat(PlaybackCodecSelector.isSoftwareCodec("OMX.amlogic.avc.decoder.awesome")).isFalse()
    }

    @Test
    fun `known bad records extract decoder names`() {
        val records = listOf(
            compatibilityRecord("OMX.bad.decoder", failures = 2, successAt = 0, failedAt = 10),
            compatibilityRecord("OMX.good.decoder", failures = 1, successAt = 0, failedAt = 10),
            compatibilityRecord("OMX.recovered.decoder", failures = 4, successAt = 20, failedAt = 10)
        )

        assertThat(PlaybackCodecSelector.knownBadDecoderNames(records))
            .containsExactly("OMX.bad.decoder")
    }

    private fun compatibilityRecord(
        decoderName: String,
        failures: Int,
        successAt: Long,
        failedAt: Long
    ) = com.streamvault.domain.model.PlaybackCompatibilityRecord(
        key = com.streamvault.domain.model.PlaybackCompatibilityKey(
            deviceFingerprint = "device",
            deviceModel = "model",
            androidSdk = 35,
            streamType = "HLS",
            videoMimeType = "video/avc",
            resolutionBucket = "1080P",
            decoderName = decoderName,
            surfaceType = "SURFACE_VIEW"
        ),
        failureType = "VIDEO_STALL",
        lastFailedAt = failedAt,
        lastSucceededAt = successAt,
        failureCount = failures,
        successCount = if (successAt > 0) 1 else 0
    )
}
