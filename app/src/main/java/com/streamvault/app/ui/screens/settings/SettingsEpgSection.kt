package com.streamvault.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.lazy.items
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary

internal fun LazyListScope.epgSourcesSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val epgSources = uiState.epgSources
    val providers = uiState.providers

    item {
        Text(
            text = "EPG Sources",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF66BB6A),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Add external XMLTV EPG sources and assign them to providers. External sources are matched to channels by ID or name and override provider-native EPG data.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }

    item {
        AddEpgSourceCard(viewModel = viewModel)
    }

    if (epgSources.isEmpty()) {
        item {
            Text(
                text = "No external EPG sources configured. Add a source above to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    } else {
        items(epgSources, key = { source -> "epg-source-${source.id}" }) { source ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.titleSmall, color = Color.White)
                            Text(displayableEpgUrl(source.url), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, maxLines = 1)
                            if (source.lastError != null) {
                                Text("Error: ${source.lastError}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF5350))
                            }
                            if (source.lastSuccessAt > 0L) {
                                val ago = (System.currentTimeMillis() - source.lastSuccessAt) / 60000
                                Text("Last synced: ${ago}m ago", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                            }
                        }
                        val sourceActionShape = RoundedCornerShape(8.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvClickableSurface(
                                onClick = { viewModel.toggleEpgSourceEnabled(source.id, !source.enabled) },
                                shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (source.enabled) Color(0xFF66BB6A).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = if (source.enabled) Color(0xFF66BB6A).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f)
                                ),
                                border = epgActionBorder(sourceActionShape),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                            ) {
                                Text(
                                    if (source.enabled) "ON" else "OFF",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (source.enabled) Color(0xFF66BB6A) else OnSurfaceDim
                                )
                            }
                            TvClickableSurface(
                                onClick = { viewModel.refreshEpgSource(source.id) },
                                shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Primary.copy(alpha = 0.15f),
                                    focusedContainerColor = Primary.copy(alpha = 0.3f)
                                ),
                                border = epgActionBorder(sourceActionShape, enabled = source.id !in uiState.refreshingEpgSourceIds),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                enabled = source.id !in uiState.refreshingEpgSourceIds
                            ) {
                                Text(
                                    if (source.id in uiState.refreshingEpgSourceIds) "..." else "Refresh",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Primary
                                )
                            }
                            if (uiState.epgPendingDeleteSourceId == source.id) {
                                TvClickableSurface(
                                    onClick = { viewModel.setPendingDeleteEpgSource(null) },
                                    shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        focusedContainerColor = Color.White.copy(alpha = 0.15f)
                                    ),
                                    border = epgActionBorder(sourceActionShape),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                ) {
                                    Text("Cancel", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
                                }
                                TvClickableSurface(
                                    onClick = { viewModel.deleteEpgSource(source.id) },
                                    shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color(0xFFEF5350).copy(alpha = 0.25f),
                                        focusedContainerColor = Color(0xFFEF5350).copy(alpha = 0.45f)
                                    ),
                                    border = epgActionBorder(sourceActionShape),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                ) {
                                    Text("Confirm Delete", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF5350))
                                }
                            } else {
                                TvClickableSurface(
                                    onClick = { viewModel.setPendingDeleteEpgSource(source.id) },
                                    shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color(0xFFEF5350).copy(alpha = 0.12f),
                                        focusedContainerColor = Color(0xFFEF5350).copy(alpha = 0.25f)
                                    ),
                                    border = epgActionBorder(sourceActionShape),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                ) {
                                    Text("Delete", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF5350))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (providers.isNotEmpty() && epgSources.isNotEmpty()) {
        item {
            Text(
                text = "Provider Assignments",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF66BB6A),
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            Text(
                text = "Assign EPG sources to providers. Channels will be matched automatically by ID or name.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        items(providers, key = { provider -> "epg-provider-${provider.id}" }) { provider ->
            val assignments = uiState.epgSourceAssignments[provider.id].orEmpty()
            val resolutionSummary = uiState.epgResolutionSummaries[provider.id]
            val assignedSourceIds = assignments.map { it.epgSourceId }.toSet()
            val unassignedSources = epgSources.filter { it.id !in assignedSourceIds }

            LaunchedEffect(provider.id) {
                viewModel.loadEpgAssignments(provider.id)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    if (resolutionSummary != null) {
                        val matchedChannels = (resolutionSummary.totalChannels - resolutionSummary.unresolvedChannels).coerceAtLeast(0)
                        val summaryParts = buildList {
                            add("Matched $matchedChannels/${resolutionSummary.totalChannels} channels")
                            if (resolutionSummary.exactIdMatches > 0) add("${resolutionSummary.exactIdMatches} exact")
                            if (resolutionSummary.normalizedNameMatches > 0) add("${resolutionSummary.normalizedNameMatches} name")
                            if (resolutionSummary.providerNativeMatches > 0) add("${resolutionSummary.providerNativeMatches} provider")
                            if (resolutionSummary.manualMatches > 0) add("${resolutionSummary.manualMatches} manual")
                            if (resolutionSummary.unresolvedChannels > 0) add("${resolutionSummary.unresolvedChannels} without EPG")
                            if (resolutionSummary.lowConfidenceChannels > 0) add("${resolutionSummary.lowConfidenceChannels} weak")
                            if (resolutionSummary.rematchCandidateChannels > 0) add("${resolutionSummary.rematchCandidateChannels} need review")
                        }
                        Text(
                            text = summaryParts.joinToString(" \u2022 "),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }

                    if (assignments.isEmpty()) {
                        Text("No EPG sources assigned", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    } else {
                        assignments.sortedBy { it.priority }.forEachIndexed { assignmentIndex, assignment ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "${assignment.epgSourceName} (priority: ${assignment.priority})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                val priorityActionShape = RoundedCornerShape(6.dp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TvClickableSurface(
                                        onClick = { viewModel.moveEpgSourceAssignmentUp(provider.id, assignment.epgSourceId) },
                                        enabled = assignmentIndex > 0,
                                        shape = ClickableSurfaceDefaults.shape(priorityActionShape),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = Color.White.copy(alpha = 0.08f),
                                            focusedContainerColor = Color.White.copy(alpha = 0.16f),
                                            disabledContainerColor = Color.White.copy(alpha = 0.04f)
                                        ),
                                        border = epgActionBorder(priorityActionShape, enabled = assignmentIndex > 0),
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                    ) {
                                        Text("Up", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                    TvClickableSurface(
                                        onClick = { viewModel.moveEpgSourceAssignmentDown(provider.id, assignment.epgSourceId) },
                                        enabled = assignmentIndex < assignments.lastIndex,
                                        shape = ClickableSurfaceDefaults.shape(priorityActionShape),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = Color.White.copy(alpha = 0.08f),
                                            focusedContainerColor = Color.White.copy(alpha = 0.16f),
                                            disabledContainerColor = Color.White.copy(alpha = 0.04f)
                                        ),
                                        border = epgActionBorder(priorityActionShape, enabled = assignmentIndex < assignments.lastIndex),
                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                    ) {
                                        Text("Down", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                TvClickableSurface(
                                    onClick = { viewModel.unassignEpgSourceFromProvider(provider.id, assignment.epgSourceId) },
                                    shape = ClickableSurfaceDefaults.shape(priorityActionShape),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color(0xFFEF5350).copy(alpha = 0.12f),
                                        focusedContainerColor = Color(0xFFEF5350).copy(alpha = 0.25f)
                                    ),
                                    border = epgActionBorder(priorityActionShape),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                ) {
                                    Text("Remove", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF5350))
                                }
                            }
                        }
                    }

                    if (unassignedSources.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            unassignedSources.forEach { source ->
                                val assignActionShape = RoundedCornerShape(8.dp)
                                TvClickableSurface(
                                    onClick = { viewModel.assignEpgSourceToProvider(provider.id, source.id) },
                                    shape = ClickableSurfaceDefaults.shape(assignActionShape),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color(0xFF66BB6A).copy(alpha = 0.12f),
                                        focusedContainerColor = Color(0xFF66BB6A).copy(alpha = 0.25f)
                                    ),
                                    border = epgActionBorder(assignActionShape),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                ) {
                                    Text("+ ${source.name}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF66BB6A))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddEpgSourceCard(viewModel: SettingsViewModel) {
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            newUrl = uri.toString()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add EPG Source", style = MaterialTheme.typography.titleSmall, color = Color.White)
            EpgSourceTextField(value = newName, onValueChange = { newName = it }, placeholder = "Source name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    EpgSourceTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        placeholder = "XMLTV URL (HTTP/HTTPS) or browse file"
                    )
                }
                val addActionShape = RoundedCornerShape(8.dp)
                TvClickableSurface(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    shape = ClickableSurfaceDefaults.shape(addActionShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.15f),
                        focusedContainerColor = Primary.copy(alpha = 0.3f)
                    ),
                    border = epgActionBorder(addActionShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text("Browse", modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = Primary)
                }
            }
            val addSourceShape = RoundedCornerShape(8.dp)
            TvClickableSurface(
                onClick = {
                    if (newName.isNotBlank() && newUrl.isNotBlank()) {
                        viewModel.addEpgSource(newName.trim(), newUrl.trim())
                        newName = ""
                        newUrl = ""
                    }
                },
                shape = ClickableSurfaceDefaults.shape(addSourceShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF66BB6A).copy(alpha = 0.2f),
                    focusedContainerColor = Color(0xFF66BB6A).copy(alpha = 0.4f)
                ),
                border = epgActionBorder(addSourceShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text("Add Source", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color(0xFF66BB6A), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun epgActionBorder(shape: RoundedCornerShape, enabled: Boolean = true) =
    ClickableSurfaceDefaults.border(
        border = Border(
            border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.08f else 0.04f)),
            shape = shape
        ),
        focusedBorder = Border(
            border = BorderStroke(FocusSpec.BorderWidth, Color.White),
            shape = shape
        )
    )

private fun displayableEpgUrl(url: String): String = when {
    url.startsWith("content://") -> {
        val lastSegment = try { android.net.Uri.parse(url).lastPathSegment } catch (_: Exception) { null }
        val decoded = lastSegment?.let { android.net.Uri.decode(it) }?.substringAfterLast("/")?.substringAfterLast("\\")
        if (!decoded.isNullOrBlank() && decoded.length < 60) "local: $decoded" else "local file"
    }
    else -> url
}
