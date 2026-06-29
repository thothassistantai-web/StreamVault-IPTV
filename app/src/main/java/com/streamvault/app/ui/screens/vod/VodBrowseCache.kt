package com.streamvault.app.ui.screens.vod

import com.streamvault.data.sync.ContentCachePolicy
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy

internal data class VodSelectedCategoryCacheKey(
    val providerId: Long,
    val categoryName: String,
    val filterType: LibraryFilterType,
    val sortBy: LibrarySortBy,
    val searchQuery: String,
    val loadLimit: Int,
)

internal data class CachedVodSelectedCategory<T>(
    val items: List<T>,
    val loadedCount: Int,
    val totalCount: Int,
    val canLoadMore: Boolean,
    val savedAtMs: Long,
)

internal data class VodPreviewCatalogCacheKey(
    val providerId: Long,
    val batchSize: Int,
    val searchQuery: String,
)

internal data class CachedVodPreviewCatalog<T>(
    val itemsByCategory: Map<String, List<T>>,
    val categoryNames: List<String>,
    val categoryCounts: Map<String, Int>,
    val libraryCount: Int,
    val hasMorePreviewRows: Boolean,
    val savedAtMs: Long,
)

/**
 * In-memory cache for VOD category list/browse results so category switches and
 * navigation back can render instantly from a recent snapshot while Room or
 * remote hydration catches up in the background.
 */
internal class VodSelectedCategoryBrowseCache<T>(
    private val ttlMs: Long = ContentCachePolicy.SERIES_CATEGORY_TTL_MILLIS,
    private val maxEntries: Int = 32,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val entries = linkedMapOf<VodSelectedCategoryCacheKey, CachedVodSelectedCategory<T>>()

    fun peek(key: VodSelectedCategoryCacheKey): CachedVodSelectedCategory<T>? {
        purgeExpired()
        return entries[key]
    }

    fun isStale(key: VodSelectedCategoryCacheKey): Boolean {
        val cached = entries[key] ?: return true
        return ContentCachePolicy.shouldRefresh(cached.savedAtMs, ttlMs, nowMs())
    }

    fun put(key: VodSelectedCategoryCacheKey, value: CachedVodSelectedCategory<T>) {
        purgeExpired()
        entries[key] = value
        while (entries.size > maxEntries) {
            val oldest = entries.entries.firstOrNull() ?: break
            entries.remove(oldest.key)
        }
    }

    fun clear() {
        entries.clear()
    }

    fun clearForProvider(providerId: Long) {
        entries.keys.removeAll { it.providerId == providerId }
    }

    private fun purgeExpired() {
        val now = nowMs()
        entries.entries.removeIf { (_, cached) ->
            ContentCachePolicy.shouldRefresh(cached.savedAtMs, ttlMs, now)
        }
    }
}

/**
 * In-memory cache for VOD home-grid preview rows (category shelves) keyed by
 * provider, visible batch size, and active search query.
 */
internal class VodPreviewCatalogBrowseCache<T>(
    private val ttlMs: Long = ContentCachePolicy.SERIES_CATEGORY_TTL_MILLIS,
    private val maxEntries: Int = 16,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val entries = linkedMapOf<VodPreviewCatalogCacheKey, CachedVodPreviewCatalog<T>>()

    fun peek(key: VodPreviewCatalogCacheKey): CachedVodPreviewCatalog<T>? {
        purgeExpired()
        return entries[key]
    }

    fun isStale(key: VodPreviewCatalogCacheKey): Boolean {
        val cached = entries[key] ?: return true
        return ContentCachePolicy.shouldRefresh(cached.savedAtMs, ttlMs, nowMs())
    }

    fun put(key: VodPreviewCatalogCacheKey, value: CachedVodPreviewCatalog<T>) {
        purgeExpired()
        entries[key] = value
        while (entries.size > maxEntries) {
            val oldest = entries.entries.firstOrNull() ?: break
            entries.remove(oldest.key)
        }
    }

    fun clear() {
        entries.clear()
    }

    fun clearForProvider(providerId: Long) {
        entries.keys.removeAll { it.providerId == providerId }
    }

    private fun purgeExpired() {
        val now = nowMs()
        entries.entries.removeIf { (_, cached) ->
            ContentCachePolicy.shouldRefresh(cached.savedAtMs, ttlMs, now)
        }
    }
}
