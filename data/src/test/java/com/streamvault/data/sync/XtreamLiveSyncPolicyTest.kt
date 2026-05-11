package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.SyncMetadata
import org.junit.Test

class XtreamLiveSyncPolicyTest {
    @Test
    fun `auto uses category by category for low devices`() {
        val method = XtreamLiveSyncPolicy.resolve(
            userMode = ProviderXtreamLiveSyncMode.AUTO,
            runtimeProfile = runtimeProfile(DeviceSyncTier.LOW),
            syncReason = XtreamLiveSyncReason.INITIAL_ONBOARDING,
            metadata = SyncMetadata(providerId = 1L),
            now = 1_000L,
            hiddenLiveCategoryIds = emptySet()
        )

        assertThat(method).isEqualTo(EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY)
    }

    @Test
    fun `auto uses category by category for manual and background sync`() {
        val profile = runtimeProfile(DeviceSyncTier.HIGH)
        val metadata = SyncMetadata(providerId = 1L)

        assertThat(
            XtreamLiveSyncPolicy.resolve(
                userMode = ProviderXtreamLiveSyncMode.AUTO,
                runtimeProfile = profile,
                syncReason = XtreamLiveSyncReason.MANUAL_SETTINGS,
                metadata = metadata,
                now = 1_000L,
                hiddenLiveCategoryIds = emptySet()
            )
        ).isEqualTo(EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY)

        assertThat(
            XtreamLiveSyncPolicy.resolve(
                userMode = ProviderXtreamLiveSyncMode.AUTO,
                runtimeProfile = profile,
                syncReason = XtreamLiveSyncReason.BACKGROUND_STALE,
                metadata = metadata,
                now = 1_000L,
                hiddenLiveCategoryIds = emptySet()
            )
        ).isEqualTo(EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY)
    }

    @Test
    fun `forced modes override auto policy`() {
        val lowProfile = runtimeProfile(DeviceSyncTier.LOW)
        val metadata = SyncMetadata(providerId = 1L, liveAvoidFullUntil = 10_000L)

        assertThat(
            XtreamLiveSyncPolicy.resolve(
                userMode = ProviderXtreamLiveSyncMode.STREAM_ALL,
                runtimeProfile = lowProfile,
                syncReason = XtreamLiveSyncReason.BACKGROUND_STALE,
                metadata = metadata,
                now = 1_000L,
                hiddenLiveCategoryIds = setOf(42L)
            )
        ).isEqualTo(EffectiveXtreamLiveSyncMethod.STREAM_ALL)

        assertThat(
            XtreamLiveSyncPolicy.resolve(
                userMode = ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY,
                runtimeProfile = runtimeProfile(DeviceSyncTier.HIGH),
                syncReason = XtreamLiveSyncReason.INITIAL_ONBOARDING,
                metadata = SyncMetadata(providerId = 1L),
                now = 1_000L,
                hiddenLiveCategoryIds = emptySet()
            )
        ).isEqualTo(EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY)
    }

    @Test
    fun `auto allows stream all for healthy high foreground sync`() {
        val method = XtreamLiveSyncPolicy.resolve(
            userMode = ProviderXtreamLiveSyncMode.AUTO,
            runtimeProfile = runtimeProfile(DeviceSyncTier.HIGH),
            syncReason = XtreamLiveSyncReason.INITIAL_ONBOARDING,
            metadata = SyncMetadata(providerId = 1L),
            now = 1_000L,
            hiddenLiveCategoryIds = emptySet()
        )

        assertThat(method).isEqualTo(EffectiveXtreamLiveSyncMethod.STREAM_ALL)
    }

    @Test
    fun `auto uses category by category when hidden categories exist`() {
        val method = XtreamLiveSyncPolicy.resolve(
            userMode = ProviderXtreamLiveSyncMode.AUTO,
            runtimeProfile = runtimeProfile(DeviceSyncTier.HIGH),
            syncReason = XtreamLiveSyncReason.FOREGROUND,
            metadata = SyncMetadata(providerId = 1L),
            now = 1_000L,
            hiddenLiveCategoryIds = setOf(42L)
        )

        assertThat(method).isEqualTo(EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY)
    }

    @Test
    fun `auto uses category by category during avoid full cooldown`() {
        val method = XtreamLiveSyncPolicy.resolve(
            userMode = ProviderXtreamLiveSyncMode.AUTO,
            runtimeProfile = runtimeProfile(DeviceSyncTier.HIGH),
            syncReason = XtreamLiveSyncReason.FOREGROUND,
            metadata = SyncMetadata(providerId = 1L, liveAvoidFullUntil = 5_000L),
            now = 1_000L,
            hiddenLiveCategoryIds = emptySet()
        )

        assertThat(method).isEqualTo(EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY)
    }

    @Test
    fun `auto uses category by category when provider stress is remembered`() {
        val method = XtreamLiveSyncPolicy.resolve(
            userMode = ProviderXtreamLiveSyncMode.AUTO,
            runtimeProfile = runtimeProfile(DeviceSyncTier.HIGH),
            syncReason = XtreamLiveSyncReason.FOREGROUND,
            metadata = SyncMetadata(
                providerId = 1L,
                liveSequentialFailuresRemembered = true,
                liveHealthySyncStreak = 1
            ),
            now = 1_000L,
            hiddenLiveCategoryIds = emptySet()
        )

        assertThat(method).isEqualTo(EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY)
    }

    @Test
    fun `auto uses category by category for segmented onboarding preference`() {
        val method = XtreamLiveSyncPolicy.resolve(
            userMode = ProviderXtreamLiveSyncMode.AUTO,
            runtimeProfile = runtimeProfile(
                tier = DeviceSyncTier.MID,
                preferSegmentedLiveOnboarding = true
            ),
            syncReason = XtreamLiveSyncReason.INITIAL_ONBOARDING,
            metadata = SyncMetadata(providerId = 1L),
            now = 1_000L,
            hiddenLiveCategoryIds = emptySet()
        )

        assertThat(method).isEqualTo(EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY)
    }

    private fun runtimeProfile(
        tier: DeviceSyncTier,
        currentlyLowMemory: Boolean = false,
        preferSegmentedLiveOnboarding: Boolean = false
    ): CatalogSyncRuntimeProfile = CatalogSyncRuntimeProfile(
        tier = tier,
        stageBatchSize = when (tier) {
            DeviceSyncTier.LOW -> 100
            DeviceSyncTier.MID -> 300
            DeviceSyncTier.HIGH -> 500
        },
        maxCategoryConcurrency = when (tier) {
            DeviceSyncTier.LOW -> 1
            DeviceSyncTier.MID -> 2
            DeviceSyncTier.HIGH -> Int.MAX_VALUE
        },
        preferSegmentedLiveOnboarding = preferSegmentedLiveOnboarding,
        deferBackgroundWorkOnLowMemory = currentlyLowMemory,
        snapshot = SyncDeviceSnapshot(
            memoryClassMb = when (tier) {
                DeviceSyncTier.LOW -> 128
                DeviceSyncTier.MID -> 256
                DeviceSyncTier.HIGH -> 512
            },
            largeMemoryClassMb = null,
            isLowRamDevice = tier == DeviceSyncTier.LOW,
            isCurrentlyLowOnMemory = currentlyLowMemory,
            availableMemMb = 256L,
            maxHeapMb = 256L,
            isTelevision = true,
            manufacturer = "test",
            model = "test",
            sdkInt = 35
        )
    )
}
