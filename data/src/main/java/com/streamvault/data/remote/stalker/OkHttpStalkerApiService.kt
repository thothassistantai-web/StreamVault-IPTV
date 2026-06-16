package com.streamvault.data.remote.stalker

import android.util.Log
import com.google.gson.JsonObject as GsonJsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import com.streamvault.domain.util.StreamEntryUrlPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Singleton
class OkHttpStalkerApiService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : StalkerApiService {
    private val cookieJar = InMemoryStalkerCookieJar()
    private val stalkerHttpClients = ConcurrentHashMap<String, OkHttpClient>()

    override suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>> {
        var lastError: Throwable? = null
        var lockedLoadUrl: String? = null
        val authModes = candidateAuthModes(profile)
        for (effectiveAuthMode in authModes) {
            if (lockedLoadUrl == null) {
                lockedLoadUrl = probeReachableLoadUrl(profile, effectiveAuthMode)
            }
            for (attempt in candidateAuthAttempts(profile, effectiveAuthMode)) {
                val recipeIndex = attempt.recipeIndex
                val recipe = attempt.recipe
                val loadUrl = attempt.loadUrl
                if (lockedLoadUrl != null && lockedLoadUrl != loadUrl) {
                    continue
                }
                    cookieJar.clear()
                    val referer = StalkerUrlFactory.portalReferer(loadUrl)
                    val evidence = mutableListOf<String>()
                    val recipeEvidence = mutableListOf("recipe:${recipe.recipe.name}", "preset:${recipe.magPreset.name}")
                    val attemptProfile = profile.withRecipe(recipe, effectiveAuthMode)
                    val handshakePayload = runCatching {
                        requestJson(
                            url = loadUrl,
                            profile = attemptProfile,
                            referer = referer,
                            query = mapOf(
                                "type" to "stb",
                                "action" to "handshake",
                                "token" to "",
                                "JsHttpRequest" to "1-xml"
                            )
                        )
                    }.getOrElse { error ->
                        lastError = error
                        continue
                    }
                    evidence += "handshake"
                    val token = handshakePayload.findString("token")
                        ?.takeIf { it.isNotBlank() }
                        ?: run {
                            lastError = IOException("Portal handshake did not return a token.")
                            continue
                        }
                    lockedLoadUrl = loadUrl

                    if (recipe.authMode.requiresCredentials()) {
                        if (attemptProfile.username.isBlank()) {
                            lastError = IOException("Portal requires account credentials for this connection.")
                            continue
                        }
                        val authPayload = runCatching {
                            requestCredentialAuth(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                allowAlternateEndpointRetry = false
                            )
                        }.getOrElse { error ->
                            lastError = error
                            continue
                        }
                        evidence += "do_auth"
                        authPayload.ensureNoPortalError()
                    }

                    var session = StalkerSession(
                        loadUrl = loadUrl,
                        portalReferer = referer,
                        token = token
                    )
                    if (recipe.preferLocalizationBeforeProfile) {
                        runCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "stb",
                                    "action" to "get_localization",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNull()?.let {
                            evidence += "get_localization"
                        }
                    }
                    val profilePayload = runCatching {
                        requestJson(
                            url = loadUrl,
                            profile = attemptProfile,
                            referer = referer,
                            token = token,
                            query = buildProfileQuery(attemptProfile)
                        )
                    }.getOrElse { error ->
                        lastError = error
                        continue
                    }
                    profilePayload.ensureNoPortalError()
                    evidence += "get_profile"

                    var providerProfile = profilePayload.toProviderProfile()
                    var bootstrapStrategy = when (recipe.recipe) {
                        StalkerBootstrapRecipe.GENERIC_SAFE -> StalkerBootstrapStrategy.AUTO
                        StalkerBootstrapRecipe.LEGACY_MAG -> StalkerBootstrapStrategy.MAC_ONLY
                        StalkerBootstrapRecipe.STRICT_MAG,
                        StalkerBootstrapRecipe.PORTAL_PREFERRED,
                        StalkerBootstrapRecipe.LOCALIZATION_STRICT,
                        StalkerBootstrapRecipe.AUTH_ONLY,
                        StalkerBootstrapRecipe.AUTH_STRICT_MAG -> StalkerBootstrapStrategy.MAC_WITH_ACCOUNT_INFO
                        StalkerBootstrapRecipe.MODULE_GATED -> StalkerBootstrapStrategy.MAC_WITH_MODULES
                    }
                    if (recipe.requestAccountInfo || providerProfile.shouldRequestAccountInfo()) {
                        runCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "account_info",
                                    "action" to "get_main_info",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNull()?.let { accountInfoPayload ->
                            providerProfile = providerProfile.merge(accountInfoPayload.toProviderProfile())
                            bootstrapStrategy = StalkerBootstrapStrategy.MAC_WITH_ACCOUNT_INFO
                            evidence += "get_account_info"
                        }
                    }
                    if (recipe.requestLocalization && "get_localization" !in evidence) {
                        runCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "stb",
                                    "action" to "get_localization",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNull()?.let {
                            evidence += "get_localization"
                        }
                    }
                    if (recipe.requestModules || providerProfile.shouldRequestModules()) {
                        runCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "stb",
                                    "action" to "get_modules",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNull()?.let { modulesPayload ->
                            val modules = modulesPayload.toModuleNames()
                            if (modules.isNotEmpty()) {
                                bootstrapStrategy = StalkerBootstrapStrategy.MAC_WITH_MODULES
                                evidence += "get_modules"
                            }
                            providerProfile = providerProfile.copy(moduleNames = modules)
                        }
                    }
                    if ((recipe.strictIdentityRequired || recipe.playbackBackendHint == StalkerPlaybackBackendHint.TEMP_LINK_STRICT) &&
                        "get_events" !in evidence
                    ) {
                        runCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "stb",
                                    "action" to "get_events",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNull()?.let {
                            evidence += "get_events"
                        }
                    }

                    val fingerprint = detectPortalFingerprint(
                        profile = providerProfile,
                        effectiveAuthMode = effectiveAuthMode,
                        selectedPreset = recipe.magPreset,
                        selectedRecipe = recipe.recipe
                    )
                    val portalProfile = profileForFingerprint(fingerprint)
                    val ambiguousState = providerProfile.isAmbiguousAccountState()
                    val credentialRequired = portalProfile == StalkerPortalProfile.AUTH_REQUIRED ||
                        portalProfile == StalkerPortalProfile.AUTH_PLUS_MAG
                    val macRequired = portalProfile != StalkerPortalProfile.AUTH_REQUIRED
                    if (effectiveAuthMode == StalkerAuthMode.MAC_ONLY &&
                        attemptProfile.username.isNotBlank() &&
                        ambiguousState
                    ) {
                        lastError = IOException("Portal partially accepted MAC identity; retrying credential-backed auth.")
                        continue
                    }
                    if (effectiveAuthMode == StalkerAuthMode.MAC_ONLY && credentialRequired) {
                        lastError = IOException("Portal requires account credentials for this connection.")
                        continue
                    }
                    val fallbackRecipeUsed = recipeIndex > 0
                    val rediscoveryAttempted = fallbackRecipeUsed || profile.bootstrapRecipe != StalkerBootstrapRecipe.GENERIC_SAFE
                    if (fallbackRecipeUsed) {
                        recipeEvidence += "fallback_recipe"
                    }
                    if (rediscoveryAttempted) {
                        recipeEvidence += "rediscovery_attempted"
                    }
                    val fingerprintEvidence = StalkerFingerprintEvidence(
                        endpointPreference = endpointPreferenceFor(loadUrl),
                        cookieMode = resolveCookieMode(
                            base = attemptProfile.cookieMode,
                            serverCookieHeader = cookieJar.cookieHeaderFor(loadUrl),
                            recipe = recipe
                        ),
                        playbackBackendHint = attemptProfile.playbackBackendHint,
                        localizationRequired = "get_localization" in evidence,
                        modulesRequired = "get_modules" in evidence,
                        alternateEndpointAccepted = loadUrl != StalkerUrlFactory.loadUrlCandidates(profile.portalUrl).firstOrNull(),
                        genericPresetRejected = fallbackRecipeUsed && recipe.magPreset != StalkerMagPreset.GENERIC_SAFE,
                        strictPresetAccepted = recipe.magPreset != StalkerMagPreset.GENERIC_SAFE,
                        archiveViaCreateLink = recipe.playbackBackendHint != StalkerPlaybackBackendHint.DIRECT ||
                            providerProfile.portalCapabilities.archiveAvailable ||
                            providerProfile.portalCapabilities.allowLocalTimeshift ||
                            providerProfile.portalCapabilities.allowLocalPvr ||
                            providerProfile.portalCapabilities.allowRemotePvr,
                        archiveViaDirectUrl = recipe.playbackBackendHint == StalkerPlaybackBackendHint.DIRECT,
                        archiveRequiresBootstrapPrep = "get_localization" in evidence || "get_modules" in evidence,
                        archiveRequiresStrictCookies = resolveCookieMode(
                            base = attemptProfile.cookieMode,
                            serverCookieHeader = cookieJar.cookieHeaderFor(loadUrl),
                            recipe = recipe
                        ) in setOf(StalkerCookieMode.PLAYBACK, StalkerCookieMode.BOTH),
                        archiveEndpointPreference = endpointPreferenceFor(loadUrl)
                    )
                    providerProfile = providerProfile.copy(
                        bootstrapStrategy = bootstrapStrategy,
                        effectiveAuthMode = effectiveAuthMode,
                        portalProfile = portalProfile,
                        portalFingerprint = fingerprint,
                        magPreset = recipe.magPreset,
                        bootstrapRecipe = recipe.recipe,
                        fingerprintEvidence = fingerprintEvidence,
                        credentialRequired = credentialRequired,
                        macRequired = macRequired,
                        bootstrapEvidence = evidence.toList(),
                        recipeEvidence = recipeEvidence.toList(),
                        strictFingerprintRequired = recipe.strictIdentityRequired,
                        fallbackRecipeUsed = fallbackRecipeUsed,
                        rediscoveryAttempted = rediscoveryAttempted,
                        portalCapabilities = providerProfile.portalCapabilities.copy(
                            bootstrapStrategy = bootstrapStrategy,
                            moduleRestricted = providerProfile.moduleNames.isNotEmpty(),
                            ambiguousAccountState = ambiguousState
                        ),
                        ambiguousState = ambiguousState
                    )
                    session = session.copy(
                        serverCookieHeader = cookieJar.cookieHeaderFor(loadUrl),
                        effectiveAuthMode = effectiveAuthMode,
                        portalProfile = portalProfile,
                        portalFingerprint = fingerprint,
                        magPreset = recipe.magPreset,
                        bootstrapRecipe = recipe.recipe,
                        fingerprintEvidence = fingerprintEvidence,
                        bootstrapEvidence = evidence.toList(),
                        recipeEvidence = recipeEvidence.toList(),
                        rediscoveryAttempted = rediscoveryAttempted
                    )
                    Log.i(
                        TAG,
                        "Stalker auth success host=${runCatching { URI(loadUrl).host }.getOrNull().orEmpty()} " +
                            "auth=${effectiveAuthMode.name} recipe=${recipe.recipe.name} preset=${recipe.magPreset.name} " +
                            "profile=${attemptProfile.deviceProfile} endpoint=${endpointPreferenceFor(loadUrl).name} " +
                            "cookie=${fingerprintEvidence.cookieMode.name} backend=${fingerprintEvidence.playbackBackendHint.name} " +
                            "bootstrap=${evidence.joinToString(",")}"
                    )
                    return Result.success(session to providerProfile)
            }
        }

        Log.w(
            TAG,
            "Stalker auth failed host=${runCatching { URI(profile.portalUrl).host }.getOrNull().orEmpty()} " +
                "hintRecipe=${profile.bootstrapRecipe.name} hintPreset=${profile.magPreset.name} " +
                "hintFingerprint=${profile.portalFingerprint.name} error=${lastError?.message.orEmpty()}"
        )
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
        if (categoryId.isNullOrBlank()) {
            fetchAllLiveChannels(
                session = session,
                profile = profile
            )?.let { return@runApiCall it }
        }

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

    override suspend fun streamLiveStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Result<Int> = runApiCall("Failed to load live channels") {
        requestStreamingItems(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "itv",
                "action" to "get_all_channels",
                "JsHttpRequest" to "1-xml"
            ),
            onItem = onItem
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

    override suspend fun getVodStreamsPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?,
        page: Int
    ): Result<StalkerPagedItems> = runApiCall("Failed to load movies") {
        fetchPagedItemPage(
            session = session,
            profile = profile,
            page = page,
            baseQuery = buildMap {
                put("type", "vod")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            }
        ).let { paged -> paged.copy(items = paged.items.filterNot { it.isSeries }) }
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

    override suspend fun getSeriesPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?,
        page: Int
    ): Result<StalkerPagedItems> = runApiCall("Failed to load series") {
        fetchPagedItemPage(
            session = session,
            profile = profile,
            page = page,
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
        val seedEntries = seriesPayload.extractItemEntries()
        val seriesItems = seriesPayload.toItemRecords()
        val series = seriesItems.firstOrNull { item -> !item.looksLikeSeasonShell() }
            ?: StalkerItemRecord(
                id = seriesId,
                name = ""
            )
        val seasonRows = seedEntries
            .mapNotNull { entry ->
                entry.findString("season_id")
                    ?.takeIf { it.isNotBlank() && it != "0" }
                    ?.let { seasonId ->
                        seasonId to entry
                    }
            }
            .distinctBy { it.first }
        val shellSeasonRows = seedEntries.mapIndexedNotNull { index, entry ->
            entry.toSeasonShellRecord(index + 1)
                ?.let { season -> season.seasonNumber.toString() to entry }
        }

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
                entry.toSeasonRecord(
                    episodeEntries = episodesPayload.extractItemEntries(),
                    fallbackSeasonNumber = seasonId.toIntOrNull()
                )
            }
        } else if (shellSeasonRows.isNotEmpty()) {
            shellSeasonRows.map { (seasonId, entry) ->
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
                entry.toSeasonRecord(
                    episodeEntries = episodesPayload.extractItemEntries(),
                    fallbackSeasonNumber = seasonId.toIntOrNull()
                )
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
        // Legacy buffered API kept for compatibility; route through the streaming path
        // and cap the in-memory list so a misbehaving portal cannot OOM the caller.
        val buffer = ArrayList<StalkerProgramRecord>()
        requestStreamingPrograms(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            channelIdOverride = channelId,
            query = perChannelEpgQuery(channelId, periodHours = 6)
        ) { program ->
            if (buffer.size < MAX_INLINE_EPG_RECORDS) {
                buffer.add(program)
            }
        }
        buffer
    }

    override suspend fun getBulkEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        periodHours: Int
    ): Result<List<StalkerProgramRecord>> = runApiCall("Failed to load bulk EPG") {
        // Legacy buffered API kept for compatibility; route through the streaming path
        // and cap the in-memory list so a misbehaving portal cannot OOM the caller.
        val buffer = ArrayList<StalkerProgramRecord>()
        requestStreamingPrograms(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            channelIdOverride = null,
            query = bulkEpgQuery(periodHours)
        ) { program ->
            if (buffer.size < MAX_INLINE_EPG_RECORDS) {
                buffer.add(program)
            }
        }
        buffer
    }

    override suspend fun streamBulkEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        periodHours: Int,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Result<Int> = runApiCall("Failed to load bulk EPG") {
        requestStreamingPrograms(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            channelIdOverride = null,
            query = bulkEpgQuery(periodHours),
            onProgram = onProgram
        )
    }

    override suspend fun streamEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String,
        periodHours: Int,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Result<Int> = runApiCall("Failed to load EPG") {
        requestStreamingPrograms(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            channelIdOverride = channelId,
            query = perChannelEpgQuery(channelId, periodHours),
            onProgram = onProgram
        )
    }

    private fun bulkEpgQuery(periodHours: Int): Map<String, String> = mapOf(
        "type" to "itv",
        "action" to "get_epg_info",
        "JsHttpRequest" to "1-xml",
        "period" to periodHours.coerceAtLeast(1).toString()
    )

    private fun perChannelEpgQuery(channelId: String, periodHours: Int): Map<String, String> = mapOf(
        "type" to "itv",
        "action" to "get_epg_info",
        "JsHttpRequest" to "1-xml",
        "ch_id" to channelId,
        "period" to periodHours.coerceAtLeast(1).toString()
    )

    override suspend fun createLink(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        kind: StalkerStreamKind,
        cmd: String,
        seriesNumber: Int?,
        archiveStartSeconds: Long?,
        archiveEndSeconds: Long?
    ): Result<String> = runApiCall("Failed to resolve playback link") {
        val type = when (kind) {
            StalkerStreamKind.LIVE,
            StalkerStreamKind.ARCHIVE -> "itv"
            StalkerStreamKind.MOVIE, StalkerStreamKind.EPISODE -> "vod"
        }
        val seriesSelector = if (kind == StalkerStreamKind.EPISODE) {
            seriesNumber?.takeIf { it > 0 }?.toString() ?: "0"
        } else {
            "0"
        }
        val forcedStorage = when (kind) {
            StalkerStreamKind.LIVE,
            StalkerStreamKind.ARCHIVE -> "undefined"
            StalkerStreamKind.MOVIE,
            StalkerStreamKind.EPISODE -> "0"
        }
        val playbackLoadUrl = createLinkLoadUrl(session)
        val payload = requestJson(
            url = playbackLoadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to type,
                "action" to "create_link",
                "JsHttpRequest" to "1-xml",
                "cmd" to cmd,
                "series" to seriesSelector,
                "forced_storage" to forcedStorage,
                "disable_ad" to "0",
                "download" to "0"
            )
        )
        payload.findString("cmd")
            ?.substringAfter(' ', missingDelimiterValue = payload.findString("cmd").orEmpty())
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { resolved ->
                val playbackUrl = if (kind == StalkerStreamKind.ARCHIVE) {
                    appendArchiveWindow(
                        url = resolved,
                        startSeconds = archiveStartSeconds,
                        endSeconds = archiveEndSeconds
                    ) ?: resolved
                } else {
                    resolved
                }
                if (!StreamEntryUrlPolicy.isAllowed(playbackUrl)) {
                    throw IOException("Portal returned an unsupported playback URL scheme for create_link.")
                }
                playbackUrl
            }
            ?: throw IOException("Portal did not return a playable URL.")
    }

