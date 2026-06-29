package com.streamvault.app.ui.preview

import com.streamvault.app.player.LivePlaybackStreamCache
import com.streamvault.app.player.LivePlaybackStreamCacheKey
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.app.ui.screens.player.shouldPreloadAdjacentChannel
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository

internal fun livePlaybackStreamCacheKey(channel: Channel): LivePlaybackStreamCacheKey =
    LivePlaybackStreamCacheKey(
        providerId = channel.providerId,
        channelId = channel.id,
        streamUrl = channel.streamUrl,
    )

internal suspend fun resolvePreparedPreviewStreamInfo(
    channel: Channel,
    channelRepository: ChannelRepository,
    pluginManager: StreamVaultPluginManager,
    streamCache: LivePlaybackStreamCache,
): Result<StreamInfo> {
    val cacheKey = livePlaybackStreamCacheKey(channel)
    streamCache.get(cacheKey)?.let { return Result.Success(it) }

    return when (val base = channelRepository.getStreamInfo(channel)) {
        is Result.Success -> prepareAndCacheStreamInfo(
            baseStreamInfo = base.data,
            cacheKey = cacheKey,
            pluginManager = pluginManager,
            streamCache = streamCache,
        )
        is Result.Error -> base
        Result.Loading -> Result.Loading
    }
}

internal suspend fun warmPreviewStreamCache(
    channel: Channel,
    channelRepository: ChannelRepository,
    pluginManager: StreamVaultPluginManager,
    providerRepository: ProviderRepository,
    streamCache: LivePlaybackStreamCache,
) {
    if (channel.streamUrl.isBlank()) return

    if (pluginManager.isGatewayManagedUrl(channel.streamUrl)) {
        runCatching { pluginManager.preparePlaybackUrl(channel.streamUrl) }
        return
    }

    val provider = providerRepository.getProvider(channel.providerId)
    if (!shouldPreloadAdjacentChannel(
            streamUrl = channel.streamUrl,
            providerType = provider?.type,
            maxConnections = provider?.maxConnections ?: 1,
            preloadCoolingDown = false,
            isGatewayManaged = false,
        )
    ) {
        return
    }

    resolvePreparedPreviewStreamInfo(
        channel = channel,
        channelRepository = channelRepository,
        pluginManager = pluginManager,
        streamCache = streamCache,
    )
}

private suspend fun prepareAndCacheStreamInfo(
    baseStreamInfo: StreamInfo,
    cacheKey: LivePlaybackStreamCacheKey,
    pluginManager: StreamVaultPluginManager,
    streamCache: LivePlaybackStreamCache,
): Result<StreamInfo> = when (val prepared = pluginManager.preparePlaybackStreamInfo(baseStreamInfo)) {
    is Result.Success -> {
        streamCache.put(cacheKey, prepared.data)
        Result.Success(prepared.data)
    }
    is Result.Error -> prepared
    Result.Loading -> {
        streamCache.put(cacheKey, baseStreamInfo)
        Result.Success(baseStreamInfo)
    }
}

internal fun adjacentPreviewChannels(
    channels: List<Channel>,
    currentChannelId: Long,
): List<Channel> {
    val index = channels.indexOfFirst { it.id == currentChannelId }
    if (index < 0) return emptyList()
    return listOfNotNull(
        channels.getOrNull(index - 1),
        channels.getOrNull(index + 1),
    )
}
