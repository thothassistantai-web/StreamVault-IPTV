package com.streamvault.domain.model

enum class RecordingStatus {
    SCHEDULED,
    RECORDING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class RecordingRequest(
    val providerId: Long,
    val channelId: Long,
    val channelName: String,
    val streamUrl: String,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val programTitle: String? = null,
    val outputPath: String? = null
) {
    init {
        require(channelName.isNotBlank()) { "channelName must not be blank" }
        require(streamUrl.isNotBlank()) { "streamUrl must not be blank" }
        require(scheduledStartMs > 0) { "scheduledStartMs must be positive" }
        require(scheduledEndMs > scheduledStartMs) { "scheduledEndMs must be after scheduledStartMs" }
    }
}

data class RecordingItem(
    val id: String,
    val providerId: Long,
    val channelId: Long,
    val channelName: String,
    val streamUrl: String,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val programTitle: String? = null,
    val outputPath: String? = null,
    val status: RecordingStatus = RecordingStatus.SCHEDULED,
    val failureReason: String? = null
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(channelName.isNotBlank()) { "channelName must not be blank" }
        require(streamUrl.isNotBlank()) { "streamUrl must not be blank" }
        require(scheduledEndMs > scheduledStartMs) { "scheduledEndMs must be after scheduledStartMs" }
    }
}

data class RecordingStorageState(
    val outputDirectory: String? = null,
    val availableBytes: Long? = null,
    val isWritable: Boolean = false
) {
    init {
        availableBytes?.let { require(it >= 0) { "availableBytes must be non-negative" } }
    }
}
