package com.streamvault.app.ui.screens.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.util.ProviderInputSanitizer
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProviderSetupViewModel @Inject constructor(
    private val providerRepository: ProviderRepository
) : ViewModel() {

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
                        // Do not prefill stored passwords back into UI.
                        password = "",
                        m3uUrl = provider.m3uUrl,
                        selectedTab = if (provider.type == ProviderType.M3U) 1 else 0,
                        m3uTab = if (provider.m3uUrl.startsWith("file://")) 1 else 0
                    )
                }
            }
        }
    }

    fun updateM3uTab(tab: Int) {
        _uiState.update { it.copy(m3uTab = tab) }
    }

    fun loginXtream(serverUrl: String, username: String, password: String, name: String) {
        _uiState.update { it.copy(validationError = null, error = null) }

        val normalizedServerUrl = ProviderInputSanitizer.normalizeUrl(serverUrl)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)

        if (normalizedServerUrl.isBlank()) {
            _uiState.update { it.copy(validationError = "Please enter server URL") }
            return
        }
        ProviderInputSanitizer.validateUrl(normalizedServerUrl)?.let { message ->
            _uiState.update { it.copy(validationError = message) }
            return
        }
        UrlSecurityPolicy.validateXtreamServerUrl(normalizedServerUrl)?.let { message ->
            _uiState.update { it.copy(validationError = message) }
            return
        }
        if (normalizedUsername.isBlank()) {
            _uiState.update { it.copy(validationError = "Please enter username") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = providerRepository.loginXtream(normalizedServerUrl, normalizedUsername, password, normalizedName, onProgress = { msg ->
                _uiState.update { it.copy(syncProgress = msg) }
            }, id = existingId)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(isLoading = false, loginSuccess = true, error = null, syncProgress = null)
                    }
                }
                is Result.Error -> {
                    val userMessage = when {
                        result.message.contains("certificate", ignoreCase = true) ||
                        result.message.contains("trust", ignoreCase = true) ->
                            "Secure connection failed — the server's TLS certificate is not trusted on this device"
                        result.message.contains("authentication failed", ignoreCase = true) ||
                        result.message.contains("auth", ignoreCase = true) ->
                            "Login failed — please check your credentials and server URL"
                        result.message.contains("unable to connect", ignoreCase = true) ||
                        result.message.contains("timeout", ignoreCase = true) ||
                        result.message.contains("network", ignoreCase = true) ->
                            "Cannot reach server — check your internet connection and server URL"
                        else -> result.message
                    }
                    _uiState.update {
                        it.copy(isLoading = false, error = userMessage, syncProgress = null)
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun addM3u(url: String, name: String) {
        _uiState.update { it.copy(validationError = null, error = null) }

        val normalizedUrl = ProviderInputSanitizer.normalizeUrl(url)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)

        if (normalizedUrl.isBlank()) {
            _uiState.update { it.copy(validationError = if (_uiState.value.m3uTab == 0) "Please enter M3U URL" else "Please select a file") }
            return
        }
        ProviderInputSanitizer.validateUrl(normalizedUrl)?.let { message ->
            _uiState.update { it.copy(validationError = message) }
            return
        }
        UrlSecurityPolicy.validatePlaylistSourceUrl(normalizedUrl)?.let { message ->
            _uiState.update { it.copy(validationError = message) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Validating...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = providerRepository.validateM3u(normalizedUrl, normalizedName, onProgress = { msg ->
                _uiState.update { it.copy(syncProgress = msg) }
            }, id = existingId)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(isLoading = false, loginSuccess = true, error = null, syncProgress = null)
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Could not validate playlist: ${result.message}", syncProgress = null)
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
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
    // Pre-fill data
    val selectedTab: Int = 0,
    val m3uTab: Int = 0, // 0 = URL, 1 = File
    val name: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = ""
)
