package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.theme.*
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Locale

@Composable
internal fun CombinedM3uProfilesCard(
    profiles: List<CombinedM3uProfile>,
    availableProviders: List<Provider>,
    selectedProfileId: Long?,
    activeLiveSource: ActiveLiveSource?,
    onSelectProfile: (Long) -> Unit,
    onCreateProfile: () -> Unit,
    onActivateProfile: (Long) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onRenameProfile: (Long) -> Unit,
    onAddProvider: (Long) -> Unit,
    onRemoveProvider: (Long, Long) -> Unit,
    onToggleProviderEnabled: (Long, Long, Boolean) -> Unit,
    onMoveProvider: (Long, Long, Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Combined M3U", style = MaterialTheme.typography.titleMedium, color = OnSurface)
                    Text(
                        "Merge selected M3U playlists into one Live TV and EPG source.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                CompactSettingsActionChip(
                    label = "Create Combined",
                    accent = Primary,
                    onClick = onCreateProfile
                )
            }

            if (profiles.isEmpty()) {
                Text("No combined M3U sources yet.", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        val isActive = (activeLiveSource as? ActiveLiveSource.CombinedM3uSource)?.profileId == profile.id
                        ProviderChip(
                            title = profile.name,
                            subtitle = buildString {
                                append("${profile.members.count { it.enabled }}/${profile.members.size} playlist(s)")
                                if (isActive) append(" • Active")
                                if (profile.members.none { it.enabled }) append(" • Empty")
                            },
                            isSelected = selectedProfileId == profile.id,
                            isActive = isActive,
                            onClick = { onSelectProfile(profile.id) }
                        )
                    }
                }

                val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.first()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactSettingsActionChip(
                            label = "Use For Live TV",
                            accent = Primary,
                            onClick = { onActivateProfile(selectedProfile.id) }
                        )
                        CompactSettingsActionChip(
                            label = "Rename",
                            accent = OnBackground,
                            onClick = { onRenameProfile(selectedProfile.id) }
                        )
                        CompactSettingsActionChip(
                            label = "Add Playlist",
                            accent = OnBackground,
                            onClick = { onAddProvider(selectedProfile.id) }
                        )
                        CompactSettingsActionChip(
                            label = "Delete",
                            accent = ErrorColor,
                            onClick = { onDeleteProfile(selectedProfile.id) }
                        )
                    }

                    Text(
                        text = "${selectedProfile.members.count { it.enabled }} of ${selectedProfile.members.size} playlists enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )

                    if (selectedProfile.members.isEmpty()) {
                        Text(
                            text = "This combined source has no playlists yet. Add at least one M3U playlist before using it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    } else if (selectedProfile.members.none { it.enabled }) {
                        Text(
                            text = "All playlists in this combined source are disabled. Enable at least one to use it in Live TV.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }

                    selectedProfile.members
                        .sortedBy { it.priority }
                        .forEachIndexed { index, member ->
                        val providerName = member.providerName.ifBlank {
                            availableProviders.firstOrNull { it.id == member.providerId }?.name ?: "Playlist ${member.providerId}"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(providerName, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                Text(
                                    if (member.enabled) "Enabled in merged source" else "Disabled in merged source",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CompactSettingsActionChip(
                                    label = "Up",
                                    accent = OnBackground,
                                    enabled = index > 0,
                                    onClick = { onMoveProvider(selectedProfile.id, member.providerId, true) }
                                )
                                CompactSettingsActionChip(
                                    label = "Down",
                                    accent = OnBackground,
                                    enabled = index < selectedProfile.members.lastIndex,
                                    onClick = { onMoveProvider(selectedProfile.id, member.providerId, false) }
                                )
                                Switch(
                                    checked = member.enabled,
                                    onCheckedChange = { onToggleProviderEnabled(selectedProfile.id, member.providerId, it) }
                                )
                                CompactSettingsActionChip(
                                    label = "Remove",
                                    accent = ErrorColor,
                                    onClick = { onRemoveProvider(selectedProfile.id, member.providerId) }
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
internal fun RenameCombinedM3uDialog(
    profile: CombinedM3uProfile,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }

    PremiumDialog(
        title = "Rename Combined M3U",
        subtitle = "Update the name shown in Live TV and provider settings.",
        onDismissRequest = onDismiss,
        widthFraction = 0.48f,
        content = {
            EpgSourceTextField(
                value = name,
                onValueChange = { updated -> name = updated },
                placeholder = "Combined source name"
            )
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
            PremiumDialogFooterButton(
                label = "Save",
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank(),
                emphasized = true
            )
        }
    )
}

@Composable
internal fun CreateCombinedM3uDialog(
    providers: List<Provider>,
    onDismiss: () -> Unit,
    onCreate: (String, List<Long>) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedProviderIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    val m3uProviders = remember(providers) { providers.filter { it.type == ProviderType.M3U } }
    val effectiveName = remember(name, selectedProviderIds, m3uProviders) {
        val manualName = name.trim()
        if (manualName.isNotBlank()) {
            manualName
        } else {
            val selectedProviders = m3uProviders.filter { it.id in selectedProviderIds }
            when {
                selectedProviders.isEmpty() -> ""
                selectedProviders.size == 1 -> "${selectedProviders.first().name} Mix"
                selectedProviders.size == 2 -> "${selectedProviders[0].name} + ${selectedProviders[1].name}"
                else -> "${selectedProviders.first().name} + ${selectedProviders.size - 1} More"
            }
        }
    }

    PremiumDialog(
        title = "Create Combined M3U",
        subtitle = "Pick the M3U playlists you want to browse together in Live TV and guide.",
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            EpgSourceTextField(
                value = name,
                onValueChange = { updated -> name = updated },
                placeholder = effectiveName.ifBlank { "Combined source name" }
            )

            if (m3uProviders.isEmpty()) {
                Text(
                    text = "No M3U playlists are available yet. Add at least one playlist first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    m3uProviders.forEach { provider ->
                        val isSelected = provider.id in selectedProviderIds
                        TvClickableSurface(
                            onClick = {
                                selectedProviderIds = if (isSelected) {
                                    selectedProviderIds - provider.id
                                } else {
                                    selectedProviderIds + provider.id
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Primary.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
                                focusedContainerColor = Primary.copy(alpha = 0.24f)
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = provider.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnSurface
                                    )
                                    Text(
                                        text = if (isSelected) "Included in this combined source" else "Press to include this playlist",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceDim
                                    )
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = null
                                )
                            }
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
            PremiumDialogFooterButton(
                label = "Create",
                onClick = { onCreate(effectiveName, selectedProviderIds.toList()) },
                enabled = selectedProviderIds.isNotEmpty() && effectiveName.isNotBlank(),
                emphasized = true
            )
        }
    )
}

@Composable
internal fun AddCombinedProviderDialog(
    profile: CombinedM3uProfile,
    availableProviders: List<Provider>,
    onDismiss: () -> Unit,
    onAddProvider: (Long) -> Unit
) {
    val candidateProviders = remember(profile, availableProviders) {
        availableProviders.filter { provider -> profile.members.none { it.providerId == provider.id } }
    }
    var selectedProviderId by rememberSaveable(profile.id) { mutableStateOf(candidateProviders.firstOrNull()?.id) }
    PremiumDialog(
        title = "Add Playlist To ${profile.name}",
        subtitle = "Select another M3U playlist to include in this combined source.",
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            if (candidateProviders.isEmpty()) {
                Text(
                    text = "All M3U playlists are already in this combined source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    candidateProviders.forEach { provider ->
                        val isSelected = selectedProviderId == provider.id
                        TvClickableSurface(
                            onClick = { selectedProviderId = provider.id },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Primary.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
                                focusedContainerColor = Primary.copy(alpha = 0.22f)
                            ),
                            border = ClickableSurfaceDefaults.border(
                                border = Border(
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                                focusedBorder = Border(
                                    border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedProviderId = provider.id }
                                )
                            }
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
            PremiumDialogFooterButton(
                label = "Add",
                onClick = { selectedProviderId?.let(onAddProvider) },
                enabled = selectedProviderId != null && candidateProviders.isNotEmpty(),
                emphasized = true
            )
        }
    )
}

@Composable
private fun ProviderChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.16f) else Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.24f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(
                if (isActive) "$subtitle • Active" else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) Primary else OnSurfaceDim
            )
        }
    }
}

private fun availableSyncSelections(provider: Provider): List<ProviderSyncSelection> = buildList {
    add(ProviderSyncSelection.TV)
    add(ProviderSyncSelection.MOVIES)
    if (provider.type == ProviderType.XTREAM_CODES) {
        add(ProviderSyncSelection.SERIES)
    }
    add(ProviderSyncSelection.EPG)
}

@Composable
internal fun ProviderSyncOptionsDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onSelect: (ProviderSyncSelection?) -> Unit
) {
    PremiumDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_sync_dialog_title, provider.name),
        content = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.settings_sync_dialog_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
            SyncOptionButton(stringResource(R.string.settings_sync_option_fast)) {
                onSelect(ProviderSyncSelection.FAST)
            }
            availableSyncSelections(provider).forEach { option ->
                SyncOptionButton(text = syncSelectionLabel(option)) {
                    onSelect(option)
                }
            }
            OutlinedButton(
                onClick = { onSelect(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_sync_option_custom))
            }
        }
    }
    )
}

