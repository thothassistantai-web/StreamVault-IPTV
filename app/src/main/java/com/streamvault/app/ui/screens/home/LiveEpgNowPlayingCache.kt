package com.streamvault.app.ui.screens.home

import com.streamvault.domain.model.Program
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class LiveEpgChannelKey(
    val providerId: Long,
    val lookupKey: String,
)

data class LiveEpgCachePartition(
    val immediate: Map<String, Program>,
    val revalidate: Set<LiveEpgChannelKey>,
)

/**
 * Per-channel now-playing cache for the Live TV grid so scrolling back to a row
 * does not re-hit EPG resolution or provider fallbacks on every visit.
 */
@Singleton
class LiveEpgNowPlayingCache @Inject constructor() {
    private val nowMs: () -> Long = { System.currentTimeMillis() }

    private data class Entry(val program: Program, val savedAtMs: Long)

    private val entries = ConcurrentHashMap<LiveEpgChannelKey, Entry>()

    fun partition(keys: List<LiveEpgChannelKey>): LiveEpgCachePartition {
        if (keys.isEmpty()) return LiveEpgCachePartition(emptyMap(), emptySet())

        val immediate = linkedMapOf<String, Program>()
        val revalidate = linkedSetOf<LiveEpgChannelKey>()
        val now = nowMs()

        keys.distinct().forEach { key ->
            val entry = entries[key]
            when {
                entry == null -> revalidate += key
                now - entry.savedAtMs < FRESH_MS -> immediate[key.lookupKey] = entry.program
                now - entry.savedAtMs < STALE_MS -> {
                    immediate[key.lookupKey] = entry.program
                    revalidate += key
                }
                else -> revalidate += key
            }
        }

        return LiveEpgCachePartition(immediate, revalidate)
    }

    fun put(programsByLookupKey: Map<String, Program>, providerIdByLookupKey: Map<String, Long>) {
        val now = nowMs()
        programsByLookupKey.forEach { (lookupKey, program) ->
            val providerId = providerIdByLookupKey[lookupKey] ?: return@forEach
            entries[LiveEpgChannelKey(providerId, lookupKey)] = Entry(program, now)
        }
    }

    fun clear() {
        entries.clear()
    }

    fun clearForProvider(providerId: Long) {
        entries.keys.removeAll { it.providerId == providerId }
    }

    companion object {
        const val FRESH_MS = 45L * 1_000L
        const val STALE_MS = 5L * 60L * 1_000L
    }
}
