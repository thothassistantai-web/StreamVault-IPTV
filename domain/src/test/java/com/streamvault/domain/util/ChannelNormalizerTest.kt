package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelNormalizerTest {

    @Test
    fun `strips HD quality tag`() {
        val id = ChannelNormalizer.getLogicalGroupId("BBC One HD", 1L)
        assertThat(id).isEqualTo("1_bbcone")
    }

    @Test
    fun `strips FHD tag and country prefix`() {
        val id = ChannelNormalizer.getLogicalGroupId("US: HBO Max FHD", 1L)
        assertThat(id).isEqualTo("1_hbomax")
    }

    @Test
    fun `strips pipe-delimited country code`() {
        val id = ChannelNormalizer.getLogicalGroupId("|UK| Sky Sports 1", 1L)
        assertThat(id).isEqualTo("1_skysports1")
    }

    @Test
    fun `strips parenthesized content`() {
        val id = ChannelNormalizer.getLogicalGroupId("CNN (US)", 1L)
        assertThat(id).isEqualTo("1_cnn")
    }

    @Test
    fun `strips bracketed content`() {
        val id = ChannelNormalizer.getLogicalGroupId("[HD] ESPN", 1L)
        assertThat(id).isEqualTo("1_espn")
    }

    @Test
    fun `removes accents`() {
        val id = ChannelNormalizer.getLogicalGroupId("Téléfilm", 1L)
        assertThat(id).isEqualTo("1_telefilm")
    }

    @Test
    fun `same channel different qualities normalize to same id`() {
        val hdId = ChannelNormalizer.getLogicalGroupId("BBC One HD", 1L)
        val sdId = ChannelNormalizer.getLogicalGroupId("BBC One SD", 1L)
        val fhdId = ChannelNormalizer.getLogicalGroupId("BBC One FHD", 1L)
        assertThat(hdId).isEqualTo(sdId)
        assertThat(sdId).isEqualTo(fhdId)
    }

    @Test
    fun `different providers have different ids`() {
        val id1 = ChannelNormalizer.getLogicalGroupId("BBC One", 1L)
        val id2 = ChannelNormalizer.getLogicalGroupId("BBC One", 2L)
        assertThat(id1).isNotEqualTo(id2)
    }

    @Test
    fun `strips multiple quality tags`() {
        val id = ChannelNormalizer.getLogicalGroupId("ESPN 4K HEVC", 1L)
        assertThat(id).isEqualTo("1_espn")
    }

    @Test
    fun `preserves channel numbers`() {
        val id = ChannelNormalizer.getLogicalGroupId("Sky Sports 1", 1L)
        assertThat(id).isEqualTo("1_skysports1")
    }

    @Test
    fun `strips colon-delimited country prefix`() {
        val id = ChannelNormalizer.getLogicalGroupId("FR: Canal+", 1L)
        assertThat(id).isEqualTo("1_canal")
    }
}
