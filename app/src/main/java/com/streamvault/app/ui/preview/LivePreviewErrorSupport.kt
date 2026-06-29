package com.streamvault.app.ui.preview

import com.streamvault.player.PlayerError

data class LivePreviewErrorDisplay(
    val code: String,
    val message: String,
)

fun resolveLivePreviewErrorFromPlayerError(
    error: PlayerError,
    fallbackMessage: String,
): LivePreviewErrorDisplay {
    val code = when (error) {
        is PlayerError.NetworkError -> "NETWORK"
        is PlayerError.SourceError -> "SOURCE"
        is PlayerError.DecoderError -> "DECODER"
        is PlayerError.DrmError -> "DRM"
        is PlayerError.UnknownError -> "UNKNOWN"
    }
    return LivePreviewErrorDisplay(
        code = code,
        message = error.message.ifBlank { fallbackMessage },
    )
}

fun resolveLivePreviewErrorFromMessage(
    message: String,
    fallbackMessage: String,
): LivePreviewErrorDisplay = LivePreviewErrorDisplay(
    code = "STREAM",
    message = message.ifBlank { fallbackMessage },
)
