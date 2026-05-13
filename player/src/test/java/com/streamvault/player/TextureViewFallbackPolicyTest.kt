package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlayerSurfaceMode
import org.junit.Test

class TextureViewFallbackPolicyTest {

    @Test
    fun `ready texture view without first frame still falls back after timeout`() {
        val shouldFallback = shouldFallbackTextureViewWithoutFirstFrame(
            renderSurfaceType = PlayerRenderSurfaceType.TEXTURE_VIEW,
            sessionSurfaceModeOverride = null,
            fallbackAttempted = false,
            hasStreamInfo = true,
            hasRenderedFirstVideoFrame = false,
            isCurrentStreamLive = true,
            playbackState = PlaybackState.READY,
            elapsedSincePrepareMs = 9_500L,
            startupTimeoutMs = 9_000L,
            bufferedDurationMs = 0L,
            bufferedStartupThresholdMs = 4_000L,
            selectedVideoDecoderName = "OMX.amlogic.mpeg2.decoder"
        )

        assertThat(shouldFallback).isTrue()
    }

    @Test
    fun `texture view does not fall back after first frame renders`() {
        val shouldFallback = shouldFallbackTextureViewWithoutFirstFrame(
            renderSurfaceType = PlayerRenderSurfaceType.TEXTURE_VIEW,
            sessionSurfaceModeOverride = null,
            fallbackAttempted = false,
            hasStreamInfo = true,
            hasRenderedFirstVideoFrame = true,
            isCurrentStreamLive = true,
            playbackState = PlaybackState.READY,
            elapsedSincePrepareMs = 9_500L,
            startupTimeoutMs = 9_000L,
            bufferedDurationMs = 0L,
            bufferedStartupThresholdMs = 4_000L,
            selectedVideoDecoderName = "OMX.amlogic.mpeg2.decoder"
        )

        assertThat(shouldFallback).isFalse()
    }

    @Test
    fun `texture view buffering waits for enough buffered data`() {
        val shouldFallback = shouldFallbackTextureViewWithoutFirstFrame(
            renderSurfaceType = PlayerRenderSurfaceType.TEXTURE_VIEW,
            sessionSurfaceModeOverride = null,
            fallbackAttempted = false,
            hasStreamInfo = true,
            hasRenderedFirstVideoFrame = false,
            isCurrentStreamLive = true,
            playbackState = PlaybackState.BUFFERING,
            elapsedSincePrepareMs = 9_500L,
            startupTimeoutMs = 9_000L,
            bufferedDurationMs = 1_500L,
            bufferedStartupThresholdMs = 4_000L,
            selectedVideoDecoderName = "OMX.amlogic.mpeg2.decoder"
        )

        assertThat(shouldFallback).isFalse()
    }

    @Test
    fun `surface view override suppresses texture view fallback`() {
        val shouldFallback = shouldFallbackTextureViewWithoutFirstFrame(
            renderSurfaceType = PlayerRenderSurfaceType.TEXTURE_VIEW,
            sessionSurfaceModeOverride = PlayerSurfaceMode.SURFACE_VIEW,
            fallbackAttempted = false,
            hasStreamInfo = true,
            hasRenderedFirstVideoFrame = false,
            isCurrentStreamLive = true,
            playbackState = PlaybackState.READY,
            elapsedSincePrepareMs = 9_500L,
            startupTimeoutMs = 9_000L,
            bufferedDurationMs = 0L,
            bufferedStartupThresholdMs = 4_000L,
            selectedVideoDecoderName = "OMX.amlogic.mpeg2.decoder"
        )

        assertThat(shouldFallback).isFalse()
    }
}