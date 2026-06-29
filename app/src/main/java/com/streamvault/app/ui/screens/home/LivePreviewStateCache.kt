package com.streamvault.app.ui.screens.home

/**
 * Remembers the last previewed channel per Live TV browse context so category
 * switches can restore the preview pane instead of showing the empty placeholder.
 */
internal data class LivePreviewStateCacheKey(
    val sourceKey: String,
    val categoryId: Long,
    val combinedFilterProviderId: Long?,
)

internal class LivePreviewStateCache(
    private val maxEntries: Int = 24,
) {
    private val entries = linkedMapOf<LivePreviewStateCacheKey, Long>()

    fun get(key: LivePreviewStateCacheKey): Long? = entries[key]

    fun put(key: LivePreviewStateCacheKey, channelId: Long) {
        entries[key] = channelId
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    fun clearForSource(sourceKey: String) {
        entries.keys.removeAll { it.sourceKey == sourceKey }
    }

    fun clear() {
        entries.clear()
    }
}
