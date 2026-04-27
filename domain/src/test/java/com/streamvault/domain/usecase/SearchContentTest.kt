package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchContentTest {

    @Test
    fun returnsCombinedResultsAcrossAllSections() = runTest {
        val useCase = SearchContent(
            channelRepository = FakeChannelRepository(
                searchResults = listOf(Channel(id = 1L, name = "News 1"))
            ),
            movieRepository = FakeMovieRepository(
                searchResults = listOf(Movie(id = 2L, name = "Movie 1"))
            ),
            seriesRepository = FakeSeriesRepository(
                searchResults = listOf(Series(id = 3L, name = "Series 1"))
            )
        )

        val result = useCase(providerId = 99L, query = "star").first()

        assertThat(result.channels).hasSize(1)
        assertThat(result.movies).hasSize(1)
        assertThat(result.series).hasSize(1)
    }

    @Test
    fun restrictsResultsToRequestedScope() = runTest {
        val useCase = SearchContent(
            channelRepository = FakeChannelRepository(
                searchResults = listOf(Channel(id = 1L, name = "News 1"))
            ),
            movieRepository = FakeMovieRepository(
                searchResults = listOf(Movie(id = 2L, name = "Movie 1"))
            ),
            seriesRepository = FakeSeriesRepository(
                searchResults = listOf(Series(id = 3L, name = "Series 1"))
            )
        )

        val result = useCase(
            providerId = 99L,
            query = "star",
            scope = SearchContentScope.MOVIES
        ).first()

        assertThat(result.channels).isEmpty()
        assertThat(result.movies).hasSize(1)
        assertThat(result.series).isEmpty()
    }

    @Test
    fun shortQueriesReturnEmptyResults() = runTest {
        val useCase = SearchContent(
            channelRepository = FakeChannelRepository(
                searchResults = listOf(Channel(id = 1L, name = "News 1"))
            ),
            movieRepository = FakeMovieRepository(
                searchResults = listOf(Movie(id = 2L, name = "Movie 1"))
            ),
            seriesRepository = FakeSeriesRepository(
                searchResults = listOf(Series(id = 3L, name = "Series 1"))
            )
        )

        val result = useCase(providerId = 99L, query = "a").first()

        assertThat(result.channels).isEmpty()
        assertThat(result.movies).isEmpty()
        assertThat(result.series).isEmpty()
    }

    @Test
    fun rethrows_non_io_upstream_failures() = runTest {
        val expected = IllegalStateException("channel search failed")
        val useCase = SearchContent(
            channelRepository = FakeChannelRepository(
                searchResults = emptyList(),
                searchFlow = flow { throw expected }
            ),
            movieRepository = FakeMovieRepository(searchResults = emptyList()),
            seriesRepository = FakeSeriesRepository(searchResults = emptyList())
        )

        val thrown = try {
            useCase(providerId = 99L, query = "star").first()
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown?.message).isEqualTo(expected.message)
    }
}

