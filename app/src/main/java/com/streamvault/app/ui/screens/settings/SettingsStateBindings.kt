@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.streamvault.app.ui.screens.settings

import android.app.Application
import com.streamvault.app.R
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun observeSettingsPreferenceSnapshot(
    providerRepository: ProviderRepository,
    activeProviderIdFlow: Flow<Long?>,
    preferencesRepository: PreferencesRepository
): Flow<SettingsPreferenceSnapshot> {
    return combine(
        providerRepository.getProviders(),
        activeProviderIdFlow,
        preferencesRepository.parentalControlLevel,
        preferencesRepository.hasParentalPin
    ) { providers, activeId, level, hasParentalPin ->
        SettingsPreferenceSnapshot(
            providers = providers,
            activeProviderId = activeId,
            parentalControlLevel = level,
            hasParentalPin = hasParentalPin,
            appLanguage = "system",
            preferredAudioLanguage = "auto",
            playerMediaSessionEnabled = true,
            playerDecoderMode = DecoderMode.AUTO,
            playerPlaybackSpeed = 1f,
            playerControlsTimeoutSeconds = 5,
            playerLiveOverlayTimeoutSeconds = 4,
            playerNoticeTimeoutSeconds = 6,
            playerDiagnosticsTimeoutSeconds = 15,
            subtitleTextScale = 1f,
            subtitleTextColor = 0xFFFFFFFF.toInt(),
            subtitleBackgroundColor = 0x80000000.toInt(),
            wifiMaxVideoHeight = null,
            ethernetMaxVideoHeight = null,
            playerTimeshiftEnabled = false,
            playerTimeshiftDepthMinutes = 30,
            lastSpeedTestMegabits = null,
            lastSpeedTestTimestamp = null,
            lastSpeedTestTransport = null,
            lastSpeedTestRecommendedHeight = null,
            lastSpeedTestEstimated = false,
            isIncognitoMode = false,
            useXtreamTextClassification = true,
            xtreamBase64TextCompatibility = false,
            liveTvChannelMode = LiveTvChannelMode.PRO,
            showLiveSourceSwitcher = false,
            showAllChannelsCategory = true,
            showRecentChannelsCategory = true,
            liveTvCategoryFilters = emptyList(),
            liveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
            liveChannelNumberingMode = ChannelNumberingMode.GROUP,
            liveChannelGroupingMode = LiveChannelGroupingMode.RAW_VARIANTS,
            groupedChannelLabelMode = GroupedChannelLabelMode.HYBRID,
            liveVariantPreferenceMode = LiveVariantPreferenceMode.BALANCED,
            vodViewMode = VodViewMode.MODERN,
            guideDefaultCategoryId = VirtualCategoryIds.FAVORITES,
            guideDefaultCategoryOptions = emptyList(),
            preventStandbyDuringPlayback = true,
            zapAutoRevert = true,
            autoCheckAppUpdates = true,
            autoDownloadAppUpdates = false,
            lastAppUpdateCheckAt = null,
            cachedAppUpdateVersionName = null,
            cachedAppUpdateVersionCode = null,
            cachedAppUpdateReleaseUrl = null,
            cachedAppUpdateDownloadUrl = null,
            cachedAppUpdateReleaseNotes = "",
            cachedAppUpdatePublishedAt = null
        )
    }.combine(preferencesRepository.appLanguage) { snapshot, language ->
        snapshot.copy(appLanguage = language)
    }.combine(preferencesRepository.preferredAudioLanguage) { snapshot, preferredAudioLanguage ->
        snapshot.copy(preferredAudioLanguage = preferredAudioLanguage ?: "auto")
    }.combine(preferencesRepository.playerMediaSessionEnabled) { snapshot, mediaSessionEnabled ->
        snapshot.copy(playerMediaSessionEnabled = mediaSessionEnabled)
    }.combine(preferencesRepository.playerDecoderMode) { snapshot, decoderMode ->
        snapshot.copy(playerDecoderMode = decoderMode)
    }.combine(preferencesRepository.playerPlaybackSpeed) { snapshot, playerPlaybackSpeed ->
        snapshot.copy(playerPlaybackSpeed = playerPlaybackSpeed)
    }.combine(preferencesRepository.playerControlsTimeoutSeconds) { snapshot, timeoutSeconds ->
        snapshot.copy(playerControlsTimeoutSeconds = timeoutSeconds)
    }.combine(preferencesRepository.playerLiveOverlayTimeoutSeconds) { snapshot, timeoutSeconds ->
        snapshot.copy(playerLiveOverlayTimeoutSeconds = timeoutSeconds)
    }.combine(preferencesRepository.playerNoticeTimeoutSeconds) { snapshot, timeoutSeconds ->
        snapshot.copy(playerNoticeTimeoutSeconds = timeoutSeconds)
    }.combine(preferencesRepository.playerDiagnosticsTimeoutSeconds) { snapshot, timeoutSeconds ->
        snapshot.copy(playerDiagnosticsTimeoutSeconds = timeoutSeconds)
    }.combine(preferencesRepository.playerSubtitleTextScale) { snapshot, subtitleTextScale ->
        snapshot.copy(subtitleTextScale = subtitleTextScale)
    }.combine(preferencesRepository.playerSubtitleTextColor) { snapshot, subtitleTextColor ->
        snapshot.copy(subtitleTextColor = subtitleTextColor)
    }.combine(preferencesRepository.playerSubtitleBackgroundColor) { snapshot, subtitleBackgroundColor ->
        snapshot.copy(subtitleBackgroundColor = subtitleBackgroundColor)
    }.combine(preferencesRepository.playerWifiMaxVideoHeight) { snapshot, wifiMaxVideoHeight ->
        snapshot.copy(wifiMaxVideoHeight = wifiMaxVideoHeight)
    }.combine(preferencesRepository.playerEthernetMaxVideoHeight) { snapshot, ethernetMaxVideoHeight ->
        snapshot.copy(ethernetMaxVideoHeight = ethernetMaxVideoHeight)
    }.combine(preferencesRepository.playerTimeshiftEnabled) { snapshot, enabled ->
        snapshot.copy(playerTimeshiftEnabled = enabled)
    }.combine(preferencesRepository.playerTimeshiftDepthMinutes) { snapshot, depthMinutes ->
        snapshot.copy(playerTimeshiftDepthMinutes = depthMinutes)
    }.combine(preferencesRepository.lastSpeedTestMegabits) { snapshot, lastSpeedTestMegabits ->
        snapshot.copy(lastSpeedTestMegabits = lastSpeedTestMegabits)
    }.combine(preferencesRepository.lastSpeedTestTimestamp) { snapshot, lastSpeedTestTimestamp ->
        snapshot.copy(lastSpeedTestTimestamp = lastSpeedTestTimestamp)
    }.combine(preferencesRepository.lastSpeedTestTransport) { snapshot, lastSpeedTestTransport ->
        snapshot.copy(lastSpeedTestTransport = lastSpeedTestTransport)
    }.combine(preferencesRepository.lastSpeedTestRecommendedHeight) { snapshot, lastSpeedTestRecommendedHeight ->
        snapshot.copy(lastSpeedTestRecommendedHeight = lastSpeedTestRecommendedHeight)
    }.combine(preferencesRepository.lastSpeedTestEstimated) { snapshot, lastSpeedTestEstimated ->
        snapshot.copy(lastSpeedTestEstimated = lastSpeedTestEstimated)
    }.combine(preferencesRepository.isIncognitoMode) { snapshot, incognito ->
        snapshot.copy(isIncognitoMode = incognito)
    }.combine(preferencesRepository.useXtreamTextClassification) { snapshot, useTextClass ->
        snapshot.copy(useXtreamTextClassification = useTextClass)
    }.combine(preferencesRepository.xtreamBase64TextCompatibility) { snapshot, compatibilityEnabled ->
        snapshot.copy(xtreamBase64TextCompatibility = compatibilityEnabled)
    }.combine(preferencesRepository.liveTvChannelMode) { snapshot, liveTvChannelMode ->
        snapshot.copy(liveTvChannelMode = LiveTvChannelMode.fromStorage(liveTvChannelMode))
    }.combine(preferencesRepository.showLiveSourceSwitcher) { snapshot, showLiveSourceSwitcher ->
        snapshot.copy(showLiveSourceSwitcher = showLiveSourceSwitcher)
    }.combine(preferencesRepository.showAllChannelsCategory) { snapshot, showAllChannelsCategory ->
        snapshot.copy(showAllChannelsCategory = showAllChannelsCategory)
    }.combine(preferencesRepository.showRecentChannelsCategory) { snapshot, showRecentChannelsCategory ->
        snapshot.copy(showRecentChannelsCategory = showRecentChannelsCategory)
    }.combine(preferencesRepository.liveTvCategoryFilters) { snapshot, liveTvCategoryFilters ->
        snapshot.copy(liveTvCategoryFilters = liveTvCategoryFilters)
    }.combine(preferencesRepository.liveTvQuickFilterVisibility) { snapshot, visibilityMode ->
        snapshot.copy(
            liveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.fromStorage(visibilityMode)
        )
    }.combine(preferencesRepository.liveChannelNumberingMode) { snapshot, liveChannelNumberingMode ->
        snapshot.copy(liveChannelNumberingMode = liveChannelNumberingMode)
    }.combine(preferencesRepository.liveChannelGroupingMode) { snapshot, liveChannelGroupingMode ->
        snapshot.copy(liveChannelGroupingMode = liveChannelGroupingMode)
    }.combine(preferencesRepository.groupedChannelLabelMode) { snapshot, groupedChannelLabelMode ->
        snapshot.copy(groupedChannelLabelMode = groupedChannelLabelMode)
    }.combine(preferencesRepository.liveVariantPreferenceMode) { snapshot, liveVariantPreferenceMode ->
        snapshot.copy(liveVariantPreferenceMode = liveVariantPreferenceMode)
    }.combine(preferencesRepository.vodViewMode) { snapshot, vodViewMode ->
        snapshot.copy(vodViewMode = VodViewMode.fromStorage(vodViewMode))
    }.combine(preferencesRepository.guideDefaultCategoryId) { snapshot, guideDefaultCategoryId ->
        snapshot.copy(guideDefaultCategoryId = guideDefaultCategoryId ?: VirtualCategoryIds.FAVORITES)
    }.combine(preferencesRepository.preventStandbyDuringPlayback) { snapshot, preventStandby ->
        snapshot.copy(preventStandbyDuringPlayback = preventStandby)
    }.combine(preferencesRepository.zapAutoRevert) { snapshot, zapAutoRevert ->
        snapshot.copy(zapAutoRevert = zapAutoRevert)
    }.combine(preferencesRepository.autoCheckAppUpdates) { snapshot, autoCheckAppUpdates ->
        snapshot.copy(autoCheckAppUpdates = autoCheckAppUpdates)
    }.combine(preferencesRepository.autoDownloadAppUpdates) { snapshot, autoDownloadAppUpdates ->
        snapshot.copy(autoDownloadAppUpdates = autoDownloadAppUpdates)
    }.combine(preferencesRepository.lastAppUpdateCheckTimestamp) { snapshot, lastAppUpdateCheckAt ->
        snapshot.copy(lastAppUpdateCheckAt = lastAppUpdateCheckAt)
    }.combine(preferencesRepository.cachedAppUpdateVersionName) { snapshot, versionName ->
        snapshot.copy(cachedAppUpdateVersionName = versionName)
    }.combine(preferencesRepository.cachedAppUpdateVersionCode) { snapshot, versionCode ->
        snapshot.copy(cachedAppUpdateVersionCode = versionCode)
    }.combine(preferencesRepository.cachedAppUpdateReleaseUrl) { snapshot, releaseUrl ->
        snapshot.copy(cachedAppUpdateReleaseUrl = releaseUrl)
    }.combine(preferencesRepository.cachedAppUpdateDownloadUrl) { snapshot, downloadUrl ->
        snapshot.copy(cachedAppUpdateDownloadUrl = downloadUrl)
    }.combine(preferencesRepository.cachedAppUpdateReleaseNotes) { snapshot, releaseNotes ->
        snapshot.copy(cachedAppUpdateReleaseNotes = releaseNotes)
    }.combine(preferencesRepository.cachedAppUpdatePublishedAt) { snapshot, publishedAt ->
        snapshot.copy(cachedAppUpdatePublishedAt = publishedAt)
    }
}

