package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uChannelDisplayNameTest {
    @Test
    fun resolve_keeps_gateway_title_as_is() {
        val title = "🟢 US: NBA LAKERS VS CELTICS ᴸᴵⱽᴱ"
        val resolved = M3uChannelDisplayName.resolve(
            M3uChannelDisplayName.Input(
                name = title,
                groupTitle = "🎟️ Special Events",
                streamUrl = "http://127.0.0.1:3000/dlhd-event-stream/abc.m3u8",
            ),
        )
        assertThat(resolved).isEqualTo(title)
    }

    @Test
    fun resolve_appends_language_from_tvg_language_when_missing_in_title() {
        val resolved = M3uChannelDisplayName.resolve(
            M3uChannelDisplayName.Input(
                name = "US: NBA LAKERS VS CELTICS ᴸᴵⱽᴱ",
                groupTitle = "Special Events",
                streamUrl = "http://127.0.0.1:3000/dlhd-event-stream/nba.m3u8",
                tvgLanguage = "eng",
            ),
        )
        assertThat(resolved).isEqualTo("US: NBA LAKERS VS CELTICS ᴸᴵⱽᴱ [EN]")
    }

    @Test
    fun resolve_does_not_duplicate_french_label_already_in_title() {
        val title = "FR: NHL CANADIENS VS LEAFS 🇫🇷 [FR] ᴸᴵⱽᴱ"
        val resolved = M3uChannelDisplayName.resolve(
            M3uChannelDisplayName.Input(
                name = title,
                groupTitle = "Special Events",
                streamUrl = "http://127.0.0.1:3000/dlhd-event-stream/fr.m3u8",
                tvgLanguage = "fra",
            ),
        )
        assertThat(resolved).isEqualTo(title)
    }

    @Test
    fun resolve_leaves_regular_channels_untouched() {
        val title = "CNN International"
        val resolved = M3uChannelDisplayName.resolve(
            M3uChannelDisplayName.Input(
                name = title,
                groupTitle = "News",
                streamUrl = "http://example.com/cnn.m3u8",
                tvgLanguage = "eng",
            ),
        )
        assertThat(resolved).isEqualTo(title)
    }

    @Test
    fun preserveProviderLabel_detects_special_events_group_and_gateway_urls() {
        val input = M3uChannelDisplayName.Input(
            name = "US: NFL CHIEFS VS BILLS ᴸᴵⱽᴱ",
            groupTitle = "🎟️ Special Events",
            streamUrl = "http://127.0.0.1:3000/dlhd-event-stream/nfl.m3u8",
        )
        assertThat(M3uChannelDisplayName.preserveProviderLabel(input)).isTrue()
    }
}
