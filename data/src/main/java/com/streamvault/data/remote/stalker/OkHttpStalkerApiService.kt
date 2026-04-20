package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.Result
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class OkHttpStalkerApiService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : StalkerApiService {

    override suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>> {
        var lastError: Throwable? = null
        for (loadUrl in StalkerUrlFactory.loadUrlCandidates(profile.portalUrl)) {
            val referer = StalkerUrlFactory.portalReferer(loadUrl)
            val handshakeResult = runCatching {
                requestJson(
                    url = loadUrl,
                    profile = profile,
                    referer = referer,
                    query = mapOf(
                        "type" to "stb",
                        "action" to "handshake",
                        "token" to "",
                        "JsHttpRequest" to "1-xml"
                    )
                )
            }
            val handshakePayload = handshakeResult.getOrElse { error ->
                lastError = error
                continue
            }
            val token = handshakePayload.findString("token")
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    lastError = IOException("Portal handshake did not return a token.")
                    continue
                }

            val session = StalkerSession(loadUrl = loadUrl, portalReferer = referer, token = token)
            val profileResult = runCatching {
                requestJson(
                    url = loadUrl,
                    profile = profile,
                    referer = referer,
                    token = token,
                    query = buildProfileQuery(profile)
                )
            }
            val profilePayload = profileResult.getOrElse { error ->
                lastError = error
                continue
            }

            return Result.success(session to profilePayload.toProviderProfile())
        }

        return Result.error(
            lastError?.message ?: "Failed to connect to portal.",
            lastError
        )
    }

    override suspend fun getLiveCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>> = runApiCall("Failed to load live categories") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "itv",
                "action" to "get_genres",
                "JsHttpRequest" to "1-xml"
            )
        ).toCategoryRecords()
    }

    override suspend fun getLiveStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>> = runApiCall("Failed to load live channels") {
        fetchPagedItems(
            session = session,
            profile = profile,
            baseQuery = buildMap {
                put("type", "itv")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                put("force_ch_link_check", "0")
                put("fav", "0")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
            }
        )
    }

    override suspend fun getVodCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>> = runApiCall("Failed to load movie categories") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "vod",
                "action" to "get_categories",
                "JsHttpRequest" to "1-xml"
            )
        ).toCategoryRecords()
    }

    override suspend fun getVodStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>> = runApiCall("Failed to load movies") {
        fetchPagedItems(
            session = session,
            profile = profile,
            baseQuery = buildMap {
                put("type", "vod")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            }
        ).filterNot { it.isSeries }
    }

    override suspend fun getSeriesCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>> = runApiCall("Failed to load series categories") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "series",
                "action" to "get_categories",
                "JsHttpRequest" to "1-xml"
            )
        ).toCategoryRecords()
    }

    override suspend fun getSeries(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>> = runApiCall("Failed to load series") {
        fetchPagedItems(
            session = session,
            profile = profile,
            baseQuery = buildMap {
                put("type", "series")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            }
        )
    }

    override suspend fun getSeriesDetails(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        seriesId: String
    ): Result<StalkerSeriesDetails> = runApiCall("Failed to load series details") {
        val seriesPayload = requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "series",
                "action" to "get_ordered_list",
                "JsHttpRequest" to "1-xml",
                "movie_id" to seriesId,
                "season_id" to "0",
                "episode_id" to "0"
            )
        )
        val seriesItems = seriesPayload.toItemRecords()
        val series = seriesItems.firstOrNull()
            ?: StalkerItemRecord(
                id = seriesId,
                name = "Series $seriesId"
            )
        val seedEntries = seriesPayload.extractItemEntries()
        val seasonRows = seedEntries
            .mapNotNull { entry ->
                entry.findString("season_id")
                    ?.takeIf { it.isNotBlank() && it != "0" }
                    ?.let { seasonId ->
                        seasonId to entry
                    }
            }
            .distinctBy { it.first }

        val seasons = if (seasonRows.isNotEmpty()) {
            seasonRows.map { (seasonId, entry) ->
                val episodesPayload = requestJson(
                    url = session.loadUrl,
                    profile = profile,
                    referer = session.portalReferer,
                    token = session.token,
                    query = mapOf(
                        "type" to "series",
                        "action" to "get_ordered_list",
                        "JsHttpRequest" to "1-xml",
                        "movie_id" to seriesId,
                        "season_id" to seasonId,
                        "episode_id" to "0"
                    )
                )
                entry.toSeasonRecord(episodesPayload.extractItemEntries())
            }
        } else {
            listOf(
                StalkerSeasonRecord(
                    seasonNumber = 1,
                    name = "Season 1",
                    episodes = seedEntries.mapIndexedNotNull { index, entry ->
                        entry.toEpisodeRecord(index + 1, 1)
                    }
                )
            ).filter { it.episodes.isNotEmpty() }
        }

        StalkerSeriesDetails(series = series, seasons = seasons)
    }

    override suspend fun getShortEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String,
        limit: Int
    ): Result<List<StalkerProgramRecord>> = runApiCall("Failed to load EPG") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "itv",
                "action" to "get_short_epg",
                "JsHttpRequest" to "1-xml",
                "ch_id" to channelId,
                "size" to limit.coerceAtLeast(1).toString()
            )
        ).toProgramRecords(channelId)
    }

    override suspend fun getEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String
    ): Result<List<StalkerProgramRecord>> = runApiCall("Failed to load EPG") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "itv",
                "action" to "get_epg_info",
                "JsHttpRequest" to "1-xml",
                "ch_id" to channelId,
                "period" to "6"
            )
        ).toProgramRecords(channelId)
    }

    override suspend fun createLink(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        kind: StalkerStreamKind,
        cmd: String
    ): Result<String> = runApiCall("Failed to resolve playback link") {
        val type = when (kind) {
            StalkerStreamKind.LIVE -> "itv"
            StalkerStreamKind.MOVIE, StalkerStreamKind.EPISODE -> "vod"
        }
        val payload = requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to type,
                "action" to "create_link",
                "JsHttpRequest" to "1-xml",
                "cmd" to cmd,
                "series" to "0",
                "forced_storage" to "0",
                "disable_ad" to "0",
                "download" to "0"
            )
        )
        payload.findString("cmd")
            ?.substringAfter(' ', missingDelimiterValue = payload.findString("cmd").orEmpty())
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Portal did not return a playable URL.")
    }

    private suspend fun fetchPagedItems(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        baseQuery: Map<String, String>
    ): List<StalkerItemRecord> {
        val firstPage = requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = baseQuery + ("p" to "1")
        )
        val items = mutableListOf<StalkerItemRecord>()
        items += firstPage.toItemRecords()
        val totalPages = firstPage.totalPages()
        for (page in 2..totalPages) {
            val pagePayload = requestJson(
                url = session.loadUrl,
                profile = profile,
                referer = session.portalReferer,
                token = session.token,
                query = baseQuery + ("p" to page.toString())
            )
            items += pagePayload.toItemRecords()
        }
        return items
    }

    private suspend fun requestJson(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        query: Map<String, String>,
        token: String? = null
    ): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(url, query))
            .header("User-Agent", profile.userAgent)
            .header("X-User-Agent", profile.xUserAgent)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Cookie", "mac=${profile.macAddress}; stb_lang=${profile.locale}; timezone=${profile.timezone}")
            .apply {
                token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Portal request failed with HTTP ${response.code}.")
            }
            if (body.isBlank()) {
                throw IOException("Portal returned an empty response.")
            }
            val parsed = runCatching { json.parseToJsonElement(body) }
                .getOrElse { throw IOException("Portal returned unreadable JSON.", it) }
            parsed.ensureNoPortalError()
            parsed
        }
    }

    private fun JsonElement.ensureNoPortalError() {
        val error = rootObjectOrNull()?.findString("error")
            ?: findString("error")
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: return
        throw IOException(error)
    }

    private suspend inline fun <T> runApiCall(
        message: String,
        crossinline block: suspend () -> T
    ): Result<T> = try {
        Result.success(block())
    } catch (error: Exception) {
        Result.error(error.message ?: message, error)
    }

    private fun buildUrl(baseUrl: String, query: Map<String, String>): String {
        val encodedQuery = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "${baseUrl.trimEnd('/')}?$encodedQuery"
    }

    private fun buildProfileQuery(profile: StalkerDeviceProfile): Map<String, String> {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        return mapOf(
            "type" to "stb",
            "action" to "get_profile",
            "JsHttpRequest" to "1-xml",
            "hd" to "1",
            "ver" to DEFAULT_VERSION_STRING,
            "num_banks" to "2",
            "sn" to profile.serialNumber,
            "stb_type" to profile.deviceProfile,
            "client_type" to "STB",
            "image_version" to "218",
            "video_out" to "hdmi",
            "device_id" to profile.deviceId,
            "device_id2" to profile.deviceId2,
            "signature" to profile.signature,
            "auth_second_step" to "1",
            "hw_version" to "1.7-BD-00",
            "not_valid_token" to "0",
            "metrics" to "{}",
            "hw_version_2" to profile.deviceProfile,
            "timestamp" to timestamp,
            "api_signature" to "262",
            "prehash" to "0"
        )
    }

    private fun JsonElement.totalPages(): Int {
        val payload = payloadObjectOrNull() ?: return 1
        val totalItems = payload["total_items"]?.primitiveContentOrNull()?.toIntOrNull() ?: return 1
        val pageSize = payload["max_page_items"]?.primitiveContentOrNull()?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: payload["data"]?.jsonArrayOrNull()?.size?.takeIf { it > 0 }
            ?: return 1
        return ((totalItems + pageSize - 1) / pageSize).coerceAtLeast(1).coerceAtMost(MAX_PAGE_COUNT)
    }

    private fun JsonElement.toProviderProfile(): StalkerProviderProfile {
        val payload = payloadObjectOrNull()
        return StalkerProviderProfile(
            accountName = payload?.findString("name")
                ?: payload?.findString("account")
                ?: payload?.findString("login"),
            maxConnections = payload?.findString("max_online")
                ?.toIntOrNull()
                ?: payload?.findString("max_connections")?.toIntOrNull(),
            expirationDate = payload?.findString("expire_billing_date")
                ?.let(::parseExpirationDate)
                ?: payload?.findString("end_date")?.let(::parseExpirationDate),
            statusLabel = payload?.findString("status")
        )
    }

    private fun JsonElement.toCategoryRecords(): List<StalkerCategoryRecord> {
        return extractListElements().mapNotNull { entry ->
            val id = entry.findString("id")
                ?: entry.findString("genre_id")
                ?: entry.findString("category_id")
                ?: return@mapNotNull null
            val name = entry.findString("title")
                ?: entry.findString("name")
                ?: return@mapNotNull null
            StalkerCategoryRecord(
                id = id,
                name = name,
                alias = entry.findString("alias")
            )
        }
    }

    private fun JsonElement.toItemRecords(): List<StalkerItemRecord> =
        extractItemEntries().mapNotNull { entry -> entry.toItemRecord() }

    private fun JsonElement.extractItemEntries(): List<JsonObject> =
        extractListElements().mapNotNull { it.jsonObjectOrNull() }

    private fun JsonObject.toItemRecord(): StalkerItemRecord? {
        val id = findString("id")
            ?: findString("ch_id")
            ?: findString("video_id")
            ?: findString("series_id")
            ?: return null
        val name = findString("name")
            ?: findString("title")
            ?: return null
        return StalkerItemRecord(
            id = id,
            name = name,
            categoryId = findString("tv_genre_id")
                ?: findString("category_id")
                ?: findString("genre_id"),
            categoryName = findString("category_name"),
            number = findString("number")?.toIntOrNull() ?: 0,
            logoUrl = sanitizeUrl(findString("logo"))
                ?: sanitizeUrl(findString("screenshot_uri"))
                ?: sanitizeUrl(findString("cover")),
            epgChannelId = findString("xmltv_id") ?: findString("epg_id"),
            cmd = findString("cmd"),
            streamUrl = sanitizeUrl(findString("cmd")),
            plot = findString("description") ?: findString("plot"),
            cast = findString("censored")?.takeIf { false } ?: findString("actors"),
            director = findString("director"),
            genre = findString("genres_str") ?: findString("genre"),
            releaseDate = findString("year")
                ?.takeIf { it.length == 4 }
                ?: findString("released") ?: findString("added"),
            rating = findString("rating_imdb")?.toFloatOrNull()
                ?: findString("rating")?.toFloatOrNull()
                ?: 0f,
            tmdbId = findString("tmdb_id")?.toLongOrNull(),
            youtubeTrailer = findString("trailer_url"),
            backdropUrl = sanitizeUrl(findString("backdrop_path")),
            containerExtension = extractContainerExtension(
                findString("cmd"),
                findString("container_extension")
            ),
            addedAt = findString("added")?.toLongOrNull() ?: 0L,
            isAdult = findBoolean("censored") == true,
            isSeries = findBoolean("is_series") == true || findString("is_series") == "1"
        )
    }

    private fun JsonObject.toSeasonRecord(episodeEntries: List<JsonObject>): StalkerSeasonRecord {
        val seasonNumber = findString("season_id")?.toIntOrNull()
            ?: findString("season_number")?.toIntOrNull()
            ?: 1
        val seasonName = findString("title")
            ?: findString("name")
            ?: "Season $seasonNumber"
        return StalkerSeasonRecord(
            seasonNumber = seasonNumber,
            name = seasonName,
            coverUrl = sanitizeUrl(findString("screenshot_uri")) ?: sanitizeUrl(findString("cover")),
            episodes = episodeEntries.mapIndexedNotNull { index, entry ->
                entry.toEpisodeRecord(index + 1, seasonNumber)
            }
        )
    }

    private fun JsonObject.toEpisodeRecord(
        fallbackEpisodeNumber: Int,
        fallbackSeasonNumber: Int
    ): StalkerEpisodeRecord? {
        val id = findString("id")
            ?: findString("series_id")
            ?: findString("video_id")
            ?: return null
        val title = findString("name")
            ?: findString("title")
            ?: "Episode $fallbackEpisodeNumber"
        return StalkerEpisodeRecord(
            id = id,
            title = title,
            episodeNumber = findString("series_number")?.toIntOrNull()
                ?: findString("episode_number")?.toIntOrNull()
                ?: fallbackEpisodeNumber,
            seasonNumber = findString("season_id")?.toIntOrNull()
                ?: findString("season_number")?.toIntOrNull()
                ?: fallbackSeasonNumber,
            cmd = findString("cmd"),
            coverUrl = sanitizeUrl(findString("screenshot_uri")) ?: sanitizeUrl(findString("cover")),
            plot = findString("description") ?: findString("plot"),
            durationSeconds = findString("duration")?.toIntOrNull() ?: 0,
            releaseDate = findString("added"),
            rating = findString("rating_imdb")?.toFloatOrNull()
                ?: findString("rating")?.toFloatOrNull()
                ?: 0f,
            containerExtension = extractContainerExtension(findString("cmd"), findString("container_extension"))
        )
    }

    private fun JsonElement.toProgramRecords(channelId: String): List<StalkerProgramRecord> {
        return extractListElements().mapNotNull { entry ->
            val startMillis = entry.findString("start_timestamp")?.toLongOrNull()?.times(1000L)
                ?: entry.findString("time")?.let(::parseDateTime)
                ?: return@mapNotNull null
            val endMillis = entry.findString("stop_timestamp")?.toLongOrNull()?.times(1000L)
                ?: entry.findString("time_to")?.let(::parseDateTime)
                ?: startMillis + (entry.findString("duration")?.toLongOrNull()?.times(60_000L) ?: DEFAULT_PROGRAM_DURATION_MILLIS)
            StalkerProgramRecord(
                id = entry.findString("id") ?: "$channelId:$startMillis",
                channelId = channelId,
                title = entry.findString("name")
                    ?: entry.findString("title")
                    ?: return@mapNotNull null,
                description = entry.findString("descr")
                    ?: entry.findString("description")
                    ?: "",
                startTimeMillis = startMillis,
                endTimeMillis = endMillis,
                hasArchive = entry.findBoolean("has_archive") == true || entry.findString("has_archive") == "1",
                isNowPlaying = entry.findBoolean("now_playing") == true || entry.findString("now_playing") == "1"
            )
        }
    }

    private fun JsonElement.extractListElements(): List<JsonElement> {
        val jsValue = rootObjectOrNull()?.get("js") ?: this
        return when {
            jsValue is JsonArray -> jsValue.toList()
            jsValue is JsonObject && jsValue["data"] is JsonArray -> jsValue["data"]!!.jsonArray.toList()
            jsValue is JsonObject && jsValue["items"] is JsonArray -> jsValue["items"]!!.jsonArray.toList()
            else -> emptyList()
        }
    }

    private fun JsonElement.findString(key: String): String? {
        val payload = payloadObjectOrNull()
        return payload?.findString(key)
    }

    private fun JsonElement.findBoolean(key: String): Boolean? {
        val payload = payloadObjectOrNull()
        return payload?.findBoolean(key)
    }

    private fun JsonElement.payloadObjectOrNull(): JsonObject? =
        rootObjectOrNull()?.get("js")?.jsonObjectOrNull() ?: rootObjectOrNull()

    private fun JsonElement.rootObjectOrNull(): JsonObject? = when (this) {
        is JsonObject -> this
        else -> null
    }

    private fun JsonObject.findString(key: String): String? {
        val element = this[key] ?: return null
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun JsonObject.findBoolean(key: String): Boolean? {
        val element = this[key] as? JsonPrimitive ?: return null
        return element.booleanOrNull
            ?: when (element.contentOrNull?.trim()?.lowercase(Locale.ROOT)) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> null
            }
    }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = when (this) {
        is JsonArray -> this
        else -> null
    }

    private fun JsonElement.primitiveContentOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = (this as? JsonObject)

    private fun sanitizeUrl(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun extractContainerExtension(cmd: String?, fallback: String?): String? {
        fallback?.trim()?.removePrefix(".")?.takeIf { it.isNotBlank() }?.let { return it.lowercase(Locale.ROOT) }
        val path = runCatching { URI(cmd).path }.getOrNull() ?: cmd
        val extension = path?.substringAfterLast('.', "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return extension.lowercase(Locale.ROOT)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val MAX_PAGE_COUNT = 200
        private const val DEFAULT_PROGRAM_DURATION_MILLIS = 30 * 60_000L
        private const val DEFAULT_VERSION_STRING =
            "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Oct 31 15:22:54 EEST 2018; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"
    }
}

internal fun buildStalkerDeviceProfile(
    portalUrl: String,
    macAddress: String,
    deviceProfile: String,
    timezone: String,
    locale: String
): StalkerDeviceProfile {
    val normalizedProfile = deviceProfile.ifBlank { "MAG250" }
    val normalizedTimezone = timezone.ifBlank { java.util.TimeZone.getDefault().id }
    val normalizedLocale = locale.ifBlank { Locale.getDefault().language.ifBlank { "en" } }
    val normalizedMac = macAddress.uppercase(Locale.ROOT)
    val serialSeed = normalizedMac.replace(":", "")
    val serialNumber = serialSeed.takeLast(13).padStart(13, '0')
    val deviceId = stalkerDigest("device:$normalizedProfile:$normalizedMac")
    val deviceId2 = stalkerDigest("device2:$normalizedProfile:$normalizedMac")
    val signature = stalkerDigest("signature:$normalizedProfile:$normalizedMac:$normalizedTimezone")
    return StalkerDeviceProfile(
        portalUrl = portalUrl,
        macAddress = normalizedMac,
        deviceProfile = normalizedProfile,
        timezone = normalizedTimezone,
        locale = normalizedLocale,
        serialNumber = serialNumber,
        deviceId = deviceId,
        deviceId2 = deviceId2,
        signature = signature,
        userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) $normalizedProfile stbapp ver: 2 rev: 250 Safari/533.3",
        xUserAgent = "Model: $normalizedProfile; Link: Ethernet"
    )
}

private fun stalkerDigest(seed: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(seed.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }

internal fun parseExpirationDate(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { numeric ->
        return if (numeric >= 1_000_000_000_000L) numeric else numeric * 1000L
    }
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    STALKER_DATE_TIME_FORMATTERS.forEach { formatter ->
        runCatching {
            LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()?.let { return it }
    }
    STALKER_DATE_FORMATTERS.forEach { formatter ->
        runCatching {
            LocalDate.parse(value, formatter).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()?.let { return it }
    }
    return null
}

private fun parseDateTime(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return parseExpirationDate(value)
}

private val STALKER_DATE_TIME_FORMATTERS: List<DateTimeFormatter> = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "yyyy/MM/dd HH:mm:ss",
    "yyyy/MM/dd HH:mm"
).map { pattern ->
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(pattern)
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART)
}

private val STALKER_DATE_FORMATTERS: List<DateTimeFormatter> = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE,
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("yyyy/MM/dd")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART)
)
