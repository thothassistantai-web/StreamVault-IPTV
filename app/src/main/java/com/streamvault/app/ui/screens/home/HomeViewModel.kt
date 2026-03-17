package com.streamvault.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.di.AuxiliaryPlayerEngine
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import com.streamvault.app.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.app.Application
import javax.inject.Provider as InjectProvider

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel @Inject constructor(
    application: Application,
    private val providerRepository: ProviderRepository,
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferencesRepository: PreferencesRepository,
    private val epgRepository: EpgRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val getCustomCategories: GetCustomCategories,
    private val parentalControlManager: ParentalControlManager,
    @AuxiliaryPlayerEngine
    private val playerEngineProvider: InjectProvider<PlayerEngine>
) : ViewModel() {
    private val appContext = application

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _localChannels = MutableStateFlow<List<Channel>>(emptyList())
    private val _preferredInitialCategoryId = MutableStateFlow<Long?>(null)
    private val _visibleChannelWindow = MutableStateFlow<Set<Long>>(emptySet())
    private val _epgProgramMap = MutableStateFlow<Map<String, Program>>(emptyMap())
    private var epgJob: Job? = null
    private var loadChannelsJob: Job? = null
    private var categoriesJob: Job? = null
    private var recentChannelsJob: Job? = null
    private var previewPlaybackJob: Job? = null
    private var previewErrorJob: Job? = null
    private var previewPlayerEngine: PlayerEngine? = null

    init {
        loadAllProviders()
        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .collectLatest { provider ->
                    _uiState.update { it.copy(provider = provider) }
                    loadCategoriesAndChannels(provider.id)
                    observeRecentChannels(provider.id)
                    preferencesRepository.setLastActiveProviderId(provider.id)
                }
        }

        // Observe channels, search query, and favorites to update UI
        viewModelScope.launch {
            combine(
                _localChannels,
                favoriteRepository.getFavorites(ContentType.LIVE),
                _epgProgramMap
            ) { channels, favorites, epgProgramMap ->
                Triple(channels, favorites, epgProgramMap)
            }.collectLatest { (channels, favorites, epgProgramMap) ->
                val favoriteIds = favorites.map { it.contentId }.toSet()
                val markedChannels = channels.map { channel ->
                    val program = channel.epgChannelId?.let { epgId -> epgProgramMap[epgId] }
                    if (favoriteIds.contains(channel.id)) {
                        channel.copy(isFavorite = true, currentProgram = program)
                    } else {
                        channel.copy(isFavorite = false, currentProgram = program)
                    }
                }

                _uiState.update { it.copy(filteredChannels = markedChannels, isLoading = false) }
                val previewChannelId = _uiState.value.previewChannelId
                if (previewChannelId != null && markedChannels.none { it.id == previewChannelId }) {
                    clearPreview()
                }
            }
        }

        viewModelScope.launch {
            combine(
                _uiState.map { it.provider?.id }.distinctUntilChanged(),
                _uiState.map { it.filteredChannels }.distinctUntilChanged(),
                _visibleChannelWindow.debounce(120)
            ) { providerId, channels, visibleIds ->
                Triple(providerId, channels, visibleIds)
            }.collectLatest { (providerId, channels, visibleIds) ->
                val resolvedProviderId = providerId ?: return@collectLatest
                if (channels.isEmpty()) {
                    epgJob?.cancel()
                    _epgProgramMap.value = emptyMap()
                    return@collectLatest
                }

                val candidateIds = if (visibleIds.isEmpty()) {
                    channels.take(12).map { it.id }.toSet()
                } else {
                    visibleIds
                }

                fetchEpgForChannels(
                    providerId = resolvedProviderId,
                    channels = channels.filter { it.id in candidateIds }
                )
            }
        }

        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collectLatest { level ->
                _uiState.update { it.copy(parentalControlLevel = level) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.liveTvChannelMode
                .map(LiveTvChannelMode::fromStorage)
                .distinctUntilChanged()
                .collectLatest { mode ->
                    _uiState.update { it.copy(liveTvChannelMode = mode) }
                    if (mode != LiveTvChannelMode.PRO) {
                        clearPreview()
                    }
                }
        }
    }

    private fun loadAllProviders() {
        viewModelScope.launch {
            providerRepository.getProviders().collect { providers ->
                _uiState.update { it.copy(allProviders = providers) }
            }
        }
    }

    fun switchProvider(providerId: Long) {
        viewModelScope.launch {
            providerRepository.setActiveProvider(providerId)
        }
    }

    private fun loadCategoriesAndChannels(providerId: Long) {
        categoriesJob?.cancel()
        categoriesJob = viewModelScope.launch {
            combine(
                channelRepository.getCategories(providerId),
                getCustomCategories(),
                playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 24),
                preferencesRepository.defaultCategoryId,
                preferencesRepository.getLastLiveCategoryId(providerId)
            ) { providerCats, customCats, history, defaultId, lastVisitedCategoryId ->
                val recentCategory = Category(
                    id = VirtualCategoryIds.RECENT,
                    name = "Recent",
                    type = ContentType.LIVE,
                    isVirtual = true,
                    count = history.toRecentLiveContentIds().size
                )

                val orderedCategories = buildList {
                    val favoritesCategory = customCats.find { it.id == VirtualCategoryIds.FAVORITES }
                    if (favoritesCategory != null) {
                        add(favoritesCategory)
                    }
                    add(recentCategory)
                    addAll(
                        customCats.filter { it.id != VirtualCategoryIds.FAVORITES }
                    )
                    addAll(providerCats)
                }

                CategorySelectionContext(
                    categories = orderedCategories,
                    defaultCategoryId = defaultId,
                    lastVisitedCategoryId = lastVisitedCategoryId
                )
            }.collect { selectionContext ->
                val categories = selectionContext.categories
                val defaultId = selectionContext.defaultCategoryId
                val preferredCategoryId = _preferredInitialCategoryId.value
                val lastVisitedCategory = selectionContext.lastVisitedCategoryId?.let { lastVisitedId ->
                    categories.find { it.id == lastVisitedId && it.id != VirtualCategoryIds.RECENT }
                }
                _uiState.update { it.copy(categories = categories, lastVisitedCategory = lastVisitedCategory) }

                val currentSelected = _uiState.value.selectedCategory
                val preferredCategory = preferredCategoryId?.let { categoryId ->
                    categories.find { it.id == categoryId }
                }

                if (preferredCategory != null && currentSelected?.id != preferredCategory.id) {
                    _preferredInitialCategoryId.value = null
                    selectCategory(preferredCategory)
                    return@collect
                }

                if (currentSelected == null && categories.isNotEmpty()) {
                    val defaultCat = defaultId?.let { id -> categories.find { it.id == id } }
                    val favoritesCat = categories.find { it.id == VirtualCategoryIds.FAVORITES }

                    if (defaultCat != null) selectCategory(defaultCat)
                    else if (favoritesCat != null) selectCategory(favoritesCat)
                    else selectCategory(categories.first())
                } else if (currentSelected != null) {
                    val reselectedCat = categories.find { it.id == currentSelected.id }

                    if (reselectedCat != null) {
                        if (reselectedCat != currentSelected) {
                            _uiState.update { it.copy(selectedCategory = reselectedCat) }
                        }
                        loadChannelsForCategory(reselectedCat)
                    } else {
                        val defaultCat = defaultId?.let { id -> categories.find { it.id == id } }
                        val favoritesCat = categories.find { it.id == VirtualCategoryIds.FAVORITES }

                        if (defaultCat != null) selectCategory(defaultCat)
                        else if (favoritesCat != null) selectCategory(favoritesCat)
                        else if (categories.isNotEmpty()) selectCategory(categories.first())
                    }
                }
            }
        }
    }

    fun selectCategory(category: Category) {
        if (_uiState.value.selectedCategory?.id == category.id) return
        parentalControlManager.clearUnlockedCategories(_uiState.value.provider?.id)
        clearPreview()
        _uiState.update { it.copy(selectedCategory = category, isLoading = true) }
        _preferredInitialCategoryId.value = null
        if (category.id != VirtualCategoryIds.RECENT) {
            val providerId = _uiState.value.provider?.id
            if (providerId != null) {
                viewModelScope.launch {
                    if (!preferencesRepository.isIncognitoMode.first()) {
                        preferencesRepository.setLastLiveCategoryId(providerId, category.id)
                    }
                }
            }
        }
        loadChannelsForCategory(category)
    }

    fun setPreferredInitialCategory(categoryId: Long?) {
        if (categoryId == null) return

        val matchingCategory = _uiState.value.categories.firstOrNull { it.id == categoryId }
        if (matchingCategory != null) {
            if (_uiState.value.selectedCategory?.id != matchingCategory.id) {
                selectCategory(matchingCategory)
            }
            return
        }

        _preferredInitialCategoryId.value = categoryId
    }

    private fun loadChannelsForCategory(category: Category) {
        val providerId = _uiState.value.provider?.id ?: return
        loadChannelsJob?.cancel()
        loadChannelsJob = viewModelScope.launch {
            try {
            val queryFlow = _uiState.map { it.channelSearchQuery }
                .debounce(150)
                .distinctUntilChanged()

            val channelsFlow = if (category.isVirtual) {
                val orderedFlow = if (category.id == VirtualCategoryIds.RECENT) {
                    playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 24)
                        .map { it.toRecentLiveContentIds() }
                        .flatMapLatest { ids -> loadChannelsByOrderedIds(ids) }
                } else if (category.id == VirtualCategoryIds.FAVORITES) {
                    favoriteRepository.getFavorites(ContentType.LIVE)
                        .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                        .flatMapLatest { ids -> loadChannelsByOrderedIds(ids) }
                } else {
                    val groupId = -category.id
                    favoriteRepository.getFavoritesByGroup(groupId)
                        .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                        .flatMapLatest { ids -> loadChannelsByOrderedIds(ids) }
                }

                combine(orderedFlow, queryFlow) { channels, query ->
                    if (query.isBlank()) {
                        channels
                    } else {
                        channels.filter { it.name.contains(query, ignoreCase = true) }
                    }
                }
            } else {
                queryFlow.flatMapLatest { query ->
                    if (query.isBlank()) {
                        channelRepository.getChannelsByCategory(providerId, category.id)
                    } else {
                        channelRepository.searchChannelsByCategory(providerId, category.id, query)
                    }
                }
            }

            channelsFlow.collect { channels ->
                val numberedChannels = channels.mapIndexed { index, channel ->
                    channel.copy(number = index + 1)
                }
                _uiState.update { it.copy(hasChannels = numberedChannels.isNotEmpty(), isLoading = false, errorMessage = null) }
                _localChannels.value = numberedChannels
            }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = appContext.getString(R.string.home_error_load_failed)
                    )
                }
            }
        }
    }

    fun updateCategorySearchQuery(query: String) {
        _uiState.update { it.copy(categorySearchQuery = query) }
    }

    fun updateChannelSearchQuery(query: String) {
        _uiState.update { it.copy(channelSearchQuery = query) }
    }

    fun previewChannel(channel: Channel) {
        if (_uiState.value.liveTvChannelMode != LiveTvChannelMode.PRO) return
        if (_uiState.value.previewChannelId == channel.id && _uiState.value.previewPlayerEngine != null) return

        val engine = previewPlayerEngine ?: playerEngineProvider.get().also { previewPlayerEngine = it }
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()

        _uiState.update {
            it.copy(
                previewChannelId = channel.id,
                previewPlayerEngine = engine,
                isPreviewLoading = true,
                previewErrorMessage = null
            )
        }

        previewPlaybackJob = viewModelScope.launch {
            engine.playbackState.collectLatest { playbackState ->
                _uiState.update { state ->
                    state.copy(
                        isPreviewLoading = playbackState == PlaybackState.IDLE || playbackState == PlaybackState.BUFFERING,
                        previewErrorMessage = when {
                            playbackState == PlaybackState.ERROR && state.previewErrorMessage.isNullOrBlank() ->
                                appContext.getString(R.string.live_preview_failed)
                            playbackState != PlaybackState.ERROR -> null
                            else -> state.previewErrorMessage
                        }
                    )
                }
            }
        }

        previewErrorJob = viewModelScope.launch {
            engine.error.collectLatest { error ->
                if (error != null) {
                    _uiState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewErrorMessage = error.message.ifBlank { appContext.getString(R.string.live_preview_failed) }
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            when (val result = channelRepository.getStreamUrl(channel)) {
                is Result.Success -> {
                    engine.stop()
                    engine.prepare(StreamInfo(url = result.data))
                    engine.setVolume(0f)
                    engine.play()
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewErrorMessage = result.message
                        )
                    }
                }
                Result.Loading -> Unit
            }
        }
    }

    fun clearPreview() {
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        previewPlaybackJob = null
        previewErrorJob = null
        previewPlayerEngine?.stop()
        previewPlayerEngine?.release()
        previewPlayerEngine = null
        _uiState.update {
            it.copy(
                previewChannelId = null,
                previewPlayerEngine = null,
                isPreviewLoading = false,
                previewErrorMessage = null
            )
        }
    }

    private fun fetchEpgForChannels(providerId: Long, channels: List<Channel>) {
        epgJob?.cancel()
        val epgIds = channels.mapNotNull { it.epgChannelId }.distinct()

        epgJob = viewModelScope.launch {
            val freshProgramMap = if (epgIds.isNotEmpty()) {
                epgRepository.getNowPlayingForChannels(providerId, epgIds)
                    .firstOrNull()
                    ?.mapNotNull { (epgId, program) -> program?.let { epgId to it } }
                    ?.toMap()
                    ?: emptyMap()
            } else {
                emptyMap()
            }

            val channelEpgIds = channels.mapNotNull { it.epgChannelId }.toSet()
            _epgProgramMap.update { existing ->
                buildMap {
                    putAll(existing)
                    channelEpgIds.forEach { epgId ->
                        if (freshProgramMap.containsKey(epgId)) {
                            put(epgId, freshProgramMap.getValue(epgId))
                        } else {
                            remove(epgId)
                        }
                    }
                }
            }
        }
    }

    fun updateVisibleChannelWindow(channelIds: List<Long>, focusedChannelId: Long? = null) {
        val combinedIds = buildSet {
            addAll(channelIds)
            focusedChannelId?.let(::add)
        }
        if (_visibleChannelWindow.value != combinedIds) {
            _visibleChannelWindow.value = combinedIds
        }
    }

    fun addFavorite(channel: Channel) {
        viewModelScope.launch {
            favoriteRepository.addFavorite(
                contentId = channel.id,
                contentType = ContentType.LIVE,
                groupId = null
            )
            onDismissDialog()
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_added_to_favorites, channel.name)) }
        }
    }

    fun removeFavorite(channel: Channel) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(
                contentId = channel.id,
                contentType = ContentType.LIVE,
                groupId = null
            )
            onDismissDialog()
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_removed_from_favorites, channel.name)) }
        }
    }

    fun createCustomGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_name_invalid)) }
            return
        }
        if (trimmed.equals("Favorites", ignoreCase = true)) {
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_name_reserved)) }
            return
        }
        if (_uiState.value.categories.any { it.name.equals(trimmed, ignoreCase = true) }) {
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_name_duplicate, trimmed)) }
            return
        }

        viewModelScope.launch {
            favoriteRepository.createGroup(trimmed, contentType = ContentType.LIVE)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_created, trimmed)) }
        }
    }

    private fun observeRecentChannels(providerId: Long) {
        recentChannelsJob?.cancel()
        recentChannelsJob = viewModelScope.launch {
            playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 12)
                .map { it.toRecentLiveContentIds() }
                .flatMapLatest { ids -> loadChannelsByOrderedIds(ids) }
                .collect { channels ->
                    _uiState.update { it.copy(recentChannels = channels) }
                }
        }
    }

    fun requestRenameGroup(category: Category) {
        if (!category.isVirtual || category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) return
        _uiState.update {
            it.copy(
                selectedCategoryForOptions = null,
                showRenameGroupDialog = true,
                groupToRename = category,
                renameGroupError = null
            )
        }
    }

    fun cancelRenameGroup() {
        _uiState.update {
            it.copy(
                showRenameGroupDialog = false,
                groupToRename = null,
                renameGroupError = null
            )
        }
    }

    fun confirmRenameGroup(name: String) {
        val category = _uiState.value.groupToRename ?: return
        val trimmed = name.trim()
        when {
            trimmed.isBlank() -> {
                _uiState.update { it.copy(renameGroupError = appContext.getString(R.string.home_group_name_invalid)) }
                return
            }
            trimmed.equals("Favorites", ignoreCase = true) -> {
                _uiState.update { it.copy(renameGroupError = appContext.getString(R.string.home_group_name_reserved)) }
                return
            }
            _uiState.value.categories.any { existing ->
                existing.id != category.id && existing.name.equals(trimmed, ignoreCase = true)
            } -> {
                _uiState.update { it.copy(renameGroupError = appContext.getString(R.string.home_group_name_duplicate, trimmed)) }
                return
            }
        }

        viewModelScope.launch {
            favoriteRepository.renameGroup(-category.id, trimmed)
            _uiState.update {
                it.copy(
                    showRenameGroupDialog = false,
                    groupToRename = null,
                    renameGroupError = null,
                    userMessage = appContext.getString(R.string.home_group_renamed, trimmed)
                )
            }
        }
    }

    fun requestDeleteGroup(category: Category) {
        if (!category.isVirtual || category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) return
        _uiState.update {
            it.copy(
                selectedCategoryForOptions = null,
                showDeleteGroupDialog = true,
                groupToDelete = category
            )
        }
    }

    fun cancelDeleteGroup() {
        _uiState.update { it.copy(showDeleteGroupDialog = false, groupToDelete = null) }
    }

    fun confirmDeleteGroup() {
        val category = _uiState.value.groupToDelete ?: return
        viewModelScope.launch {
            favoriteRepository.deleteGroup(-category.id)
            _uiState.update {
                it.copy(
                    showDeleteGroupDialog = false,
                    groupToDelete = null,
                    userMessage = appContext.getString(R.string.home_group_deleted, category.name)
                )
            }
        }
    }

    fun addToGroup(channel: Channel, category: Category) {
        if (!category.isVirtual || category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) return
        viewModelScope.launch {
            val groupId = -category.id
            favoriteRepository.addFavorite(
                contentId = channel.id,
                contentType = ContentType.LIVE,
                groupId = groupId
            )
            onDismissDialog()
            _uiState.update { it.copy(userMessage = "Added ${channel.name} to ${category.name}") }
        }
    }

    fun removeFromGroup(channel: Channel, category: Category) {
        if (!category.isVirtual || category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) return
        viewModelScope.launch {
            val groupId = -category.id
            favoriteRepository.removeFavorite(
                contentId = channel.id,
                contentType = ContentType.LIVE,
                groupId = groupId
            )
            onDismissDialog()
            _uiState.update { it.copy(userMessage = "Removed ${channel.name} from ${category.name}") }
        }
    }

    fun moveChannel(channel: Channel, direction: Int) {
        val currentCategory = _uiState.value.selectedCategory ?: return
        if (!currentCategory.isVirtual || currentCategory.id == VirtualCategoryIds.RECENT) return

        val currentList = _localChannels.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == channel.id }
        if (index == -1) return

        val newIndex = index + direction
        if (newIndex < 0 || newIndex >= currentList.size) return

        java.util.Collections.swap(currentList, index, newIndex)
        _localChannels.value = currentList

        viewModelScope.launch {
            val groupId = if (currentCategory.id == VirtualCategoryIds.FAVORITES) null else -currentCategory.id

            val favoritesFlow = if (groupId == null) {
                favoriteRepository.getFavorites(ContentType.LIVE)
            } else {
                favoriteRepository.getFavoritesByGroup(groupId)
            }

            val favorites = favoritesFlow.first()
            val favoriteMap = favorites.associateBy { it.contentId }

            val reorderedFavorites = currentList.mapNotNull { ch ->
                favoriteMap[ch.id]
            }.mapIndexed { i, fav ->
                fav.copy(position = i)
            }

            favoriteRepository.reorderFavorites(reorderedFavorites)
        }
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun refreshData() {
        val provider = _uiState.value.provider ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            providerRepository.refreshProviderData(provider.id, force = true)
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onShowDialog(channel: Channel) {
        _uiState.update { it.copy(showDialog = true, selectedChannelForDialog = channel) }
        viewModelScope.launch {
            val memberships = favoriteRepository.getGroupMemberships(channel.id, ContentType.LIVE)
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun onDismissDialog() {
        _uiState.update { it.copy(showDialog = false, selectedChannelForDialog = null) }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun unlockCategory(category: Category) {
        val providerId = _uiState.value.provider?.id ?: return
        parentalControlManager.unlockCategory(providerId, category.id)
    }

    fun setDefaultCategory(category: Category) {
        viewModelScope.launch {
            preferencesRepository.setDefaultCategory(category.id)
            _uiState.update { it.copy(userMessage = "Set '${category.name}' as default") }
        }
    }

    fun toggleCategoryLock(category: Category) {
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            val newStatus = !category.isUserProtected
            categoryRepository.setCategoryProtection(providerId, category.id, category.type, newStatus)
            val msg = if (newStatus) "Locked '${category.name}'" else "Unlocked '${category.name}'"
            _uiState.update { it.copy(userMessage = msg) }
        }
    }

    fun showCategoryOptions(category: Category) {
        _uiState.update { it.copy(selectedCategoryForOptions = category) }
    }

    fun dismissCategoryOptions() {
        _uiState.update { it.copy(selectedCategoryForOptions = null) }
    }

    fun enterChannelReorderMode(category: Category) {
        dismissCategoryOptions()
        viewModelScope.launch {
            val reorderChannels = loadReorderChannels(category)
            _uiState.update {
                it.copy(
                    isChannelReorderMode = true,
                    reorderCategory = category,
                    filteredChannels = reorderChannels
                )
            }
        }
    }

    fun exitChannelReorderMode() {
        // Discard any unsaved sorting by restoring from the original local snapshot
        _uiState.update { it.copy(
            isChannelReorderMode = false, 
            reorderCategory = null,
            filteredChannels = _localChannels.value
        ) }
    }

    fun moveChannelUp(channel: Channel) {
        val state = _uiState.value
        val list = state.filteredChannels.toMutableList()
        val idx = list.indexOf(channel)
        if (idx > 0) {
            list.removeAt(idx)
            list.add(idx - 1, channel)
            _uiState.update { it.copy(filteredChannels = list) }
        }
    }

    fun moveChannelDown(channel: Channel) {
        val state = _uiState.value
        val list = state.filteredChannels.toMutableList()
        val idx = list.indexOf(channel)
        if (idx >= 0 && idx < list.size - 1) {
            list.removeAt(idx)
            list.add(idx + 1, channel)
            _uiState.update { it.copy(filteredChannels = list) }
        }
    }

    fun saveChannelReorder() {
        val state = _uiState.value
        val category = state.reorderCategory ?: return
        if (category.id == VirtualCategoryIds.RECENT) return
        val currentList = state.filteredChannels

        // Exit reorder mode immediately for responsive UI
        _uiState.update { it.copy(isChannelReorderMode = false, reorderCategory = null) }
        
        // Optimistically update local channels to match the new order before DB flow catches up
        _localChannels.value = currentList

        viewModelScope.launch {
            try {
                // Map the virtual category ID back to the Favorite Group ID
                val groupId = if (category.id == VirtualCategoryIds.FAVORITES) null else -category.id

                val favoritesFlow = if (groupId == null) {
                    favoriteRepository.getFavorites(ContentType.LIVE)
                } else {
                    favoriteRepository.getFavoritesByGroup(groupId)
                }

                // Get current favorites
                val favorites = favoritesFlow.first()
                val favoriteMap = favorites.associateBy { it.contentId }

                // Map the sorted Channel list back to Favorite entities with new positions
                val reorderedFavorites = currentList.mapNotNull { ch ->
                    favoriteMap[ch.id]
                }.mapIndexed { i, fav ->
                    fav.copy(position = i)
                }

                // Persist the new order in DB
                favoriteRepository.reorderFavorites(reorderedFavorites)

                _uiState.update { it.copy(userMessage = "Channel order saved") }
            } catch (e: Exception) {
                _uiState.update { it.copy(userMessage = "Failed to save channel order") }
            }
        }
    }

    private fun loadChannelsByOrderedIds(ids: List<Long>): Flow<List<Channel>> {
        if (ids.isEmpty()) return flowOf(emptyList())

        return channelRepository.getChannelsByIds(ids).map { unsorted ->
            val channelsById = unsorted.associateBy { it.id }
            ids.mapNotNull { channelsById[it] }
        }
    }

    private suspend fun loadReorderChannels(category: Category): List<Channel> {
        if (!category.isVirtual || category.id == VirtualCategoryIds.RECENT) return emptyList()

        val groupId = if (category.id == VirtualCategoryIds.FAVORITES) null else -category.id
        val favorites = if (groupId == null) {
            favoriteRepository.getFavorites(ContentType.LIVE).first()
        } else {
            favoriteRepository.getFavoritesByGroup(groupId).first()
        }

        val orderedIds = favorites
            .sortedBy { it.position }
            .map { it.contentId }

        return loadChannelsByOrderedIds(orderedIds).first()
    }

    private fun List<PlaybackHistory>.toRecentLiveContentIds(): List<Long> =
        asSequence()
            .filter { it.contentType == ContentType.LIVE }
            .sortedByDescending { it.lastWatchedAt }
            .distinctBy { it.contentId }
            .map { it.contentId }
            .toList()

    override fun onCleared() {
        epgJob?.cancel()
        loadChannelsJob?.cancel()
        categoriesJob?.cancel()
        recentChannelsJob?.cancel()
        clearPreview()
        super.onCleared()
    }
}

data class HomeUiState(
    val provider: Provider? = null,
    val allProviders: List<Provider> = emptyList(),
    val categories: List<Category> = emptyList(),
    val recentChannels: List<Channel> = emptyList(),
    val lastVisitedCategory: Category? = null,
    val selectedCategory: Category? = null,
    val filteredChannels: List<Channel> = emptyList(),
    val hasChannels: Boolean = false,
    val isLoading: Boolean = true,
    val categorySearchQuery: String = "",
    val channelSearchQuery: String = "",
    val showDialog: Boolean = false,
    val selectedChannelForDialog: Channel? = null,
    val dialogGroupMemberships: List<Long> = emptyList(),
    val userMessage: String? = null,
    val showRenameGroupDialog: Boolean = false,
    val groupToRename: Category? = null,
    val renameGroupError: String? = null,
    val showDeleteGroupDialog: Boolean = false,
    val groupToDelete: Category? = null,
    val parentalControlLevel: Int = 0,
    val selectedCategoryForOptions: Category? = null,
    val isChannelReorderMode: Boolean = false,
    val reorderCategory: Category? = null,
    val liveTvChannelMode: LiveTvChannelMode = LiveTvChannelMode.COMFORTABLE,
    val previewChannelId: Long? = null,
    val previewPlayerEngine: PlayerEngine? = null,
    val isPreviewLoading: Boolean = false,
    val previewErrorMessage: String? = null,
    val errorMessage: String? = null
)

private data class CategorySelectionContext(
    val categories: List<Category>,
    val defaultCategoryId: Long?,
    val lastVisitedCategoryId: Long?
)
