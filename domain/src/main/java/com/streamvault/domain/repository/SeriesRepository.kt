package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.flow.Flow

interface SeriesRepository {
    fun getSeries(providerId: Long): Flow<List<Series>>
    fun getSeriesByCategory(providerId: Long, categoryId: Long): Flow<List<Series>>
    fun getSeriesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<Series>>
    fun getSeriesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Series>>
    fun getCategoryPreviewRows(providerId: Long, limitPerCategory: Int): Flow<Map<Long?, List<Series>>>
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Series>>
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Series>>
    fun getSeriesByIds(ids: List<Long>): Flow<List<Series>>
    fun getCategories(providerId: Long): Flow<List<Category>>
    fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>>
    fun getLibraryCount(providerId: Long): Flow<Int>
    fun browseSeries(query: LibraryBrowseQuery): Flow<PagedResult<Series>>
    fun searchSeries(providerId: Long, query: String): Flow<List<Series>>
    suspend fun getSeriesById(seriesId: Long): Series?
    suspend fun getSeriesDetails(providerId: Long, seriesId: Long): Result<Series>
    @Deprecated("Use getEpisodeStreamInfo() for richer metadata", replaceWith = ReplaceWith("getEpisodeStreamInfo(episode)"))
    suspend fun getEpisodeStreamUrl(episode: Episode): Result<String>
    suspend fun getEpisodeStreamInfo(episode: Episode): Result<StreamInfo> =
        getEpisodeStreamUrl(episode).map { StreamInfo(it) }
    suspend fun refreshSeries(providerId: Long): Result<Unit>
    suspend fun updateEpisodeWatchProgress(episodeId: Long, progress: Long)
}
