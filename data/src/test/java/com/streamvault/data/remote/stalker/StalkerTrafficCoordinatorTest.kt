package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class StalkerTrafficCoordinatorTest {
    @Before
    fun setUp() {
        StalkerTrafficCoordinator.resetForTests()
    }

    @After
    fun tearDown() {
        StalkerTrafficCoordinator.resetForTests()
    }

    @Test
    fun `deferCatalogFetchMillis stays deferred while playback is active beyond legacy window`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L, now = 1_000L)

        val deferredAtTwoMinutes = StalkerTrafficCoordinator.deferCatalogFetchMillis(
            providerId = 7L,
            now = 121_000L
        )

        assertThat(deferredAtTwoMinutes).isGreaterThan(0L)
    }

    @Test
    fun `deferCatalogFetchMillis clears immediately when playback stops`() {
        StalkerTrafficCoordinator.notePlaybackActivity(providerId = 7L, now = 1_000L)
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L, now = 2_000L)

        StalkerTrafficCoordinator.notePlaybackStopped(7L)

        assertThat(StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L, now = 2_001L)).isEqualTo(0L)
    }
}
