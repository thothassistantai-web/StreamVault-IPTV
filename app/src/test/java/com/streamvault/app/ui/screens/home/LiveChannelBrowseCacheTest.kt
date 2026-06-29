package com.streamvault.app.ui.screens.home

import com.streamvault.domain.model.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelBrowseCacheTest {
    private val key = LiveChannelBrowseCacheKey(
        sourceKey = "provider_1",
        categoryId = 10L,
        searchQuery = "",
        combinedFilterProviderId = null
    )

    @Test
    fun `get returns entry regardless of age`() {
        var now = 1_000L
        val cache = LiveChannelBrowseCache(ttlMs = 60_000L, nowMs = { now })
        cache.put(key, CachedChannelBrowse(channels = emptyList(), hasMore = false, cachedAtMs = now))

        now += 120_000L
        assertNotNull(cache.get(key))
    }

    @Test
    fun `isFresh returns false when entry missing or expired`() {
        var now = 1_000L
        val cache = LiveChannelBrowseCache(ttlMs = 60_000L, nowMs = { now })
        assertFalse(cache.isFresh(key, now))

        cache.put(
            key,
            CachedChannelBrowse(
                channels = listOf(Channel(id = 1L, name = "A", providerId = 1L, streamUrl = "http://a")),
                hasMore = false,
                cachedAtMs = now
            )
        )
        assertTrue(cache.isFresh(key, now))

        now += 60_000L
        assertFalse(cache.isFresh(key, now))
    }

    @Test
    fun `clearForSource removes only matching source entries`() {
        val cache = LiveChannelBrowseCache()
        val otherKey = key.copy(sourceKey = "provider_2")
        cache.put(key, CachedChannelBrowse(channels = emptyList(), hasMore = false))
        cache.put(otherKey, CachedChannelBrowse(channels = emptyList(), hasMore = false))

        cache.clearForSource("provider_1")

        assertNull(cache.get(key))
        assertNotNull(cache.get(otherKey))
    }

    @Test
    fun `markStaleForSource forces refresh eligibility`() {
        var now = 1_000L
        val cache = LiveChannelBrowseCache(ttlMs = 60_000L, nowMs = { now })
        cache.put(
            key,
            CachedChannelBrowse(
                channels = listOf(Channel(id = 1L, name = "A", providerId = 1L, streamUrl = "http://a")),
                hasMore = false,
                cachedAtMs = now
            )
        )
        assertTrue(cache.isFresh(key, now))

        cache.markStaleForSource("provider_1")
        assertFalse(cache.isFresh(key, now + 1_000L))
    }
}