private class FakeChannelRepository(
    private val searchResults: List<Channel>,
    private val searchFlow: Flow<List<Channel>>? = null
) : ChannelRepository {
    override fun searchChannels(providerId: Long, query: String): Flow<List<Channel>> = searchFlow ?: flowOf(searchResults)
    override fun getChannels(providerId: Long): Flow<List<Channel>> = unsupported()
    override fun getChannelCount(providerId: Long): Flow<Int> = unsupported()
    override fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>> = unsupported()
    override fun getChannelsByCategoryPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<Channel>> = unsupported()
    override fun getChannelsByNumber(providerId: Long, categoryId: Long): Flow<List<Channel>> = unsupported()
    override fun getChannelsWithoutErrors(providerId: Long, categoryId: Long): Flow<List<Channel>> = unsupported()
    override fun getChannelsWithoutErrorsPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<Channel>> = unsupported()
    override suspend fun getChannelsByCategoryPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel> = error("Not used in test")
    override suspend fun getChannelsWithoutErrorsPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel> = error("Not used in test")
    override fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>> = unsupported()
    override fun searchChannelsByCategoryPaged(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<Channel>> = unsupported()
    override fun getCategories(providerId: Long): Flow<List<Category>> = unsupported()
    override suspend fun getChannel(channelId: Long): Channel? = error("Not used in test")
    override suspend fun getStreamInfo(channel: Channel, preferStableUrl: Boolean): Result<StreamInfo> = error("Not used in test")
    override suspend fun refreshChannels(providerId: Long): Result<Unit> = error("Not used in test")
    override fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>> = unsupported()
    override suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit> = error("Not used in test")
    override suspend fun resetChannelErrorCount(channelId: Long): Result<Unit> = error("Not used in test")
}

private class FakeMovieRepository(
    private val searchResults: List<Movie>,
    private val searchFlow: Flow<List<Movie>>? = null
) : MovieRepository {
    override fun searchMovies(providerId: Long, query: String): Flow<List<Movie>> = searchFlow ?: flowOf(searchResults)
    override fun getMovies(providerId: Long): Flow<List<Movie>> = unsupported()
    override fun getMoviesByCategory(providerId: Long, categoryId: Long): Flow<List<Movie>> = unsupported()
    override fun getMoviesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<Movie>> = unsupported()
    override fun getMoviesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Movie>> = unsupported()
    override fun getCategoryPreviewRows(providerId: Long, categoryIds: List<Long>, limitPerCategory: Int): Flow<Map<Long?, List<Movie>>> = unsupported()
    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Movie>> = unsupported()
    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Movie>> = unsupported()
    override fun getRecommendations(providerId: Long, limit: Int): Flow<List<Movie>> = unsupported()
    override fun getRelatedContent(providerId: Long, movieId: Long, limit: Int): Flow<List<Movie>> = unsupported()
    override fun getMoviesByIds(ids: List<Long>): Flow<List<Movie>> = unsupported()
    override fun getCategories(providerId: Long): Flow<List<Category>> = unsupported()
    override fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>> = unsupported()
    override fun getLibraryCount(providerId: Long): Flow<Int> = unsupported()
    override fun browseMovies(query: LibraryBrowseQuery): Flow<PagedResult<Movie>> = unsupported()
    override suspend fun getMovie(movieId: Long): Movie? = error("Not used in test")
    override suspend fun getMovieDetails(providerId: Long, movieId: Long): Result<Movie> = error("Not used in test")
    override suspend fun getStreamInfo(movie: Movie): Result<StreamInfo> = error("Not used in test")
    override suspend fun refreshMovies(providerId: Long): Result<Unit> = error("Not used in test")
    override suspend fun updateWatchProgress(movieId: Long, progress: Long): Result<Unit> = error("Not used in test")
}

private class FakeSeriesRepository(
    private val searchResults: List<Series>,
    private val searchFlow: Flow<List<Series>>? = null
) : SeriesRepository {
    override fun searchSeries(providerId: Long, query: String): Flow<List<Series>> = searchFlow ?: flowOf(searchResults)
    override fun getSeries(providerId: Long): Flow<List<Series>> = unsupported()
    override fun getSeriesByCategory(providerId: Long, categoryId: Long): Flow<List<Series>> = unsupported()
    override fun getSeriesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<Series>> = unsupported()
    override fun getSeriesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Series>> = unsupported()
    override fun getCategoryPreviewRows(providerId: Long, categoryIds: List<Long>, limitPerCategory: Int): Flow<Map<Long?, List<Series>>> = unsupported()
    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Series>> = unsupported()
    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Series>> = unsupported()
    override fun getSeriesByIds(ids: List<Long>): Flow<List<Series>> = unsupported()
    override fun getCategories(providerId: Long): Flow<List<Category>> = unsupported()
    override fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>> = unsupported()
    override fun getLibraryCount(providerId: Long): Flow<Int> = unsupported()
    override fun browseSeries(query: LibraryBrowseQuery): Flow<PagedResult<Series>> = unsupported()
    override suspend fun getSeriesById(seriesId: Long): Series? = error("Not used in test")
    override suspend fun getSeriesDetails(providerId: Long, seriesId: Long): Result<Series> = error("Not used in test")
    override suspend fun getEpisodeStreamInfo(episode: com.streamvault.domain.model.Episode): Result<StreamInfo> = error("Not used in test")
    override suspend fun refreshSeries(providerId: Long): Result<Unit> = error("Not used in test")
    override suspend fun updateEpisodeWatchProgress(episodeId: Long, progress: Long): Result<Unit> = error("Not used in test")
}

private fun <T> unsupported(): Flow<T> = error("Not used in test")
