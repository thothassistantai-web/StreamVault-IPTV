package com.streamvault.data.repository

import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.*
import com.streamvault.data.mapper.*
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.streamvault.data.util.toFtsPrefixQuery
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import javax.inject.Singleton

@Singleton
class SeriesRepositoryImpl @Inject constructor(
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val categoryDao: CategoryDao,
    private val providerDao: ProviderDao,
    private val xtreamApiService: XtreamApiService,
    private val preferencesRepository: PreferencesRepository,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : SeriesRepository {

    private data class CachedXtreamProvider(
        val signature: String,
        val provider: XtreamProvider
    )

    private val xtreamProviderCache = ConcurrentHashMap<Long, CachedXtreamProvider>()

    override fun getSeries(providerId: Long): Flow<List<Series>> =
        combine(
            seriesDao.getByProvider(providerId),
            preferencesRepository.parentalControlLevel
        ) { entities: List<SeriesEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list: List<SeriesEntity> -> list.map { it.toDomain() } }

    override fun getSeriesByCategory(providerId: Long, categoryId: Long): Flow<List<Series>> =
        combine(
            seriesDao.getByCategory(providerId, categoryId),
            preferencesRepository.parentalControlLevel
        ) { entities: List<SeriesEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list: List<SeriesEntity> -> list.map { it.toDomain() } }

    override fun getSeriesByCategoryPage(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<Series>> =
        combine(
            seriesDao.getByCategoryPage(providerId, categoryId, limit, offset),
            preferencesRepository.parentalControlLevel
        ) { entities: List<SeriesEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list: List<SeriesEntity> -> list.map { it.toDomain() } }

    override fun getSeriesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getByCategoryPreview(providerId, categoryId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities: List<SeriesEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list: List<SeriesEntity> -> list.map { it.toDomain() } }

    override fun getCategoryPreviewRows(providerId: Long, limitPerCategory: Int): Flow<Map<Long?, List<Series>>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            preferencesRepository.parentalControlLevel
        ) { categories, level ->
            val filtered = if (level == 2) categories.filter { !it.isAdult && !it.isUserProtected } else categories
            filtered to level
        }.flatMapLatest { (filteredCategories, level) ->
            if (filteredCategories.isEmpty()) {
                flowOf(emptyMap())
            } else {
                // SQL LIMIT applied per-category — avoids loading the full catalog into memory
                val categoryGroupFlows: List<Flow<Pair<Long?, List<Series>>>> = filteredCategories.map { cat ->
                    seriesDao.getByCategoryPreview(providerId, cat.categoryId, limitPerCategory)
                        .map { entities ->
                            val items = if (level == 2) entities.filter { !it.isUserProtected } else entities
                            (cat.categoryId as Long?) to items.map { it.toDomain() }
                        }
                }
                combine(categoryGroupFlows) { pairs ->
                    pairs.associate { it.first to it.second }
                }
            }
        }

    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getTopRatedPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities: List<SeriesEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list: List<SeriesEntity> -> list.map { it.toDomain() } }

    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getFreshPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities: List<SeriesEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list: List<SeriesEntity> -> list.map { it.toDomain() } }

    override fun getSeriesByIds(ids: List<Long>): Flow<List<Series>> =
        seriesDao.getByIds(ids).map { entities -> entities.map { it.toDomain() } }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            preferencesRepository.parentalControlLevel
        ) { entities: List<CategoryEntity>, level: Int ->
            val mapped = entities.map { it.toDomain() }
            if (level == 2) {
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
        val categoryId = query.categoryId
        val pageFlow = if (query.categoryId == null) {
            seriesDao.getByProviderPage(query.providerId, query.limit, query.offset)
        } else {
            seriesDao.getByCategoryPage(query.providerId, categoryId!!, query.limit, query.offset)
        }
        val countFlow = if (query.categoryId == null) {
            seriesDao.getCount(query.providerId)
        } else {
            seriesDao.getCountByCategory(query.providerId, categoryId!!)
        }
        return combine(pageFlow, countFlow, preferencesRepository.parentalControlLevel) { entities: List<SeriesEntity>, totalCount: Int, level: Int ->
            val filtered = if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
            PagedResult(
                items = filtered.map { it.toDomain() },
                totalCount = totalCount,
                offset = query.offset,
                limit = query.limit
            )
        }
    }

    override fun searchSeries(providerId: Long, query: String): Flow<List<Series>> =
        query.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isBlank()) {
            flowOf(emptyList())
            } else combine(
                seriesDao.search(providerId, ftsQuery),
                preferencesRepository.parentalControlLevel
            ) { entities: List<SeriesEntity>, level: Int ->
                if (level == 2) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list: List<SeriesEntity> -> list.map { it.toDomain() } }
        }

    override suspend fun getSeriesById(seriesId: Long): Series? =
        seriesDao.getById(seriesId)?.toDomain()

    override suspend fun getSeriesDetails(providerId: Long, seriesId: Long): Result<Series> {
        val seriesEntity = seriesDao.getById(seriesId)
            ?: return Result.error("Series not found")

        val provider = providerDao.getById(providerId)
            ?: return Result.error("Provider not found")

        // M3U and other non-Xtream providers have no standardized series-detail endpoint.
        if (provider.type != ProviderType.XTREAM_CODES) {
            return Result.success(buildSeriesWithPersistedEpisodes(seriesEntity))
        }

        val xtreamProvider = getOrCreateXtreamProvider(providerId, provider)

        return when (val remoteResult = xtreamProvider.getSeriesInfo(seriesEntity.seriesId)) {
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
                    lastModified = if (remoteSeries.lastModified > 0) remoteSeries.lastModified else seriesEntity.lastModified
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
                if (localSeries.seasons.isNotEmpty()) {
                    Result.success(localSeries)
                } else {
                    Result.error(remoteResult.message, remoteResult.exception)
                }
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    @Deprecated("Use getEpisodeStreamInfo() instead", ReplaceWith("getEpisodeStreamInfo(episode)"))
    override suspend fun getEpisodeStreamUrl(episode: Episode): Result<String> =
        xtreamStreamUrlResolver.resolve(
            url = episode.streamUrl,
            fallbackProviderId = episode.providerId,
            fallbackStreamId = episode.episodeId.takeIf { it > 0 } ?: episode.id,
            fallbackContentType = ContentType.SERIES_EPISODE,
            fallbackContainerExtension = episode.containerExtension
        )?.let { resolvedUrl ->
            Result.success(resolvedUrl)
        } ?: Result.error("No stream URL available for episode: ${episode.title}")

    override suspend fun refreshSeries(providerId: Long): Result<Unit> =
        Result.success(Unit) // Handled by ProviderRepository

    override suspend fun updateEpisodeWatchProgress(episodeId: Long, progress: Long) {
        episodeDao.updateWatchProgress(episodeId, progress)
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

    private fun getOrCreateXtreamProvider(providerId: Long, provider: ProviderEntity): XtreamProvider {
        val decryptedPassword = CredentialCrypto.decryptIfNeeded(provider.password)
        val signature = listOf(provider.serverUrl, provider.username, decryptedPassword).joinToString("\u0000")
        val cached = xtreamProviderCache[providerId]
        if (cached != null && cached.signature == signature) {
            return cached.provider
        }

        return XtreamProvider(
            providerId = providerId,
            api = xtreamApiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = decryptedPassword
        ).also { xtreamProvider ->
            xtreamProviderCache[providerId] = CachedXtreamProvider(signature, xtreamProvider)
        }
    }
}
