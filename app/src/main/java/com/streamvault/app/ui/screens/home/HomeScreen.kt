package com.streamvault.app.ui.screens.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.streamvault.app.ui.components.SearchInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import com.streamvault.app.ui.components.CategoryRow
import com.streamvault.app.ui.components.ChannelCard
import com.streamvault.app.ui.components.shell.ContentMetadataStrip
import com.streamvault.app.ui.components.shell.LiveChannelRowSurface
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.components.dialogs.CategoryOptionsDialog
import com.streamvault.app.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.dialogs.RenameGroupDialog
import com.streamvault.app.ui.components.ReorderTopBar
import com.streamvault.app.ui.components.SkeletonCard
import com.streamvault.app.ui.components.shimmerEffect
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Provider
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.screens.multiview.MultiViewViewModel
import com.streamvault.app.ui.screens.multiview.MultiViewPlannerDialog
import com.streamvault.app.navigation.Routes
import com.streamvault.domain.model.VirtualCategoryIds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FocusRestoreTarget {
    CATEGORY,
    CHANNEL
}


// ── Screen ─────────────────────────────────────────────────────────




// ── Screen ─────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onChannelClick: (Channel, Category?, Provider?) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    initialCategoryId: Long? = null,
    viewModel: HomeViewModel = hiltViewModel(),
    multiViewViewModel: MultiViewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isProMode = uiState.liveTvChannelMode == LiveTvChannelMode.PRO
    val isDenseMode = uiState.liveTvChannelMode != LiveTvChannelMode.COMFORTABLE
    val channelRowHeight = when (uiState.liveTvChannelMode) {
        LiveTvChannelMode.COMFORTABLE -> 92.dp
        LiveTvChannelMode.COMPACT -> 54.dp
        LiveTvChannelMode.PRO -> 52.dp
    }
    val channelListSpacing = when (uiState.liveTvChannelMode) {
        LiveTvChannelMode.COMFORTABLE -> 8.dp
        LiveTvChannelMode.COMPACT -> 2.dp
        LiveTvChannelMode.PRO -> 2.dp
    }
    val previewChannel = remember(uiState.filteredChannels, uiState.previewChannelId) {
        uiState.filteredChannels.firstOrNull { it.id == uiState.previewChannelId }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    // Split screen state
    val splitSlots by multiViewViewModel.slotsFlow.collectAsState()
    val hasSplitChannels = splitSlots.any { it != null }
    var showSplitManagerDialog by remember { mutableStateOf(false) }
    
    // Parental Control State
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingUnlockCategory by remember { mutableStateOf<Category?>(null) }
    var pendingUnlockChannel by remember { mutableStateOf<Channel?>(null) }
    var pendingLockToggleCategory by remember { mutableStateOf<Category?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(initialCategoryId) {
        viewModel.setPreferredInitialCategory(initialCategoryId)
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }
    
    if (showPinDialog) {
        PinDialog(
            onDismissRequest = { 
                showPinDialog = false
                pinError = null
                pendingUnlockCategory = null
                pendingUnlockChannel = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        showPinDialog = false
                        pinError = null
                        
                        pendingUnlockCategory?.let { category ->
                            // Use the sequence: Select (clears old unlocks) -> Unlock (adds this one)
                            viewModel.selectCategory(category)
                            viewModel.unlockCategory(category)
                            pendingUnlockCategory = null
                        }
                        
                        pendingUnlockChannel?.let { channel ->
                             viewModel.clearPreview()
                             onChannelClick(channel, uiState.selectedCategory, uiState.provider)
                             pendingUnlockChannel = null
                        }

                        pendingLockToggleCategory?.let { category ->
                            viewModel.toggleCategoryLock(category)
                            pendingLockToggleCategory = null
                        }
                    } else {
                        pinError = context.getString(R.string.home_incorrect_pin)
                    }
                }
            },
            error = pinError
        )
    }

    if (uiState.selectedCategoryForOptions != null) {
        val category = uiState.selectedCategoryForOptions!!
        CategoryOptionsDialog(
            category = category,
            onDismissRequest = { viewModel.dismissCategoryOptions() },
            onSetAsDefault = {
                viewModel.setDefaultCategory(category)
                viewModel.dismissCategoryOptions()
            },
            onRename = if (category.isVirtual && category.id !in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) {
                { viewModel.requestRenameGroup(category) }
            } else null,
            onToggleLock = {
                viewModel.dismissCategoryOptions()
                pendingLockToggleCategory = category
                showPinDialog = true
            },
            onDelete = if (category.isVirtual && category.id !in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) {
                {
                    viewModel.requestDeleteGroup(category)
                }
            } else null,
            onReorderChannels = if (category.isVirtual && category.id != VirtualCategoryIds.RECENT) {
                { viewModel.enterChannelReorderMode(category) }
            } else null
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_live_tv),
            subtitle = uiState.provider?.name,
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
            if (uiState.isChannelReorderMode) {
                ReorderTopBar(
                    categoryName = uiState.reorderCategory?.name ?: uiState.selectedCategory?.name ?: "Channels",
                    onSave = { viewModel.saveChannelReorder() },
                    onCancel = { viewModel.exitChannelReorderMode() },
                    subtitle = stringResource(R.string.live_reorder_subtitle)
                )
            }

            if (uiState.isLoading && uiState.categories.isEmpty()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Sidebar skeleton
                    Column(
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight()
                            .background(SurfaceElevated)
                            .padding(vertical = 16.dp, horizontal = 16.dp)
                    ) {
                        repeat(10) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .padding(vertical = 4.dp)
                                    .background(Color.DarkGray, RoundedCornerShape(8.dp))
                                    .shimmerEffect()
                            )
                        }
                    }
                    // Content skeleton
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(24.dp)
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 180.dp),
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(20) {
                                SkeletonCard(
                                    modifier = Modifier.aspectRatio(16f/9f)
                                )
                            }
                        }
                    }
                }
            } else {
                val channelSearchFocusRequester = remember { FocusRequester() }
                val categoryFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
                val channelFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
                var lastFocusedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
                var lastFocusedChannelId by rememberSaveable { mutableStateOf<Long?>(null) }
                var preferredRestoreTarget by rememberSaveable { mutableStateOf(FocusRestoreTarget.CHANNEL.name) }
                var shouldRestoreCategoryFocus by remember { mutableStateOf(false) }
                var shouldRestoreChannelFocus by remember { mutableStateOf(false) }

                LaunchedEffect(
                    uiState.showDialog,
                    showPinDialog,
                    uiState.showDeleteGroupDialog,
                    uiState.selectedCategoryForOptions
                ) {
                    val modalClosed =
                        !uiState.showDialog &&
                        !showPinDialog &&
                        !uiState.showDeleteGroupDialog &&
                        uiState.selectedCategoryForOptions == null

                    if (!modalClosed) return@LaunchedEffect

                    val canRestoreChannel = lastFocusedChannelId != null &&
                        uiState.filteredChannels.any { it.id == lastFocusedChannelId }
                    val canRestoreCategory = lastFocusedCategoryId != null &&
                        uiState.categories.any { it.id == lastFocusedCategoryId }
                    val restoreTarget = runCatching {
                        FocusRestoreTarget.valueOf(preferredRestoreTarget)
                    }.getOrDefault(FocusRestoreTarget.CHANNEL)

                    shouldRestoreCategoryFocus = restoreTarget == FocusRestoreTarget.CATEGORY && canRestoreCategory
                    shouldRestoreChannelFocus = when {
                        restoreTarget == FocusRestoreTarget.CATEGORY -> false
                        canRestoreChannel -> true
                        else -> false
                    }

                    if (!shouldRestoreChannelFocus && !shouldRestoreCategoryFocus && canRestoreCategory) {
                        shouldRestoreCategoryFocus = true
                    }
                }

                LaunchedEffect(shouldRestoreChannelFocus, uiState.filteredChannels) {
                    if (!shouldRestoreChannelFocus) return@LaunchedEffect
                    kotlinx.coroutines.delay(80)
                    val channelId = lastFocusedChannelId
                    val restored = channelId != null && runCatching {
                        channelFocusRequesters[channelId]?.requestFocus()
                    }.isSuccess
                    if (!restored) {
                        val fallbackId = uiState.filteredChannels.firstOrNull()?.id
                        fallbackId?.let { runCatching { channelFocusRequesters[it]?.requestFocus() } }
                    }
                    shouldRestoreChannelFocus = false
                }

                LaunchedEffect(shouldRestoreCategoryFocus, uiState.categories) {
                    if (!shouldRestoreCategoryFocus) return@LaunchedEffect
                    kotlinx.coroutines.delay(80)
                    val categoryId = lastFocusedCategoryId
                    if (categoryId != null) {
                        runCatching { categoryFocusRequesters[categoryId]?.requestFocus() }
                    }
                    shouldRestoreCategoryFocus = false
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    // Sidebar - Categories
                    val categorySearchFocusRequester = remember { FocusRequester() }
                    val focusManager = LocalFocusManager.current
                    
                    Column(
                        modifier = Modifier
                            .width(272.dp)
                            .fillMaxHeight()
                            .background(SurfaceElevated.copy(alpha = 0.88f), RoundedCornerShape(20.dp))
                            .padding(top = 10.dp)
                    ) {
                        // Sticky Header Part
                        Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                            Text(
                                text = stringResource(R.string.home_categories_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = OnSurface,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                            SearchInput(
                                value = uiState.categorySearchQuery,
                                onValueChange = { viewModel.updateCategorySearchQuery(it) },
                                placeholder = stringResource(R.string.home_search_categories),
                                focusRequester = categorySearchFocusRequester,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {

                        // ── Split Screen entry (under search bar) ──
                        if (hasSplitChannels) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                var isFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = { showSplitManagerDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { isFocused = it.isFocused },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color(0xFF0D2E16),
                                        focusedContainerColor = Color(0xFF1B5E20)
                                    ),
                                    border = ClickableSurfaceDefaults.border(
                                        border = androidx.tv.material3.Border(
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                Color(0xFF2E7D32)
                                            )
                                        )
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.action_split),
                                            fontSize = 14.sp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.multiview_nav),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.White,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = stringResource(R.string.label_slots_count, splitSlots.count { it != null }),
                                                style = androidx.compose.ui.text.TextStyle(
                                                    fontSize = 9.sp,
                                                    color = Color(0xFF81C784)
                                                )
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }

                        items(
                            items = uiState.categories.filter {
                                uiState.categorySearchQuery.isEmpty() ||
                                it.name.contains(uiState.categorySearchQuery, ignoreCase = true)
                            },
                            key = { it.id }
                        ) { category ->
                            val isLocked = (category.isAdult || category.isUserProtected) && uiState.parentalControlLevel == 1
                            val categoryFocusRequester = categoryFocusRequesters.getOrPut(category.id) { FocusRequester() }

                            CategoryItem(
                                category = category,
                                isSelected = category.id == uiState.selectedCategory?.id,
                                isLocked = isLocked,
                                focusRequester = categoryFocusRequester,
                                onClick = {
                                    if (isLocked) {
                                        pendingUnlockCategory = category
                                        showPinDialog = true
                                    } else {
                                        viewModel.selectCategory(category)
                                    }
                                },
                                onLongClick = {
                                    preferredRestoreTarget = FocusRestoreTarget.CATEGORY.name
                                    viewModel.showCategoryOptions(category)
                                },
                                onJumpToSearch = { categorySearchFocusRequester.requestFocus() },
                                onJumpToContent = {
                                    if (uiState.filteredChannels.isNotEmpty()) {
                                        val preferredChannelId = lastFocusedChannelId
                                            ?.takeIf { channelId -> uiState.filteredChannels.any { it.id == channelId } }
                                            ?: uiState.filteredChannels.first().id
                                        val focused = runCatching {
                                            channelFocusRequesters[preferredChannelId]?.requestFocus()
                                        }.isSuccess
                                        if (!focused) runCatching { channelSearchFocusRequester.requestFocus() }
                                    } else {
                                        runCatching { channelSearchFocusRequester.requestFocus() }
                                    }
                                },
                                onFocused = { lastFocusedCategoryId = category.id }
                            )
                        }
                    }
                }

                // Content - Channel Grid / Pro Preview
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(if (isProMode) 12.dp else 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(if (isProMode) 0.92f else 1f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, top = 2.dp, bottom = if (isDenseMode) 4.dp else 6.dp, end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(if (isDenseMode) 2.dp else 4.dp)
                        ) {
                            Text(
                                text = uiState.selectedCategory?.name ?: stringResource(R.string.home_all_channels),
                                style = if (isDenseMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                color = OnBackground
                            )
                            if (isDenseMode) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${stringResource(R.string.live_shell_provider)}: ${uiState.provider?.name ?: stringResource(R.string.playlist_no_provider)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OnSurfaceDim,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.live_channel_results, uiState.filteredChannels.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OnSurfaceDim,
                                        maxLines = 1
                                    )
                                }
                            } else {
                                ContentMetadataStrip(
                                    values = buildList {
                                        add("${stringResource(R.string.live_shell_provider)}: ${uiState.provider?.name ?: stringResource(R.string.playlist_no_provider)}")
                                        add(stringResource(R.string.live_channel_results, uiState.filteredChannels.size))
                                        uiState.lastVisitedCategory?.name?.let {
                                            add("${stringResource(R.string.live_shell_last_group)}: $it")
                                        }
                                    }
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(if (isDenseMode) 8.dp else 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SearchInput(
                                    value = uiState.channelSearchQuery,
                                    onValueChange = { viewModel.updateChannelSearchQuery(it) },
                                    placeholder = stringResource(R.string.home_search_channels),
                                    onSearch = { focusManager.clearFocus() },
                                    focusRequester = channelSearchFocusRequester,
                                    modifier = Modifier.width(if (isProMode) 270.dp else if (isDenseMode) 300.dp else 340.dp)
                                )
                                if (hasSplitChannels) {
                                    StatusPill(
                                        label = "${stringResource(R.string.live_shell_split)} ${splitSlots.count { it != null }}/4"
                                    )
                                }
                            }
                        }

                        if (uiState.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                    Text(
                                        text = stringResource(R.string.home_loading_channels),
                                        color = Color.White.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else if (!uiState.hasChannels) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.home_no_channels_found),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = OnBackground
                                    )
                                    Text(
                                        text = stringResource(R.string.home_no_channels_found_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceDim
                                    )
                                    val selectedCategory = uiState.selectedCategory
                                    if (selectedCategory?.isVirtual == true && selectedCategory.id == VirtualCategoryIds.FAVORITES) {
                                        Text(
                                            text = stringResource(R.string.home_add_favorites_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    } else if (selectedCategory?.isVirtual == true && selectedCategory.id == VirtualCategoryIds.RECENT) {
                                        Text(
                                            text = stringResource(R.string.home_recent_channels_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    }
                                }
                            }
                        } else {
                            var ignoreNextClick by remember { mutableStateOf(false) }
                            val channelListState = rememberLazyListState()

                            LaunchedEffect(ignoreNextClick) {
                                if (ignoreNextClick) {
                                    kotlinx.coroutines.delay(1000)
                                    ignoreNextClick = false
                                }
                            }

                            var draggingChannel by remember { mutableStateOf<Channel?>(null) }

                            LaunchedEffect(uiState.isChannelReorderMode) {
                                if (!uiState.isChannelReorderMode) {
                                    draggingChannel = null
                                }
                            }

                            LaunchedEffect(channelListState, uiState.filteredChannels, lastFocusedChannelId) {
                                snapshotFlow {
                                    channelListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                                        uiState.filteredChannels.getOrNull(item.index)?.id
                                    } to lastFocusedChannelId
                                }
                                    .distinctUntilChanged()
                                    .collect { (visibleIds, focusedId) ->
                                        viewModel.updateVisibleChannelWindow(visibleIds, focusedId)
                                    }
                            }

                            DisposableEffect(Unit) {
                                onDispose {
                                    viewModel.updateVisibleChannelWindow(emptyList(), null)
                                }
                            }

                            LazyColumn(
                                state = channelListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onPreviewKeyEvent { event ->
                                        if (uiState.isChannelReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                            if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                                                if (draggingChannel != null) {
                                                    draggingChannel = null
                                                    true
                                                } else {
                                                    viewModel.exitChannelReorderMode()
                                                    true
                                                }
                                            } else false
                                        } else false
                                    },
                                contentPadding = PaddingValues(
                                    start = 10.dp,
                                    end = 10.dp,
                                    bottom = if (isDenseMode) 8.dp else 12.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(channelListSpacing)
                            ) {
                                items(
                                    items = uiState.filteredChannels,
                                    key = { it.id }
                                ) { channel ->
                                    val isLocked = (channel.isAdult || channel.isUserProtected || (uiState.selectedCategory?.isUserProtected
                                        ?: false)) && uiState.parentalControlLevel == 1
                                    val isDraggingThis = draggingChannel == channel
                                    val channelFocusRequester = channelFocusRequesters.getOrPut(channel.id) { FocusRequester() }

                                    LiveChannelRowSurface(
                                        channel = channel,
                                        isLocked = isLocked,
                                        isReorderMode = uiState.isChannelReorderMode,
                                        isDragging = isDraggingThis,
                                        rowHeight = channelRowHeight,
                                        onClick = {
                                            if (uiState.isChannelReorderMode) {
                                                draggingChannel = if (isDraggingThis) null else channel
                                            } else if (ignoreNextClick) {
                                                ignoreNextClick = false
                                            } else if (!uiState.showDialog) {
                                                if (isLocked) {
                                                    pendingUnlockChannel = channel
                                                    showPinDialog = true
                                                } else if (isProMode) {
                                                    if (uiState.previewChannelId == channel.id) {
                                                        viewModel.clearPreview()
                                                        onChannelClick(channel, uiState.selectedCategory, uiState.provider)
                                                    } else {
                                                        viewModel.previewChannel(channel)
                                                    }
                                                } else {
                                                    onChannelClick(channel, uiState.selectedCategory, uiState.provider)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!uiState.isChannelReorderMode) {
                                                ignoreNextClick = true
                                                preferredRestoreTarget = FocusRestoreTarget.CHANNEL.name
                                                viewModel.onShowDialog(channel)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(channelFocusRequester)
                                            .onFocusChanged { focusState ->
                                                if (focusState.isFocused) {
                                                    lastFocusedChannelId = channel.id
                                                }
                                            }
                                            .onPreviewKeyEvent { event ->
                                                if (uiState.isChannelReorderMode && isDraggingThis && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                                    when (event.nativeKeyEvent.keyCode) {
                                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                            viewModel.moveChannelUp(channel); true
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                            viewModel.moveChannelUp(channel); true
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                            viewModel.moveChannelDown(channel); true
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                            viewModel.moveChannelDown(channel); true
                                                        }
                                                        else -> false
                                                    }
                                                } else false
                                            }
                                    )
                                }
                            }
                        }
                    }

                    if (isProMode) {
                        LivePreviewPane(
                            channel = previewChannel,
                            playerEngine = uiState.previewPlayerEngine,
                            isLoading = uiState.isPreviewLoading,
                            errorMessage = uiState.previewErrorMessage,
                            modifier = Modifier
                                .weight(1.08f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    if (uiState.showDialog && uiState.selectedChannelForDialog != null) {
        val channel = uiState.selectedChannelForDialog!!
        com.streamvault.app.ui.components.dialogs.AddToGroupDialog(
            contentTitle = channel.name,
            channel = channel,
            groups = uiState.categories.filter { it.isVirtual && it.id !in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT) },
            isFavorite = channel.isFavorite,
            memberOfGroups = uiState.dialogGroupMemberships,
            onDismiss = { viewModel.onDismissDialog() },
            onToggleFavorite = {
                if (channel.isFavorite) viewModel.removeFavorite(channel) else viewModel.addFavorite(channel)
            },
            onAddToGroup = { group -> viewModel.addToGroup(channel, group) },
            onRemoveFromGroup = { group -> viewModel.removeFromGroup(channel, group) },
            onCreateGroup = { name -> viewModel.createCustomGroup(name) },
            onNavigateToSplitScreen = { onNavigate(Routes.MULTI_VIEW) }
        )
    }

    if (uiState.showRenameGroupDialog && uiState.groupToRename != null) {
        RenameGroupDialog(
            initialName = uiState.groupToRename!!.name,
            errorMessage = uiState.renameGroupError,
            onDismissRequest = { viewModel.cancelRenameGroup() },
            onConfirm = { name -> viewModel.confirmRenameGroup(name) }
        )
    }

    // Split Screen manager dialog (opened from the header button)
    if (showSplitManagerDialog) {
        MultiViewPlannerDialog(
            pendingChannel = null,
            onDismiss = { showSplitManagerDialog = false },
            onLaunch = {
                showSplitManagerDialog = false
                onNavigate(Routes.MULTI_VIEW)
            },
            viewModel = multiViewViewModel
        )
    }

    if (uiState.showDeleteGroupDialog && uiState.groupToDelete != null) {
        val group = uiState.groupToDelete!!
        
        // Fix for ghost clicks: Debounce interaction for 500ms to ignore long-press release
        var canInteract by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(500)
            canInteract = true
        }

        val safeDismiss = {
            if (canInteract) viewModel.cancelDeleteGroup()
        }

        PremiumDialog(
            title = stringResource(R.string.home_delete_group_title),
            subtitle = stringResource(R.string.home_delete_group_body, group.name),
            onDismissRequest = safeDismiss,
            widthFraction = 0.36f,
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.home_delete_group_cancel),
                    onClick = safeDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.home_delete_group_confirm),
                    onClick = { if (canInteract) viewModel.confirmDeleteGroup() },
                    destructive = true
                )
            }
        )
    }
}

@Composable
private fun LivePreviewPane(
    channel: Channel?,
    playerEngine: com.streamvault.player.PlayerEngine?,
    isLoading: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.72f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.live_preview_title),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                val player = playerEngine?.getPlayerView()
                if (channel != null && player is Player && errorMessage == null) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                this.player = player
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            }
                        },
                        update = { view ->
                            if (view.player != player) {
                                view.player = player
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.live_preview_placeholder_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = OnBackground,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = errorMessage ?: stringResource(R.string.live_preview_placeholder_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                if (isLoading && channel != null) {
                    Row(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.live_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }

            if (channel != null) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground
                )
                channel.currentProgram?.let { program ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = program.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground
                        )
                        Text(
                            text = "${formatProgramTime(program.startTime)} - ${formatProgramTime(program.endTime)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        LinearProgressIndicator(
                            progress = { program.progressPercent() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = Primary,
                            trackColor = SurfaceHighlight
                        )
                        if (program.description.isNotBlank()) {
                            Text(
                                text = program.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface,
                                maxLines = 4,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                } ?: Text(
                    text = stringResource(R.string.live_preview_no_epg),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )

                Text(
                    text = stringResource(R.string.live_preview_open_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary
                )
            }
        }
    }
}

private fun formatProgramTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
}

@Composable
private fun CategoryItem(
    category: Category,
    isSelected: Boolean,
    isLocked: Boolean = false,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onJumpToSearch: () -> Unit,
    onJumpToContent: () -> Unit,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onJumpToSearch()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onJumpToContent()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.15f) else Color.Transparent,
            focusedContainerColor = SurfaceHighlight,
            contentColor = if (isSelected) Primary else OnSurface
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color.White),
                shape = RoundedCornerShape(10.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (isFocused) OnBackground else if (isSelected) Primary else OnSurface,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = category.count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocused) OnBackground else OnSurfaceDim,
                modifier = Modifier.padding(start = 10.dp)
            )

            if (isLocked) {
                Text(
                    text = stringResource(R.string.home_locked_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) OnBackground else OnSurfaceDim,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

// SearchInput moved to its own component file

@Composable
fun ReorderSidePanel(
    channels: List<Channel>,
    onMoveUp: (Channel) -> Unit,
    onMoveDown: (Channel) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var draggingChannel by remember { mutableStateOf<Channel?>(null) }
    
    // Focus requester to trap focus inside the panel
    val panelFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) {
        panelFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(SurfaceElevated)
            .padding(16.dp)
            .focusRequester(panelFocusRequester)
            .focusGroup() // Traps D-pad focus in this container
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Text(
                "Reorder Channels", 
                style = MaterialTheme.typography.titleMedium, 
                color = Primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                androidx.tv.material3.Button(
                    onClick = onSave,
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = Primary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_save), maxLines = 1) }

                androidx.tv.material3.Button(
                    onClick = onCancel,
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = OnSurface
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_cancel), maxLines = 1) }
            }
            
            // List
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(channels.size) { index ->
                    val channel = channels[index]
                    var isFocused by remember { mutableStateOf(false) }
                    val isDraggingThis = draggingChannel == channel
                    
                    Surface(
                        onClick = { 
                            draggingChannel = if (isDraggingThis) null else channel 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                            .onKeyEvent { event ->
                                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                    if (isDraggingThis) {
                                        when (event.nativeKeyEvent.keyCode) {
                                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                onMoveUp(channel)
                                                true
                                            }
                                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                onMoveDown(channel)
                                                true
                                            }
                                            else -> false
                                        }
                                    } else {
                                        // Trap focus left/right so we don't accidentally exit panel
                                        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                                            event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                                            true 
                                        } else {
                                            false
                                        }
                                    }
                                } else false
                            },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            focusedContainerColor = if (isDraggingThis) Primary else Primary.copy(alpha = 0.2f),
                            containerColor = if (isDraggingThis) Primary.copy(alpha = 0.5f) else Color.Transparent
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, FocusBorder),
                                shape = RoundedCornerShape(8.dp)
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isDraggingThis) {
                                Text(stringResource(R.string.action_move), color = Color.White, modifier = Modifier.padding(end = 8.dp))
                            }
                            Text(
                                "${index + 1}. ${channel.name}", 
                                modifier = Modifier.weight(1f), 
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isDraggingThis) Color.White else if (isFocused) OnBackground else OnSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            
            // Helper Text
            Text(
                if (draggingChannel != null) "UP/DOWN to move.\nOK to drop." else "OK to grab channel.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
