package com.streamvault.app.ui.screens.search

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.usecase.SearchContentResult
import com.streamvault.domain.usecase.SearchContentScope
import org.junit.Test

class SearchResultsCacheTest {

    private val key = SearchCacheKey(
        providerId = 1L,
        normalizedQuery = "news",
        scope = SearchContentScope.ALL
    )

    private val sampleResult = SearchContentResult(
        channels = listOf(Channel(id = 10L, name = "News", streamUrl = "http://stream", providerId = 1L))
    )

    @Test
    fun `returns fresh cached search results within ttl`() {
        var now = 1_000L
        val cache = SearchResultsCache(nowMs = { now })

        cache.put(key, sampleResult)

        assertThat(cache.getFresh(key)?.channels?.map { it.id }).containsExactly(10L)
        assertThat(cache.getStale(key)?.channels?.map { it.id }).containsExactly(10L)
    }

    @Test
    fun `expires cached search results after ttl`() {
        var now = 0L
        val cache = SearchResultsCache(
            ttlMs = 1_000L,
            nowMs = { now }
        )

        cache.put(key, sampleResult)
        now = 1_500L

        assertThat(cache.getFresh(key)).isNull()
        assertThat(cache.getStale(key)?.channels?.map { it.id }).containsExactly(10L)
    }

    @Test
    fun `evicts least recently used entries when over capacity`() {
        val cache = SearchResultsCache(maxEntries = 2)
        val firstKey = SearchCacheKey(1L, "one", SearchContentScope.ALL)
        val secondKey = SearchCacheKey(1L, "two", SearchContentScope.ALL)
        val thirdKey = SearchCacheKey(1L, "three", SearchContentScope.ALL)

        cache.put(firstKey, sampleResult)
        cache.put(secondKey, sampleResult)
        cache.getFresh(firstKey)
        cache.put(thirdKey, sampleResult)

        assertThat(cache.getStale(firstKey)).isNotNull()
        assertThat(cache.getStale(secondKey)).isNull()
        assertThat(cache.getStale(thirdKey)).isNotNull()
    }

    @Test
    fun `clearProvider removes only matching provider entries`() {
        val cache = SearchResultsCache()
        val otherProviderKey = SearchCacheKey(2L, "news", SearchContentScope.LIVE)

        cache.put(key, sampleResult)
        cache.put(otherProviderKey, sampleResult)

        cache.clearProvider(1L)

        assertThat(cache.getStale(key)).isNull()
        assertThat(cache.getStale(otherProviderKey)).isNotNull()
    }
}
