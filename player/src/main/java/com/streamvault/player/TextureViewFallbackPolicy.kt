package com.streamvault.player

import com.streamvault.domain.model.PlayerSurfaceMode

private const val UNKNOWN_DECODER_NAME = "Unknown"

internal fun shouldFallbackTextureViewWithoutFirstFrame(
    renderSurfaceType: PlayerRenderSurfaceType,
    sessionSurfaceModeOverride: PlayerSurfaceMode?,
    fallbackAttempted: Boolean,
    hasStreamInfo: Boolean,
    hasRenderedFirstVideoFrame: Boolean,
    isCurrentStreamLive: Boolean,
    playbackState: PlaybackState,
    elapsedSincePrepareMs: Long,
    startupTimeoutMs: Long,
    bufferedDurationMs: Long,
    bufferedStartupThresholdMs: Long,
    selectedVideoDecoderName: String
): Boolean {
    if (renderSurfaceType != PlayerRenderSurfaceType.TEXTURE_VIEW) return false
    if (sessionSurfaceModeOverride == PlayerSurfaceMode.SURFACE_VIEW) return false
    if (fallbackAttempted) return false
    if (!hasStreamInfo) return false
    if (hasRenderedFirstVideoFrame) return false
    if (!isCurrentStreamLive) return false
    if (playbackState != PlaybackState.READY && playbackState != PlaybackState.BUFFERING) return false
    if (elapsedSincePrepareMs < startupTimeoutMs) return false
    if (playbackState == PlaybackState.BUFFERING && bufferedDurationMs < bufferedStartupThresholdMs) {
        return false
    }
    if (selectedVideoDecoderName == UNKNOWN_DECODER_NAME && playbackState != PlaybackState.READY) return false
    return true
}