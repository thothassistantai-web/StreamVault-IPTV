package com.streamvault.app.ui.screens.epg

import com.streamvault.app.ui.model.guideLookupKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.ui.model.applyProviderCategoryDisplayPreferences
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ChannelEpgMapping
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.data.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class EpgUiState(
    val currentProviderName: String? = null,
    val providerSourceLabel: String = "",
    val providerArchiveSummary: String = "",
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: Long = ChannelRepository.ALL_CHANNELS_ID,
    val programSearchQuery: String = "",
    val showScheduledOnly: Boolean = false,
    val selectedChannelMode: GuideChannelMode = GuideChannelMode.ALL,
    val selectedDensity: GuideDensity = GuideDensity.COMPACT,
    val parentalControlLevel: Int = 0,
    val showFavoritesOnly: Boolean = false,
    val favoriteChannelIds: Set<Long> = emptySet(),
    val channels: List<Channel> = emptyList(),
    val programsByChannel: Map<String, List<Program>> = emptyMap(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val totalChannelCount: Int = 0,
    val channelsWithSchedule: Int = 0,
    val failedScheduleCount: Int = 0,
    val lastUpdatedAt: Long? = null,
    val isGuideStale: Boolean = false,
    val guideAnchorTime: Long = System.currentTimeMillis(),
    val guideWindowStart: Long = System.currentTimeMillis() - EpgViewModel.LOOKBACK_MS,
    val guideWindowEnd: Long = System.currentTimeMillis() + EpgViewModel.LOOKAHEAD_MS
)

data class EpgOverrideUiState(
    val channel: Channel? = null,
    val currentMapping: ChannelEpgMapping? = null,
    val searchQuery: String = "",
    val candidates: List<EpgOverrideCandidate> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
)

enum class GuideChannelMode {
    ALL,
    ANCHORED,
    ARCHIVE_READY
}

enum class GuideDensity {
    COMPACT,
    COMFORTABLE,
    CINEMATIC
}

private data class GuideProgramsResult(
    val programsByChannel: Map<String, List<Program>>,
    val failedCount: Int
)

private data class GuideChannelSelection(
    val channels: List<Channel>,
    val favoriteChannelIds: Set<Long>
)

private data class GuideBaseRequest(
    val categories: List<Category>,
    val hiddenCategoryIds: Set<Long>,
    val resolvedCategoryId: Long,
    val parentalControlLevel: Int,
    val anchorTime: Long,
    val favoritesOnly: Boolean,
    val windowStart: Long,
    val windowEnd: Long
)

private data class GuideBaseSnapshot(
    val providerId: Long,
    val currentProviderName: String,
    val providerSourceLabel: String,
    val providerArchiveSummary: String,
    val categories: List<Category>,
    val selectedCategoryId: Long,
    val parentalControlLevel: Int,
    val showFavoritesOnly: Boolean,
    val favoriteChannelIds: Set<Long>,
    val allChannels: List<Channel>,
    val visibleChannels: List<Channel>,
    val baseProgramsByChannel: Map<String, List<Program>>,
    val failedScheduleCount: Int,
    val lastUpdatedAt: Long,
    val baseChannelsWithSchedule: Int,
    val baseGuideStale: Boolean,
    val guideAnchorTime: Long,
    val guideWindowStart: Long,
    val guideWindowEnd: Long
)

private data class GuideDisplaySnapshot(
    val channels: List<Channel>,
    val programsByChannel: Map<String, List<Program>>,
    val totalChannelCount: Int,
    val channelsWithSchedule: Int,
    val isGuideStale: Boolean
)

private data class GuideBaseComputation(
    val guideResult: GuideProgramsResult,
    val now: Long,
    val channelsWithSchedule: Int,
    val hasUpcomingData: Boolean
)

private data class GuideSelectionRequest(
    val requestedCategoryId: Long,
    val anchorTime: Long,
    val favoritesOnly: Boolean,
    val parentalControlLevel: Int,
    val unlockedCategoryIds: Set<Long>,
    val isStartupSelection: Boolean
)

@HiltViewModel
class EpgViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val epgSourceRepository: EpgSourceRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferencesRepository: PreferencesRepository,
    private val parentalControlManager: ParentalControlManager,
    private val getCustomCategories: GetCustomCategories
) : ViewModel() {

    companion object {
        const val MAX_CHANNELS = 60
        private const val MAX_XTREAM_GUIDE_FALLBACK_CHANNELS = 24
        const val LOOKBACK_MS = 60 * 60 * 1000L
        const val LOOKAHEAD_MS = 6 * 60 * 60 * 1000L
        const val HALF_HOUR_SHIFT_MS = 30 * 60 * 1000L
        const val WINDOW_SHIFT_MS = 3 * 60 * 60 * 1000L
        const val PAGE_SHIFT_MS = LOOKBACK_MS + LOOKAHEAD_MS
        const val DAY_SHIFT_MS = 24 * 60 * 60 * 1000L
        const val PRIME_TIME_HOUR = 20
        const val NO_ACTIVE_PROVIDER = "NO_ACTIVE_PROVIDER"
    }

    private val _uiState = MutableStateFlow(EpgUiState())
    val uiState: StateFlow<EpgUiState> = _uiState.asStateFlow()

    private val selectedCategoryId = MutableStateFlow(ChannelRepository.ALL_CHANNELS_ID)
    private val guideAnchorTime = MutableStateFlow(System.currentTimeMillis())
    private val showScheduledOnly = MutableStateFlow(false)
    private val selectedChannelMode = MutableStateFlow(GuideChannelMode.ALL)
    private val selectedDensity = MutableStateFlow(GuideDensity.COMPACT)
    private val showFavoritesOnly = MutableStateFlow(false)
    private val programSearchQuery = MutableStateFlow("")
    private val startupCategoryId = MutableStateFlow<Long?>(null)
    private val refreshNonce = MutableStateFlow(0)
    private val baseGuideSnapshot = MutableStateFlow<GuideBaseSnapshot?>(null)
    private val _overrideUiState = MutableStateFlow(EpgOverrideUiState())
    val overrideUiState: StateFlow<EpgOverrideUiState> = _overrideUiState.asStateFlow()
    private var overrideSearchJob: Job? = null
    private var combinedCategoriesById: Map<Long, CombinedCategory> = emptyMap()

    init {
        restoreGuidePreferences()
        observeGuideBase()
        observeGuidePresentation()
    }

    fun selectCategory(categoryId: Long) {
        startupCategoryId.value = null
        if (selectedCategoryId.value == categoryId) return
        baseGuideSnapshot.value?.providerId?.takeIf { it > 0L }?.let { providerId ->
            parentalControlManager.retainUnlockedCategory(
                providerId = providerId,
                categoryId = categoryId.takeIf { it > 0L && it != ChannelRepository.ALL_CHANNELS_ID }
            )
        }
        selectedCategoryId.value = categoryId
    }

    fun updateProgramSearchQuery(query: String) {
        programSearchQuery.value = query
    }

    fun clearProgramSearch() {
        if (programSearchQuery.value.isBlank()) return
        programSearchQuery.value = ""
    }

    suspend fun verifyPin(pin: String): Boolean =
        preferencesRepository.verifyParentalPin(pin)

    fun unlockCategory(categoryId: Long) {
        val providerId = baseGuideSnapshot.value?.providerId?.takeIf { it > 0L } ?: return
        parentalControlManager.unlockCategory(providerId, categoryId)
    }

    fun refresh() {
        refreshNonce.update { it + 1 }
    }

    fun openEpgOverride(channel: Channel) {
        _overrideUiState.value = EpgOverrideUiState(
            channel = channel,
            isLoading = true
        )
        loadEpgOverrideCandidates(channel = channel, query = "", refreshMapping = true)
    }

    fun dismissEpgOverride() {
        overrideSearchJob?.cancel()
        _overrideUiState.value = EpgOverrideUiState()
    }

    fun updateEpgOverrideSearch(query: String) {
        val channel = _overrideUiState.value.channel ?: return
        _overrideUiState.update {
            it.copy(
                searchQuery = query,
                isLoading = true,
                error = null
            )
        }
        loadEpgOverrideCandidates(channel = channel, query = query, refreshMapping = false)
    }

    fun applyEpgOverride(candidate: EpgOverrideCandidate) {
        val channel = _overrideUiState.value.channel ?: return
        viewModelScope.launch {
            _overrideUiState.update { it.copy(isSaving = true, error = null) }
            when (val result = epgSourceRepository.applyManualOverride(
                providerId = channel.providerId,
                channelId = channel.id,
                epgSourceId = candidate.epgSourceId,
                xmltvChannelId = candidate.xmltvChannelId
            )) {
                is com.streamvault.domain.model.Result.Error -> {
                    _overrideUiState.update { it.copy(isSaving = false, error = result.message) }
                }
                else -> {
                    dismissEpgOverride()
                    refresh()
                }
            }
        }
    }

    fun clearEpgOverride() {
        val channel = _overrideUiState.value.channel ?: return
        viewModelScope.launch {
            _overrideUiState.update { it.copy(isSaving = true, error = null) }
            when (val result = epgSourceRepository.clearManualOverride(channel.providerId, channel.id)) {
                is com.streamvault.domain.model.Result.Error -> {
                    _overrideUiState.update { it.copy(isSaving = false, error = result.message) }
                }
                else -> {
                    dismissEpgOverride()
                    refresh()
                }
            }
        }
    }

    fun jumpToNow() {
        updateGuideAnchorTime(System.currentTimeMillis())
    }

    fun jumpForwardHalfHour() {
        updateGuideAnchorTime(guideAnchorTime.value + HALF_HOUR_SHIFT_MS)
    }

    fun jumpBackwardHalfHour() {
        updateGuideAnchorTime((guideAnchorTime.value - HALF_HOUR_SHIFT_MS).coerceAtLeast(0L))
    }

    fun jumpForward() {
        updateGuideAnchorTime(guideAnchorTime.value + WINDOW_SHIFT_MS)
    }

    fun jumpBackward() {
        updateGuideAnchorTime((guideAnchorTime.value - WINDOW_SHIFT_MS).coerceAtLeast(0L))
    }

    fun pageBackward() {
        updateGuideAnchorTime((guideAnchorTime.value - PAGE_SHIFT_MS).coerceAtLeast(0L))
    }

    fun pageForward() {
        updateGuideAnchorTime(guideAnchorTime.value + PAGE_SHIFT_MS)
    }

    fun jumpToTomorrow() {
        updateGuideAnchorTime(System.currentTimeMillis() + DAY_SHIFT_MS)
    }

    fun jumpToPrimeTime() {
        val selectedDayStart = (guideAnchorTime.value / DAY_SHIFT_MS) * DAY_SHIFT_MS
        updateGuideAnchorTime(selectedDayStart + (PRIME_TIME_HOUR * 60 * 60 * 1000L))
    }

    fun jumpToPreviousDay() {
        updateGuideAnchorTime((guideAnchorTime.value - DAY_SHIFT_MS).coerceAtLeast(0L))
    }

    fun jumpToNextDay() {
        updateGuideAnchorTime(guideAnchorTime.value + DAY_SHIFT_MS)
    }

    fun jumpToDay(dayStartMillis: Long) {
        val currentTimeOfDay = guideAnchorTime.value
            .mod(DAY_SHIFT_MS)
            .let { if (it < 0L) it + DAY_SHIFT_MS else it }
        updateGuideAnchorTime(dayStartMillis + currentTimeOfDay)
    }

    fun toggleScheduledOnly() {
        val enabled = !showScheduledOnly.value
        showScheduledOnly.value = enabled
        viewModelScope.launch {
            preferencesRepository.setGuideScheduledOnly(enabled)
        }
    }

    fun selectChannelMode(mode: GuideChannelMode) {
        selectedChannelMode.value = mode
        viewModelScope.launch {
            preferencesRepository.setGuideChannelMode(mode.name)
        }
    }

    fun selectDensity(density: GuideDensity) {
        selectedDensity.value = density
        viewModelScope.launch {
            preferencesRepository.setGuideDensity(density.name)
        }
    }

    fun toggleFavoritesOnly() {
        val enabled = !showFavoritesOnly.value
        showFavoritesOnly.value = enabled
        viewModelScope.launch {
            preferencesRepository.setGuideFavoritesOnly(enabled)
        }
    }

    fun applyNavigationContext(
        categoryId: Long?,
        anchorTime: Long?,
        favoritesOnly: Boolean?
    ) {
        categoryId?.let { requested ->
            startupCategoryId.value = null
            selectedCategoryId.value = requested
        }
        anchorTime?.takeIf { it > 0L }?.let { requested ->
            guideAnchorTime.value = requested
        }
        favoritesOnly?.let { requested ->
            showFavoritesOnly.value = requested
        }
    }

    private fun observeGuideBase() {
        viewModelScope.launch {
            combine(
                combinedM3uRepository.getActiveLiveSource(),
                providerRepository.getActiveProvider()
            ) { activeSource, activeProvider ->
                Pair(activeSource ?: activeProvider?.id?.let { ActiveLiveSource.ProviderSource(it) }, activeProvider)
            }.distinctUntilChanged { old, new ->
                old.first == new.first && old.second?.id == new.second?.id
            }.collectLatest { (activeSource, activeProvider) ->
                if (activeSource == null && activeProvider == null) {
                    baseGuideSnapshot.value = null
                    _uiState.update {
                        it.copy(
                            currentProviderName = null,
                            providerSourceLabel = "",
                            providerArchiveSummary = "",
                            categories = emptyList(),
                            channels = emptyList(),
                            programsByChannel = emptyMap(),
                            isInitialLoading = false,
                            isRefreshing = false,
                            error = NO_ACTIVE_PROVIDER,
                            totalChannelCount = 0,
                            channelsWithSchedule = 0,
                            failedScheduleCount = 0,
                            lastUpdatedAt = null,
                            isGuideStale = false,
                            guideAnchorTime = System.currentTimeMillis(),
                            guideWindowStart = System.currentTimeMillis() - LOOKBACK_MS,
                            guideWindowEnd = System.currentTimeMillis() + LOOKAHEAD_MS
                        )
                    }
                    return@collectLatest
                }

                when (activeSource) {
                    is ActiveLiveSource.ProviderSource -> {
                        val provider = activeProvider?.takeIf { it.id == activeSource.providerId }
                            ?: providerRepository.getProvider(activeSource.providerId)
                            ?: return@collectLatest
                        observeSingleProviderGuide(provider)
                    }
                    is ActiveLiveSource.CombinedM3uSource -> {
                        observeCombinedGuide(activeSource.profileId)
                    }
                    null -> Unit
                }
            }
        }
    }

    private suspend fun observeSingleProviderGuide(provider: com.streamvault.domain.model.Provider) {
        combine(
            channelRepository.getCategories(provider.id),
            getCustomCategories(ContentType.LIVE),
            preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE),
            preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)
        ) { providerCategories, customCategories, hiddenCategoryIds, sortMode ->
            GuideCategoryData(
                providerCategories = providerCategories,
                customCategories = customCategories,
                hiddenCategoryIds = hiddenCategoryIds,
                sortMode = sortMode
            )
        }
            .combine(
                combine(
                    selectedCategoryId,
                    startupCategoryId,
                    guideAnchorTime,
                    showFavoritesOnly,
                    refreshNonce
                ) { requestedCategoryId, startupSelectionId, anchorTime, favoritesOnly, _ ->
                    GuideSelectionSeed(
                        requestedCategoryId = startupSelectionId ?: requestedCategoryId,
                        anchorTime = anchorTime,
                        favoritesOnly = favoritesOnly,
                        isStartupSelection = startupSelectionId != null
                    )
                }.combine(preferencesRepository.parentalControlLevel) { selection, parentalControlLevel ->
                    selection to parentalControlLevel
                }.combine(parentalControlManager.unlockedCategoriesForProvider(provider.id)) { (selection, parentalControlLevel), unlockedCategoryIds ->
                    GuideSelectionRequest(
                        requestedCategoryId = selection.requestedCategoryId,
                        anchorTime = selection.anchorTime,
                        favoritesOnly = selection.favoritesOnly,
                        parentalControlLevel = parentalControlLevel,
                        unlockedCategoryIds = unlockedCategoryIds,
                        isStartupSelection = selection.isStartupSelection
                    )
                }
            ) { categoryData, selection ->
                val visibleProviderCategories = applyProviderCategoryDisplayPreferences(
                    categories = categoryData.providerCategories.filter { it.id != ChannelRepository.ALL_CHANNELS_ID },
                    hiddenCategoryIds = categoryData.hiddenCategoryIds,
                    sortMode = categoryData.sortMode
                )
                val orderedCategories = buildGuideCategoryList(
                    providerCategories = visibleProviderCategories,
                    customCategories = categoryData.customCategories
                )
                val resolvedCategoryId = resolveGuideCategorySelection(
                    requestedCategoryId = selection.requestedCategoryId,
                    categories = orderedCategories,
                    parentalControlLevel = selection.parentalControlLevel,
                    unlockedCategoryIds = selection.unlockedCategoryIds,
                    fallbackFromEmptyFavorites = selection.isStartupSelection
                )
                GuideBaseRequest(
                    categories = orderedCategories,
                    hiddenCategoryIds = categoryData.hiddenCategoryIds,
                    resolvedCategoryId = resolvedCategoryId,
                    parentalControlLevel = selection.parentalControlLevel,
                    anchorTime = selection.anchorTime,
                    favoritesOnly = selection.favoritesOnly,
                    windowStart = selection.anchorTime - LOOKBACK_MS,
                    windowEnd = selection.anchorTime + LOOKAHEAD_MS
                )
            }.collectLatest { request ->
                val categories = request.categories
                val hasVisibleGuide = _uiState.value.channels.isNotEmpty() || _uiState.value.programsByChannel.isNotEmpty()
                _uiState.update {
                    it.copy(
                        currentProviderName = provider.name,
                        providerSourceLabel = when (provider.type) {
                            com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "Xtream Codes"
                            com.streamvault.domain.model.ProviderType.M3U -> "M3U Playlist"
                        },
                        providerArchiveSummary = buildProviderArchiveSummary(provider),
                        categories = categories,
                        parentalControlLevel = request.parentalControlLevel,
                        showFavoritesOnly = request.favoritesOnly,
                        selectedCategoryId = request.resolvedCategoryId,
                        guideAnchorTime = request.anchorTime,
                        guideWindowStart = request.windowStart,
                        guideWindowEnd = request.windowEnd,
                        isInitialLoading = !hasVisibleGuide,
                        isRefreshing = hasVisibleGuide,
                        error = null
                    )
                }

                combine(loadGuideChannelsForProvider(provider.id, request), favoriteRepository.getFavorites(ContentType.LIVE)) { preferredChannels, favorites ->
                    val favoriteIds = favorites.map { it.contentId }.toSet()
                    GuideChannelSelection(
                        channels = preferredChannels.filterNot { channel -> channel.categoryId in request.hiddenCategoryIds },
                        favoriteChannelIds = favoriteIds
                    )
                }.collectLatest { channelSelection ->
                    publishGuideSnapshot(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceLabel = when (provider.type) {
                            com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "Xtream Codes"
                            com.streamvault.domain.model.ProviderType.M3U -> "M3U Playlist"
                        },
                        providerArchiveSummary = buildProviderArchiveSummary(provider),
                        categories = categories,
                        request = request,
                        channelSelection = channelSelection,
                        guideResult = loadGuidePrograms(
                            provider = provider,
                            providerId = provider.id,
                            channels = channelSelection.channels.take(MAX_CHANNELS),
                            categoryId = request.resolvedCategoryId,
                            favoritesOnly = request.favoritesOnly,
                            windowStart = request.windowStart,
                            windowEnd = request.windowEnd
                        )
                    )
                    finalizeStartupCategory(request.resolvedCategoryId)
                }
            }
    }

    private suspend fun observeCombinedGuide(profileId: Long) {
        combine(
            combinedM3uRepository.getCombinedCategories(profileId),
            getCustomCategories(ContentType.LIVE),
            combine(
                selectedCategoryId,
                startupCategoryId,
                guideAnchorTime,
                showFavoritesOnly,
                refreshNonce
            ) { requestedCategoryId, startupSelectionId, anchorTime, favoritesOnly, _ ->
                GuideSelectionSeed(
                    requestedCategoryId = startupSelectionId ?: requestedCategoryId,
                    anchorTime = anchorTime,
                    favoritesOnly = favoritesOnly,
                    isStartupSelection = startupSelectionId != null
                )
            }.combine(preferencesRepository.parentalControlLevel) { selection, parentalControlLevel ->
                selection to parentalControlLevel
            }
        ) { combinedCategories, customCategories, selection ->
            combinedCategoriesById = combinedCategories.associateBy { it.category.id }
            val categories = buildGuideCategoryList(
                providerCategories = combinedCategories.map { it.category },
                customCategories = customCategories
            )
            val resolvedCategoryId = resolveGuideCategorySelection(
                requestedCategoryId = selection.first.requestedCategoryId,
                categories = categories,
                parentalControlLevel = selection.second,
                unlockedCategoryIds = emptySet(),
                fallbackFromEmptyFavorites = selection.first.isStartupSelection
            )
            GuideBaseRequest(
                categories = categories,
                hiddenCategoryIds = emptySet(),
                resolvedCategoryId = resolvedCategoryId,
                parentalControlLevel = selection.second,
                anchorTime = selection.first.anchorTime,
                favoritesOnly = selection.first.favoritesOnly,
                windowStart = selection.first.anchorTime - LOOKBACK_MS,
                windowEnd = selection.first.anchorTime + LOOKAHEAD_MS
            )
        }.collectLatest { request ->
            val categories = request.categories
            val profile = combinedM3uRepository.getProfile(profileId)
            val profileName = profile?.name ?: "Combined M3U"
            val hasVisibleGuide = _uiState.value.channels.isNotEmpty() || _uiState.value.programsByChannel.isNotEmpty()
            _uiState.update {
                it.copy(
                    currentProviderName = profileName,
                    providerSourceLabel = "Combined M3U",
                    providerArchiveSummary = "Guide data is merged from each member playlist's own EPG sources.",
                    categories = categories,
                    parentalControlLevel = request.parentalControlLevel,
                    showFavoritesOnly = request.favoritesOnly,
                    selectedCategoryId = request.resolvedCategoryId,
                    guideAnchorTime = request.anchorTime,
                    guideWindowStart = request.windowStart,
                    guideWindowEnd = request.windowEnd,
                    isInitialLoading = !hasVisibleGuide,
                    isRefreshing = hasVisibleGuide,
                    error = null
                )
            }

            combine(
                combinedGuideChannels(profileId, request.resolvedCategoryId),
                favoriteRepository.getFavorites(ContentType.LIVE)
            ) { channels, favorites ->
                val favoriteIds = favorites.map { it.contentId }.toSet()
                val preferredChannels = if (request.favoritesOnly) channels.filter { it.id in favoriteIds } else channels
                GuideChannelSelection(
                    channels = preferredChannels,
                    favoriteChannelIds = favoriteIds
                )
            }.collectLatest { channelSelection ->
                publishGuideSnapshot(
                    providerId = 0L,
                    providerName = profileName,
                    providerSourceLabel = "Combined M3U",
                    providerArchiveSummary = "Guide data is merged from each member playlist's own EPG sources.",
                    categories = categories,
                    request = request,
                    channelSelection = channelSelection,
                    guideResult = loadCombinedGuidePrograms(
                        channels = channelSelection.channels.take(MAX_CHANNELS),
                        windowStart = request.windowStart,
                        windowEnd = request.windowEnd
                    )
                )
                finalizeStartupCategory(request.resolvedCategoryId)
            }
        }
    }

    private fun combinedGuideChannels(profileId: Long, categoryId: Long) =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            val flows = combinedCategoriesById.values.map { combinedM3uRepository.getCombinedChannels(profileId, it) }
            if (flows.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
            else combine(flows) { arrays -> arrays.toList().flatMap { it } }
        } else if (categoryId == VirtualCategoryIds.FAVORITES) {
            favoriteRepository.getFavorites(ContentType.LIVE)
                .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                .flatMapLatest(::loadGuideChannelsByOrderedIds)
        } else if (categoryId < 0L) {
            favoriteRepository.getFavoritesByGroup(-categoryId)
                .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                .flatMapLatest(::loadGuideChannelsByOrderedIds)
        } else {
            val combinedCategory = combinedCategoriesById[categoryId]
            if (combinedCategory == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else combinedM3uRepository.getCombinedChannels(profileId, combinedCategory)
        }

    private suspend fun publishGuideSnapshot(
        providerId: Long,
        providerName: String,
        providerSourceLabel: String,
        providerArchiveSummary: String,
        categories: List<Category>,
        request: GuideBaseRequest,
        channelSelection: GuideChannelSelection,
        guideResult: GuideProgramsResult
    ) {
        val visibleChannels = channelSelection.channels.take(MAX_CHANNELS)
        val computedNow = System.currentTimeMillis()
        val computedChannelsWithSchedule = visibleChannels.count { channel ->
            channel.guideLookupKey()
                ?.let { lookupKey -> guideResult.programsByChannel[lookupKey].orEmpty().isNotEmpty() }
                ?: false
        }
        val computedHasUpcomingData = guideResult.programsByChannel.values.any { programs ->
            programs.any { program -> program.endTime > request.windowStart }
        }
        baseGuideSnapshot.value = GuideBaseSnapshot(
            providerId = providerId,
            currentProviderName = providerName,
            providerSourceLabel = providerSourceLabel,
            providerArchiveSummary = providerArchiveSummary,
            categories = categories,
            selectedCategoryId = request.resolvedCategoryId,
            parentalControlLevel = request.parentalControlLevel,
            showFavoritesOnly = request.favoritesOnly,
            favoriteChannelIds = channelSelection.favoriteChannelIds,
            allChannels = channelSelection.channels,
            visibleChannels = visibleChannels,
            baseProgramsByChannel = guideResult.programsByChannel,
            failedScheduleCount = guideResult.failedCount,
            lastUpdatedAt = computedNow,
            baseChannelsWithSchedule = computedChannelsWithSchedule,
            baseGuideStale = visibleChannels.isNotEmpty() && (computedChannelsWithSchedule == 0 || !computedHasUpcomingData),
            guideAnchorTime = request.anchorTime,
            guideWindowStart = request.windowStart,
            guideWindowEnd = request.windowEnd
        )
    }

    private fun loadEpgOverrideCandidates(channel: Channel, query: String, refreshMapping: Boolean) {
        overrideSearchJob?.cancel()
        overrideSearchJob = viewModelScope.launch {
            val mapping = if (refreshMapping) {
                epgSourceRepository.getChannelMapping(channel.providerId, channel.id)
            } else {
                _overrideUiState.value.currentMapping
            }
            val candidates = epgSourceRepository.getOverrideCandidates(
                providerId = channel.providerId,
                query = query
            )
            _overrideUiState.update {
                it.copy(
                    channel = channel,
                    currentMapping = mapping,
                    searchQuery = query,
                    candidates = candidates,
                    isLoading = false,
                    isSaving = false,
                    error = null
                )
            }
        }
    }

    private fun observeGuidePresentation() {
        viewModelScope.launch {
            combine(
                baseGuideSnapshot,
                programSearchQuery,
                showScheduledOnly,
                selectedChannelMode,
                selectedDensity
            ) { baseSnapshot, searchQuery, scheduledOnly, channelMode, density ->
                GuidePresentationState(
                    baseSnapshot = baseSnapshot,
                    searchQuery = searchQuery.trim(),
                    scheduledOnly = scheduledOnly,
                    channelMode = channelMode,
                    density = density
                )
            }.collectLatest { presentation ->
                val baseSnapshot = presentation.baseSnapshot ?: run {
                    _uiState.update {
                        it.copy(
                            programSearchQuery = presentation.searchQuery,
                            showScheduledOnly = presentation.scheduledOnly,
                            selectedChannelMode = presentation.channelMode,
                            selectedDensity = presentation.density
                        )
                    }
                    return@collectLatest
                }

                val displaySnapshot = withContext(Dispatchers.Default) {
                    buildGuideDisplaySnapshot(
                        baseSnapshot = baseSnapshot,
                        searchQuery = presentation.searchQuery,
                        scheduledOnly = presentation.scheduledOnly,
                        channelMode = presentation.channelMode
                    )
                }

                _uiState.update {
                    it.copy(
                        currentProviderName = baseSnapshot.currentProviderName,
                        providerSourceLabel = baseSnapshot.providerSourceLabel,
                        providerArchiveSummary = baseSnapshot.providerArchiveSummary,
                        categories = baseSnapshot.categories,
                        selectedCategoryId = baseSnapshot.selectedCategoryId,
                        parentalControlLevel = baseSnapshot.parentalControlLevel,
                        programSearchQuery = presentation.searchQuery,
                        showScheduledOnly = presentation.scheduledOnly,
                        selectedChannelMode = presentation.channelMode,
                        selectedDensity = presentation.density,
                        showFavoritesOnly = baseSnapshot.showFavoritesOnly,
                        favoriteChannelIds = baseSnapshot.favoriteChannelIds,
                        channels = displaySnapshot.channels,
                        programsByChannel = displaySnapshot.programsByChannel,
                        isInitialLoading = false,
                        isRefreshing = false,
                        error = null,
                        totalChannelCount = displaySnapshot.totalChannelCount,
                        channelsWithSchedule = displaySnapshot.channelsWithSchedule,
                        failedScheduleCount = baseSnapshot.failedScheduleCount,
                        lastUpdatedAt = baseSnapshot.lastUpdatedAt,
                        isGuideStale = displaySnapshot.isGuideStale,
                        guideAnchorTime = baseSnapshot.guideAnchorTime,
                        guideWindowStart = baseSnapshot.guideWindowStart,
                        guideWindowEnd = baseSnapshot.guideWindowEnd
                    )
                }
            }
        }
    }

    private fun restoreGuidePreferences() {
        viewModelScope.launch {
            preferencesRepository.guideDensity.first()
                ?.let { saved ->
                    GuideDensity.entries.firstOrNull { it.name == saved }?.let { density ->
                        selectedDensity.value = density
                    }
                }
            preferencesRepository.guideChannelMode.first()
                ?.let { saved ->
                    GuideChannelMode.entries.firstOrNull { it.name == saved }?.let { mode ->
                        selectedChannelMode.value = mode
                    }
                }
            startupCategoryId.value = preferencesRepository.guideDefaultCategoryId.first() ?: VirtualCategoryIds.FAVORITES
            showFavoritesOnly.value = preferencesRepository.guideFavoritesOnly.first()
            showScheduledOnly.value = preferencesRepository.guideScheduledOnly.first()
            preferencesRepository.guideAnchorTime.first()
                ?.takeIf { it > 0L }
                ?.let { guideAnchorTime.value = it }
        }
    }

    private fun buildProviderArchiveSummary(provider: com.streamvault.domain.model.Provider): String {
        return when (provider.type) {
            com.streamvault.domain.model.ProviderType.XTREAM_CODES ->
                "Xtream replay depends on archive-enabled channels and valid replay stream ids from the provider."
            com.streamvault.domain.model.ProviderType.M3U ->
                if (provider.epgUrl.isBlank()) {
                    "M3U replay is limited: archive depends on provider templates and guide coverage is weaker without XMLTV."
                } else {
                    "M3U replay depends on the provider catch-up template and matching guide data."
                }
        }
    }

    private fun updateGuideAnchorTime(anchorTimeMs: Long) {
        guideAnchorTime.value = anchorTimeMs
        viewModelScope.launch {
            preferencesRepository.setGuideAnchorTime(anchorTimeMs)
        }
    }

    private fun buildGuideCategoryList(
        providerCategories: List<Category>,
        customCategories: List<Category>
    ): List<Category> {
        val favoritesCategory = customCategories.find { it.id == VirtualCategoryIds.FAVORITES }
        return buildList {
            if (favoritesCategory != null) {
                add(favoritesCategory)
            }
            addAll(customCategories.filter { it.id != VirtualCategoryIds.FAVORITES })
            add(
                Category(
                    id = ChannelRepository.ALL_CHANNELS_ID,
                    name = "All Channels",
                    type = ContentType.LIVE,
                    count = providerCategories.sumOf(Category::count)
                )
            )
            addAll(providerCategories)
        }
    }

    private fun loadGuideChannelsForProvider(
        providerId: Long,
        request: GuideBaseRequest
    ) = when (request.resolvedCategoryId) {
        ChannelRepository.ALL_CHANNELS_ID -> combine(
            channelRepository.getChannelsByNumber(providerId, request.resolvedCategoryId),
            channelRepository.getChannelsWithoutErrors(providerId, request.resolvedCategoryId),
            favoriteRepository.getFavorites(ContentType.LIVE)
        ) { channelsByNumber, healthyChannels, favorites ->
            val favoriteIds = favorites.map { it.contentId }.toSet()
            if (request.favoritesOnly) {
                healthyChannels.filter { it.id in favoriteIds }
                    .ifEmpty { channelsByNumber.filter { it.id in favoriteIds } }
            } else {
                healthyChannels.ifEmpty { channelsByNumber }
            }
        }

        VirtualCategoryIds.FAVORITES -> favoriteRepository.getFavorites(ContentType.LIVE)
            .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
            .flatMapLatest { ids -> loadGuideChannelsByOrderedIds(ids, providerId) }

        in Long.MIN_VALUE..<0L -> favoriteRepository.getFavoritesByGroup(-request.resolvedCategoryId)
            .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
            .flatMapLatest { ids -> loadGuideChannelsByOrderedIds(ids, providerId) }

        else -> combine(
            channelRepository.getChannelsByNumber(providerId, request.resolvedCategoryId),
            channelRepository.getChannelsWithoutErrors(providerId, request.resolvedCategoryId),
            favoriteRepository.getFavorites(ContentType.LIVE)
        ) { channelsByNumber, healthyChannels, favorites ->
            val favoriteIds = favorites.map { it.contentId }.toSet()
            if (request.favoritesOnly) {
                healthyChannels.filter { it.id in favoriteIds }
                    .ifEmpty { channelsByNumber.filter { it.id in favoriteIds } }
            } else {
                healthyChannels.ifEmpty { channelsByNumber }
            }
        }
    }

    private fun loadGuideChannelsByOrderedIds(ids: List<Long>, providerId: Long? = null) =
        if (ids.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            channelRepository.getChannelsByIds(ids).map { unsorted ->
                val filtered = providerId?.let { requiredProviderId ->
                    unsorted.filter { it.providerId == requiredProviderId }
                } ?: unsorted
                val channelsById = filtered.associateBy { it.id }
                ids.mapNotNull { channelsById[it] }
            }
        }

    private fun finalizeStartupCategory(resolvedCategoryId: Long) {
        if (startupCategoryId.value == null) return
        startupCategoryId.value = null
        selectedCategoryId.value = resolvedCategoryId
    }

    private suspend fun loadGuidePrograms(
        provider: com.streamvault.domain.model.Provider,
        providerId: Long,
        channels: List<Channel>,
        categoryId: Long,
        favoritesOnly: Boolean,
        windowStart: Long,
        windowEnd: Long
    ): GuideProgramsResult {
        return withContext(Dispatchers.IO) {
            if (channels.isEmpty()) {
                return@withContext GuideProgramsResult(emptyMap(), failedCount = 0)
            }

            val guideKeys = channels.mapNotNull(Channel::guideLookupKey).distinct()

            // 1. Try the multi-source resolved path first
            val channelIds = channels.map { it.id }
            val resolvedPrograms = runCatching {
                epgRepository.getResolvedProgramsForChannels(providerId, channelIds, windowStart, windowEnd)
            }.getOrElse { emptyMap() }

            // 2. For channels not covered by resolution, fall back to legacy provider-native query
            val unresolvedChannels = channels.filter { channel ->
                val key = channel.guideLookupKey()
                key == null || resolvedPrograms[key].isNullOrEmpty()
            }
            val legacyPrograms = if (unresolvedChannels.isNotEmpty()) {
                val xmltvKeys = unresolvedChannels.mapNotNull {
                    it.epgChannelId?.trim()?.takeIf(String::isNotEmpty)
                }.distinct()
                runCatching {
                    if (xmltvKeys.isEmpty()) emptyMap()
                    else epgRepository.getProgramsForChannels(providerId, xmltvKeys, windowStart, windowEnd).first()
                }.getOrElse { emptyMap() }
            } else {
                emptyMap()
            }

            val programsByChannel = resolvedPrograms + legacyPrograms

            // 3. Xtream on-demand fallback for still-missing channels
            val fallbackProgramsByChannel = fetchXtreamGuideFallback(
                provider = provider,
                providerId = providerId,
                channels = channels,
                existingProgramsByChannel = programsByChannel,
                windowStart = windowStart,
                windowEnd = windowEnd
            )
            val mergedProgramsByChannel = programsByChannel + fallbackProgramsByChannel

            GuideProgramsResult(
                programsByChannel = mergedProgramsByChannel,
                failedCount = guideKeys.count { lookupKey -> mergedProgramsByChannel[lookupKey].isNullOrEmpty() }
            )
        }
    }

    private suspend fun loadCombinedGuidePrograms(
        channels: List<Channel>,
        windowStart: Long,
        windowEnd: Long
    ): GuideProgramsResult {
        if (channels.isEmpty()) {
            return GuideProgramsResult(emptyMap(), failedCount = 0)
        }
        val groupedPrograms = channels.groupBy { it.providerId }
            .mapValues { (_, providerChannels) ->
                providerChannels
            }

        val mergedProgramsByChannel = buildMap<String, List<Program>> {
            groupedPrograms.forEach { (providerId, providerChannels) ->
                val provider = providerRepository.getProvider(providerId)
                    ?: com.streamvault.domain.model.Provider(
                        id = providerId,
                        name = "M3U Provider",
                        type = com.streamvault.domain.model.ProviderType.M3U,
                        serverUrl = ""
                    )
                val result = loadGuidePrograms(
                    provider = provider,
                    providerId = providerId,
                    channels = providerChannels,
                    categoryId = ChannelRepository.ALL_CHANNELS_ID,
                    favoritesOnly = false,
                    windowStart = windowStart,
                    windowEnd = windowEnd
                )
                putAll(result.programsByChannel)
            }
        }
        val guideKeys = channels.mapNotNull(Channel::guideLookupKey).distinct()
        return GuideProgramsResult(
            programsByChannel = mergedProgramsByChannel,
            failedCount = guideKeys.count { lookupKey -> mergedProgramsByChannel[lookupKey].isNullOrEmpty() }
        )
    }

    private suspend fun fetchXtreamGuideFallback(
        provider: com.streamvault.domain.model.Provider,
        providerId: Long,
        channels: List<Channel>,
        existingProgramsByChannel: Map<String, List<Program>>,
        windowStart: Long,
        windowEnd: Long
    ): Map<String, List<Program>> {
        if (provider.type != com.streamvault.domain.model.ProviderType.XTREAM_CODES) {
            return emptyMap()
        }

        val missingChannels = channels.filter { channel ->
            val lookupKey = channel.guideLookupKey()
            lookupKey != null &&
                channel.streamId > 0L &&
                existingProgramsByChannel[lookupKey].isNullOrEmpty()
        }
        if (missingChannels.isEmpty()) {
            return emptyMap()
        }

        return coroutineScope {
            missingChannels
                .take(MAX_XTREAM_GUIDE_FALLBACK_CHANNELS)
                .map { channel ->
                    async {
                        val result = providerRepository.getProgramsForLiveStream(
                            providerId = providerId,
                            streamId = channel.streamId,
                            epgChannelId = channel.epgChannelId,
                            limit = 12
                        )
                        val programs = (result as? com.streamvault.domain.model.Result.Success)?.data
                            .orEmpty()
                            .filter { program -> program.endTime > windowStart && program.startTime < windowEnd }
                            .sortedBy { program -> program.startTime }
                        val lookupKey = channel.guideLookupKey() ?: return@async null
                        if (programs.isEmpty()) null else lookupKey to programs
                    }
                }
                .awaitAll()
                .mapNotNull { it }
                .toMap()
        }
    }

    private suspend fun buildGuideDisplaySnapshot(
        baseSnapshot: GuideBaseSnapshot,
        searchQuery: String,
        scheduledOnly: Boolean,
        channelMode: GuideChannelMode
    ): GuideDisplaySnapshot {
        val normalizedQuery = searchQuery.trim()
        val (candidateChannels, candidateProgramsByChannel) = if (normalizedQuery.isBlank()) {
            baseSnapshot.visibleChannels to baseSnapshot.baseProgramsByChannel
        } else {
            buildSearchGuideSnapshot(baseSnapshot, normalizedQuery)
        }

        val displayChannels = candidateChannels.filter { channel ->
            val programs = channel.guideLookupKey()
                ?.let { lookupKey -> candidateProgramsByChannel[lookupKey].orEmpty() }
                .orEmpty()
            val matchesScheduled = !scheduledOnly || programs.isNotEmpty()
            val matchesMode = when (channelMode) {
                GuideChannelMode.ALL -> true
                GuideChannelMode.ANCHORED -> programs.any { program ->
                    baseSnapshot.guideAnchorTime in program.startTime until program.endTime
                }
                GuideChannelMode.ARCHIVE_READY -> channel.catchUpSupported || programs.any { it.hasArchive }
            }
            matchesScheduled && matchesMode
        }
        val channelsWithSchedule = candidateChannels.count { channel ->
            channel.guideLookupKey()
                ?.let { lookupKey -> candidateProgramsByChannel[lookupKey].orEmpty().isNotEmpty() }
                ?: false
        }
        val hasUpcomingData = candidateProgramsByChannel.values.any { programs ->
            programs.any { program -> program.endTime > baseSnapshot.guideWindowStart }
        }

        return GuideDisplaySnapshot(
            channels = displayChannels,
            programsByChannel = candidateProgramsByChannel,
            totalChannelCount = candidateChannels.size,
            channelsWithSchedule = channelsWithSchedule,
            isGuideStale = candidateChannels.isNotEmpty() && (channelsWithSchedule == 0 || !hasUpcomingData)
        )
    }

    private suspend fun buildSearchGuideSnapshot(
        baseSnapshot: GuideBaseSnapshot,
        searchQuery: String
    ): Pair<List<Channel>, Map<String, List<Program>>> {
        val matchedProgramsByChannel = baseSnapshot.baseProgramsByChannel
            .mapValues { (_, programs) ->
                programs.filter { program ->
                    program.title.contains(searchQuery, ignoreCase = true) ||
                        program.description.contains(searchQuery, ignoreCase = true)
                }
            }
            .filterValues { it.isNotEmpty() }

        val matchedChannels = baseSnapshot.visibleChannels.filter { channel ->
            channel.name.contains(searchQuery, ignoreCase = true) ||
                channel.categoryName?.contains(searchQuery, ignoreCase = true) == true ||
                channel.guideLookupKey()?.let(matchedProgramsByChannel::containsKey) == true
        }.take(MAX_CHANNELS)

        val matchedChannelKeys = matchedChannels.mapNotNull(Channel::guideLookupKey).toSet()
        return matchedChannels to matchedProgramsByChannel.filterKeys { it in matchedChannelKeys }
    }

    private fun resolveGuideCategorySelection(
        requestedCategoryId: Long,
        categories: List<Category>,
        parentalControlLevel: Int,
        unlockedCategoryIds: Set<Long>,
        fallbackFromEmptyFavorites: Boolean = false
    ): Long {
        val requestedExists = requestedCategoryId == ChannelRepository.ALL_CHANNELS_ID ||
            categories.any { it.id == requestedCategoryId }
        if (requestedCategoryId == ChannelRepository.ALL_CHANNELS_ID && requestedExists) {
            return ChannelRepository.ALL_CHANNELS_ID
        }

        val requestedCategory = categories.firstOrNull { it.id == requestedCategoryId }
        if (requestedCategory != null && isGuideCategoryAccessible(requestedCategory, parentalControlLevel, unlockedCategoryIds)) {
            if (fallbackFromEmptyFavorites && requestedCategory.id == VirtualCategoryIds.FAVORITES && requestedCategory.count <= 0) {
                return ChannelRepository.ALL_CHANNELS_ID
            }
            return requestedCategory.id
        }

        return categories.firstOrNull { category ->
            if (fallbackFromEmptyFavorites && category.id == VirtualCategoryIds.FAVORITES && category.count <= 0) {
                false
            } else {
                isGuideCategoryAccessible(category, parentalControlLevel, unlockedCategoryIds)
            }
        }?.id ?: ChannelRepository.ALL_CHANNELS_ID
    }

    private fun isGuideCategoryAccessible(
        category: Category,
        parentalControlLevel: Int,
        unlockedCategoryIds: Set<Long>
    ): Boolean {
        if (parentalControlLevel != 1) {
            return true
        }
        return (!category.isAdult && !category.isUserProtected) || unlockedCategoryIds.contains(category.id)
    }
}

private data class GuidePresentationState(
    val baseSnapshot: GuideBaseSnapshot?,
    val searchQuery: String,
    val scheduledOnly: Boolean,
    val channelMode: GuideChannelMode,
    val density: GuideDensity
)

private data class GuideSelectionSeed(
    val requestedCategoryId: Long,
    val anchorTime: Long,
    val favoritesOnly: Boolean,
    val isStartupSelection: Boolean
)

private data class GuideCategoryData(
    val providerCategories: List<Category>,
    val customCategories: List<Category>,
    val hiddenCategoryIds: Set<Long>,
    val sortMode: com.streamvault.domain.model.CategorySortMode
)
