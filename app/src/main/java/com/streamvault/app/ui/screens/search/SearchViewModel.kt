package com.streamvault.app.ui.screens.search

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.streamvault.app.R
import androidx.lifecycle.viewModelScope
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.SearchHistoryScope
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.SearchContent
import com.streamvault.domain.usecase.SearchContentResult
import com.streamvault.domain.usecase.SearchContentScope
import com.streamvault.domain.util.AdultContentVisibilityPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val searchContent: SearchContent,
    private val preferencesRepository: PreferencesRepository,
    private val parentalControlManager: ParentalControlManager,
    private val favoriteRepository: FavoriteRepository,
    private val categoryRepository: CategoryRepository,
    private val recordingManager: RecordingManager,
) : ViewModel() {
    private val searchResultsCache = SearchResultsCache()
    private companion object {
        const val MAX_RESULTS_PER_SECTION = 120
        const val MAX_RECENT_QUERIES = 6
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedTab = MutableStateFlow(SearchTab.ALL)
    val selectedTab: StateFlow<SearchTab> = _selectedTab.asStateFlow()

    private val _parentalControlLevel = MutableStateFlow(0)
    private var lastActiveProviderId: Long? = null

    private val _recordingChannelIds = MutableStateFlow<Set<Long>>(emptySet())
    val recordingChannelIds: StateFlow<Set<Long>> = _recordingChannelIds.asStateFlow()

    private val _scheduledChannelIds = MutableStateFlow<Set<Long>>(emptySet())
    val scheduledChannelIds: StateFlow<Set<Long>> = _scheduledChannelIds.asStateFlow()

    private val unlockedCategoryIds = providerRepository.getActiveProvider()
        .onEach { provider ->
            val providerId = provider?.id
            if (lastActiveProviderId != null && lastActiveProviderId != providerId) {
                searchResultsCache.clearProvider(lastActiveProviderId!!)
            }
            lastActiveProviderId = providerId
        }
        .flatMapLatest { provider ->
            provider?.let { parentalControlManager.unlockedCategoriesForProvider(it.id) } ?: flowOf(emptySet())
        }

    init {
        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collect { level ->
                _parentalControlLevel.value = level
            }
        }
        viewModelScope.launch {
            recordingManager.observeRecordingItems().collect { items ->
                _recordingChannelIds.value = items
                    .filter { it.status == RecordingStatus.RECORDING }
                    .map { it.channelId }.toSet()
                _scheduledChannelIds.value = items
                    .filter { it.status == RecordingStatus.SCHEDULED }
                    .map { it.channelId }.toSet()
            }
        }
    }

    val recentQueries: StateFlow<List<String>> = combine(
        _selectedTab,
        providerRepository.getActiveProvider()
    ) { tab, provider ->
        tab.toSearchHistoryScope() to provider?.id
    }.flatMapLatest { (scope, providerId) ->
        preferencesRepository.getRecentSearchQueries(
            scope = scope,
            providerId = providerId,
            limit = MAX_RECENT_QUERIES
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(FlowPreview::class)
    val uiState: StateFlow<SearchUiState> = combine(
        providerRepository.getActiveProvider(),
        _query.debounce(300),
        _selectedTab,
        _parentalControlLevel,
        unlockedCategoryIds
    ) { provider, query, tab, level, unlockedIds ->
        SearchFilterParams(provider, query, tab, level, unlockedIds)
    }.distinctUntilChanged().flatMapLatest { params ->
        val provider = params.provider
        val query = params.query
        val tab = params.tab
        val level = params.level
        val unlockedIds = params.unlockedCategoryIds

        val trimmedQuery = query.trim()
        val trimmedQueryLength = trimmedQuery.length
        if (provider == null || trimmedQueryLength < 2) {
            flowOf(
                SearchUiState(
                    parentalControlLevel = level,
                    hasActiveProvider = provider != null,
                    queryLength = trimmedQueryLength,
                    unlockedCategoryIds = unlockedIds
                )
            )
        } else {
            searchResultsFlow(params, trimmedQuery, trimmedQueryLength)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

    private fun searchResultsFlow(
        params: SearchFilterParams,
        trimmedQuery: String,
        trimmedQueryLength: Int,
    ): Flow<SearchUiState> {
        val provider = params.provider ?: return flowOf(SearchUiState())
        val cacheKey = SearchCacheKey(
            providerId = provider.id,
            normalizedQuery = trimmedQuery.lowercase(),
            scope = params.tab.toSearchScope()
        )
        val cachedFresh = searchResultsCache.getFresh(cacheKey)
        val cachedStale = cachedFresh ?: searchResultsCache.getStale(cacheKey)

        return flow {
            if (cachedStale != null) {
                emit(
                    params.toUiState(
                        results = cachedStale,
                        isLoading = cachedFresh == null,
                        trimmedQueryLength = trimmedQueryLength
                    )
                )
            } else {
                emit(
                    SearchUiState(
                        isLoading = true,
                        hasSearched = true,
                        parentalControlLevel = params.level,
                        hasActiveProvider = true,
                        queryLength = trimmedQueryLength,
                        unlockedCategoryIds = params.unlockedCategoryIds
                    )
                )
            }

            if (cachedFresh != null) {
                return@flow
            }

            searchContent(
                providerId = provider.id,
                query = trimmedQuery,
                scope = params.tab.toSearchScope(),
                maxResultsPerSection = MAX_RESULTS_PER_SECTION
            ).collect { results ->
                searchResultsCache.put(cacheKey, results)
                emit(
                    params.toUiState(
                        results = results,
                        isLoading = false,
                        trimmedQueryLength = trimmedQueryLength
                    )
                )
            }
        }
    }

    private fun SearchFilterParams.toUiState(
        results: SearchContentResult,
        isLoading: Boolean,
        trimmedQueryLength: Int,
    ): SearchUiState {
        val filterAdult = !AdultContentVisibilityPolicy.showInAggregatedSurfaces(level)
        return SearchUiState(
            channels = if (filterAdult) {
                results.channels.filterNot { it.isAdult || it.isUserProtected }
            } else {
                results.channels
            },
            movies = if (filterAdult) {
                results.movies.filterNot { it.isAdult || it.isUserProtected }
            } else {
                results.movies
            },
            series = if (filterAdult) {
                results.series.filterNot { it.isAdult || it.isUserProtected }
            } else {
                results.series
            },
            isLoading = isLoading,
            hasSearched = true,
            hasSearchError = results.isPartialResult,
            parentalControlLevel = level,
            hasActiveProvider = true,
            queryLength = trimmedQueryLength,
            unlockedCategoryIds = unlockedCategoryIds
        )
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onSearchSubmitted() {
        val normalizedQuery = _query.value.trim()
        if (normalizedQuery.length < 2) return

        _query.value = normalizedQuery
        viewModelScope.launch {
            preferencesRepository.recordRecentSearchQuery(
                query = normalizedQuery,
                scope = _selectedTab.value.toSearchHistoryScope(),
                providerId = lastActiveProviderId
            )
        }
    }

    fun onRecentQuerySelected(query: String) {
        _query.value = query
        onSearchSubmitted()
    }

    fun submitExternalQuery(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) return

        _query.value = normalizedQuery
        onSearchSubmitted()
    }

    fun clearRecentQueries() {
        viewModelScope.launch {
            preferencesRepository.clearRecentSearchQueries(
                scope = _selectedTab.value.toSearchHistoryScope(),
                providerId = lastActiveProviderId
            )
        }
    }

    fun onTabSelected(tab: SearchTab) {
        _selectedTab.value = tab
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun unlockCategory(categoryId: Long?) {
        val providerId = lastActiveProviderId ?: return
        val resolvedCategoryId = categoryId ?: return
        parentalControlManager.unlockCategory(providerId, resolvedCategoryId)
    }

    fun toggleFavorite(contentId: Long, contentType: ContentType, currentlyFavorite: Boolean) {
        viewModelScope.launch {
            val providerId = lastActiveProviderId ?: return@launch
            if (currentlyFavorite) {
                favoriteRepository.removeFavorite(providerId, contentId, contentType)
            } else {
                favoriteRepository.addFavorite(providerId, contentId, contentType)
            }
        }
    }

    fun hideItemCategory(categoryId: Long, contentType: ContentType) {
        val providerId = lastActiveProviderId ?: return
        viewModelScope.launch {
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = contentType,
                categoryId = categoryId,
                hidden = true
            )
        }
    }

    fun toggleCategoryProtection(categoryId: Long, contentType: ContentType, currentlyProtected: Boolean) {
        val providerId = lastActiveProviderId ?: return
        viewModelScope.launch {
            categoryRepository.setCategoryProtection(
                providerId = providerId,
                categoryId = categoryId,
                type = contentType,
                isProtected = !currentlyProtected
            )
        }
    }
}

internal data class SearchFilterParams(
    val provider: Provider?,
    val query: String,
    val tab: SearchTab,
    val level: Int,
    val unlockedCategoryIds: Set<Long>
)

enum class SearchTab(@get:StringRes val titleRes: Int) {
    ALL(R.string.search_all),
    LIVE(R.string.search_live_tv),
    MOVIES(R.string.search_movies),
    SERIES(R.string.search_series)
}

internal fun SearchTab.toSearchScope(): SearchContentScope = when (this) {
    SearchTab.ALL -> SearchContentScope.ALL
    SearchTab.LIVE -> SearchContentScope.LIVE
    SearchTab.MOVIES -> SearchContentScope.MOVIES
    SearchTab.SERIES -> SearchContentScope.SERIES
}

internal fun SearchTab.toSearchHistoryScope(): SearchHistoryScope = when (this) {
    SearchTab.ALL -> SearchHistoryScope.ALL
    SearchTab.LIVE -> SearchHistoryScope.LIVE
    SearchTab.MOVIES -> SearchHistoryScope.MOVIE
    SearchTab.SERIES -> SearchHistoryScope.SERIES
}

data class SearchUiState(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val hasSearchError: Boolean = false,
    val parentalControlLevel: Int = 0,
    val hasActiveProvider: Boolean = false,
    val queryLength: Int = 0,
    val unlockedCategoryIds: Set<Long> = emptySet()
) {
    val isEmpty: Boolean get() = hasSearched && channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    val totalResults: Int get() = channels.size + movies.size + series.size
}
