package com.streamvault.app.ui.screens.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSetupViewModelTest {

    private val providerRepository: ProviderRepository = mock()
    private val combinedM3uRepository: CombinedM3uRepository = mock()
    private val validateAndAddProvider: ValidateAndAddProvider = mock()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(providerRepository.getProviders()).thenReturn(flowOf(emptyList()))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(flowOf(null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `adding m3u while combined source is active prepares attach prompt with names`() = runTest {
        val createdProvider = Provider(
            id = 7L,
            name = "Playlist 7",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            m3uUrl = "https://example.com/list.m3u"
        )
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(44L))
        )
        whenever(combinedM3uRepository.getProfile(44L)).thenReturn(
            CombinedM3uProfile(id = 44L, name = "Weekend Set")
        )
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Success(createdProvider)
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider
        )

        viewModel.addM3u("https://example.com/list.m3u", "Playlist 7")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isEqualTo(44L)
        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileName).isEqualTo("Weekend Set")
        assertThat(viewModel.uiState.value.createdProviderName).isEqualTo("Playlist 7")
        assertThat(viewModel.uiState.value.loginSuccess).isFalse()
    }

    @Test
    fun `attach created provider to combined keeps combined source active`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider
        )

        val seededState = viewModel.uiState.value.copy(
            createdProviderId = 12L,
            pendingCombinedAttachProfileId = 99L
        )
        val field = ProviderSetupViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ProviderSetupState>
        stateFlow.value = seededState

        viewModel.attachCreatedProviderToCombined()
        advanceUntilIdle()

        verify(combinedM3uRepository).addProvider(99L, 12L)
        verify(combinedM3uRepository).setActiveLiveSource(eq(ActiveLiveSource.CombinedM3uSource(99L)))
        assertThat(viewModel.uiState.value.loginSuccess).isTrue()
        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isNull()
    }

    @Test
    fun `stalker source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.STALKER)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `xtream source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.XTREAM)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `m3u source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.M3U)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `source defaults do not override customized epg sync mode`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider
        )

        viewModel.updateEpgSyncMode(ProviderEpgSyncMode.SKIP)
        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.STALKER)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.SKIP)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isTrue()
    }
}
