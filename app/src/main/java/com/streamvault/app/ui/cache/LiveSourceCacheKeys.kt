package com.streamvault.app.ui.cache

import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Provider

object LiveSourceCacheKeys {
    fun provider(providerId: Long): String = "provider_$providerId"

    fun combined(profileId: Long): String = "combined_$profileId"

    fun resolve(providerId: Long?, combinedProfileId: Long?): String? = when {
        combinedProfileId != null -> combined(combinedProfileId)
        providerId != null -> provider(providerId)
        else -> null
    }

    fun from(activeSource: ActiveLiveSource?, activeProvider: Provider?): String? = when (activeSource) {
        is ActiveLiveSource.ProviderSource -> provider(activeSource.providerId)
        is ActiveLiveSource.CombinedM3uSource -> combined(activeSource.profileId)
        null -> activeProvider?.id?.let(::provider)
    }
}
