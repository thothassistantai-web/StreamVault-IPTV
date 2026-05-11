package com.streamvault.data.sync

import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first

internal suspend fun hasUsableLiveCatalogForActivation(
    providerId: Long,
    providerType: ProviderType,
    channelDao: ChannelDao
): Boolean {
    if (providerType != ProviderType.XTREAM_CODES) {
        return true
    }
    return channelDao.getCount(providerId).first() > 0
}