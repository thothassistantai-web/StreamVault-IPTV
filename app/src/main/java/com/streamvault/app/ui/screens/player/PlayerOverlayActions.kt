package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DIAGNOSTICS_EXTRA_VISIBLE_MS = 10_000L

fun PlayerViewModel.openChannelListOverlay() {
    clearNumericChannelInput()
    showChannelListOverlayFlow.value = true
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showControlsFlow.value = false
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.openCategoryListOverlay() {
    if (currentProviderId <= 0 || availableCategoriesFlow.value.isEmpty()) return
    showCategoryListOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.selectCategoryFromOverlay(category: Category) {
    showCategoryListOverlayFlow.value = false
    currentCategoryId = category.id
    activeCategoryIdFlow.value = category.id
    isVirtualCategory = category.isVirtual
    loadPlaylist(
        categoryId = category.id,
        providerId = currentProviderId,
        isVirtual = category.isVirtual,
        initialChannelId = currentContentId
    )
    openChannelListOverlay()
}

fun PlayerViewModel.openEpgOverlay() {
    clearNumericChannelInput()
    showEpgOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showControlsFlow.value = false
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.openChannelInfoOverlay() {
    clearNumericChannelInput()
    showChannelInfoOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showControlsFlow.value = false
    channelInfoHideJob?.cancel()
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.closeChannelInfoOverlay() {
    channelInfoHideJob?.cancel()
    showChannelInfoOverlayFlow.value = false
    if (!hasVisibleTransientLiveOverlay()) clearLiveOverlayAutoHide()
}

fun PlayerViewModel.closeOverlays() {
    clearNumericChannelInput()
    showChannelListOverlayFlow.value = false
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showDiagnosticsFlow.value = false
    channelInfoHideJob?.cancel()
    clearLiveOverlayAutoHide()
    clearDiagnosticsAutoHide()
}

fun PlayerViewModel.toggleDiagnostics() {
    showDiagnosticsFlow.value = !showDiagnosticsFlow.value
    if (showDiagnosticsFlow.value) {
        scheduleDiagnosticsAutoHide()
    } else {
        clearDiagnosticsAutoHide()
    }
}

fun PlayerViewModel.onLiveOverlayInteraction() {
    if (hasVisibleTransientLiveOverlay()) {
        scheduleLiveOverlayAutoHide()
    }
    if (showDiagnosticsFlow.value) {
        scheduleDiagnosticsAutoHide()
    }
}

fun PlayerViewModel.hideControlsAfterDelay() {
    controlsHideJob?.cancel()
    controlsHideJob = viewModelScope.launch {
        delay(playerControlsTimeoutMs)
        showControlsFlow.value = false
    }
}

fun PlayerViewModel.refreshControlsAutoHide() {
    if (showControlsFlow.value) {
        hideControlsAfterDelay()
    }
}

fun PlayerViewModel.cancelControlsAutoHide() {
    controlsHideJob?.cancel()
    controlsHideJob = null
}

internal fun PlayerViewModel.hideZapOverlayAfterDelay() {
    zapOverlayJob?.cancel()
    zapOverlayJob = viewModelScope.launch {
        delay(liveOverlayTimeoutMs)
        showZapOverlayFlow.value = false
    }
}

internal fun PlayerViewModel.hasVisibleTransientLiveOverlay(): Boolean =
    showChannelInfoOverlayFlow.value ||
        showChannelListOverlayFlow.value ||
        showEpgOverlayFlow.value

internal fun PlayerViewModel.clearLiveOverlayAutoHide() {
    liveOverlayHideJob?.cancel()
    liveOverlayHideJob = null
}

internal fun PlayerViewModel.clearDiagnosticsAutoHide() {
    diagnosticsHideJob?.cancel()
    diagnosticsHideJob = null
}

internal fun PlayerViewModel.scheduleLiveOverlayAutoHide() {
    if (currentContentType != ContentType.LIVE) {
        clearLiveOverlayAutoHide()
        return
    }
    liveOverlayHideJob?.cancel()
    liveOverlayHideJob = viewModelScope.launch {
        delay(liveOverlayTimeoutMs)
        showChannelInfoOverlayFlow.value = false
        showChannelListOverlayFlow.value = false
        showEpgOverlayFlow.value = false
    }
}

internal fun PlayerViewModel.scheduleDiagnosticsAutoHide() {
    if (currentContentType != ContentType.LIVE) {
        clearDiagnosticsAutoHide()
        return
    }
    diagnosticsHideJob?.cancel()
    diagnosticsHideJob = viewModelScope.launch {
        delay(diagnosticsTimeoutMs + DIAGNOSTICS_EXTRA_VISIBLE_MS)
        showDiagnosticsFlow.value = false
    }
}
