package com.streamvault.app.ui.cache

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StaleWhileRevalidateFlowTest {

    @Test
    fun `emits stale value before upstream`() = runTest {
        val emissions = flowOf("fresh")
            .staleWhileRevalidate(staleValue = "stale")
            .toList()

        assertThat(emissions).containsExactly("stale", "fresh").inOrder()
    }

    @Test
    fun `skips stale emission when cache is absent`() = runTest {
        val emissions = flowOf("fresh")
            .staleWhileRevalidate(staleValue = null)
            .toList()

        assertThat(emissions).containsExactly("fresh")
    }

    @Test
    fun `invokes onFresh for upstream values`() = runTest {
        val seen = mutableListOf<String>()
        flowOf("fresh")
            .staleWhileRevalidate(staleValue = "stale", onFresh = seen::add)
            .toList()

        assertThat(seen).containsExactly("fresh")
    }
}
