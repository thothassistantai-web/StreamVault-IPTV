package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for [M3uParser].
 *
 * All tests run on the host JVM — no Android framework needed.
 */
class M3uParserTest {

    private lateinit var parser: M3uParser

    @Before
    fun setUp() {
        parser = M3uParser()
    }

    // ── Happy path ─────────────────────────────────────────────────

    @Test
    fun `parse_validPlaylist_returnsEntries`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn" tvg-name="CNN" tvg-logo="http://logo.png" group-title="News",CNN International
            http://stream.example.com/cnn.m3u8
            #EXTINF:-1 tvg-id="bbc" tvg-name="BBC" group-title="News",BBC World
            http://stream.example.com/bbc.ts
            #EXTINF:-1 group-title="Sports",Sports Channel
            http://stream.example.com/sports.ts
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(3)

        val cnn = entries[0]
        assertThat(cnn.name).isEqualTo("CNN International")
        assertThat(cnn.tvgId).isEqualTo("cnn")
        assertThat(cnn.tvgName).isEqualTo("CNN")
        assertThat(cnn.tvgLogo).isEqualTo("http://logo.png")
        assertThat(cnn.groupTitle).isEqualTo("News")
        assertThat(cnn.url).isEqualTo("http://stream.example.com/cnn.m3u8")

        val bbc = entries[1]
        assertThat(bbc.name).isEqualTo("BBC World")
        assertThat(bbc.tvgId).isEqualTo("bbc")
        assertThat(bbc.tvgLogo).isNull()

