package com.streamvault.data.sync

import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.SyncMetadata

enum class XtreamLiveSyncReason {
    INITIAL_ONBOARDING,
    FOREGROUND,
    MANUAL_SETTINGS,
    BACKGROUND_STALE
}

internal enum class EffectiveXtreamLiveSyncMethod {
    STREAM_ALL,
    CATEGORY_BY_CATEGORY
}

internal object XtreamLiveSyncPolicy {
    fun resolve(
        userMode: ProviderXtreamLiveSyncMode,
        runtimeProfile: CatalogSyncRuntimeProfile,
        syncReason: XtreamLiveSyncReason,
        metadata: SyncMetadata,
        now: Long,
        hiddenLiveCategoryIds: Set<Long>
    ): EffectiveXtreamLiveSyncMethod = when (userMode) {
        ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY -> EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        ProviderXtreamLiveSyncMode.STREAM_ALL -> EffectiveXtreamLiveSyncMethod.STREAM_ALL
        ProviderXtreamLiveSyncMode.AUTO -> resolveAuto(
            runtimeProfile = runtimeProfile,
            syncReason = syncReason,
            metadata = metadata,
            now = now,
            hiddenLiveCategoryIds = hiddenLiveCategoryIds
        )
    }

    private fun resolveAuto(
        runtimeProfile: CatalogSyncRuntimeProfile,
        syncReason: XtreamLiveSyncReason,
        metadata: SyncMetadata,
        now: Long,
        hiddenLiveCategoryIds: Set<Long>
    ): EffectiveXtreamLiveSyncMethod {
        if (hiddenLiveCategoryIds.isNotEmpty()) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (runtimeProfile.tier == DeviceSyncTier.LOW) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (runtimeProfile.snapshot.isCurrentlyLowOnMemory) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (metadata.liveAvoidFullUntil > now) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (metadata.liveSequentialFailuresRemembered) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (syncReason == XtreamLiveSyncReason.INITIAL_ONBOARDING && runtimeProfile.preferSegmentedLiveOnboarding) {
            return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        }
        return when (syncReason) {
            XtreamLiveSyncReason.BACKGROUND_STALE,
            XtreamLiveSyncReason.MANUAL_SETTINGS -> EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
            XtreamLiveSyncReason.INITIAL_ONBOARDING,
            XtreamLiveSyncReason.FOREGROUND -> EffectiveXtreamLiveSyncMethod.STREAM_ALL
        }
    }
}