@Composable
internal fun ProviderCustomSyncDialog(
    provider: Provider,
    selected: Set<ProviderSyncSelection>,
    onToggle: (ProviderSyncSelection) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    PremiumDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_sync_custom_title, provider.name),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_sync_custom_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface
                )
                availableSyncSelections(provider).forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(
                            checked = option in selected,
                            onCheckedChange = { onToggle(option) }
                        )
                        Text(
                            text = syncSelectionLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground
                        )
                    }
                }
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_sync_btn),
                    onClick = onConfirm,
                    enabled = selected.isNotEmpty()
                )
            }
        }
    )
}

@Composable
private fun SyncOptionButton(
    text: String,
    onClick: () -> Unit
) {
    TvButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

@Composable
private fun syncSelectionLabel(selection: ProviderSyncSelection): String = when (selection) {
    ProviderSyncSelection.ALL -> stringResource(R.string.settings_sync_option_all)
    ProviderSyncSelection.FAST -> stringResource(R.string.settings_sync_option_fast)
    ProviderSyncSelection.TV -> stringResource(R.string.settings_sync_option_tv)
    ProviderSyncSelection.MOVIES -> stringResource(R.string.settings_sync_option_movies)
    ProviderSyncSelection.SERIES -> stringResource(R.string.settings_sync_option_series)
    ProviderSyncSelection.EPG -> stringResource(R.string.settings_sync_option_epg)
}

@Composable
internal fun ProviderSettingsCard(
    provider: Provider,
    isActive: Boolean,
    isSyncing: Boolean,
    diagnostics: ProviderDiagnosticsUiModel?,
    databaseMaintenance: DatabaseMaintenanceUiModel?,
    syncWarnings: List<String>,
    onRetryWarningAction: (ProviderWarningAction) -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onParentalControl: () -> Unit,
    onToggleM3uVodClassification: (Boolean) -> Unit,
    onRefreshM3uClassification: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Use Column layout - provider info + buttons below as separate focusable items
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isActive) SurfaceHighlight else SurfaceElevated,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = if (isActive) Primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Provider info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
                Text(
                    text = provider.type.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
            }
            ProviderStatusBadge(status = provider.status)
            if (isActive) {
                Text(
                    text = stringResource(R.string.settings_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier
                        .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Expiration Date
        val expDate = provider.expirationDate
        val expirationText = remember(expDate) {
            when (expDate) {
                null -> context.getString(R.string.settings_expiration_unknown)
                Long.MAX_VALUE -> context.getString(R.string.settings_expiration_never)
                else -> context.getString(R.string.settings_expires, java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expDate)))
            }
        }
        Text(
            text = expirationText,
            style = MaterialTheme.typography.bodySmall,
            color = if (expDate != null && expDate < System.currentTimeMillis() && expDate != Long.MAX_VALUE) ErrorColor else OnSurfaceDim
        )

        diagnostics?.let { model ->
            Text(
                text = listOf(model.sourceLabel, model.connectionSummary, model.expirySummary)
                    .filter { it.isNotBlank() }
                    .joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_live), count = model.liveCount)
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_movies), count = model.movieCount)
                if (provider.type == ProviderType.XTREAM_CODES) {
                    ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_series), count = model.seriesCount)
                }
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_epg), count = model.epgCount)
            }

            ProviderDiagnosticsPanel(
                provider = provider,
                diagnostics = model,
                databaseMaintenance = databaseMaintenance
            )
        }

        if (provider.type == ProviderType.M3U) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, RoundedCornerShape(10.dp))
                    .border(1.dp, SurfaceHighlight, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_m3u_vod_classification_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = OnBackground
                        )
                        Text(
                            text = stringResource(R.string.settings_m3u_vod_classification_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                    Switch(
                        checked = provider.m3uVodClassificationEnabled,
                        onCheckedChange = onToggleM3uVodClassification
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvClickableSurface(
                        onClick = onRefreshM3uClassification,
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Primary.copy(alpha = 0.15f),
                            focusedContainerColor = Primary.copy(alpha = 0.3f)
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_m3u_vod_classification_refresh),
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        if (syncWarnings.isNotEmpty()) {
            val hasEpgWarning = syncWarnings.any { it.contains("EPG", ignoreCase = true) }
            val hasMoviesWarning = syncWarnings.any { it.contains("Movies", ignoreCase = true) }
            val hasSeriesWarning = syncWarnings.any { it.contains("Series", ignoreCase = true) }

            Text(
                text = stringResource(R.string.settings_provider_warnings, syncWarnings.take(3).joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = Secondary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasEpgWarning) {
                    TvClickableSurface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.EPG) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_epg),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (hasMoviesWarning) {
                    TvClickableSurface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.MOVIES) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_movies),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (hasSeriesWarning && provider.type == ProviderType.XTREAM_CODES) {
                    TvClickableSurface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.SERIES) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_series),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Action buttons - each independently focusable for d-pad
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isActive) {
                TvClickableSurface(
                    onClick = onConnect,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary,
                        focusedContainerColor = Primary.copy(alpha = 0.8f),
                        contentColor = Color.White
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                            shape = RoundedCornerShape(6.dp)
                        )
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.settings_connect),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            } else {
                TvClickableSurface(
                    onClick = onRefresh,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.2f),
                        focusedContainerColor = Primary.copy(alpha = 0.5f)
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                            shape = RoundedCornerShape(6.dp)
                        )
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = if (isSyncing) stringResource(R.string.settings_syncing_btn) else stringResource(R.string.settings_sync_btn),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            TvClickableSurface(
                onClick = onEdit,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Secondary.copy(alpha = 0.2f),
                    focusedContainerColor = Secondary.copy(alpha = 0.5f)
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                        shape = RoundedCornerShape(6.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_edit),
                    style = MaterialTheme.typography.labelMedium,
                    color = Secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            TvClickableSurface(
                onClick = onDelete,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = ErrorColor.copy(alpha = 0.2f),
                    focusedContainerColor = ErrorColor.copy(alpha = 0.5f)
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                        shape = RoundedCornerShape(6.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = ErrorColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            if (isActive) {
                TvClickableSurface(
                    onClick = onParentalControl,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.15f),
                        focusedContainerColor = Primary.copy(alpha = 0.3f)
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                            shape = RoundedCornerShape(6.dp)
                        )
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_category_controls_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

    }
}

@Composable
internal fun ProviderSelectorTab(
    provider: Provider,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onClick
            ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.34f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) Primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f)
                )
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 2.dp,
                    color = Primary
                )
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = OnBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = provider.type.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
            if (isActive) {
                Text(
                    text = stringResource(R.string.settings_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ProviderCompactStat(
    title: String,
    count: Int
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = OnBackground
            )
        }
    }
}

