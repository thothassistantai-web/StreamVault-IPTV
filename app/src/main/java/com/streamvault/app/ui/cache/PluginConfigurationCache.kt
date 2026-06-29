package com.streamvault.app.ui.cache

import com.streamvault.app.ui.screens.plugins.ActivePluginConfiguration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginConfigurationCache @Inject constructor() {
    private val entries = mutableMapOf<String, ActivePluginConfiguration>()

    fun get(pluginId: String): ActivePluginConfiguration? = entries[pluginId]

    fun put(pluginId: String, configuration: ActivePluginConfiguration) {
        entries[pluginId] = configuration
    }

    fun remove(pluginId: String) {
        entries.remove(pluginId)
    }

    fun clear() {
        entries.clear()
    }
}
