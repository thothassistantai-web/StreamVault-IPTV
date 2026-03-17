package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncManager"

enum class SyncRepairSection {
    EPG,
    MOVIES,
    SERIES
}

@Singleton
class SyncManager @Inject constructor(
    private val providerDao: ProviderDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val xtreamApiService: XtreamApiService,
    private val m3uParser: M3uParser,
    private val epgRepository: EpgRepository,
    private val okHttpClient: OkHttpClient,
    private val syncMetadataRepository: SyncMetadataRepository
) {
    private data class SyncOutcome(
        val partial: Boolean = false,
        val warnings: List<String> = emptyList()
    )

    private data class M3uImportStats(
        val header: M3uParser.M3uHeader,
        val liveCount: Int,
        val movieCount: Int,
        val warnings: List<String> = emptyList()
    )

    private data class StreamedPlaylist(
        val inputStream: InputStream,
        val contentEncoding: String? = null,
        val sourceName: String? = null
    )

    private class CategoryAccumulator(
        private val providerId: Long,
        private val type: ContentType,
        private val startId: Long  // retained for API compatibility but no longer used in ID generation
    ) {
        private val categoryIds = LinkedHashMap<String, Long>()

        fun idFor(name: String): Long {
            return categoryIds.getOrPut(name) { stableId(providerId, type, name) }
        }

        fun entities(): List<CategoryEntity> {
            return categoryIds.map { (name, id) ->
                CategoryEntity(
                    categoryId = id,
                    name = name,
                    parentId = 0,
                    type = type,
                    providerId = providerId,
                    isAdult = AdultContentClassifier.isAdultCategoryName(name)
                )
            }
        }

        /** Generates a stable, collision-resistant ID from the provider+type+name triple. */
        private fun stableId(providerId: Long, type: ContentType, name: String): Long {
            val key = "$providerId/${type.name}/$name"
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
            var result = 0L
            for (i in 0 until 8) result = (result shl 8) or (bytes[i].toLong() and 0xFF)
            return (result and Long.MAX_VALUE).coerceAtLeast(1L)
        }
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> {
        val providerEntity = providerDao.getById(providerId)
            ?: return com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = CredentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
        _syncState.value = SyncState.Syncing("Starting...")

        return try {
            val outcome = when (provider.type) {
                ProviderType.XTREAM_CODES -> syncXtream(provider, force, onProgress)
                ProviderType.M3U -> syncM3u(provider, force, onProgress)
            }
            providerDao.updateSyncTime(providerId, System.currentTimeMillis())
            _syncState.value = if (outcome.partial) {
                SyncState.Partial("Sync completed with warnings", outcome.warnings)
            } else {
                SyncState.Success()
            }
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for provider $providerId: ${redactUrlForLogs(e.message)}")
            _syncState.value = SyncState.Error(e.message ?: "Unknown error", e)
            com.streamvault.domain.model.Result.error(e.message ?: "Sync failed", e)
        }
    }

    fun resetState() {
        _syncState.value = SyncState.Idle
    }

    suspend fun retrySection(
        providerId: Long,
        section: SyncRepairSection,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> {
        val providerEntity = providerDao.getById(providerId)
            ?: return com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = CredentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()

        return try {
            when (section) {
                SyncRepairSection.EPG -> syncEpgOnly(provider, onProgress)
                SyncRepairSection.MOVIES -> syncMoviesOnly(provider, onProgress)
                SyncRepairSection.SERIES -> syncSeriesOnly(provider, onProgress)
            }
            _syncState.value = SyncState.Success()
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Section retry failed for provider $providerId [$section]: ${redactUrlForLogs(e.message)}")
            _syncState.value = SyncState.Error(e.message ?: "Retry failed", e)
            com.streamvault.domain.model.Result.error(e.message ?: "Retry failed", e)
        }
    }

    private suspend fun syncXtream(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        UrlSecurityPolicy.validateXtreamServerUrl(provider.serverUrl)?.let { message ->
            throw IllegalStateException(message)
        }
        progress(onProgress, "Connecting to server...")
        val api = XtreamProvider(
            providerId = provider.id,
            api = xtreamApiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password
        )

        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(onProgress, "Downloading Live TV...")
            val cats = retryTransient { api.getLiveCategories().getOrThrow("Live categories") }
            categoryDao.replaceAll(provider.id, "LIVE", cats.map { it.toEntity(provider.id) })

            val channels = retryTransient { api.getLiveStreams().getOrThrow("Live streams") }
            channelDao.replaceAll(provider.id, channels.map { it.toEntity() })

            metadata = metadata.copy(lastLiveSync = now, liveCount = channels.size)
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastMovieSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(onProgress, "Downloading Movies...")
            val catsResult = runCatching { retryTransient { api.getVodCategories().getOrThrow("VOD categories") } }
            catsResult.getOrNull()?.let {
                categoryDao.replaceAll(provider.id, "MOVIE", it.map { category -> category.toEntity(provider.id) })
            } ?: warnings.add("Movies categories sync failed")

            val moviesResult = runCatching { retryTransient { api.getVodStreams().getOrThrow("VOD streams") } }
            moviesResult.getOrNull()?.let {
                movieDao.replaceAll(provider.id, it.map { movie -> movie.toEntity() })
                metadata = metadata.copy(lastMovieSync = now, movieCount = it.size)
                syncMetadataRepository.updateMetadata(metadata)
            } ?: warnings.add("Movies streams sync failed")
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastSeriesSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(onProgress, "Downloading Series...")
            val catsResult = runCatching { retryTransient { api.getSeriesCategories().getOrThrow("Series categories") } }
            catsResult.getOrNull()?.let {
                categoryDao.replaceAll(provider.id, "SERIES", it.map { category -> category.toEntity(provider.id) })
            } ?: warnings.add("Series categories sync failed")

            val seriesResult = runCatching { retryTransient { api.getSeriesList().getOrThrow("Series list") } }
            seriesResult.getOrNull()?.let {
                seriesDao.replaceAll(provider.id, it.map { series -> series.toEntity() })
                metadata = metadata.copy(lastSeriesSync = now, seriesCount = it.size)
                syncMetadataRepository.updateMetadata(metadata)
            } ?: warnings.add("Series list sync failed")
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastEpgSync, ContentCachePolicy.EPG_TTL_MILLIS, now)) {
            try {
                progress(onProgress, "Downloading EPG...")
                val base = provider.serverUrl.trimEnd('/')
                val xmltvUrl = provider.epgUrl.ifBlank {
                    XtreamUrlFactory.buildXmltvUrl(base, provider.username, provider.password)
                }
                UrlSecurityPolicy.validateOptionalEpgUrl(xmltvUrl)?.let { message ->
                    throw IllegalStateException(message)
                }
                retryTransient { epgRepository.refreshEpg(provider.id, xmltvUrl) }
                metadata = metadata.copy(lastEpgSync = now)
                syncMetadataRepository.updateMetadata(metadata)
            } catch (e: Exception) {
                Log.e(TAG, "EPG sync failed (non-fatal): ${e.message}")
                warnings.add("EPG sync failed")
            }
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    private suspend fun syncM3u(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSync, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            val stats = withContext(Dispatchers.IO) { importM3uPlaylist(provider, onProgress) }
            if (stats.liveCount == 0 && stats.movieCount == 0) {
                throw IllegalStateException("Playlist is empty or contains no supported entries")
            }
            warnings += stats.warnings
            if (provider.epgUrl.isBlank() && !stats.header.tvgUrl.isNullOrBlank()) {
                providerDao.updateEpgUrl(provider.id, stats.header.tvgUrl)
            }
            metadata = metadata.copy(
                lastLiveSync = now,
                lastMovieSync = now,
                lastSeriesSync = now,
                liveCount = stats.liveCount,
                movieCount = stats.movieCount
            )
            syncMetadataRepository.updateMetadata(metadata)
        }

        val currentEpgUrl = providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
        if (!currentEpgUrl.isNullOrBlank() && (force || ContentCachePolicy.shouldRefresh(metadata.lastEpgSync, ContentCachePolicy.EPG_TTL_MILLIS, now))) {
            val epgValidationError = UrlSecurityPolicy.validateOptionalEpgUrl(currentEpgUrl)
            if (epgValidationError != null) {
                warnings.add(epgValidationError)
            } else {
                try {
                    progress(onProgress, "Downloading EPG...")
                    retryTransient { epgRepository.refreshEpg(provider.id, currentEpgUrl) }
                    metadata = metadata.copy(lastEpgSync = now)
                    syncMetadataRepository.updateMetadata(metadata)
                } catch (e: Exception) {
                    Log.e(TAG, "EPG sync failed (non-fatal): ${e.message}")
                    warnings.add("EPG sync failed")
                }
            }
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    private suspend fun syncEpgOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        progress(onProgress, "Retrying EPG...")
        val epgUrl = when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                val base = provider.serverUrl.trimEnd('/')
                provider.epgUrl.ifBlank {
                    XtreamUrlFactory.buildXmltvUrl(base, provider.username, provider.password)
                }
            }
            ProviderType.M3U -> providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
        }
        if (epgUrl.isBlank()) {
            throw IllegalStateException("No EPG URL configured for this provider")
        }
        UrlSecurityPolicy.validateOptionalEpgUrl(epgUrl)?.let { message ->
            throw IllegalStateException(message)
        }
        retryTransient { epgRepository.refreshEpg(provider.id, epgUrl) }
        val now = System.currentTimeMillis()
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(lastEpgSync = now)
        syncMetadataRepository.updateMetadata(metadata)
    }

    private suspend fun syncMoviesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(onProgress, "Retrying Movies...")
                val api = XtreamProvider(
                    providerId = provider.id,
                    api = xtreamApiService,
                    serverUrl = provider.serverUrl,
                    username = provider.username,
                    password = provider.password
                )
                val cats = retryTransient { api.getVodCategories().getOrThrow("VOD categories") }
                categoryDao.replaceAll(provider.id, "MOVIE", cats.map { it.toEntity(provider.id) })

                val movies = retryTransient { api.getVodStreams().getOrThrow("VOD streams") }
                movieDao.replaceAll(provider.id, movies.map { it.toEntity() })

                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(lastMovieSync = now, movieCount = movies.size)
                syncMetadataRepository.updateMetadata(metadata)
            }
            ProviderType.M3U -> {
                progress(onProgress, "Retrying Movies...")
                val stats = withContext(Dispatchers.IO) {
                    importM3uPlaylist(provider, onProgress, includeLive = false, includeMovies = true)
                }
                if (stats.movieCount == 0) {
                    throw IllegalStateException("Playlist contains no movie entries")
                }
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(lastMovieSync = now, movieCount = stats.movieCount)
                syncMetadataRepository.updateMetadata(metadata)
            }
        }
    }

    private suspend fun syncSeriesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        if (provider.type != ProviderType.XTREAM_CODES) {
            throw IllegalStateException("Series retry is available only for Xtream providers")
        }
        progress(onProgress, "Retrying Series...")
        val api = XtreamProvider(
            providerId = provider.id,
            api = xtreamApiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password
        )

        val cats = retryTransient { api.getSeriesCategories().getOrThrow("Series categories") }
        categoryDao.replaceAll(provider.id, "SERIES", cats.map { it.toEntity(provider.id) })

        val seriesList = retryTransient { api.getSeriesList().getOrThrow("Series list") }
        seriesDao.replaceAll(provider.id, seriesList.map { it.toEntity() })

        val now = System.currentTimeMillis()
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(lastSeriesSync = now, seriesCount = seriesList.size)
        syncMetadataRepository.updateMetadata(metadata)
    }

    private suspend fun importM3uPlaylist(
        provider: Provider,
        onProgress: ((String) -> Unit)?,
        includeLive: Boolean = true,
        includeMovies: Boolean = true,
        batchSize: Int = 1000
    ): M3uImportStats {
        UrlSecurityPolicy.validatePlaylistSourceUrl(provider.m3uUrl.ifBlank { provider.serverUrl })?.let { message ->
            throw IllegalStateException(message)
        }
        progress(onProgress, "Downloading Playlist...")
        val existingChannelIds = if (includeLive) channelDao.getIdMappings(provider.id).associate { it.remoteId to it.id } else emptyMap()
        val existingMovieIds = if (includeMovies) movieDao.getIdMappings(provider.id).associate { it.remoteId to it.id } else emptyMap()
        val liveCategories = CategoryAccumulator(provider.id, ContentType.LIVE, 1L)
        val movieCategories = CategoryAccumulator(provider.id, ContentType.MOVIE, 10_000L)
        val channelBatch = ArrayList<ChannelEntity>(batchSize)
        val movieBatch = ArrayList<MovieEntity>(batchSize)
        val seenLiveStreamIds = if (includeLive) mutableSetOf<Long>() else null
        val seenMovieStreamIds = if (includeMovies) mutableSetOf<Long>() else null
        var header = M3uParser.M3uHeader()
        var liveCount = 0
        var movieCount = 0
        var parsedCount = 0
        var nextMilestone = PROGRESS_INTERVAL
        val warnings = mutableListOf<String>()
        var insecureStreamCount = 0

        openPlaylistStream(provider) { streamed ->
            progress(onProgress, "Parsing Playlist...")
            maybeDecompressPlaylist(streamed).use { input ->
                m3uParser.parseStreaming(
                    inputStream = input,
                    onHeader = { parsedHeader ->
                        val secureEpgUrl = parsedHeader.tvgUrl?.takeIf { UrlSecurityPolicy.isSecureRemoteUrl(it) }
                        if (parsedHeader.tvgUrl != null && secureEpgUrl == null) {
                            warnings += "Ignored insecure EPG URL from playlist header."
                        }
                        header = parsedHeader.copy(tvgUrl = secureEpgUrl)
                    }
                ) { entry ->
                    parsedCount++
                    if (parsedCount >= nextMilestone) {
                        progress(onProgress, "Imported $parsedCount playlist entries...")
                        nextMilestone += PROGRESS_INTERVAL
                    }
                    if (!UrlSecurityPolicy.isAllowedStreamEntryUrl(entry.url)) {
                        insecureStreamCount++
                        return@parseStreaming
                    }

                    val safeLogoUrl = UrlSecurityPolicy.sanitizeImportedAssetUrl(entry.tvgLogo)
                    val safeCatchUpSource = UrlSecurityPolicy.sanitizeImportedAssetUrl(entry.catchUpSource)

                    if (isVodEntry(entry)) {
                        if (!includeMovies) {
                            return@parseStreaming
                        }
                        val groupTitle = entry.groupTitle.ifBlank { "Uncategorized" }
                        val stableStreamId = stableId(provider.id, entry.tvgId, entry.url)
                        seenMovieStreamIds?.add(stableStreamId)
                        movieCategories.idFor(groupTitle)
                        movieBatch.add(
                            Movie(
                                id = existingMovieIds[stableStreamId] ?: 0L,
                                name = entry.name,
                                posterUrl = safeLogoUrl,
                                categoryId = movieCategories.idFor(groupTitle),
                                categoryName = groupTitle,
                                streamUrl = entry.url,
                                providerId = provider.id,
                                rating = entry.rating?.toFloatOrNull() ?: 0f,
                                year = entry.year,
                                genre = entry.genre,
                                isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle),
                                streamId = stableStreamId
                            ).toEntity()
                        )
                        movieCount++
                        if (movieBatch.size >= batchSize) {
                            flushMovieBatch(movieBatch)
                        }
                    } else {
                        if (!includeLive) {
                            return@parseStreaming
                        }
                        val groupTitle = entry.groupTitle.ifBlank { "Uncategorized" }
                        val stableStreamId = stableId(provider.id, entry.tvgId, entry.url)
                        seenLiveStreamIds?.add(stableStreamId)
                        liveCategories.idFor(groupTitle)
                        channelBatch.add(
                            Channel(
                                id = existingChannelIds[stableStreamId] ?: 0L,
                                name = entry.name,
                                logoUrl = safeLogoUrl,
                                groupTitle = groupTitle,
                                categoryId = liveCategories.idFor(groupTitle),
                                categoryName = groupTitle,
                                epgChannelId = entry.tvgId ?: entry.tvgName,
                                number = entry.tvgChno ?: 0,
                                streamUrl = entry.url,
                                catchUpSupported = entry.catchUp != null,
                                catchUpDays = entry.catchUpDays ?: 0,
                                catchUpSource = safeCatchUpSource,
                                providerId = provider.id,
                                isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle),
                                streamId = stableStreamId
                            ).toEntity()
                        )
                        liveCount++
                        if (channelBatch.size >= batchSize) {
                            flushChannelBatch(channelBatch)
                        }
                    }
                }
            }
        }

        flushChannelBatch(channelBatch)
        flushMovieBatch(movieBatch)
        if (includeLive) {
            categoryDao.replaceAll(provider.id, "LIVE", liveCategories.entities())
            pruneStaleChannels(existingChannelIds, seenLiveStreamIds.orEmpty())
        }
        if (includeMovies) {
            categoryDao.replaceAll(provider.id, "MOVIE", movieCategories.entities())
            pruneStaleMovies(existingMovieIds, seenMovieStreamIds.orEmpty())
            movieDao.restoreWatchProgress(provider.id)
        }

        if (insecureStreamCount > 0) {
            warnings += "Ignored $insecureStreamCount insecure playlist stream URL(s)."
        }

        return M3uImportStats(
            header = header,
            liveCount = liveCount,
            movieCount = movieCount,
            warnings = warnings
        )
    }

    private suspend fun openPlaylistStream(
        provider: Provider,
        block: suspend (StreamedPlaylist) -> Unit
    ) {
        val urlStr = provider.m3uUrl.ifBlank { provider.serverUrl }
        if (urlStr.startsWith("file:")) {
            java.io.File(java.net.URI(urlStr)).inputStream().use { input ->
                block(StreamedPlaylist(inputStream = input, sourceName = urlStr))
            }
            return
        }

        retryTransient {
            okHttpClient.newCall(Request.Builder().url(urlStr).build()).execute().use { response ->
                ensureSuccessfulPlaylistResponse(response)
                val body = response.body ?: throw IllegalStateException("Empty M3U response")
                body.byteStream().use { input ->
                    block(
                        StreamedPlaylist(
                            inputStream = input,
                            contentEncoding = response.header("Content-Encoding"),
                            sourceName = urlStr
                        )
                    )
                }
            }
        }
    }

    private fun ensureSuccessfulPlaylistResponse(response: Response) {
        if (response.isSuccessful) {
            return
        }
        if (response.code in 500..599 || response.code == 429) {
            throw IOException("Transient HTTP ${response.code}")
        }
        throw IllegalStateException("Failed to download M3U: HTTP ${response.code}")
    }

    private fun maybeDecompressPlaylist(streamed: StreamedPlaylist): InputStream {
        val buffered = if (streamed.inputStream is BufferedInputStream) {
            streamed.inputStream
        } else {
            BufferedInputStream(streamed.inputStream, 64 * 1024)
        }
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val gzipMagic = first == 0x1f && second == 0x8b
        val encodedGzip = streamed.contentEncoding?.contains("gzip", ignoreCase = true) == true
        val namedGzip = streamed.sourceName?.lowercase()?.endsWith(".gz") == true
        return if (gzipMagic || encodedGzip || namedGzip) {
            GZIPInputStream(buffered, 64 * 1024)
        } else {
            buffered
        }
    }

    private suspend fun flushChannelBatch(batch: MutableList<ChannelEntity>) {
        if (batch.isEmpty()) {
            return
        }
        channelDao.insertAll(batch.distinctBy { it.streamId })
        batch.clear()
    }

    private suspend fun flushMovieBatch(batch: MutableList<MovieEntity>) {
        if (batch.isEmpty()) {
            return
        }
        movieDao.insertAll(batch.distinctBy { it.streamId })
        batch.clear()
    }

    private suspend fun pruneStaleChannels(
        existingChannelIds: Map<Long, Long>,
        seenStreamIds: Set<Long>
    ) {
        val staleIds = existingChannelIds
            .filterKeys { it !in seenStreamIds }
            .values

        staleIds.chunked(900).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                channelDao.deleteByIds(chunk)
            }
        }
    }

    private suspend fun pruneStaleMovies(
        existingMovieIds: Map<Long, Long>,
        seenStreamIds: Set<Long>
    ) {
        val staleIds = existingMovieIds
            .filterKeys { it !in seenStreamIds }
            .values

        staleIds.chunked(900).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                movieDao.deleteByIds(chunk)
            }
        }
    }

    private fun stableId(providerId: Long, tvgId: String?, url: String): Long {
        return if (!tvgId.isNullOrBlank()) {
            hashToLong("$providerId:tvg:$tvgId")
        } else {
            hashToLong("$providerId:url:$url")
        }
    }

    private fun hashToLong(input: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (digest[i].toLong() and 0xFF)
        }
        return result and Long.MAX_VALUE
    }

    /** Delegates to M3uParser to avoid duplicate logic. */
    internal fun isVodEntry(entry: M3uParser.M3uEntry): Boolean = M3uParser.isVodEntry(entry)

    private fun progress(callback: ((String) -> Unit)?, message: String) {
        _syncState.value = SyncState.Syncing(message)
        callback?.invoke(message)
    }

    private fun redactUrlForLogs(url: String?): String {
        if (url.isNullOrBlank()) return "<empty>"
        return runCatching {
            val parsed = java.net.URI(url)
            val scheme = parsed.scheme ?: "http"
            val host = parsed.host ?: return@runCatching "<redacted>"
            val path = parsed.path.orEmpty()
            "$scheme://$host$path"
        }.getOrDefault("<redacted>")
    }

    private suspend fun <T> retryTransient(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 700L,
        block: suspend () -> T
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        var lastError: Throwable? = null

        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (t: Throwable) {
                lastError = t
                attempt++
                if (attempt >= maxAttempts || !isRetryable(t)) {
                    throw t
                }
                delay(delayMs)
                delayMs *= 2
            }
        }

        throw lastError ?: IllegalStateException("Unknown sync retry failure")
    }

    private fun isRetryable(error: Throwable): Boolean {
        if (error is IOException) return true

        val message = error.message.orEmpty().lowercase()
        return message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("unable to resolve host") ||
            message.contains("connection reset") ||
            message.contains("connect") ||
            message.contains("network")
    }

    companion object {
        private const val PROGRESS_INTERVAL = 5_000
    }

    private fun <T> com.streamvault.domain.model.Result<T>.getOrThrow(resourceName: String): T {
        return when (this) {
            is com.streamvault.domain.model.Result.Success -> data
            is com.streamvault.domain.model.Result.Error ->
                throw Exception("Failed to fetch $resourceName: $message")
            is com.streamvault.domain.model.Result.Loading ->
                throw Exception("Unexpected loading state for $resourceName")
        }
    }
}
