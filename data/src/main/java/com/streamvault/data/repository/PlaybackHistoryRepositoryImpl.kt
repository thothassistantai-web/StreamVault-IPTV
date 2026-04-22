package com.streamvault.data.repository

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.PlaybackWatchedStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.util.DEFAULT_PLAYBACK_COMPLETION_THRESHOLD
import com.streamvault.domain.util.isPlaybackComplete
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamUrlFactory

@Singleton
class PlaybackHistoryRepositoryImpl @Inject constructor(
    private val dao: PlaybackHistoryDao,
    private val preferencesRepository: PreferencesRepository,
    private val movieDao: MovieDao,
    private val episodeDao: EpisodeDao,
    private val transactionRunner: DatabaseTransactionRunner
) : PlaybackHistoryRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingResumeUpdates = ConcurrentHashMap<PlaybackKey, PlaybackHistory>()
    private val isIncognito: StateFlow<Boolean> =
        preferencesRepository.isIncognitoMode
            .stateIn(repositoryScope, SharingStarted.Eagerly, false)

    init {
        repositoryScope.launch {
            while (true) {
                delay(RESUME_POSITION_FLUSH_INTERVAL_MS)
                flushPendingResumeUpdates()
            }
        }
    }

    override fun getRecentlyWatched(limit: Int): Flow<List<PlaybackHistory>> {
        return dao.getRecentlyWatched(limit).map { list -> list.map { it.toDomain() } }
    }

    override fun getRecentlyWatchedByProvider(providerId: Long, limit: Int): Flow<List<PlaybackHistory>> {
        return dao.getRecentlyWatchedByProvider(providerId, limit).map { list -> list.map { it.toDomain() } }
    }

    override fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int> {
        return episodeDao.getUnwatchedCount(
            providerId = providerId,
            seriesId = seriesId,
            completionThreshold = DEFAULT_PLAYBACK_COMPLETION_THRESHOLD
        )
    }

    override suspend fun getPlaybackHistory(contentId: Long, contentType: ContentType, providerId: Long): PlaybackHistory? {
        val directMatch = dao.get(contentId, contentType.name, providerId)?.toDomain()
        if (directMatch != null) {
            return directMatch
        }

        return when (contentType) {
            ContentType.MOVIE -> dao.getLatestMovieHistoryBySharedTmdb(contentId, providerId)?.toDomain()
            ContentType.SERIES -> dao.getLatestSeriesHistoryBySharedTmdb(contentId, providerId)?.toDomain()
            ContentType.SERIES_EPISODE,
            ContentType.LIVE -> null
        }
    }

    override suspend fun markAsWatched(history: PlaybackHistory): Result<Unit> {
        return try {
            if (isIncognito.value) {
                return Result.success(Unit)
            }

            val existing = takePendingResumeUpdate(history.playbackKey())
                ?: dao.get(history.contentId, history.contentType.name, history.providerId)?.toDomain()
            val resolvedTotalDuration = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L
            val resolvedResumePosition = when {
                resolvedTotalDuration > 0L -> resolvedTotalDuration
                history.resumePositionMs > 0L -> history.resumePositionMs
                else -> existing?.resumePositionMs ?: 0L
            }
            val updatedHistory = history.copy(
                streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                resumePositionMs = resolvedResumePosition,
                totalDurationMs = resolvedTotalDuration,
                watchCount = existing?.watchCount ?: history.watchCount,
                watchedStatus = PlaybackWatchedStatus.COMPLETED_MANUAL,
                lastWatchedAt = System.currentTimeMillis()
            )
            transactionRunner.inTransaction {
                dao.insertOrUpdate(updatedHistory.toEntity())
                syncDenormalizedProgress(updatedHistory.contentId, updatedHistory.contentType, updatedHistory.providerId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to mark content as watched", e)
        }
    }

    override suspend fun recordPlayback(history: PlaybackHistory): Result<Unit> {
        return try {
            if (isIncognito.value) {
                return Result.success(Unit)
            }

            val existing = takePendingResumeUpdate(history.playbackKey())
                ?: dao.get(history.contentId, history.contentType.name, history.providerId)?.toDomain()
            val updatedHistory = history.copy(
                streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                resumePositionMs = history.resumePositionMs.takeIf { it > 0L } ?: existing?.resumePositionMs ?: 0L,
                totalDurationMs = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L,
                watchCount = (existing?.watchCount ?: 0) + 1,
                watchedStatus = resolveWatchedStatus(
                    resumePositionMs = history.resumePositionMs.takeIf { it > 0L } ?: existing?.resumePositionMs ?: 0L,
                    totalDurationMs = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L,
                    fallback = history.watchedStatus
                ),
                lastWatchedAt = System.currentTimeMillis()
            )
            transactionRunner.inTransaction {
                dao.insertOrUpdate(updatedHistory.toEntity())
                syncDenormalizedProgress(updatedHistory.contentId, updatedHistory.contentType, updatedHistory.providerId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to record playback history", e)
        }
    }

    override suspend fun updateResumePosition(history: PlaybackHistory): Result<Unit> {
        return try {
            if (isIncognito.value) {
                return Result.success(Unit)
            }

            val existing = pendingResumeUpdates[history.playbackKey()]
                ?: dao.get(history.contentId, history.contentType.name, history.providerId)?.toDomain()

            val updatedHistory = history.copy(
                streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                watchCount = existing?.watchCount ?: 1,
                watchedStatus = resolveWatchedStatus(
                    resumePositionMs = history.resumePositionMs,
                    totalDurationMs = history.totalDurationMs,
                    fallback = existing?.watchedStatus ?: history.watchedStatus
                ),
                lastWatchedAt = System.currentTimeMillis()
            )
            pendingResumeUpdates[history.playbackKey()] = updatedHistory
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to update playback resume position", e)
        }
    }

    override suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long): Result<Unit> = try {
        pendingResumeUpdates.remove(PlaybackKey(contentId, contentType, providerId))
        transactionRunner.inTransaction {
            dao.delete(contentId, contentType.name, providerId)
            syncDenormalizedProgress(contentId, contentType, providerId)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to remove playback history item", e)
    }

    override suspend fun clearAllHistory(): Result<Unit> = try {
        pendingResumeUpdates.clear()
        transactionRunner.inTransaction {
            dao.deleteAll()
            movieDao.resetAllWatchProgress()
            episodeDao.resetAllWatchProgress()
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to clear playback history", e)
    }

    override suspend fun clearHistoryForProvider(providerId: Long): Result<Unit> = try {
        pendingResumeUpdates.keys.removeIf { it.providerId == providerId }
        transactionRunner.inTransaction {
            dao.deleteByProvider(providerId)
            syncDenormalizedProgressForProvider(providerId)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to clear provider playback history", e)
    }

    override suspend fun clearLiveHistoryForProvider(providerId: Long): Result<Unit> = try {
        pendingResumeUpdates.keys.removeIf { it.providerId == providerId && it.contentType == ContentType.LIVE }
        dao.deleteByProviderAndType(providerId, ContentType.LIVE.name)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to clear live playback history", e)
    }

    override suspend fun flushPendingProgress(): Result<Unit> = try {
        flushPendingResumeUpdates()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to flush pending progress", e)
    }

    private suspend fun flushPendingResumeUpdates() {
        val snapshot = pendingResumeUpdates.entries.toList()
        snapshot.forEach { (key, history) ->
            if (pendingResumeUpdates.remove(key, history)) {
                transactionRunner.inTransaction {
                    dao.insertOrUpdate(history.toEntity())
                    syncDenormalizedProgress(history.contentId, history.contentType, history.providerId)
                }
            }
        }
    }

    private fun takePendingResumeUpdate(key: PlaybackKey): PlaybackHistory? =
        pendingResumeUpdates.remove(key)

    private suspend fun syncDenormalizedProgress(contentId: Long, contentType: ContentType, providerId: Long) {
        when (contentType) {
            ContentType.MOVIE -> movieDao.syncWatchProgressFromHistory(contentId, providerId)
            ContentType.SERIES_EPISODE -> episodeDao.syncWatchProgressFromHistory(contentId, providerId)
            else -> Unit
        }
    }

    private suspend fun syncDenormalizedProgressForProvider(providerId: Long) {
        movieDao.syncWatchProgressFromHistoryByProvider(providerId)
        episodeDao.syncWatchProgressFromHistoryByProvider(providerId)
    }
    private fun resolveWatchedStatus(
        resumePositionMs: Long,
        totalDurationMs: Long,
        fallback: PlaybackWatchedStatus
    ): PlaybackWatchedStatus {
        if (totalDurationMs <= 0L) {
            return fallback
        }
        return if (isPlaybackComplete(resumePositionMs, totalDurationMs, DEFAULT_PLAYBACK_COMPLETION_THRESHOLD)) {
            PlaybackWatchedStatus.COMPLETED_AUTO
        } else {
            PlaybackWatchedStatus.IN_PROGRESS
        }
    }
}

private const val RESUME_POSITION_FLUSH_INTERVAL_MS = 30_000L

private data class PlaybackKey(
    val contentId: Long,
    val contentType: ContentType,
    val providerId: Long
)

private fun PlaybackHistory.playbackKey(): PlaybackKey =
    PlaybackKey(contentId = contentId, contentType = contentType, providerId = providerId)
