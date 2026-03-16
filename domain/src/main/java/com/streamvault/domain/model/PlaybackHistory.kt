package com.streamvault.domain.model

data class PlaybackHistory(
    val id: Long = 0,
    val contentId: Long,
    val contentType: ContentType,
    val providerId: Long,
    val title: String,
    val posterUrl: String? = null,
    val streamUrl: String,
    val resumePositionMs: Long = 0,
    val totalDurationMs: Long = 0,
    val lastWatchedAt: Long = 0,
    val watchCount: Int = 1,
    val seriesId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
) {
    init {
        require(resumePositionMs >= 0) { "resumePositionMs must be non-negative" }
        require(totalDurationMs >= 0) { "totalDurationMs must be non-negative" }
        require(watchCount >= 1) { "watchCount must be at least 1" }
        require(lastWatchedAt >= 0) { "lastWatchedAt must be non-negative" }
        seasonNumber?.let { require(it >= 0) { "seasonNumber must be non-negative" } }
        episodeNumber?.let { require(it >= 0) { "episodeNumber must be non-negative" } }
    }
}
