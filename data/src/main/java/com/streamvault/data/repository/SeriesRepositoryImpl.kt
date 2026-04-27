package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.*
import com.streamvault.data.mapper.*
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.sync.ContentCachePolicy
import com.streamvault.domain.model.*
import com.streamvault.domain.model.Result.Success
import com.streamvault.domain.repository.SeriesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.data.util.rankSearchResults
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesRepositoryImpl @Inject constructor(
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val providerDao: ProviderDao,
    private val stalkerApiService: StalkerApiService,
    private val xtreamApiService: XtreamApiService,
    private val credentialCrypto: CredentialCrypto,
    private val preferencesRepository: PreferencesRepository,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao
) : SeriesRepository {
    private companion object {
        const val SEARCH_RESULT_LIMIT = 200
        const val MIN_SEARCH_QUERY_LENGTH = 2
        const val BROWSE_WINDOW_BUFFER = 80
        const val XTREAM_SERIES_CATEGORY_TIMEOUT_MILLIS = 60_000L
        const val XTREAM_CATEGORY_HYDRATION_CONCURRENCY = 1
        const val XTREAM_EMPTY_CATEGORY_RETRY_COOLDOWN_MILLIS = 30_000L
        const val CURSOR_BATCH_SIZE = 40
        const val STALKER_PREVIEW_REQUIRED_COUNT_THRESHOLD = 24
        const val STALKER_PREVIEW_MAX_REMOTE_PAGES = 2
    }

    private data class CachedXtreamProvider(
        val signature: String,
        val provider: XtreamProvider
    )

    private data class NameCursor(
        val name: String,
        val id: Long
    )

    private data class RatingCursor(
        val rating: Float,
        val name: String,
        val id: Long
    )

    private data class FreshCursor(
        val lastModified: Long,
        val name: String,
        val id: Long
    )

    private val xtreamProviderCache = ConcurrentHashMap<Long, CachedXtreamProvider>()
    private val xtreamCategoryLoadLocks = ConcurrentHashMap<String, Mutex>()
    private val loadedXtreamCategories = ConcurrentHashMap.newKeySet<String>()
    private val backgroundRefreshes = ConcurrentHashMap.newKeySet<String>()
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(XTREAM_CATEGORY_HYDRATION_CONCURRENCY)
    )

    override fun getSeries(providerId: Long): Flow<List<Series>> =
        combine(
            seriesDao.getByProvider(providerId),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getSeriesByCategory(providerId: Long, categoryId: Long): Flow<List<Series>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId)
            emitAll(
                combine(
                    seriesDao.getByCategory(providerId, categoryId),
                    preferencesRepository.parentalControlLevel
                ) { entities, level: Int ->
                    if (level >= 3) {
                        entities.filter { !it.isUserProtected }
                    } else {
                        entities
                    }
                }.map { list -> list.map { it.toDomain() } }
            )
        }

    override fun getSeriesByCategoryPage(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<Series>> = flow {
        ensureXtreamCategoryLoaded(providerId, categoryId)
        emitAll(
            combine(
                seriesDao.getByCategoryPage(providerId, categoryId, limit, offset),
                preferencesRepository.parentalControlLevel
            ) { entities, level: Int ->
                if (level >= 3) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list -> list.map { it.toDomain() } }
        )
    }

    override fun getSeriesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Series>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId)
            emitAll(
                combine(
                    seriesDao.getByCategoryPreview(providerId, categoryId, limit),
                    preferencesRepository.parentalControlLevel
                ) { entities, level: Int ->
                    if (level >= 3) {
                        entities.filter { !it.isUserProtected }
                    } else {
                        entities
                    }
                }.map { list -> list.map { it.toDomain() } }
            )
        }

    override fun getCategoryPreviewRows(providerId: Long, categoryIds: List<Long>, limitPerCategory: Int): Flow<Map<Long?, List<Series>>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            preferencesRepository.parentalControlLevel
        ) { categories, level ->
            val requestedIds = categoryIds.toSet()
            val filtered = if (level >= 3) categories.filter { !it.isAdult && !it.isUserProtected } else categories
            filtered.filter { it.categoryId in requestedIds } to level
        }.flatMapLatest { (filteredCategories, level) ->
            if (filteredCategories.isEmpty()) {
                flowOf(emptyMap())
            } else channelFlow {
                val provider = providerDao.getById(providerId)
                val previewCategories = if (provider?.type == ProviderType.STALKER_PORTAL) {
                    val stalkerProvider = createStalkerProvider(providerId, provider)
                    filteredCategories.filterNot { category ->
                        stalkerProvider.isWildcardCategory(ContentType.SERIES, category.categoryId)
                    }
                } else {
                    filteredCategories
                }
                if (previewCategories.isEmpty()) {
                    send(emptyMap())
                    return@channelFlow
                }
                if (provider != null &&
                    (provider.type == ProviderType.XTREAM_CODES || provider.type == ProviderType.STALKER_PORTAL)
                ) {
                    previewCategories.forEach { category ->
                        launch(Dispatchers.IO) {
                            ensureXtreamCategoryLoaded(
                                providerId = providerId,
                                categoryId = category.categoryId,
                                requiredCount = limitPerCategory,
                                refreshStaleInBackground = false,
                                allowStalkerWildcard = false
                            )
                        }
                    }
                }
                val categoryGroupFlows: List<Flow<Pair<Long?, List<Series>>>> = previewCategories.map { cat ->
                    seriesDao.getByCategoryPreview(providerId, cat.categoryId, limitPerCategory)
                        .map { entities ->
                            val items = if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            (cat.categoryId as Long?) to items.map { it.toDomain() }
                        }
                }
                combine(categoryGroupFlows) { pairs ->
                    pairs.associate { it.first to it.second }
                }.collect { previews ->
                    send(previews)
                }
            }
        }

    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getTopRatedPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getFreshPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getSeriesByIds(ids: List<Long>): Flow<List<Series>> =
        seriesDao.getByIds(ids).map { entities -> entities.map { it.toDomain() } }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            preferencesRepository.parentalControlLevel
        ) { entities: List<CategoryEntity>, level: Int ->
            val mapped = entities.map { it.toDomain() }
            if (level >= 3) {
                mapped.filter { !it.isAdult && !it.isUserProtected }
            } else {
                mapped
            }
        }

    override fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>> =
        seriesDao.getCategoryCounts(providerId).map { counts ->
            counts.associate { it.categoryId to it.item_count }
        }

    override fun getLibraryCount(providerId: Long): Flow<Int> =
        seriesDao.getCount(providerId)

    override fun browseSeries(query: LibraryBrowseQuery): Flow<PagedResult<Series>> {
        return flow {
            query.categoryId?.let { ensureXtreamCategoryLoaded(query.providerId, it, browseFetchLimit(query)) }
            emit(fetchSeriesBrowseResult(query))
        }
    }

    override fun searchSeries(providerId: Long, query: String): Flow<List<Series>> =
        query.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }?.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isNullOrBlank()) {
            flowOf(emptyList())
            } else combine(
                safeSeriesSearchFlow(seriesDao.search(providerId, ftsQuery, SEARCH_RESULT_LIMIT)),
                preferencesRepository.parentalControlLevel
            ) { entities, level: Int ->
                if (level >= 3) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list ->
                list.map { it.toDomain() }
                    .rankSearchResults(query) { it.name }
            }.combine(favoriteDao.getAllByType(providerId, ContentType.SERIES.name)) { series, favorites ->
                val favoriteIds = favorites.map { it.contentId }.toSet()
                series.map { if (it.id in favoriteIds) it.copy(isFavorite = true) else it }
            }
        }

    override suspend fun getSeriesById(seriesId: Long): Series? =
        seriesDao.getById(seriesId)?.toDomain()

    override suspend fun getSeriesDetails(providerId: Long, seriesId: Long): Result<Series> {
        val seriesEntity = seriesDao.getById(seriesId)
            ?: seriesDao.getBySeriesId(providerId, seriesId)
            ?: return Result.error("Series not found")

        val provider = providerDao.getById(providerId)
            ?: return Result.error("Provider not found")

        if (seriesEntity.seriesId <= 0L) {
            return Result.success(buildSeriesWithPersistedEpisodes(seriesEntity))
        }

        val remoteResult = try {
            when (provider.type) {
                ProviderType.XTREAM_CODES -> getOrCreateXtreamProvider(providerId, provider).getSeriesInfo(seriesEntity.seriesId)
                ProviderType.STALKER_PORTAL -> createStalkerProvider(providerId, provider).getSeriesInfo(
                    seriesEntity.providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesEntity.seriesId.toString()
                )
                ProviderType.M3U -> return Result.success(buildSeriesWithPersistedEpisodes(seriesEntity))
            }
        } catch (e: Exception) {
            return Result.error(e.message ?: "Failed to access provider credentials", e)
        }

        return when (remoteResult) {
            is Result.Success -> {
                val remoteSeries = remoteResult.data

                val updatedSeries = seriesEntity.copy(
                    name = remoteSeries.name.ifBlank { seriesEntity.name },
                    posterUrl = remoteSeries.posterUrl ?: seriesEntity.posterUrl,
                    backdropUrl = remoteSeries.backdropUrl ?: seriesEntity.backdropUrl,
                    categoryId = remoteSeries.categoryId ?: seriesEntity.categoryId,
                    categoryName = remoteSeries.categoryName ?: seriesEntity.categoryName,
                    plot = remoteSeries.plot ?: seriesEntity.plot,
                    cast = remoteSeries.cast ?: seriesEntity.cast,
                    director = remoteSeries.director ?: seriesEntity.director,
                    genre = remoteSeries.genre ?: seriesEntity.genre,
                    releaseDate = remoteSeries.releaseDate ?: seriesEntity.releaseDate,
                    rating = if (remoteSeries.rating > 0f) remoteSeries.rating else seriesEntity.rating,
                    tmdbId = remoteSeries.tmdbId ?: seriesEntity.tmdbId,
                    youtubeTrailer = remoteSeries.youtubeTrailer ?: seriesEntity.youtubeTrailer,
                    episodeRunTime = remoteSeries.episodeRunTime ?: seriesEntity.episodeRunTime,
                    lastModified = if (remoteSeries.lastModified > 0) remoteSeries.lastModified else seriesEntity.lastModified,
                    providerSeriesId = remoteSeries.providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesEntity.providerSeriesId
                )
                seriesDao.update(updatedSeries)

                val episodesToPersist = remoteSeries.seasons
                    .flatMap { season ->
                        season.episodes.map { episode ->
                            val remoteEpisodeId = episode.episodeId.takeIf { it > 0 } ?: episode.id
                            episode.copy(
                                id = 0,
                                episodeId = remoteEpisodeId,
                                seasonNumber = if (episode.seasonNumber > 0) episode.seasonNumber else season.seasonNumber,
                                seriesId = seriesEntity.id,
                                providerId = providerId
                            ).toEntity().copy(
                                id = 0,
                                episodeId = remoteEpisodeId,
                                seriesId = seriesEntity.id,
                                providerId = providerId
                            )
                        }
                    }

                if (episodesToPersist.isNotEmpty()) {
                    episodeDao.replaceAll(seriesEntity.id, providerId, episodesToPersist)
                }

                val persistedSeries = seriesDao.getById(seriesEntity.id) ?: updatedSeries
                val persistedEpisodes = episodeDao.getBySeriesSync(seriesEntity.id).map { it.toDomain() }
                val persistedByRemoteEpisodeId = persistedEpisodes.associateBy {
                    it.episodeId.takeIf { remoteId -> remoteId > 0 } ?: it.id
                }

                val mergedSeasons = if (remoteSeries.seasons.isNotEmpty()) {
                    remoteSeries.seasons
                        .sortedBy { it.seasonNumber }
                        .map { remoteSeason ->
                            val mergedEpisodes = remoteSeason.episodes.map { remoteEpisode ->
                                val remoteEpisodeId = remoteEpisode.episodeId.takeIf { it > 0 } ?: remoteEpisode.id
                                persistedByRemoteEpisodeId[remoteEpisodeId] ?: remoteEpisode.copy(
                                    episodeId = remoteEpisodeId,
                                    seriesId = seriesEntity.id,
                                    providerId = providerId
                                )
                            }
                            remoteSeason.copy(
                                episodes = mergedEpisodes,
                                episodeCount = mergedEpisodes.size
                            )
                        }
                } else {
                    persistedEpisodes.groupBy { it.seasonNumber }
                        .entries
                        .sortedBy { it.key }
                        .map { (seasonNumber, episodes) ->
                            Season(
                                seasonNumber = seasonNumber,
                                name = "Season $seasonNumber",
                                episodes = episodes,
                                episodeCount = episodes.size
                            )
                        }
                }

                Result.success(
                    persistedSeries.toDomain().copy(seasons = mergedSeasons)
                )
            }
            is Result.Error -> {
                val localSeries = buildSeriesWithPersistedEpisodes(seriesEntity)
                Result.success(localSeries)
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getEpisodeStreamInfo(episode: Episode): Result<StreamInfo> = try {
        xtreamStreamUrlResolver.resolveWithMetadata(
            url = episode.streamUrl,
            fallbackProviderId = episode.providerId,
            fallbackStreamId = episode.episodeId.takeIf { it > 0 } ?: episode.id,
            fallbackContentType = ContentType.SERIES_EPISODE,
            fallbackContainerExtension = episode.containerExtension
        )?.let { resolvedStream ->
            val ext = resolvedStream.containerExtension ?: episode.containerExtension
            Result.success(
                StreamInfo(
                    url = resolvedStream.url,
                    title = episode.title,
                    headers = resolvedStream.headers,
                    userAgent = resolvedStream.userAgent,
                    streamType = StreamType.fromContainerExtension(ext),
                    containerExtension = ext,
                    expirationTime = resolvedStream.expirationTime
                )
            )
        } ?: Result.error("No stream URL available for episode: ${episode.title}")
    } catch (e: Exception) {
        Result.error(e.message ?: "Failed to resolve stream URL for episode: ${episode.title}", e)
    }

    override suspend fun refreshSeries(providerId: Long): Result<Unit> =
        Result.success(Unit) // Handled by ProviderRepository

    override suspend fun updateEpisodeWatchProgress(episodeId: Long, progress: Long): Result<Unit> = try {
        episodeDao.updateWatchProgress(episodeId, progress)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update episode watch progress", e)
    }

    private suspend fun buildSeriesWithPersistedEpisodes(seriesEntity: SeriesEntity): Series {
        val episodes = episodeDao.getBySeriesSync(seriesEntity.id).map { it.toDomain() }
        val seasons = episodes.groupBy { it.seasonNumber }
            .entries
            .sortedBy { it.key }
            .map { (seasonNumber, seasonEpisodes) ->
                Season(
                    seasonNumber = seasonNumber,
                    name = "Season $seasonNumber",
                    episodes = seasonEpisodes,
                    episodeCount = seasonEpisodes.size
                )
            }
        return seriesEntity.toDomain().copy(seasons = seasons)
    }

    private fun seriesBrowseSource(query: LibraryBrowseQuery): Flow<List<Series>> {
        val normalizedSearch = query.searchQuery.trim()
        val fetchLimit = browseFetchLimit(query)
        val fastFlow = when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> {
                val ftsQuery = normalizedSearch.toFtsPrefixQuery() ?: return flowOf(emptyList())
                query.categoryId?.let { categoryId ->
                    combine(
                        safeSeriesSearchFlow(seriesDao.searchByCategory(query.providerId, categoryId, ftsQuery, SEARCH_RESULT_LIMIT)),
                        preferencesRepository.parentalControlLevel
                    ) { entities, level ->
                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                    }.map { entities ->
                        entities.map { it.toDomain() }
                            .rankSearchResults(normalizedSearch) { it.name }
                    }
                } ?: searchSeries(query.providerId, normalizedSearch)
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                when {
                    query.sortBy == LibrarySortBy.RATING || query.filterBy.type == LibraryFilterType.TOP_RATED -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                                emitAll(
                                    combine(
                                        seriesDao.getTopRatedByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: getTopRatedPreview(query.providerId, fetchLimit)
                    }
                    query.sortBy == LibrarySortBy.RELEASE || query.sortBy == LibrarySortBy.UPDATED || query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                                emitAll(
                                    combine(
                                        seriesDao.getFreshByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: getFreshPreview(query.providerId, fetchLimit)
                    }
                    else -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                                emitAll(
                                    combine(
                                        seriesDao.getByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            seriesDao.getByProviderPage(query.providerId, fetchLimit, 0),
                            preferencesRepository.parentalControlLevel
                        ) { entities, level ->
                            if (level >= 3) entities.filter { !it.isUserProtected } else entities
                        }.map { entities -> entities.map { it.toDomain() } }
                    }
                }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                        emitAll(
                            combine(
                                seriesDao.getFavoritesByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getFavoritesByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                        emitAll(
                            combine(
                                seriesDao.getInProgressByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getInProgressByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                        emitAll(
                            combine(
                                seriesDao.getUnwatchedByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getUnwatchedByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                        emitAll(
                            combine(
                                seriesDao.getByWatchCountCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getByWatchCountProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            else -> null
        }

        val categoryId = query.categoryId
        return fastFlow ?: flow {
            categoryId?.let { ensureXtreamCategoryLoaded(query.providerId, it, fetchLimit) }
            val source = if (categoryId == null) {
                seriesDao.getByProviderPage(query.providerId, fetchLimit, 0)
            } else {
                seriesDao.getByCategoryPage(query.providerId, categoryId, fetchLimit, 0)
            }
            emitAll(
                combine(
                    source,
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            )
        }
    }

    private fun seriesBrowseTotalCount(query: LibraryBrowseQuery): Flow<Int> {
        val normalizedSearch = query.searchQuery.trim()
        return when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> seriesBrowseSource(query).map { it.size }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getFavoriteCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getFavoriteCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getInProgressCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getInProgressCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getUnwatchedCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getUnwatchedCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getCount(query.providerId)
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getCount(query.providerId)
            }
            else -> seriesBrowseSource(query).map { it.size }
        }
    }

    private fun browseFetchLimit(query: LibraryBrowseQuery): Int =
        (query.offset + query.limit + BROWSE_WINDOW_BUFFER).coerceAtMost(SEARCH_RESULT_LIMIT)

    private suspend fun fetchSeriesBrowseResult(query: LibraryBrowseQuery): PagedResult<Series> {
        val totalCount = seriesBrowseTotalCount(query).first()
        val favoriteIds = favoriteDao.getAllByType(query.providerId, ContentType.SERIES.name)
            .first()
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()

        val items = if (supportsCursorBrowse(query)) {
            fetchSeriesCursorWindow(query, favoriteIds)
        } else {
            val series = seriesBrowseSource(query).first()
            val history = playbackHistoryDao.getByProvider(query.providerId).first()
            val inProgressIds = history
                .asSequence()
                .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                .filter {
                    it.resumePositionMs > 0L && (
                        it.totalDurationMs <= 0L ||
                            it.resumePositionMs < (it.totalDurationMs * 0.95f).toLong()
                        )
                }
                .mapNotNull { it.seriesId ?: it.contentId }
                .toSet()
            val watchCounts = history
                .asSequence()
                .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                .groupBy { it.seriesId ?: it.contentId }
                .mapValues { (_, entries) -> entries.maxOf { it.watchCount } }

            applySeriesBrowseQuery(
                series = series,
                query = query,
                favoriteIds = favoriteIds,
                inProgressIds = inProgressIds,
                watchCounts = watchCounts
            ).drop(query.offset).take(query.limit)
        }

        val hasMoreRemote = query.categoryId?.let { categoryId ->
            val provider = providerDao.getById(query.providerId)
            if (provider?.type == ProviderType.STALKER_PORTAL) {
                seriesCategoryHydrationDao.get(query.providerId, categoryId)?.let { !it.isComplete } ?: false
            } else {
                false
            }
        } ?: false

        return PagedResult(
            items = items,
            totalCount = totalCount,
            offset = query.offset,
            limit = query.limit,
            hasMoreRemote = hasMoreRemote
        )
    }

    private suspend fun fetchSeriesCursorWindow(
        query: LibraryBrowseQuery,
        favoriteIds: Set<Long>
    ): List<Series> {
        val parentalLevel = preferencesRepository.parentalControlLevel.first()
        val targetVisibleCount = (query.offset + query.limit).coerceAtLeast(query.limit)
        val collected = ArrayList<Series>(targetVisibleCount)

        when {
            query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                collectSeriesPages<NameCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { NameCursor(it.name, it.id) }
                ) { limit, cursor ->
                    loadSeriesNamePage(query, limit, cursor)
                }
            }
            (query.sortBy == LibrarySortBy.RATING || query.filterBy.type == LibraryFilterType.TOP_RATED) -> {
                collectSeriesPages<RatingCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { RatingCursor(it.rating, it.name, it.id) }
                ) { limit, cursor ->
                    loadSeriesRatingPage(query, limit, cursor)
                }
            }
            query.sortBy == LibrarySortBy.RELEASE ||
                query.sortBy == LibrarySortBy.UPDATED ||
                query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                collectSeriesPages<FreshCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { FreshCursor(it.lastModified, it.name, it.id) }
                ) { limit, cursor ->
                    loadSeriesFreshPage(query, limit, cursor)
                }
            }
        }

        return collected.drop(query.offset).take(query.limit)
    }

    private suspend fun <C> collectSeriesPages(
        query: LibraryBrowseQuery,
        parentalLevel: Int,
        collected: MutableList<Series>,
        favoriteIds: Set<Long>,
        extractCursor: (SeriesBrowseEntity) -> C,
        loadPage: suspend (limit: Int, cursor: C?) -> List<SeriesBrowseEntity>
    ) {
        var cursor: C? = null
        val targetVisibleCount = query.offset + query.limit
        while (collected.size < targetVisibleCount) {
            val batch = loadPage(CURSOR_BATCH_SIZE, cursor)
            if (batch.isEmpty()) {
                return
            }
            val visibleBatch = if (parentalLevel >= 3) {
                batch.filterNot { it.isUserProtected }
            } else {
                batch
            }
            collected += visibleBatch.map { entity ->
                val item = entity.toDomain()
                if (item.id in favoriteIds) item.copy(isFavorite = true) else item
            }
            if (batch.size < CURSOR_BATCH_SIZE) {
                return
            }
            cursor = extractCursor(batch.last())
        }
    }

    private suspend fun loadSeriesNamePage(query: LibraryBrowseQuery, limit: Int, cursor: NameCursor?): List<SeriesBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) seriesDao.getByProviderCursorPage(query.providerId, limit)
            else seriesDao.getByProviderCursorPageAfter(query.providerId, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) seriesDao.getByCategoryCursorPage(query.providerId, categoryId, limit)
            else seriesDao.getByCategoryCursorPageAfter(query.providerId, categoryId, cursor.name, cursor.id, limit)
        }
    }

    private suspend fun loadSeriesRatingPage(query: LibraryBrowseQuery, limit: Int, cursor: RatingCursor?): List<SeriesBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) seriesDao.getTopRatedCursorPage(query.providerId, limit)
            else seriesDao.getTopRatedCursorPageAfter(query.providerId, cursor.rating, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) seriesDao.getTopRatedByCategoryCursorPage(query.providerId, categoryId, limit)
            else seriesDao.getTopRatedByCategoryCursorPageAfter(query.providerId, categoryId, cursor.rating, cursor.name, cursor.id, limit)
        }
    }

    private suspend fun loadSeriesFreshPage(query: LibraryBrowseQuery, limit: Int, cursor: FreshCursor?): List<SeriesBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) seriesDao.getFreshCursorPage(query.providerId, limit)
            else seriesDao.getFreshCursorPageAfter(query.providerId, cursor.lastModified, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) seriesDao.getFreshByCategoryCursorPage(query.providerId, categoryId, limit)
            else seriesDao.getFreshByCategoryCursorPageAfter(query.providerId, categoryId, cursor.lastModified, cursor.name, cursor.id, limit)
        }
    }

    private fun supportsCursorBrowse(query: LibraryBrowseQuery): Boolean {
        if (query.searchQuery.isNotBlank()) return false
        return when {
            query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy in setOf(
                    LibrarySortBy.LIBRARY,
                    LibrarySortBy.TITLE,
                    LibrarySortBy.RELEASE,
                    LibrarySortBy.UPDATED,
                    LibrarySortBy.RATING
                ) -> true
            query.filterBy.type == LibraryFilterType.TOP_RATED -> true
            query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> true
            else -> false
        }
    }

    private fun applySeriesBrowseQuery(
        series: List<Series>,
        query: LibraryBrowseQuery,
        favoriteIds: Set<Long>,
        inProgressIds: Set<Long>,
        watchCounts: Map<Long, Int>
    ): List<Series> {
        val withFavoriteState = series.map { item -> item.copy(isFavorite = item.id in favoriteIds) }
        val filtered = withFavoriteState.filter { item ->
            seriesMatchesFilter(item, query.filterBy.type, inProgressIds) && seriesMatchesSearch(item, query.searchQuery)
        }

        val sorted = when (query.sortBy) {
            LibrarySortBy.LIBRARY -> filtered
            LibrarySortBy.TITLE -> filtered.sortedBy { it.name.lowercase() }
            LibrarySortBy.RELEASE, LibrarySortBy.UPDATED -> filtered.sortedByDescending(::seriesFreshnessScore)
            LibrarySortBy.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySortBy.WATCH_COUNT -> filtered.sortedByDescending { watchCounts[it.id] ?: 0 }
        }

        return if (query.searchQuery.isBlank() || query.sortBy != LibrarySortBy.LIBRARY) {
            sorted
        } else {
            sorted.rankSearchResults(query.searchQuery) { it.name }
        }
    }

    private fun seriesMatchesFilter(series: Series, filterType: LibraryFilterType, inProgressIds: Set<Long>): Boolean = when (filterType) {
        LibraryFilterType.ALL -> true
        LibraryFilterType.FAVORITES -> series.isFavorite
        LibraryFilterType.IN_PROGRESS -> series.id in inProgressIds
        LibraryFilterType.UNWATCHED -> series.id !in inProgressIds
        LibraryFilterType.TOP_RATED -> series.rating > 0f
        LibraryFilterType.RECENTLY_UPDATED -> seriesFreshnessScore(series) > 0L
    }

    private fun seriesMatchesSearch(series: Series, searchQuery: String): Boolean {
        val normalizedQuery = searchQuery.trim().lowercase()
        if (normalizedQuery.isBlank()) return true
        return sequenceOf(series.name, series.genre, series.categoryName)
            .filterNotNull()
            .any { value -> value.lowercase().contains(normalizedQuery) }
    }

    private fun safeSeriesSearchFlow(source: Flow<List<SeriesBrowseEntity>>): Flow<List<SeriesBrowseEntity>> =
        source.catch { error ->
            if (error is SQLiteException) {
                emit(emptyList<SeriesBrowseEntity>())
            } else {
                throw error
            }
        }

    private suspend fun ensureXtreamCategoryLoaded(
        providerId: Long,
        categoryId: Long,
        requiredCount: Int = SEARCH_RESULT_LIMIT,
        refreshStaleInBackground: Boolean = true,
        allowStalkerWildcard: Boolean = true
    ) {
        val key = "$providerId:$categoryId"
        val provider = providerDao.getById(providerId) ?: return
        if (provider.type != ProviderType.XTREAM_CODES && provider.type != ProviderType.STALKER_PORTAL) return

        val localCount = seriesDao.getCountByCategory(providerId, categoryId).first()
        val hydration = seriesCategoryHydrationDao.get(providerId, categoryId)
        if (provider.type == ProviderType.STALKER_PORTAL) {
            hydrateStalkerSeriesCategoryToCount(
                providerId = providerId,
                categoryId = categoryId,
                provider = provider,
                requiredCount = requiredCount,
                localCount = localCount,
                hydration = hydration,
                allowWildcard = allowStalkerWildcard
            )
            return
        }
        val isFresh = hydration?.isFresh() == true && (localCount > 0 || hydration.itemCount == 0)

        if (isFresh) {
            loadedXtreamCategories.add(key)
            return
        }

        // Stale but has cached data — show immediately, refresh in background
        if (localCount > 0 && refreshStaleInBackground) {
            loadedXtreamCategories.add(key)
            triggerSeriesCategoryHydration(providerId, categoryId, provider, requiredCount)
            return
        }

        if (localCount > 0) {
            loadedXtreamCategories.add(key)
            return
        }

        // No data — fetch inline so the user sees something right away
        hydrateSeriesCategory(providerId, categoryId, provider)
    }

    private fun triggerSeriesCategoryHydration(
        providerId: Long,
        categoryId: Long,
        provider: ProviderEntity,
        requiredCount: Int = SEARCH_RESULT_LIMIT,
        allowStalkerWildcard: Boolean = true
    ) {
        val key = "$providerId:$categoryId"
        if (!backgroundRefreshes.add(key)) return
        repositoryScope.launch {
            try {
                val localCount = seriesDao.getCountByCategory(providerId, categoryId).first()
                val hydration = seriesCategoryHydrationDao.get(providerId, categoryId)
                if (localCount == 0 && hydration?.isEmptyRetryCoolingDown() == true) return@launch
                if (provider.type == ProviderType.STALKER_PORTAL) {
                    hydrateStalkerSeriesCategoryToCount(
                        providerId = providerId,
                        categoryId = categoryId,
                        provider = provider,
                        requiredCount = requiredCount,
                        localCount = localCount,
                        hydration = hydration,
                        allowWildcard = allowStalkerWildcard
                    )
                } else {
                    hydrateSeriesCategory(providerId, categoryId, provider)
                }
            } finally {
                backgroundRefreshes.remove(key)
            }
        }
    }

    private suspend fun hydrateSeriesCategory(
        providerId: Long,
        categoryId: Long,
        provider: ProviderEntity
    ) {
        val key = "$providerId:$categoryId"
        val lock = xtreamCategoryLoadLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            val localCount = seriesDao.getCountByCategory(providerId, categoryId).first()
            val hydration = seriesCategoryHydrationDao.get(providerId, categoryId)
            if (hydration?.isFresh() == true && (localCount > 0 || hydration.itemCount == 0)) {
                loadedXtreamCategories.add(key)
                return
            }

            runCatching {
                val result = withXtreamSeriesCategoryTimeout(categoryId) {
                    if (provider.type == ProviderType.XTREAM_CODES) {
                        getOrCreateXtreamProvider(providerId, provider).getSeriesList(categoryId)
                    } else {
                        return@withXtreamSeriesCategoryTimeout Success(emptyList())
                    }
                }
                when (result) {
                    is Success -> {
                        val entities = result.data.map { item -> item.toEntity() }
                        val lazyXtreamHydration = shouldUsePersistedCategoryHydration(provider)
                        if (lazyXtreamHydration && entities.isEmpty()) {
                            seriesCategoryHydrationDao.upsert(
                                SeriesCategoryHydrationEntity(
                                    providerId = providerId,
                                    categoryId = categoryId,
                                    lastHydratedAt = System.currentTimeMillis(),
                                    itemCount = localCount,
                                    lastStatus = "EMPTY_RETRY",
                                    lastError = "Xtream category returned an empty result; retry pending.",
                                    lastLoadedPage = 0,
                                    totalPages = 0,
                                    isComplete = false,
                                    pageSize = 0
                                )
                            )
                            loadedXtreamCategories.remove(key)
                        } else {
                            seriesDao.replaceCategory(providerId, categoryId, entities)
                            episodeDao.deleteOrphans()
                            seriesCategoryHydrationDao.upsert(
                                SeriesCategoryHydrationEntity(
                                    providerId = providerId,
                                    categoryId = categoryId,
                                    lastHydratedAt = System.currentTimeMillis(),
                                    itemCount = entities.size,
                                    lastStatus = "SUCCESS",
                                    lastError = null,
                                    lastLoadedPage = 1,
                                    totalPages = 1,
                                    isComplete = true,
                                    pageSize = entities.size
                                )
                            )
                            loadedXtreamCategories.add(key)
                        }
                    }
                    else -> Unit
                }
            }.onFailure { error ->
                val currentCount = seriesDao.getCountByCategory(providerId, categoryId).first()
                seriesCategoryHydrationDao.upsert(
                    SeriesCategoryHydrationEntity(
                        providerId = providerId,
                        categoryId = categoryId,
                        lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                        itemCount = currentCount,
                        lastStatus = "ERROR",
                        lastError = error.message,
                        lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                        totalPages = hydration?.totalPages ?: 0,
                        isComplete = hydration?.isComplete ?: false,
                        pageSize = hydration?.pageSize ?: 0
                    )
                )
            }
        }
    }

    private suspend fun hydrateStalkerSeriesCategoryToCount(
        providerId: Long,
        categoryId: Long,
        provider: ProviderEntity,
        requiredCount: Int,
        localCount: Int? = null,
        hydration: SeriesCategoryHydrationEntity? = null,
        allowWildcard: Boolean = true
    ) {
        val key = "$providerId:$categoryId"
        val lock = xtreamCategoryLoadLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            val stalkerProvider = createStalkerProvider(providerId, provider)
            if (!allowWildcard && stalkerProvider.isWildcardCategory(ContentType.SERIES, categoryId)) return
            var currentCount = localCount ?: seriesDao.getCountByCategory(providerId, categoryId).first()
            var currentHydration = hydration ?: seriesCategoryHydrationDao.get(providerId, categoryId)
            if (currentHydration?.isComplete == true || currentCount >= requiredCount) return
            if (currentCount == 0 && currentHydration?.isEmptyRetryCoolingDown() == true) return

            val isPreviewLoad = requiredCount <= STALKER_PREVIEW_REQUIRED_COUNT_THRESHOLD
            var nextPage = ((currentHydration?.lastLoadedPage ?: 0) + 1).coerceAtLeast(1)
            // The cached totalPages can under-report when the preview hydrate stored
            // it from a partial response. Skip the pre-fetch guard on the first
            // iteration so we always perform at least one real fetch that refreshes
            // totalPages; subsequent iterations use the in-loop updated value.
            var firstIteration = true
            while (currentCount < requiredCount) {
                if (isPreviewLoad && nextPage > STALKER_PREVIEW_MAX_REMOTE_PAGES) break
                val totalPages = currentHydration?.totalPages ?: 0
                if (!firstIteration && totalPages > 0 && nextPage > totalPages) break
                firstIteration = false
                when (val result = stalkerProvider.getSeriesListPage(categoryId, nextPage)) {
                    is Success -> {
                        val entities = result.data.items.map { series -> series.toEntity() }
                        val pageComplete = result.data.isComplete || entities.isEmpty()
                        seriesDao.upsertCategoryPage(providerId, entities)
                        currentCount = seriesDao.getCountByCategory(providerId, categoryId).first()
                        currentHydration = SeriesCategoryHydrationEntity(
                            providerId = providerId,
                            categoryId = categoryId,
                            lastHydratedAt = System.currentTimeMillis(),
                            itemCount = currentCount,
                            lastStatus = "SUCCESS",
                            lastError = null,
                            lastLoadedPage = result.data.page,
                            totalPages = result.data.totalPages,
                            isComplete = pageComplete,
                            pageSize = result.data.pageSize
                        )
                        seriesCategoryHydrationDao.upsert(currentHydration!!)
                        if (pageComplete) break
                        nextPage = result.data.page + 1
                    }
                    is Result.Error -> {
                        seriesCategoryHydrationDao.upsert(
                            SeriesCategoryHydrationEntity(
                                providerId = providerId,
                                categoryId = categoryId,
                                lastHydratedAt = currentHydration?.lastHydratedAt ?: 0L,
                                itemCount = currentCount,
                                lastStatus = "ERROR",
                                lastError = result.message,
                                lastLoadedPage = currentHydration?.lastLoadedPage ?: 0,
                                totalPages = currentHydration?.totalPages ?: 0,
                                isComplete = currentHydration?.isComplete ?: false,
                                pageSize = currentHydration?.pageSize ?: 0
                            )
                        )
                        break
                    }
                    is Result.Loading -> break
                }
            }
        }
    }

    private fun SeriesCategoryHydrationEntity.isFresh(now: Long = System.currentTimeMillis()): Boolean {
        if (lastStatus != "SUCCESS") return false
        return !ContentCachePolicy.shouldRefresh(lastHydratedAt, ContentCachePolicy.SERIES_CATEGORY_TTL_MILLIS, now)
    }

    private fun SeriesCategoryHydrationEntity.isEmptyRetryCoolingDown(now: Long = System.currentTimeMillis()): Boolean {
        if (lastStatus != "EMPTY_RETRY") return false
        return !ContentCachePolicy.shouldRefresh(lastHydratedAt, XTREAM_EMPTY_CATEGORY_RETRY_COOLDOWN_MILLIS, now)
    }

    private fun shouldUsePersistedCategoryHydration(provider: ProviderEntity): Boolean {
        return provider.type == ProviderType.XTREAM_CODES && provider.xtreamFastSyncEnabled
    }

    private suspend fun <T> withXtreamSeriesCategoryTimeout(
        categoryId: Long,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(XTREAM_SERIES_CATEGORY_TIMEOUT_MILLIS) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out after 60 seconds while loading series category $categoryId", e)
        }
    }

    private fun seriesFreshnessScore(series: Series): Long =
        series.lastModified
            .takeIf { it > 0L }
            ?: series.releaseDate
                ?.filter { it.isDigit() }
                ?.take(8)
                ?.toLongOrNull()
            ?: 0L

    private suspend fun getOrCreateXtreamProvider(providerId: Long, provider: ProviderEntity): XtreamProvider {
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val signature = listOf(
            provider.serverUrl,
            provider.username,
            provider.password,
            enableBase64TextCompatibility.toString()
        ).joinToString("\u0000")
        return requireNotNull(
            xtreamProviderCache.compute(providerId) { _, cached ->
                if (cached != null && cached.signature == signature) {
                    cached
                } else {
                    val decryptedPassword = credentialCrypto.decryptIfNeeded(provider.password)
                    CachedXtreamProvider(
                        signature = signature,
                        provider = XtreamProvider(
                            providerId = providerId,
                            api = xtreamApiService,
                            serverUrl = provider.serverUrl,
                            username = provider.username,
                            password = decryptedPassword,
                            allowedOutputFormats = provider.toDomain().allowedOutputFormats,
                            enableBase64TextCompatibility = enableBase64TextCompatibility
                        )
                    )
                }
            }
        ) { "Provider cache compute returned null for providerId=$providerId" }.provider
    }

    private fun createStalkerProvider(providerId: Long, provider: ProviderEntity): StalkerProvider {
        return StalkerProvider(
            providerId = providerId,
            api = stalkerApiService,
            portalUrl = provider.serverUrl,
            macAddress = provider.stalkerMacAddress,
            deviceProfile = provider.stalkerDeviceProfile,
            timezone = provider.stalkerDeviceTimezone,
            locale = provider.stalkerDeviceLocale
        )
    }
}
