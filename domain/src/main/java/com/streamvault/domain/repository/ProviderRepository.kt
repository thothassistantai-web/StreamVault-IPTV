package com.streamvault.domain.repository

import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import kotlinx.coroutines.flow.Flow

data class LiveStreamProgramRequest(
    val streamId: Long,
    val epgChannelId: String? = null
)

interface ProviderRepository {
    fun getProviders(): Flow<List<Provider>>
    fun getActiveProvider(): Flow<Provider?>
    suspend fun getProvider(id: Long): Provider?
    suspend fun addProvider(provider: Provider): Result<Long>
    suspend fun updateProvider(provider: Provider): Result<Unit>
    suspend fun deleteProvider(id: Long): Result<Unit>

    /**
     * Returns cleartext credentials for all providers that have both a
     * username and a password. Used by the Drive credentials sync path
     * (M3). Decryption happens inside the `:data` layer — the cleartext
     * payload is only ever exposed via this single typed method.
     */
    suspend fun getAllProviderCredentials(): List<ProviderCredentials>

    /**
     * Applies a cleartext password to the provider matched by
     * `(serverUrl, username)`. Encryption happens inside the `:data`
     * layer. Returns true if a matching provider was found and updated.
     */
    suspend fun updateProviderPassword(
        serverUrl: String,
        username: String,
        cleartextPassword: String,
    ): Boolean

    suspend fun setActiveProvider(id: Long): Result<Unit>
    suspend fun loginXtream(serverUrl: String, username: String, password: String, name: String, httpUserAgent: String = "", httpHeaders: String = "", xtreamFastSyncEnabled: Boolean, epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND, xtreamLiveSyncMode: ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO, onProgress: ((String) -> Unit)? = null, id: Long? = null): Result<Provider>
    suspend fun validateM3u(url: String, name: String, httpUserAgent: String = "", httpHeaders: String = "", epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND, m3uVodClassificationEnabled: Boolean = false, onProgress: ((String) -> Unit)? = null, id: Long? = null): Result<Provider>
    suspend fun loginStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
        username: String = "",
        password: String = "",
        httpUserAgent: String = "",
        httpHeaders: String = "",
        deviceProfile: String = "",
        timezone: String = "",
        locale: String = "",
        serialNumber: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        signature: String = "",
        stalkerAdvancedOptionsJson: String = "",
        epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null
    ): Result<Provider>
    suspend fun loginJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null
    ): Result<Provider>
    suspend fun loginJellyfinQuickConnect(
        serverUrl: String,
        name: String,
        onCode: ((String) -> Unit)? = null,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null
    ): Result<Provider>
    suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>
    suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String? = null,
        limit: Int = 12
    ): Result<List<Program>>
    suspend fun getProgramsForLiveStreams(
        providerId: Long,
        requests: List<LiveStreamProgramRequest>,
        limit: Int = 12
    ): Map<LiveStreamProgramRequest, Result<List<Program>>> =
        requests.distinct().associateWith { request ->
            getProgramsForLiveStream(
                providerId = providerId,
                streamId = request.streamId,
                epgChannelId = request.epgChannelId,
                limit = limit
            )
        }
    suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String?
    suspend fun buildCatchUpUrls(providerId: Long, streamId: Long, start: Long, end: Long): List<String> =
        listOfNotNull(buildCatchUpUrl(providerId, streamId, start, end))
}
