package com.streamvault.domain.usecase

import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.ProviderSavedWithSyncErrorException
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.repository.ProviderRepository
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

data class XtreamProviderSetupCommand(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val xtreamFastSyncEnabled: Boolean = false,
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val xtreamLiveSyncMode: ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
    val existingProviderId: Long? = null
)

data class M3uProviderSetupCommand(
    val url: String,
    val name: String,
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val m3uVodClassificationEnabled: Boolean = false,
    val existingProviderId: Long? = null
)

data class StalkerProviderSetupCommand(
    val portalUrl: String,
    val macAddress: String,
    val name: String,
    val authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    val username: String = "",
    val password: String = "",
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val deviceProfile: String = "",
    val timezone: String = "",
    val locale: String = "",
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val stalkerAdvancedOptionsJson: String = "",
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val existingProviderId: Long? = null
)

data class JellyfinProviderSetupCommand(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val existingProviderId: Long? = null
)

data class JellyfinQuickConnectProviderSetupCommand(
    val serverUrl: String,
    val name: String,
    val existingProviderId: Long? = null
)

sealed class ValidateAndAddProviderResult {
    data class Success(val provider: Provider) : ValidateAndAddProviderResult()
    data class SavedWithWarning(val provider: Provider, val warning: String) : ValidateAndAddProviderResult()
    data class ValidationError(val message: String) : ValidateAndAddProviderResult()
    data class Error(val message: String, val exception: Throwable? = null) : ValidateAndAddProviderResult()
}

