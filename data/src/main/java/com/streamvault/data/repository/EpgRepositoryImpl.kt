package com.streamvault.data.repository

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.entity.ProgramEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.util.rankSearchResults
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import com.streamvault.data.remote.NetworkTimeoutConfig
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepositoryImpl @Inject constructor(
    private val programDao: ProgramDao,
    private val xmltvParser: XmltvParser,
    private val okHttpClient: OkHttpClient,
    private val transactionRunner: DatabaseTransactionRunner,
    private val epgSourceRepository: EpgSourceRepository,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : EpgRepository {

    private val providerRefreshMutexes = ConcurrentHashMap<Long, Mutex>()

    private val epgHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(NetworkTimeoutConfig.EPG_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val MAX_EPG_SIZE_BYTES = NetworkTimeoutConfig.EPG_MAX_SIZE_BYTES
        private const val EPG_PROGRAM_BATCH_SIZE = 500
        private const val NOW_AND_NEXT_LOOKBACK_MS = 60L * 60L * 1000L
        private const val NOW_AND_NEXT_LOOKAHEAD_MS = 2L * 60L * 60L * 1000L
        private const val NOW_AND_NEXT_REFRESH_INTERVAL_MS = 60L * 1000L

        private fun String.escapeSqlLike(escape: Char = '\\'): String =
            this.replace("$escape", "$escape$escape")
                .replace("%", "$escape%")
                .replace("_", "${escape}_")
    }

    override fun getProgramsForChannel(
        providerId: Long,
        channelId: String,
        startTime: Long,
        endTime: Long
    ): Flow<List<Program>> =
        programDao.getForChannel(providerId, channelId, startTime, endTime)
            .map { entities -> entities.map { it.toDomain() } }

    override fun getProgramsForChannels(
        providerId: Long,
        channelIds: List<String>,
        startTime: Long,
        endTime: Long
    ): Flow<Map<String, List<Program>>> {
        if (channelIds.isEmpty()) return flowOf(emptyMap())
        val chunks = channelIds.chunked(500)
        if (chunks.size == 1) {
            return programDao.getForChannels(providerId, channelIds, startTime, endTime)
                .map { entities -> entities.map { it.toDomain() }.groupBy { it.channelId } }
        }
        return flow {
            emit(getProgramsForChannelsSnapshot(providerId, channelIds, startTime, endTime))
        }
    }

    override suspend fun getProgramsForChannelsSnapshot(
        providerId: Long,
        channelIds: List<String>,
        startTime: Long,
        endTime: Long
    ): Map<String, List<Program>> {
        if (channelIds.isEmpty()) return emptyMap()

        val entities = if (channelIds.size <= 500) {
            programDao.getForChannelsSync(providerId, channelIds, startTime, endTime)
        } else {
            channelIds.chunked(500).flatMap { chunk ->
                programDao.getForChannelsSync(providerId, chunk, startTime, endTime)
            }
        }

        return entities
            .map { it.toDomain() }
            .groupBy { it.channelId }
    }

    override fun getProgramsByCategory(
        providerId: Long,
        categoryId: Long,
        startTime: Long,
        endTime: Long
    ): Flow<List<Program>> =
        programDao.getForCategory(providerId, categoryId, startTime, endTime)
            .map { entities -> entities.map { it.toDomain() } }

    override fun searchPrograms(
        providerId: Long,
        query: String,
        startTime: Long,
        endTime: Long,
        categoryId: Long?,
        limit: Int
    ): Flow<List<Program>> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) return flowOf(emptyList())
        val escaped = normalizedQuery.escapeSqlLike()
        return programDao.searchPrograms(
            providerId = providerId,
            queryPattern = "%$escaped%",
            startTime = startTime,
            endTime = endTime,
            categoryId = categoryId,
            limit = limit
        ).map { entities ->
            entities.map { it.toDomain() }
                .rankSearchResults(normalizedQuery) { it.title }
        }
    }

    override fun getNowPlaying(providerId: Long, channelId: String): Flow<Program?> =
        nowTicker.flatMapLatest { now ->
            programDao.getNowPlaying(providerId, channelId, now)
                .map { it?.toDomain() }
        }

    override fun getNowPlayingForChannels(providerId: Long, channelIds: List<String>): Flow<Map<String, Program?>> {
        if (channelIds.isEmpty()) return flowOf(emptyMap())
        val now = System.currentTimeMillis()
        val chunks = channelIds.chunked(500)
        if (chunks.size == 1) {
            return programDao.getNowPlayingForChannels(providerId, channelIds, now)
                .map { entities ->
                    val grouped = entities.map { it.toDomain() }.groupBy { it.channelId }
                    channelIds.associateWith { id -> grouped[id]?.firstOrNull() }
                }
        }
        return combine(chunks.map { chunk ->
            programDao.getNowPlayingForChannels(providerId, chunk, now)
        }) { arrays ->
            val grouped = arrays.flatMap { it.toList() }.map { it.toDomain() }.groupBy { it.channelId }
            channelIds.associateWith { id -> grouped[id]?.firstOrNull() }
        }
    }

    override suspend fun getNowPlayingForChannelsSnapshot(
        providerId: Long,
        channelIds: List<String>
    ): Map<String, Program?> {
        if (channelIds.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val entities = if (channelIds.size <= 500) {
            programDao.getNowPlayingForChannelsSync(providerId, channelIds, now)
        } else {
            channelIds.chunked(500).flatMap { chunk ->
                programDao.getNowPlayingForChannelsSync(providerId, chunk, now)
            }
        }

        val grouped = entities.map { it.toDomain() }.groupBy { it.channelId }
        return channelIds.associateWith { id -> grouped[id]?.firstOrNull() }
    }

    override fun getNowAndNext(providerId: Long, channelId: String): Flow<Pair<Program?, Program?>> =
        nowTicker.flatMapLatest { now ->
            programDao.getForChannel(
                providerId = providerId,
                channelId = channelId,
                startTime = now - NOW_AND_NEXT_LOOKBACK_MS,
                endTime = now + NOW_AND_NEXT_LOOKAHEAD_MS
            ).map { entities ->
                val programs = entities.map { it.toDomain() }
                val current = programs.find { it.startTime <= now && it.endTime > now }
                val nextStart = current?.endTime ?: now
                val next = programs.firstOrNull { it.startTime >= nextStart && it != current }
                current to next
            }
        }

    override suspend fun refreshEpg(providerId: Long, epgUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            providerRefreshMutex(providerId).withLock {
                val stagingProviderId = -providerId
                val batch = ArrayList<ProgramEntity>(EPG_PROGRAM_BATCH_SIZE)
                suspend fun flushBatch() {
                    if (batch.isEmpty()) return
                    val rows = batch.toList()
                    batch.clear()
                    transactionRunner.inTransaction {
                        programDao.insertAll(rows)
                    }
                    yield()
                }
                try {
                    val request = Request.Builder()
                        .url(epgUrl)
                        .header("Accept-Encoding", "identity")
                        .build()
                    val response = epgHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        return@withLock Result.error("Failed to download EPG: HTTP ${response.code}")
                    }

                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (contentLength > MAX_EPG_SIZE_BYTES) {
                        response.close()
                        return@withLock Result.error("EPG file too large (${contentLength / 1_048_576}MB)")
                    }

                    val body = response.body ?: return@withLock Result.error("Empty EPG response")
                    val contentEncoding = response.header("Content-Encoding")

                    transactionRunner.inTransaction {
                        programDao.deleteByProvider(stagingProviderId)
                    }

                    body.byteStream().use { rawStream ->
                        val alreadyDecompressed = contentEncoding?.contains("gzip", ignoreCase = true) == true
                        // Decompress Content-Encoding: gzip manually since we requested
                        // Accept-Encoding: identity to disable OkHttp's transparent decompression.
                        val httpStream: InputStream = if (alreadyDecompressed) {
                            GZIPInputStream(rawStream)
                        } else {
                            rawStream
                        }
                        // Cap total bytes read even when Content-Length is absent (chunked transfer)
                        val limitedStream = object : FilterInputStream(httpStream) {
                            private var bytesRead = 0L
                            override fun read(): Int {
                                if (bytesRead >= MAX_EPG_SIZE_BYTES) throw IOException("EPG response too large (>200 MB)")
                                return super.read().also { if (it >= 0) bytesRead++ }
                            }
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (bytesRead >= MAX_EPG_SIZE_BYTES) throw IOException("EPG response too large (>200 MB)")
                                return super.read(b, off, len).also { if (it > 0) bytesRead += it }
                            }
                        }
                        val xmlInput: InputStream = if (alreadyDecompressed) {
                            limitedStream
                        } else {
                            xmltvParser.maybeDecompressGzip(epgUrl, limitedStream)
                        }
                        xmlInput.use {
                            xmltvParser.parseStreaming(xmlInput) { program ->
                                batch.add(program.copy(providerId = stagingProviderId).toEntity())
                                if (batch.size >= EPG_PROGRAM_BATCH_SIZE) {
                                    flushBatch()
                                }
                            }
                        }
                    }

                    flushBatch()

                    transactionRunner.inTransaction {
                        programDao.deleteByProvider(providerId)
                        programDao.moveToProvider(stagingProviderId, providerId)
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    programDao.deleteByProvider(stagingProviderId)
                    if (e is IOException && e.message?.contains("too large", ignoreCase = true) == true) {
                        Result.error("EPG response exceeded 200 MB limit", e)
                    } else {
                        Result.error("Failed to refresh EPG: ${e.message}", e)
                    }
                }
            }
        }

    override suspend fun clearOldPrograms(beforeTime: Long) {
        programDao.deleteOld(beforeTime)
    }

    override fun onProviderDeleted(providerId: Long) {
        providerRefreshMutexes.remove(providerId)
    }

    override suspend fun getResolvedProgramsForChannels(
        providerId: Long,
        channelIds: List<Long>,
        startTime: Long,
        endTime: Long
    ): Map<String, List<Program>> =
        epgSourceRepository.getResolvedProgramsForChannels(providerId, channelIds, startTime, endTime)

    override suspend fun getResolvedProgramsForPlaybackChannel(
        providerId: Long,
        internalChannelId: Long,
        epgChannelId: String?,
        streamId: Long,
        startTime: Long,
        endTime: Long
    ): List<Program> {
        val normalizedChannelId = epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
        val lookupKey = normalizedChannelId ?: streamId.takeIf { it > 0L }?.toString()

        if (internalChannelId > 0L && lookupKey != null) {
            val resolvedPrograms = epgSourceRepository.getResolvedProgramsForChannels(
                providerId = providerId,
                channelIds = listOf(internalChannelId),
                startTime = startTime,
                endTime = endTime
            )[lookupKey].orEmpty()
            if (resolvedPrograms.isNotEmpty()) {
                return resolvedPrograms.sortedBy { it.startTime }
            }
        }

        if (normalizedChannelId != null) {
            return getProgramsForChannel(providerId, normalizedChannelId, startTime, endTime)
                .first()
                .sortedBy { it.startTime }
        }

        return emptyList()
    }

    private val nowTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(NOW_AND_NEXT_REFRESH_INTERVAL_MS)
        }
    }.shareIn(externalScope, SharingStarted.WhileSubscribed(), replay = 1)

    private fun providerRefreshMutex(providerId: Long): Mutex =
        providerRefreshMutexes.computeIfAbsent(providerId) { Mutex() }
}
