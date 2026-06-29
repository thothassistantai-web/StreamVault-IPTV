package com.streamvault.app.ui.screens.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LivePreviewStateCacheTest {

    @Test
    fun `restores cached preview channel per category`() {
        val cache = LivePreviewStateCache()
        val sportsKey = LivePreviewStateCacheKey(
            sourceKey = "provider_1",
            categoryId = 10L,
            combinedFilterProviderId = null,
        )
        val newsKey = sportsKey.copy(categoryId = 20L)

        cache.put(sportsKey, channelId = 100L)
        cache.put(newsKey, channelId = 200L)

        assertThat(cache.get(sportsKey)).isEqualTo(100L)
        assertThat(cache.get(newsKey)).isEqualTo(200L)
    }

    @Test
    fun `clears cached preview state for source`() {
        val cache = LivePreviewStateCache()
        val providerOneKey = LivePreviewStateCacheKey("provider_1", 10L, null)
        val providerTwoKey = LivePreviewStateCacheKey("provider_2", 10L, null)
        cache.put(providerOneKey, 100L)
        cache.put(providerTwoKey, 200L)

        cache.clearForSource("provider_1")

        assertThat(cache.get(providerOneKey)).isNull()
        assertThat(cache.get(providerTwoKey)).isEqualTo(200L)
    }
}
