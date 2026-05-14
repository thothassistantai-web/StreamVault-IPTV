package com.streamvault.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifestParser
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.extractor.mp4.PsshAtomUtil
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

@UnstableApi
internal class ClearKeySmoothStreamingManifestParser(
    private val delegate: SsManifestParser = SsManifestParser()
) : ParsingLoadable.Parser<SsManifest> {

    override fun parse(uri: Uri, inputStream: InputStream): SsManifest {
        val manifest = delegate.parse(uri, inputStream)
        val protection = manifest.protectionElement ?: return manifest
        val keyIds = protection.trackEncryptionBoxes
            .mapNotNull { box -> box.cryptoData.encryptionKey?.takeIf { it.size == KEY_ID_SIZE } }
            .distinctBy { it.contentToString() }
            .map { it.toUuid() }

        if (keyIds.isEmpty()) return manifest

        // Android ClearKey expects CENC init data as PSSH v1 with KID entries.
        val clearKeyPssh = PsshAtomUtil.buildPsshAtom(C.CLEARKEY_UUID, keyIds.toTypedArray(), null)
        val patchedProtection = SsManifest.ProtectionElement(
            C.CLEARKEY_UUID,
            clearKeyPssh,
            protection.trackEncryptionBoxes
        )
        val drmInitData = DrmInitData(
            DrmInitData.SchemeData(C.CLEARKEY_UUID, MimeTypes.VIDEO_MP4, clearKeyPssh)
        )
        val patchedElements = manifest.streamElements.map { element ->
            if (element.type == C.TRACK_TYPE_AUDIO || element.type == C.TRACK_TYPE_VIDEO) {
                element.copy(
                    element.formats.map { format ->
                        format.buildUpon()
                            .setDrmInitData(drmInitData)
                            .build()
                    }.toTypedArray()
                )
            } else {
                element
            }
        }.toTypedArray()

        return SsManifest(
            manifest.majorVersion,
            manifest.minorVersion,
            MANIFEST_TIME_SCALE,
            manifest.durationUs.toManifestDuration(),
            manifest.dvrWindowLengthUs.toManifestDuration(),
            manifest.lookAheadCount,
            manifest.isLive,
            patchedProtection,
            patchedElements
        )
    }

    private fun Long.toManifestDuration(): Long =
        if (this == C.TIME_UNSET) 0L else this

    private fun ByteArray.toUuid(): UUID {
        val buffer = ByteBuffer.wrap(this)
        return UUID(buffer.long, buffer.long)
    }

    private companion object {
        private const val KEY_ID_SIZE = 16
        private const val MANIFEST_TIME_SCALE = 1_000_000L
    }
}
