package com.streamvault.app.ui.cache

import com.streamvault.domain.model.Channel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveRecentChannelsCache @Inject constructor() {
    companion object {
        const val STALE_AFTER_MS = 5 * 60 * 1000L
    }

    private data class Entry(
        val channels: List<Channel>,
        val cachedAtMillis: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(sourceKey: String): List<Channel>? = entries[sourceKey]?.channels

    fun put(sourceKey: String, channels: List<Channel>) {
        entries[sourceKey] = Entry(channels, System.currentTimeMillis())
    }

    fun isStale(sourceKey: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cachedAt = entries[sourceKey]?.cachedAtMillis ?: return true
        return nowMillis - cachedAt >= STALE_AFTER_MS
    }

    fun clear() {
        entries.clear()
    }

    fun clearForSource(sourceKey: String) {
        entries.remove(sourceKey)
    }
}
