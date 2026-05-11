package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.PlaybackCompatibilityDao
import com.streamvault.data.local.entity.PlaybackCompatibilityRecordEntity
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlaybackCompatibilityRepositoryImplTest {
    private val dao: PlaybackCompatibilityDao = mock()
    private val repository: PlaybackCompatibilityRepository = PlaybackCompatibilityRepositoryImpl(dao)

    @Test
    fun `getKnownBadRecords maps dao rows and keeps only known bad entries`() = runTest {
        whenever(
            dao.getKnownBadCandidates(
                deviceFingerprint = DEVICE_FINGERPRINT,
                streamType = STREAM_TYPE,
                videoMimeType = VIDEO_MIME_TYPE,
                resolutionBucket = RESOLUTION_BUCKET
            )
        ).thenReturn(
            listOf(
                entity(decoderName = "OMX.bad.decoder", failureCount = 2, lastFailedAt = 20L, lastSucceededAt = 0L),
                entity(decoderName = "OMX.low.confidence", failureCount = 1, lastFailedAt = 20L, lastSucceededAt = 0L),
                entity(decoderName = "OMX.recovered.decoder", failureCount = 4, lastFailedAt = 20L, lastSucceededAt = 30L)
            )
        )

        val records = repository.getKnownBadRecords(
            deviceFingerprint = DEVICE_FINGERPRINT,
            streamType = STREAM_TYPE,
            videoMimeType = VIDEO_MIME_TYPE,
            resolutionBucket = RESOLUTION_BUCKET
        )

        assertThat(records).hasSize(1)
        val record = records.single()
        assertThat(record.key.decoderName).isEqualTo("OMX.bad.decoder")
        assertThat(record.key.surfaceType).isEqualTo(SURFACE_TYPE)
        assertThat(record.failureCount).isEqualTo(2)
        assertThat(record.lastFailedAt).isEqualTo(20L)
        assertThat(record.lastSucceededAt).isEqualTo(0L)
    }

    @Test
    fun `recordFailure delegates to dao and prunes with defaults`() = runTest {
        whenever(dao.deleteOlderThan(any())).thenReturn(0)
        whenever(dao.keepMostRecent(any())).thenReturn(0)

        repository.recordFailure(KEY, failureType = FAILURE_TYPE, at = 123L)

        verify(dao).recordFailure(
            deviceFingerprint = KEY.deviceFingerprint,
            deviceModel = KEY.deviceModel,
            androidSdk = KEY.androidSdk,
            streamType = KEY.streamType,
            videoMimeType = KEY.videoMimeType,
            resolutionBucket = KEY.resolutionBucket,
            decoderName = KEY.decoderName,
            surfaceType = KEY.surfaceType,
            failureType = FAILURE_TYPE,
            failedAt = 123L
        )
        verify(dao).deleteOlderThan(any())
        verify(dao).keepMostRecent(eq(250))
    }

    @Test
    fun `recordSuccess delegates to dao without pruning`() = runTest {
        repository.recordSuccess(KEY, at = 456L)

        verify(dao).recordSuccess(
            deviceFingerprint = KEY.deviceFingerprint,
            deviceModel = KEY.deviceModel,
            androidSdk = KEY.androidSdk,
            streamType = KEY.streamType,
            videoMimeType = KEY.videoMimeType,
            resolutionBucket = KEY.resolutionBucket,
            decoderName = KEY.decoderName,
            surfaceType = KEY.surfaceType,
            succeededAt = 456L
        )
        verify(dao, never()).deleteOlderThan(any())
        verify(dao, never()).keepMostRecent(any())
    }

    @Test
    fun `prune delegates explicit retention to dao`() = runTest {
        repository.prune(maxRecords = 15, olderThanMs = 999L)

        verify(dao).deleteOlderThan(999L)
        verify(dao).keepMostRecent(15)
    }

    @Test
    fun `playback compatibility dao source avoids sqlite upsert syntax unsupported on android 9`() {
        val source = readDaoSource()

        assertThat(source).contains("@Insert(onConflict = OnConflictStrategy.IGNORE)")
        assertThat(source).contains("UPDATE playback_compatibility_records")
        assertThat(source).doesNotContain("DO UPDATE")
        assertThat(source).doesNotContain("excluded.")
    }

    private fun entity(
        decoderName: String,
        failureCount: Int,
        lastFailedAt: Long,
        lastSucceededAt: Long
    ) = PlaybackCompatibilityRecordEntity(
        id = 1L,
        deviceFingerprint = DEVICE_FINGERPRINT,
        deviceModel = DEVICE_MODEL,
        androidSdk = ANDROID_SDK,
        streamType = STREAM_TYPE,
        videoMimeType = VIDEO_MIME_TYPE,
        resolutionBucket = RESOLUTION_BUCKET,
        decoderName = decoderName,
        surfaceType = SURFACE_TYPE,
        failureType = FAILURE_TYPE,
        lastFailedAt = lastFailedAt,
        lastSucceededAt = lastSucceededAt,
        failureCount = failureCount,
        successCount = if (lastSucceededAt > 0L) 1 else 0
    )

    private fun readDaoSource(): String {
        val candidates = listOf(
            Paths.get("src", "main", "java", "com", "streamvault", "data", "local", "dao", "Daos.kt"),
            Paths.get("data", "src", "main", "java", "com", "streamvault", "data", "local", "dao", "Daos.kt")
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Could not locate Daos.kt from ${Paths.get("").toAbsolutePath()}")
        return String(Files.readAllBytes(path))
    }

    private companion object {
        const val DEVICE_FINGERPRINT = "amazon/firetv/device"
        const val DEVICE_MODEL = "AFTKA"
        const val ANDROID_SDK = 28
        const val STREAM_TYPE = "HLS"
        const val VIDEO_MIME_TYPE = "video/avc"
        const val RESOLUTION_BUCKET = "1080P"
        const val SURFACE_TYPE = "SURFACE_VIEW"
        const val FAILURE_TYPE = "VIDEO_STALL"

        val KEY = PlaybackCompatibilityKey(
            deviceFingerprint = DEVICE_FINGERPRINT,
            deviceModel = DEVICE_MODEL,
            androidSdk = ANDROID_SDK,
            streamType = STREAM_TYPE,
            videoMimeType = VIDEO_MIME_TYPE,
            resolutionBucket = RESOLUTION_BUCKET,
            decoderName = "OMX.test.decoder",
            surfaceType = SURFACE_TYPE
        )
    }
}
