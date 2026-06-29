package com.streamvault.app.ui.screens.home

import com.streamvault.domain.model.Category
import javax.inject.Inject
import javax.inject.Singleton

data class CachedCategoryList(
    val categories: List<Category>,
    val lastVisitedCategory: Category?,
    val pinnedCategoryIds: Set<Long>,
    val hiddenLiveCategories: List<Category>,
    val cachedAtMillis: Long = System.currentTimeMillis()
)

/**
 * In-memory cache of Live TV sidebar categories (with counts) keyed by live source
 * so revisiting a provider or combined M3U profile can render instantly while Room
 * flows refresh in the background.
 */
@Singleton
class LiveCategoryListCache @Inject constructor() {
    companion object {
        const val STALE_AFTER_MS = 5 * 60 * 1000L
    }

    private val entries = mutableMapOf<String, CachedCategoryList>()

    fun get(sourceKey: String): CachedCategoryList? = entries[sourceKey]

    fun put(sourceKey: String, value: CachedCategoryList) {
        entries[sourceKey] = value
    }

    fun isStale(sourceKey: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cachedAt = entries[sourceKey]?.cachedAtMillis ?: return true
        return nowMillis - cachedAt >= STALE_AFTER_MS
    }

    fun markStale(sourceKey: String) {
        val entry = entries[sourceKey] ?: return
        entries[sourceKey] = entry.copy(cachedAtMillis = 0L)
    }

    fun clear() {
        entries.clear()
    }

    fun clearForSource(sourceKey: String) {
        entries.remove(sourceKey)
    }
}
