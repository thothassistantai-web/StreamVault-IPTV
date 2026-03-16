package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgramTest {

    @Test
    fun `durationMinutes - 1 hour program`() {
        val program = Program(
            channelId = "ch1",
            title = "News",
            startTime = 1000000L,
            endTime = 1000000L + 3600000L  // 1 hour
        )
        assertThat(program.durationMinutes).isEqualTo(60)
    }

    @Test
    fun `durationMinutes - 30 minute program`() {
        val program = Program(
            channelId = "ch1",
            title = "Show",
            startTime = 0L,
            endTime = 1800000L
        )
        assertThat(program.durationMinutes).isEqualTo(30)
    }

    @Test
    fun `durationMinutes - zero duration`() {
        val program = Program(channelId = "ch1", title = "Test", startTime = 100L, endTime = 100L)
        assertThat(program.durationMinutes).isEqualTo(0)
    }

    @Test
    fun `progressPercent - not now playing returns 0`() {
        val program = Program(
            channelId = "ch1",
            title = "Test",
            isNowPlaying = false,
            startTime = 0L,
            endTime = 100000L
        )
        assertThat(program.progressPercent(50000L)).isEqualTo(0f)
    }

    @Test
    fun `progressPercent - before start returns 0`() {
        val program = Program(
            channelId = "ch1",
            title = "Test",
            isNowPlaying = true,
            startTime = 100000L,
            endTime = 200000L
        )
        assertThat(program.progressPercent(50000L)).isEqualTo(0f)
    }

    @Test
    fun `progressPercent - at midpoint returns 0_5`() {
        val program = Program(
            channelId = "ch1",
            title = "Test",
            isNowPlaying = true,
            startTime = 100000L,
            endTime = 200000L
        )
        assertThat(program.progressPercent(150000L)).isEqualTo(0.5f)
    }

    @Test
    fun `progressPercent - past end coerces to 1`() {
        val program = Program(
            channelId = "ch1",
            title = "Test",
            isNowPlaying = true,
            startTime = 100000L,
            endTime = 200000L
        )
        assertThat(program.progressPercent(999999L)).isEqualTo(1f)
    }

    @Test
    fun `progressPercent - zero duration returns 0`() {
        val program = Program(
            channelId = "ch1",
            title = "Test",
            isNowPlaying = true,
            startTime = 100000L,
            endTime = 100000L
        )
        assertThat(program.progressPercent(100000L)).isEqualTo(0f)
    }
}
