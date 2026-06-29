package com.streamvault.app.ui.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DashboardShelfCacheTest {

    @Test
    fun `stores and retrieves snapshot by source key`() {
        val cache = DashboardShelfCache()
        val snapshot = DashboardShelfCache.CachedSnapshot(recentMovies = emptyList())

        cache.put("provider_1", snapshot)

        assertThat(cache.get("provider_1")?.recentMovies).isEmpty()
        assertThat(cache.get("provider_2")).isNull()
    }

    @Test
    fun `marks entries stale after ttl`() {
        val cache = DashboardShelfCache()
        cache.put("provider_1", DashboardShelfCache.CachedSnapshot())
        val cachedAt = cache.get("provider_1")!!.cachedAtMillis

        assertThat(cache.isStale("provider_1", nowMillis = cachedAt + DashboardShelfCache.STALE_AFTER_MS)).isTrue()
        assertThat(cache.isStale("provider_1", nowMillis = cachedAt + DashboardShelfCache.STALE_AFTER_MS - 1L)).isFalse()
    }
}
