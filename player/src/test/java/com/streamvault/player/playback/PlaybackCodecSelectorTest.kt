package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DecoderMode
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

    @Test
    fun `managed codec selector is reserved for explicit decoder modes`() {
        assertThat(shouldUseManagedCodecSelector(DecoderMode.AUTO, ActiveDecoderPolicy.AUTO)).isFalse()
        assertThat(shouldUseManagedCodecSelector(DecoderMode.AUTO, ActiveDecoderPolicy.SOFTWARE_PREFERRED)).isFalse()
        assertThat(shouldUseManagedCodecSelector(DecoderMode.HARDWARE, ActiveDecoderPolicy.HARDWARE_PREFERRED)).isTrue()
        assertThat(shouldUseManagedCodecSelector(DecoderMode.SOFTWARE, ActiveDecoderPolicy.SOFTWARE_PREFERRED)).isTrue()
        assertThat(shouldUseManagedCodecSelector(DecoderMode.COMPATIBILITY, ActiveDecoderPolicy.COMPATIBILITY)).isTrue()
    }

    @Test
    fun `auto default with av sync off uses stock media3 render path`() {
        val plan = buildPlaybackRendererPlan(
            requestedMode = DecoderMode.AUTO,
            decoderPolicy = ActiveDecoderPolicy.AUTO,
            useAudioVideoSyncSink = false,
            useVideoRendererWorkaround = false
        )

        assertThat(plan.renderPath).isEqualTo("stock-media3")
        assertThat(plan.useStockRenderersFactory).isTrue()
        assertThat(plan.useAudioVideoSyncSink).isFalse()
        assertThat(plan.useManagedCodecSelector).isFalse()
    }

    @Test
    fun `auto ignores decoder reuse workaround to keep default startup stock`() {
        val plan = buildPlaybackRendererPlan(
            requestedMode = DecoderMode.AUTO,
            decoderPolicy = ActiveDecoderPolicy.AUTO,
            useAudioVideoSyncSink = false,
            useVideoRendererWorkaround = true
        )

        assertThat(plan.renderPath).isEqualTo("stock-media3")
        assertThat(plan.useStockRenderersFactory).isTrue()
        assertThat(plan.useAudioVideoSyncSink).isFalse()
        assertThat(plan.useVideoRendererWorkaround).isFalse()
        assertThat(plan.useManagedCodecSelector).isFalse()
    }

    @Test
    fun `auto with av sync on only requests audio video offset sink`() {
        val plan = buildPlaybackRendererPlan(
            requestedMode = DecoderMode.AUTO,
            decoderPolicy = ActiveDecoderPolicy.AUTO,
            useAudioVideoSyncSink = true,
            useVideoRendererWorkaround = true
        )

        assertThat(plan.renderPath).isEqualTo("av-sync-sink")
        assertThat(plan.useStockRenderersFactory).isFalse()
        assertThat(plan.useAudioVideoSyncSink).isTrue()
        assertThat(plan.useVideoRendererWorkaround).isFalse()
        assertThat(plan.useManagedCodecSelector).isFalse()
    }

    @Test
    fun `explicit hardware uses managed codec selector`() {
        val plan = buildPlaybackRendererPlan(
            requestedMode = DecoderMode.HARDWARE,
            decoderPolicy = ActiveDecoderPolicy.HARDWARE_PREFERRED,
            useAudioVideoSyncSink = false,
            useVideoRendererWorkaround = false
        )

        assertThat(plan.useManagedCodecSelector).isTrue()
        assertThat(plan.renderPath).isEqualTo("managed-codec-selector")
    }

    @Test
    fun `explicit software uses managed codec selector`() {
        val plan = buildPlaybackRendererPlan(
            requestedMode = DecoderMode.SOFTWARE,
            decoderPolicy = ActiveDecoderPolicy.SOFTWARE_PREFERRED,
            useAudioVideoSyncSink = false,
            useVideoRendererWorkaround = false
        )

        assertThat(plan.useManagedCodecSelector).isTrue()
        assertThat(plan.renderPath).isEqualTo("managed-codec-selector")
    }

    @Test
    fun `explicit compatibility can combine managed selector and renderer workaround`() {
        val plan = buildPlaybackRendererPlan(
            requestedMode = DecoderMode.COMPATIBILITY,
            decoderPolicy = ActiveDecoderPolicy.COMPATIBILITY,
            useAudioVideoSyncSink = false,
            useVideoRendererWorkaround = true
        )

        assertThat(plan.useManagedCodecSelector).isTrue()
        assertThat(plan.useVideoRendererWorkaround).isTrue()
        assertThat(plan.renderPath).isEqualTo("decoder-reuse-workaround+managed-codec-selector")
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
