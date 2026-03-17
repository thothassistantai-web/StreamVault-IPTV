package com.streamvault.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.TextPrimary
import com.streamvault.app.ui.theme.TextSecondary

@Composable
fun ReorderTopBar(
    categoryName: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    visible: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Primary.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_reordering, categoryName),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = OnSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContentColor = OnSurface
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder)
                            )
                        ),
                        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                    ) {
                        Text(stringResource(R.string.action_cancel), modifier = Modifier.padding(horizontal = 8.dp))
                    }

                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.colors(
                            containerColor = Primary,
                            contentColor = Color.White,
                            focusedContainerColor = Primary.copy(alpha = 0.88f),
                            focusedContentColor = Color.White
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder)
                            )
                        ),
                        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                    ) {
                        Text(stringResource(R.string.action_save_order), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }
}