internal fun SettingsUiState.applyPreferenceSnapshot(snapshot: SettingsPreferenceSnapshot): SettingsUiState {
    val cachedAppUpdate = snapshot.toCachedAppUpdateUiModel()
    return copy(
        providers = snapshot.providers,
        activeProviderId = snapshot.activeProviderId,
        parentalControlLevel = snapshot.parentalControlLevel,
        hasParentalPin = snapshot.hasParentalPin,
        appLanguage = snapshot.appLanguage,
        preferredAudioLanguage = snapshot.preferredAudioLanguage,
        playerMediaSessionEnabled = snapshot.playerMediaSessionEnabled,
        playerDecoderMode = snapshot.playerDecoderMode,
        playerPlaybackSpeed = snapshot.playerPlaybackSpeed,
        playerControlsTimeoutSeconds = snapshot.playerControlsTimeoutSeconds,
        playerLiveOverlayTimeoutSeconds = snapshot.playerLiveOverlayTimeoutSeconds,
        playerNoticeTimeoutSeconds = snapshot.playerNoticeTimeoutSeconds,
        playerDiagnosticsTimeoutSeconds = snapshot.playerDiagnosticsTimeoutSeconds,
        subtitleTextScale = snapshot.subtitleTextScale,
        subtitleTextColor = snapshot.subtitleTextColor,
        subtitleBackgroundColor = snapshot.subtitleBackgroundColor,
        wifiMaxVideoHeight = snapshot.wifiMaxVideoHeight,
        ethernetMaxVideoHeight = snapshot.ethernetMaxVideoHeight,
        playerTimeshiftEnabled = snapshot.playerTimeshiftEnabled,
        playerTimeshiftDepthMinutes = snapshot.playerTimeshiftDepthMinutes,
        lastSpeedTest = snapshot.lastSpeedTestMegabits?.let {
            InternetSpeedTestUiModel(
                megabitsPerSecond = it,
                measuredAtMs = snapshot.lastSpeedTestTimestamp ?: 0L,
                transportLabel = snapshot.lastSpeedTestTransport ?: InternetSpeedTestTransport.UNKNOWN.name,
                recommendedMaxVideoHeight = snapshot.lastSpeedTestRecommendedHeight,
                isEstimated = snapshot.lastSpeedTestEstimated
            )
        },
        isIncognitoMode = snapshot.isIncognitoMode,
        useXtreamTextClassification = snapshot.useXtreamTextClassification,
        xtreamBase64TextCompatibility = snapshot.xtreamBase64TextCompatibility,
        liveTvChannelMode = snapshot.liveTvChannelMode,
        showLiveSourceSwitcher = snapshot.showLiveSourceSwitcher,
        showAllChannelsCategory = snapshot.showAllChannelsCategory,
        showRecentChannelsCategory = snapshot.showRecentChannelsCategory,
        liveTvCategoryFilters = snapshot.liveTvCategoryFilters,
        liveTvQuickFilterVisibilityMode = snapshot.liveTvQuickFilterVisibilityMode,
        liveChannelNumberingMode = snapshot.liveChannelNumberingMode,
        liveChannelGroupingMode = snapshot.liveChannelGroupingMode,
        groupedChannelLabelMode = snapshot.groupedChannelLabelMode,
        liveVariantPreferenceMode = snapshot.liveVariantPreferenceMode,
        vodViewMode = snapshot.vodViewMode,
        guideDefaultCategoryId = snapshot.guideDefaultCategoryId,
        guideDefaultCategoryOptions = guideDefaultCategoryOptions,
        preventStandbyDuringPlayback = snapshot.preventStandbyDuringPlayback,
        zapAutoRevert = snapshot.zapAutoRevert,
        autoCheckAppUpdates = snapshot.autoCheckAppUpdates,
        autoDownloadAppUpdates = snapshot.autoDownloadAppUpdates,
        appUpdate = cachedAppUpdate.copy(
            downloadStatus = appUpdate.downloadStatus,
            downloadedVersionName = appUpdate.downloadedVersionName,
            errorMessage = appUpdate.errorMessage
        )
    )
}

