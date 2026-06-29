package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamEntryUrlPolicyTest {
    @Test
    fun `isAllowed accepts tivimate pipe suffix urls`() {
        val url =
            "http://127.0.0.1:3000/dlhd-event-stream/abc.m3u8|User-Agent=TiviMate/4.7.0|Referer=https://embed.example/"
        assertThat(StreamEntryUrlPolicy.isAllowed(url)).isTrue()
    }

    @Test
    fun `isAllowed rejects unsupported scheme before pipe suffix`() {
        val url = "rtmp://example.com/live|User-Agent=TiviMate/4.7.0"
        assertThat(StreamEntryUrlPolicy.isAllowed(url)).isTrue()
    }
}
