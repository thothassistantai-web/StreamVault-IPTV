package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    fun getChannels(providerId: Long): Flow<List<Channel>>
    fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>>
    fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>>
    fun getCategories(providerId: Long): Flow<List<Category>>
    fun searchChannels(providerId: Long, query: String): Flow<List<Channel>>
    suspend fun getChannel(channelId: Long): Channel?
    @Deprecated("Use getStreamInfo() for richer metadata", replaceWith = ReplaceWith("getStreamInfo(channel)"))
    suspend fun getStreamUrl(channel: Channel): Result<String>
    suspend fun getStreamInfo(channel: Channel): Result<StreamInfo> =
        getStreamUrl(channel).map { StreamInfo(it) }
    suspend fun refreshChannels(providerId: Long): Result<Unit>
    fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>>
    suspend fun incrementChannelErrorCount(channelId: Long)
    suspend fun resetChannelErrorCount(channelId: Long)

    companion object {
        const val ALL_CHANNELS_ID = -1_000_000L
    }
}
