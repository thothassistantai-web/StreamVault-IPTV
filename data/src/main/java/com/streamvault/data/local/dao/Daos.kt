package com.streamvault.data.local.dao

import androidx.room.*
import com.streamvault.data.local.entity.*
import kotlinx.coroutines.flow.Flow

data class RemoteIdMapping(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "remote_id") val remoteId: Long
)

@Dao
abstract class ProviderDao {
    @Query("SELECT * FROM providers ORDER BY created_at DESC")
    abstract fun getAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE is_active = 1 LIMIT 1")
    abstract fun getActive(): Flow<ProviderEntity?>

    @Query("SELECT * FROM providers WHERE server_url = :serverUrl AND username = :username")
    abstract suspend fun getByUrlAndUser(serverUrl: String, username: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id = :id")
    abstract suspend fun getById(id: Long): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(provider: ProviderEntity): Long

    @Update
    abstract suspend fun update(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE providers SET is_active = 0")
    abstract suspend fun deactivateAll()

    @Query("UPDATE providers SET is_active = 1 WHERE id = :id")
    abstract suspend fun activate(id: Long)

    @Query("UPDATE providers SET last_synced_at = :timestamp WHERE id = :id")
    abstract suspend fun updateSyncTime(id: Long, timestamp: Long)

    @Query("UPDATE providers SET epg_url = :epgUrl WHERE id = :id")
    abstract suspend fun updateEpgUrl(id: Long, epgUrl: String)

    /** Atomically deactivates all providers then activates the given one. */
    @Transaction
    open suspend fun setActive(id: Long) {
        deactivateAll()
        activate(id)
    }
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE provider_id = :providerId ORDER BY number ASC")
    fun getByProvider(providerId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE provider_id = :providerId ORDER BY number ASC LIMIT :limit OFFSET :offset")
    fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY number ASC")
    fun getByCategory(providerId: Long, categoryId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY number ASC LIMIT :limit OFFSET :offset")
    fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN channels_fts ON c.id = channels_fts.rowid
        WHERE c.provider_id = :providerId
          AND channels_fts MATCH :query
        ORDER BY c.name ASC
        """
    )
    fun search(providerId: Long, query: String): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN channels_fts ON c.id = channels_fts.rowid
        WHERE c.provider_id = :providerId
          AND c.category_id = :categoryId
          AND channels_fts MATCH :query
        ORDER BY c.name ASC
        """
    )
    fun searchByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("SELECT id, stream_id AS remote_id FROM channels WHERE provider_id = :providerId")
    suspend fun getIdMappings(providerId: Long): List<RemoteIdMapping>

    @Query("DELETE FROM channels WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    suspend fun replaceAll(providerId: Long, channels: List<ChannelEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = channels
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
    }

    @Query("SELECT * FROM channels WHERE id IN (:ids)")
    fun getByIds(ids: List<Long>): Flow<List<ChannelEntity>>

    @Query("SELECT category_id, COUNT(*) as item_count FROM channels WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM channels WHERE provider_id = :providerId")
    fun getCount(providerId: Long): Flow<Int>

    @Query("UPDATE channels SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)

    @Query("UPDATE channels SET error_count = error_count + 1 WHERE id = :id")
    suspend fun incrementErrorCount(id: Long)

    @Query("UPDATE channels SET error_count = 0 WHERE id = :id")
    suspend fun resetErrorCount(id: Long)
}

@Dao
interface ChannelPreferenceDao {
    @Query("SELECT aspect_ratio FROM channel_preferences WHERE channel_id = :channelId LIMIT 1")
    fun observeAspectRatio(channelId: Long): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: ChannelPreferenceEntity)

    @Query("DELETE FROM channel_preferences")
    suspend fun deleteAll()
}

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY name ASC")
    fun getByProvider(providerId: Long): Flow<List<MovieEntity>>

    /** SQL-level parental filter — avoids loading protected items into memory. */
    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND is_user_protected = 0 ORDER BY name ASC")
    fun getByProviderUnprotected(providerId: Long): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY name ASC LIMIT :limit OFFSET :offset")
    fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC")
    fun getByCategory(providerId: Long, categoryId: Long): Flow<List<MovieEntity>>

    /** SQL-level parental filter per category. */
    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId AND is_user_protected = 0 ORDER BY name ASC")
    fun getByCategoryUnprotected(providerId: Long, categoryId: Long): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC LIMIT :limit OFFSET :offset")
    fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC LIMIT :limit")
    fun getByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId ORDER BY release_date DESC, name ASC LIMIT :limit")
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT m.* FROM movies m
        JOIN movies_fts ON m.id = movies_fts.rowid
        WHERE m.provider_id = :providerId
          AND movies_fts MATCH :query
        ORDER BY m.name ASC
        """
    )
    fun search(providerId: Long, query: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getById(id: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE id IN (:ids)")
    fun getByIds(ids: List<Long>): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE provider_id = :providerId AND stream_id = :streamId")
    suspend fun getByStreamId(providerId: Long, streamId: Long): MovieEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("SELECT id, stream_id AS remote_id FROM movies WHERE provider_id = :providerId")
    suspend fun getIdMappings(providerId: Long): List<RemoteIdMapping>

    @Update
    suspend fun update(movie: MovieEntity)

    @Query("UPDATE movies SET watch_progress = :progress, last_watched_at = :timestamp WHERE id = :id")
    suspend fun updateWatchProgress(id: Long, progress: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM movies WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("DELETE FROM movies WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("""
        UPDATE movies 
        SET watch_progress = (
            SELECT resume_position_ms FROM playback_history 
            WHERE playback_history.content_id = movies.id 
            AND playback_history.content_type = 'MOVIE'
            AND playback_history.provider_id = :providerId
        )
        WHERE provider_id = :providerId AND EXISTS (
            SELECT 1 FROM playback_history 
            WHERE playback_history.content_id = movies.id
            AND playback_history.content_type = 'MOVIE' 
            AND playback_history.provider_id = :providerId
        )
    """)
    suspend fun restoreWatchProgress(providerId: Long)

    @Transaction
    suspend fun replaceAll(providerId: Long, movies: List<MovieEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = movies
            .distinctBy { it.streamId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.streamId] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
        restoreWatchProgress(providerId)
    }

    @Query("SELECT category_id, COUNT(*) as item_count FROM movies WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId")
    fun getCount(providerId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE provider_id = :providerId AND category_id = :categoryId")
    fun getCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query("UPDATE movies SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY name ASC")
    fun getByProvider(providerId: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY name ASC LIMIT :limit OFFSET :offset")
    fun getByProviderPage(providerId: Long, limit: Int, offset: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC")
    fun getByCategory(providerId: Long, categoryId: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC LIMIT :limit OFFSET :offset")
    fun getByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND category_id = :categoryId ORDER BY name ASC LIMIT :limit")
    fun getByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY rating DESC, name ASC LIMIT :limit")
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId ORDER BY last_modified DESC, name ASC LIMIT :limit")
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query(
        """
        SELECT s.* FROM series s
        JOIN series_fts ON s.id = series_fts.rowid
        WHERE s.provider_id = :providerId
          AND series_fts MATCH :query
        ORDER BY s.name ASC
        """
    )
    fun search(providerId: Long, query: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getById(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE id IN (:ids)")
    fun getByIds(ids: List<Long>): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE provider_id = :providerId AND series_id = :seriesId LIMIT 1")
    suspend fun getBySeriesId(providerId: Long, seriesId: Long): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntity>)

    @Update
    suspend fun update(series: SeriesEntity)

    @Query("SELECT id, series_id AS remote_id FROM series WHERE provider_id = :providerId")
    suspend fun getIdMappings(providerId: Long): List<RemoteIdMapping>

    @Query("DELETE FROM series WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Transaction
    suspend fun replaceAll(providerId: Long, series: List<SeriesEntity>) {
        val existingByRemoteId = getIdMappings(providerId).associate { it.remoteId to it.id }
        val remapped = series
            .distinctBy { it.seriesId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.seriesId] ?: 0L) }
        deleteByProvider(providerId)
        insertAll(remapped)
    }

    @Query("SELECT category_id, COUNT(*) as item_count FROM series WHERE provider_id = :providerId AND category_id IS NOT NULL GROUP BY category_id")
    fun getCategoryCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId")
    fun getCount(providerId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM series WHERE provider_id = :providerId AND category_id = :categoryId")
    fun getCountByCategory(providerId: Long, categoryId: Long): Flow<Int>

    @Query("UPDATE series SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, isProtected: Boolean)
}


@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE series_id = :seriesId ORDER BY season_number ASC, episode_number ASC")
    fun getBySeries(seriesId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE series_id = :seriesId ORDER BY season_number ASC, episode_number ASC")
    suspend fun getBySeriesSync(seriesId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT id, episode_id AS remote_id FROM episodes WHERE provider_id = :providerId AND series_id = :seriesId")
    suspend fun getIdMappings(providerId: Long, seriesId: Long): List<RemoteIdMapping>

    @Query("UPDATE episodes SET watch_progress = :progress, last_watched_at = :timestamp WHERE id = :id")
    suspend fun updateWatchProgress(id: Long, progress: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM episodes WHERE series_id = :seriesId")
    suspend fun deleteBySeries(seriesId: Long)

    @Query("DELETE FROM episodes WHERE series_id NOT IN (SELECT id FROM series)")
    suspend fun deleteOrphans(): Int

    @Query("""
        UPDATE episodes 
        SET watch_progress = (
            SELECT resume_position_ms FROM playback_history 
            WHERE playback_history.content_id = episodes.id 
            AND playback_history.content_type = 'SERIES_EPISODE'
            AND playback_history.provider_id = episodes.provider_id
        )
        WHERE series_id = :seriesId AND EXISTS (
            SELECT 1 FROM playback_history 
            WHERE playback_history.content_id = episodes.id
            AND playback_history.content_type = 'SERIES_EPISODE' 
            AND playback_history.provider_id = episodes.provider_id
        )
    """)
    suspend fun restoreWatchProgress(seriesId: Long)

    @Transaction
    suspend fun replaceAll(seriesId: Long, providerId: Long, episodes: List<EpisodeEntity>) {
        val existingByRemoteId = getIdMappings(providerId, seriesId).associate { it.remoteId to it.id }
        val remapped = episodes
            .distinctBy { it.episodeId }
            .map { entity -> entity.copy(id = existingByRemoteId[entity.episodeId] ?: 0L) }
        deleteBySeries(seriesId)
        insertAll(remapped)
        restoreWatchProgress(seriesId)
    }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE provider_id = :providerId AND type = :type ORDER BY name ASC")
    fun getByProviderAndType(providerId: Long, type: String): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE provider_id = :providerId AND type = :type")
    suspend fun deleteByProviderAndType(providerId: Long, type: String)

    @Transaction
    suspend fun replaceAll(providerId: Long, type: String, categories: List<CategoryEntity>) {
        deleteByProviderAndType(providerId, type)
        insertAll(categories)
    }

    @Query("UPDATE categories SET is_user_protected = :isProtected WHERE provider_id = :providerId AND category_id = :categoryId AND type = :type")
    suspend fun updateProtectionStatus(providerId: Long, categoryId: Long, type: String, isProtected: Boolean)
}

@Dao
interface ProgramDao {
    @Query("SELECT * FROM programs WHERE provider_id = :providerId AND channel_id = :channelId AND end_time > :startTime AND start_time < :endTime ORDER BY start_time ASC")
    fun getForChannel(providerId: Long, channelId: String, startTime: Long, endTime: Long): Flow<List<ProgramEntity>>

    @Query(
        """
        SELECT * FROM programs
        WHERE provider_id = :providerId
          AND channel_id IN (:channelIds)
          AND end_time > :startTime
          AND start_time < :endTime
        ORDER BY channel_id ASC, start_time ASC
        """
    )
    fun getForChannels(providerId: Long, channelIds: List<String>, startTime: Long, endTime: Long): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM programs WHERE provider_id = :providerId AND channel_id = :channelId AND start_time <= :now AND end_time > :now LIMIT 1")
    fun getNowPlaying(providerId: Long, channelId: String, now: Long = System.currentTimeMillis()): Flow<ProgramEntity?>

    @Query("SELECT * FROM programs WHERE provider_id = :providerId AND channel_id IN (:channelIds) AND start_time <= :now AND end_time > :now")
    fun getNowPlayingForChannels(providerId: Long, channelIds: List<String>, now: Long = System.currentTimeMillis()): Flow<List<ProgramEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programs: List<ProgramEntity>)

    @Query("DELETE FROM programs WHERE end_time < :beforeTime")
    suspend fun deleteOld(beforeTime: Long): Int

    @Query("DELETE FROM programs WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)

    @Query("UPDATE programs SET provider_id = :targetProviderId WHERE provider_id = :sourceProviderId")
    suspend fun moveToProvider(sourceProviderId: Long, targetProviderId: Long)

    @Query("DELETE FROM programs WHERE provider_id = :providerId AND channel_id = :channelId")
    suspend fun deleteForChannel(providerId: Long, channelId: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE group_id IS NULL ORDER BY position ASC")
    fun getAllGlobal(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE content_type = :contentType AND group_id IS NULL ORDER BY position ASC")
    fun getGlobalByType(contentType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE content_type = :contentType ORDER BY position ASC")
    fun getAllByType(contentType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE group_id = :groupId ORDER BY position ASC")
    fun getByGroup(groupId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE content_id = :contentId AND content_type = :contentType AND (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId) LIMIT 1")
    suspend fun get(contentId: Long, contentType: String, groupId: Long?): FavoriteEntity?

    @Query("SELECT COUNT(*) FROM favorites WHERE group_id IS NULL AND content_type = :contentType")
    fun getGlobalFavoriteCount(contentType: String): Flow<Int>

    @Query("SELECT group_id as category_id, COUNT(*) as item_count FROM favorites WHERE group_id IS NOT NULL AND content_type = :contentType GROUP BY group_id")
    fun getGroupFavoriteCounts(contentType: String): Flow<List<CategoryCount>>

    @Query("SELECT group_id FROM favorites WHERE content_id = :contentId AND content_type = :contentType AND group_id IS NOT NULL")
    suspend fun getGroupMemberships(contentId: Long, contentType: String): List<Long>

    @Query("SELECT MAX(position) FROM favorites WHERE (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId)")
    suspend fun getMaxPosition(groupId: Long?): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Update
    suspend fun updateAll(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE content_id = :contentId AND content_type = :contentType AND (:groupId IS NULL AND group_id IS NULL OR group_id = :groupId)")
    suspend fun delete(contentId: Long, contentType: String, groupId: Long?)

    @Query("DELETE FROM favorites WHERE content_type = 'LIVE' AND content_id NOT IN (SELECT id FROM channels)")
    suspend fun deleteMissingLiveFavorites(): Int

    @Query("DELETE FROM favorites WHERE content_type = 'MOVIE' AND content_id NOT IN (SELECT id FROM movies)")
    suspend fun deleteMissingMovieFavorites(): Int

    @Query("DELETE FROM favorites WHERE content_type = 'SERIES' AND content_id NOT IN (SELECT id FROM series)")
    suspend fun deleteMissingSeriesFavorites(): Int

    @Query("UPDATE favorites SET group_id = :groupId WHERE id = :favoriteId")
    suspend fun updateGroup(favoriteId: Long, groupId: Long?)
}

@Dao
interface VirtualGroupDao {
    @Query("SELECT * FROM virtual_groups WHERE content_type = :contentType ORDER BY position ASC")
    fun getByType(contentType: String): Flow<List<VirtualGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: VirtualGroupEntity): Long

    @Query("UPDATE virtual_groups SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM virtual_groups WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY last_watched_at DESC LIMIT :limit")
    fun getRecentlyWatched(limit: Int = 100): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE provider_id = :providerId ORDER BY last_watched_at DESC LIMIT :limit")
    fun getRecentlyWatchedByProvider(providerId: Long, limit: Int = 100): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history ORDER BY last_watched_at DESC")
    suspend fun getAllSync(): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM playback_history WHERE content_id = :contentId AND content_type = :contentType AND provider_id = :providerId")
    suspend fun get(contentId: Long, contentType: String, providerId: Long): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(history: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE content_id = :contentId AND content_type = :contentType AND provider_id = :providerId")
    suspend fun delete(contentId: Long, contentType: String, providerId: Long)

    @Query("DELETE FROM playback_history")
    suspend fun deleteAll()

    @Query("DELETE FROM playback_history WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: Long)
}

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE provider_id = :providerId")
    fun get(providerId: Long): Flow<SyncMetadataEntity?>

    @Query("SELECT * FROM sync_metadata WHERE provider_id = :providerId")
    suspend fun getSync(providerId: Long): SyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(metadata: SyncMetadataEntity)

    @Query("DELETE FROM sync_metadata WHERE provider_id = :providerId")
    suspend fun delete(providerId: Long)
}
