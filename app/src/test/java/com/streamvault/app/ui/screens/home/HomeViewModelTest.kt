package com.streamvault.app.ui.screens.home

import android.app.Application
import androidx.lifecycle.ViewModel
import com.streamvault.app.player.LivePreviewHandoffManager
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.ui.screens.multiview.MultiViewManager
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.*
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.UnlockParentalCategory
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider as InjectProvider
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val providerRepository: ProviderRepository = mock()
    private val combinedM3uRepository: CombinedM3uRepository = mock()
    private val channelRepository: ChannelRepository = mock()
    private val categoryRepository: CategoryRepository = mock()
    private val favoriteRepository: FavoriteRepository = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val epgRepository: EpgRepository = mock()
    private val playbackHistoryRepository: PlaybackHistoryRepository = mock()
    private val getCustomCategories: GetCustomCategories = mock()
    private val unlockParentalCategory: UnlockParentalCategory = mock()
    private val parentalControlManager: ParentalControlManager = mock()
    private val syncManager: SyncManager = mock()
    private val tvInputChannelSyncManager: TvInputChannelSyncManager = mock()
    private val multiViewManager = MultiViewManager()
    private val livePreviewHandoffManager: LivePreviewHandoffManager = mock()
    private val playerEngine: PlayerEngine = mock()
    private val playerEngineProvider: InjectProvider<PlayerEngine> = mock()
    private val application: Application = mock()
    private val createdViewModels = mutableListOf<HomeViewModel>()

    private lateinit var viewModel: HomeViewModel

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(application.getString(any())).thenReturn("test-message")

        // Mock default flows to prevent exceptions during init
        whenever(providerRepository.getProviders()).thenReturn(flowOf(emptyList()))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(flowOf(null))
        whenever(combinedM3uRepository.getActiveLiveSourceOptions()).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(favoriteRepository.getFavorites(any<Long>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(any<List<Long>>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.defaultCategoryId).thenReturn(flowOf(null))
        whenever(preferencesRepository.getLastLiveCategoryId(any())).thenReturn(flowOf(null))
        whenever(preferencesRepository.liveTvChannelMode).thenReturn(flowOf("COMPACT"))
        whenever(preferencesRepository.liveTvCategoryFilters).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.liveTvQuickFilterVisibility).thenReturn(flowOf(null))
        whenever(preferencesRepository.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferencesRepository.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(preferencesRepository.showLiveSourceSwitcher).thenReturn(flowOf(false))
        whenever(preferencesRepository.liveChannelNumberingMode).thenReturn(flowOf(ChannelNumberingMode.PROVIDER))
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        whenever(preferencesRepository.getHiddenCategoryIds(any(), any())).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(any(), any())).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(preferencesRepository.getPinnedCategoryIds(any(), any())).thenReturn(flowOf(emptySet()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(any<Long>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(any<List<Long>>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavoritesByGroup(any())).thenReturn(flowOf(emptyList()))
        whenever(parentalControlManager.unlockedCategoriesForProvider(any())).thenReturn(flowOf(emptySet()))
        whenever(syncManager.syncStateForProvider(any())).thenReturn(flowOf(SyncState.Idle))
        runBlocking {
            whenever(epgRepository.getResolvedProgramsForChannels(any(), any(), any(), any())).thenReturn(emptyMap())
            whenever(preferencesRepository.setLastActiveProviderId(any())).thenReturn(Unit)
        }
        whenever(playerEngineProvider.get()).thenReturn(playerEngine)

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        createdViewModels.asReversed().forEach(::clearViewModel)
        createdViewModels.clear()
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel =
        HomeViewModel(
            application = application,
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            channelRepository = channelRepository,
            categoryRepository = categoryRepository,
            favoriteRepository = favoriteRepository,
            preferencesRepository = preferencesRepository,
            epgRepository = epgRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            getCustomCategories = getCustomCategories,
            unlockParentalCategory = unlockParentalCategory,
            parentalControlManager = parentalControlManager,
            syncManager = syncManager,
            tvInputChannelSyncManager = tvInputChannelSyncManager,
            multiViewManager = multiViewManager,
            livePreviewHandoffManager = livePreviewHandoffManager,
            playerEngineProvider = playerEngineProvider
        ).also(createdViewModels::add)

    private fun clearViewModel(viewModel: HomeViewModel) {
        val clearMethod = ViewModel::class.java.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.name.startsWith("clear")
        } ?: error("Unable to find ViewModel clear method")
        clearMethod.isAccessible = true
        clearMethod.invoke(viewModel)
    }

    @Test
    fun `when switchProvider is called, it delegates to repository`() = runTest {
        viewModel.switchProvider(1L)
        runCurrent()
        verify(providerRepository).setActiveProvider(1L)
        verify(combinedM3uRepository).setActiveLiveSource(ActiveLiveSource.ProviderSource(1L))
    }

    @Test
    fun `initial state has empty categories and is loading`() = runTest {
        val state = viewModel.uiState.value
        assertThat(state.isLoading).isTrue()
        assertThat(state.categories).isEmpty()
        assertThat(state.filteredChannels).isEmpty()
    }

    @Test
    fun `updateCategorySearchQuery updates state`() = runTest {
        viewModel.updateCategorySearchQuery("News")
        assertThat(viewModel.uiState.value.categorySearchQuery).isEqualTo("News")
    }

    @Test
    fun `updateChannelSearchQuery updates state and triggers filtering`() = runTest {
        viewModel.updateChannelSearchQuery("CNN")
        assertThat(viewModel.uiState.value.channelSearchQuery).isEqualTo("CNN")
    }

    @Test
    fun `selectCategory sets selected category and triggers loading`() = runTest {
        val category = Category(id = 1L, name = "Sports", parentId = null)
        
        // Mock the repositories needed for loading channels
        whenever(channelRepository.getChannelsByCategoryPage(any(), any(), any())).thenReturn(flowOf(emptyList()))
        val provider = Provider(id = 1L, name = "Provider", type = com.streamvault.domain.model.ProviderType.M3U, serverUrl = "http://test")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectCategory(category)
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertThat(state.selectedCategory).isEqualTo(category)
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `recent live history becomes a virtual recent category`() = runTest {
        val provider = Provider(
            id = 9L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(
            flowOf(
                listOf(
                    Category(
                        id = VirtualCategoryIds.FAVORITES,
                        name = "Favorites",
                        isVirtual = true
                    )
                )
            )
        )
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(
            flowOf(
                listOf(
                    PlaybackHistory(
                        contentId = 21L,
                        contentType = ContentType.LIVE,
                        providerId = provider.id,
                        title = "News",
                        streamUrl = "http://stream"
                    )
                )
            )
        )
        whenever(channelRepository.getChannelsByIds(listOf(21L))).thenReturn(
            flowOf(
                listOf(
                    Channel(
                        id = 21L,
                        name = "News",
                        streamUrl = "http://stream",
                        providerId = provider.id
                    )
                )
            )
        )
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()

        assertThat(viewModel.uiState.value.categories.map { it.id }).contains(VirtualCategoryIds.RECENT)
        assertThat(viewModel.uiState.value.recentChannels.map { it.id }).containsExactly(21L)
    }

    @Test
    fun `recent category stays visible in live tv even when history is empty`() = runTest {
        val provider = Provider(
            id = 12L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(
            flowOf(
                listOf(
                    Category(
                        id = VirtualCategoryIds.FAVORITES,
                        name = "Favorites",
                        isVirtual = true
                    )
                )
            )
        )
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()

        val categories = viewModel.uiState.value.categories
        assertThat(categories.map { it.id }).containsExactly(
            VirtualCategoryIds.FAVORITES,
            VirtualCategoryIds.RECENT,
            ChannelRepository.ALL_CHANNELS_ID
        )
        assertThat(categories.first { it.id == VirtualCategoryIds.RECENT }.count).isEqualTo(0)
    }

    @Test
    fun `last visited live category is exposed for quick return`() = runTest {
        val provider = Provider(
            id = 14L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        val sportsCategory = Category(id = 5L, name = "Sports")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(sportsCategory)))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(
            flowOf(
                listOf(
                    Category(
                        id = VirtualCategoryIds.FAVORITES,
                        name = "Favorites",
                        isVirtual = true
                    )
                )
            )
        )
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.getLastLiveCategoryId(provider.id)).thenReturn(flowOf(sportsCategory.id))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()

        assertThat(viewModel.uiState.value.lastVisitedCategory?.id).isEqualTo(sportsCategory.id)
        assertThat(viewModel.uiState.value.lastVisitedCategory?.name).isEqualTo("Sports")
    }

    @Test
    fun `selecting a live category remembers it for the current provider`() = runTest {
        val provider = Provider(
            id = 20L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        val category = Category(id = 7L, name = "Kids")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(category)))
        whenever(channelRepository.getChannelsByCategoryPage(eq(provider.id), eq(category.id), any())).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()
        viewModel.selectCategory(category)
        advanceUntilIdle()

        verify(preferencesRepository).setLastLiveCategoryId(provider.id, category.id)
    }

    @Test
    fun `selecting recent does not overwrite remembered live group`() = runTest {
        val provider = Provider(
            id = 21L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        val recentCategory = Category(
            id = VirtualCategoryIds.RECENT,
            name = "Recent",
            type = ContentType.LIVE,
            isVirtual = true
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()
        viewModel.selectCategory(recentCategory)
        advanceUntilIdle()

        verify(preferencesRepository, never()).setLastLiveCategoryId(provider.id, recentCategory.id)
    }

    @Test
    fun `channel search delegates to repository for provider categories`() = runTest {
        val provider = Provider(
            id = 30L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://test"
        )
        val category = Category(id = 11L, name = "News")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(category)))
        whenever(channelRepository.getChannelsByCategoryPage(eq(provider.id), eq(category.id), any())).thenReturn(
            flowOf(listOf(Channel(id = 1L, name = "BBC News", providerId = provider.id, streamUrl = "https://stream")))
        )
        whenever(channelRepository.searchChannelsByCategoryPaged(eq(provider.id), eq(category.id), eq("bbc"), any())).thenReturn(
            flowOf(listOf(Channel(id = 1L, name = "BBC News", providerId = provider.id, streamUrl = "https://stream")))
        )
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectCategory(category)
        advanceUntilIdle()
        viewModel.updateChannelSearchQuery("bbc")
        advanceUntilIdle()

        verify(channelRepository).searchChannelsByCategoryPaged(eq(provider.id), eq(category.id), eq("bbc"), any())
    }

    @Test
    fun `hidden numbering mode keeps displayed channel numbers non-negative`() = runTest {
        val provider = Provider(
            id = 31L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://test"
        )
        val category = Category(id = 12L, name = "News")
        val channel = Channel(
            id = 1L,
            name = "BBC News",
            providerId = provider.id,
            streamUrl = "https://stream",
            number = 23
        )
        whenever(preferencesRepository.liveChannelNumberingMode).thenReturn(flowOf(ChannelNumberingMode.HIDDEN))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(category)))
        whenever(channelRepository.getChannelsByCategoryPage(eq(provider.id), eq(category.id), any())).thenReturn(flowOf(listOf(channel)))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectCategory(category)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.filteredChannels.map(Channel::number)).containsExactly(0)
    }
}
