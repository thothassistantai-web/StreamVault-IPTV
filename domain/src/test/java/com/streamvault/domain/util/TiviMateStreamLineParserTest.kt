package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TiviMateStreamLineParserTest {
    @Test
    fun `parse extracts url user agent referer and origin`() {
        val parsed = TiviMateStreamLineParser.parse(
            "http://127.0.0.1:3000/tivimate-stream/espn.m3u8|User-Agent=TiviMate/4.7.0|Referer=https://daddylive.org/|Origin=https://daddylive.org"
        )

        assertThat(parsed.url).isEqualTo("http://127.0.0.1:3000/tivimate-stream/espn.m3u8")
        assertThat(parsed.userAgent).isEqualTo("TiviMate/4.7.0")
        assertThat(parsed.headers).containsEntry("Referer", "https://daddylive.org/")
        assertThat(parsed.headers).containsEntry("Origin", "https://daddylive.org")
    }

    @Test
    fun `parse returns plain url when no pipe suffix`() {
        val parsed = TiviMateStreamLineParser.parse("https://example.com/live.ts")
        assertThat(parsed.url).isEqualTo("https://example.com/live.ts")
        assertThat(parsed.userAgent).isNull()
        assertThat(parsed.headers).isEmpty()
    }
}
