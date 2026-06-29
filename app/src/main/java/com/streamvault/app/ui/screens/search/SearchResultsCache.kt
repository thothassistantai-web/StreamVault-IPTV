package com.streamvault.app.ui.screens.search

import com.streamvault.domain.usecase.SearchContentResult
import com.streamvault.domain.usecase.SearchContentScope

internal data class SearchCacheKey(
    val providerId: Long,
    val normalizedQuery: String,
    val scope: SearchContentScope,
)

internal data class CachedSearchResults(
    val result: SearchContentResult,
    val savedAtMs: Long,
)

/**
 * LRU + TTL cache for search results so repeat queries and recent-query taps
 * can render immediately without a loading flash.
 */
internal class SearchResultsCache(
    private val ttlMs: Long = DEFAULT_SEARCH_RESULTS_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val entries = linkedMapOf<SearchCacheKey, CachedSearchResults>()

    fun getFresh(key: SearchCacheKey): SearchContentResult? {
        val cached = entries[key] ?: return null
        if (isExpired(cached)) {
            return null
        }
        touch(key, cached)
        return cached.result
    }

    fun getStale(key: SearchCacheKey): SearchContentResult? {
        val cached = entries[key] ?: return null
        touch(key, cached)
        return cached.result
    }

    fun put(key: SearchCacheKey, result: SearchContentResult) {
        purgeExpired()
        entries[key] = CachedSearchResults(result, nowMs())
        evictOverflow()
    }

    fun clearProvider(providerId: Long) {
        entries.keys.removeAll { it.providerId == providerId }
    }

    fun clearAll() {
        entries.clear()
    }

    private fun touch(key: SearchCacheKey, cached: CachedSearchResults) {
        entries.remove(key)
        entries[key] = cached
    }

    private fun isExpired(cached: CachedSearchResults): Boolean =
        nowMs() - cached.savedAtMs > ttlMs

    private fun purgeExpired() {
        val now = nowMs()
        entries.entries.removeIf { now - it.value.savedAtMs > ttlMs }
    }

    private fun evictOverflow() {
        while (entries.size > maxEntries) {
            val oldest = entries.entries.firstOrNull() ?: break
            entries.remove(oldest.key)
        }
    }

    companion object {
        const val DEFAULT_SEARCH_RESULTS_TTL_MS = 5 * 60 * 1_000L
        const val DEFAULT_MAX_ENTRIES = 24
    }
}
