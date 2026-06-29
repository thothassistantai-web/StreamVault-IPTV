package com.streamvault.app.ui.cache

import com.streamvault.app.plugins.InstalledStreamVaultPlugin
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginsListCache @Inject constructor() {
    private var plugins: List<InstalledStreamVaultPlugin>? = null

    fun get(): List<InstalledStreamVaultPlugin>? = plugins

    fun put(value: List<InstalledStreamVaultPlugin>) {
        plugins = value
    }

    fun clear() {
        plugins = null
    }
}
