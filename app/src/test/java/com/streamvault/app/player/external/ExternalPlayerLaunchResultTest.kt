package com.streamvault.app.player.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalPlayerLaunchResultTest {

    // ─── inferExternalPlayerMimeType ───────────────────────────────────────

    @Test
    fun `m3u8 URL returns HLS MIME type`() {
        val url = "https://example.com/live/stream.m3u8"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("application/x-mpegURL")
    }

    @Test
    fun `ts URL returns transport stream MIME type`() {
        val url = "https://cdn.example.com/channel/segment.ts"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("video/mp2t")
    }

    @Test
    fun `mp4 URL returns video wildcard fallback`() {
        val url = "https://cdn.example.com/video.mp4"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("video/*")
    }

    @Test
    fun `unknown extension returns video wildcard fallback`() {
        val url = "https://cdn.example.com/stream.xyz"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("video/*")
    }

    @Test
    fun `uppercase M3U8 extension returns HLS MIME type`() {
        val url = "https://example.com/live/stream.M3U8"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("application/x-mpegURL")
    }

    @Test
    fun `uppercase TS extension returns transport stream MIME type`() {
        val url = "https://example.com/stream.TS"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("video/mp2t")
    }

    @Test
    fun `mixed-case m3U8 extension returns HLS MIME type`() {
        val url = "https://example.com/live/stream.M3u8"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("application/x-mpegURL")
    }

    @Test
    fun `query string on m3u8 URL still returns HLS MIME type`() {
        val url = "https://example.com/live/stream.m3u8?token=abc&quality=hd"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("application/x-mpegURL")
    }

    @Test
    fun `query string on ts URL still returns transport stream MIME type`() {
        val url = "https://cdn.example.com/stream.ts?token=xyz"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("video/mp2t")
    }

    @Test
    fun `fragment on m3u8 URL still returns HLS MIME type`() {
        val url = "https://example.com/stream.m3u8#section=3"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("application/x-mpegURL")
    }

    @Test
    fun `query string and fragment on ts URL still returns transport stream MIME type`() {
        val url = "https://cdn.example.com/stream.ts?token=abc#section=2"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("video/mp2t")
    }

    @Test
    fun `mp4 with query string returns video wildcard fallback`() {
        val url = "https://cdn.example.com/video.mp4?quality=1080p"
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("video/*")
    }

    @Test
    fun `whitespace-padded URL is trimmed before extension check`() {
        val url = "  https://example.com/stream.m3u8  "
        val result = inferExternalPlayerMimeType(url)
        assertThat(result).isEqualTo("application/x-mpegURL")
    }

    // ─── ExternalPlayerLaunchResult.fromUrl ────────────────────────────────

    @Test
    fun `fromUrl with valid m3u8 URL returns Success`() {
        val result = ExternalPlayerLaunchResult.fromUrl("https://example.com/live.m3u8")
        assertThat(result).isInstanceOf(ExternalPlayerLaunchResult.Success::class.java)
        val success = result as ExternalPlayerLaunchResult.Success
        assertThat(success.url).isEqualTo("https://example.com/live.m3u8")
        assertThat(success.mimeType).isEqualTo("application/x-mpegURL")
    }

    @Test
    fun `fromUrl with blank URL returns InvalidUrl`() {
        val result = ExternalPlayerLaunchResult.fromUrl("   ")
        assertThat(result).isInstanceOf(ExternalPlayerLaunchResult.InvalidUrl::class.java)
        val invalid = result as ExternalPlayerLaunchResult.InvalidUrl
        assertThat(invalid.rawUrl).isEqualTo("   ")
        assertThat(invalid.reason).isNotEmpty()
    }

    @Test
    fun `fromUrl with ts URL returns Success with transport stream mimeType`() {
        val result = ExternalPlayerLaunchResult.fromUrl("https://cdn.example.com/stream.ts")
        assertThat(result).isInstanceOf(ExternalPlayerLaunchResult.Success::class.java)
        val success = result as ExternalPlayerLaunchResult.Success
        assertThat(success.mimeType).isEqualTo("video/mp2t")
    }

    @Test
    fun `fromUrl with mp4 URL returns Success with video wildcard mimeType`() {
        val result = ExternalPlayerLaunchResult.fromUrl("https://cdn.example.com/video.mp4")
        assertThat(result).isInstanceOf(ExternalPlayerLaunchResult.Success::class.java)
        val success = result as ExternalPlayerLaunchResult.Success
        assertThat(success.mimeType).isEqualTo("video/*")
    }
}