class ValidateAndAddProvider @Inject constructor(
    private val providerSetupInputValidator: ProviderSetupInputValidator,
    private val providerRepository: ProviderRepository
) {
    companion object {
        private const val MAX_XTREAM_PLAYLIST_USERNAME_LENGTH = 128
        private const val MAX_XTREAM_PLAYLIST_PASSWORD_LENGTH = 256
    }

    /**
     * Performs local input validation only — no network calls, no persistence, no side effects.
     * Returns the [ValidateAndAddProviderResult.ValidationError] if the input is invalid, or
     * `null` if the input passed all local checks and is ready for [loginXtream].
     */
    fun validateXtreamInput(command: XtreamProviderSetupCommand): ValidateAndAddProviderResult.ValidationError? {
        return when (
            val result = providerSetupInputValidator.validateXtream(
                serverUrl = command.serverUrl,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                name = command.name,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders
            )
        ) {
            is Result.Error -> ValidateAndAddProviderResult.ValidationError(result.message)
            else -> null
        }
    }

    /**
     * Performs local input validation only — no network calls, no persistence, no side effects.
     * Returns the [ValidateAndAddProviderResult.ValidationError] if the input is invalid, or
     * `null` if the input passed all local checks and is ready for [addM3u].
     */
    fun validateM3uInput(command: M3uProviderSetupCommand): ValidateAndAddProviderResult.ValidationError? {
        return when (
            val result = providerSetupInputValidator.validateM3u(
                url = command.url,
                name = command.name,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders
            )
        ) {
            is Result.Error -> ValidateAndAddProviderResult.ValidationError(result.message)
            else -> null
        }
    }

    /**
     * Performs local input validation only — no network calls, no persistence, no side effects.
     * Returns the [ValidateAndAddProviderResult.ValidationError] if the input is invalid, or
     * `null` if the input passed all local checks and is ready for [loginStalker].
     */
    fun validateStalkerInput(command: StalkerProviderSetupCommand): ValidateAndAddProviderResult.ValidationError? {
        return when (
            val result = providerSetupInputValidator.validateStalker(
                portalUrl = command.portalUrl,
                macAddress = command.macAddress,
                name = command.name,
                authMode = command.authMode,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders,
                deviceProfile = command.deviceProfile,
                timezone = command.timezone,
                locale = command.locale,
                serialNumber = command.serialNumber,
                deviceId = command.deviceId,
                deviceId2 = command.deviceId2,
                signature = command.signature,
                stalkerAdvancedOptionsJson = command.stalkerAdvancedOptionsJson
            )
        ) {
            is Result.Error -> ValidateAndAddProviderResult.ValidationError(result.message)
            else -> null
        }
    }

    suspend fun loginXtream(
        command: XtreamProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateXtream(
                serverUrl = command.serverUrl,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                name = command.name,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders
            )
        ) {
            is Result.Success -> providerRepository.loginXtream(
                serverUrl = validated.data.serverUrl,
                username = validated.data.username,
                password = validated.data.password,
                name = validated.data.name,
                httpUserAgent = validated.data.httpUserAgent,
                httpHeaders = validated.data.httpHeaders,
                xtreamFastSyncEnabled = command.xtreamFastSyncEnabled,
                epgSyncMode = command.epgSyncMode,
                xtreamLiveSyncMode = command.xtreamLiveSyncMode,
                onProgress = onProgress,
                id = command.existingProviderId
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun addM3u(
        command: M3uProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateM3u(
                url = command.url,
                name = command.name,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders
            )
        ) {
            is Result.Success -> {
                val validatedInput = validated.data
                when (val parsedXtream = parseXtreamPlaylistUrl(validatedInput.url)) {
                    is ParsedXtreamPlaylistUrlResult.ValidationError ->
                        ValidateAndAddProviderResult.ValidationError(parsedXtream.message)

                    is ParsedXtreamPlaylistUrlResult.Success -> {
                        providerRepository.loginXtream(
                            serverUrl = parsedXtream.serverUrl,
                            username = parsedXtream.username,
                            password = parsedXtream.password,
                            name = validatedInput.name,
                            httpUserAgent = validatedInput.httpUserAgent,
                            httpHeaders = validatedInput.httpHeaders,
                            xtreamFastSyncEnabled = false,
                            epgSyncMode = command.epgSyncMode,
                            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
                            onProgress = onProgress,
                            id = command.existingProviderId
                        ).toUseCaseResult()
                    }

                    ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist -> {
                        providerRepository.validateM3u(
                            url = validatedInput.url,
                            name = validatedInput.name,
                            httpUserAgent = validatedInput.httpUserAgent,
                            httpHeaders = validatedInput.httpHeaders,
                            epgSyncMode = command.epgSyncMode,
                            m3uVodClassificationEnabled = command.m3uVodClassificationEnabled,
                            onProgress = onProgress,
                            id = command.existingProviderId
                        ).toUseCaseResult()
                    }
                }
            }

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun loginStalker(
        command: StalkerProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateStalker(
                portalUrl = command.portalUrl,
                macAddress = command.macAddress,
                name = command.name,
                authMode = command.authMode,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders,
                deviceProfile = command.deviceProfile,
                timezone = command.timezone,
                locale = command.locale,
                serialNumber = command.serialNumber,
                deviceId = command.deviceId,
                deviceId2 = command.deviceId2,
                signature = command.signature,
                stalkerAdvancedOptionsJson = command.stalkerAdvancedOptionsJson
            )
        ) {
            is Result.Success -> providerRepository.loginStalker(
                portalUrl = validated.data.portalUrl,
                macAddress = validated.data.macAddress,
                name = validated.data.name,
                authMode = validated.data.authMode,
                username = validated.data.username,
                password = validated.data.password,
                httpUserAgent = validated.data.httpUserAgent,
                httpHeaders = validated.data.httpHeaders,
                deviceProfile = validated.data.deviceProfile,
                timezone = validated.data.timezone,
                locale = validated.data.locale,
                serialNumber = validated.data.serialNumber,
                deviceId = validated.data.deviceId,
                deviceId2 = validated.data.deviceId2,
                signature = validated.data.signature,
                stalkerAdvancedOptionsJson = validated.data.stalkerAdvancedOptionsJson,
                epgSyncMode = command.epgSyncMode,
                onProgress = onProgress,
                id = command.existingProviderId
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun loginJellyfin(
        command: JellyfinProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateJellyfin(
                serverUrl = command.serverUrl,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                name = command.name
            )
        ) {
            is Result.Success -> providerRepository.loginJellyfin(
                serverUrl = validated.data.serverUrl,
                username = validated.data.username,
                password = validated.data.password,
                name = validated.data.name,
                onProgress = onProgress,
                id = command.existingProviderId
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun loginJellyfinQuickConnect(
        command: JellyfinQuickConnectProviderSetupCommand,
        onCode: ((String) -> Unit)? = null,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateJellyfinQuickConnect(
                serverUrl = command.serverUrl,
                name = command.name
            )
        ) {
            is Result.Success -> providerRepository.loginJellyfinQuickConnect(
                serverUrl = validated.data.serverUrl,
                name = validated.data.name,
                onCode = onCode,
                onProgress = onProgress,
                id = command.existingProviderId
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    private fun Result<Provider>.toUseCaseResult(): ValidateAndAddProviderResult = when (this) {
        is Result.Success -> ValidateAndAddProviderResult.Success(data)
        is Result.Error -> {
            val savedWithWarning = exception as? ProviderSavedWithSyncErrorException
            if (savedWithWarning != null) {
                ValidateAndAddProviderResult.SavedWithWarning(
                    provider = savedWithWarning.provider,
                    warning = savedWithWarning.message ?: message
                )
            } else {
                ValidateAndAddProviderResult.Error(message, exception)
            }
        }
        is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
    }

    private sealed interface ParsedXtreamPlaylistUrlResult {
        data object NotXtreamPlaylist : ParsedXtreamPlaylistUrlResult
        data class ValidationError(val message: String) : ParsedXtreamPlaylistUrlResult
        data class Success(
            val serverUrl: String,
            val username: String,
            val password: String
        ) : ParsedXtreamPlaylistUrlResult
    }

    private fun parseXtreamPlaylistUrl(url: String): ParsedXtreamPlaylistUrlResult {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        val scheme = uri.scheme?.lowercase() ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        if (scheme != "http" && scheme != "https") return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist

        val normalizedPath = uri.path.orEmpty().lowercase()
        if (!normalizedPath.endsWith("/get.php")) return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist

        val query = parseQueryParameters(uri.rawQuery)
        val username = query["username"]?.takeIf { it.isNotBlank() }
            ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        val password = query["password"]?.takeIf { it.isNotBlank() }
            ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        val type = query["type"]?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        if (type != "m3u" && type != "m3u_plus") return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist

        if (username.length > MAX_XTREAM_PLAYLIST_USERNAME_LENGTH) {
            return ParsedXtreamPlaylistUrlResult.ValidationError("Playlist username is too long.")
        }
        if (password.length > MAX_XTREAM_PLAYLIST_PASSWORD_LENGTH) {
            return ParsedXtreamPlaylistUrlResult.ValidationError("Playlist password is too long.")
        }

        if (!uri.userInfo.isNullOrBlank()) {
            return ParsedXtreamPlaylistUrlResult.ValidationError(
                "Playlist sources must not include embedded credentials in the URL authority."
            )
        }

        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return ParsedXtreamPlaylistUrlResult.ValidationError("Playlist sources must include a host.")
        val authority = buildString {
            append(host.asXtreamServerHost())
            if (uri.port != -1) {
                append(":")
                append(uri.port)
            }
        }
        return ParsedXtreamPlaylistUrlResult.Success(
            serverUrl = "$scheme://$authority",
            username = username,
            password = password
        )
    }

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val value = part.substringAfter('=', "")
                decodeQueryComponent(key) to decodeQueryComponent(value)
            }
            .toMap()
    }

    private fun decodeQueryComponent(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun String.asXtreamServerHost(): String =
        if (contains(':') && !startsWith("[")) "[$this]" else this
}
