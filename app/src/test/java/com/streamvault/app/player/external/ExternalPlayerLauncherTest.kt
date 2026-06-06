package com.streamvault.app.player.external

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalPlayerLauncherTest {

    @Test
    fun `blank URL returns null`() {
        assertThat(ExternalPlayerLauncher.buildExternalPlayerIntent("")).isNull()
        assertThat(ExternalPlayerLauncher.buildExternalPlayerIntent("   ")).isNull()
        assertThat(ExternalPlayerLauncher.buildExternalPlayerIntent("\t")).isNull()
    }

    @Test
    fun `ftp URL returns null`() {
        assertThat(
            ExternalPlayerLauncher.buildExternalPlayerIntent("ftp://example.com/stream.m3u8")
        ).isNull()
    }

    @Test
    fun `logical internal URLs return null`() {
        assertThat(ExternalPlayerLauncher.buildExternalPlayerIntent("xtream://provider/movie/123")).isNull()
        assertThat(ExternalPlayerLauncher.buildExternalPlayerIntent("stalker://provider/channel/456")).isNull()
    }

    @Test
    fun `VLC compatible direct schemes return intents`() {
        listOf(
            "http://example.com/stream.ts",
            "https://example.com/stream.m3u8",
            "rtsp://example.com/live",
            "rtmp://example.com/live",
            "rtsps://example.com/live",
            "mms://example.com/live",
            "content://media/external/video/media/1",
            "file:///sdcard/Movies/video.mp4",
        ).forEach { url ->
            assertThat(ExternalPlayerLauncher.buildExternalPlayerIntent(url)).isNotNull()
        }
    }

    @Test
    fun `https m3u8 URL returns ACTION_VIEW intent with HLS MIME type`() {
        val intent = checkNotNull(ExternalPlayerLauncher.buildExternalPlayerIntent(
            "https://example.com/stream.m3u8"
        ))

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data.toString()).isEqualTo("https://example.com/stream.m3u8")
        assertThat(intent.type).isEqualTo("application/x-mpegURL")
        assertThat(intent.categories).contains(Intent.CATEGORY_BROWSABLE)
    }

    @Test
    fun `https ts URL returns ACTION_VIEW intent with transport stream MIME type`() {
        val intent = checkNotNull(ExternalPlayerLauncher.buildExternalPlayerIntent(
            "https://example.com/stream.ts"
        ))

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.type).isEqualTo("video/mp2t")
        assertThat(intent.categories).contains(Intent.CATEGORY_BROWSABLE)
    }
}
