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
    fun `deferCatalogFetchMillis stays deferred while playback is active`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)

        val deferred = StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L)

        assertThat(deferred).isGreaterThan(0L)
    }

    @Test
    fun `deferCatalogFetchMillis clears immediately when playback stops`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStopped(providerId = 7L)

        val deferred = StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L)

        assertThat(deferred).isEqualTo(0L)
    }

    @Test
    fun `deferCatalogFetchMillis tracks nested playback sessions for same provider`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStopped(providerId = 7L)

        val deferred = StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L)

        assertThat(deferred).isGreaterThan(0L)
    }
}
