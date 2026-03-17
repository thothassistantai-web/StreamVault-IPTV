package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Category
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.app.ui.design.AppColors
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import androidx.tv.material3.*

@Composable
fun AddToGroupDialog(
    contentTitle: String,
    channel: Channel? = null,          // the channel being managed (for split screen)
    groups: List<Category>,
    isFavorite: Boolean,
    memberOfGroups: List<Long>,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToGroup: (Category) -> Unit,
    onRemoveFromGroup: (Category) -> Unit,
    onCreateGroup: (String) -> Unit,
    isQueuedForSplitScreen: Boolean = false,
    onOpenSplitScreenPlanner: (() -> Unit)? = null
) {
    var showCreateGroup by remember { mutableStateOf(false) }
    val contentFocusRequester = remember { FocusRequester() }  // focuses first interactive item, not close button

    // Ghost-click debounce
    var canInteract by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { contentFocusRequester.requestFocus() }
        delay(500)
        canInteract = true
    }

    val safeDismiss = { if (canInteract) onDismiss() }

    if (showCreateGroup) {
        CreateGroupDialog(
            onDismissRequest = { showCreateGroup = false },
            onConfirm = { name ->
                onCreateGroup(name)
                showCreateGroup = false
            }
        )
    }

    if (!showCreateGroup) {
        Dialog(
            onDismissRequest = safeDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated),
                modifier = Modifier
                    .width(400.dp)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.add_group_manage_title, contentTitle),
                            style = MaterialTheme.typography.titleLarge,
                            color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = safeDismiss
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.add_group_close_cd))
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ── Favorites ────────────────────────────────
                        item {
                            var isFocused by remember { mutableStateOf(false) }
                            Button(
                                onClick = onToggleFavorite,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(contentFocusRequester)
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .background(
                                        color = if (isFocused) AppColors.Focus else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .border(
                                        if (isFocused) 3.dp else 0.dp,
                                        if (isFocused) AppColors.Focus else Color.Transparent,
                                        CircleShape
                                    ),
                                colors = ButtonDefaults.colors(
                                    containerColor = when {
                                        isFocused -> AppColors.Focus
                                        isFavorite -> AppColors.Warning
                                        else -> AppColors.Brand
                                    },
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = if (isFavorite) stringResource(R.string.add_group_remove_favorites)
                                        else stringResource(R.string.add_group_add_favorites),
                                    tint = Color.Black
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isFavorite) stringResource(R.string.add_group_remove_favorites)
                                    else stringResource(R.string.add_group_add_favorites),
                                    color = Color.Black
                                )
                            }
                        }

                        // ── Split Screen ─────────────────────────────
                        if (channel != null && onOpenSplitScreenPlanner != null) {
                            item {
                                var isFocused by remember { mutableStateOf(false) }
                                Button(
                                    onClick = onOpenSplitScreenPlanner,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .background(
                                            color = if (isFocused) AppColors.Focus else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .border(
                                            if (isFocused) 3.dp else 0.dp,
                                            if (isFocused) AppColors.Focus else Color.Transparent,
                                            CircleShape
                                        ),
                                    colors = ButtonDefaults.colors(
                                    containerColor = when {
                                        isFocused -> AppColors.Focus
                                        isQueuedForSplitScreen -> AppColors.Success.copy(alpha = 0.8f)
                                        else -> AppColors.Success
                                    }
                                )
                            ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.multiview_add_to_split),
                                        tint = if (isFocused) Color.Black else Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isQueuedForSplitScreen) stringResource(R.string.multiview_queued)
                                        else stringResource(R.string.multiview_add_to_split),
                                        color = if (isFocused) Color.Black else Color.White
                                    )
                                }
                            }
                        }

                        // ── Custom Groups Section ─────────────────────
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .height(1.dp)
                                    .background(AppColors.SurfaceAccent.copy(alpha = 0.7f))
                            )
                            Text(
                                text = stringResource(R.string.add_group_custom_groups_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        items(groups) { group ->
                            val groupId = -group.id
                            val isMember = memberOfGroups.contains(groupId)
                            var isFocused by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        if (isMember) onRemoveFromGroup(group) else onAddToGroup(group)
                                    },
                                    modifier = Modifier
                                        .background(
                                            color = if (isFocused) AppColors.Focus else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .border(
                                            if (isFocused) 3.dp else 0.dp,
                                            if (isFocused) AppColors.Focus else Color.Transparent,
                                            CircleShape
                                        ),
                                    colors = ButtonDefaults.colors(
                                        containerColor = when {
                                            isFocused -> AppColors.Focus
                                            isMember -> AppColors.Live.copy(alpha = 0.2f)
                                            else -> AppColors.BrandMuted.copy(alpha = 0.4f)
                                        },
                                        contentColor = if (isFocused) Color.Black else AppColors.TextPrimary
                                    )
                                ) {
                                    Text(
                                        if (isMember) stringResource(R.string.add_group_remove_btn)
                                        else stringResource(R.string.add_group_add_btn)
                                    )
                                }
                            }
                        }

                    item {
                        var isFocused by remember { mutableStateOf(false) }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showCreateGroup = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                                .background(
                                    color = if (isFocused) AppColors.Focus else Color.Transparent,
                                    shape = CircleShape
                                )
                                .border(
                                    if (isFocused) 3.dp else 1.dp,
                                    if (isFocused) AppColors.Focus else AppColors.BrandMuted.copy(alpha = 0.55f),
                                    CircleShape
                                ),
                            colors = ButtonDefaults.colors(
                                containerColor = if (isFocused) AppColors.Focus else Color.Transparent,
                                contentColor = if (isFocused) Color.Black else AppColors.TextPrimary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_group_create_new_btn))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.add_group_create_new_btn))
                        }
                    }
                    }
                }
            }
        }
    }
}