internal fun observeProviderDiagnostics(
    providerRepository: ProviderRepository,
    syncMetadataRepository: SyncMetadataRepository,
    application: Application
): Flow<Map<Long, ProviderDiagnosticsUiModel>> {
    return providerRepository.getProviders()
        .flatMapLatest { providers ->
            if (providers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    providers.map { provider ->
                        syncMetadataRepository.observeMetadata(provider.id).map { metadata ->
                            provider.id to ProviderDiagnosticsUiModel(
                                lastSyncStatus = metadata?.lastSyncStatus ?: "NONE",
                                lastLiveSync = metadata?.lastLiveSync ?: 0L,
                                lastMovieSync = metadata?.lastMovieSync ?: 0L,
                                lastMovieAttempt = metadata?.lastMovieAttempt ?: 0L,
                                lastMovieSuccess = metadata?.lastMovieSuccess ?: 0L,
                                lastMoviePartial = metadata?.lastMoviePartial ?: 0L,
                                lastSeriesSync = metadata?.lastSeriesSync ?: 0L,
                                lastEpgSync = metadata?.lastEpgSync ?: 0L,
                                liveCount = metadata?.liveCount ?: 0,
                                movieCount = metadata?.movieCount ?: 0,
                                seriesCount = metadata?.seriesCount ?: 0,
                                epgCount = metadata?.epgCount ?: 0,
                                movieSyncMode = metadata?.movieSyncMode ?: VodSyncMode.UNKNOWN,
                                movieWarningsCount = metadata?.movieWarningsCount ?: 0,
                                movieCatalogStale = metadata?.movieCatalogStale ?: false,
                                liveSequentialFailuresRemembered = metadata?.liveSequentialFailuresRemembered ?: false,
                                liveHealthySyncStreak = metadata?.liveHealthySyncStreak ?: 0,
                                movieParallelFailuresRemembered = metadata?.movieParallelFailuresRemembered ?: false,
                                movieHealthySyncStreak = metadata?.movieHealthySyncStreak ?: 0,
                                seriesSequentialFailuresRemembered = metadata?.seriesSequentialFailuresRemembered ?: false,
                                seriesHealthySyncStreak = metadata?.seriesHealthySyncStreak ?: 0,
                                capabilitySummary = buildCapabilitySummary(application, provider),
                                sourceLabel = provider.sourceLabel(),
                                expirySummary = provider.expirySummary(),
                                connectionSummary = "${provider.maxConnections} connection(s)",
                                archiveSummary = provider.archiveSummary()
                            )
                        }
                    }
                ) { pairs ->
                    pairs.toMap()
                }
            }
        }
}

