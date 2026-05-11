package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProviderSyncWorkerTest {
    private val providerDao: ProviderDao = mock()
    private val channelDao: ChannelDao = mock()
    private val xtreamLiveOnboardingDao: XtreamLiveOnboardingDao = mock()
    private val syncManager: SyncManager = mock()

    @Test
    fun `xtream provider with incomplete onboarding state is tracked for initial live resume`() = runTest {
        val provider = ProviderEntity(
            id = 9L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user",
            isActive = false,
            status = ProviderStatus.PARTIAL
        )
        whenever(xtreamLiveOnboardingDao.getIncompleteByProvider(9L)).thenReturn(
            XtreamLiveOnboardingStateEntity(
                providerId = 9L,
                phase = "FAILED",
                updatedAt = 456L
            )
        )

        val result = shouldTrackInitialLiveOnboarding(provider, xtreamLiveOnboardingDao)

        assertThat(result).isTrue()
    }

    @Test
    fun `non xtream provider is not tracked for initial live resume`() = runTest {
        val provider = ProviderEntity(
            id = 5L,
            name = "Playlist",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/list.m3u",
            m3uUrl = "https://example.com/list.m3u",
            isActive = false,
            status = ProviderStatus.PARTIAL
        )

        val result = shouldTrackInitialLiveOnboarding(provider, xtreamLiveOnboardingDao)

        assertThat(result).isFalse()
    }

    @Test
    fun `targeted resume success activates provider and stamps sync time`() = runTest {
        val provider = ProviderEntity(
            id = 9L,
            name = "Playlist",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/list.m3u",
            m3uUrl = "https://example.com/list.m3u",
            isActive = false,
            status = ProviderStatus.PARTIAL,
            lastSyncedAt = 0L
        )
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))

        reconcileTargetedProviderStatus(
            providerDao = providerDao,
            channelDao = channelDao,
            syncManager = syncManager,
            provider = provider,
            result = Result.success(Unit),
            currentTimeMillis = 456L
        )

        val updatedProvider = argumentCaptor<ProviderEntity>()
        verify(providerDao).update(updatedProvider.capture())
        assertThat(updatedProvider.firstValue.isActive).isTrue()
        assertThat(updatedProvider.firstValue.status).isEqualTo(ProviderStatus.ACTIVE)
        assertThat(updatedProvider.firstValue.lastSyncedAt).isEqualTo(456L)
    }

    @Test
    fun `targeted xtream resume success without committed live channels stays inactive partial`() = runTest {
        val provider = ProviderEntity(
            id = 9L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user",
            isActive = false,
            status = ProviderStatus.PARTIAL,
            lastSyncedAt = 0L
        )
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))

        reconcileTargetedProviderStatus(
            providerDao = providerDao,
            channelDao = channelDao,
            syncManager = syncManager,
            provider = provider,
            result = Result.success(Unit),
            currentTimeMillis = 456L
        )

        val updatedProvider = argumentCaptor<ProviderEntity>()
        verify(providerDao).update(updatedProvider.capture())
        assertThat(updatedProvider.firstValue.isActive).isFalse()
        assertThat(updatedProvider.firstValue.status).isEqualTo(ProviderStatus.PARTIAL)
        assertThat(updatedProvider.firstValue.lastSyncedAt).isEqualTo(456L)
    }

    @Test
    fun `targeted resume failure marks non-partial provider inactive and error`() = runTest {
        val provider = ProviderEntity(
            id = 9L,
            name = "Playlist",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/list.m3u",
            m3uUrl = "https://example.com/list.m3u",
            isActive = true,
            status = ProviderStatus.ACTIVE
        )

        reconcileTargetedProviderStatus(
            providerDao = providerDao,
            channelDao = channelDao,
            syncManager = syncManager,
            provider = provider,
            result = Result.error("timeout")
        )

        val updatedProvider = argumentCaptor<ProviderEntity>()
        verify(providerDao).update(updatedProvider.capture())
        assertThat(updatedProvider.firstValue.isActive).isFalse()
        assertThat(updatedProvider.firstValue.status).isEqualTo(ProviderStatus.ERROR)
        assertThat(updatedProvider.firstValue.lastSyncedAt).isEqualTo(provider.lastSyncedAt)
        verify(syncManager, never()).currentSyncState(9L)
    }
}
