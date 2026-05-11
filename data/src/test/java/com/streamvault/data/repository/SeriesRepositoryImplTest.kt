package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesBrowseEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.stalker.StalkerCategoryRecord
import com.streamvault.data.remote.stalker.StalkerEpisodeRecord
import com.streamvault.data.remote.stalker.StalkerItemRecord
import com.streamvault.data.remote.stalker.StalkerPagedItems
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerSeasonRecord
import com.streamvault.data.remote.stalker.StalkerSeriesDetails
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibraryFilterBy
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.PlaybackHistoryRepository
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class SeriesRepositoryImplTest {

    private val seriesDao: SeriesDao = mock()
    private val episodeDao: EpisodeDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val playbackHistoryDao: PlaybackHistoryDao = mock()
    private val playbackHistoryRepository: PlaybackHistoryRepository = mock()
    private val providerDao: ProviderDao = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao = mock()
    private val xtreamContentIndexDao: XtreamContentIndexDao = mock()
    private val syncManager: SyncManager = mock()
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }

    @Test
    fun `getSeriesByCategory does not hydrate xtream category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
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
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)

        val repository = createRepository()

        val result = repository.getSeriesByCategory(7L, 77L).first()

        assertThat(result).isEmpty()
        verify(syncManager).prioritizeXtreamIndexCategory(7L, ContentType.SERIES, 77L)
        verify(seriesDao, never()).replaceCategory(eq(7L), eq(77L), any())
        verify(xtreamApiService, never()).getSeriesList(any(), any())
        verify(episodeDao, never()).deleteOrphans()
    }

    @Test
    fun `getSeriesDetails uses fresh xtream hydrated cache without refetching`() = runTest {
        val hydratedAt = System.currentTimeMillis()
        val seriesEntity = SeriesEntity(
            id = 99L,
            seriesId = 301L,
            name = "Cached Series",
            providerId = 7L,
            cacheState = "DETAIL_HYDRATED",
            detailHydratedAt = hydratedAt
        )
        whenever(seriesDao.getById(99L)).thenReturn(seriesEntity)
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
        whenever(episodeDao.getBySeriesSync(99L)).thenReturn(emptyList())

        val result = createRepository().getSeriesDetails(7L, 99L)

        assertThat(result.getOrNull()?.name).isEqualTo("Cached Series")
        verify(xtreamApiService, never()).getSeriesInfo(any(), any())
        verify(xtreamContentIndexDao, never()).markDetailHydrated(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `getSeriesByCategory lazily hydrates stalker category when local cache is empty`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
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
        whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )
        whenever(stalkerApiService.getSeriesPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = listOf(
                        StalkerItemRecord(
                            id = "301",
                            name = "Series",
                            categoryId = "77",
                            isSeries = true
                        )
                    ),
                    page = 1,
                    totalPages = 1,
                    pageSize = 1
                )
            )
        )
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        whenever(episodeDao.deleteOrphans()).thenReturn(0)

        val repository = createRepository()

        val result = repository.getSeriesByCategory(7L, 77L).first()

        assertThat(result).isEmpty()
        verify(seriesDao).upsertCategoryPage(eq(7L), any())
        verify(seriesDao, never()).replaceCategory(eq(7L), eq(77L), any())
        verifyNoInteractions(episodeDao)
    }

    @Test
    fun `stalker series preview loads only first page`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0), flowOf(18))
        whenever(seriesDao.getByCategoryPreview(7L, 77L, 18)).thenReturn(flowOf(emptyList()))
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        whenever(categoryDao.getByProviderAndType(7L, ContentType.SERIES.name)).thenReturn(
            flowOf(listOf(com.streamvault.data.local.entity.CategoryEntity(providerId = 7L, categoryId = 77L, name = "Drama", type = ContentType.SERIES)))
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
        whenever(stalkerApiService.getSeriesCategories(any(), any())).thenReturn(
            Result.success(listOf(StalkerCategoryRecord(id = "77", name = "Drama")))
        )
        whenever(stalkerApiService.getSeriesPage(any(), any(), anyOrNull(), eq(1))).thenReturn(
            Result.success(
                StalkerPagedItems(
                    items = (1..18).map { index ->
                        StalkerItemRecord(id = "30$index", name = "Series $index", categoryId = "77", isSeries = true)
                    },
                    page = 1,
                    totalPages = 2,
                    pageSize = 50
                )
            )
        )

        val repository = createRepository()

        repository.getCategoryPreviewRows(7L, listOf(77L), 18).first()

        verify(stalkerApiService).getSeriesPage(any(), any(), anyOrNull(), eq(1))
        verify(stalkerApiService, never()).getSeriesPage(any(), any(), anyOrNull(), eq(2))
    }

    @Test
    fun `browseSeries recently updated excludes stale entries and uses filtered total count`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getFreshCountByProvider(7L)).thenReturn(flowOf(1))
        whenever(seriesDao.getFreshPreview(7L, 100)).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(id = 1L, seriesId = 101L, name = "Fresh Series", providerId = 7L, lastModified = 10_000L),
                    SeriesBrowseEntity(id = 2L, seriesId = 102L, name = "Stale Series", providerId = 7L, lastModified = 0L)
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryDao.getByProvider(7L)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.browseSeries(
            LibraryBrowseQuery(
                providerId = 7L,
                sortBy = LibrarySortBy.UPDATED,
                filterBy = LibraryFilterBy(LibraryFilterType.RECENTLY_UPDATED),
                offset = 0,
                limit = 20
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(1)
        assertThat(result.items.map { it.name }).containsExactly("Fresh Series")
    }

    @Test
    fun `getSeriesByCategory skips legacy xtream empty retry hydration`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(seriesDao.getCountByCategory(7L, 77L)).thenReturn(flowOf(0))
        whenever(seriesDao.getByCategory(7L, 77L)).thenReturn(flowOf(emptyList()))
        whenever(seriesCategoryHydrationDao.get(7L, 77L)).thenReturn(null)
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                xtreamFastSyncEnabled = true,
                status = ProviderStatus.ACTIVE
            )
        )
        val repository = createRepository()

        repository.getSeriesByCategory(7L, 77L).first()

        verify(syncManager).prioritizeXtreamIndexCategory(7L, ContentType.SERIES, 77L)
        verify(seriesDao, never()).replaceCategory(eq(7L), eq(77L), any())
        verify(seriesCategoryHydrationDao, never()).upsert(any())
        verify(xtreamApiService, never()).getSeriesList(any(), any())
    }

    @Test
    fun `getSeriesDetails falls back to remote series id lookup`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Series",
            providerId = 7L
        )
        whenever(seriesDao.getById(301L)).thenReturn(null)
        whenever(seriesDao.getBySeriesId(7L, 301L)).thenReturn(seriesEntity)
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "http://example.com",
                username = "user",
                password = "pass",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())

        val repository = createRepository()

        val result = repository.getSeriesDetails(7L, 301L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.id).isEqualTo(15L)
        assertThat(series.seriesId).isEqualTo(301L)
    }

    @Test
    fun `getSeriesDetails returns local series when xtream details fail`() = runTest {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 301L,
            name = "Stored Series",
            posterUrl = "https://img.example.test/poster.jpg",
            providerId = 7L
        )
        whenever(seriesDao.getById(301L)).thenReturn(null)
        whenever(seriesDao.getBySeriesId(7L, 301L)).thenReturn(seriesEntity)
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
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())
        whenever(xtreamApiService.getSeriesInfo(any(), any())).thenThrow(RuntimeException("bad response"))

        val repository = createRepository()

        val result = repository.getSeriesDetails(7L, 301L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.id).isEqualTo(15L)
        assertThat(series.name).isEqualTo("Stored Series")
        assertThat(series.posterUrl).isEqualTo("https://img.example.test/poster.jpg")
    }

    @Test
    fun `getSeriesDetails uses provider series id for stalker details`() = runTest {
        val seriesEntity = SeriesEntity(
            id = 15L,
            seriesId = 256103980L,
            providerSeriesId = "55000:55000",
            name = "Stored Series",
            providerId = 7L
        )
        whenever(seriesDao.getById(15L)).thenReturn(seriesEntity)
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
        whenever(stalkerApiService.getSeriesDetails(any(), any(), eq("55000:55000"))).thenReturn(
            Result.success(
                StalkerSeriesDetails(
                    series = StalkerItemRecord(id = "55000:55000", name = "", isSeries = true),
                    seasons = listOf(
                        StalkerSeasonRecord(
                            seasonNumber = 1,
                            name = "Season 1",
                            episodes = listOf(
                                StalkerEpisodeRecord(
                                    id = "55000:1:1",
                                    title = "Episode 1",
                                    episodeNumber = 1,
                                    seasonNumber = 1,
                                    cmd = "cmd"
                                )
                            )
                        )
                    )
                )
            )
        )
        whenever(episodeDao.getBySeriesSync(15L)).thenReturn(emptyList())

        val repository = createRepository()

        val result = repository.getSeriesDetails(7L, 15L)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        val series = (result as com.streamvault.domain.model.Result.Success).data
        assertThat(series.name).isEqualTo("Stored Series")
        verify(stalkerApiService).getSeriesDetails(any(), any(), eq("55000:55000"))
        verifyNoInteractions(xtreamApiService)
        verify(xtreamContentIndexDao, never()).markDetailHydrated(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `searchSeries returns empty list when sqlite throws for malformed fts query`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.search(eq(7L), any(), any())).thenReturn(
            flow { throw SQLiteException("malformed MATCH expression") }
        )
        whenever(seriesDao.searchFallback(eq(7L), any(), any())).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(
                        id = 15L,
                        seriesId = 1500L,
                        name = "Drama House",
                        providerId = 7L
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchSeries(7L, "drama").first()

        assertThat(result.map { it.name }).containsExactly("Drama House")
    }

    @Test
    fun `searchSeries returns empty without like fallback when fts has no rows`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.search(eq(7L), any(), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchSeries(7L, "drama").first()

        assertThat(result).isEmpty()
        verify(seriesDao, never()).searchFallback(eq(7L), any(), any())
    }

    @Test
    fun `searchSeries does not run like fallback when fts returns rows`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.search(eq(7L), any(), any())).thenReturn(
            flowOf(
                listOf(
                    SeriesBrowseEntity(
                        id = 17L,
                        seriesId = 1700L,
                        name = "Drama",
                        providerId = 7L
                    )
                )
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchSeries(7L, "drama").first()

        assertThat(result.map { it.name }).containsExactly("Drama")
        verify(seriesDao, never()).searchFallback(eq(7L), any(), any())
    }

    @Test
    fun `browseSeries search uses bounded page query`() = runTest {
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(seriesDao.searchPage(eq(7L), any(), any(), any(), any(), any(), any())).thenReturn(
            listOf(
                SeriesBrowseEntity(id = 17L, seriesId = 1700L, name = "Drama", providerId = 7L),
                SeriesBrowseEntity(id = 18L, seriesId = 1800L, name = "Drama Island", providerId = 7L)
            )
        )
        whenever(favoriteDao.getAllByType(7L, ContentType.SERIES.name)).thenReturn(flowOf(emptyList()))

        val result = createRepository().browseSeries(
            LibraryBrowseQuery(
                providerId = 7L,
                searchQuery = "drama",
                offset = 0,
                limit = 1
            )
        ).first()

        assertThat(result.totalCount).isEqualTo(2)
        assertThat(result.items.map { it.name }).containsExactly("Drama")
        verify(seriesDao, times(1)).searchPage(eq(7L), any(), any(), any(), any(), eq(2), eq(0))
        verify(seriesDao, never()).search(eq(7L), any(), any())
        verify(seriesDao, never()).searchFallback(eq(7L), any(), any())
    }

    private fun createRepository() = SeriesRepositoryImpl(
        seriesDao = seriesDao,
        episodeDao = episodeDao,
        categoryDao = categoryDao,
        favoriteDao = favoriteDao,
        playbackHistoryDao = playbackHistoryDao,
        playbackHistoryRepository = playbackHistoryRepository,
        providerDao = providerDao,
        stalkerApiService = stalkerApiService,
        xtreamApiService = xtreamApiService,
        credentialCrypto = credentialCrypto,
        preferencesRepository = preferencesRepository,
        xtreamStreamUrlResolver = xtreamStreamUrlResolver,
        xtreamContentIndexDao = xtreamContentIndexDao,
        syncManager = syncManager,
        seriesCategoryHydrationDao = seriesCategoryHydrationDao
    )
}
