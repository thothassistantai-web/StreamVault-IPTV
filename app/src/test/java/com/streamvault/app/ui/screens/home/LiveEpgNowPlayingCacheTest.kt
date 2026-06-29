package com.streamvault.app.ui.screens.home

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Program
import org.junit.Test

class LiveEpgNowPlayingCacheTest {

    @Test
    fun `partition returns fresh entries without revalidation`() {
        val nowMs = mutableListOf(0L)
        val cache = LiveEpgNowPlayingCache(nowMs = { nowMs.first() })
        val key = LiveEpgChannelKey(providerId = 1L, lookupKey = "bbc1.uk")
        val program = Program(
            id = 1L,
            providerId = 1L,
            channelId = "bbc1.uk",
            title = "News",
            startTime = 0L,
            endTime = 100L,
        )
        cache.put(mapOf("bbc1.uk" to program), mapOf("bbc1.uk" to 1L))

        nowMs[0] = 10_000L
        val partition = cache.partition(listOf(key))

        assertThat(partition.immediate).containsEntry("bbc1.uk", program)
        assertThat(partition.revalidate).isEmpty()
    }

    @Test
    fun `partition marks stale entries for revalidation`() {
        val nowMs = mutableListOf(0L)
        val cache = LiveEpgNowPlayingCache(nowMs = { nowMs.first() })
        val key = LiveEpgChannelKey(providerId = 1L, lookupKey = "bbc1.uk")
        cache.put(
            mapOf(
                "bbc1.uk" to Program(
                    id = 1L,
                    providerId = 1L,
                    channelId = "bbc1.uk",
                    title = "News",
                    startTime = 0L,
                    endTime = 100L,
                )
            ),
            mapOf("bbc1.uk" to 1L),
        )

        nowMs[0] = LiveEpgNowPlayingCache.FRESH_MS + 1L
        val partition = cache.partition(listOf(key))

        assertThat(partition.immediate).containsKey("bbc1.uk")
        assertThat(partition.revalidate).containsExactly(key)
    }
}
