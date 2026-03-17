package com.streamvault.data.repository

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.entity.ProgramEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepositoryImpl @Inject constructor(
    private val programDao: ProgramDao,
    private val xmltvParser: XmltvParser,
    private val okHttpClient: OkHttpClient,
    private val transactionRunner: DatabaseTransactionRunner
) : EpgRepository {

    companion object {
        private const val MAX_EPG_SIZE_BYTES = 200L * 1_048_576 // 200 MB
        private const val NOW_AND_NEXT_LOOKBACK_MS = 60L * 60L * 1000L
        private const val NOW_AND_NEXT_LOOKAHEAD_MS = 2L * 60L * 60L * 1000L
        private const val NOW_AND_NEXT_REFRESH_INTERVAL_MS = 60L * 1000L
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
        return combine(chunks.map { chunk ->
            programDao.getForChannels(providerId, chunk, startTime, endTime)
        }) { arrays ->
            arrays.flatMap { it.toList() }.map { it.toDomain() }.groupBy { it.channelId }
        }
    }

    override fun getNowPlaying(providerId: Long, channelId: String): Flow<Program?> =
        programDao.getNowPlaying(providerId, channelId, System.currentTimeMillis())
            .map { it?.toDomain() }

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

    override fun getNowAndNext(providerId: Long, channelId: String): Flow<Pair<Program?, Program?>> =
        nowTicker().flatMapLatest { now ->
            programDao.getForChannel(
                providerId = providerId,
                channelId = channelId,
                startTime = now - NOW_AND_NEXT_LOOKBACK_MS,
                endTime = now + NOW_AND_NEXT_LOOKAHEAD_MS
            ).map { entities ->
                val programs = entities.map { it.toDomain() }
                val current = programs.find { it.startTime <= now && it.endTime > now }
                val next = programs.find { it.startTime > now }
                current to next
            }
        }

    override suspend fun refreshEpg(providerId: Long, epgUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val stagingProviderId = -providerId
            val batch = ArrayList<ProgramEntity>(500)
            try {
                programDao.deleteByProvider(stagingProviderId)

                val request = Request.Builder().url(epgUrl).build()
                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.error("Failed to download EPG: HTTP ${response.code}")
                }

                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (contentLength > MAX_EPG_SIZE_BYTES) {
                    response.close()
                    return@withContext Result.error("EPG file too large (${contentLength / 1_048_576}MB)")
                }

                val body = response.body ?: return@withContext Result.error("Empty EPG response")

                body.byteStream().use { inputStream ->
                    // Cap total bytes read even when Content-Length is absent (chunked transfer)
                    val limitedStream = object : FilterInputStream(inputStream) {
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
                    xmltvParser.parseStreaming(limitedStream) { program ->
                        batch.add(program.copy(providerId = stagingProviderId).toEntity())
                        if (batch.size >= 500) {
                            programDao.insertAll(batch.toList())
                            batch.clear()
                        }
                    }
                }

                if (batch.isNotEmpty()) {
                    programDao.insertAll(batch.toList())
                    batch.clear()
                }

                transactionRunner.inTransaction {
                    programDao.deleteByProvider(providerId)
                    programDao.moveToProvider(stagingProviderId, providerId)
                }

                Result.success(Unit)
            } catch (e: Exception) {
                programDao.deleteByProvider(stagingProviderId)
                Result.error("Failed to refresh EPG: ${e.message}", e)
            }
        }

    override suspend fun clearOldPrograms(beforeTime: Long) {
        programDao.deleteOld(beforeTime)
    }

    private fun nowTicker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(NOW_AND_NEXT_REFRESH_INTERVAL_MS)
        }
    }
}
