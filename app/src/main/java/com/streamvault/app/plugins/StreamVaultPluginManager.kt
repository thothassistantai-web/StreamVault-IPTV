package com.streamvault.app.plugins

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.streamvault.app.BuildConfig
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class StreamVaultPluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messengerClient: PluginMessengerClient,
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    private val prefs = context.getSharedPreferences("streamvault_plugins", Context.MODE_PRIVATE)

    suspend fun discoverPlugins(): List<InstalledStreamVaultPlugin> = withContext(Dispatchers.IO) {
        queryPluginServices()
            .mapNotNull { resolveInfo -> resolvePlugin(resolveInfo) }
            .distinctBy { it.manifest.id }
            .sortedBy { it.displayName.lowercase() }
    }

    suspend fun setPluginEnabled(
        plugin: InstalledStreamVaultPlugin,
        enabled: Boolean,
        onProgress: (String) -> Unit = {}
    ): PluginActionResult = withContext(Dispatchers.IO) {
        val command = Bundle().apply {
            putBoolean(StreamVaultPluginContract.KEY_ENABLED, enabled)
        }
        val response = runCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_SET_ENABLED,
                data = command,
                timeoutMillis = 120_000L
            )
        }.getOrElse { error ->
            return@withContext PluginActionResult(false, error.message ?: "Plugin did not respond")
        }

        if (!response.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, true)) {
            return@withContext PluginActionResult(
                success = false,
                message = response.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                    .ifBlank { "Plugin rejected the request" }
            )
        }

        prefs.edit().putBoolean(enabledKey(plugin.manifest.id), enabled).apply()
        if (enabled && plugin.manifest.hasCapability(StreamVaultPluginContract.CAPABILITY_PROVIDER_M3U)) {
            syncPluginProvider(plugin, onProgress)?.let { return@withContext it }
        } else if (!enabled) {
            removePluginProvider(plugin)?.let { return@withContext it }
        }

        PluginActionResult(
            success = true,
            message = response.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                .ifBlank { if (enabled) "Plugin activated" else "Plugin deactivated" }
        )
    }

    suspend fun installApkFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val target = pluginApkFile("local-${System.currentTimeMillis()}.apk")
        runCatching {
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected APK" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            return@withContext Result.error("Could not copy selected plugin APK", error)
        }
        launchPackageInstaller(target)
    }

    suspend fun installApkFromUrl(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedUrl = url.trim()
        if (!isHttpOrHttpsUrl(normalizedUrl)) {
            return@withContext Result.error("Plugin URL must be http or https")
        }

        val target = pluginApkFile("plugin-${System.currentTimeMillis()}.apk")
        runCatching {
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(normalizedUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response")
                target.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
        }.onFailure { error ->
            return@withContext Result.error("Could not download plugin APK", error)
        }
        launchPackageInstaller(target)
    }

    fun openPluginConfiguration(plugin: InstalledStreamVaultPlugin): PluginActionResult {
        val action = plugin.manifest.configurationActivityAction?.takeIf { it.isNotBlank() }
            ?: return PluginActionResult(false, "This plugin has no configuration screen")
        val intent = Intent(action).apply {
            setPackage(plugin.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            PluginActionResult(true, "Opening ${plugin.displayName}")
        }.getOrElse { error ->
            PluginActionResult(false, error.message ?: "Could not open plugin settings")
        }
    }

    suspend fun loadPluginConfiguration(plugin: InstalledStreamVaultPlugin): Result<PluginConfigurationSnapshot> =
        withContext(Dispatchers.IO) {
            if (!plugin.manifest.supportsHostRenderedConfiguration) {
                return@withContext Result.error("This plugin does not expose a StreamVault configuration schema")
            }

            val schemaResponse = runCatching {
                messengerClient.send(
                    packageName = plugin.packageName,
                    serviceClassName = plugin.serviceClassName,
                    what = StreamVaultPluginContract.MSG_GET_CONFIGURATION_SCHEMA,
                    timeoutMillis = 10_000L
                )
            }.getOrElse { error ->
                return@withContext Result.error(error.message ?: "Plugin configuration schema is unavailable")
            }
            if (!schemaResponse.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
                return@withContext Result.error(
                    schemaResponse.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                        .ifBlank { "Plugin configuration schema is unavailable" }
                )
            }

            val schemaJson = schemaResponse
                .getString(StreamVaultPluginContract.KEY_CONFIGURATION_SCHEMA_JSON)
                .orEmpty()
            val schema = runCatching { json.decodeFromString<PluginConfigurationSchema>(schemaJson) }
                .getOrElse { error ->
                    return@withContext Result.error(error.message ?: "Plugin configuration schema is invalid")
                }
            if (schema.sections.isEmpty() && schema.actions.isEmpty()) {
                return@withContext Result.error("Plugin configuration schema is empty")
            }

            val values = loadPluginConfigurationValues(plugin).getOrNull() ?: JsonObject(emptyMap())
            Result.success(PluginConfigurationSnapshot(plugin, schema, values))
        }

    suspend fun loadPluginConfigurationValues(plugin: InstalledStreamVaultPlugin): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            val valuesResponse = runCatching {
                messengerClient.send(
                    packageName = plugin.packageName,
                    serviceClassName = plugin.serviceClassName,
                    what = StreamVaultPluginContract.MSG_GET_CONFIGURATION_VALUES,
                    timeoutMillis = 10_000L
                )
            }.getOrElse { error ->
                return@withContext Result.error(error.message ?: "Plugin configuration values are unavailable")
            }
            if (!valuesResponse.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
                return@withContext Result.error(
                    valuesResponse.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                        .ifBlank { "Plugin configuration values are unavailable" }
                )
            }

            val valuesJson = valuesResponse
                .getString(StreamVaultPluginContract.KEY_CONFIGURATION_VALUES_JSON)
                .orEmpty()
            val values = if (valuesJson.isBlank()) {
                JsonObject(emptyMap())
            } else {
                runCatching { json.decodeFromString<JsonObject>(valuesJson) }
                    .getOrElse { error ->
                        return@withContext Result.error(error.message ?: "Plugin configuration values are invalid")
                    }
            }
            Result.success(values)
        }

    suspend fun savePluginConfiguration(
        plugin: InstalledStreamVaultPlugin,
        valuesJson: String
    ): PluginActionResult = withContext(Dispatchers.IO) {
        val response = runCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_SET_CONFIGURATION_VALUES,
                data = Bundle().apply {
                    putString(StreamVaultPluginContract.KEY_CONFIGURATION_VALUES_JSON, valuesJson)
                },
                timeoutMillis = 60_000L
            )
        }.getOrElse { error ->
            return@withContext PluginActionResult(false, error.message ?: "Plugin settings could not be saved")
        }
        response.toPluginActionResult("Plugin settings saved")
    }

    suspend fun runPluginConfigurationAction(
        plugin: InstalledStreamVaultPlugin,
        actionId: String
    ): PluginActionResult = withContext(Dispatchers.IO) {
        val response = runCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_RUN_CONFIGURATION_ACTION,
                data = Bundle().apply {
                    putString(StreamVaultPluginContract.KEY_CONFIGURATION_ACTION_ID, actionId)
                },
                timeoutMillis = 120_000L
            )
        }.getOrElse { error ->
            return@withContext PluginActionResult(false, error.message ?: "Plugin action failed")
        }
        response.toPluginActionResult("Plugin action completed")
    }

    suspend fun preparePlaybackUrl(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext Result.success(Unit)
        val plugins = discoverPlugins()
            .filter { it.enabled && it.manifest.hasCapability(StreamVaultPluginContract.CAPABILITY_PLAYBACK_PREPARE) }
        for (plugin in plugins) {
            val response = runCatching {
                messengerClient.send(
                    packageName = plugin.packageName,
                    serviceClassName = plugin.serviceClassName,
                    what = StreamVaultPluginContract.MSG_PREPARE_PLAYBACK,
                    data = Bundle().apply { putString(StreamVaultPluginContract.KEY_INPUT_URL, url) },
                    timeoutMillis = 120_000L
                )
            }.getOrNull() ?: continue

            if (!response.getBoolean(StreamVaultPluginContract.KEY_HANDLED, false)) continue
            if (response.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
                return@withContext Result.success(Unit)
            }
            return@withContext Result.error(
                response.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                    .ifBlank { "${plugin.displayName} could not prepare playback" }
            )
        }
        Result.success(Unit)
    }

    suspend fun rewriteCastUrl(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext url
        val plugins = discoverPlugins()
            .filter { it.enabled && it.manifest.hasCapability(StreamVaultPluginContract.CAPABILITY_CAST_REWRITE_URL) }
        for (plugin in plugins) {
            val response = runCatching {
                messengerClient.send(
                    packageName = plugin.packageName,
                    serviceClassName = plugin.serviceClassName,
                    what = StreamVaultPluginContract.MSG_REWRITE_CAST_URL,
                    data = Bundle().apply { putString(StreamVaultPluginContract.KEY_INPUT_URL, url) },
                    timeoutMillis = 10_000L
                )
            }.getOrNull() ?: continue

            if (!response.getBoolean(StreamVaultPluginContract.KEY_HANDLED, false)) continue
            if (!response.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) return@withContext null
            return@withContext response.getString(StreamVaultPluginContract.KEY_OUTPUT_URL).orEmpty()
                .ifBlank { url }
        }
        url
    }

    private suspend fun syncPluginProvider(
        plugin: InstalledStreamVaultPlugin,
        onProgress: (String) -> Unit
    ): PluginActionResult? {
        val providerResponse = runCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_GET_PROVIDER_URL,
                timeoutMillis = 120_000L
            )
        }.getOrElse { error ->
            return PluginActionResult(false, error.message ?: "Plugin provider URL is unavailable")
        }
        if (!providerResponse.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
            return PluginActionResult(
                false,
                providerResponse.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                    .ifBlank { "Plugin provider URL is unavailable" }
            )
        }

        val providerUrl = providerResponse.getString(StreamVaultPluginContract.KEY_URL).orEmpty()
        if (providerUrl.isBlank()) {
            return PluginActionResult(false, "Plugin did not return a provider URL")
        }
        val providerName = providerResponse.getString(StreamVaultPluginContract.KEY_PROVIDER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: plugin.manifest.providerName?.takeIf { it.isNotBlank() }
            ?: "${plugin.displayName} Plugin"
        val existingProvider = trackedProvider(plugin)
        val activeSource = combinedM3uRepository.getActiveLiveSource().first()

        val provider = if (existingProvider != null) {
            val updatedProvider = existingProvider.copy(
                name = providerName,
                serverUrl = providerUrl,
                m3uUrl = providerUrl,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                m3uVodClassificationEnabled = false,
                isActive = true,
                lastSyncedAt = 0L
            )
            when (val updateResult = providerRepository.updateProvider(updatedProvider)) {
                is Result.Error -> return PluginActionResult(false, updateResult.message)
                Result.Loading -> return PluginActionResult(false, "Provider update is still running")
                is Result.Success -> Unit
            }
            when (val refreshResult = providerRepository.refreshProviderData(
                providerId = updatedProvider.id,
                force = true,
                epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND,
                onProgress = onProgress
            )) {
                is Result.Error -> return PluginActionResult(false, refreshResult.message)
                Result.Loading -> return PluginActionResult(false, "Provider refresh is still running")
                is Result.Success -> Unit
            }
            updatedProvider
        } else {
            when (val createResult = providerRepository.validateM3u(
                url = providerUrl,
                name = providerName,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                m3uVodClassificationEnabled = false,
                onProgress = onProgress
            )) {
                is Result.Error -> return PluginActionResult(false, createResult.message)
                Result.Loading -> return PluginActionResult(false, "Provider sync is still running")
                is Result.Success -> createResult.data
            }
        }

        prefs.edit().putLong(providerKey(plugin.manifest.id), provider.id).apply()
        providerRepository.setActiveProvider(provider.id)
        attachProviderToLiveSource(provider.id, activeSource)
        refreshTvInputCatalogInBackground()
        return null
    }

    private suspend fun removePluginProvider(plugin: InstalledStreamVaultPlugin): PluginActionResult? {
        val providerId = prefs.getLong(providerKey(plugin.manifest.id), -1L).takeIf { it > 0L }
            ?: return null
        when (val result = providerRepository.deleteProvider(providerId)) {
            is Result.Error -> return PluginActionResult(false, result.message)
            Result.Loading -> return PluginActionResult(false, "Provider removal is still running")
            is Result.Success -> Unit
        }
        val activeSource = combinedM3uRepository.getActiveLiveSource().first()
        if (activeSource is ActiveLiveSource.ProviderSource && activeSource.providerId == providerId) {
            combinedM3uRepository.setActiveLiveSource(null)
        }
        prefs.edit().remove(providerKey(plugin.manifest.id)).apply()
        refreshTvInputCatalogInBackground()
        return null
    }

    private suspend fun attachProviderToLiveSource(providerId: Long, activeSource: ActiveLiveSource?) {
        when (activeSource) {
            is ActiveLiveSource.CombinedM3uSource -> {
                combinedM3uRepository.addProvider(activeSource.profileId, providerId)
                combinedM3uRepository.setActiveLiveSource(activeSource)
            }
            is ActiveLiveSource.ProviderSource,
            null -> combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.ProviderSource(providerId))
        }
    }

    private suspend fun trackedProvider(plugin: InstalledStreamVaultPlugin): Provider? {
        val providerId = prefs.getLong(providerKey(plugin.manifest.id), -1L).takeIf { it > 0L }
            ?: return providerRepository.getProviders().first().firstOrNull {
                it.type == ProviderType.M3U && it.m3uUrl.isNotBlank() && it.name == plugin.manifest.providerName
            }
        return providerRepository.getProvider(providerId)
    }

    private fun refreshTvInputCatalogInBackground() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).let { scope ->
            scope.launchCatching { tvInputChannelSyncManager.refreshTvInputCatalog() }
        }
    }

    private fun resolvePlugin(resolveInfo: ResolveInfo): InstalledStreamVaultPlugin? {
        val serviceInfo = resolveInfo.serviceInfo ?: return null
        val packageName = serviceInfo.packageName ?: return null
        val serviceName = serviceInfo.name ?: return null
        val appLabel = serviceInfo.loadLabel(context.packageManager)?.toString().orEmpty()
        val manifest = readManifestFromService(packageName, serviceName)
            ?: readManifestFromMetadata(serviceInfo.metaData)
            ?: StreamVaultPluginManifest(
                id = packageName,
                name = appLabel.ifBlank { packageName },
                description = "StreamVault plugin"
            )
        val status = runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                messengerClient.send(
                    packageName = packageName,
                    serviceClassName = serviceName,
                    what = StreamVaultPluginContract.MSG_GET_STATUS,
                    timeoutMillis = 2_500L
                )
            }
        }.getOrNull()
        return InstalledStreamVaultPlugin(
            packageName = packageName,
            serviceClassName = serviceName,
            appLabel = appLabel,
            manifest = manifest,
            enabled = prefs.getBoolean(enabledKey(manifest.id), false),
            statusLabel = status?.getString(StreamVaultPluginContract.KEY_STATUS_LABEL).orEmpty(),
            lastMessage = status?.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
        )
    }

    private fun readManifestFromService(packageName: String, serviceName: String): StreamVaultPluginManifest? =
        runCatching {
            val response = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                messengerClient.send(
                    packageName = packageName,
                    serviceClassName = serviceName,
                    what = StreamVaultPluginContract.MSG_GET_MANIFEST,
                    timeoutMillis = 3_000L
                )
            }
            val manifestJson = response.getString(StreamVaultPluginContract.KEY_MANIFEST_JSON).orEmpty()
            json.decodeFromString<StreamVaultPluginManifest>(manifestJson)
        }.getOrNull()

    private fun readManifestFromMetadata(metaData: Bundle?): StreamVaultPluginManifest? {
        if (metaData == null) return null

        val manifestJson = metaData.metaString(StreamVaultPluginContract.META_MANIFEST_JSON)
        if (manifestJson.isNotBlank()) {
            runCatching { json.decodeFromString<StreamVaultPluginManifest>(manifestJson) }
                .getOrNull()
                ?.let { return it }
        }

        val id = metaData.metaString(StreamVaultPluginContract.META_ID).takeIf { it.isNotBlank() }
            ?: return null
        val name = metaData.metaString(StreamVaultPluginContract.META_NAME).ifBlank { id }
        return StreamVaultPluginManifest(
            id = id,
            name = name,
            versionName = metaData.metaString(StreamVaultPluginContract.META_VERSION_NAME),
            versionCode = metaData.metaLong(StreamVaultPluginContract.META_VERSION_CODE),
            description = metaData.metaString(StreamVaultPluginContract.META_DESCRIPTION),
            capabilities = metaData.metaCsv(StreamVaultPluginContract.META_CAPABILITIES),
            configurationMode = metaData.metaString(StreamVaultPluginContract.META_CONFIGURATION_MODE)
                .takeIf { it.isNotBlank() },
            configurationActivityAction = metaData
                .metaString(StreamVaultPluginContract.META_CONFIGURATION_ACTIVITY_ACTION)
                .takeIf { it.isNotBlank() },
            providerName = metaData.metaString(StreamVaultPluginContract.META_PROVIDER_NAME)
                .takeIf { it.isNotBlank() }
        )
    }

    private fun queryPluginServices(): List<ResolveInfo> {
        val intent = Intent(StreamVaultPluginContract.ACTION_PLUGIN_SERVICE)
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
    }

    private fun launchPackageInstaller(apkFile: File): Result<Unit> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return Result.error("Allow installs from StreamVault, then choose the plugin APK again")
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (error: ActivityNotFoundException) {
            Result.error("No package installer is available on this device", error)
        } catch (error: SecurityException) {
            Result.error("The package installer could not be launched", error)
        }
    }

    private fun pluginApkFile(fileName: String): File {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.cacheDir, "downloads")
        return File(downloadsDir, "plugin-apks/$fileName")
    }

    private fun isHttpOrHttpsUrl(value: String): Boolean =
        runCatching {
            val parsed = URI(value)
            parsed.scheme.equals("http", ignoreCase = true) ||
                parsed.scheme.equals("https", ignoreCase = true)
        }.getOrDefault(false)

    private fun enabledKey(pluginId: String): String = "enabled.$pluginId"
    private fun providerKey(pluginId: String): String = "provider.$pluginId"
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(block: suspend () -> Unit) {
    launch { runCatching { block() } }
}

@Suppress("DEPRECATION")
private fun Bundle.metaString(key: String): String = when (val value = get(key)) {
    is String -> value
    is CharSequence -> value.toString()
    is Number -> value.toString()
    is Boolean -> value.toString()
    else -> ""
}

@Suppress("DEPRECATION")
private fun Bundle.metaLong(key: String): Long = when (val value = get(key)) {
    is Long -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is String -> value.toLongOrNull() ?: 0L
    else -> 0L
}

private fun Bundle.metaCsv(key: String): List<String> =
    metaString(key)
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun Bundle.toPluginActionResult(successMessage: String): PluginActionResult {
    val success = getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)
    return PluginActionResult(
        success = success,
        message = getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
            .ifBlank { if (success) successMessage else "Plugin operation failed" }
    )
}
