package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.Result

data class StalkerDeviceProfile(
    val portalUrl: String,
    val macAddress: String,
    val deviceProfile: String,
    val timezone: String,
    val locale: String,
    val serialNumber: String,
    val deviceId: String,
    val deviceId2: String,
    val signature: String,
    val userAgent: String,
    val xUserAgent: String
)

data class StalkerSession(
    val loadUrl: String,
    val portalReferer: String,
    val token: String
)

data class StalkerProviderProfile(
    val accountId: String? = null,
    val accountName: String? = null,
    val maxConnections: Int? = null,
    val expirationDate: Long? = null,
    val statusLabel: String? = null,
    val authAccess: Boolean? = null
)

data class StalkerCategoryRecord(
    val id: String,
    val name: String,
    val alias: String? = null
)

data class StalkerItemRecord(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val number: Int = 0,
    val logoUrl: String? = null,
    val epgChannelId: String? = null,
    val cmd: String? = null,
    val streamUrl: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: Float = 0f,
    val tmdbId: Long? = null,
    val youtubeTrailer: String? = null,
    val backdropUrl: String? = null,
    val containerExtension: String? = null,
    val addedAt: Long = 0L,
    val isAdult: Boolean = false,
    val isSeries: Boolean = false
)

data class StalkerPagedItems(
    val items: List<StalkerItemRecord>,
    val page: Int,
    val totalPages: Int,
    val pageSize: Int
) {
    val isComplete: Boolean get() = page >= totalPages
}

data class StalkerSeriesDetails(
    val series: StalkerItemRecord,
    val seasons: List<StalkerSeasonRecord>
)

data class StalkerSeasonRecord(
    val seasonNumber: Int,
    val name: String,
    val coverUrl: String? = null,
    val episodes: List<StalkerEpisodeRecord>
)

data class StalkerEpisodeRecord(
    val id: String,
    val title: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val cmd: String? = null,
    val coverUrl: String? = null,
    val plot: String? = null,
    val durationSeconds: Int = 0,
    val releaseDate: String? = null,
    val rating: Float = 0f,
    val containerExtension: String? = null
)

data class StalkerProgramRecord(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val hasArchive: Boolean = false,
    val isNowPlaying: Boolean = false
)

interface StalkerApiService {
    suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>>

    suspend fun getLiveCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>>

    suspend fun getLiveStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>>

    suspend fun streamLiveStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Result<Int>

    suspend fun getVodCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>>

    suspend fun getVodStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>>

    suspend fun getVodStreamsPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?,
        page: Int
    ): Result<StalkerPagedItems>

    suspend fun getSeriesCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>>

    suspend fun getSeries(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>>

    suspend fun getSeriesPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?,
        page: Int
    ): Result<StalkerPagedItems>

    suspend fun getSeriesDetails(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        seriesId: String
    ): Result<StalkerSeriesDetails>

    suspend fun getShortEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String,
        limit: Int
    ): Result<List<StalkerProgramRecord>>

    suspend fun getEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String
    ): Result<List<StalkerProgramRecord>>

    suspend fun getBulkEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        periodHours: Int = 6
    ): Result<List<StalkerProgramRecord>>

    /**
     * Streams the bulk Stalker EPG payload one program at a time.
     *
     * Many portals return a single very large JSON body for `get_epg_info` regardless of `period`/`ch_id`.
     * Building a full Gson tree of that payload is what causes the EPG OOM on TV devices, so this path
     * walks the response with `JsonReader` and emits records via [onProgram] without ever holding the
     * full payload in memory.
     */
    suspend fun streamBulkEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        periodHours: Int = 6,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Result<Int>

    /**
     * Streams a per-channel Stalker EPG response. Mirrors [streamBulkEpg]; the [channelId] is forwarded
     * as the portal's `ch_id` query parameter.
     */
    suspend fun streamEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String,
        periodHours: Int = 6,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Result<Int>

    suspend fun createLink(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        kind: StalkerStreamKind,
        cmd: String,
        seriesNumber: Int? = null
    ): Result<String>
}
