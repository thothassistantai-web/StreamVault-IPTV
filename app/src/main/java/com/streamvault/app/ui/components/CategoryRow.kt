package com.streamvault.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.streamvault.app.ui.theme.LocalSpacing
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.app.ui.components.shell.AppSectionHeader

// ── Netflix-style horizontal category row ─────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T : Any> CategoryRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    keySelector: ((T) -> Any)? = null,
    itemContent: @Composable (T) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AppSectionHeader(
            title = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            actionLabel = onSeeAll?.let { stringResource(R.string.category_see_all) },
            onActionClick = onSeeAll
        )

        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = items,
                key = keySelector  // null = index-based keys (safe default)
            ) { item ->
                itemContent(item)
            }
        }
    }
}