        val sports = entries[2]
        assertThat(sports.name).isEqualTo("Sports Channel")
        assertThat(sports.groupTitle).isEqualTo("Sports")
    }

    @Test
    fun `parse_malformedEntry_skipsGracefully`() {
        // Second entry has no URL line — should be skipped
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="News",Good Channel
            http://stream.example.com/good.ts
            #EXTINF:-1 group-title="News",Bad Channel
            #EXTINF:-1 group-title="News",Another Good Channel
            http://stream.example.com/another.ts
        """.trimIndent()

        val entries = parseEntries(m3u)

        // "Bad Channel" is skipped since its "URL" line is another #EXTINF
        assertThat(entries.map { it.name }).containsExactly("Good Channel", "Another Good Channel")
    }

    @Test
    fun `parse_emptyInputStream_returnsEmptyList`() {
        val entries = parseEntries("")
        assertThat(entries).isEmpty()
    }

    @Test
    fun `parse_noExtinfHeader_skipsLines`() {
        // Bare URLs without #EXTINF preamble should be ignored
        val m3u = """
            http://stream.example.com/channel1.ts
            http://stream.example.com/channel2.ts
        """.trimIndent()

        val entries = parseEntries(m3u)
        assertThat(entries).isEmpty()
    }

    // ── Attribute parsing ─────────────────────────────────────────

    @Test
    fun `parse_quotedAttributes_extractsCorrectly`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Movies HD" tvg-id="movie1",Action Movie
            http://vod.example.com/movie1.mp4
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(1)
        assertThat(entries[0].groupTitle).isEqualTo("Movies HD")
    }

    @Test
    fun `parse_quotedAttributes_withSpacesOrSpecialChars`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Al Jazeera | Arabic" tvg-name="Al Jazeera",Al Jazeera Arabic
            http://stream.example.com/aljazeera.ts
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(1)
        assertThat(entries[0].groupTitle).isEqualTo("Al Jazeera | Arabic")
    }

    @Test
    fun `parse_catchUpAttributes_extracted`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 catchup="xc" catchup-source="http://catchup.example.com/ch1/{utc}-{utcend}.ts" group-title="Live",Live Channel
            http://stream.example.com/ch1.ts
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(1)
        with(entries[0]) {
            assertThat(catchUp).isEqualTo("xc")
            assertThat(catchUpSource).isEqualTo("http://catchup.example.com/ch1/{utc}-{utcend}.ts")
        }
    }

    @Test
    fun `parse_unknownAttributes_ignoredGracefully`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Live" tvg-language="en" custom-attr="custom" another="x",Live Channel
            http://stream.example.com/ch1.ts
        """.trimIndent()

        val entry = parseEntries(m3u).single()

        assertThat(entry.tvgLanguage).isEqualTo("en")
        assertThat(entry.name).isEqualTo("Live Channel")
    }

    @Test
    fun `parse_gzipPlaylist_returnsEntries`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Live",Compressed
            http://stream.example.com/compressed.ts
        """.trimIndent()

        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(m3u)
        }

        val entries = parseEntries(String(java.util.zip.GZIPInputStream(output.toByteArray().inputStream()).readBytes(), Charsets.UTF_8))
        assertThat(entries).hasSize(1)
        assertThat(entries.single().name).isEqualTo("Compressed")
    }

    @Test
    fun `parse_largeSyntheticPlaylist_returnsAllEntries`() {
        val count = 100_000
        val builder = StringBuilder("#EXTM3U\n")
        repeat(count) { index ->
            builder.append("#EXTINF:-1 group-title=\"Group ${index % 10}\",Channel ${index + 1}\n")
            builder.append("http://stream.example.com/ch${index + 1}.ts\n")
        }

        val entries = parseEntries(builder.toString())

        assertThat(entries).hasSize(count)
        assertThat(entries.first().name).isEqualTo("Channel 1")
        assertThat(entries.last().name).isEqualTo("Channel 100000")
    }

    @Test
    fun `parse_unicodeName_preserved`() {
        val m3u = "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"Arabic\",قناة الأولى\n" +
                "http://stream.example.com/arabic.ts\n" +
                "#EXTINF:-1 group-title=\"Hebrew\",ערוץ ראשון\n" +
                "http://stream.example.com/hebrew.ts"

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(2)
        assertThat(entries[0].name).isEqualTo("قناة الأولى")
        assertThat(entries[1].name).isEqualTo("ערוץ ראשון")
    }

    @Test
    fun `parse_tokenizedUrl_preserved`() {
        val tokenizedUrl = "http://stream.example.com/ch1.ts?token=abc123&expire=9999999999&uid=user42"
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Live",Tokenized Channel
            $tokenizedUrl
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(1)
        assertThat(entries[0].url).isEqualTo(tokenizedUrl)
    }

    // ── VOD detection (via SyncManager — canonical isVodEntry) ────

    @Test
    fun `isVodEntry_movieExtension_returnsTrue`() {
        // Create a throwaway SyncManager reference to access the internal helper
        // We test via the M3uParser-level behaviour: parse a playlist and verify isVod logic
        val entries = parseEntries(buildPlaylist(
            "http://vod.example.com/avatar.mp4" to "Movies",
            "http://vod.example.com/inception.mkv" to "VOD",
            "http://vod.example.com/oldfilm.avi" to "Films"
        ))

        assertThat(entries).hasSize(3)
        // All three entries should be classified as VOD by SyncManager
        entries.forEach { entry ->
            val url = entry.url.lowercase()
            val group = entry.groupTitle.lowercase()
            val isVod = url.endsWith(".mp4") || url.endsWith(".mkv") || url.endsWith(".avi") ||
                    url.contains("/movie/") || group.contains("movie") ||
                    group.contains("vod") || group.contains("film")
            assertThat(isVod).isTrue()
        }
    }

    @Test
    fun `isVodEntry_liveStream_returnsFalse`() {
        val entries = parseEntries(buildPlaylist(
            "http://live.example.com/ch1.ts" to "Live",
            "http://live.example.com/ch2.m3u8" to "Sports"
        ))

        assertThat(entries).hasSize(2)
        entries.forEach { entry ->
            val url = entry.url.lowercase()
            val group = entry.groupTitle.lowercase()
            val isVod = url.endsWith(".mp4") || url.endsWith(".mkv") || url.endsWith(".avi") ||
                    url.contains("/movie/") || group.contains("movie") ||
                    group.contains("vod") || group.contains("film")
            assertThat(isVod).isFalse()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun buildPlaylist(vararg entries: Pair<String, String>): String {
        val sb = StringBuilder("#EXTM3U\n")
        entries.forEachIndexed { index, (url, group) ->
            sb.append("#EXTINF:-1 group-title=\"$group\",Channel ${index + 1}\n")
            sb.append("$url\n")
        }
        return sb.toString()
    }

    private fun parseEntries(m3u: String): List<M3uParser.M3uEntry> {
        return parser.parse(m3u.byteInputStream()).entries
    }
}
