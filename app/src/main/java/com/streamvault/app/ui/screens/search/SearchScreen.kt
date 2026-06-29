package com.streamvault.app.ui.screens.search

import androidx.annotation.StringRes
import com.streamvault.app.ui.interaction.TvClickableSurface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.ui.components.CategoryRow
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.app.ui.components.ChannelCard
import com.streamvault.app.ui.components.MovieCard
import com.streamvault.app.ui.components.SeriesCard
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.requestFocusSafely
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onChannelClick: (Channel) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val recentQueries by viewModel.recentQueries.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recordingChannelIds by viewModel.recordingChannelIds.collectAsStateWithLifecycle()
    val scheduledChannelIds by viewModel.scheduledChannelIds.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingChannel by remember { mutableStateOf<Channel?>(null) }
    var pendingMovie by remember { mutableStateOf<Movie?>(null) }
    var pendingSeries by remember { mutableStateOf<Series?>(null) }
    val scope = rememberCoroutineScope()
    val selectedStateLabel = stringResource(R.string.a11y_selected)

    // ── Long-press actions dialog state ───────────────────────────────
    var showActionsDialog by remember { mutableStateOf(false) }
    var actionsChannel by remember { mutableStateOf<Channel?>(null) }
    var actionsMovie by remember { mutableStateOf<Movie?>(null) }
    var actionsSeries by remember { mutableStateOf<Series?>(null) }
    var actionsIsFavorite by remember { mutableStateOf(false) }
    // Separate PIN purpose: unlock-to-play vs toggle-parental-protection
    var pinIsForProtectionToggle by remember { mutableStateOf(false) }
    var pendingProtectionCategoryId by remember { mutableStateOf<Long?>(null) }
    var pendingProtectionContentType by remember { mutableStateOf<ContentType?>(null) }
    var pendingProtectionCurrentlyProtected by remember { mutableStateOf(false) }

    fun showChannelActions(channel: Channel) {
        actionsChannel = channel; actionsMovie = null; actionsSeries = null
        actionsIsFavorite = channel.isFavorite
        showActionsDialog = true
    }
    fun showMovieActions(movie: Movie) {
        actionsChannel = null; actionsMovie = movie; actionsSeries = null
        actionsIsFavorite = movie.isFavorite
        showActionsDialog = true
    }
    fun showSeriesActions(series: Series) {
        actionsChannel = null; actionsMovie = null; actionsSeries = series
        actionsIsFavorite = series.isFavorite
        showActionsDialog = true
    }
    val channelRows = remember(uiState.channels) { uiState.channels.chunked(4) }
    val movieRows = remember(uiState.movies) { uiState.movies.chunked(6) }
    val seriesRows = remember(uiState.series) { uiState.series.chunked(6) }

    fun isLocked(categoryId: Long?, isAdult: Boolean, isUserProtected: Boolean): Boolean {
        if (uiState.parentalControlLevel != 1) {
            return false
        }
        if (!isAdult && !isUserProtected) {
            return false
        }
        return categoryId == null || categoryId !in uiState.unlockedCategoryIds
    }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocusSafely(tag = "SearchScreen", target = "Search field")
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            viewModel.submitExternalQuery(initialQuery)
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(showPinDialog) {
        if (!showPinDialog) {
            searchFocusRequester.requestFocusSafely(tag = "SearchScreen", target = "Search field")
        }
    }

    if (showPinDialog) {
        com.streamvault.app.ui.components.dialogs.PinDialog(
            onDismissRequest = {
                showPinDialog = false
                pinError = null
                pendingChannel = null
                pendingMovie = null
                pendingSeries = null
                pinIsForProtectionToggle = false
                pendingProtectionCategoryId = null
                pendingProtectionContentType = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        if (pinIsForProtectionToggle) {
                            val catId = pendingProtectionCategoryId
                            val ct = pendingProtectionContentType
                            if (catId != null && ct != null) {
                                viewModel.toggleCategoryProtection(catId, ct, pendingProtectionCurrentlyProtected)
                            }
                            showPinDialog = false
                            pinError = null
                            pinIsForProtectionToggle = false
                            pendingProtectionCategoryId = null
                            pendingProtectionContentType = null
                        } else {
                            pendingChannel?.categoryId?.let(viewModel::unlockCategory)
                            pendingMovie?.categoryId?.let(viewModel::unlockCategory)
                            pendingSeries?.categoryId?.let(viewModel::unlockCategory)
                            showPinDialog = false
                            pinError = null
                            pendingChannel?.let { onChannelClick(it) }
                            pendingMovie?.let { onMovieClick(it) }
                            pendingSeries?.let { onSeriesClick(it) }
                            pendingChannel = null
                            pendingMovie = null
                            pendingSeries = null
                        }
                    } else {
                        pinError = context.getString(R.string.search_incorrect_pin)
                    }
                }
            },
            error = pinError
        )
    }

    val selectedTabDescription = selectedStateLabel

    // ── Long-press actions dialog ─────────────────────────────────────
    if (showActionsDialog) {
        val actionsTitle = actionsChannel?.name ?: actionsMovie?.name ?: actionsSeries?.name ?: ""
        val actionsCategoryId = actionsChannel?.categoryId ?: actionsMovie?.categoryId ?: actionsSeries?.categoryId
        val actionsIsProtected = actionsChannel?.isUserProtected ?: actionsMovie?.isUserProtected ?: actionsSeries?.isUserProtected ?: false
        val actionsContentType = when {
            actionsChannel != null -> ContentType.LIVE
            actionsMovie != null -> ContentType.MOVIE
            else -> ContentType.SERIES
        }
        val actionsItemId = actionsChannel?.id ?: actionsMovie?.id ?: actionsSeries?.id ?: 0L
        SearchItemActionsDialog(
            title = actionsTitle,
            isFavorite = actionsIsFavorite,
            isProtected = actionsIsProtected,
            hasCategoryId = actionsCategoryId != null,
            onDismiss = { showActionsDialog = false },
            onToggleFavorite = {
                viewModel.toggleFavorite(actionsItemId, actionsContentType, actionsIsFavorite)
                actionsIsFavorite = !actionsIsFavorite
            },
            onHide = if (actionsCategoryId != null) {
                {
                    viewModel.hideItemCategory(actionsCategoryId, actionsContentType)
                    showActionsDialog = false
                }
            } else null,
            onToggleLock = if (actionsCategoryId != null) {
                {
                    showActionsDialog = false
                    pendingProtectionCategoryId = actionsCategoryId
                    pendingProtectionContentType = actionsContentType
                    pendingProtectionCurrentlyProtected = actionsIsProtected
                    pinIsForProtectionToggle = true
                    showPinDialog = true
                }
            } else null
        )
    }

    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = stringResource(R.string.search_title),
        subtitle = stringResource(R.string.search_screen_subtitle),
        navigationChrome = AppNavigationChrome.TopBar,
        compactHeader = true,
        showScreenHeader = false
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                SearchHeroPanel(
                    query = query,
                    selectedTab = selectedTab,
                    recentQueries = recentQueries,
                    totalResults = uiState.totalResults,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = {
                        viewModel.onSearchSubmitted()
                    },
                    onTabSelected = viewModel::onTabSelected,
                    onRecentQuerySelected = {
                        viewModel.onRecentQuerySelected(it)
                    },
                    onClearRecentQueries = viewModel::clearRecentQueries,
                    focusRequester = searchFocusRequester,
                    selectedStateLabel = selectedTabDescription
                )
            }

            when {
                !uiState.hasActiveProvider -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_no_provider_title),
                            subtitle = stringResource(R.string.search_no_provider_subtitle)
                        )
                    }
                }

                uiState.queryLength < 2 -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_ready_title),
                            subtitle = stringResource(R.string.search_type_to_search)
                        )
                    }
                }

                uiState.isLoading && uiState.totalResults == 0 -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_loading_title),
                            subtitle = stringResource(R.string.search_loading_subtitle)
                        )
                    }
                }

                uiState.isEmpty && uiState.hasSearchError -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_error_title),
                            subtitle = stringResource(R.string.search_error_subtitle)
                        )
                    }
                }

                uiState.isEmpty -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_no_results_title),
                            subtitle = stringResource(R.string.search_no_results, query)
                        )
                    }
                }

                else -> {
                    item {
                        SearchResultsSummaryRow(
                            uiState = uiState
                        )
                    }

                    if (selectedTab == SearchTab.ALL) {
                        if (uiState.channels.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_live_tv),
                                    items = uiState.channels.take(18),
                                    keySelector = { it.id }
                                ) { channel ->
                                    val channelLocked = isLocked(
                                        categoryId = channel.categoryId,
                                        isAdult = channel.isAdult,
                                        isUserProtected = channel.isUserProtected
                                    )
                                    ChannelCard(
                                        channel = channel,
                                        isLocked = channelLocked,
                                        isRecording = channel.id in recordingChannelIds,
                                        isScheduledRecording = channel.id in scheduledChannelIds,
                                        onClick = {
                                            if (channelLocked) {
                                                pendingChannel = channel
                                                showPinDialog = true
                                            } else {
                                                onChannelClick(channel)
                                            }
                                        },
                                        onLongClick = { showChannelActions(channel) }
                                    )
                                }
                            }
                        }

                        if (uiState.movies.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_movies),
                                    items = uiState.movies.take(18),
                                    keySelector = { it.id }
                                ) { movie ->
                                    val movieLocked = isLocked(
                                        categoryId = movie.categoryId,
                                        isAdult = movie.isAdult,
                                        isUserProtected = movie.isUserProtected
                                    )
                                    MovieCard(
                                        movie = movie,
                                        isLocked = movieLocked,
                                        onClick = {
                                            if (movieLocked) {
                                                pendingMovie = movie
                                                showPinDialog = true
                                            } else {
                                                onMovieClick(movie)
                                            }
                                        },
                                        onLongClick = { showMovieActions(movie) }
                                    )
                                }
                            }
                        }

                        if (uiState.series.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_series),
                                    items = uiState.series.take(18),
                                    keySelector = { it.id }
                                ) { seriesItem ->
                                    val seriesLocked = isLocked(
                                        categoryId = seriesItem.categoryId,
                                        isAdult = seriesItem.isAdult,
                                        isUserProtected = seriesItem.isUserProtected
                                    )
                                    SeriesCard(
                                        series = seriesItem,
                                        isLocked = seriesLocked,
                                        onClick = {
                                            if (seriesLocked) {
                                                pendingSeries = seriesItem
                                                showPinDialog = true
                                            } else {
                                                onSeriesClick(seriesItem)
                                            }
                                        },
                                        onLongClick = { showSeriesActions(seriesItem) }
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            SectionHeader(
                                title = when (selectedTab) {
                                    SearchTab.ALL -> stringResource(R.string.search_all)
                                    SearchTab.LIVE -> stringResource(R.string.search_live_tv)
                                    SearchTab.MOVIES -> stringResource(R.string.search_movies)
                                    SearchTab.SERIES -> stringResource(R.string.search_series)
                                }
                            )
                        }

                        when (selectedTab) {
                            SearchTab.ALL -> Unit
                            SearchTab.LIVE -> items(channelRows, key = { row ->
                                row.joinToString("-") { it.id.toString() }
                            }) { row ->
                                SearchChannelGridRow(
                                    channels = row,
                                    recordingChannelIds = recordingChannelIds,
                                    scheduledChannelIds = scheduledChannelIds,
                                    isLocked = { channel ->
                                        isLocked(
                                            categoryId = channel.categoryId,
                                            isAdult = channel.isAdult,
                                            isUserProtected = channel.isUserProtected
                                        )
                                    },
                                    onChannelClick = { channel, locked ->
                                        if (locked) {
                                            pendingChannel = channel
                                            showPinDialog = true
                                        } else {
                                            onChannelClick(channel)
                                        }
                                    },
                                    onChannelLongClick = { channel -> showChannelActions(channel) }
                                )
                            }

                            SearchTab.MOVIES -> items(movieRows, key = { row ->
                                row.joinToString("-") { it.id.toString() }
                            }) { row ->
                                SearchMovieGridRow(
                                    movies = row,
                                    isLocked = { movie ->
                                        isLocked(
                                            categoryId = movie.categoryId,
                                            isAdult = movie.isAdult,
                                            isUserProtected = movie.isUserProtected
                                        )
                                    },
                                    onMovieClick = { movie, locked ->
                                        if (locked) {
                                            pendingMovie = movie
                                            showPinDialog = true
                                        } else {
                                            onMovieClick(movie)
                                        }
                                    },
                                    onMovieLongClick = { movie -> showMovieActions(movie) }
                                )
                            }

                            SearchTab.SERIES -> items(seriesRows, key = { row ->
                                row.joinToString("-") { it.id.toString() }
                            }) { row ->
                                SearchSeriesGridRow(
                                    seriesItems = row,
                                    isLocked = { seriesItem ->
                                        isLocked(
                                            categoryId = seriesItem.categoryId,
                                            isAdult = seriesItem.isAdult,
                                            isUserProtected = seriesItem.isUserProtected
                                        )
                                    },
                                    onSeriesClick = { seriesItem, locked ->
                                        if (locked) {
                                            pendingSeries = seriesItem
                                            showPinDialog = true
                                        } else {
                                            onSeriesClick(seriesItem)
                                        }
                                    },
                                    onSeriesLongClick = { seriesItem -> showSeriesActions(seriesItem) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHeroPanel(
    query: String,
    selectedTab: SearchTab,
    recentQueries: List<String>,
    totalResults: Int,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTabSelected: (SearchTab) -> Unit,
    onRecentQuerySelected: (String) -> Unit,
    onClearRecentQueries: () -> Unit,
    focusRequester: FocusRequester,
    selectedStateLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.92f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.search_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = stringResource(R.string.search_command_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 640.dp)
                    )
                }

                SearchStatusCard(
                    title = if (query.length >= 2) {
                        stringResource(R.string.search_results_title, totalResults)
                    } else {
                        stringResource(R.string.search_ready_title)
                    },
                    body = if (query.length >= 2) {
                        stringResource(R.string.search_screen_subtitle)
                    } else {
                        stringResource(R.string.search_type_to_search)
                    },
                    modifier = Modifier.widthIn(min = 220.dp, max = 360.dp)
                )
            }

            SearchInput(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.search_hint),
                focusRequester = focusRequester,
                onSearch = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchTab.values().toList(), key = { it.name }) { tab ->
                    SearchPill(
                        text = stringResource(tab.titleRes),
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier.semantics {
                            selected = tab == selectedTab
                            if (tab == selectedTab) {
                                stateDescription = selectedStateLabel
                            }
                        }
                    )
                }
            }

            if (recentQueries.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.search_recent_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentQueries, key = { it }) { recentQuery ->
                            val recentQueryDescription = stringResource(R.string.a11y_recent_search, recentQuery)
                            SearchPill(
                                text = recentQuery,
                                selected = recentQuery.equals(query, ignoreCase = true),
                                onClick = { onRecentQuerySelected(recentQuery) },
                                modifier = Modifier.semantics {
                                    contentDescription = recentQueryDescription
                                    if (recentQuery.equals(query, ignoreCase = true)) {
                                        selected = true
                                        stateDescription = selectedStateLabel
                                    }
                                }
                            )
                        }
                    }
                    SearchPill(
                        text = stringResource(R.string.search_clear_history),
                        selected = false,
                        compact = true,
                        onClick = onClearRecentQueries
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    TvClickableSurface(
        modifier = modifier,
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.22f) else Surface.copy(alpha = 0.72f),
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.30f) else SurfaceHighlight,
            contentColor = if (selected) Color.White else TextSecondary,
            focusedContentColor = TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.65f) else FocusBorder.copy(alpha = 0.28f)
                ),
                shape = CircleShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = CircleShape
            )
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 8.dp else 10.dp
            ),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchStatusCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = Surface.copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun <T : Any> SearchResultRail(
    title: String,
    items: List<T>,
    keySelector: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    CategoryRow(
        title = title,
        items = items,
        keySelector = keySelector
    ) { item ->
        itemContent(item)
    }
}

