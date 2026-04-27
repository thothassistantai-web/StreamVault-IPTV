package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.MovieBrowseEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.PlaybackHistoryLiteEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.stalker.StalkerDeviceProfile
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.stalker.StalkerCategoryRecord
import com.streamvault.data.remote.stalker.StalkerItemRecord
import com.streamvault.data.remote.stalker.StalkerPagedItems
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MovieRepositoryImplTest {

    private val movieDao: MovieDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val providerDao: ProviderDao = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val playbackHistoryDao: PlaybackHistoryDao = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    @Test
    fun `getRecommendations prioritizes movies similar to recent history`() = runTest {
        val watchedMovie = movieEntity(
            id = 1L,
            name = "Space Run",
            genre = "Action, Sci-Fi",
            categoryId = 10L,
            rating = 7.2f
        )
        val recommendedMovie = movieEntity(
            id = 2L,
            name = "Galaxy Pursuit",
            genre = "Sci-Fi Action",
            categoryId = 10L,
            rating = 8.8f
        )
        val unrelatedMovie = movieEntity(
            id = 3L,
            name = "Quiet Tea",
            genre = "Drama",
            categoryId = 11L,
            rating = 9.5f
        )

    whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
    whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getTopRatedPreview(7L, 48)).thenReturn(flowOf(listOf(recommendedMovie, unrelatedMovie, watchedMovie)))
        whenever(movieDao.getFreshPreview(7L, 48)).thenReturn(flowOf(listOf(recommendedMovie, unrelatedMovie, watchedMovie)))
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getRecentlyWatchedByProvider(eq(7L), eq(24))).thenReturn(
            flowOf(
                listOf(
                    PlaybackHistoryLiteEntity(
                        contentId = watchedMovie.id,
                        contentType = ContentType.MOVIE,
                        providerId = 7L,
                        title = watchedMovie.name,
                        lastWatchedAt = 1_000L,
                        resumePositionMs = 1_000L,
                        totalDurationMs = 10_000L
                    )
                )
            )
        )

        val repository = createRepository()

        val result = repository.getRecommendations(7L, limit = 5).first()

        assertThat(result.map { it.name }).containsExactly("Galaxy Pursuit", "Quiet Tea").inOrder()
    }

    @Test
    fun `getRelatedContent ranks shared genre ahead of category only matches`() = runTest {
        val targetMovie = movieEntity(
            id = 1L,
            name = "Space Run",
            genre = "Action, Sci-Fi",
            categoryId = 10L,
            rating = 7.2f
        )
        val closeMatch = movieEntity(
            id = 2L,
            name = "Galaxy Pursuit",
            genre = "Sci-Fi Action",
            categoryId = 10L,
            rating = 8.8f
        )
        val categoryOnly = movieEntity(
            id = 3L,
            name = "Harbor Escape",
            genre = "Thriller",
            categoryId = 10L,
            rating = 9.0f
        )

        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 10L)).thenReturn(flowOf(1))
        whenever(movieDao.getById(targetMovie.id)).thenReturn(
            movieRecord(
                id = targetMovie.id,
                name = targetMovie.name,
                genre = targetMovie.genre.orEmpty(),
                categoryId = targetMovie.categoryId ?: 0L,
                rating = targetMovie.rating
            )
        )
        whenever(movieDao.getByCategoryPreview(7L, 10L, 48)).thenReturn(flowOf(listOf(targetMovie, closeMatch, categoryOnly)))
        whenever(movieDao.getTopRatedPreview(7L, 32)).thenReturn(flowOf(listOf(closeMatch, categoryOnly)))

        val repository = createRepository()

        val result = repository.getRelatedContent(7L, targetMovie.id, limit = 5).first()

        assertThat(result.map { it.name }).containsExactly("Galaxy Pursuit", "Harbor Escape").inOrder()
    }

    @Test
    fun `getMoviesByCategory lazily hydrates xtream category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(xtreamApiService.getVodCategories(any())).thenReturn(
            listOf(XtreamCategory(categoryId = "42", categoryName = "Action"))
        )
        whenever(xtreamApiService.getVodStreams(any())).thenReturn(
            listOf(
                XtreamStream(
                    streamId = 101L,
                    name = "Movie",
                    categoryId = "42",
                    streamIcon = null,
                    containerExtension = "mp4"
                )
            )
        )

        val repository = createRepository()

        repository.getMoviesByCategory(7L, 42L).first()

        verify(movieDao).replaceCategory(eq(7L), eq(42L), any())
    }

    @Test
    fun `getMoviesByCategory marks empty xtream fast sync hydration for retry`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(syncMetadataRepository.getMetadata(7L)).thenReturn(
            SyncMetadata(providerId = 7L, movieSyncMode = VodSyncMode.LAZY_BY_CATEGORY)
        )
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(xtreamApiService.getVodCategories(any())).thenReturn(
            listOf(XtreamCategory(categoryId = "42", categoryName = "Action"))
        )
        whenever(xtreamApiService.getVodStreams(any())).thenReturn(emptyList())

        val repository = createRepository()

        repository.getMoviesByCategory(7L, 42L).first()

        verify(movieDao, never()).replaceCategory(eq(7L), eq(42L), any())
        val hydrationCaptor = argumentCaptor<com.streamvault.data.local.entity.MovieCategoryHydrationEntity>()
        verify(movieCategoryHydrationDao).upsert(hydrationCaptor.capture())
        assertThat(hydrationCaptor.firstValue.lastStatus).isEqualTo("EMPTY_RETRY")
        assertThat(hydrationCaptor.firstValue.itemCount).isEqualTo(0)
    }

    @Test
    fun `getMoviesByCategory lazily hydrates stalker category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0))
        whenever(movieDao.getByCategory(7L, 42L)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Stalker",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "http://example.com",
                stalkerMacAddress = "00:11:22:33:44:55",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "101",
                            name = "Movie",
                            categoryId = "42",
                            cmd = "ffmpeg http://example.com/movie.mp4",
                            containerExtension = "mp4"
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 1
                )
            )
        )

        val repository = createRepository()

        repository.getMoviesByCategory(7L, 42L).first()

        verify(movieDao).upsertCategoryPage(eq(7L), any())
        verify(movieDao, never()).replaceCategory(eq(7L), eq(42L), any())
    }

    @Test
    fun `stalker movie preview loads only first page`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.getCountByCategory(7L, 42L)).thenReturn(flowOf(0), flowOf(18))
        whenever(movieDao.getByCategoryPreview(7L, 42L, 18)).thenReturn(flowOf(emptyList()))
        whenever(movieCategoryHydrationDao.get(7L, 42L)).thenReturn(null)
        whenever(categoryDao.getByProviderAndType(7L, ContentType.MOVIE.name)).thenReturn(
            flowOf(listOf(com.streamvault.data.local.entity.CategoryEntity(providerId = 7L, categoryId = 42L, name = "Action", type = ContentType.MOVIE)))
        )
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Stalker",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "http://example.com",
                stalkerMacAddress = "00:11:22:33:44:55",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(stalkerApiService.authenticate(any())).thenReturn(
            Result.success(
                StalkerSession(
                    loadUrl = "http://example.com/stalker_portal/server/load.php",
                    portalReferer = "http://example.com/stalker_portal/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Stalker")
            )
        )
        whenever(stalkerApiService.getVodCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "42", name = "Action")))
        )
        whenever(stalkerApiService.getVodStreamsPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = (1..18).map { index ->
                        StalkerItemRecord(id = "10$index", name = "Movie $index", categoryId = "42")
                    },
                    page = 1,
                    totalPages = 2,
                    pageSize = 50
                )
            )
        )

        val repository = createRepository()

        repository.getCategoryPreviewRows(7L, listOf(42L), 18).first()

        verify(stalkerApiService).getVodStreamsPage(any(), any(), anyOrNull(), eq(1))
        verify(stalkerApiService, never()).getVodStreamsPage(any(), any(), anyOrNull(), eq(2))
    }

    @Test
    fun `getMovieDetails returns local data for stalker without full remote fetch`() = runTest {
        whenever(movieDao.getById(101L)).thenReturn(
            movieRecord(
                id = 101L,
                name = "Stored Movie",
                genre = "Action",
                categoryId = 42L,
                rating = 8.5f
            )
        )
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Stalker",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "http://example.com",
                stalkerMacAddress = "00:11:22:33:44:55",
                status = ProviderStatus.ACTIVE
            )
        )

        val repository = createRepository()

        val result = repository.getMovieDetails(7L, 101L)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.name).isEqualTo("Stored Movie")
        verify(stalkerApiService, never()).authenticate(any())
    }

    @Test
    fun `searchMovies returns empty list when sqlite throws for malformed fts query`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(movieDao.search(eq(7L), any(), any())).thenReturn(
            flow { throw SQLiteException("malformed MATCH expression") }
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.MOVIE.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchMovies(7L, "matrix").first()

        assertThat(result).isEmpty()
    }

    private fun createRepository() = MovieRepositoryImpl(
        movieDao = movieDao,
        categoryDao = categoryDao,
        providerDao = providerDao,
        stalkerApiService = stalkerApiService,
        xtreamApiService = xtreamApiService,
        credentialCrypto = credentialCrypto,
        preferencesRepository = preferencesRepository,
        favoriteDao = favoriteDao,
        playbackHistoryDao = playbackHistoryDao,
        xtreamStreamUrlResolver = xtreamStreamUrlResolver,
        movieCategoryHydrationDao = movieCategoryHydrationDao,
        syncMetadataRepository = syncMetadataRepository,
        transactionRunner = transactionRunner
    )

    private fun movieEntity(
        id: Long,
        name: String,
        genre: String,
        categoryId: Long,
        rating: Float
    ) = MovieBrowseEntity(
        id = id,
        streamId = id,
        name = name,
        genre = genre,
        categoryId = categoryId,
        providerId = 7L,
        rating = rating,
        streamUrl = "https://example.com/$id.m3u8"
    )

    private fun movieRecord(
        id: Long,
        name: String,
        genre: String,
        categoryId: Long,
        rating: Float
    ) = MovieEntity(
        id = id,
        streamId = id,
        name = name,
        genre = genre,
        categoryId = categoryId,
        providerId = 7L,
        rating = rating,
        streamUrl = "https://example.com/$id.m3u8"
    )
}
