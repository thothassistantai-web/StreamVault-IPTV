package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalPlaybackModeTest {

    @Test
    fun `fromStorageValue - internal returns INTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("internal")).isEqualTo(ExternalPlaybackMode.INTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - ask returns ASK_EVERY_TIME`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("ask")).isEqualTo(ExternalPlaybackMode.ASK_EVERY_TIME)
    }

    @Test
    fun `fromStorageValue - external returns EXTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("external")).isEqualTo(ExternalPlaybackMode.EXTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - null returns INTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue(null)).isEqualTo(ExternalPlaybackMode.INTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - blank string returns INTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("")).isEqualTo(ExternalPlaybackMode.INTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - whitespace string returns INTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("   ")).isEqualTo(ExternalPlaybackMode.INTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - unknown token returns INTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("unknown")).isEqualTo(ExternalPlaybackMode.INTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - mixed-case internal returns INTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("INTERNAL")).isEqualTo(ExternalPlaybackMode.INTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - mixed-case ask returns ASK_EVERY_TIME`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("ASK")).isEqualTo(ExternalPlaybackMode.ASK_EVERY_TIME)
    }

    @Test
    fun `fromStorageValue - mixed-case external returns EXTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("EXTERNAL")).isEqualTo(ExternalPlaybackMode.EXTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - mixed-case with spaces returns INTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("  Internal  ")).isEqualTo(ExternalPlaybackMode.INTERNAL_PLAYER)
    }

    @Test
    fun `fromStorageValue - mixed-case with spaces returns ASK_EVERY_TIME`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("  Ask  ")).isEqualTo(ExternalPlaybackMode.ASK_EVERY_TIME)
    }

    @Test
    fun `fromStorageValue - mixed-case with spaces returns EXTERNAL_PLAYER`() {
        assertThat(ExternalPlaybackMode.fromStorageValue("  External  ")).isEqualTo(ExternalPlaybackMode.EXTERNAL_PLAYER)
    }

    @Test
    fun `storageValue - each enum has a stable lowercase value`() {
        assertThat(ExternalPlaybackMode.INTERNAL_PLAYER.storageValue).isEqualTo("internal")
        assertThat(ExternalPlaybackMode.ASK_EVERY_TIME.storageValue).isEqualTo("ask")
        assertThat(ExternalPlaybackMode.EXTERNAL_PLAYER.storageValue).isEqualTo("external")
    }

    @Test
    fun `entries - exactly three values`() {
        assertThat(ExternalPlaybackMode.entries).hasSize(3)
    }
}
