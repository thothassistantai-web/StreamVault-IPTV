package com.streamvault.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.domain.model.Provider

@Composable
fun PlaylistSwitcher(
    currentProvider: Provider?,
    allProviders: List<Provider>,
    onProviderSelected: (Provider) -> Unit,
    modifier: Modifier = Modifier
) {
    var showProviderList by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    BackHandler(enabled = showProviderList) { showProviderList = false }

    Box(modifier = modifier) {
        Surface(
            onClick = { showProviderList = !showProviderList },
            modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (isFocused) Primary.copy(alpha = 0.2f) else SurfaceElevated,
                focusedContainerColor = Primary.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.label_provider),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) Primary else OnSurfaceDim
                )
                Text(
                    text = currentProvider?.name ?: stringResource(R.string.playlist_no_provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFocused) Primary else OnBackground
                )
                Icon(
                    imageVector = if (showProviderList) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = OnSurfaceDim
                )
            }
        }

        if (showProviderList && allProviders.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .padding(top = 48.dp)
                    .width(250.dp),
                shape = RoundedCornerShape(8.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    allProviders.forEach { provider ->
                        ProviderItem(
                            provider = provider,
                            isSelected = provider.id == currentProvider?.id,
                            onClick = {
                                onProviderSelected(provider)
                                showProviderList = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderItem(
    provider: Provider,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = provider.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused || isSelected) Primary else OnBackground
            )
            if (isSelected) {
                Text(
                    text = stringResource(R.string.label_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary
                )
            }
        }
    }
}
