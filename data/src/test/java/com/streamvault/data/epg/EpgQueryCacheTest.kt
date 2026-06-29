package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class EpgQueryCacheTest {

    @Test
    fun `getOrLoad returns fresh value without reloading`() = runTest {
        val nowMs = mutableListOf(0L)
        val loads = AtomicInteger(0)
        val cache = EpgQueryCache(backgroundScope, nowMs = { nowMs.first() })

        val first = cache.getOrLoad("key", freshTtlMs = 1_000L, staleTtlMs = 5_000L) {
            loads.incrementAndGet()
            "value"
        }
        nowMs[0] = 500L
        val second = cache.getOrLoad("key", freshTtlMs = 1_000L, staleTtlMs = 5_000L) {
            loads.incrementAndGet()
            "other"
        }

        assertThat(first).isEqualTo("value")
        assertThat(second).isEqualTo("value")
        assertThat(loads.get()).isEqualTo(1)
    }

    @Test
    fun `getOrLoad serves stale value while revalidating in background`() = runTest {
        val nowMs = mutableListOf(0L)
        val loads = AtomicInteger(0)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val cache = EpgQueryCache(scope.backgroundScope, nowMs = { nowMs.first() })

        cache.getOrLoad("key", freshTtlMs = 1_000L, staleTtlMs = 5_000L) {
            loads.incrementAndGet()
            "stale"
        }

        nowMs[0] = 2_000L
        val served = cache.getOrLoad("key", freshTtlMs = 1_000L, staleTtlMs = 5_000L) {
            loads.incrementAndGet()
            "fresh"
        }

        assertThat(served).isEqualTo("stale")
        advanceTimeBy(1)
        dispatcher.scheduler.advanceUntilIdle()

        val afterRefresh = cache.getOrLoad("key", freshTtlMs = 1_000L, staleTtlMs = 5_000L) {
            loads.incrementAndGet()
            "ignored"
        }
        assertThat(afterRefresh).isEqualTo("fresh")
        assertThat(loads.get()).isEqualTo(2)
    }

    @Test
    fun `invalidateProvider drops cached entries`() = runTest {
        val loads = AtomicInteger(0)
        val cache = EpgQueryCache(backgroundScope)

        cache.getOrLoad("p7:rpc:1:0:100", freshTtlMs = 60_000L, staleTtlMs = 120_000L) {
            loads.incrementAndGet()
            mapOf("one" to emptyList<Any>())
        }
        cache.invalidateProvider(7L)
        cache.getOrLoad("p7:rpc:1:0:100", freshTtlMs = 60_000L, staleTtlMs = 120_000L) {
            loads.incrementAndGet()
            mapOf("one" to emptyList<Any>())
        }

        assertThat(loads.get()).isEqualTo(2)
    }
}
