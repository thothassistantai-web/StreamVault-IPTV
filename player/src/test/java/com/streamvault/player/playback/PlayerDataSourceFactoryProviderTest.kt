package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerDataSourceFactoryProviderTest {
    @Test
    fun `mpeg ts live keeps okhttp data source`() {
        assertThat(shouldUsePlatformHttpDataSource(ResolvedStreamType.MPEG_TS_LIVE)).isFalse()
    }

    @Test
    fun `hls keeps okhttp data source`() {
        assertThat(shouldUsePlatformHttpDataSource(ResolvedStreamType.HLS)).isFalse()
    }

    @Test
    fun `read stats wrap only live stream types`() {
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.HLS)).isTrue()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.MPEG_TS_LIVE)).isTrue()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.PROGRESSIVE)).isFalse()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.DASH)).isFalse()
    }
}
