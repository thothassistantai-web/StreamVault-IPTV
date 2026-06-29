package com.streamvault.app.ui.cache

import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.Provider
import javax.inject.Inject
import javax.inject.Singleton

data class CombinedProfilesSnapshot(
    val profiles: List<CombinedM3uProfile> = emptyList(),
    val availableM3uProviders: List<Provider> = emptyList(),
    val activeLiveSource: ActiveLiveSource? = null,
    val liveSourceOptions: List<ActiveLiveSourceOption> = emptyList(),
)

@Singleton
class CombinedProfilesCache @Inject constructor() {
    private var snapshot: CombinedProfilesSnapshot? = null

    fun get(): CombinedProfilesSnapshot? = snapshot

    fun putProfiles(profiles: List<CombinedM3uProfile>) {
        snapshot = snapshot?.copy(profiles = profiles) ?: CombinedProfilesSnapshot(profiles = profiles)
    }

    fun putAvailableM3uProviders(providers: List<Provider>) {
        snapshot = snapshot?.copy(availableM3uProviders = providers)
            ?: CombinedProfilesSnapshot(availableM3uProviders = providers)
    }

    fun putActiveLiveSource(activeLiveSource: ActiveLiveSource?) {
        snapshot = snapshot?.copy(activeLiveSource = activeLiveSource)
            ?: CombinedProfilesSnapshot(activeLiveSource = activeLiveSource)
    }

    fun putLiveSourceOptions(options: List<ActiveLiveSourceOption>) {
        snapshot = snapshot?.copy(liveSourceOptions = options)
            ?: CombinedProfilesSnapshot(liveSourceOptions = options)
    }

    fun clear() {
        snapshot = null
    }
}