internal fun observeCategoryManagement(
    activeProviderIdFlow: Flow<Long?>,
    preferencesRepository: PreferencesRepository,
    categoryRepository: CategoryRepository
): Flow<CategoryManagementSnapshot> {
    return activeProviderIdFlow.flatMapLatest { providerId ->
        if (providerId == null) {
            flowOf(CategoryManagementSnapshot())
        } else {
            combine(
                observeCategorySortModes(providerId, preferencesRepository),
                categoryRepository.getCategories(providerId),
                observeHiddenCategoryIdsByType(providerId, preferencesRepository)
            ) { sortModes, categories, hiddenByType ->
                CategoryManagementSnapshot(
                    categorySortModes = sortModes,
                    hiddenCategories = categories
                        .filter { category -> category.id in hiddenByType[category.type].orEmpty() }
                        .sortedWith(compareBy<Category>({ it.type.ordinal }, { it.name.lowercase() }))
                )
            }
        }
    }
}

private fun observeCategorySortModes(
    providerId: Long,
    preferencesRepository: PreferencesRepository
): Flow<Map<ContentType, CategorySortMode>> {
    return combine(
        preferencesRepository.getCategorySortMode(providerId, ContentType.LIVE),
        preferencesRepository.getCategorySortMode(providerId, ContentType.MOVIE),
        preferencesRepository.getCategorySortMode(providerId, ContentType.SERIES)
    ) { liveSort, movieSort, seriesSort ->
        mapOf(
            ContentType.LIVE to liveSort,
            ContentType.MOVIE to movieSort,
            ContentType.SERIES to seriesSort
        )
    }
}

