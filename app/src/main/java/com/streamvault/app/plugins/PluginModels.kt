package com.streamvault.app.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class StreamVaultPluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val versionName: String = "",
    val versionCode: Long = 0L,
    val description: String = "",
    val capabilities: List<String> = emptyList(),
    val configurationMode: String? = null,
    val configurationActivityAction: String? = null,
    val providerName: String? = null
) {
    fun hasCapability(capability: String): Boolean = capability in capabilities

    val supportsHostRenderedConfiguration: Boolean
        get() = hasCapability(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA) ||
            configurationMode == StreamVaultPluginContract.CONFIGURATION_MODE_HOST_SCHEMA
}

data class InstalledStreamVaultPlugin(
    val packageName: String,
    val serviceClassName: String,
    val appLabel: String,
    val manifest: StreamVaultPluginManifest,
    val enabled: Boolean,
    val statusLabel: String = "",
    val lastMessage: String = ""
) {
    val displayName: String
        get() = manifest.name.ifBlank { appLabel.ifBlank { packageName } }
}

data class PluginActionResult(
    val success: Boolean,
    val message: String
)

data class PluginConfigurationSnapshot(
    val plugin: InstalledStreamVaultPlugin,
    val schema: PluginConfigurationSchema,
    val values: JsonObject
)

@Serializable
data class PluginConfigurationSchema(
    val schemaVersion: Int = 1,
    val title: String = "",
    val description: String = "",
    val sections: List<PluginConfigurationSection> = emptyList(),
    val actions: List<PluginConfigurationAction> = emptyList()
)

@Serializable
data class PluginConfigurationSection(
    val id: String,
    val title: String,
    val description: String = "",
    val fields: List<PluginConfigurationField> = emptyList()
)

@Serializable
data class PluginConfigurationField(
    val key: String,
    val type: String = TYPE_TEXT,
    val label: String,
    val description: String = "",
    val placeholder: String = "",
    val required: Boolean = false,
    val readOnly: Boolean = false,
    val secret: Boolean = false,
    val defaultValue: JsonElement? = null,
    val options: List<PluginConfigurationOption> = emptyList(),
    val min: Double? = null,
    val max: Double? = null
) {
    companion object {
        const val TYPE_INFO = "info"
        const val TYPE_TEXT = "text"
        const val TYPE_PASSWORD = "password"
        const val TYPE_URL = "url"
        const val TYPE_NUMBER = "number"
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_SELECT = "select"
        const val TYPE_TEXTAREA = "textarea"
    }
}

@Serializable
data class PluginConfigurationOption(
    val value: String,
    val label: String,
    val description: String = ""
)

@Serializable
data class PluginConfigurationAction(
    val id: String,
    val label: String,
    val description: String = "",
    val destructive: Boolean = false,
    val refreshAfterRun: Boolean = true
)