@Composable
private fun ProviderStatusBadge(status: ProviderStatus) {
    val (label, color) = when (status) {
        ProviderStatus.ACTIVE -> stringResource(R.string.settings_status_active) to Primary
        ProviderStatus.PARTIAL -> stringResource(R.string.settings_status_partial) to Secondary
        ProviderStatus.ERROR -> stringResource(R.string.settings_status_error) to ErrorColor
        ProviderStatus.EXPIRED -> stringResource(R.string.settings_status_expired) to ErrorColor
        ProviderStatus.DISABLED -> stringResource(R.string.settings_status_disabled) to OnSurfaceDim
        ProviderStatus.UNKNOWN -> stringResource(R.string.settings_status_unknown) to OnSurfaceDim
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
internal fun SettingsOverviewCard(
    activeProviderName: String,
    providerCount: Int,
    protectionSummary: String,
    languageLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_overview_title),
                style = MaterialTheme.typography.titleLarge,
                color = OnBackground
            )
            Text(
                text = stringResource(R.string.settings_overview_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_provider_label),
                    value = activeProviderName
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_count_label),
                    value = providerCount.toString()
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_protection_label),
                    value = protectionSummary
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_language_label),
                    value = languageLabel
                )
            }
        }

    }
}

@Composable
private fun ProviderDiagnosticsPanel(
    provider: Provider,
    diagnostics: ProviderDiagnosticsUiModel,
    databaseMaintenance: DatabaseMaintenanceUiModel?
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.settings_provider_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )
        Text(
            text = diagnostics.capabilitySummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = stringResource(R.string.diagnostics_summary_format, diagnostics.sourceLabel, diagnostics.connectionSummary),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = diagnostics.expirySummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = diagnostics.archiveSummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_live),
                count = diagnostics.liveCount,
                timestamp = diagnostics.lastLiveSync
            )
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_movies),
                count = diagnostics.movieCount,
                timestamp = diagnostics.lastMovieSync
            )
            if (provider.type == ProviderType.XTREAM_CODES) {
                ProviderDiagnosticPill(
                    title = stringResource(R.string.settings_diagnostic_series),
                    count = diagnostics.seriesCount,
                    timestamp = diagnostics.lastSeriesSync
                )
            }
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_epg),
                count = diagnostics.epgCount,
                timestamp = diagnostics.lastEpgSync
            )
        }
        Text(
            text = stringResource(R.string.settings_diagnostic_status, diagnostics.lastSyncStatus),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurface
        )
        diagnostics.healthSummary(provider.type)?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorColor,
                fontWeight = FontWeight.Medium
            )
        }
        databaseMaintenance?.let { report ->
            DatabaseMaintenancePanel(report = report)
        }
    }
}

