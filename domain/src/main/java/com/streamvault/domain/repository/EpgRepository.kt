package com.streamvault.domain.repository

import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface EpgRepository {
    fun getProgramsForChannel(providerId: Long, channelId: String, startTime: Long, endTime: Long): Flow<List<Program>>
    fun getProgramsForChannels(providerId: Long, channelIds: List<String>, startTime: Long, endTime: Long): Flow<Map<String, List<Program>>>
    fun getNowPlaying(providerId: Long, channelId: String): Flow<Program?>
    fun getNowPlayingForChannels(providerId: Long, channelIds: List<String>): Flow<Map<String, Program?>>
    fun getNowAndNext(providerId: Long, channelId: String): Flow<Pair<Program?, Program?>>
    suspend fun refreshEpg(providerId: Long, epgUrl: String): Result<Unit>
    suspend fun clearOldPrograms(beforeTime: Long)
}
