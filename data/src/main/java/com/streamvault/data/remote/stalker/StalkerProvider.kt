package com.streamvault.data.remote.stalker

import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.provider.IptvProvider
import com.streamvault.domain.util.ChannelNormalizer
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StalkerProvider(
    override val providerId: Long,
    private val api: StalkerApiService,
    private val portalUrl: String,
    private val macAddress: String,
    private val deviceProfile: String,
    private val timezone: String,
    private val locale: String
) : IptvProvider {

    private data class CategorySeed(
        val id: Long,
        val rawId: String,
        val name: String
    )

    private val authMutex = Mutex()
    private var sessionCache: StalkerSession? = null
    private var accountProfileCache: StalkerProviderProfile? = null
    private val categoryCache = mutableMapOf<ContentType, List<CategorySeed>>()

    override suspend fun authenticate(): Result<Provider> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val profile = authResult.data.second
                val hostLabel = portalUrl.substringAfter("://").substringBefore('/').ifBlank { "portal" }
                val providerName = profile.accountName?.takeIf { it.isNotBlank() }
                    ?: "${normalizedMacAddress().takeLast(8)}@$hostLabel"
                Result.success(
                    Provider(
                        id = providerId,
                        name = providerName,
                        type = ProviderType.STALKER_PORTAL,
                        serverUrl = StalkerUrlFactory.normalizePortalUrl(portalUrl),
                        stalkerMacAddress = normalizedMacAddress(),
                        stalkerDeviceProfile = normalizedDeviceProfile(),
                        stalkerDeviceTimezone = normalizedTimezone(),
                        stalkerDeviceLocale = normalizedLocale(),
                        maxConnections = profile.maxConnections ?: 1,
                        expirationDate = profile.expirationDate,
                        apiVersion = "Stalker/MAG Portal",
                        status = when (profile.statusLabel?.trim()?.lowercase(Locale.ROOT)) {
                            "active", "enabled", "1" -> ProviderStatus.ACTIVE
                            "expired", "0" -> ProviderStatus.EXPIRED
                            "disabled", "blocked", "banned" -> ProviderStatus.DISABLED
                            else -> ProviderStatus.ACTIVE
                        }
                    )
                )
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getLiveCategories(): Result<List<Category>> =
        mapCategories(ContentType.LIVE) { session, profile ->
            api.getLiveCategories(session, profile)
        }

    override suspend fun getLiveStreams(categoryId: Long?): Result<List<Channel>> =
        mapItems(ContentType.LIVE, categoryId) { session, profile, rawCategoryId ->
            api.getLiveStreams(session, profile, rawCategoryId)
        }.mapData { items ->
            items.mapNotNull(::toChannel)
        }

    override suspend fun getVodCategories(): Result<List<Category>> =
        mapCategories(ContentType.MOVIE) { session, profile ->
            api.getVodCategories(session, profile)
        }

    override suspend fun getVodStreams(categoryId: Long?): Result<List<Movie>> =
        mapItems(ContentType.MOVIE, categoryId) { session, profile, rawCategoryId ->
            api.getVodStreams(session, profile, rawCategoryId)
        }.mapData { items ->
            items.mapNotNull(::toMovie)
        }

    override suspend fun getVodInfo(vodId: Long): Result<Movie> {
        return when (val moviesResult = getVodStreams(null)) {
            is Result.Success -> moviesResult.data
                .firstOrNull { movie ->
                    movie.streamId == vodId || movie.id == vodId
                }?.let { movie -> Result.success(movie) }
                ?: Result.error("Movie not found")
            is Result.Error -> Result.error(moviesResult.message, moviesResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getSeriesCategories(): Result<List<Category>> =
        mapCategories(ContentType.SERIES) { session, profile ->
            api.getSeriesCategories(session, profile)
        }

    override suspend fun getSeriesList(categoryId: Long?): Result<List<Series>> =
        mapItems(ContentType.SERIES, categoryId) { session, profile, rawCategoryId ->
            api.getSeries(session, profile, rawCategoryId)
        }.mapData { items ->
            items.mapNotNull(::toSeries)
        }

    override suspend fun getSeriesInfo(seriesId: Long): Result<Series> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val detailsResult = api.getSeriesDetails(session, currentDeviceProfile(), seriesId.toString())) {
                    is Result.Success -> Result.success(detailsResult.data.toSeries())
                    is Result.Error -> Result.error(detailsResult.message, detailsResult.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getEpg(channelId: String): Result<List<Program>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val epgResult = api.getEpg(session, currentDeviceProfile(), channelId)) {
                    is Result.Success -> Result.success(epgResult.data.map { it.toProgram() })
                    is Result.Error -> Result.error(epgResult.message, epgResult.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getShortEpg(channelId: String, limit: Int): Result<List<Program>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val epgResult = api.getShortEpg(session, currentDeviceProfile(), channelId, limit)) {
                    is Result.Success -> Result.success(epgResult.data.map { it.toProgram() })
                    is Result.Error -> Result.error(epgResult.message, epgResult.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    suspend fun resolvePlaybackUrl(kind: StalkerStreamKind, cmd: String): Result<String> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val linkResult = api.createLink(session, currentDeviceProfile(), kind, cmd)) {
                    is Result.Success -> Result.success(linkResult.data)
                    is Result.Error -> Result.error(linkResult.message, linkResult.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String {
        throw UnsupportedOperationException("Stalker stream URLs require a command token context.")
    }

    override suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String? = null

    private suspend fun mapCategories(
        type: ContentType,
        loader: suspend (StalkerSession, StalkerDeviceProfile) -> Result<List<StalkerCategoryRecord>>
    ): Result<List<Category>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                when (val result = loader(session, currentDeviceProfile())) {
                    is Result.Success -> {
                        val categories = result.data.map { record ->
                            val id = syntheticCategoryId(type, record.id.ifBlank { record.name })
                            CategorySeed(
                                id = id,
                                rawId = record.id,
                                name = record.name
                            )
                        }
                        categoryCache[type] = categories
                        Result.success(
                            categories.map { seed ->
                                Category(
                                    id = seed.id,
                                    name = seed.name,
                                    type = type,
                                    isAdult = AdultContentClassifier.isAdultCategoryName(seed.name)
                                )
                            }
                        )
                    }
                    is Result.Error -> Result.error(result.message, result.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun mapItems(
        type: ContentType,
        categoryId: Long?,
        loader: suspend (StalkerSession, StalkerDeviceProfile, String?) -> Result<List<StalkerItemRecord>>
    ): Result<List<StalkerItemRecord>> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, _) = authResult.data
                val rawCategoryId = resolveRawCategoryId(type, categoryId)
                loader(session, currentDeviceProfile(), rawCategoryId)
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun ensureAuthenticated(): Result<Pair<StalkerSession, StalkerProviderProfile>> =
        authMutex.withLock {
            val cachedSession = sessionCache
            val cachedProfile = accountProfileCache
            if (cachedSession != null && cachedProfile != null) {
                return@withLock Result.success(cachedSession to cachedProfile)
            }

            val profile = buildStalkerDeviceProfile(
                portalUrl = portalUrl,
                macAddress = normalizedMacAddress(),
                deviceProfile = normalizedDeviceProfile(),
                timezone = normalizedTimezone(),
                locale = normalizedLocale()
            )
            when (val authResult = api.authenticate(profile)) {
                is Result.Success -> {
                    sessionCache = authResult.data.first
                    accountProfileCache = authResult.data.second
                    Result.success(authResult.data)
                }
                is Result.Error -> Result.error(authResult.message, authResult.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }

    private fun currentDeviceProfile(): StalkerDeviceProfile {
        return buildStalkerDeviceProfile(
            portalUrl = portalUrl,
            macAddress = normalizedMacAddress(),
            deviceProfile = normalizedDeviceProfile(),
            timezone = normalizedTimezone(),
            locale = normalizedLocale()
        )
    }

    private suspend fun resolveRawCategoryId(type: ContentType, categoryId: Long?): String? {
        val normalizedType = when (type) {
            ContentType.SERIES_EPISODE -> ContentType.SERIES
            else -> type
        }
        val targetId = categoryId ?: return null
        val cached = categoryCache[normalizedType]
        if (cached != null) {
            return cached.firstOrNull { it.id == targetId }?.rawId
        }
        when (val categoriesResult = when (normalizedType) {
            ContentType.LIVE -> getLiveCategories()
            ContentType.MOVIE -> getVodCategories()
            ContentType.SERIES -> getSeriesCategories()
            ContentType.SERIES_EPISODE -> Result.success(emptyList())
        }) {
            is Result.Success -> return categoryCache[normalizedType]?.firstOrNull { it.id == targetId }?.rawId
            else -> return null
        }
    }

    private fun toChannel(item: StalkerItemRecord): Channel? {
        val numericId = stableItemId(ContentType.LIVE, item.id)
        val category = resolveCategory(ContentType.LIVE, item.categoryId, item.categoryName)
        val directStreamUrl = item.streamUrl
            ?.substringAfter(' ', missingDelimiterValue = item.streamUrl)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val streamUrl = item.cmd?.takeIf { it.isNotBlank() }?.let { cmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.LIVE,
                itemId = numericId,
                cmd = cmd,
                containerExtension = item.containerExtension
            )
        } ?: directStreamUrl
            ?: return null
        val resolvedName = item.name.ifBlank { "Channel $numericId" }
        return Channel(
            id = 0L,
            name = resolvedName,
            logoUrl = item.logoUrl,
            categoryId = category.id,
            categoryName = category.name,
            streamUrl = streamUrl,
            epgChannelId = item.epgChannelId ?: item.id,
            number = item.number.coerceAtLeast(0),
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            logicalGroupId = ChannelNormalizer.getLogicalGroupId(resolvedName, providerId),
            streamId = numericId
        )
    }

    private fun toMovie(item: StalkerItemRecord): Movie? {
        val numericId = stableItemId(ContentType.MOVIE, item.id)
        val category = resolveCategory(ContentType.MOVIE, item.categoryId, item.categoryName)
        val directStreamUrl = item.streamUrl
            ?.substringAfter(' ', missingDelimiterValue = item.streamUrl)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val streamUrl = item.cmd?.takeIf { it.isNotBlank() }?.let { cmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.MOVIE,
                itemId = numericId,
                cmd = cmd,
                containerExtension = item.containerExtension
            )
        } ?: directStreamUrl
            ?: return null
        return Movie(
            id = 0L,
            name = item.name.ifBlank { "Movie $numericId" },
            posterUrl = item.logoUrl,
            backdropUrl = item.backdropUrl,
            categoryId = category.id,
            categoryName = category.name,
            streamUrl = streamUrl,
            containerExtension = item.containerExtension,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating.coerceIn(0f, 10f),
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            streamId = numericId,
            addedAt = item.addedAt
        )
    }

    private fun toSeries(item: StalkerItemRecord): Series? {
        val numericId = stableItemId(ContentType.SERIES, item.id)
        val category = resolveCategory(ContentType.SERIES, item.categoryId, item.categoryName)
        return Series(
            id = 0L,
            name = item.name.ifBlank { "Series $numericId" },
            posterUrl = item.logoUrl,
            backdropUrl = item.backdropUrl,
            categoryId = category.id,
            categoryName = category.name,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating.coerceIn(0f, 10f),
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            lastModified = item.addedAt,
            seriesId = item.id.toLongOrNull() ?: numericId
        )
    }

    private fun StalkerSeriesDetails.toSeries(): Series {
        val baseSeries = toSeries(series) ?: Series(
            id = 0L,
            name = series.name.ifBlank { "Series ${series.id}" },
            providerId = providerId,
            seriesId = series.id.toLongOrNull() ?: stableItemId(ContentType.SERIES, series.id)
        )
        val mappedSeasons = seasons
            .sortedBy { it.seasonNumber }
            .map { season ->
                val episodes = season.episodes.mapIndexed { index, episode ->
                    episode.toEpisode(
                        fallbackSeriesId = baseSeries.seriesId,
                        fallbackSeasonNumber = season.seasonNumber,
                        fallbackEpisodeNumber = index + 1
                    )
                }
                Season(
                    seasonNumber = season.seasonNumber.coerceAtLeast(0),
                    name = season.name.ifBlank { "Season ${season.seasonNumber}" },
                    coverUrl = season.coverUrl,
                    episodes = episodes,
                    episodeCount = episodes.size
                )
            }
        return baseSeries.copy(seasons = mappedSeasons)
    }

    private fun StalkerEpisodeRecord.toEpisode(
        fallbackSeriesId: Long,
        fallbackSeasonNumber: Int,
        fallbackEpisodeNumber: Int
    ): Episode {
        val numericId = stableItemId(ContentType.SERIES_EPISODE, id)
        val directStreamUrl = cmd
            ?.substringAfter(' ', missingDelimiterValue = cmd)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val resolvedStreamUrl = cmd?.takeIf { it.isNotBlank() }?.let { resolvedCmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.EPISODE,
                itemId = numericId,
                cmd = resolvedCmd,
                containerExtension = containerExtension
            )
        } ?: directStreamUrl.orEmpty()
        return Episode(
            id = 0L,
            title = title.ifBlank { "Episode $fallbackEpisodeNumber" },
            episodeNumber = episodeNumber.coerceAtLeast(1),
            seasonNumber = seasonNumber.takeIf { it > 0 } ?: fallbackSeasonNumber.coerceAtLeast(1),
            streamUrl = resolvedStreamUrl,
            containerExtension = containerExtension,
            coverUrl = coverUrl,
            plot = plot,
            durationSeconds = durationSeconds.coerceAtLeast(0),
            rating = rating.coerceIn(0f, 10f),
            releaseDate = releaseDate,
            seriesId = fallbackSeriesId,
            providerId = providerId,
            isAdult = false,
            isUserProtected = false,
            episodeId = id.toLongOrNull() ?: numericId
        )
    }

    private fun StalkerProgramRecord.toProgram(): Program =
        Program(
            id = id.toLongOrNull() ?: stableItemId(ContentType.LIVE, id),
            channelId = channelId,
            title = title,
            description = description,
            startTime = startTimeMillis,
            endTime = endTimeMillis,
            hasArchive = hasArchive,
            isNowPlaying = isNowPlaying,
            providerId = providerId
        )

    private fun resolveCategory(type: ContentType, rawId: String?, rawName: String?): CategorySeed {
        val normalizedName = rawName?.trim().takeUnless { it.isNullOrBlank() }
        val normalizedRawId = rawId?.trim().takeUnless { it.isNullOrBlank() }
        val cached = categoryCache[type]
            ?.firstOrNull { category ->
                category.rawId == normalizedRawId ||
                    (normalizedName != null && category.name.equals(normalizedName, ignoreCase = true))
            }
        if (cached != null) {
            return cached
        }
        val fallbackSeed = normalizedRawId ?: normalizedName ?: "uncategorized"
        return CategorySeed(
            id = syntheticCategoryId(type, fallbackSeed),
            rawId = normalizedRawId ?: fallbackSeed,
            name = normalizedName ?: "Category $fallbackSeed"
        )
    }

    private fun stableItemId(type: ContentType, rawId: String): Long =
        rawId.trim().toLongOrNull()?.takeIf { it > 0 } ?: syntheticCategoryId(type, rawId)

    private fun syntheticCategoryId(type: ContentType, seed: String): Long {
        val normalized = "$providerId/${type.name}/${seed.trim().lowercase(Locale.ROOT)}"
        return (normalized.hashCode().toLong() and 0x7fff_ffffL).coerceAtLeast(1L)
    }

    private fun normalizedMacAddress(): String =
        macAddress.trim().uppercase(Locale.ROOT)

    private fun normalizedDeviceProfile(): String =
        deviceProfile.trim().ifBlank { "MAG250" }

    private fun normalizedTimezone(): String =
        timezone.trim().ifBlank { java.util.TimeZone.getDefault().id }

    private fun normalizedLocale(): String =
        locale.trim().ifBlank { Locale.getDefault().language.ifBlank { "en" } }
}

private inline fun <T, R> Result<T>.mapData(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.success(transform(data))
    is Result.Error -> Result.error(message, exception)
    is Result.Loading -> Result.error("Unexpected loading state")
}