@Composable
private fun SearchChannelGridRow(
    channels: List<Channel>,
    recordingChannelIds: Set<Long> = emptySet(),
    scheduledChannelIds: Set<Long> = emptySet(),
    isLocked: (Channel) -> Boolean,
    onChannelClick: (Channel, Boolean) -> Unit,
    onChannelLongClick: (Channel) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        channels.forEach { channel ->
            val locked = isLocked(channel)
            ChannelCard(
                channel = channel,
                isLocked = locked,
                isRecording = channel.id in recordingChannelIds,
                isScheduledRecording = channel.id in scheduledChannelIds,
                onClick = { onChannelClick(channel, locked) },
                onLongClick = { onChannelLongClick(channel) }
            )
        }
    }
}

@Composable
private fun SearchMovieGridRow(
    movies: List<Movie>,
    isLocked: (Movie) -> Boolean,
    onMovieClick: (Movie, Boolean) -> Unit,
    onMovieLongClick: (Movie) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        movies.forEach { movie ->
            val locked = isLocked(movie)
            MovieCard(
                movie = movie,
                isLocked = locked,
                onClick = { onMovieClick(movie, locked) },
                onLongClick = { onMovieLongClick(movie) }
            )
        }
    }
}

@Composable
private fun SearchSeriesGridRow(
    seriesItems: List<Series>,
    isLocked: (Series) -> Boolean,
    onSeriesClick: (Series, Boolean) -> Unit,
    onSeriesLongClick: (Series) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        seriesItems.forEach { seriesItem ->
            val locked = isLocked(seriesItem)
            SeriesCard(
                series = seriesItem,
                isLocked = locked,
                onClick = { onSeriesClick(seriesItem, locked) },
                onLongClick = { onSeriesLongClick(seriesItem) }
            )
        }
    }
}


