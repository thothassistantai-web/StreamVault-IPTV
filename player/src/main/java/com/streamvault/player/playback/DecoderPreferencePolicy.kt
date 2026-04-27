package com.streamvault.player.playback

import com.streamvault.domain.model.DecoderMode

interface DecoderPreferencePolicy {
    fun preferredMode(requestedMode: DecoderMode, mediaId: String): DecoderMode
    fun onDecoderInitFailure(requestedMode: DecoderMode, mediaId: String): DecoderMode?
    fun resetForMedia(mediaId: String)
}

class DefaultDecoderPreferencePolicy : DecoderPreferencePolicy {
    private val softwareRetriedMediaIds = mutableSetOf<String>()

    override fun preferredMode(requestedMode: DecoderMode, mediaId: String): DecoderMode {
        return when (requestedMode) {
            DecoderMode.SOFTWARE, DecoderMode.COMPATIBILITY -> DecoderMode.SOFTWARE
            DecoderMode.HARDWARE, DecoderMode.AUTO -> DecoderMode.HARDWARE
        }
    }

    override fun onDecoderInitFailure(requestedMode: DecoderMode, mediaId: String): DecoderMode? {
        if (requestedMode == DecoderMode.SOFTWARE || requestedMode == DecoderMode.COMPATIBILITY) return null
        if (!softwareRetriedMediaIds.add(mediaId)) return null
        return DecoderMode.SOFTWARE
    }

    override fun resetForMedia(mediaId: String) {
        softwareRetriedMediaIds.remove(mediaId)
    }
}

