package com.streamvault.data.repository

import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.MovieRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.streamvault.data.util.toFtsPrefixQuery
import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import javax.inject.Singleton

@Singleton
class MovieRepositoryImpl @Inject constructor(
    private val movieDao: MovieDao,
    private val categoryDao: CategoryDao,
    private val preferencesRepository: PreferencesRepository,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : MovieRepository {

    override fun getMovies(providerId: Long): Flow<List<Movie>> =
        preferencesRepository.parentalControlLevel.flatMapLatest { level ->
            if (level == 2) movieDao.getByProviderUnprotected(providerId)
            else movieDao.getByProvider(providerId)
        }.map { list -> list.map { it.toDomain() } }

    override fun getMoviesByCategory(providerId: Long, categoryId: Long): Flow<List<Movie>> =
        preferencesRepository.parentalControlLevel.flatMapLatest { level ->
            if (level == 2) movieDao.getByCategoryUnprotected(providerId, categoryId)
            else movieDao.getByCategory(providerId, categoryId)
        }.map { list -> list.map { it.toDomain() } }

    override fun getMoviesByCategoryPage(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<Movie>> =
        combine(
            movieDao.getByCategoryPage(providerId, categoryId, limit, offset),
            preferencesRepository.parentalControlLevel
        ) { entities: List<MovieEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getMoviesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            movieDao.getByCategoryPreview(providerId, categoryId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities: List<MovieEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getCategoryPreviewRows(providerId: Long, limitPerCategory: Int): Flow<Map<Long?, List<Movie>>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.MOVIE.name),
            preferencesRepository.parentalControlLevel
        ) { categories, level ->
            val filtered = if (level == 2) categories.filter { !it.isAdult && !it.isUserProtected } else categories
            filtered to level
        }.flatMapLatest { (filteredCategories, level) ->
            if (filteredCategories.isEmpty()) {
                flowOf(emptyMap())
            } else {
                // SQL LIMIT applied per-category — avoids loading the full catalog into memory
                val categoryGroupFlows: List<Flow<Pair<Long?, List<Movie>>>> = filteredCategories.map { cat ->
                    movieDao.getByCategoryPreview(providerId, cat.categoryId, limitPerCategory)
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

    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            movieDao.getTopRatedPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities: List<MovieEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            movieDao.getFreshPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities: List<MovieEntity>, level: Int ->
            if (level == 2) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }

    override fun getMoviesByIds(ids: List<Long>): Flow<List<Movie>> =
        movieDao.getByIds(ids).map { entities -> entities.map { it.toDomain() } }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.MOVIE.name),
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
        movieDao.getCategoryCounts(providerId).map { counts ->
            counts.associate { it.categoryId to it.item_count }
        }

    override fun getLibraryCount(providerId: Long): Flow<Int> =
        movieDao.getCount(providerId)

    override fun browseMovies(query: LibraryBrowseQuery): Flow<PagedResult<Movie>> {
        val categoryId = query.categoryId
        val pageFlow = if (query.categoryId == null) {
            movieDao.getByProviderPage(query.providerId, query.limit, query.offset)
        } else {
            movieDao.getByCategoryPage(query.providerId, categoryId!!, query.limit, query.offset)
        }
        val countFlow = if (query.categoryId == null) {
            movieDao.getCount(query.providerId)
        } else {
            movieDao.getCountByCategory(query.providerId, categoryId!!)
        }
        return combine(pageFlow, countFlow, preferencesRepository.parentalControlLevel) { entities, totalCount, level ->
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

    override fun searchMovies(providerId: Long, query: String): Flow<List<Movie>> =
        query.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isBlank()) {
            flowOf(emptyList())
            } else combine(
                movieDao.search(providerId, ftsQuery),
                preferencesRepository.parentalControlLevel
            ) { entities: List<MovieEntity>, level: Int ->
                if (level == 2) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list -> list.map { it.toDomain() } }
        }

    override suspend fun getMovie(movieId: Long): Movie? =
        movieDao.getById(movieId)?.toDomain()

    override suspend fun getMovieDetails(providerId: Long, movieId: Long): Result<Movie> {
        val movie = movieDao.getById(movieId)?.toDomain()
        return if (movie != null) Result.success(movie) else Result.error("Movie not found")
    }

    @Deprecated("Use getStreamInfo() instead", ReplaceWith("getStreamInfo(movie)"))
    override suspend fun getStreamUrl(movie: Movie): Result<String> =
        xtreamStreamUrlResolver.resolve(
            url = movie.streamUrl,
            fallbackProviderId = movie.providerId,
            fallbackStreamId = movie.streamId,
            fallbackContentType = ContentType.MOVIE,
            fallbackContainerExtension = movie.containerExtension
        )?.let { resolvedUrl ->
            Result.success(resolvedUrl)
        } ?: Result.error("No stream URL available for movie: ${movie.name}")

    override suspend fun refreshMovies(providerId: Long): Result<Unit> =
        Result.success(Unit) // Handled by ProviderRepository

    override suspend fun updateWatchProgress(movieId: Long, progress: Long) {
        movieDao.updateWatchProgress(movieId, progress)
    }
}
