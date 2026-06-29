package com.streamvault.app.ui.screens.home

import com.streamvault.domain.model.Channel
import javax.inject.Inject
import javax.inject.Singleton

data class LiveChannelBrowseCacheKey(
    val sourceKey: String,
    val categoryId: Long,
    val searchQuery: String,
    val combinedFilterProviderId: Long?
)

data class CachedChannelBrowse(
    val channels: List<Channel>,
    val hasMore: Boolean,
    val cachedAtMs: Long = System.currentTimeMillis()
)

/**
 * In-memory cache of recently browsed Live TV channel lists so category switches
 * and tab returns can render instantly from local DB results without clearing
 * the grid or showing a blocking spinner while Room re-subscribes.
 */
@Singleton
class LiveChannelBrowseCache @Inject constructor() {
    private val ttlMs: Long = DEFAULT_LIVE_CHANNEL_BROWSE_TTL_MS
    private val nowMs: () -> Long = { System.currentTimeMillis() }

    private val entries = mutableMapOf<LiveChannelBrowseCacheKey, CachedChannelBrowse>()

    fun get(key: LiveChannelBrowseCacheKey): CachedChannelBrowse? = entries[key]

    fun isFresh(key: LiveChannelBrowseCacheKey, atMs: Long = nowMs()): Boolean {
        val entry = entries[key] ?: return false
        val ageMs = atMs - entry.cachedAtMs
        return ageMs in 0 until ttlMs
    }

    fun put(key: LiveChannelBrowseCacheKey, value: CachedChannelBrowse) {
        entries[key] = value.copy(cachedAtMs = nowMs())
    }

    fun clear() {
        entries.clear()
    }

    fun clearForSource(sourceKey: String) {
        entries.keys.removeAll { it.sourceKey == sourceKey }
    }

    fun markStaleForSource(sourceKey: String) {
        entries.entries.forEach { (key, value) ->
            if (key.sourceKey == sourceKey) {
                entries[key] = value.copy(cachedAtMs = 0L)
            }
        }
    }

    companion object {
        const val DEFAULT_LIVE_CHANNEL_BROWSE_TTL_MS = 5 * 60 * 1_000L
    }
}
