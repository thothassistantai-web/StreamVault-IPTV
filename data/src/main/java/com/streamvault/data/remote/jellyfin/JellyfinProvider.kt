package com.streamvault.data.remote.jellyfin

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.Result
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import java.net.URLEncoder

@Singleton
class JellyfinProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private companion object {
        private const val MOVIE_CATEGORY_ID = 1L
        private const val SERIES_CATEGORY_ID = 2L
        private const val REQUEST_TIMEOUT_SECONDS = 60L
        private const val QUICK_CONNECT_TIMEOUT_MILLIS = 120_000L
        private const val QUICK_CONNECT_POLL_INTERVAL_MILLIS = 2_000L
    }

    private val itemsResponseType = object : TypeToken<JellyfinItemsResponseDto>() {}.type
    private val seriesResponseType = object : TypeToken<JellyfinSeriesEpisodesResponseDto>() {}.type
    private val authResultType = object : TypeToken<JellyfinAuthenticationResultDto>() {}.type

    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<String> {
        return try {
            val session = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                authenticateSession(serverUrl, username, password)
            }
            Result.success(session.accessToken)
        } catch (e: Exception) {
            Result.error("Jellyfin authentication failed: ${e.message}", e)
        }
    }

    suspend fun authenticateQuickConnect(
        serverUrl: String,
        onCode: ((String) -> Unit)? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<JellyfinQuickConnectAuthenticationResult> {
        return try {
            onProgress?.invoke("Requesting Quick Connect code...")
            val initiation = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                initiateQuickConnect(serverUrl)
            }
            val secret = initiation.secret?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Jellyfin Quick Connect did not return a secret")
            val code = initiation.code?.takeIf { it.isNotBlank() } ?: secret
            onCode?.invoke(code)
            onProgress?.invoke("Waiting for approval on your Jellyfin server...")
            val deadline = System.currentTimeMillis() + QUICK_CONNECT_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                val state = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    pollQuickConnectState(serverUrl, secret)
                }
                if (state.authenticated) {
                    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        authenticateWithQuickConnect(serverUrl, secret)
                    }
                }
                delay(QUICK_CONNECT_POLL_INTERVAL_MILLIS)
            }
            Result.error("Quick Connect timed out waiting for approval")
        } catch (e: Exception) {
            Result.error("Jellyfin Quick Connect failed: ${e.message}", e)
        }
    }

    suspend fun fetchMovies(provider: Provider): Result<List<MovieEntity>> = try {
        val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fetchItems(provider, "/Items", mapOf(
                "IncludeItemTypes" to "Movie",
                "Recursive" to "true",
                "EnableImages" to "false",
                "EnableUserData" to "false",
                "Fields" to "Overview,ProviderIds,ProductionYear,PremiereDate,RunTimeTicks,Genres,CommunityRating,ImageTags,BackdropImageTags,MediaSources,DateCreated,Path"
            ))
        }
        Result.success(items.map { item -> buildMovieEntity(item, provider) })
    } catch (e: Exception) {
        Result.error("Failed to load Jellyfin movies: ${e.message}", e)
    }

    suspend fun fetchSeries(provider: Provider): Result<List<SeriesEntity>> = try {
        val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fetchItems(provider, "/Items", mapOf(
                "IncludeItemTypes" to "Series",
                "Recursive" to "true",
                "EnableImages" to "false",
                "EnableUserData" to "false",
                "Fields" to "Overview,ProviderIds,ProductionYear,PremiereDate,RunTimeTicks,Genres,CommunityRating,ImageTags,BackdropImageTags,DateCreated,DateLastMediaAdded,Path"
            ))
        }
        Result.success(items.map { item -> buildSeriesEntity(item, provider) })
    } catch (e: Exception) {
        Result.error("Failed to load Jellyfin series: ${e.message}", e)
    }

    suspend fun fetchEpisodes(provider: Provider, seriesRemoteId: String, seriesLocalId: Long): Result<List<EpisodeEntity>> = try {
        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fetchSeriesEpisodes(provider, seriesRemoteId)
        }
        Result.success(response.map { item -> buildEpisodeEntity(item, provider, seriesLocalId) })
    } catch (e: Exception) {
        Result.error("Failed to load Jellyfin episodes: ${e.message}", e)
    }

    private fun authenticateSession(serverUrl: String, username: String, password: String): JellyfinAuthenticatedSession {
        val url = "${serverUrl.trimEnd('/')}/Users/AuthenticateByName"
        val payload = gson.toJson(JellyfinAuthenticateRequestDto(username = username, password = password))
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, username))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = executeRequest(request, "Jellyfin login failed")
        val parsed = gson.fromJson<JellyfinAuthenticationResultDto>(body, authResultType)
        val token = parsed.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Jellyfin login did not return an access token")
        val userId = parsed.user?.id?.takeIf { it.isNotBlank() }
            ?: parsed.sessionInfo?.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Jellyfin login did not return a user id")
        return JellyfinAuthenticatedSession(accessToken = token, userId = userId, userName = parsed.user?.name ?: username)
    }

    private fun initiateQuickConnect(serverUrl: String): JellyfinQuickConnectInitiateResponseDto {
        val url = buildUrl(serverUrl, "/QuickConnect/Initiate", emptyMap())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, "StreamVault"))
            .header("Accept", "application/json")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return executeJsonRequest(request, object : TypeToken<JellyfinQuickConnectInitiateResponseDto>() {}.type, "Quick Connect initiation failed")
    }

    private fun pollQuickConnectState(serverUrl: String, secret: String): JellyfinQuickConnectStatusResponseDto {
        val url = buildUrl(serverUrl, "/QuickConnect/Connect", mapOf("Secret" to secret))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, "StreamVault"))
            .header("Accept", "application/json")
            .get()
            .build()
        return executeJsonRequest(request, object : TypeToken<JellyfinQuickConnectStatusResponseDto>() {}.type, "Quick Connect status failed")
    }

    private fun authenticateWithQuickConnect(serverUrl: String, secret: String): Result<JellyfinQuickConnectAuthenticationResult> {
        return try {
            val url = buildUrl(serverUrl, "/Users/AuthenticateWithQuickConnect", emptyMap())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, "StreamVault"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(gson.toJson(JellyfinQuickConnectAuthenticateRequestDto(secret)).toRequestBody("application/json".toMediaType()))
                .build()
            val body = executeRequest(request, "Quick Connect authentication failed")
            val auth = gson.fromJson<JellyfinAuthenticationResultDto>(body, authResultType)
            val accessToken = auth.accessToken?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Quick Connect did not return an access token")
            val userId = auth.user?.id?.takeIf { it.isNotBlank() }
                ?: auth.sessionInfo?.userId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Quick Connect did not return a user id")
            Result.success(JellyfinQuickConnectAuthenticationResult(accessToken, userId, auth.user?.name ?: ""))
        } catch (e: Exception) {
            Result.error("Quick Connect authentication failed: ${e.message}", e)
        }
    }

    private fun fetchItems(provider: Provider, path: String, query: Map<String, String>): List<JellyfinItemDto> {
        val url = buildUrl(provider.serverUrl, path, query)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(provider.serverUrl, provider.username, provider.password))
            .header("Accept", "application/json")
            .get()
            .build()
        val body = executeRequest(request, "Jellyfin request failed")
        if (body.isBlank()) return emptyList()
        val parsed = gson.fromJson<JellyfinItemsResponseDto>(body, itemsResponseType)
        return parsed.items.orEmpty().filter { !it.id.isNullOrBlank() }
    }

    private fun fetchSeriesEpisodes(provider: Provider, seriesRemoteId: String): List<JellyfinItemDto> {
        try {
            val url = buildUrl(provider.serverUrl, "/Shows/$seriesRemoteId/Episodes", mapOf(
                "Fields" to "Overview,ProviderIds,PremiereDate,RunTimeTicks,Genres,CommunityRating,ImageTags,MediaSources,ParentIndexNumber,IndexNumber",
                "EnableImages" to "false",
                "EnableUserData" to "false"
            ))
            val request = Request.Builder()
                .url(url)
                .header("Authorization", buildJellyfinAuthorizationHeader(provider.serverUrl, provider.username, provider.password))
                .header("Accept", "application/json")
                .get()
                .build()
            val body = executeRequest(request, "Jellyfin episodes request failed")
            if (body.isBlank()) return emptyList()
            val parsed = gson.fromJson<JellyfinSeriesEpisodesResponseDto>(body, seriesResponseType)
            return parsed.items.orEmpty().filter { !it.id.isNullOrBlank() }
        } catch (e: Exception) {
            android.util.Log.e("JellyfinEps", "fetchSeriesEpisodes error for seriesRemoteId=$seriesRemoteId url=${provider.serverUrl}: ${e::class.java.simpleName} msg='${e.message}'", e)
            throw e
        }
    }

    private fun buildMovieEntity(item: JellyfinItemDto, provider: Provider): MovieEntity {
        val remoteId = item.id.orEmpty()
        return MovieEntity(
            streamId = stableRemoteId(remoteId),
            name = item.name.orEmpty(),
            posterUrl = buildImageUrl(provider.serverUrl, remoteId, "Primary", item.imageTags?.get("Primary")),
            backdropUrl = item.backdropImageTags?.firstOrNull()?.let { tag ->
                buildImageUrl(provider.serverUrl, remoteId, "Backdrop", tag, imageIndex = 0)
            },
            categoryId = MOVIE_CATEGORY_ID,
            categoryName = "Movies",
            streamUrl = buildStreamUrl(provider.serverUrl, remoteId),
            containerExtension = item.primaryMediaSource?.container?.takeIf { it.isNotBlank() },
            plot = item.overview,
            genre = item.genres?.joinToString(", "),
            releaseDate = item.premiereDate?.take(10),
            duration = item.runTimeTicks?.let(::ticksToDurationString),
            durationSeconds = item.runTimeTicks?.let(::ticksToSeconds)?.toInt() ?: 0,
            rating = item.communityRating?.toFloat() ?: 0f,
            year = item.productionYear?.toString(),
            tmdbId = item.providerIds?.get("Tmdb")?.toLongOrNull(),
            providerId = provider.id,
            isAdult = false,
            addedAt = item.dateCreated?.let(::parseJellyfinDateMillis) ?: System.currentTimeMillis()
        )
    }

    private fun buildSeriesEntity(item: JellyfinItemDto, provider: Provider): SeriesEntity {
        val remoteId = item.id.orEmpty()
        return SeriesEntity(
            seriesId = stableRemoteId(remoteId),
            providerSeriesId = remoteId,
            name = item.name.orEmpty(),
            posterUrl = buildImageUrl(provider.serverUrl, remoteId, "Primary", item.imageTags?.get("Primary")),
            backdropUrl = item.backdropImageTags?.firstOrNull()?.let { tag ->
                buildImageUrl(provider.serverUrl, remoteId, "Backdrop", tag, imageIndex = 0)
            },
            categoryId = SERIES_CATEGORY_ID,
            categoryName = "Series",
            plot = item.overview,
            genre = item.genres?.joinToString(", "),
            releaseDate = item.premiereDate?.take(10),
            rating = item.communityRating?.toFloat() ?: 0f,
            tmdbId = item.providerIds?.get("Tmdb")?.toLongOrNull(),
            episodeRunTime = item.runTimeTicks?.let(::ticksToDurationString),
            lastModified = (item.dateLastMediaAdded?.let(::parseJellyfinDateMillis)
                ?: item.dateCreated?.let(::parseJellyfinDateMillis)
                ?: System.currentTimeMillis()).coerceAtLeast(0L),
            providerId = provider.id,
            isAdult = false
        )
    }

    private fun buildEpisodeEntity(item: JellyfinItemDto, provider: Provider, seriesLocalId: Long): EpisodeEntity {
        val remoteId = item.id.orEmpty()
        return EpisodeEntity(
            episodeId = stableRemoteId(remoteId),
            title = item.name.orEmpty(),
            episodeNumber = item.indexNumber ?: 0,
            seasonNumber = item.parentIndexNumber ?: 0,
            streamUrl = buildStreamUrl(provider.serverUrl, remoteId),
            containerExtension = item.primaryMediaSource?.container?.takeIf { it.isNotBlank() },
            coverUrl = buildImageUrl(provider.serverUrl, remoteId, "Primary", item.imageTags?.get("Primary")),
            plot = item.overview,
            duration = item.runTimeTicks?.let(::ticksToDurationString),
            durationSeconds = item.runTimeTicks?.let(::ticksToSeconds)?.toInt() ?: 0,
            rating = item.communityRating?.toFloat() ?: 0f,
            releaseDate = item.premiereDate?.take(10),
            seriesId = seriesLocalId,
            providerId = provider.id,
            isAdult = false
        )
    }

    private fun <T> executeJsonRequest(request: Request, responseType: java.lang.reflect.Type, errorPrefix: String): T {
        val body = executeRequest(request, errorPrefix)
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson<T>(body, responseType)
    }

    private fun executeRequest(request: Request, errorPrefix: String): String {
        okHttpClient.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("$errorPrefix with HTTP ${response.code}")
                }
                return response.body?.string().orEmpty()
            }
    }

    private fun buildUrl(baseUrl: String, path: String, query: Map<String, String>): String {
        val normalized = baseUrl.trimEnd('/')
        val qs = query.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "$normalized$path${if (qs.isNotBlank()) "?$qs" else ""}"
    }

    private fun buildStreamUrl(baseUrl: String, itemId: String): String =
        "${baseUrl.trimEnd('/')}/Videos/$itemId/stream"

    private fun buildImageUrl(baseUrl: String, itemId: String, imageType: String, tag: String?, imageIndex: Int? = null): String {
        val qs = buildList {
            tag?.takeIf { it.isNotBlank() }?.let { add("tag=${enc(it)}") }
        }.joinToString("&")
        val path = if (imageIndex != null) "/Items/$itemId/Images/$imageType/$imageIndex" else "/Items/$itemId/Images/$imageType"
        return "${baseUrl.trimEnd('/')}$path${if (qs.isNotBlank()) "?$qs" else ""}"
    }

    private fun stableRemoteId(value: String): Long {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (digest[i].toLong() and 0xff)
        return result and Long.MAX_VALUE
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun ticksToSeconds(ticks: Long): Long = ticks / 10_000_000L
    private fun ticksToDurationString(ticks: Long): String {
        val total = ticksToSeconds(ticks)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
    private fun parseJellyfinDateMillis(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}

private data class JellyfinAuthenticateRequestDto(
    @SerializedName("Username") val username: String,
    @SerializedName("Pw") val password: String
)

private data class JellyfinAuthenticationResultDto(
    @SerializedName("AccessToken") val accessToken: String? = null,
    @SerializedName("User") val user: JellyfinAuthenticatedUserDto? = null,
    @SerializedName("SessionInfo") val sessionInfo: JellyfinSessionInfoDto? = null
)

private data class JellyfinItemsResponseDto(
    @SerializedName("Items") val items: List<JellyfinItemDto>? = emptyList()
)

private data class JellyfinSeriesEpisodesResponseDto(
    @SerializedName("Items") val items: List<JellyfinItemDto>? = emptyList()
)

private data class JellyfinItemDto(
    @SerializedName("Id") val id: String? = null,
    @SerializedName("Name") val name: String? = null,
    @SerializedName("Overview") val overview: String? = null,
    @SerializedName("PremiereDate") val premiereDate: String? = null,
    @SerializedName("ProductionYear") val productionYear: Int? = null,
    @SerializedName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerializedName("CommunityRating") val communityRating: Double? = null,
    @SerializedName("ProviderIds") val providerIds: Map<String, String>? = null,
    @SerializedName("Genres") val genres: List<String>? = null,
    @SerializedName("DateCreated") val dateCreated: String? = null,
    @SerializedName("DateLastMediaAdded") val dateLastMediaAdded: String? = null,
    @SerializedName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerializedName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerializedName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerializedName("IndexNumber") val indexNumber: Int? = null,
    @SerializedName("MediaSources") val mediaSources: List<JellyfinMediaSourceDto>? = null
) {
    val primaryMediaSource: JellyfinMediaSourceDto? get() = mediaSources?.firstOrNull()
}

private data class JellyfinMediaSourceDto(
    @SerializedName("Id") val id: String? = null,
    @SerializedName("Container") val container: String? = null
)

private data class JellyfinAuthenticatedSession(val accessToken: String, val userId: String, val userName: String)
private data class JellyfinAuthenticatedUserDto(@SerializedName("Id") val id: String? = null, @SerializedName("Name") val name: String? = null)
private data class JellyfinSessionInfoDto(@SerializedName("UserId") val userId: String? = null)

private data class JellyfinQuickConnectInitiateResponseDto(
    @SerializedName("Code") val code: String? = null,
    @SerializedName("Secret") val secret: String? = null
)

private data class JellyfinQuickConnectStatusResponseDto(@SerializedName("Authenticated") val authenticated: Boolean = false)

private data class JellyfinQuickConnectAuthenticateRequestDto(@SerializedName("Secret") val secret: String)

data class JellyfinQuickConnectAuthenticationResult(val accessToken: String, val userId: String, val userName: String)