@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Primary,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .semantics { heading() }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResultsSummaryRow(
    uiState: SearchUiState
) {
    val countsSummary = listOf(
        stringResource(R.string.search_results_count, stringResource(R.string.search_live_tv), uiState.channels.size),
        stringResource(R.string.search_results_count, stringResource(R.string.search_movies), uiState.movies.size),
        stringResource(R.string.search_results_count, stringResource(R.string.search_series), uiState.series.size)
    ).joinToString("  •  ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.search_results_title, uiState.totalResults),
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = countsSummary,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchMessageState(
    title: String,
    subtitle: String
) {
    TvEmptyState(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

// ── Long-press actions dialog ─────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchItemActionsDialog(
    title: String,
    isFavorite: Boolean,
    isProtected: Boolean,
    hasCategoryId: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHide: (() -> Unit)?,
    onToggleLock: (() -> Unit)?
) {
    // Ghost-click debounce: ignore select key-up inherited from the long-press gesture
    var canInteract by remember { mutableStateOf(false) }
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
        delay(500)
        canInteract = true
    }

    val safeDismiss = { if (canInteract) onDismiss() }

    Dialog(
        onDismissRequest = safeDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated),
            modifier = Modifier
                .width(360.dp)
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = safeDismiss,
                        modifier = Modifier.mouseClickable(onClick = safeDismiss)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_actions_dismiss))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ── Favorite toggle ──────────────────────────────
                    SearchActionButton(
                        icon = Icons.Default.Star,
                        label = if (isFavorite) stringResource(R.string.search_actions_remove_favorite)
                                else stringResource(R.string.search_actions_add_favorite),
                        isActive = isFavorite,
                        focusRequester = firstFocusRequester,
                        onClick = { if (canInteract) onToggleFavorite() }
                    )

                    // ── Hide category ────────────────────────────────
                    if (onHide != null) {
                        SearchActionButton(
                            icon = Icons.Default.Close,
                            label = stringResource(R.string.search_actions_hide_category),
                            isActive = false,
                            onClick = { if (canInteract) onHide() }
                        )
                    } else {
                        SearchActionButton(
                            icon = Icons.Default.Close,
                            label = stringResource(R.string.search_actions_hide_no_category),
                            isActive = false,
                            enabled = false,
                            onClick = {}
                        )
                    }

                    // ── Parental lock toggle ─────────────────────────
                    if (onToggleLock != null) {
                        SearchActionButton(
                            icon = Icons.Default.Lock,
                            label = if (isProtected) stringResource(R.string.search_actions_unlock)
                                    else stringResource(R.string.search_actions_lock),
                            isActive = isProtected,
                            onClick = { if (canInteract) onToggleLock() }
                        )
                    } else {
                        SearchActionButton(
                            icon = Icons.Default.Lock,
                            label = stringResource(R.string.search_actions_lock_no_category),
                            isActive = false,
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val baseModifier = modifier
        .fillMaxWidth()
        .onFocusChanged { isFocused = it.isFocused }
        .then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        )
        .border(
            width = if (isFocused) 2.dp else 0.dp,
            color = if (isFocused) AppColors.Focus else Color.Transparent,
            shape = RoundedCornerShape(12.dp)
        )
        .mouseClickable(onClick = onClick)

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = baseModifier,
        colors = ButtonDefaults.colors(
            containerColor = when {
                !enabled -> AppColors.Surface.copy(alpha = 0.3f)
                isFocused -> AppColors.Focus
                isActive -> AppColors.Warning.copy(alpha = 0.85f)
                else -> AppColors.Brand.copy(alpha = 0.70f)
            },
            contentColor = if (!enabled) AppColors.TextSecondary else Color.Black,
            disabledContainerColor = AppColors.Surface.copy(alpha = 0.3f),
            disabledContentColor = AppColors.TextSecondary
        ),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
