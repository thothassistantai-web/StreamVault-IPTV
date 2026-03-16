package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamType
import org.junit.Test

class StreamTypeDetectorTest {

    @Test
    fun `m3u8 extension detected as HLS`() {
        assertThat(StreamTypeDetector.detect("http://example.com/stream.m3u8"))
            .isEqualTo(StreamType.HLS)
    }

    @Test
    fun `m3u8 with query params detected as HLS`() {
        assertThat(StreamTypeDetector.detect("http://example.com/stream.m3u8?token=abc"))
            .isEqualTo(StreamType.HLS)
    }

    @Test
    fun `hls path segment detected as HLS`() {
        assertThat(StreamTypeDetector.detect("http://example.com/hls/stream"))
            .isEqualTo(StreamType.HLS)
    }

    @Test
    fun `mpd extension detected as DASH`() {
        assertThat(StreamTypeDetector.detect("http://example.com/manifest.mpd"))
            .isEqualTo(StreamType.DASH)
    }

    @Test
    fun `dash path segment detected as DASH`() {
        assertThat(StreamTypeDetector.detect("http://example.com/dash/stream"))
            .isEqualTo(StreamType.DASH)
    }

    @Test
    fun `ts extension detected as MPEG_TS`() {
        assertThat(StreamTypeDetector.detect("http://example.com/channel.ts"))
            .isEqualTo(StreamType.MPEG_TS)
    }

    @Test
    fun `live path without mp4 detected as MPEG_TS`() {
        assertThat(StreamTypeDetector.detect("http://example.com/live/channel1"))
            .isEqualTo(StreamType.MPEG_TS)
    }

    @Test
    fun `live path with mp4 detected as PROGRESSIVE`() {
        assertThat(StreamTypeDetector.detect("http://example.com/live/movie.mp4"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }

    @Test
    fun `mp4 detected as PROGRESSIVE`() {
        assertThat(StreamTypeDetector.detect("http://example.com/movie.mp4"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }

    @Test
    fun `mkv detected as PROGRESSIVE`() {
        assertThat(StreamTypeDetector.detect("http://example.com/movie.mkv"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }

    @Test
    fun `avi detected as PROGRESSIVE`() {
        assertThat(StreamTypeDetector.detect("http://example.com/movie.avi"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }

    @Test
    fun `flv detected as PROGRESSIVE`() {
        assertThat(StreamTypeDetector.detect("http://example.com/video.flv"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }

    @Test
    fun `webm detected as PROGRESSIVE`() {
        assertThat(StreamTypeDetector.detect("http://example.com/video.webm"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }

    @Test
    fun `mov detected as PROGRESSIVE`() {
        assertThat(StreamTypeDetector.detect("http://example.com/video.mov"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }

    @Test
    fun `unknown url returns UNKNOWN`() {
        assertThat(StreamTypeDetector.detect("http://example.com/stream"))
            .isEqualTo(StreamType.UNKNOWN)
    }

    @Test
    fun `case insensitive detection`() {
        assertThat(StreamTypeDetector.detect("http://example.com/STREAM.M3U8"))
            .isEqualTo(StreamType.HLS)
    }

    @Test
    fun `fragment stripped before matching`() {
        assertThat(StreamTypeDetector.detect("http://example.com/video.mp4#t=10"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }
}
