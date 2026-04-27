package com.streamvault.domain.model

data class PlaybackCompatibilityKey(
    val deviceFingerprint: String,
    val deviceModel: String,
    val androidSdk: Int,
    val streamType: String,
    val videoMimeType: String,
    val resolutionBucket: String,
    val decoderName: String,
    val surfaceType: String
)

data class PlaybackCompatibilityRecord(
    val key: PlaybackCompatibilityKey,
    val failureType: String,
    val lastFailedAt: Long,
    val lastSucceededAt: Long,
    val failureCount: Int,
    val successCount: Int
) {
    val isKnownBad: Boolean
        get() = failureCount >= 2 && lastFailedAt >= lastSucceededAt
}

