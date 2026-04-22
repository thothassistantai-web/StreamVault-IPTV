package com.streamvault.app.ui.screens.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamRequestException
import com.streamvault.data.remote.xtream.XtreamResponseTooLargeException
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.StalkerProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

@HiltViewModel
class ProviderSetupViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val validateAndAddProvider: ValidateAndAddProvider
) : ViewModel() {

    enum class SetupSourceType {
        XTREAM,
        STALKER,
        M3U
    }

    private val _uiState = MutableStateFlow(ProviderSetupState())
    val uiState: StateFlow<ProviderSetupState> = _uiState.asStateFlow()
    private val _knownLocalM3uUrls = MutableStateFlow<Set<String>>(emptySet())
    val knownLocalM3uUrls: StateFlow<Set<String>> = _knownLocalM3uUrls.asStateFlow()

    init {
        viewModelScope.launch {
            providerRepository.getActiveProvider().collect { provider ->
                if (provider != null) {
                    _uiState.update { it.copy(hasExistingProvider = true) }
                }
            }
        }
        viewModelScope.launch {
            providerRepository.getProviders().collect { providers ->
                _knownLocalM3uUrls.value = providers
                    .mapNotNull { provider ->
                        provider.m3uUrl.takeIf { it.startsWith("file://") }
                    }
                    .toSet()
            }
        }
    }

    fun loadProvider(id: Long) {
        viewModelScope.launch {
            val provider = providerRepository.getProvider(id)
            if (provider != null) {
                _uiState.update {
                    it.copy(
                        isEditing = true,
                        existingProviderId = id,
                        name = provider.name,
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        password = "",
                        m3uUrl = provider.m3uUrl,
                        stalkerMacAddress = provider.stalkerMacAddress,
                        stalkerDeviceProfile = provider.stalkerDeviceProfile,
                        stalkerDeviceTimezone = provider.stalkerDeviceTimezone,
                        stalkerDeviceLocale = provider.stalkerDeviceLocale,
                        epgSyncMode = provider.epgSyncMode,
                        hasCustomizedEpgSyncMode = true,
                        xtreamFastSyncEnabled = provider.xtreamFastSyncEnabled,
                        m3uVodClassificationEnabled = provider.m3uVodClassificationEnabled,
                        selectedTab = when (provider.type) {
                            ProviderType.XTREAM_CODES -> 0
                            ProviderType.STALKER_PORTAL -> 1
                            ProviderType.M3U -> 2
                        },
                        m3uTab = if (provider.m3uUrl.startsWith("file://")) 1 else 0
                    )
                }
            }
        }
    }

    fun updateM3uTab(tab: Int) {
        _uiState.update { it.copy(m3uTab = tab) }
    }

    fun updateXtreamFastSyncEnabled(enabled: Boolean) {
        _uiState.update { it.copy(xtreamFastSyncEnabled = enabled) }
    }

    fun updateM3uVodClassificationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(m3uVodClassificationEnabled = enabled) }
    }

    fun updateEpgSyncMode(mode: ProviderEpgSyncMode) {
        _uiState.update { it.copy(epgSyncMode = mode, hasCustomizedEpgSyncMode = true) }
    }

    fun applySourceDefaults(sourceType: SetupSourceType) {
        _uiState.update { current ->
            if (current.isEditing || current.hasCustomizedEpgSyncMode) {
                current
            } else {
                current.copy(
                    epgSyncMode = defaultEpgSyncModeFor(sourceType)
                )
            }
        }
    }

    fun loginStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        deviceProfile: String,
        timezone: String,
        locale: String
    ) {
        _uiState.update { it.copy(validationError = null, error = null) }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.loginStalker(
                StalkerProviderSetupCommand(
                    portalUrl = portalUrl,
                    macAddress = macAddress,
                    name = name,
                    deviceProfile = deviceProfile,
                    timezone = timezone,
                    locale = locale,
                    epgSyncMode = _uiState.value.epgSyncMode,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun loginXtream(serverUrl: String, username: String, password: String, name: String) {
        _uiState.update { it.copy(validationError = null, error = null) }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    name = name,
                    xtreamFastSyncEnabled = _uiState.value.xtreamFastSyncEnabled,
                    epgSyncMode = _uiState.value.epgSyncMode,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = mapXtreamLoginError(result),
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun addM3u(url: String, name: String) {
        _uiState.update { it.copy(validationError = null, error = null) }

        if (url.isBlank()) {
            _uiState.update {
                it.copy(validationError = if (_uiState.value.m3uTab == 0) "Please enter M3U URL" else "Please select a file")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Validating...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.addM3u(
                M3uProviderSetupCommand(
                    url = url,
                    name = name,
                    epgSyncMode = _uiState.value.epgSyncMode,
                    m3uVodClassificationEnabled = _uiState.value.m3uVodClassificationEnabled,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    val activeCombinedProfileId = (combinedM3uRepository.getActiveLiveSource().first()
                        as? ActiveLiveSource.CombinedM3uSource)?.profileId
                    val activeCombinedProfileName = activeCombinedProfileId?.let { profileId ->
                        combinedM3uRepository.getProfile(profileId)?.name
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = activeCombinedProfileId == null,
                            createdProviderId = result.provider.id,
                            createdProviderName = result.provider.name,
                            pendingCombinedAttachProfileId = activeCombinedProfileId,
                            pendingCombinedAttachProfileName = activeCombinedProfileName,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Could not validate playlist: ${result.message}",
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun attachCreatedProviderToCombined() {
        val profileId = _uiState.value.pendingCombinedAttachProfileId ?: return
        val providerId = _uiState.value.createdProviderId ?: return
        viewModelScope.launch {
            combinedM3uRepository.addProvider(profileId, providerId)
            combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(profileId))
            _uiState.update {
                it.copy(
                    pendingCombinedAttachProfileId = null,
                    pendingCombinedAttachProfileName = null,
                    loginSuccess = true
                )
            }
        }
    }

    fun skipCreatedProviderCombinedAttach() {
        _uiState.update {
            it.copy(
                pendingCombinedAttachProfileId = null,
                pendingCombinedAttachProfileName = null,
                loginSuccess = true
            )
        }
    }

    private fun mapXtreamLoginError(result: ValidateAndAddProviderResult.Error): String {
        val failure = result.exception
        return when {
            result.message.startsWith(PROVIDER_LOGIN_SYNC_FAILED_PREFIX, ignoreCase = true) ->
                "Login succeeded, but the initial sync failed while loading the playlist"

            failure.hasCause<CredentialDecryptionException>() ->
                failure.findCause<CredentialDecryptionException>()?.message
                    ?: CredentialDecryptionException.MESSAGE

            failure.hasCause<SSLPeerUnverifiedException>() ||
                failure.hasCause<CertificateException>() ||
                failure.hasCause<SSLException>() ->
                "Secure connection failed - the server's TLS certificate is not trusted on this device"

            failure.hasCause<XtreamAuthenticationException>() ->
                "Login failed - please check your credentials and server URL"

            failure.findCause<XtreamRequestException>()?.statusCode in setOf(403, 408, 429) ->
                "Server is temporarily busy - try syncing again in a moment"

            failure.findCause<XtreamRequestException>()?.statusCode == 401 ->
                "Login failed - please check your credentials and server URL"

            failure.findCause<XtreamRequestException>()?.statusCode in 500..599 ->
                "Server is temporarily busy - try syncing again in a moment"

            failure.hasCause<SocketTimeoutException>() ||
                failure.hasCause<InterruptedIOException>() ||
                failure.hasCause<UnknownHostException>() ||
                failure.hasCause<ConnectException>() ||
                failure.hasCause<NoRouteToHostException>() ||
                failure.hasCause<XtreamNetworkException>() ->
                "Cannot reach server - check your internet connection and server URL"

            failure.hasCause<XtreamResponseTooLargeException>() ->
                "Server returned an unusually large response - try again later or contact the provider"

            failure.hasCause<XtreamParsingException>() ->
                "Server returned unreadable data - verify the provider details and try again"

            else -> result.message
        }
    }

    private inline fun <reified T : Throwable> Throwable?.findCause(): T? {
        return generateSequence(this) { it.cause }
            .filterIsInstance<T>()
            .firstOrNull()
    }

    private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean =
        findCause<T>() != null

    private companion object {
        private const val PROVIDER_LOGIN_SYNC_FAILED_PREFIX =
            "Provider login succeeded, but initial sync failed"
    }
}

data class ProviderSetupState(
    val isLoading: Boolean = false,
    val loginSuccess: Boolean = false,
    val hasExistingProvider: Boolean = false,
    val error: String? = null,
    val validationError: String? = null,
    val syncProgress: String? = null,
    val isEditing: Boolean = false,
    val existingProviderId: Long? = null,
    val selectedTab: Int = 0,
    val m3uTab: Int = 0,
    val name: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val stalkerMacAddress: String = "",
    val stalkerDeviceProfile: String = "",
    val stalkerDeviceTimezone: String = "",
    val stalkerDeviceLocale: String = "",
    val createdProviderId: Long? = null,
    val createdProviderName: String? = null,
    val pendingCombinedAttachProfileId: Long? = null,
    val pendingCombinedAttachProfileName: String? = null,
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val hasCustomizedEpgSyncMode: Boolean = false,
    val xtreamFastSyncEnabled: Boolean = true,
    val m3uVodClassificationEnabled: Boolean = false
)

private fun defaultEpgSyncModeFor(sourceType: ProviderSetupViewModel.SetupSourceType): ProviderEpgSyncMode = when (sourceType) {
    ProviderSetupViewModel.SetupSourceType.STALKER,
    ProviderSetupViewModel.SetupSourceType.XTREAM,
    ProviderSetupViewModel.SetupSourceType.M3U -> ProviderEpgSyncMode.BACKGROUND
}
