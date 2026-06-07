package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutProfile

internal fun LazyListScope.settingsBrowsingSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: android.content.Context,
    appLandingDestinationLabel: String,
    guideDefaultCategoryLabel: String,
    timeFormatLabel: String,
    appLanguageLabel: String,
    onShowLiveTvModeDialogChange: (Boolean) -> Unit,
    onShowLiveTvFiltersDialogChange: (Boolean) -> Unit,
    onShowLiveTvQuickFilterVisibilityDialogChange: (Boolean) -> Unit,
    onShowLiveChannelNumberingDialogChange: (Boolean) -> Unit,
    onShowLiveChannelGroupingDialogChange: (Boolean) -> Unit,
    onShowGroupedChannelLabelDialogChange: (Boolean) -> Unit,
    onShowLiveVariantPreferenceDialogChange: (Boolean) -> Unit,
    onShowLandingScreenDialogChange: (Boolean) -> Unit,
    onShowGuideDefaultCategoryDialogChange: (Boolean) -> Unit,
    onShowTimeFormatDialogChange: (Boolean) -> Unit,
    onShowVodViewModeDialogChange: (Boolean) -> Unit,
    onCategorySortDialogTypeChange: (String?) -> Unit,
    onShowLanguageDialogChange: (Boolean) -> Unit,
    onRemoteShortcutDialogTargetChange: (RemoteShortcutDialogTarget?) -> Unit
) {
    item {
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_tv_channel_mode),
            value = stringResource(uiState.liveTvChannelMode.labelResId()),
            onClick = { onShowLiveTvModeDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_default_landing_screen),
            value = appLandingDestinationLabel,
            onClick = { onShowLandingScreenDialogChange(true) }
        )
        TvClickableSurface(
            onClick = { viewModel.setShowLiveSourceSwitcher(!uiState.showLiveSourceSwitcher) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_show_live_source_switcher), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_show_live_source_switcher_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.showLiveSourceSwitcher, onCheckedChange = { viewModel.setShowLiveSourceSwitcher(it) })
            }
        }
        TvClickableSurface(
            onClick = { viewModel.setShowAllChannelsCategory(!uiState.showAllChannelsCategory) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_show_all_channels_category), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_show_all_channels_category_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.showAllChannelsCategory, onCheckedChange = { viewModel.setShowAllChannelsCategory(it) })
            }
        }
        TvClickableSurface(
            onClick = { viewModel.setShowRecentChannelsCategory(!uiState.showRecentChannelsCategory) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_show_recent_channels_category), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_show_recent_channels_category_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.showRecentChannelsCategory, onCheckedChange = { viewModel.setShowRecentChannelsCategory(it) })
            }
        }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_tv_quick_filters),
            value = formatLiveTvQuickFiltersValue(uiState.liveTvCategoryFilters, context),
            onClick = { onShowLiveTvFiltersDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_tv_quick_filter_visibility),
            value = stringResource(uiState.liveTvQuickFilterVisibilityMode.labelResId()),
            onClick = { onShowLiveTvQuickFilterVisibilityDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_channel_numbering_mode),
            value = stringResource(uiState.liveChannelNumberingMode.labelResId()),
            onClick = { onShowLiveChannelNumberingDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_channel_grouping_mode),
            value = stringResource(uiState.liveChannelGroupingMode.labelResId()),
            onClick = { onShowLiveChannelGroupingDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_grouped_channel_label_mode),
            value = stringResource(uiState.groupedChannelLabelMode.labelResId()),
            onClick = { onShowGroupedChannelLabelDialogChange(true) },
            enabled = uiState.liveChannelGroupingMode == LiveChannelGroupingMode.GROUPED,
            indent = 24.dp
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_variant_preference_mode),
            value = stringResource(uiState.liveVariantPreferenceMode.labelResId()),
            onClick = { onShowLiveVariantPreferenceDialogChange(true) },
            enabled = uiState.liveChannelGroupingMode == LiveChannelGroupingMode.GROUPED,
            indent = 24.dp
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_guide_default_category),
            value = guideDefaultCategoryLabel,
            onClick = { onShowGuideDefaultCategoryDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_time_format),
            value = timeFormatLabel,
            onClick = { onShowTimeFormatDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_vod_view_mode),
            value = stringResource(uiState.vodViewMode.labelResId()),
            onClick = { onShowVodViewModeDialogChange(true) }
        )
        SwitchSettingsRow(
            label = stringResource(R.string.settings_vod_infinite_scroll),
            value = stringResource(
                if (uiState.vodInfiniteScroll) R.string.settings_vod_infinite_scroll_on
                else R.string.settings_vod_infinite_scroll_off
            ),
            checked = uiState.vodInfiniteScroll,
            onCheckedChange = { viewModel.setVodInfiniteScroll(it) },
            enabled = uiState.vodViewMode == VodViewMode.MODERN,
            indent = 24.dp
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        ClickableSettingsRow(
            label = stringResource(R.string.settings_category_sort_live),
            value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.LIVE] ?: CategorySortMode.DEFAULT, context),
            onClick = { onCategorySortDialogTypeChange(ContentType.LIVE.name) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_category_sort_movies),
            value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.MOVIE] ?: CategorySortMode.DEFAULT, context),
            onClick = { onCategorySortDialogTypeChange(ContentType.MOVIE.name) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_category_sort_series),
            value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.SERIES] ?: CategorySortMode.DEFAULT, context),
            onClick = { onCategorySortDialogTypeChange(ContentType.SERIES.name) }
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { onShowLanguageDialogChange(true) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.settings_app_language), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                Text(text = appLanguageLabel, style = MaterialTheme.typography.bodyMedium, color = Primary)
            }
        }
    }
    item {
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(R.string.settings_remote_shortcuts_title),
            style = MaterialTheme.typography.titleSmall,
            color = OnSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
        Text(
            text = stringResource(R.string.settings_remote_shortcuts_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = OnBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Text(
            text = stringResource(R.string.settings_remote_profile_global),
            style = MaterialTheme.typography.labelLarge,
            color = Primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
    remoteShortcutRows(
        profile = RemoteShortcutProfile.GLOBAL,
        uiState = uiState,
        context = context,
        onRemoteShortcutDialogTargetChange = onRemoteShortcutDialogTargetChange
    )
    item {
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(R.string.settings_remote_profile_playback),
            style = MaterialTheme.typography.labelLarge,
            color = Primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
    remoteShortcutRows(
        profile = RemoteShortcutProfile.PLAYBACK,
        uiState = uiState,
        context = context,
        onRemoteShortcutDialogTargetChange = onRemoteShortcutDialogTargetChange
    )
    item {
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(R.string.settings_remote_profile_browse),
            style = MaterialTheme.typography.labelLarge,
            color = Primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
    remoteShortcutRows(
        profile = RemoteShortcutProfile.BROWSE,
        uiState = uiState,
        context = context,
        onRemoteShortcutDialogTargetChange = onRemoteShortcutDialogTargetChange
    )
}

private fun LazyListScope.remoteShortcutRows(
    profile: RemoteShortcutProfile,
    uiState: SettingsUiState,
    context: android.content.Context,
    onRemoteShortcutDialogTargetChange: (RemoteShortcutDialogTarget?) -> Unit
) {
    RemoteColorButton.entries.forEach { button ->
        item {
            val selection = uiState.remoteShortcutPreferences.selection(profile, button)
            ClickableSettingsRow(
                label = stringResource(button.labelResId()),
                value = formatRemoteShortcutSelectionLabel(selection, profile, button, context),
                onClick = { onRemoteShortcutDialogTargetChange(RemoteShortcutDialogTarget(profile, button)) },
                indent = if (profile == RemoteShortcutProfile.GLOBAL) 0.dp else 16.dp
            )
        }
    }
}
