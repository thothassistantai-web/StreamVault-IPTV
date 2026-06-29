package com.streamvault.app.ui.screens.home

import com.streamvault.domain.model.ActiveLiveSource

internal fun liveResumeScopeKey(activeLiveSource: ActiveLiveSource?): String? = when (activeLiveSource) {
    is ActiveLiveSource.ProviderSource -> "provider_${activeLiveSource.providerId}"
    is ActiveLiveSource.CombinedM3uSource -> "combined_${activeLiveSource.profileId}"
    null -> null
}