private fun observeHiddenCategoryIdsByType(
    providerId: Long,
    preferencesRepository: PreferencesRepository
): Flow<Map<ContentType, Set<Long>>> {
    return combine(
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.LIVE),
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.MOVIE),
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.SERIES)
    ) { hiddenLive, hiddenMovies, hiddenSeries ->
        mapOf(
            ContentType.LIVE to hiddenLive,
            ContentType.MOVIE to hiddenMovies,
            ContentType.SERIES to hiddenSeries
        )
    }
}

private fun buildCapabilitySummary(application: Application, provider: Provider): String {
    return when (provider.type) {
        ProviderType.XTREAM_CODES -> {
            if (provider.epgUrl.isNotBlank()) {
                application.getString(R.string.settings_capability_xtream_with_epg)
            } else {
                application.getString(R.string.settings_capability_xtream_without_epg)
            }
        }
        ProviderType.M3U -> {
            if (provider.epgUrl.isNotBlank()) {
                application.getString(R.string.settings_capability_m3u_with_epg)
            } else {
                application.getString(R.string.settings_capability_m3u_without_epg)
            }
        }
        ProviderType.STALKER_PORTAL -> {
            if (provider.epgUrl.isNotBlank()) {
                "Portal catalog with MAC auth, XMLTV import, and on-demand playback link resolution."
            } else {
                "Portal catalog with MAC auth and on-demand guide/playback resolution."
            }
        }
    }
}

