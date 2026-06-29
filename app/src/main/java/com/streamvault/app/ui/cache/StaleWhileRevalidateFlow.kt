package com.streamvault.app.ui.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits [staleValue] immediately when present, then forwards every upstream value.
 * Use `null` stale when no cache entry exists (including empty lists as valid cache).
 */
fun <T> Flow<T>.staleWhileRevalidate(
    staleValue: T?,
    onFresh: (T) -> Unit = {}
): Flow<T> = flow {
    if (staleValue != null) {
        emit(staleValue)
    }
    collect { fresh ->
        onFresh(fresh)
        emit(fresh)
    }
}
