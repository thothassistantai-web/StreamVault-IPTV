package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackCompatibilityRecord
import java.util.Locale

enum class ActiveDecoderPolicy {
    AUTO,
    HARDWARE_PREFERRED,
    SOFTWARE_PREFERRED,
    COMPATIBILITY
}

@UnstableApi
class PlaybackCodecSelector(
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
    private val policyProvider: () -> ActiveDecoderPolicy,
    private val knownBadProvider: () -> Set<String>
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): MutableList<MediaCodecInfo> {
        val infos = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        val knownBad = knownBadProvider().map { it.lowercase(Locale.ROOT) }.toSet()
        val policy = policyProvider()
        return infos.sortedWith(
            compareBy<MediaCodecInfo> { info ->
                if (info.name.lowercase(Locale.ROOT) in knownBad) 1 else 0
            }.thenBy { info ->
                when (policy) {
                    ActiveDecoderPolicy.AUTO -> 0
                    ActiveDecoderPolicy.HARDWARE_PREFERRED -> if (isSoftwareCodec(info.name)) 1 else 0
                    ActiveDecoderPolicy.SOFTWARE_PREFERRED,
                    ActiveDecoderPolicy.COMPATIBILITY -> if (isSoftwareCodec(info.name)) 0 else 1
                }
            }
        ).toMutableList()
    }

    companion object {
        fun isSoftwareCodec(name: String): Boolean {
            val normalized = name.lowercase(Locale.ROOT)
            return normalized.startsWith("omx.google.") ||
                normalized.startsWith("c2.android.") ||
                normalized.contains("ffmpeg") ||
                normalized.contains("avcodec") ||
                normalized.contains("sw")
        }

        fun knownBadDecoderNames(records: List<PlaybackCompatibilityRecord>): Set<String> =
            records.filter(PlaybackCompatibilityRecord::isKnownBad)
                .map { it.key.decoderName }
                .filter { it.isNotBlank() }
                .toSet()
    }
}