private fun Provider.sourceLabel(): String = when (type) {
    ProviderType.XTREAM_CODES -> "Xtream Codes"
    ProviderType.M3U -> "M3U Playlist"
    ProviderType.STALKER_PORTAL -> "Stalker/MAG Portal"
}

private fun Provider.expirySummary(): String {
    val expirationDate = expirationDate
    return when {
        expirationDate == null -> "Expiry unknown"
        expirationDate == Long.MAX_VALUE -> "No expiry reported"
        expirationDate < System.currentTimeMillis() -> "Expired"
        else -> {
            val formatter = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            "Active until ${formatter.format(Date(expirationDate))}"
        }
    }
}

private fun Provider.archiveSummary(): String = when (type) {
    ProviderType.XTREAM_CODES -> "Catch-up depends on provider archive flags and replay stream ids."
    ProviderType.M3U -> {
        if (epgUrl.isBlank()) {
            "M3U replay is limited without guide coverage."
        } else {
            "M3U replay depends on channel templates and guide alignment."
        }
    }
    ProviderType.STALKER_PORTAL -> {
        if (epgUrl.isBlank()) {
            "Stalker replay depends on portal support; guide falls back to portal data."
        } else {
            "Stalker replay depends on portal support with optional XMLTV coverage."
        }
    }
}