    private fun createLinkLoadUrl(session: StalkerSession): String {
        return session.loadUrl
    }

    override fun currentCookieHeader(session: StalkerSession): String =
        cookieJar.cookieHeaderFor(session.loadUrl).ifBlank { session.serverCookieHeader }

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

    private suspend fun fetchPagedItemPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        baseQuery: Map<String, String>,
        page: Int
    ): StalkerPagedItems {
        val safePage = page.coerceAtLeast(1).coerceAtMost(MAX_PAGE_COUNT)
        val payload = requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = baseQuery + ("p" to safePage.toString())
        )
        val items = payload.toItemRecords()
        return StalkerPagedItems(
            items = items,
            page = safePage,
            totalPages = payload.totalPages(),
            pageSize = payload.pageSize(items.size)
        )
    }

    private suspend fun fetchAllLiveChannels(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): List<StalkerItemRecord>? {
        return runCatching {
            requestJson(
                url = session.loadUrl,
                profile = profile,
                referer = session.portalReferer,
                token = session.token,
                query = mapOf(
                    "type" to "itv",
                    "action" to "get_all_channels",
                    "JsHttpRequest" to "1-xml"
                )
            ).toItemRecords()
        }.onFailure { error ->
            Log.w(TAG, "Stalker get_all_channels failed; falling back to paged live catalog", error)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun requestJson(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        query: Map<String, String>,
        token: String? = null,
        allowAlternateEndpointRetry: Boolean = false,
        method: String = "GET",
        body: String? = null
    ): JsonElement = withContext(Dispatchers.IO) {
        val effectiveQuery = prepareQuery(profile, query)
        val action = effectiveQuery["action"]
        val fullUrl = buildUrl(url, effectiveQuery)
        val requestBuilder = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", profile.userAgent)
            .header("X-User-Agent", profile.xUserAgent)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Cookie", buildCookieHeader(fullUrl, profile))
            .apply {
                token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }
            .applyStalkerHeaderOverrides(
                headerOverrides = profile.headerOverrides,
                preserveUserAgent = profile.advancedOptions.apiUserAgent.isNotBlank()
            )
        val requestBody = body?.toRequestBody(FORM_URL_ENCODED_MEDIA_TYPE)
        val request = requestBuilder
            .method(method, requestBody)
            .build()

        runCatching {
            executeJsonRequest(request, action, profile)
        }.recoverCatching { error ->
            if (!allowAlternateEndpointRetry) throw error
            val alternateUrl = siblingLoadUrl(url)
                ?.takeIf { it != url }
                ?: throw error
            Log.w(
                TAG,
                "Retrying Stalker ${action.orEmpty()} via alternate endpoint $alternateUrl after ${error.message}"
            )
            val alternateRequest = request.newBuilder()
                .url(buildUrl(alternateUrl, effectiveQuery))
                .header("Referer", StalkerUrlFactory.portalReferer(alternateUrl))
                .header("Cookie", buildCookieHeader(buildUrl(alternateUrl, effectiveQuery), profile))
                .applyStalkerHeaderOverrides(
                    headerOverrides = profile.headerOverrides,
                    preserveUserAgent = profile.advancedOptions.apiUserAgent.isNotBlank()
                )
                .method(method, requestBody)
                .build()
            executeJsonRequest(alternateRequest, action, profile)
        }.getOrElse { throw it }
    }

    private suspend fun probeReachableLoadUrl(
        profile: StalkerDeviceProfile,
        effectiveAuthMode: StalkerAuthMode
    ): String? = withContext(Dispatchers.IO) {
        val recipe = candidateRecipes(profile, effectiveAuthMode).firstOrNull() ?: return@withContext null
        val attemptProfile = profile.withRecipe(recipe, effectiveAuthMode)
        val handshakeQuery = mapOf(
            "type" to "stb",
            "action" to "handshake",
            "token" to "",
            "JsHttpRequest" to "1-xml"
        )
        orderedLoadUrlCandidates(profile, recipe).firstOrNull { loadUrl ->
            val fullUrl = buildUrl(loadUrl, prepareQuery(attemptProfile, handshakeQuery))
            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", attemptProfile.userAgent)
                .header("X-User-Agent", attemptProfile.xUserAgent)
                .header("Referer", StalkerUrlFactory.portalReferer(loadUrl))
                .header("Accept", "*/*")
                .header("Cookie", buildCookieHeader(fullUrl, attemptProfile))
                .applyStalkerHeaderOverrides(
                    headerOverrides = attemptProfile.headerOverrides,
                    preserveUserAgent = attemptProfile.advancedOptions.apiUserAgent.isNotBlank()
                )
                .get()
                .build()
            runCatching {
                stalkerHttpClientFor(attemptProfile).newCall(request).execute().use { response ->
                    response.code == 200
                }
            }.getOrDefault(false)
        }
    }

    private fun executeJsonRequest(request: Request, action: String?, profile: StalkerDeviceProfile): JsonElement {
        return stalkerHttpClientFor(profile).newCall(request).execute().use { response ->
            captureResponseCookies(response)
            if (!response.isSuccessful) {
                response.body?.close()
                throw IOException("Portal request failed with HTTP ${response.code}.")
            }
            val responseBody = response.body
                ?: throw IOException("Portal returned an empty response${actionSuffix(action)}.")
            val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val raw = readBodyBounded(
                stream = responseBody.byteStream(),
                charsetName = charset.name(),
                maxBytes = maxBodyBytesForAction(action),
                action = action
            )
            if (raw.isBlank()) {
                throw IOException("Portal returned an empty response${actionSuffix(action)}.")
            }
            val parsed = parsePortalJson(raw, action)
            parsed.ensureNoPortalError()
            parsed
        }
    }

    /**
     * Reads [stream] into a String, throwing [IOException] if the body exceeds [maxBytes].
     * This prevents unbounded heap allocation when portals return unexpectedly large responses.
     */
    private fun readBodyBounded(
        stream: InputStream,
        charsetName: String,
        maxBytes: Long,
        action: String?
    ): String {
        val out = ByteArrayOutputStream(16_384)
        var totalRead = 0L
        val chunk = ByteArray(8_192)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            totalRead += n
            if (totalRead > maxBytes) {
                val limitKb = maxBytes / 1024
                throw IOException(
                    "Portal response for '${action ?: "request"}' exceeded the ${limitKb}KB limit; " +
                        "portal may be misbehaving."
                )
            }
            out.write(chunk, 0, n)
        }
        return try {
            out.toString(charsetName)
        } catch (_: java.io.UnsupportedEncodingException) {
            out.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * Returns an appropriate response-body size ceiling for the given Stalker [action].
     * Smaller endpoints (auth, create_link) get tight limits; large catalog endpoints get
     * a generous but still bounded ceiling to prevent OOM from misbehaving portals.
     */
    private fun maxBodyBytesForAction(action: String?): Long = when (action?.trim()) {
        "handshake", "get_profile", "get_account_info" -> 512L * 1024          // 512 KB
        "create_link"                                   -> 64L * 1024           // 64 KB
        "get_genres", "get_vod_categories", "get_series_categories",
        "get_categories", "get_live_categories"         -> 2L * 1024 * 1024    // 2 MB
        "get_ordered_list", "get_vod_list", "get_series",
        "get_seasons", "get_series_info", "get_vod_info" -> 8L * 1024 * 1024  // 8 MB
        else                                             -> 4L * 1024 * 1024   // 4 MB default
    }

    private suspend fun requestStreamingItems(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        query: Map<String, String>,
        token: String,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val effectiveQuery = prepareQuery(profile, query)
        val action = effectiveQuery["action"]
        val fullUrl = buildUrl(url, effectiveQuery)
        val request = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", profile.userAgent)
            .header("X-User-Agent", profile.xUserAgent)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Cookie", buildCookieHeader(fullUrl, profile))
            .header("Authorization", "Bearer $token")
            .applyStalkerHeaderOverrides(
                headerOverrides = profile.headerOverrides,
                preserveUserAgent = profile.advancedOptions.apiUserAgent.isNotBlank()
            )
            .get()
            .build()

        executeStreamingRequest(request, action, profile, onItem)
    }

    private suspend fun executeStreamingRequest(
        request: Request,
        action: String?,
        profile: StalkerDeviceProfile,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int {
        return stalkerHttpClientFor(profile).newCall(request).execute().use { response ->
            captureResponseCookies(response)
            if (!response.isSuccessful) {
                throw IOException("Portal request failed with HTTP ${response.code}.")
            }
            val body = response.body ?: throw IOException("Portal returned an empty response${actionSuffix(action)}.")
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val reader = JsonReader(InputStreamReader(body.byteStream(), charset))
            reader.isLenient = true
            try {
                streamStalkerItems(reader, onItem)
            } catch (error: IllegalStateException) {
                throw IOException("Portal returned unreadable JSON${actionSuffix(action)}.", error)
            }
        }
    }

    private suspend fun streamStalkerItems(
        reader: JsonReader,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int {
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> streamItemArray(reader, onItem)
            JsonToken.BEGIN_OBJECT -> streamItemObject(reader, onItem)
            JsonToken.NULL -> {
                reader.nextNull()
                0
            }
            else -> {
                reader.skipValue()
                0
            }
        }
    }

    private suspend fun streamItemObject(
        reader: JsonReader,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int {
        var count = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "error" -> {
                    val error = reader.nextStringOrSkip()
                    // Use the same placeholder filter as ensureNoPortalError so that
                    // "null", "0", "false", and "ok" from loose portals are not treated
                    // as real errors in the streamed item path either.
                    if (!error.isNullOrBlank() && !isPlaceholderErrorValue(error)) {
                        throw IOException(error)
                    }
                }
                "js", "data", "items" -> count += streamStalkerItems(reader, onItem)
                else -> {
                    // Object-keyed catalogs (e.g. `{"data":{"100":{...},"200":{...}}}`) use
                    // numeric string keys for each item. Attempt to parse any object value as
                    // an item record before falling back to skip, so these catalogs are not
                    // silently dropped on the streaming path.
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        val element = JsonParser.parseReader(reader)
                        val item = element.asJsonObjectOrNull()?.toItemRecord()
                        if (item != null) {
                            onItem(item)
                            count++
                        }
                    } else {
                        reader.skipValue()
                    }
                }
            }
        }
        reader.endObject()
        return count
    }

    private suspend fun streamItemArray(
        reader: JsonReader,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                val element = JsonParser.parseReader(reader)
                val item = element.asJsonObjectOrNull()?.toItemRecord()
                if (item != null) {
                    onItem(item)
                    count++
                }
            } else {
                count += streamStalkerItems(reader, onItem)
            }
        }
        reader.endArray()
        return count
    }

    /**
     * Issues a streamed Stalker request that emits [StalkerProgramRecord]s instead of building
     * a Gson tree of the entire response. This is the heap-safe path used for `get_epg_info`,
     * which on some portals returns >30 MB of JSON regardless of the requested period.
     */
    private suspend fun requestStreamingPrograms(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        query: Map<String, String>,
        token: String,
        channelIdOverride: String?,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val effectiveQuery = prepareQuery(profile, query)
        val action = effectiveQuery["action"]
        val fullUrl = buildUrl(url, effectiveQuery)
        val request = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", profile.userAgent)
            .header("X-User-Agent", profile.xUserAgent)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Cookie", buildCookieHeader(fullUrl, profile))
            .header("Authorization", "Bearer $token")
            .applyStalkerHeaderOverrides(
                headerOverrides = profile.headerOverrides,
                preserveUserAgent = profile.advancedOptions.apiUserAgent.isNotBlank()
            )
            .get()
            .build()

        executeStreamingPrograms(request, action, profile, channelIdOverride, onProgram)
    }

    private suspend fun executeStreamingPrograms(
        request: Request,
        action: String?,
        profile: StalkerDeviceProfile,
        channelIdOverride: String?,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int {
        return stalkerHttpClientFor(profile).newCall(request).execute().use { response ->
            captureResponseCookies(response)
            if (!response.isSuccessful) {
                throw IOException("Portal request failed with HTTP ${response.code}.")
            }
            val body = response.body ?: throw IOException("Portal returned an empty response${actionSuffix(action)}.")
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val limited = ByteSizeLimitInputStream(
                delegate = body.byteStream(),
                maxBytes = MAX_EPG_BYTES,
                onOverflow = {
                    "Portal EPG payload exceeded ${MAX_EPG_BYTES} bytes${actionSuffix(action)}."
                }
            )
            val reader = JsonReader(InputStreamReader(limited, charset))
            reader.isLenient = true
            try {
                streamStalkerPrograms(reader, channelIdOverride, onProgram)
            } catch (error: IllegalStateException) {
                throw IOException("Portal returned unreadable JSON${actionSuffix(action)}.", error)
            }
        }
    }

    private suspend fun streamStalkerPrograms(
        reader: JsonReader,
        channelIdOverride: String?,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int {
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> streamProgramArray(reader, channelIdOverride, onProgram)
            JsonToken.BEGIN_OBJECT -> streamProgramObject(reader, channelIdOverride, onProgram)
            JsonToken.NULL -> {
                reader.nextNull()
                0
            }
            else -> {
                reader.skipValue()
                0
            }
        }
    }

    private suspend fun streamProgramObject(
        reader: JsonReader,
        channelIdOverride: String?,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int {
        var count = 0
        reader.beginObject()
        var sawEnvelope = false
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                "error" -> {
                    val error = reader.nextStringOrSkip()
                    if (!error.isNullOrBlank() && !error.equals("null", ignoreCase = true)) {
                        throw IOException(error)
                    }
                }
                "js", "data", "items" -> {
                    sawEnvelope = true
                    count += streamStalkerPrograms(reader, channelIdOverride, onProgram)
                }
                else -> {
                    // Some portals return the bulk EPG as an object whose keys are channel IDs
                    // mapped to arrays of program objects. When we have not already descended
                    // into an envelope key and there is no caller-supplied override, treat the
                    // key as the channel ID and walk the array.
                    if (!sawEnvelope && channelIdOverride == null && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        count += streamProgramArray(reader, name, onProgram)
                    } else {
                        reader.skipValue()
                    }
                }
            }
        }
        reader.endObject()
        return count
    }

    private suspend fun streamProgramArray(
        reader: JsonReader,
        channelIdOverride: String?,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                val element = JsonParser.parseReader(reader)
                val program = element.asJsonObjectOrNull()?.toProgramRecord(channelIdOverride)
                if (program != null) {
                    onProgram(program)
                    count++
                }
            } else {
                count += streamStalkerPrograms(reader, channelIdOverride, onProgram)
            }
        }
        reader.endArray()
        return count
    }

    private fun GsonJsonObject.toProgramRecord(channelIdOverride: String?): StalkerProgramRecord? {
        val resolvedChannelId = channelIdOverride
            ?: findString("ch_id")
            ?: findString("channel_id")
            ?: findString("id_channel")
            ?: findString("xmltv_id")
            ?: findString("epg_id")
            ?: return null
        val startMillis = findString("start_timestamp")?.toLongOrNull()?.times(1000L)
            ?: findString("time")?.let(::parseDateTime)
            ?: return null
        val endMillis = findString("stop_timestamp")?.toLongOrNull()?.times(1000L)
            ?: findString("time_to")?.let(::parseDateTime)
            ?: (startMillis + (findString("duration")?.toLongOrNull()?.times(60_000L) ?: DEFAULT_PROGRAM_DURATION_MILLIS))
        val title = findString("name") ?: findString("title") ?: return null
        return StalkerProgramRecord(
            id = findString("id") ?: "$resolvedChannelId:$startMillis",
            channelId = resolvedChannelId,
            title = title,
            description = findString("descr") ?: findString("description") ?: "",
            startTimeMillis = startMillis,
            endTimeMillis = endMillis,
            hasArchive = findBoolean("has_archive") == true || findString("has_archive") == "1",
            isNowPlaying = findBoolean("now_playing") == true || findString("now_playing") == "1"
        )
    }

    private fun siblingLoadUrl(url: String): String? {
        val normalized = StalkerUrlFactory.normalizePortalUrl(url)
        val lower = normalized.lowercase(Locale.ROOT)
        return when {
            lower.endsWith("/server/load.php") -> normalized.removeSuffix("/server/load.php") + "/portal.php"
            lower.endsWith("/portal.php") -> normalized.removeSuffix("/portal.php") + "/server/load.php"
            else -> null
        }
    }

    private fun parsePortalJson(body: String, action: String?): JsonElement {
        val normalized = sanitizePortalResponseBody(body)
        runCatching { json.parseToJsonElement(normalized) }
            .getOrNull()
            ?.let { return it }

        if (looksLikeHtml(normalized)) {
            val lower = normalized.lowercase(Locale.ROOT)
            if (lower.contains("access denied") || lower.contains("forbidden")) {
                throw IOException("Portal denied the request${actionSuffix(action)}.")
            }
        }

        extractEmbeddedJson(normalized)?.let { candidate ->
            runCatching { json.parseToJsonElement(candidate) }
                .getOrNull()
                ?.let { return it }
        }

        throw runCatching { json.parseToJsonElement(normalized) }
            .fold(
                onSuccess = { IOException("Portal returned unreadable JSON${actionSuffix(action)}.") },
                onFailure = { error -> IOException("Portal returned unreadable JSON${actionSuffix(action)}.", error) }
            )
    }

    private fun JsonElement.ensureNoPortalError() {
        val raw = rootObjectOrNull()?.findString("error")
            ?: findString("error")
            ?: return
        // Ignore well-known placeholder values that some portals emit even on success.
        if (raw.isBlank() || isPlaceholderErrorValue(raw)) return
        throw IOException(raw)
    }

    /**
     * Returns `true` for portal error strings that are not real errors.
     * Common examples: `"null"`, `"0"`, `"false"`, `"ok"`, empty strings.
     */
    private fun isPlaceholderErrorValue(value: String): Boolean {
        return when (value.lowercase(Locale.ROOT).trim()) {
            "null", "0", "false", "ok", "" -> true
            else -> false
        }
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

    private fun prepareQuery(
        profile: StalkerDeviceProfile,
        query: Map<String, String>
    ): LinkedHashMap<String, String> {
        val action = query["action"].orEmpty()
        val matchingRule = profile.advancedOptions.requestRules
            .firstOrNull { it.action.trim() == action }
        if (matchingRule?.blockRequest == true) {
            throw IOException("Stalker request '$action' was blocked by advanced settings.")
        }
        val prepared = LinkedHashMap<String, String>()
        query.forEach { (key, value) -> prepared[key] = value }
        matchingRule?.paramOverrides.orEmpty().forEach { override ->
            val name = override.name.trim()
            if (name.isBlank()) return@forEach
            val value = override.value
            if (value.isBlank()) {
                prepared.remove(name)
            } else {
                prepared[name] = value
            }
        }
        if (action == "get_profile" && prepared.containsKey("JsHttpRequest")) {
            val jsValue = prepared.remove("JsHttpRequest")
            if (jsValue != null) {
                prepared["JsHttpRequest"] = jsValue
            }
        }
        return prepared
    }

    private fun stalkerHttpClientFor(profile: StalkerDeviceProfile): OkHttpClient {
        val proxy = profile.advancedOptions.proxy
        val key = proxy?.let { "${it.host}:${it.port}" } ?: "<direct>"
        return stalkerHttpClients.computeIfAbsent(key) {
            okHttpClient.newBuilder()
                .cookieJar(cookieJar)
                .apply {
                    if (proxy != null) {
                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
                    }
                }
                .build()
        }
    }

    private fun buildProfileQuery(profile: StalkerDeviceProfile): Map<String, String> {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val preset = stalkerMagPresetSpec(profile.magPreset)
        val metrics = buildMetricsJson(profile, preset)
        val hwVersion = profile.advancedOptions.hwVersion.trim().ifBlank { preset.hwVersion }
        return linkedMapOf(
            "type" to "stb",
            "action" to "get_profile",
            "hd" to "1",
            "ver" to preset.versionString,
            "sn" to profile.serialNumber,
            "stb_type" to profile.deviceProfile,
            "client_type" to "STB",
            "image_version" to preset.imageVersion,
            "video_out" to "hdmi",
            "device_id" to profile.deviceId,
            "device_id2" to profile.deviceId2,
            "signature" to profile.signature,
            "auth_second_step" to "1",
            "hw_version" to hwVersion,
            "not_valid_token" to "0",
            "metrics" to metrics,
            "hw_version_2" to hwVersion,
            "timestamp" to timestamp,
            "api_signature" to preset.apiSignature,
            "prehash" to "false",
            "num_banks" to "2",
            "player_version" to preset.imageVersion,
            "stb_lang" to profile.locale.ifBlank { preset.localization.substringBefore('.') },
            "locale" to preset.localization,
            "JsHttpRequest" to "1-xml"
        )
    }

    private fun buildCookieHeader(url: String, profile: StalkerDeviceProfile): String {
        val cookies = linkedMapOf<String, String>()
        profile.macAddress.takeIf { it.isNotBlank() }?.let { cookies["mac"] = encode(it) }
        profile.locale.takeIf { it.isNotBlank() }?.let { cookies["stb_lang"] = encode(it) }
        profile.timezone.takeIf { it.isNotBlank() }?.let { cookies["timezone"] = encode(it) }
        cookieJar.cookieHeaderFor(url).split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "").trim()
                val value = part.substringAfter('=', missingDelimiterValue = "").trim()
                key.takeIf { it.isNotBlank() && value.isNotBlank() }?.let { it to value }
        }.forEach { (key, value) ->
            cookies.putIfAbsent(key, value)
        }
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private suspend fun requestCredentialAuth(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        token: String,
        allowAlternateEndpointRetry: Boolean = false
    ): JsonElement {
        val formBody = listOf(
            "login" to profile.username,
            "password" to profile.password
        ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val query = mapOf(
            "type" to "stb",
            "action" to "do_auth",
            "JsHttpRequest" to "1-xml"
        )
        return runCatching {
            requestJson(
                url = url,
                profile = profile,
                referer = referer,
                token = token,
                allowAlternateEndpointRetry = allowAlternateEndpointRetry,
                query = query,
                method = "POST",
                body = formBody
            )
        }.getOrElse {
            requestJson(
                url = url,
                profile = profile,
                referer = referer,
                token = token,
                allowAlternateEndpointRetry = allowAlternateEndpointRetry,
                query = query + mapOf(
                    "login" to profile.username,
                    "password" to profile.password
                )
            )
        }
    }

    private fun buildMetricsJson(
        profile: StalkerDeviceProfile,
        preset: StalkerMagPresetSpec
    ): String {
        if (preset.metricsJson != "{}") {
            return preset.metricsJson
        }
        return buildString {
            append('{')
            append("\"mac\":\"").append(profile.macAddress).append("\",")
            append("\"sn\":\"").append(profile.serialNumber).append("\",")
            append("\"model\":\"").append(profile.deviceProfile).append("\",")
            append("\"type\":\"STB\",")
            append("\"uid\":\"").append(profile.deviceId2.take(16)).append("\",")
            append("\"random\":\"").append(profile.deviceId2.take(16)).append('"')
            append('}')
        }
    }

    private fun captureResponseCookies(response: Response) {
        val cookies = Cookie.parseAll(response.request.url, response.headers)
        if (cookies.isNotEmpty()) {
            cookieJar.saveFromResponse(response.request.url, cookies)
        }
    }

    private fun JsonElement.totalPages(): Int {
        val payload = payloadObjectOrNull() ?: return 1
        val totalItems = payload["total_items"]?.primitiveContentOrNull()?.toIntOrNull() ?: return 1
        val pageSize = pageSize(payload["data"]?.jsonArrayOrNull()?.size ?: 0).takeIf { it > 0 }
            ?: return 1
        return ((totalItems + pageSize - 1) / pageSize).coerceAtLeast(1).coerceAtMost(MAX_PAGE_COUNT)
    }

    private fun JsonElement.pageSize(fallback: Int): Int {
        val payload = payloadObjectOrNull() ?: return fallback
        return payload["max_page_items"]?.primitiveContentOrNull()?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: payload["data"]?.jsonArrayOrNull()?.size?.takeIf { it > 0 }
            ?: fallback
    }

    private fun JsonElement.toProviderProfile(): StalkerProviderProfile {
        val payload = payloadObjectOrNull()
        return StalkerProviderProfile(
            accountId = payload?.findString("id"),
            accountName = payload?.findString("name")
                ?: payload?.findString("account")
                ?: payload?.findString("login"),
            maxConnections = payload?.findString("max_online")
                ?.toIntOrNull()
                ?: payload?.findString("max_connections")?.toIntOrNull(),
            expirationDate = payload?.findString("expire_billing_date")
                ?.let(::parseExpirationDate)
                ?: payload?.findString("end_date")?.let(::parseExpirationDate),
            statusLabel = payload?.findString("status"),
            authAccess = payload?.findBoolean("auth_access")
        )
    }

    private fun StalkerProviderProfile.merge(other: StalkerProviderProfile): StalkerProviderProfile =
        copy(
            accountId = other.accountId ?: accountId,
            accountName = other.accountName ?: accountName,
            maxConnections = other.maxConnections ?: maxConnections,
            expirationDate = other.expirationDate ?: expirationDate,
            statusLabel = other.statusLabel ?: statusLabel,
            authAccess = other.authAccess ?: authAccess,
            moduleNames = if (other.moduleNames.isNotEmpty()) other.moduleNames else moduleNames,
            bootstrapStrategy = if (other.bootstrapStrategy != StalkerBootstrapStrategy.AUTO) {
                other.bootstrapStrategy
            } else {
                bootstrapStrategy
            },
            effectiveAuthMode = if (other.effectiveAuthMode != StalkerAuthMode.AUTO) {
                other.effectiveAuthMode
            } else {
                effectiveAuthMode
            },
            portalProfile = if (other.portalProfile != StalkerPortalProfile.MAG_BASIC) {
                other.portalProfile
            } else {
                portalProfile
            },
            portalCapabilities = portalCapabilities.copy(
                bootstrapStrategy = other.portalCapabilities.bootstrapStrategy
                    .takeUnless { it == StalkerBootstrapStrategy.AUTO }
                    ?: portalCapabilities.bootstrapStrategy,
                useHttpTemporaryLink = portalCapabilities.useHttpTemporaryLink || other.portalCapabilities.useHttpTemporaryLink,
                nginxSecureLink = portalCapabilities.nginxSecureLink || other.portalCapabilities.nginxSecureLink,
                flussonicTemporaryLink = portalCapabilities.flussonicTemporaryLink || other.portalCapabilities.flussonicTemporaryLink,
                wowzaTemporaryLink = portalCapabilities.wowzaTemporaryLink || other.portalCapabilities.wowzaTemporaryLink,
                useLoadBalancing = portalCapabilities.useLoadBalancing || other.portalCapabilities.useLoadBalancing,
                allowLocalTimeshift = portalCapabilities.allowLocalTimeshift || other.portalCapabilities.allowLocalTimeshift,
                allowLocalPvr = portalCapabilities.allowLocalPvr || other.portalCapabilities.allowLocalPvr,
                allowRemotePvr = portalCapabilities.allowRemotePvr || other.portalCapabilities.allowRemotePvr,
                archiveAvailable = portalCapabilities.archiveAvailable || other.portalCapabilities.archiveAvailable,
                moduleRestricted = portalCapabilities.moduleRestricted || other.portalCapabilities.moduleRestricted,
                ambiguousAccountState = portalCapabilities.ambiguousAccountState || other.portalCapabilities.ambiguousAccountState
            ),
            credentialRequired = credentialRequired || other.credentialRequired,
            macRequired = macRequired && other.macRequired,
            bootstrapEvidence = (bootstrapEvidence + other.bootstrapEvidence).distinct(),
            ambiguousState = ambiguousState || other.ambiguousState
        )

    private fun StalkerProviderProfile.shouldRequestAccountInfo(): Boolean =
        authAccess == false || isAmbiguousAccountState()

    private fun StalkerProviderProfile.shouldRequestModules(): Boolean =
        authAccess == false || isAmbiguousAccountState()

    private fun StalkerProviderProfile.isAmbiguousAccountState(): Boolean {
        val normalizedStatus = statusLabel?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val normalizedAccountId = accountId?.trim().orEmpty()
        val normalizedAccountName = accountName?.trim().orEmpty()
        return normalizedStatus == "0" ||
            authAccess == false ||
            normalizedAccountId.isBlank() ||
            normalizedAccountId == "0" ||
            normalizedAccountName.isBlank() ||
            normalizedAccountName == "0"
    }

    private fun JsonElement.toModuleNames(): List<String> {
        val payload = payloadObjectOrNull()
        val modulesValue = payload?.get("modules")
            ?: rootObjectOrNull()?.get("js")
            ?: return emptyList()
        return when (modulesValue) {
            is JsonArray -> modulesValue.mapNotNull { element ->
                element.primitiveContentOrNull()
                    ?: element.jsonObjectOrNull()?.findString("id")
                    ?: element.jsonObjectOrNull()?.findString("name")
            }
            is JsonObject -> modulesValue.keys.toList()
            is JsonPrimitive -> listOfNotNull(modulesValue.contentOrNull?.trim()?.takeIf { it.isNotBlank() })
            else -> emptyList()
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
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
        val capabilities = StalkerPortalCapabilities(
            useHttpTemporaryLink = findBoolean("use_http_tmp_link") == true || findString("use_http_tmp_link") == "1",
            nginxSecureLink = findBoolean("nginx_secure_link") == true || findString("nginx_secure_link") == "1",
            flussonicTemporaryLink = findBoolean("flussonic_tmp_link") == true || findString("flussonic_tmp_link") == "1",
            wowzaTemporaryLink = findBoolean("wowza_tmp_link") == true || findString("wowza_tmp_link") == "1",
            useLoadBalancing = findBoolean("use_load_balancing") == true || findString("use_load_balancing") == "1",
            allowLocalTimeshift = findBoolean("allow_local_timeshift") == true || findString("allow_local_timeshift") == "1",
            allowLocalPvr = findBoolean("allow_local_pvr") == true || findString("allow_local_pvr") == "1",
            allowRemotePvr = findBoolean("allow_remote_pvr") == true || findString("allow_remote_pvr") == "1",
            archiveAvailable = findBoolean("archive") == true || findString("archive") == "1"
        )
        val alternateCommands = extractAlternateCommands()
        val descriptor = buildStalkerPlaybackDescriptor(
            primaryCmd = findString("cmd"),
            alternateCommands = alternateCommands,
            capabilities = capabilities
        )
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
            playbackDescriptor = descriptor,
            commandVariants = descriptor?.candidates.orEmpty(),
            portalCapabilities = capabilities,
            mcCmd = findString("mc_cmd"),
            useHttpTemporaryLink = capabilities.useHttpTemporaryLink,
            nginxSecureLink = capabilities.nginxSecureLink,
            flussonicTemporaryLink = capabilities.flussonicTemporaryLink,
            wowzaTemporaryLink = capabilities.wowzaTemporaryLink,
            useLoadBalancing = capabilities.useLoadBalancing,
            allowLocalTimeshift = capabilities.allowLocalTimeshift,
            allowLocalPvr = capabilities.allowLocalPvr,
            allowRemotePvr = capabilities.allowRemotePvr,
            archiveAvailable = capabilities.archiveAvailable,
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
            addedAt = parseDateTime(findString("added")) ?: 0L,
            isAdult = findBoolean("censored") == true,
            isSeries = findBoolean("is_series") == true || findString("is_series") == "1"
        )
    }

    private fun GsonJsonObject.toItemRecord(): StalkerItemRecord? {
        val id = findString("id")
            ?: findString("ch_id")
            ?: findString("video_id")
            ?: findString("series_id")
            ?: return null
        val name = findString("name")
            ?: findString("title")
            ?: return null
        val capabilities = StalkerPortalCapabilities(
            useHttpTemporaryLink = findBoolean("use_http_tmp_link") == true || findString("use_http_tmp_link") == "1",
            nginxSecureLink = findBoolean("nginx_secure_link") == true || findString("nginx_secure_link") == "1",
            flussonicTemporaryLink = findBoolean("flussonic_tmp_link") == true || findString("flussonic_tmp_link") == "1",
            wowzaTemporaryLink = findBoolean("wowza_tmp_link") == true || findString("wowza_tmp_link") == "1",
            useLoadBalancing = findBoolean("use_load_balancing") == true || findString("use_load_balancing") == "1",
            allowLocalTimeshift = findBoolean("allow_local_timeshift") == true || findString("allow_local_timeshift") == "1",
            allowLocalPvr = findBoolean("allow_local_pvr") == true || findString("allow_local_pvr") == "1",
            allowRemotePvr = findBoolean("allow_remote_pvr") == true || findString("allow_remote_pvr") == "1",
            archiveAvailable = findBoolean("archive") == true || findString("archive") == "1"
        )
        val alternateCommands = extractAlternateCommands()
        val descriptor = buildStalkerPlaybackDescriptor(
            primaryCmd = findString("cmd"),
            alternateCommands = alternateCommands,
            capabilities = capabilities
        )
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
            playbackDescriptor = descriptor,
            commandVariants = descriptor?.candidates.orEmpty(),
            portalCapabilities = capabilities,
            mcCmd = findString("mc_cmd"),
            useHttpTemporaryLink = capabilities.useHttpTemporaryLink,
            nginxSecureLink = capabilities.nginxSecureLink,
            flussonicTemporaryLink = capabilities.flussonicTemporaryLink,
            wowzaTemporaryLink = capabilities.wowzaTemporaryLink,
            useLoadBalancing = capabilities.useLoadBalancing,
            allowLocalTimeshift = capabilities.allowLocalTimeshift,
            allowLocalPvr = capabilities.allowLocalPvr,
            allowRemotePvr = capabilities.allowRemotePvr,
            archiveAvailable = capabilities.archiveAvailable,
            plot = findString("description") ?: findString("plot"),
            cast = findString("actors"),
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
            addedAt = parseDateTime(findString("added")) ?: 0L,
            isAdult = findBoolean("censored") == true,
            isSeries = findBoolean("is_series") == true || findString("is_series") == "1"
        )
    }

    private fun JsonObject.extractAlternateCommands(): List<Pair<String, String>> {
        val directVariants = buildList {
            listOf("cmd_1", "cmd_2", "cmd_3", "mc_cmd").forEach { key ->
                findString(key)?.takeIf { it.isNotBlank() }?.let { add(key to it) }
            }
        }
        val commandArrayVariants = (this["cmds"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val entry = element.jsonObjectOrNull() ?: return@mapIndexedNotNull null
            val cmd = entry.findString("url")
                ?: entry.findString("cmd")
                ?: return@mapIndexedNotNull null
            "cmds[$index]" to cmd
        }
        return (directVariants + commandArrayVariants).distinctBy { it.second.trim() }
    }

    private fun GsonJsonObject.extractAlternateCommands(): List<Pair<String, String>> {
        val directVariants = buildList {
            listOf("cmd_1", "cmd_2", "cmd_3", "mc_cmd").forEach { key ->
                findString(key)?.takeIf { it.isNotBlank() }?.let { add(key to it) }
            }
        }
        val commandArrayVariants = getAsJsonArray("cmds")
            ?.mapIndexedNotNull { index, element ->
                val entry = element.asJsonObjectOrNull() ?: return@mapIndexedNotNull null
                val cmd = entry.findString("url")
                    ?: entry.findString("cmd")
                    ?: return@mapIndexedNotNull null
                "cmds[$index]" to cmd
            }
            .orEmpty()
        return (directVariants + commandArrayVariants).distinctBy { it.second.trim() }
    }

    private fun StalkerItemRecord.looksLikeSeasonShell(): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.startsWith("Season ", ignoreCase = true)) {
            return true
        }
        return id.contains(':') && isSeries.not() && cmd.isNullOrBlank().not()
    }

    private fun JsonObject.toSeasonRecord(
        episodeEntries: List<JsonObject>,
        fallbackSeasonNumber: Int? = null
    ): StalkerSeasonRecord {
        val seasonNumber = findString("season_id")?.toIntOrNull()
            ?: findString("season_number")?.toIntOrNull()
            ?: extractSeasonNumberFromCmd(findString("cmd"))
            ?: fallbackSeasonNumber
            ?: 1
        val seasonName = findString("title")
            ?: findString("name")
            ?: "Season $seasonNumber"
        val explicitEpisodes = episodeEntries
            .filterNot { entry -> entry.looksLikeSeasonShellRow() }
            .mapIndexedNotNull { index, entry ->
            entry.toEpisodeRecord(index + 1, seasonNumber)
            }
        return StalkerSeasonRecord(
            seasonNumber = seasonNumber,
            name = seasonName,
            coverUrl = sanitizeUrl(findString("screenshot_uri")) ?: sanitizeUrl(findString("cover")),
            episodes = explicitEpisodes.takeUnless { it.isEmpty() || it.isSeasonShellOnly() }
                ?: buildEpisodesFromSeriesShell(seasonNumber, seasonName)
        )
    }

    private fun JsonObject.toSeasonShellRecord(fallbackSeasonNumber: Int): StalkerSeasonRecord? {
        val episodeNumbers = extractSeasonShellEpisodeNumbers()
        if (episodeNumbers.isEmpty()) {
            return null
        }
        val seasonNumber = findString("season_id")?.toIntOrNull()
            ?: findString("season_number")?.toIntOrNull()
            ?: extractSeasonNumberFromCmd(findString("cmd"))
            ?: findString("name")?.filter(Char::isDigit)?.toIntOrNull()
            ?: fallbackSeasonNumber
        val seasonName = findString("title")
            ?: findString("name")
            ?: "Season $seasonNumber"
        return StalkerSeasonRecord(
            seasonNumber = seasonNumber,
            name = seasonName,
            coverUrl = sanitizeUrl(findString("screenshot_uri")) ?: sanitizeUrl(findString("cover")),
            episodes = buildEpisodesFromSeriesShell(seasonNumber, seasonName)
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

    private fun JsonObject.looksLikeSeasonShellRow(): Boolean {
        val episodeNumber = findString("series_number")?.toIntOrNull()
            ?: findString("episode_number")?.toIntOrNull()
        if (episodeNumber != null) {
            return false
        }
        if (extractSeasonShellEpisodeNumbers().isNotEmpty()) {
            return true
        }
        val title = findString("name") ?: findString("title") ?: ""
        if (title.trim().startsWith("Season ", ignoreCase = true)) {
            return true
        }
        return extractSeasonNumberFromCmd(findString("cmd")) != null
    }

    private fun JsonObject.buildEpisodesFromSeriesShell(
        fallbackSeasonNumber: Int,
        fallbackSeasonName: String
    ): List<StalkerEpisodeRecord> {
        val episodeNumbers = extractSeasonShellEpisodeNumbers()
        if (episodeNumbers.isEmpty()) {
            return emptyList()
        }
        val seasonName = fallbackSeasonName.ifBlank { "Season $fallbackSeasonNumber" }
        return episodeNumbers.map { episodeNumber ->
            StalkerEpisodeRecord(
                id = "${findString("id") ?: findString("series_id") ?: "season:$fallbackSeasonNumber"}:$episodeNumber",
                title = "$seasonName Episode $episodeNumber",
                episodeNumber = episodeNumber,
                seasonNumber = fallbackSeasonNumber,
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
    }

    private fun JsonObject.extractSeasonShellEpisodeNumbers(): List<Int> {
        val seriesArray = this["series"]?.jsonArrayOrNull() ?: return emptyList()
        return seriesArray.mapIndexedNotNull { index, element ->
            element.primitiveContentOrNull()?.toIntOrNull()
                ?: element.jsonObjectOrNull()?.findString("series_number")?.toIntOrNull()
                ?: element.jsonObjectOrNull()?.findString("episode_number")?.toIntOrNull()
                ?: (index + 1)
        }
    }

    private fun List<StalkerEpisodeRecord>.isSeasonShellOnly(): Boolean {
        if (size != 1) {
            return false
        }
        val entry = first()
        return entry.episodeNumber <= 1 && entry.title.startsWith("Season ", ignoreCase = true)
    }

    private fun extractSeasonNumberFromCmd(cmd: String?): Int? {
        val decoded = decodeBase64JsonPayload(cmd) ?: return null
        return decoded.findString("season_num")?.toIntOrNull()
            ?: decoded.findString("season_id")?.toIntOrNull()
    }

    private fun decodeBase64JsonPayload(cmd: String?): GsonJsonObject? {
        val value = cmd?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val decoded = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            JsonParser.parseString(decoded).asJsonObject
        }.getOrNull()
    }

    private fun JsonElement.toProgramRecords(channelId: String? = null): List<StalkerProgramRecord> {
        return extractListElements().mapNotNull { entry ->
            val resolvedChannelId = channelId ?: entry.findProgramChannelId() ?: return@mapNotNull null
            val startMillis = entry.findString("start_timestamp")?.toLongOrNull()?.times(1000L)
                ?: entry.findString("time")?.let(::parseDateTime)
                ?: return@mapNotNull null
            val endMillis = entry.findString("stop_timestamp")?.toLongOrNull()?.times(1000L)
                ?: entry.findString("time_to")?.let(::parseDateTime)
                ?: startMillis + (entry.findString("duration")?.toLongOrNull()?.times(60_000L) ?: DEFAULT_PROGRAM_DURATION_MILLIS)
            StalkerProgramRecord(
                id = entry.findString("id") ?: "$resolvedChannelId:$startMillis",
                channelId = resolvedChannelId,
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

    private fun JsonElement.findProgramChannelId(): String? {
        val payload = payloadObjectOrNull() ?: return null
        return payload.findString("ch_id")
            ?: payload.findString("channel_id")
            ?: payload.findString("id_channel")
            ?: payload.findString("xmltv_id")
            ?: payload.findString("epg_id")
    }

    private fun JsonElement.extractListElements(): List<JsonElement> {
        val jsValue = rootObjectOrNull()?.get("js") ?: this
        return when {
            jsValue is JsonArray -> jsValue.toList()
            jsValue is JsonObject && jsValue["data"] is JsonArray -> jsValue["data"]!!.jsonArray.toList()
            jsValue is JsonObject && jsValue["items"] is JsonArray -> jsValue["items"]!!.jsonArray.toList()
            // Object-keyed catalogs: {"js":{"data":{"100":{...}}}} — use map values
            jsValue is JsonObject && jsValue["data"] is JsonObject -> jsValue["data"]!!.jsonObject.values.toList()
            jsValue is JsonObject && jsValue["items"] is JsonObject -> jsValue["items"]!!.jsonObject.values.toList()
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

    private fun GsonJsonObject.findString(key: String): String? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asJsonPrimitive
            .takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun GsonJsonObject.findBoolean(key: String): Boolean? {
        val value = findString(key)?.lowercase(Locale.ROOT) ?: return null
        return when (value) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }

    private fun com.google.gson.JsonElement.asJsonObjectOrNull(): GsonJsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun JsonReader.nextStringOrSkip(): String? {
        return when (peek()) {
            JsonToken.STRING,
            JsonToken.NUMBER,
            JsonToken.BOOLEAN -> nextString()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
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

    private fun sanitizePortalResponseBody(body: String): String {
        val withoutBom = body.trimStart('\uFEFF')
        val sanitizedControls = withoutBom.filter { char ->
            char == '\n' || char == '\r' || char == '\t' || char.code >= 0x20
        }
        return sanitizedControls.trim()
    }

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<body", ignoreCase = true)
    }

    private fun extractEmbeddedJson(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        val callbackStart = trimmed.indexOf('(')
        val callbackEnd = trimmed.lastIndexOf(')')
        if (callbackStart in 1 until callbackEnd) {
            val callbackPayload = trimmed.substring(callbackStart + 1, callbackEnd).trim()
            if (callbackPayload.startsWith('{') || callbackPayload.startsWith('[')) {
                return callbackPayload
            }
        }

        val objectStart = trimmed.indexOf('{')
        val objectEnd = trimmed.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            return trimmed.substring(objectStart, objectEnd + 1)
        }

        val arrayStart = trimmed.indexOf('[')
        val arrayEnd = trimmed.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return trimmed.substring(arrayStart, arrayEnd + 1)
        }

        return null
    }

    private fun actionSuffix(action: String?): String =
        action?.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()

    private fun candidateAuthModes(profile: StalkerDeviceProfile): List<StalkerAuthMode> {
        return when (profile.authMode) {
            StalkerAuthMode.AUTO -> buildList {
                val hasMac = profile.macAddress.isNotBlank()
                val hasCredentials = profile.username.isNotBlank()
                if (hasMac && hasCredentials) add(StalkerAuthMode.MAC_PLUS_CREDENTIALS)
                if (hasMac) add(StalkerAuthMode.MAC_ONLY)
                if (hasCredentials) add(StalkerAuthMode.CREDENTIALS_ONLY)
                if (isEmpty()) add(StalkerAuthMode.MAC_ONLY)
            }
            else -> listOf(profile.authMode)
        }.distinct()
    }

    private fun candidateRecipes(
        profile: StalkerDeviceProfile,
        effectiveAuthMode: StalkerAuthMode
    ): List<StalkerRecipeSpec> {
        val defaultRecipe = defaultRecipeFor(
            authMode = effectiveAuthMode,
            fingerprintHint = profile.portalFingerprint,
            presetHint = profile.magPreset
        ).copy(
            recipe = profile.bootstrapRecipe.takeUnless { it == StalkerBootstrapRecipe.GENERIC_SAFE }
                ?: defaultRecipeFor(
                    authMode = effectiveAuthMode,
                    fingerprintHint = profile.portalFingerprint,
                    presetHint = profile.magPreset
                ).recipe,
            magPreset = profile.magPreset.takeUnless { it == StalkerMagPreset.GENERIC_SAFE }
                ?: defaultRecipeFor(
                    authMode = effectiveAuthMode,
                    fingerprintHint = profile.portalFingerprint,
                    presetHint = profile.magPreset
                ).magPreset
        )
        return (listOf(defaultRecipe) + fallbackRecipesFor(effectiveAuthMode))
            .distinctBy { "${it.recipe}:${it.magPreset}:${it.authMode}" }
    }

    private fun candidateAuthAttempts(
        profile: StalkerDeviceProfile,
        effectiveAuthMode: StalkerAuthMode
    ): List<StalkerAuthAttempt> {
        val recipes = candidateRecipes(profile, effectiveAuthMode)
        val defaultRecipe = recipes.firstOrNull() ?: return emptyList()
        val hasLearnedEndpointPreference = profile.endpointPreference != StalkerEndpointPreference.AUTO ||
            defaultRecipe.endpointPreference != StalkerEndpointPreference.AUTO
        return if (hasLearnedEndpointPreference) {
            recipes.flatMapIndexed { recipeIndex, recipe ->
                orderedLoadUrlCandidates(profile, recipe).map { loadUrl ->
                    StalkerAuthAttempt(recipeIndex, recipe, loadUrl)
                }
            }
        } else {
            orderedLoadUrlCandidates(profile, defaultRecipe).flatMap { loadUrl ->
                recipes.mapIndexed { recipeIndex, recipe ->
                    StalkerAuthAttempt(recipeIndex, recipe, loadUrl)
                }
            }
        }
    }

    private fun orderedLoadUrlCandidates(
        profile: StalkerDeviceProfile,
        recipe: StalkerRecipeSpec
    ): List<String> {
        val baseCandidates = StalkerUrlFactory.loadUrlCandidates(profile.portalUrl)
        when (profile.endpointPreference) {
            StalkerEndpointPreference.PORTAL -> {
                return baseCandidates.filter { candidate ->
                    candidate.lowercase(Locale.ROOT).endsWith("/portal.php")
                }.ifEmpty { baseCandidates }
            }
            StalkerEndpointPreference.SERVER_LOAD -> {
                return baseCandidates.filter { candidate ->
                    candidate.lowercase(Locale.ROOT).endsWith("/server/load.php")
                }.ifEmpty { baseCandidates }
            }
            StalkerEndpointPreference.AUTO -> Unit
        }
        val preferred = recipe.endpointPreference.takeUnless { it == StalkerEndpointPreference.AUTO }
        return when (preferred) {
            StalkerEndpointPreference.PORTAL ->
                baseCandidates.sortedByDescending { candidate -> candidate.lowercase(Locale.ROOT).endsWith("/portal.php") }
            StalkerEndpointPreference.SERVER_LOAD ->
                baseCandidates.sortedByDescending { candidate -> candidate.lowercase(Locale.ROOT).endsWith("/server/load.php") }
            StalkerEndpointPreference.AUTO,
            null -> baseCandidates
        }
    }

    private data class StalkerAuthAttempt(
        val recipeIndex: Int,
        val recipe: StalkerRecipeSpec,
        val loadUrl: String
    )

    private fun endpointPreferenceFor(loadUrl: String): StalkerEndpointPreference =
        when {
            loadUrl.lowercase(Locale.ROOT).endsWith("/portal.php") -> StalkerEndpointPreference.PORTAL
            loadUrl.lowercase(Locale.ROOT).endsWith("/server/load.php") -> StalkerEndpointPreference.SERVER_LOAD
            else -> StalkerEndpointPreference.AUTO
        }

    private fun appendArchiveWindow(
        url: String,
        startSeconds: Long?,
        endSeconds: Long?
    ): String? {
        if (startSeconds == null || startSeconds <= 0L) {
            return url
        }
        val endValue = endSeconds?.takeIf { it > 0L } ?: startSeconds
        val separator = if ('?' in url) '&' else '?'
        val existingUtc = Regex("""([?&])utc=\d+""")
        val existingLutc = Regex("""([?&])lutc=\d+""")
        val withUtc = if (existingUtc.containsMatchIn(url)) {
            existingUtc.replace(url) { match ->
                "${match.groupValues[1]}utc=$startSeconds"
            }
        } else {
            "$url${separator}utc=$startSeconds"
        }
        return if (existingLutc.containsMatchIn(withUtc)) {
            existingLutc.replace(withUtc) { match ->
                "${match.groupValues[1]}lutc=$endValue"
            }
        } else {
            val lutcSeparator = if ('?' in withUtc) '&' else '?'
            "$withUtc${lutcSeparator}lutc=$endValue"
        }
    }

    private fun resolveCookieMode(
        base: StalkerCookieMode,
        serverCookieHeader: String,
        recipe: StalkerRecipeSpec
    ): StalkerCookieMode {
        val hasServerCookies = serverCookieHeader.isNotBlank()
        if (!hasServerCookies) return base
        return when {
            recipe.playbackBackendHint in setOf(
                StalkerPlaybackBackendHint.PLAY_LIVE,
                StalkerPlaybackBackendHint.PLAY_MOVIE,
                StalkerPlaybackBackendHint.TEMP_LINK,
                StalkerPlaybackBackendHint.TEMP_LINK_STRICT
            ) && base == StalkerCookieMode.CREATE_LINK -> StalkerCookieMode.BOTH
            recipe.playbackBackendHint != StalkerPlaybackBackendHint.AUTO -> StalkerCookieMode.PLAYBACK
            else -> StalkerCookieMode.CREATE_LINK
        }
    }

    private fun StalkerAuthMode.requiresCredentials(): Boolean =
        this == StalkerAuthMode.CREDENTIALS_ONLY || this == StalkerAuthMode.MAC_PLUS_CREDENTIALS

    private fun resolvePortalProfile(
        authMode: StalkerAuthMode,
        providerProfile: StalkerProviderProfile,
        requestedProfile: StalkerDeviceProfile
    ): StalkerPortalProfile {
        val hasCredentials = requestedProfile.username.isNotBlank()
        val modulesRestricted = providerProfile.moduleNames.isNotEmpty()
        val strictFingerprint = requestedProfile.serialNumber.isNotBlank() ||
            requestedProfile.deviceId.isNotBlank() ||
            requestedProfile.deviceId2.isNotBlank() ||
            requestedProfile.signature.isNotBlank()
        return when {
            modulesRestricted -> StalkerPortalProfile.MODULE_GATED
            authMode == StalkerAuthMode.MAC_PLUS_CREDENTIALS -> StalkerPortalProfile.AUTH_PLUS_MAG
            authMode == StalkerAuthMode.CREDENTIALS_ONLY -> StalkerPortalProfile.AUTH_REQUIRED
            providerProfile.authAccess == false && hasCredentials -> StalkerPortalProfile.AUTH_PLUS_MAG
            providerProfile.authAccess == false && requestedProfile.macAddress.isBlank() -> StalkerPortalProfile.AUTH_REQUIRED
            providerProfile.portalCapabilities.usesTemporaryLinks && strictFingerprint -> StalkerPortalProfile.MAG_STRICT
            else -> StalkerPortalProfile.MAG_BASIC
        }
    }

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
        private const val TAG = "OkHttpStalkerApi"
        private const val MAX_PAGE_COUNT = 200
        private const val DEFAULT_PROGRAM_DURATION_MILLIS = 30 * 60_000L
        /** Hard ceiling for the streamed `get_epg_info` body; anything larger is treated as a portal fault. */
        private const val MAX_EPG_BYTES = 64L * 1024L * 1024L
        /** Records retained by the legacy buffered [getBulkEpg]/[getEpg] APIs to keep callers heap-safe. */
        private const val MAX_INLINE_EPG_RECORDS = 1500
        private val FORM_URL_ENCODED_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        private const val DEFAULT_VERSION_STRING =
            "ImageDescription: 0.2.18-r19-pub-250; ImageDate: Mon Jun 12 11:04:49 EEST 2017; PORTAL version: 5.6.10; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x23"
    }
}

internal fun buildStalkerDeviceProfile(
    portalUrl: String,
    macAddress: String,
    authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    magPresetHint: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    portalFingerprintHint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    bootstrapRecipeHint: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    endpointPreferenceHint: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    cookieModeHint: StalkerCookieMode = StalkerCookieMode.NONE,
    playbackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    username: String = "",
    password: String = "",
    deviceProfile: String,
    timezone: String,
    locale: String,
    httpUserAgentOverride: String = "",
    httpHeadersOverride: String = "",
    serialNumberOverride: String = "",
    deviceIdOverride: String = "",
    deviceId2Override: String = "",
    signatureOverride: String = "",
    stalkerAdvancedOptionsJson: String = ""
): StalkerDeviceProfile {
    val advancedOptions = StalkerAdvancedOptionsCodec.decode(stalkerAdvancedOptionsJson)
    val preset = stalkerMagPresetSpec(magPresetHint)
    val normalizedInputProfile = deviceProfile.trim()
    val normalizedProfile = when {
        normalizedInputProfile.isBlank() -> preset.defaultDeviceProfile
        magPresetHint != StalkerMagPreset.GENERIC_SAFE && normalizedInputProfile.equals("MAG250", ignoreCase = true) ->
            preset.defaultDeviceProfile
        else -> normalizedInputProfile
    }
    val normalizedTimezone = timezone.ifBlank { java.util.TimeZone.getDefault().id }
    val normalizedLocale = locale.ifBlank { Locale.getDefault().language.ifBlank { "en" } }
    val normalizedMac = macAddress.uppercase(Locale.ROOT)
    val normalizedUsername = username.trim()
    val normalizedHttpUserAgent = httpUserAgentOverride.trim()
    val normalizedHttpHeaders = httpHeadersOverride.trim()
    val headerOverrides = parseStalkerHeaderOverrides(normalizedHttpHeaders)
    val effectiveAuthMode = sanitizeStalkerAuthMode(
        requested = authMode,
        normalizedMac = normalizedMac,
        normalizedUsername = normalizedUsername
    )
    val defaultUserAgent =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) $normalizedProfile stbapp ver: 2 rev: ${preset.imageVersion} Safari/533.3"
    val apiUserAgent = advancedOptions.apiUserAgent.trim().ifBlank {
        resolveStalkerUserAgent(
            defaultUserAgent = defaultUserAgent,
            httpUserAgentOverride = normalizedHttpUserAgent,
            headerOverrides = headerOverrides
        ).orEmpty()
    }
    val playerUserAgent = advancedOptions.playerUserAgent.trim().ifBlank {
        apiUserAgent
    }
    return StalkerDeviceProfile(
        portalUrl = portalUrl,
        macAddress = normalizedMac,
        authMode = effectiveAuthMode,
        magPreset = magPresetHint,
        portalFingerprint = portalFingerprintHint,
        bootstrapRecipe = bootstrapRecipeHint,
        endpointPreference = endpointPreferenceHint,
        cookieMode = cookieModeHint,
        playbackBackendHint = playbackBackendHint,
        username = normalizedUsername,
        password = password,
        deviceProfile = normalizedProfile,
        timezone = normalizedTimezone,
        locale = normalizedLocale,
        serialNumber = serialNumberOverride.trim().uppercase(Locale.ROOT),
        deviceId = deviceIdOverride.trim().uppercase(Locale.ROOT),
        deviceId2 = deviceId2Override.trim().uppercase(Locale.ROOT),
        signature = signatureOverride.trim().uppercase(Locale.ROOT),
        userAgent = apiUserAgent,
        playerUserAgent = playerUserAgent,
        xUserAgent = "Model: $normalizedProfile; Link: ${advancedOptions.normalizedLink}",
        httpUserAgent = normalizedHttpUserAgent,
        httpHeaders = normalizedHttpHeaders,
        headerOverrides = headerOverrides,
        advancedOptions = advancedOptions
    )
}

private fun sanitizeStalkerAuthMode(
    requested: StalkerAuthMode,
    normalizedMac: String,
    normalizedUsername: String
): StalkerAuthMode {
    val hasMac = normalizedMac.isNotBlank()
    val hasCredentials = normalizedUsername.isNotBlank()
    return when (requested) {
        StalkerAuthMode.AUTO -> StalkerAuthMode.AUTO
        StalkerAuthMode.MAC_ONLY -> when {
            hasMac -> StalkerAuthMode.MAC_ONLY
            hasCredentials -> StalkerAuthMode.CREDENTIALS_ONLY
            else -> StalkerAuthMode.AUTO
        }
        StalkerAuthMode.MAC_PLUS_CREDENTIALS -> when {
            hasMac && hasCredentials -> StalkerAuthMode.MAC_PLUS_CREDENTIALS
            hasMac -> StalkerAuthMode.MAC_ONLY
            hasCredentials -> StalkerAuthMode.CREDENTIALS_ONLY
            else -> StalkerAuthMode.AUTO
        }
        StalkerAuthMode.CREDENTIALS_ONLY -> when {
            hasCredentials -> StalkerAuthMode.CREDENTIALS_ONLY
            hasMac -> StalkerAuthMode.MAC_ONLY
            else -> StalkerAuthMode.AUTO
        }
    }
}

private fun StalkerDeviceProfile.withRecipe(
    recipe: StalkerRecipeSpec,
    effectiveAuthMode: StalkerAuthMode
): StalkerDeviceProfile {
    return buildStalkerDeviceProfile(
        portalUrl = portalUrl,
        macAddress = macAddress,
        authMode = effectiveAuthMode,
        magPresetHint = recipe.magPreset,
        portalFingerprintHint = portalFingerprint,
        bootstrapRecipeHint = recipe.recipe,
        endpointPreferenceHint = recipe.endpointPreference.takeUnless {
            it == StalkerEndpointPreference.AUTO
        } ?: endpointPreference,
        cookieModeHint = recipe.cookieMode.takeUnless {
            it == StalkerCookieMode.NONE
        } ?: cookieMode,
        playbackBackendHint = recipe.playbackBackendHint.takeUnless {
            it == StalkerPlaybackBackendHint.AUTO
        } ?: playbackBackendHint,
        username = username,
        password = password,
        deviceProfile = deviceProfile,
        timezone = timezone,
        locale = locale,
        httpUserAgentOverride = httpUserAgent,
        httpHeadersOverride = httpHeaders,
        serialNumberOverride = serialNumber,
        deviceIdOverride = deviceId,
        deviceId2Override = deviceId2,
        signatureOverride = signature,
        stalkerAdvancedOptionsJson = StalkerAdvancedOptionsCodec.encode(advancedOptions)
    )
}

private fun resolveStalkerUserAgent(
    defaultUserAgent: String,
    httpUserAgentOverride: String,
    headerOverrides: Map<String, String?>
): String? {
    val overriddenUserAgent = headerOverrides.entries.firstOrNull { (name, _) ->
        name.equals("User-Agent", ignoreCase = true)
    }?.value
    return overriddenUserAgent ?: httpUserAgentOverride.ifBlank { defaultUserAgent }
}

private fun Request.Builder.applyStalkerHeaderOverrides(
    headerOverrides: Map<String, String?>,
    preserveUserAgent: Boolean = false
): Request.Builder = apply {
    headerOverrides.forEach { (name, value) ->
        if (preserveUserAgent && name.equals("User-Agent", ignoreCase = true)) {
            return@forEach
        }
        if (value == null) {
            removeHeader(name)
        } else {
            header(name, value)
        }
    }
}

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


/**
 * InputStream wrapper that throws [IOException] when more than [maxBytes] bytes are read from
 * [delegate]. Used to bound the streamed get_epg_info response so a misbehaving portal
 * cannot push the device into low-memory or OOM territory.
 */
private class ByteSizeLimitInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
    private val onOverflow: () -> String
) : InputStream() {
    private var bytesRead: Long = 0

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            bytesRead += 1
            checkLimit()
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            bytesRead += n
            checkLimit()
        }
        return n
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()

    private fun checkLimit() {
        if (bytesRead > maxBytes) {
            throw IOException(onOverflow())
        }
    }
}

private class InMemoryStalkerCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val key = url.topPrivateDomain() ?: url.host
        val existing = store.getOrPut(key) { mutableListOf() }
        cookies.forEach { incoming ->
            existing.removeAll { current ->
                current.name == incoming.name &&
                    current.domain == incoming.domain &&
                    current.path == incoming.path
            }
            if (!incoming.expiresAt.let { it > 0L } || incoming.expiresAt > System.currentTimeMillis()) {
                existing += incoming
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val key = url.topPrivateDomain() ?: url.host
        val existing = store[key].orEmpty()
        val now = System.currentTimeMillis()
        val valid = existing.filter { cookie -> cookie.expiresAt > now && cookie.matches(url) }
        if (valid.size != existing.size) {
            store[key] = valid.toMutableList()
        }
        return valid
    }

    fun cookieHeaderFor(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return loadForRequest(httpUrl)
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }

    fun clear() {
        store.clear()
    }
}
