package com.streamvault.app.ui.screens.vod

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.Movie
import org.junit.Test

class VodBrowseCacheTest {
    private var nowMs = 0L

    private val selectedKey = VodSelectedCategoryCacheKey(
        providerId = 1L,
        categoryName = "Action",
        filterType = LibraryFilterType.ALL,
        sortBy = LibrarySortBy.LIBRARY,
        searchQuery = "",
        loadLimit = VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE,
    )

    private val previewKey = VodPreviewCatalogCacheKey(
        providerId = 1L,
        batchSize = 6,
        searchQuery = "",
    )

    @Test
    fun `selected category cache returns snapshot within ttl`() {
        val cache = VodSelectedCategoryBrowseCache<Movie>(nowMs = { nowMs })
        val snapshot = CachedVodSelectedCategory(
            items = emptyList(),
            loadedCount = 0,
            totalCount = 0,
            canLoadMore = false,
            savedAtMs = nowMs,
        )

        cache.put(selectedKey, snapshot)

        assertThat(cache.peek(selectedKey)).isEqualTo(snapshot)
        assertThat(cache.isStale(selectedKey)).isFalse()
    }

    @Test
    fun `selected category cache expires after ttl`() {
        val cache = VodSelectedCategoryBrowseCache<Movie>(
            ttlMs = 1_000L,
            nowMs = { nowMs },
        )
        cache.put(
            selectedKey,
            CachedVodSelectedCategory(
                items = emptyList(),
                loadedCount = 0,
                totalCount = 0,
                canLoadMore = false,
                savedAtMs = nowMs,
            )
        )

        nowMs = 1_500L

        assertThat(cache.peek(selectedKey)).isNull()
        assertThat(cache.isStale(selectedKey)).isTrue()
    }

    @Test
    fun `preview catalog cache clears entries for provider`() {
        val cache = VodPreviewCatalogBrowseCache<Movie>(nowMs = { nowMs })
        cache.put(
            previewKey,
            CachedVodPreviewCatalog(
                itemsByCategory = emptyMap(),
                categoryNames = emptyList(),
                categoryCounts = emptyMap(),
                libraryCount = 0,
                hasMorePreviewRows = false,
                savedAtMs = nowMs,
            )
        )

        cache.clearForProvider(1L)

        assertThat(cache.peek(previewKey)).isNull()
    }
}
