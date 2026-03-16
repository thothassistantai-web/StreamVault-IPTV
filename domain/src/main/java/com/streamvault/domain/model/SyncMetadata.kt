package com.streamvault.domain.model

data class SyncMetadata(
    val providerId: Long,
    val lastLiveSync: Long = 0,
    val lastMovieSync: Long = 0,
    val lastSeriesSync: Long = 0,
    val lastEpgSync: Long = 0,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val epgCount: Int = 0,
    val lastSyncStatus: String = "NONE"
) {
    init {
        require(liveCount >= 0) { "liveCount must be non-negative" }
        require(movieCount >= 0) { "movieCount must be non-negative" }
        require(seriesCount >= 0) { "seriesCount must be non-negative" }
        require(epgCount >= 0) { "epgCount must be non-negative" }
    }
}
