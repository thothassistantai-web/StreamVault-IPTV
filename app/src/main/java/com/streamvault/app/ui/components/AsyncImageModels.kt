package com.streamvault.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
fun rememberCrossfadeImageModel(data: Any?): Any? {
    val context = LocalContext.current
    return remember(context, data) {
        data?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .build()
        }
    }
}