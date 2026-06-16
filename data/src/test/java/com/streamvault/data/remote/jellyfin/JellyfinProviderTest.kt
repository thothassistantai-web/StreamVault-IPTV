package com.streamvault.data.remote.jellyfin

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Test

class JellyfinProviderTest {

    @Test
    fun `fetchMovies does not embed access token in artwork urls`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = when (request.url.encodedPath) {
                    "/Items" -> {
                        """
                        {
                          "Items": [
                            {
                              "Id": "movie-1",
                              "Name": "Movie 1",
                              "ImageTags": { "Primary": "poster-tag" },
                              "BackdropImageTags": ["backdrop-tag"]
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    else -> error("Unexpected request path: ${request.url.encodedPath}")
                }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = JellyfinProvider(
            okHttpClient = client,
            gson = Gson()
        )

        val result = provider.fetchMovies(
            Provider(
                name = "Jellyfin",
                type = ProviderType.JELLYFIN,
                serverUrl = "https://demo.example",
                username = "alice",
                password = "secret-token"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val movie = (result as Result.Success).data.single()
        assertThat(movie.posterUrl).isEqualTo("https://demo.example/Items/movie-1/Images/Primary?tag=poster-tag")
        assertThat(movie.backdropUrl).isEqualTo("https://demo.example/Items/movie-1/Images/Backdrop/0?tag=backdrop-tag")
        assertThat(movie.posterUrl).doesNotContain("api_key")
        assertThat(movie.backdropUrl).doesNotContain("api_key")
    }
}