@Composable
private fun DatabaseMaintenancePanel(report: DatabaseMaintenanceUiModel) {
    val appTimeFormat = LocalAppTimeFormat.current
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Database Health",
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )
        Text(
            text = "Last maintenance ${formatDiagnosticTimestamp(report.ranAt, dateTimeFormat)}",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = buildString {
                append("Pruned ")
                append(report.deletedPrograms)
                append(" internal programs, ")
                append(report.deletedExternalProgrammes)
                append(" external programs, ")
                append(report.deletedOrphanEpisodes)
                append(" orphan episodes, and ")
                append(report.deletedStaleFavorites)
                append(" stale favorites.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = buildString {
                append("Main DB ")
                append(formatMaintenanceBytes(report.mainDbBytes))
                append(" • WAL ")
                append(formatMaintenanceBytes(report.walBytes))
                append(" • Reclaimable ")
                append(formatMaintenanceBytes(report.reclaimableBytes))
                append(" • VACUUM ")
                append(if (report.vacuumRan) "ran" else "not needed or skipped")
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = buildString {
                append("Rows: channels ")
                append(formatMaintenanceCount(report.channelRows))
                append(", movies ")
                append(formatMaintenanceCount(report.movieRows))
                append(", series ")
                append(formatMaintenanceCount(report.seriesRows))
                append(", episodes ")
                append(formatMaintenanceCount(report.episodeRows))
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = buildString {
                append("Programs ")
                append(formatMaintenanceCount(report.programRows))
                append(", external EPG ")
                append(formatMaintenanceCount(report.epgProgrammeRows))
                append(", history ")
                append(formatMaintenanceCount(report.playbackHistoryRows))
                append(", favorites ")
                append(formatMaintenanceCount(report.favoriteRows))
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}

@Composable
private fun ProviderDiagnosticPill(
    title: String,
    count: Int,
    timestamp: Long
) {
    val appTimeFormat = LocalAppTimeFormat.current
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
    val syncLabel = remember(timestamp, dateTimeFormat) { formatDiagnosticTimestamp(timestamp, dateTimeFormat) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Text(
                text = stringResource(R.string.settings_diagnostic_items, count),
                style = MaterialTheme.typography.labelLarge,
                color = OnBackground
            )
            Text(
                text = syncLabel ?: stringResource(R.string.settings_diagnostic_never),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurface
            )
        }
    }
}

private fun ProviderDiagnosticsUiModel.healthSummary(providerType: ProviderType): String? {
    val warnings = buildList {
        if (liveSequentialFailuresRemembered) {
            add("Live sync needs attention")
        }
        if (movieParallelFailuresRemembered) {
            add(
                if (movieWarningsCount > 0) {
                    "Movies have $movieWarningsCount remembered warning(s)"
                } else {
                    "Movie sync still has remembered warnings"
                }
            )
        }
        if (movieCatalogStale) {
            add("Movie catalog is running stale")
        }
        if (providerType == ProviderType.XTREAM_CODES && seriesSequentialFailuresRemembered) {
            add("Series sync needs attention")
        }
    }
    if (warnings.isEmpty()) {
        val streakParts = buildList {
            if (liveHealthySyncStreak > 0) add("Live streak $liveHealthySyncStreak")
            if (movieHealthySyncStreak > 0) add("Movies streak $movieHealthySyncStreak")
            if (providerType == ProviderType.XTREAM_CODES && seriesHealthySyncStreak > 0) {
                add("Series streak $seriesHealthySyncStreak")
            }
        }
        return streakParts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }
    return warnings.joinToString(" • ")
}

private fun formatDiagnosticTimestamp(timestamp: Long, dateTimeFormat: DateFormat): String? =
    if (timestamp <= 0L) {
        null
    } else {
        dateTimeFormat.format(java.util.Date(timestamp))
    }

private fun formatMaintenanceBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val formatted = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$formatted ${units[unitIndex]}"
}

private fun formatMaintenanceCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

@Composable
internal fun SettingsOverviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SurfaceHighlight.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = OnBackground,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CompactSettingsActionChip(
    label: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = accent.copy(alpha = if (enabled) 0.14f else 0.08f),
            contentColor = accent.copy(alpha = if (enabled) 1f else 0.42f),
            focusedContainerColor = accent.copy(alpha = if (enabled) 0.28f else 0.08f),
            focusedContentColor = accent.copy(alpha = if (enabled) 1f else 0.42f),
            disabledContainerColor = accent.copy(alpha = 0.08f),
            disabledContentColor = accent.copy(alpha = 0.42f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.08f else 0.04f)),
                shape = RoundedCornerShape(8.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            color = accent.copy(alpha = if (enabled) 1f else 0.42f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun EpgSourceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val isTelevisionDevice = com.streamvault.app.device.rememberIsTelevisionDevice()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    var hasContainerFocus by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
    var pendingInputActivation by remember { mutableStateOf(false) }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    val isFocused = hasContainerFocus || hasInputFocus

    // Re-call bringIntoView on every frame of the keyboard animation so the
    // LazyColumn keeps scrolling the focused field into view as imePadding shrinks.
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if ((hasInputFocus || hasContainerFocus) && imeBottom > 0) {
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    fun requestBringIntoView(delayMillis: Long = 0L) {
        coroutineScope.launch {
            if (delayMillis > 0) {
                kotlinx.coroutines.delay(delayMillis)
            }
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            val coercedSelectionStart = fieldValue.selection.start.coerceIn(0, value.length)
            val coercedSelectionEnd = fieldValue.selection.end.coerceIn(0, value.length)
            val coercedComposition = fieldValue.composition?.let { composition ->
                val compositionStart = composition.start.coerceIn(0, value.length)
                val compositionEnd = composition.end.coerceIn(0, value.length)
                if (compositionStart <= compositionEnd) {
                    TextRange(compositionStart, compositionEnd)
                } else {
                    null
                }
            }
            fieldValue = fieldValue.copy(
                text = value,
                selection = TextRange(coercedSelectionStart, coercedSelectionEnd),
                composition = coercedComposition
            )
        }
    }

    LaunchedEffect(acceptsInput, pendingInputActivation) {
        if (!isTelevisionDevice || !acceptsInput || !pendingInputActivation) {
            return@LaunchedEffect
        }
        focusRequester.requestFocus()
        keyboardController?.show()
        requestBringIntoView(120)
        pendingInputActivation = false
    }

    TvClickableSurface(
        onClick = {
            if (!isTelevisionDevice) {
                focusRequester.requestFocus()
                keyboardController?.show()
                requestBringIntoView()
                requestBringIntoView(180)
                return@TvClickableSurface
            }
            acceptsInput = true
            pendingInputActivation = true
            requestBringIntoView()
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.12f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { hasContainerFocus = it.isFocused }
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty() && !isFocused) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = { updatedValue ->
                    fieldValue = updatedValue
                    if (updatedValue.text != value) {
                        onValueChange(updatedValue.text)
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                singleLine = true,
                cursorBrush = SolidColor(Primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusProperties {
                        canFocus = !isTelevisionDevice || acceptsInput
                        if (isTelevisionDevice && acceptsInput) {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (!isTelevisionDevice || !acceptsInput || event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        val cursor = fieldValue.selection.end
                        when (event.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val nextCursor = (cursor - 1).coerceAtLeast(0)
                                fieldValue = fieldValue.copy(selection = TextRange(nextCursor))
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val nextCursor = (cursor + 1).coerceAtMost(fieldValue.text.length)
                                fieldValue = fieldValue.copy(selection = TextRange(nextCursor))
                                true
                            }
                            else -> false
                        }
                    }
                    .onFocusChanged {
                        hasInputFocus = it.isFocused
                        if (it.isFocused) {
                            requestBringIntoView(120)
                        } else {
                            if (isTelevisionDevice) {
                                acceptsInput = false
                            }
                            keyboardController?.hide()
                        }
                    },
                readOnly = isTelevisionDevice && !acceptsInput
            )
        }
    }
}


