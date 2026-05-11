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

internal fun shouldUseManagedCodecSelector(
    requestedMode: DecoderMode,
    decoderPolicy: ActiveDecoderPolicy
): Boolean = requestedMode != DecoderMode.AUTO && decoderPolicy != ActiveDecoderPolicy.AUTO

internal data class PlaybackRendererPlan(
    val useAudioVideoSyncSink: Boolean,
    val useVideoRendererWorkaround: Boolean,
    val useManagedCodecSelector: Boolean,
    val renderPath: String
) {
    val useStockRenderersFactory: Boolean
        get() = !useAudioVideoSyncSink && !useVideoRendererWorkaround
}

internal fun buildPlaybackRendererPlan(
    requestedMode: DecoderMode,
    decoderPolicy: ActiveDecoderPolicy,
    useAudioVideoSyncSink: Boolean,
    useVideoRendererWorkaround: Boolean
): PlaybackRendererPlan {
    val useManagedCodecSelector = shouldUseManagedCodecSelector(requestedMode, decoderPolicy)
    val useEffectiveVideoRendererWorkaround = useVideoRendererWorkaround && requestedMode != DecoderMode.AUTO
    val renderPath = buildList {
        if (useAudioVideoSyncSink) add("av-sync-sink")
        if (useEffectiveVideoRendererWorkaround) add("decoder-reuse-workaround")
        if (useManagedCodecSelector) add("managed-codec-selector")
    }.ifEmpty { listOf("stock-media3") }.joinToString("+")
    return PlaybackRendererPlan(
        useAudioVideoSyncSink = useAudioVideoSyncSink,
        useVideoRendererWorkaround = useEffectiveVideoRendererWorkaround,
        useManagedCodecSelector = useManagedCodecSelector,
        renderPath = renderPath
    )
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
                normalized.contains("avcodec")
        }

        fun knownBadDecoderNames(records: List<PlaybackCompatibilityRecord>): Set<String> =
            records.filter(PlaybackCompatibilityRecord::isKnownBad)
                .map { it.key.decoderName }
                .filter { it.isNotBlank() }
                .toSet()
    }
}

