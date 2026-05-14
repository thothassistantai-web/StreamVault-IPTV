package com.streamvault.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

internal data class ExternalSubtitlePlaybackTransition(
    val resumePositionMs: Long,
    val playWhenReady: Boolean
)

internal fun buildExternalSubtitlePlaybackTransition(
    currentPositionMs: Long,
    playWhenReady: Boolean
): ExternalSubtitlePlaybackTransition = ExternalSubtitlePlaybackTransition(
    resumePositionMs = currentPositionMs.coerceAtLeast(0L),
    playWhenReady = playWhenReady
)

internal fun TrackSelectionParameters.withExternalSubtitleEnabled(): TrackSelectionParameters =
    buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()
