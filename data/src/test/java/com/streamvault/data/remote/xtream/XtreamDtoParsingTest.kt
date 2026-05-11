package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamVodMovieData
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class XtreamDtoParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `xtream category decodes is_adult from provider payload`() {
        val category = json.decodeFromString<XtreamCategory>(
            """
            {
              "category_id": "28",
              "category_name": "|+18| ADULTS LIVE",
              "is_adult": "1"
            }
            """.trimIndent()
        )

        assertThat(category.isAdult).isTrue()
    }

    @Test
    fun `xtream stream and vod payloads decode nullable is_adult`() {
        val stream = json.decodeFromString<XtreamStream>(
            """
            {
              "name": "Adult Channel",
              "stream_id": 99,
              "is_adult": 1
            }
            """.trimIndent()
        )
        val vod = json.decodeFromString<XtreamVodMovieData>(
            """
            {
              "stream_id": 123,
              "name": "Adult Movie",
              "is_adult": "true"
            }
            """.trimIndent()
        )

        assertThat(stream.isAdult).isTrue()
        assertThat(vod.isAdult).isTrue()
    }

      @Test
      fun `xtream thin live row ignores rich provider fields while decoding sync fields`() {
        val row = json.decodeFromString<XtreamLiveStreamRow>(
          """
          {
            "num": "12",
            "name": "Live Channel",
            "stream_id": "777",
            "stream_icon": "https://img.example.test/live.png",
            "epg_channel_id": "live.us",
            "category_id": "0",
            "category_name": "News",
            "category_ids": ["123", "456"],
            "tv_archive": "1",
            "tv_archive_duration": "3",
            "container_extension": ".m3u8",
            "is_adult": "0",
            "direct_source": "https://cdn.example.test/live/777/master.m3u8?token=abc",
            "cover_big": "https://img.example.test/cover.jpg",
            "rating": "9.1",
            "tmdb": "999",
            "youtube_trailer": "abc"
          }
          """.trimIndent()
        )

        assertThat(row.num).isEqualTo(12)
        assertThat(row.name).isEqualTo("Live Channel")
        assertThat(row.streamId).isEqualTo(777L)
        assertThat(row.streamIcon).isEqualTo("https://img.example.test/live.png")
        assertThat(row.epgChannelId).isEqualTo("live.us")
        assertThat(row.categoryId).isEqualTo("0")
        assertThat(row.categoryName).isEqualTo("News")
        assertThat(row.categoryIds).containsExactly("123", "456").inOrder()
        assertThat(row.tvArchive).isEqualTo(1)
        assertThat(row.tvArchiveDuration).isEqualTo(3)
        assertThat(row.containerExtension).isEqualTo(".m3u8")
        assertThat(row.isAdult).isFalse()
      }

    @Test
    fun `xtream series payload decodes false and missing is_adult safely`() {
        val explicitFalse = json.decodeFromString<XtreamSeriesItem>(
            """
            {
              "series_id": 77,
              "name": "Series",
              "is_adult": "0"
            }
            """.trimIndent()
        )
        val missing = json.decodeFromString<XtreamSeriesItem>(
            """
            {
              "series_id": 78,
              "name": "Series Two"
            }
            """.trimIndent()
        )

        assertThat(explicitFalse.isAdult).isFalse()
        assertThat(missing.isAdult).isNull()
    }
}