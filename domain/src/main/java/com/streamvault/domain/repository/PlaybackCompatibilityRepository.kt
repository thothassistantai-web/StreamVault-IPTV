package com.streamvault.domain.repository

import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.model.PlaybackCompatibilityRecord

interface PlaybackCompatibilityRepository {
    suspend fun getKnownBadRecords(
        deviceFingerprint: String,
        streamType: String,
        videoMimeType: String,
        resolutionBucket: String
    ): List<PlaybackCompatibilityRecord>

    suspend fun recordFailure(key: PlaybackCompatibilityKey, failureType: String, at: Long = System.currentTimeMillis())

    suspend fun recordSuccess(key: PlaybackCompatibilityKey, at: Long = System.currentTimeMillis())

    suspend fun prune(maxRecords: Int = 250, olderThanMs: Long = System.currentTimeMillis() - DEFAULT_RETENTION_MS)

    companion object {
        const val DEFAULT_RETENTION_MS: Long = 90L * 24L * 60L * 60L * 1000L
    }
}

