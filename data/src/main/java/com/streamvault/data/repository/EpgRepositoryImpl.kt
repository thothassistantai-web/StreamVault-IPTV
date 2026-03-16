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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgRepositoryImpl @Inject constructor(
    private val programDao: ProgramDao,
    private val xmltvParser: XmltvParser,
    private val okHttpClient: OkHttpClient,
    private val transactionRunner: DatabaseTransactionRunner
) : EpgRepository {

    companion object {
        private const val MAX_EPG_SIZE_BYTES = 200L * 1_048_576 // 200 MB
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
        if (channelIds.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptyMap())
        }
        return programDao.getForChannels(providerId, channelIds, startTime, endTime)
            .map { entities ->
                entities.map { it.toDomain() }
                    .groupBy { it.channelId }
            }
    }

    override fun getNowPlaying(providerId: Long, channelId: String): Flow<Program?> =
        programDao.getNowPlaying(providerId, channelId, System.currentTimeMillis())
            .map { it?.toDomain() }

    override fun getNowPlayingForChannels(providerId: Long, channelIds: List<String>): Flow<Map<String, Program?>> =
        programDao.getNowPlayingForChannels(providerId, channelIds, System.currentTimeMillis())
            .map { entities -> 
                val grouped = entities.map { it.toDomain() }.groupBy { it.channelId }
                channelIds.associateWith { id -> grouped[id]?.firstOrNull() }
            }

    override fun getNowAndNext(providerId: Long, channelId: String): Flow<Pair<Program?, Program?>> =
        programDao.getForChannel(
            providerId,
            channelId,
            System.currentTimeMillis() - 3600000, // 1 hour ago
            System.currentTimeMillis() + 7200000   // 2 hours from now
        ).map { entities ->
            val programs = entities.map { it.toDomain() }
            val now = System.currentTimeMillis()
            val current = programs.find { it.startTime <= now && it.endTime > now }
            val next = programs.find { it.startTime > now }
            Pair(current, next)
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
                    xmltvParser.parseStreaming(inputStream) { program ->
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
}
