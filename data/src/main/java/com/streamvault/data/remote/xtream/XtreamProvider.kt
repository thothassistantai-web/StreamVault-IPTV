package com.streamvault.data.remote.xtream

import android.util.Log
import com.streamvault.data.remote.dto.*
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.domain.model.*
import com.streamvault.domain.provider.IptvProvider
import com.streamvault.domain.util.ChannelNormalizer
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Xtream Codes provider implementation.
 * Converts Xtream API responses to domain models.
 */
class XtreamProvider(
    override val providerId: Long,
    private val api: XtreamApiService,
    private val serverUrl: String,
    private val username: String,
    private val password: String
) : IptvProvider {

    private var serverInfo: XtreamServerInfo? = null
    private val adultCategoryCache = mutableMapOf<ContentType, Set<Long>>()
    private val adultCategoryCacheMutex = Mutex()

    override suspend fun authenticate(): Result<Provider> = try {
        val response = api.authenticate(
            XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password)
        )
        serverInfo = response.serverInfo

        if (response.userInfo.auth != 1) {
            Result.error("Authentication failed: ${response.userInfo.message}")
        } else {
            // Parse expiration date
            val expDateStr = response.userInfo.expDate
            val expDate = when {
                expDateStr == null -> null
                expDateStr.equals("Unlimited", ignoreCase = true) -> Long.MAX_VALUE
                expDateStr.equals("null", ignoreCase = true) -> null
                // Try as timestamp (seconds)
                expDateStr.toLongOrNull() != null -> expDateStr.toLong() * 1000
                // Try as Date String (yyyy-MM-dd, etc.) - simple fallback
                else -> {
                    try {
                        // formats: yyyy-MM-dd HH:mm:ss, yyyy-MM-dd, etc.
                        // For now, let's try a few common patterns or just use a generic parser if available.
                        // Since we don't have a heavy date library, let's try basic java.text.SimpleDateFormat
                        val formats = listOf(
                            "yyyy-MM-dd HH:mm:ss",
                            "yyyy-MM-dd"
                        )
                        var parsed: Long? = null
                        for (fmt in formats) {
                            try {
                                parsed = java.text.SimpleDateFormat(fmt, java.util.Locale.ROOT).parse(expDateStr)?.time
                                if (parsed != null) break
                            } catch (_: Exception) {}
                        }
                        parsed
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            Result.success(
                Provider(
                    id = providerId,
                    name = "$username@${serverUrl.substringAfter("://").substringBefore("/")}",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    maxConnections = response.userInfo.maxConnections.toIntOrNull() ?: 1,
                    expirationDate = expDate,
                    status = when (response.userInfo.status) {
                        "Active" -> ProviderStatus.ACTIVE
                        "Expired" -> ProviderStatus.EXPIRED
                        "Disabled" -> ProviderStatus.DISABLED
                        else -> {
                            Log.w("XtreamProvider", "Unknown account status: ${response.userInfo.status}")
                            ProviderStatus.UNKNOWN
                        }
                    }
                )
            )
        }
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Authentication failed", e), e)
    }

    // ── Live TV ────────────────────────────────────────────────────

    override suspend fun getLiveCategories(): Result<List<Category>> = try {
        val categories = api.getLiveCategories(
            XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_live_categories")
        )
        cacheAdultCategoryIds(ContentType.LIVE, categories)
        Result.success(categories.map { it.toDomain(ContentType.LIVE) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load live categories", e), e)
    }

    override suspend fun getLiveStreams(categoryId: Long?): Result<List<Channel>> = try {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.LIVE)
        val streams = api.getLiveStreams(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_live_streams",
                extraQueryParams = mapOf("category_id" to categoryId?.toString())
            )
        )
        Result.success(streams.map { it.toChannel(adultCategoryIds) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load live streams", e), e)
    }

    // ── VOD ────────────────────────────────────────────────────────

    override suspend fun getVodCategories(): Result<List<Category>> = try {
        val categories = api.getVodCategories(
            XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_vod_categories")
        )
        cacheAdultCategoryIds(ContentType.MOVIE, categories)
        Result.success(categories.map { it.toDomain(ContentType.MOVIE) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load VOD categories", e), e)
    }

    override suspend fun getVodStreams(categoryId: Long?): Result<List<Movie>> = try {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.MOVIE)
        val streams = api.getVodStreams(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_vod_streams",
                extraQueryParams = mapOf("category_id" to categoryId?.toString())
            )
        )
        Result.success(streams.map { it.toMovie(adultCategoryIds) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load VOD", e), e)
    }

    override suspend fun getVodInfo(vodId: Long): Result<Movie> = try {
        val response = api.getVodInfo(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_vod_info",
                extraQueryParams = mapOf("vod_id" to vodId.toString())
            )
        )
        val movieData = response.movieData
        val info = response.info

        if (movieData == null) {
            Result.error("Movie not found")
        } else {
            Result.success(
                Movie(
                    id = movieData.streamId,
                    name = movieData.name,
                    posterUrl = info?.movieImage,
                    backdropUrl = info?.backdropPath?.firstOrNull(),
                    categoryId = movieData.categoryId?.toLongOrNull(),
                    containerExtension = movieData.containerExtension,
                    plot = info?.plot,
                    cast = info?.cast,
                    director = info?.director,
                    genre = info?.genre,
                    releaseDate = info?.releaseDate,
                    duration = info?.duration,
                    durationSeconds = info?.durationSecs ?: 0,
                    rating = info?.rating?.toFloatOrNull() ?: 0f,
                    tmdbId = info?.tmdbId,
                    youtubeTrailer = info?.youtubeTrailer,
                    providerId = providerId,
                    streamUrl = buildMovieStreamUrl(movieData.streamId, movieData.containerExtension),
                    streamId = movieData.streamId,
                    isAdult = resolveAdultFlag(
                        categoryId = movieData.categoryId?.toLongOrNull(),
                        categoryName = null,
                        adultCategoryIds = loadAdultCategoryIds(ContentType.MOVIE)
                    )
                )
            )
        }
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load movie details", e), e)
    }

    // ── Series ─────────────────────────────────────────────────────

    override suspend fun getSeriesCategories(): Result<List<Category>> = try {
        val categories = api.getSeriesCategories(
            XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_series_categories")
        )
        cacheAdultCategoryIds(ContentType.SERIES, categories)
        Result.success(categories.map { it.toDomain(ContentType.SERIES) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load series categories", e), e)
    }

    override suspend fun getSeriesList(categoryId: Long?): Result<List<Series>> = try {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.SERIES)
        val items = api.getSeriesList(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_series",
                extraQueryParams = mapOf("category_id" to categoryId?.toString())
            )
        )
        Result.success(items.map { it.toDomain(adultCategoryIds) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load series", e), e)
    }

    override suspend fun getSeriesInfo(seriesId: Long): Result<Series> = try {
        val response = api.getSeriesInfo(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_series_info",
                extraQueryParams = mapOf("series_id" to seriesId.toString())
            )
        )
        val info = response.info

        if (info == null) {
            Result.error("Series not found")
        } else {
            val adultCategoryIds = loadAdultCategoryIds(ContentType.SERIES)
            val isAdult = resolveAdultFlag(
                categoryId = info.categoryId?.toLongOrNull(),
                categoryName = null,
                adultCategoryIds = adultCategoryIds
            )
            val seasons = response.episodes.map { (seasonNum, episodes) ->
                Season(
                    seasonNumber = seasonNum.toIntOrNull() ?: 0,
                    name = "Season $seasonNum",
                    coverUrl = response.seasons.find {
                        it.seasonNumber == (seasonNum.toIntOrNull() ?: 0)
                    }?.cover,
                    episodes = episodes.map { ep ->
                        Episode(
                            id = ep.id.toLongOrNull() ?: 0,
                            title = ep.title.ifBlank { ep.info?.name ?: "Episode ${ep.episodeNum}" },
                            episodeNumber = ep.episodeNum,
                            seasonNumber = ep.season,
                            containerExtension = ep.containerExtension,
                            coverUrl = ep.info?.movieImage,
                            plot = ep.info?.plot,
                            duration = ep.info?.duration,
                            durationSeconds = ep.info?.durationSecs ?: 0,
                            rating = ep.info?.rating?.toFloatOrNull() ?: 0f,
                            releaseDate = ep.info?.releaseDate,
                            seriesId = seriesId,
                            providerId = providerId,
                            streamUrl = XtreamUrlFactory.buildInternalStreamUrl(
                                providerId = providerId,
                                kind = XtreamStreamKind.SERIES,
                                streamId = ep.id.toLongOrNull() ?: 0,
                                containerExtension = ep.containerExtension
                            ),
                            isAdult = isAdult,
                            isUserProtected = false,
                            episodeId = ep.id.toLongOrNull() ?: 0
                        )
                    },
                    episodeCount = episodes.size
                )
            }.sortedBy { it.seasonNumber }

            Result.success(
                info.toDomain(adultCategoryIds).copy(
                    seasons = seasons,
                    providerId = providerId,
                    isAdult = isAdult
                )
            )
        }
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load series details", e), e)
    }

    // ── EPG ────────────────────────────────────────────────────────

    override suspend fun getEpg(channelId: String): Result<List<Program>> = try {
        val streamId = channelId.toLongOrNull() ?: 0
        val response = api.getFullEpg(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_simple_data_table",
                extraQueryParams = mapOf("stream_id" to streamId.toString())
            )
        )
        Result.success(response.epgListings.map { it.toDomain() })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load EPG", e), e)
    }

    override suspend fun getShortEpg(channelId: String, limit: Int): Result<List<Program>> = try {
        val streamId = channelId.toLongOrNull() ?: 0
        val response = api.getShortEpg(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_short_epg",
                extraQueryParams = mapOf(
                    "stream_id" to streamId.toString(),
                    "limit" to limit.toString()
                )
            )
        )
        Result.success(response.epgListings.map { it.toDomain() })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load EPG", e), e)
    }

    // ── Stream URLs ────────────────────────────────────────────────

    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String {
        return XtreamUrlFactory.buildPlaybackUrl(serverUrl, username, password, XtreamStreamKind.LIVE, streamId)
    }

    private fun buildMovieStreamUrl(streamId: Long, containerExtension: String?): String {
        return XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = serverUrl,
            username = username,
            password = password,
            kind = XtreamStreamKind.MOVIE,
            streamId = streamId,
            containerExtension = containerExtension
        )
    }

    private fun buildSeriesStreamUrl(streamId: Long, containerExtension: String?): String {
        return XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = serverUrl,
            username = username,
            password = password,
            kind = XtreamStreamKind.SERIES,
            streamId = streamId,
            containerExtension = containerExtension
        )
    }

    override suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String? {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.ROOT)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC") // Xtream servers typically use UTC for EPG timeshifts
        val formattedStart = dateFormat.format(java.util.Date(start * 1000L))
        val durationMinutes = (end - start) / 60
        return XtreamUrlFactory.buildCatchUpUrl(
            serverUrl = serverUrl,
            username = username,
            password = password,
            durationMinutes = durationMinutes,
            formattedStart = formattedStart,
            streamId = streamId
        )
    }

    // ── Mappers ────────────────────────────────────────────────────
    
    private suspend fun loadAdultCategoryIds(type: ContentType): Set<Long> {
        adultCategoryCacheMutex.withLock {
            adultCategoryCache[type]?.let { return it }
            val categories = when (type) {
                ContentType.LIVE -> api.getLiveCategories(
                    XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_live_categories")
                )
                ContentType.MOVIE -> api.getVodCategories(
                    XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_vod_categories")
                )
                ContentType.SERIES -> api.getSeriesCategories(
                    XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_series_categories")
                )
                ContentType.SERIES_EPISODE -> emptyList()
            }
            return categories
                .filter { AdultContentClassifier.isAdultCategoryName(it.categoryName) }
                .mapNotNull { it.categoryId.toLongOrNull() }
                .toSet()
                .also { adultCategoryCache[type] = it }
        }
    }

    private suspend fun cacheAdultCategoryIds(type: ContentType, categories: List<XtreamCategory>) {
        val ids = categories
            .filter { AdultContentClassifier.isAdultCategoryName(it.categoryName) }
            .mapNotNull { it.categoryId.toLongOrNull() }
            .toSet()
        adultCategoryCacheMutex.withLock {
            adultCategoryCache[type] = ids
        }
    }

    private fun resolveAdultFlag(
        categoryId: Long?,
        categoryName: String?,
        adultCategoryIds: Set<Long>
    ): Boolean {
        return (categoryId != null && categoryId in adultCategoryIds) ||
            AdultContentClassifier.isAdultCategoryName(categoryName)
    }

    private fun XtreamCategory.toDomain(type: ContentType) = Category(
        id = categoryId.toLongOrNull() ?: 0,
        name = categoryName,
        parentId = if (parentId > 0) parentId.toLong() else null,
        type = type,
        isAdult = AdultContentClassifier.isAdultCategoryName(categoryName)
    )

    private fun XtreamStream.toChannel(adultCategoryIds: Set<Long>): Channel {
        return Channel(
            id = 0,
            name = name,
            logoUrl = streamIcon,
            categoryId = categoryId?.toLongOrNull(),
            categoryName = categoryName,
            epgChannelId = epgChannelId,
            number = num,
            catchUpSupported = tvArchive == 1,
            catchUpDays = tvArchiveDuration ?: 0,
            providerId = providerId,
            streamUrl = XtreamUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = XtreamStreamKind.LIVE,
                streamId = streamId
            ),
            isAdult = resolveAdultFlag(
                categoryId = categoryId?.toLongOrNull(),
                categoryName = categoryName,
                adultCategoryIds = adultCategoryIds
            ),
            isUserProtected = false,
            logicalGroupId = ChannelNormalizer.getLogicalGroupId(name, providerId),
            streamId = streamId
        )
    }

    private fun XtreamStream.toMovie(adultCategoryIds: Set<Long>) = Movie(
        id = 0,
        name = name,
        posterUrl = streamIcon,
        categoryId = categoryId?.toLongOrNull(),
        categoryName = categoryName,
        containerExtension = containerExtension,
        rating = rating5based?.toFloatOrNull() ?: 0f,
        providerId = providerId,
        streamUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = providerId,
            kind = XtreamStreamKind.MOVIE,
            streamId = streamId,
            containerExtension = containerExtension
        ),
        isAdult = resolveAdultFlag(
            categoryId = categoryId?.toLongOrNull(),
            categoryName = categoryName,
            adultCategoryIds = adultCategoryIds
        ),
        isUserProtected = false,
        streamId = streamId
    )

    private fun XtreamSeriesItem.toDomain(adultCategoryIds: Set<Long>) = Series(
        id = 0,
        name = name,
        posterUrl = cover,
        backdropUrl = backdropPath?.firstOrNull(),
        categoryId = categoryId?.toLongOrNull(),
        plot = plot,
        cast = cast,
        director = director,
        genre = genre,
        releaseDate = releaseDate,
        rating = rating5based?.toFloatOrNull() ?: rating?.toFloatOrNull() ?: 0f,
        youtubeTrailer = youtubeTrailer,
        episodeRunTime = episodeRunTime,
        lastModified = lastModified?.toLongOrNull() ?: 0L,
        providerId = providerId,
        isAdult = resolveAdultFlag(
            categoryId = categoryId?.toLongOrNull(),
            categoryName = null,
            adultCategoryIds = adultCategoryIds
        ),
        isUserProtected = false,
        seriesId = seriesId
    )

    private fun XtreamEpgListing.toDomain(): Program {
        // Xtream sometimes base64-encodes title and description
        val decodedTitle = tryBase64Decode(title)
        val decodedDescription = tryBase64Decode(description)

        return Program(
            id = id.toLongOrNull() ?: 0,
            channelId = channelId,
            title = decodedTitle,
            description = decodedDescription,
            startTime = startTimestamp * 1000L,
            endTime = stopTimestamp * 1000L,
            lang = lang,
            hasArchive = hasArchive == 1,
            isNowPlaying = nowPlaying == 1,
            providerId = providerId
        )
    }

    private fun tryBase64Decode(value: String): String = try {
        if (value.isBlank()) value
        else {
            val decoded = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            if (decoded.any { it.isLetterOrDigit() }) decoded else value
        }
    } catch (_: Exception) {
        value
    }
}
