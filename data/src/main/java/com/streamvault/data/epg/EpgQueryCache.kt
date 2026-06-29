package com.streamvault.data.epg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory TTL cache for expensive EPG snapshot queries with stale-while-revalidate.
 */
@Singleton
class EpgQueryCache @Inject constructor(
    private val refreshScope: CoroutineScope,
) {
    private val nowMs: () -> Long = { System.currentTimeMillis() }

    private data class Entry<T>(val value: T, val savedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry<*>>()
    private val refreshMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> getOrLoad(
        key: String,
        freshTtlMs: Long,
        staleTtlMs: Long,
        loader: suspend () -> T,
    ): T {
        val cached = entries[key] as? Entry<T>
        val now = nowMs()
        if (cached != null) {
            val ageMs = now - cached.savedAtMs
            when {
                ageMs < freshTtlMs -> return cached.value
                ageMs < staleTtlMs -> {
                    scheduleBackgroundRefresh(key, loader)
                    return cached.value
                }
            }
        }

        val fresh = loader()
        entries[key] = Entry(fresh, nowMs())
        return fresh
    }

    fun invalidateProvider(providerId: Long) {
        val prefix = providerKeyPrefix(providerId)
        entries.keys.removeIf { it.startsWith(prefix) }
    }

    fun invalidateAll() {
        entries.clear()
    }

    private fun <T> scheduleBackgroundRefresh(key: String, loader: suspend () -> T) {
        val mutex = refreshMutexes.computeIfAbsent(key) { Mutex() }
        refreshScope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                val fresh = loader()
                entries[key] = Entry(fresh, nowMs())
            } catch (_: Exception) {
                // Keep serving stale data when background refresh fails.
            } finally {
                mutex.unlock()
            }
        }
    }

    companion object {
        const val PROGRAMS_SNAPSHOT_FRESH_MS = 2L * 60L * 1_000L
        const val PROGRAMS_SNAPSHOT_STALE_MS = 15L * 60L * 1_000L
        const val RESOLVED_PROGRAMS_FRESH_MS = 2L * 60L * 1_000L
        const val RESOLVED_PROGRAMS_STALE_MS = 15L * 60L * 1_000L
        const val NOW_PLAYING_FRESH_MS = 45L * 1_000L
        const val NOW_PLAYING_STALE_MS = 5L * 60L * 1_000L
        const val PLAYBACK_CHANNEL_FRESH_MS = 30L * 1_000L
        const val PLAYBACK_CHANNEL_STALE_MS = 10L * 60L * 1_000L
        private const val NOW_PLAYING_BUCKET_MS = 60L * 1_000L

        fun providerKeyPrefix(providerId: Long): String = "p$providerId:"

        fun programsForChannelsKey(
            providerId: Long,
            channelIds: List<String>,
            startTime: Long,
            endTime: Long,
        ): String = buildString {
            append(providerKeyPrefix(providerId))
            append("pfc:")
            append(channelIds.sorted().joinToString(","))
            append(':')
            append(startTime)
            append(':')
            append(endTime)
        }

        fun resolvedProgramsKey(
            providerId: Long,
            channelIds: List<Long>,
            startTime: Long,
            endTime: Long,
        ): String = buildString {
            append(providerKeyPrefix(providerId))
            append("rpc:")
            append(channelIds.sorted().joinToString(","))
            append(':')
            append(startTime)
            append(':')
            append(endTime)
        }

        fun nowPlayingKey(
            providerId: Long,
            channelIds: List<String>,
            nowBucket: Long,
        ): String = buildString {
            append(providerKeyPrefix(providerId))
            append("np:")
            append(channelIds.sorted().joinToString(","))
            append(':')
            append(nowBucket)
        }

        fun playbackChannelKey(
            providerId: Long,
            internalChannelId: Long,
            epgChannelId: String?,
            streamId: Long,
            startTime: Long,
            endTime: Long,
        ): String = buildString {
            append(providerKeyPrefix(providerId))
            append("pbc:")
            append(internalChannelId)
            append(':')
            append(epgChannelId.orEmpty())
            append(':')
            append(streamId)
            append(':')
            append(startTime)
            append(':')
            append(endTime)
        }

        fun nowPlayingBucket(nowMs: Long): Long = nowMs / NOW_PLAYING_BUCKET_MS
    }
}
