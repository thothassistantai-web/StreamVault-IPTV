package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class OkHttpXtreamApiServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `get classifies 401 as authentication error`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(statusCode = 401, body = "{}"),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamAuthenticationException::class.java)
        assertThat(failure).hasMessageThat().contains("HTTP 401")
    }

    @Test
    fun `get classifies malformed JSON as parsing error`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(statusCode = 200, body = "{not-json}"),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(failure).hasMessageThat().contains("Malformed JSON")
    }

    @Test
    fun `get classifies transport failures as network errors`() = runTest {
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { throw SocketTimeoutException("timed out") }
                .build(),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamNetworkException::class.java)
        assertThat(failure).hasMessageThat().contains("timed out")
    }

    @Test
    fun `streamVodStreams decodes array incrementally`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(
                statusCode = 200,
                body = """
                    [
                      {"stream_id": "101", "name": "Movie One", "container_extension": "mp4"},
                      {"stream_id": "102", "name": "Movie Two", "container_extension": "mkv"}
                    ]
                """.trimIndent()
            ),
            json = json
        )
        val seenIds = mutableListOf<Long>()

        val count = service.streamVodStreams("https://example.test/player_api.php?action=get_vod_streams") { item ->
            seenIds += item.streamId
        }

        assertThat(count).isEqualTo(2)
        assertThat(seenIds).containsExactly(101L, 102L).inOrder()
    }

    @Test
    fun `streamLiveStreamRows decodes thin live rows incrementally`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(
                statusCode = 200,
                body = """
                    [
                      {
                        "stream_id": "101",
                        "name": "Live One",
                        "category_ids": ["10"],
                        "direct_source": "https://cdn.example.test/live/101/master.m3u8?token=abc",
                        "rating": "9.1"
                      },
                      {
                        "stream_id": "102",
                        "name": "Live Two",
                        "container_extension": "ts",
                        "cover_big": "https://img.example.test/cover.jpg"
                      }
                    ]
                """.trimIndent()
            ),
            json = json
        )
        val seen = mutableListOf<Pair<Long, String>>()

        val count = service.streamLiveStreamRows("https://example.test/player_api.php?action=get_live_streams") { row ->
            seen += row.streamId to row.name
        }

        assertThat(count).isEqualTo(2)
        assertThat(seen).containsExactly(101L to "Live One", 102L to "Live Two").inOrder()
    }

    @Test
    fun `get applies request profile user agent`() = runTest {
        var seenUserAgent: String? = null
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seenUserAgent = chain.request().header("User-Agent")
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test")
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )

        service.getLiveCategories(
            endpoint = "https://example.test/player_api.php",
            requestProfile = HttpRequestProfile(userAgent = "StreamVaultTest/1.0", ownerTag = "provider:7/xtream")
        )

        assertThat(seenUserAgent).isEqualTo("StreamVaultTest/1.0")
    }

    @Test
    fun `series info parser tolerates flattened info and nested episode objects`() {
        val payload = """
            {
              "name": "Example Series",
              "cover_big": "https://img.example.test/cover.jpg",
              "releasedate": "2024-04-01",
              "episodes": {
                "1": {
                  "101": {
                    "id": "101",
                    "episode_num": "1",
                    "title": "Pilot",
                    "season": "1",
                    "container_extension": "mp4",
                    "info": {
                      "plot": "First episode"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<XtreamSeriesInfoResponse>(payload)

        assertThat(response.info).isNotNull()
        assertThat(response.info?.name).isEqualTo("Example Series")
        assertThat(response.info?.releaseDateAlt).isEqualTo("2024-04-01")
        assertThat(response.episodes.keys).containsExactly("1")
        assertThat(response.episodes["1"]).hasSize(1)
        assertThat(response.episodes["1"]?.first()?.title).isEqualTo("Pilot")
    }

    private fun clientReturning(statusCode: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(Request.Builder().url(chain.request().url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}
