package com.streamvault.app.ui.cache

import com.streamvault.app.plugins.InstalledStreamVaultPlugin
import com.streamvault.app.plugins.StreamVaultPluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginsListCacheTest {
    @Test
    fun `put and get round trip`() {
        val cache = PluginsListCache()
        val plugin = InstalledStreamVaultPlugin(
            packageName = "com.example.plugin",
            serviceClassName = "com.example.PluginService",
            appLabel = "Example",
            manifest = StreamVaultPluginManifest(id = "example", name = "Example"),
            enabled = true
        )

        assertNull(cache.get())
        cache.put(listOf(plugin))
        assertEquals(listOf(plugin), cache.get())

        cache.clear()
        assertNull(cache.get())
    }
}
