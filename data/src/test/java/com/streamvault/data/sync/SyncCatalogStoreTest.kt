package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.CategoryImportStageEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelImportStageEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SyncCatalogStoreTest {

    private val channelDao: ChannelDao = mock()
    private val movieDao: MovieDao = mock()
    private val seriesDao: SeriesDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val catalogSyncDao: CatalogSyncDao = mock()
    private val tmdbIdentityDao: TmdbIdentityDao = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun store(sizeLimits: CatalogSizeLimits = CatalogSizeLimits()) = SyncCatalogStore(
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        categoryDao = categoryDao,
        catalogSyncDao = catalogSyncDao,
        tmdbIdentityDao = tmdbIdentityDao,
        transactionRunner = transactionRunner,
        sizeLimits = sizeLimits
    )

    @Before
    fun setup() {
        runBlocking {
            whenever(movieDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
            whenever(seriesDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
        }
    }

    @Test
    fun `replaceLiveCatalog batches changed category and channel updates`() = runTest {
        val providerId = 7L
        val currentCategory = CategoryEntity(
            id = 11L,
            categoryId = 101L,
            name = "News",
            type = ContentType.LIVE,
            providerId = providerId,
            syncFingerprint = "old-category"
        )
        val currentChannel = ChannelEntity(
            id = 21L,
            streamId = 1001L,
            name = "Old News",
            providerId = providerId,
            streamUrl = "https://old.example.com/live",
            syncFingerprint = "old-channel"
        )

        whenever(categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)).thenReturn(listOf(currentCategory))
        whenever(channelDao.getByProviderSync(providerId)).thenReturn(listOf(currentChannel))
        whenever(catalogSyncDao.getCategoryStages(eq(providerId), any(), eq(ContentType.LIVE.name))).thenReturn(
            listOf(
                CategoryImportStageEntity(
                    sessionId = 1L,
                    providerId = providerId,
                    categoryId = 101L,
                    name = "World News",
                    type = ContentType.LIVE,
                    syncFingerprint = "new-category"
                )
            )
        )

        store().replaceLiveCatalog(
            providerId = providerId,
            categories = listOf(currentCategory.copy(name = "World News")),
            channels = listOf(currentChannel.copy(name = "World News HD"))
        )

        val updatedCategories = argumentCaptor<List<CategoryEntity>>()
        verify(categoryDao).updateAll(updatedCategories.capture())
        assertThat(updatedCategories.firstValue.single()).isEqualTo(
            currentCategory.copy(
                name = "World News",
                syncFingerprint = "new-category"
            )
        )

        verify(catalogSyncDao).updateChangedChannelsFromStage(eq(providerId), any())
    }

    @Test
    fun `applyStagedMovieCatalog batches changed movie updates`() = runTest {
        val providerId = 7L
        val sessionId = 33L
        store().applyStagedMovieCatalog(providerId, sessionId, categories = null)

        verify(catalogSyncDao).updateChangedMoviesFromStage(providerId, sessionId)
        verify(movieDao).restoreWatchProgress(providerId)
    }

    @Test
    fun `applyStagedSeriesCatalog batches changed series updates`() = runTest {
        val providerId = 7L
        val sessionId = 44L
        store().applyStagedSeriesCatalog(providerId, sessionId, categories = null)

        verify(catalogSyncDao).updateChangedSeriesFromStage(providerId, sessionId)
    }

    @Test
    fun `replaceCategories clears inherited protection when a protected category is remapped`() = runTest {
        val providerId = 7L
        val currentCategory = CategoryEntity(
            id = 51L,
            categoryId = 909L,
            name = "Kids",
            type = ContentType.MOVIE,
            providerId = providerId,
            isUserProtected = true,
            syncFingerprint = "old-category"
        )
        whenever(categoryDao.getByProviderAndTypeSync(providerId, ContentType.MOVIE.name)).thenReturn(listOf(currentCategory))
        whenever(catalogSyncDao.getCategoryStages(eq(providerId), any(), eq(ContentType.MOVIE.name))).thenReturn(
            listOf(
                CategoryImportStageEntity(
                    sessionId = 1L,
                    providerId = providerId,
                    categoryId = 909L,
                    name = "Documentaries",
                    type = ContentType.MOVIE,
                    syncFingerprint = "new-category"
                )
            )
        )

        store().replaceCategories(
            providerId = providerId,
            type = ContentType.MOVIE.name,
            categories = listOf(currentCategory.copy(name = "Documentaries", isUserProtected = false))
        )

        val updatedCategories = argumentCaptor<List<CategoryEntity>>()
        verify(categoryDao).updateAll(updatedCategories.capture())
        assertThat(updatedCategories.firstValue.single()).isEqualTo(
            currentCategory.copy(
                name = "Documentaries",
                isUserProtected = false,
                syncFingerprint = "new-category"
            )
        )
        verify(movieDao).clearProtectionForCategories(providerId, listOf(909L))
        verify(channelDao, never()).clearProtectionForCategories(any(), any())
        verify(seriesDao, never()).clearProtectionForCategories(any(), any())
    }

    @Test
    fun `replaceLiveCatalog keeps lowest numbered channels within configured budget`() = runTest {
        val providerId = 9L
        whenever(categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)).thenReturn(emptyList())
        whenever(channelDao.getByProviderSync(providerId)).thenReturn(emptyList())
        whenever(catalogSyncDao.getChannelStages(eq(providerId), any())).thenReturn(emptyList())

        val limitedStore = store(CatalogSizeLimits(maxChannelsPerProvider = 2))
        val channels = listOf(
            ChannelEntity(streamId = 1001L, name = "News", number = 50, providerId = providerId, streamUrl = "https://example.com/50"),
            ChannelEntity(streamId = 1002L, name = "Sports", number = 10, providerId = providerId, streamUrl = "https://example.com/10"),
            ChannelEntity(streamId = 1003L, name = "Movies", number = 30, providerId = providerId, streamUrl = "https://example.com/30")
        )

        val acceptedCount = limitedStore.replaceLiveCatalog(providerId, categories = null, channels = channels)

        assertThat(acceptedCount).isEqualTo(2)
        val insertedChannels = argumentCaptor<List<ChannelImportStageEntity>>()
        verify(catalogSyncDao).insertChannelStages(insertedChannels.capture())
        assertThat(insertedChannels.firstValue.map { it.streamId }).containsExactly(1002L, 1003L).inOrder()
    }

    @Test
    fun `replaceMovieCatalog keeps highest rated movies within configured budget`() = runTest {
        val providerId = 11L
        whenever(movieDao.getByProviderSync(providerId)).thenReturn(emptyList())
        whenever(catalogSyncDao.getMovieStages(eq(providerId), any())).thenReturn(emptyList())

        val limitedStore = store(CatalogSizeLimits(maxMoviesPerProvider = 2))
        val movies = sequenceOf(
            MovieEntity(streamId = 2001L, name = "Movie A", providerId = providerId, streamUrl = "https://example.com/a", rating = 4.2f),
            MovieEntity(streamId = 2002L, name = "Movie B", providerId = providerId, streamUrl = "https://example.com/b", rating = 9.1f),
            MovieEntity(streamId = 2003L, name = "Movie C", providerId = providerId, streamUrl = "https://example.com/c", rating = 7.6f)
        )

        val acceptedCount = limitedStore.replaceMovieCatalog(providerId, categories = null, movies = movies)

        assertThat(acceptedCount).isEqualTo(2)
        val insertedMovies = argumentCaptor<List<MovieImportStageEntity>>()
        verify(catalogSyncDao).insertMovieStages(insertedMovies.capture())
        assertThat(insertedMovies.firstValue.map { it.streamId }).containsExactly(2002L, 2003L).inOrder()
    }
}
