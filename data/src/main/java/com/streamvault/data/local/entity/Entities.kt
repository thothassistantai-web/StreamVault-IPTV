package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "providers",
    indices = [Index(value = ["server_url", "username"], unique = true)]
)
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: String, // XTREAM_CODES, M3U
    @ColumnInfo(name = "server_url") val serverUrl: String,
    val username: String = "",
    val password: String = "",
    @ColumnInfo(name = "m3u_url") val m3uUrl: String = "",
    @ColumnInfo(name = "epg_url") val epgUrl: String = "",
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "max_connections") val maxConnections: Int = 1,
    @ColumnInfo(name = "expiration_date") val expirationDate: Long? = null,
    val status: String = "UNKNOWN",
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "channels",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "category_id"]),
        Index(value = ["provider_id", "stream_id"], unique = true),
        Index(value = ["logical_group_id"])
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "stream_id") val streamId: Long = 0,
    val name: String,
    @ColumnInfo(name = "logo_url") val logoUrl: String? = null,
    @ColumnInfo(name = "group_title") val groupTitle: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String? = null,
    val number: Int = 0,
    @ColumnInfo(name = "catch_up_supported") val catchUpSupported: Boolean = false,
    @ColumnInfo(name = "catch_up_days") val catchUpDays: Int = 0,
    val catchUpSource: String? = null,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false,
    @ColumnInfo(name = "logical_group_id") val logicalGroupId: String = "",
    @ColumnInfo(name = "error_count") val errorCount: Int = 0
)

@Entity(
    tableName = "movies",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "category_id"]),
        Index(value = ["provider_id", "stream_id"], unique = true)
    ]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "stream_id") val streamId: Long = 0,
    val name: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val duration: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int = 0,
    val rating: Float = 0f,
    val year: String? = null,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long? = null,
    @ColumnInfo(name = "youtube_trailer") val youtubeTrailer: String? = null,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "watch_progress") val watchProgress: Long = 0L,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0L,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false
)

@Entity(
    tableName = "series",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "category_id"]),
        Index(value = ["provider_id", "series_id"], unique = true)
    ]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "series_id") val seriesId: Long = 0,
    val name: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val rating: Float = 0f,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long? = null,
    @ColumnInfo(name = "youtube_trailer") val youtubeTrailer: String? = null,
    @ColumnInfo(name = "episode_run_time") val episodeRunTime: String? = null,
    @ColumnInfo(name = "last_modified") val lastModified: Long = 0L,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false
)

@Fts4(contentEntity = ChannelEntity::class)
@Entity(tableName = "channels_fts")
data class ChannelFtsEntity(
    val name: String
)

@Fts4(contentEntity = MovieEntity::class)
@Entity(tableName = "movies_fts")
data class MovieFtsEntity(
    val name: String
)

@Fts4(contentEntity = SeriesEntity::class)
@Entity(tableName = "series_fts")
data class SeriesFtsEntity(
    val name: String
)

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["series_id"]),
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "episode_id"], unique = true)
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "episode_id") val episodeId: Long = 0,
    val title: String,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int,
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    val plot: String? = null,
    val duration: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int = 0,
    val rating: Float = 0f,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "watch_progress") val watchProgress: Long = 0L,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0L,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false
)

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "category_id", "type"], unique = true)
    ]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long = 0,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    val type: String = "LIVE", // LIVE, MOVIE, SERIES
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false
)

@Entity(
    tableName = "programs",
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "channel_id"]),
        Index(value = ["start_time"]),
        Index(value = ["provider_id", "channel_id", "start_time"]),
        Index(value = ["provider_id", "channel_id", "start_time", "end_time"], unique = true)
    ]
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "channel_id") val channelId: String,
    val title: String,
    val description: String = "",
    @ColumnInfo(name = "start_time") val startTime: Long = 0,
    @ColumnInfo(name = "end_time") val endTime: Long = 0,
    val lang: String = "",
    @ColumnInfo(name = "has_archive") val hasArchive: Boolean = false
)

@Entity(
    tableName = "favorites",
    foreignKeys = [ForeignKey(
        entity = VirtualGroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["group_id"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [
        Index(value = ["content_id", "content_type", "group_id"], unique = true),
        Index(value = ["content_type", "group_id"]),
        Index(value = ["group_id", "position"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "content_id") val contentId: Long,
    @ColumnInfo(name = "content_type") val contentType: String, // LIVE, MOVIE, SERIES
    val position: Int = 0,
    @ColumnInfo(name = "group_id") val groupId: Long? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "virtual_groups",
    indices = [Index(value = ["position"])]
)
data class VirtualGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "icon_emoji") val iconEmoji: String? = null,
    val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "content_type") val contentType: String = "LIVE"
)

data class CategoryCount(
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "item_count") val item_count: Int
)

@Entity(
    tableName = "playback_history",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["content_id", "content_type", "provider_id"], unique = true),
        Index(value = ["last_watched_at"]),
        Index(value = ["provider_id"])
    ]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "content_id") val contentId: Long,
    @ColumnInfo(name = "content_type") val contentType: String, // LIVE, MOVIE, SERIES_EPISODE
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val title: String = "",
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long = 0,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long = 0,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0,
    @ColumnInfo(name = "watch_count") val watchCount: Int = 1,
    @ColumnInfo(name = "series_id") val seriesId: Long? = null,
    @ColumnInfo(name = "season_number") val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int? = null
)

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "last_live_sync") val lastLiveSync: Long = 0,
    @ColumnInfo(name = "last_movie_sync") val lastMovieSync: Long = 0,
    @ColumnInfo(name = "last_series_sync") val lastSeriesSync: Long = 0,
    @ColumnInfo(name = "last_epg_sync") val lastEpgSync: Long = 0,
    @ColumnInfo(name = "live_count") val liveCount: Int = 0,
    @ColumnInfo(name = "movie_count") val movieCount: Int = 0,
    @ColumnInfo(name = "series_count") val seriesCount: Int = 0,
    @ColumnInfo(name = "epg_count") val epgCount: Int = 0,
    @ColumnInfo(name = "last_sync_status") val lastSyncStatus: String = "NONE"
)
