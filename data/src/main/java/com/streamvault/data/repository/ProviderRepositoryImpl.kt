package com.streamvault.data.repository

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.mapper.*
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.util.ProviderInputSanitizer
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.*
import com.streamvault.domain.provider.IptvProvider
import com.streamvault.domain.repository.LiveStreamProgramRequest
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val channelDao: ChannelDao,
    private val programDao: ProgramDao,
    private val stalkerApiService: StalkerApiService,
    private val xtreamApiService: XtreamApiService,
    private val credentialCrypto: CredentialCrypto,
    private val preferencesRepository: PreferencesRepository,
    private val syncManager: SyncManager,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val transactionRunner: DatabaseTransactionRunner
) : ProviderRepository {
    private companion object {
        const val XTREAM_GUIDE_BATCH_CONCURRENCY = 4
        const val BACKGROUND_EPG_START_DELAY_MS = 15_000L
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getProviders(): Flow<List<Provider>> =
        providerDao.getAll().map { entities -> entities.map { it.toPublicDomain() } }

    override fun getActiveProvider(): Flow<Provider?> =
        providerDao.getActive().map { it?.toPublicDomain() }

    override suspend fun getProvider(id: Long): Provider? =
        providerDao.getById(id)?.toPublicDomain()

    override suspend fun addProvider(provider: Provider): Result<Long> = try {
        val id = providerDao.insert(provider.toSecureEntity())
        Result.success(id)
    } catch (e: Exception) {
        Result.error("Failed to add provider: ${e.message}", e)
    }

    override suspend fun updateProvider(provider: Provider): Result<Unit> = try {
        providerDao.update(provider.toSecureEntity())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update provider: ${e.message}", e)
    }

    override suspend fun deleteProvider(id: Long): Result<Unit> = try {
        // ProgramEntity still has no provider FK, so it requires explicit cleanup.
        programDao.deleteByProvider(id)
        providerDao.delete(id)
        syncManager.onProviderDeleted(id)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to delete provider: ${e.message}", e)
    }

    override suspend fun setActiveProvider(id: Long): Result<Unit> = try {
        providerDao.setActive(id)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to set active provider: ${e.message}", e)
    }

    override suspend fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        xtreamFastSyncEnabled: Boolean,
        epgSyncMode: ProviderEpgSyncMode,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        val normalizedServerUrl = ProviderInputSanitizer.normalizeUrl(serverUrl)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)

        ProviderInputSanitizer.validateUrl(normalizedServerUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateXtreamServerUrl(normalizedServerUrl)?.let { message ->
            return Result.error(message)
        }
        onProgress?.invoke("Authenticating...")
        val existingProvider = if (id != null) {
            providerDao.getById(id)
        } else {
            providerDao.getByUrlAndUser(normalizedServerUrl, normalizedUsername)
        }
        val effectivePassword = try {
            password.takeIf { it.isNotBlank() }
                ?: existingProvider?.password?.let(credentialCrypto::decryptIfNeeded)
                ?: ""
        } catch (e: CredentialDecryptionException) {
            return Result.error(e.message ?: CredentialDecryptionException.MESSAGE, e)
        }
        val provider = createXtreamProvider(0, normalizedServerUrl, normalizedUsername, effectivePassword)
        return when (val authResult = provider.authenticate()) {
            is Result.Success -> {
                val providerData = if (existingProvider != null) {
                    onProgress?.invoke("Updating existing provider...")
                    val updated = authResult.data.copy(
                        id = existingProvider.id,
                        name = normalizedName.ifBlank { existingProvider.name },
                        serverUrl = normalizedServerUrl,
                        username = normalizedUsername,
                        password = effectivePassword,
                        epgSyncMode = epgSyncMode,
                        xtreamFastSyncEnabled = xtreamFastSyncEnabled,
                        isActive = true,
                        lastSyncedAt = 0,
                        createdAt = existingProvider.createdAt
                    )
                    providerDao.update(updated.toSecureEntity())
                    updated.copy(password = "")
                } else {
                    val newData = authResult.data.copy(
                        name = normalizedName.ifBlank { authResult.data.name },
                        epgSyncMode = epgSyncMode,
                        xtreamFastSyncEnabled = xtreamFastSyncEnabled
                    )
                    val newId = providerDao.insert(newData.toSecureEntity())
                    newData.copy(id = newId).copy(password = "")
                }

                providerDao.setActive(providerData.id)
                when (val syncResult = syncManager.sync(providerData.id, force = false, onProgress = onProgress)) {
                    is Result.Success -> {
                        val finalStatus = if (syncManager.currentSyncState(providerData.id) is SyncState.Partial) {
                            ProviderStatus.PARTIAL
                        } else {
                            ProviderStatus.ACTIVE
                        }
                        updateProviderSyncStatus(providerData.id, finalStatus, System.currentTimeMillis())
                        maybeScheduleBackgroundEpgSync(providerData.id)
                        Result.success(providerData.copy(status = finalStatus))
                    }
                    is Result.Error -> {
                        updateProviderSyncStatus(providerData.id, ProviderStatus.ERROR)
                        Result.error(
                            "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: ${syncResult.message}",
                            syncResult.exception
                        )
                    }
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun validateM3u(
        url: String,
        name: String,
        epgSyncMode: ProviderEpgSyncMode,
        m3uVodClassificationEnabled: Boolean,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> = try {
        val normalizedUrl = ProviderInputSanitizer.normalizeUrl(url)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)

        ProviderInputSanitizer.validateUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validatePlaylistSourceUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        onProgress?.invoke("Validating playlist URL...")
        val providerName = normalizedName.ifBlank {
            normalizedUrl.substringAfterLast("/").substringBefore("?").ifBlank { "M3U Playlist" }
        }

        val existingProvider = if (id != null) {
            providerDao.getById(id)
        } else {
            providerDao.getByUrlAndUser(normalizedUrl, "")
        }

        val providerData = if (existingProvider != null) {
            val updated = existingProvider.copy(
                name = if (normalizedName.isNotBlank()) normalizedName else existingProvider.name,
                serverUrl = normalizedUrl,
                m3uUrl = normalizedUrl,
                epgSyncMode = epgSyncMode,
                m3uVodClassificationEnabled = m3uVodClassificationEnabled,
                isActive = true,
                lastSyncedAt = 0
            )
            providerDao.update(updated)
            updated.toPublicDomain()
        } else {
            val provider = Provider(
                name = providerName,
                type = ProviderType.M3U,
                serverUrl = normalizedUrl,
                m3uUrl = normalizedUrl,
                epgSyncMode = epgSyncMode,
                m3uVodClassificationEnabled = m3uVodClassificationEnabled,
                status = ProviderStatus.ACTIVE
            )
            val newId = providerDao.insert(provider.toSecureEntity())
            provider.copy(id = newId).copy(password = "")
        }

        providerDao.deactivateAll()
        providerDao.activate(providerData.id)
        when (val syncResult = syncManager.sync(providerData.id, force = false, onProgress = onProgress)) {
            is Result.Success -> {
                val finalStatus = if (syncManager.currentSyncState(providerData.id) is SyncState.Partial) {
                    ProviderStatus.PARTIAL
                } else {
                    ProviderStatus.ACTIVE
                }
                updateProviderSyncStatus(providerData.id, finalStatus, System.currentTimeMillis())
                maybeScheduleBackgroundEpgSync(providerData.id)
                Result.success(providerData.copy(status = finalStatus))
            }
            is Result.Error -> {
                updateProviderSyncStatus(providerData.id, ProviderStatus.ERROR)
                Result.error(
                    "Playlist saved, but initial sync failed. The provider was saved and can be retried from Settings: ${syncResult.message}",
                    syncResult.exception
                )
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    } catch (e: Exception) {
        Result.error("Failed to add M3U provider: ${e.message}", e)
    }

    override suspend fun loginStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        epgSyncMode: ProviderEpgSyncMode,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        val normalizedPortalUrl = ProviderInputSanitizer.normalizeUrl(portalUrl)
        val normalizedMacAddress = ProviderInputSanitizer.normalizeMacAddress(macAddress)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val normalizedDeviceProfile = ProviderInputSanitizer.normalizeDeviceProfile(deviceProfile)
        val normalizedTimezone = ProviderInputSanitizer.normalizeTimezone(timezone)
        val normalizedLocale = ProviderInputSanitizer.normalizeLocale(locale)

        ProviderInputSanitizer.validateUrl(normalizedPortalUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateStalkerPortalUrl(normalizedPortalUrl)?.let { message ->
            return Result.error(message)
        }
        ProviderInputSanitizer.validateMacAddress(normalizedMacAddress)?.let { message ->
            return Result.error(message)
        }

        onProgress?.invoke("Authenticating...")
        val existingProvider = if (id != null) {
            providerDao.getById(id)
        } else {
            providerDao.getByUrlAndUser(normalizedPortalUrl, "", normalizedMacAddress)
        }

        val provider = createStalkerProvider(
            providerId = 0L,
            portalUrl = normalizedPortalUrl,
            macAddress = normalizedMacAddress,
            deviceProfile = normalizedDeviceProfile,
            timezone = normalizedTimezone,
            locale = normalizedLocale
        )

        return when (val authResult = provider.authenticate()) {
            is Result.Success -> {
                val providerData = if (existingProvider != null) {
                    onProgress?.invoke("Updating existing provider...")
                    val updated = authResult.data.copy(
                        id = existingProvider.id,
                        name = normalizedName.ifBlank { existingProvider.name },
                        serverUrl = normalizedPortalUrl,
                        stalkerMacAddress = normalizedMacAddress,
                        stalkerDeviceProfile = normalizedDeviceProfile,
                        stalkerDeviceTimezone = normalizedTimezone,
                        stalkerDeviceLocale = normalizedLocale,
                        epgUrl = existingProvider.epgUrl,
                        epgSyncMode = epgSyncMode,
                        xtreamFastSyncEnabled = false,
                        m3uVodClassificationEnabled = false,
                        isActive = true,
                        lastSyncedAt = 0L,
                        createdAt = existingProvider.createdAt
                    )
                    providerDao.update(updated.toSecureEntity())
                    updated.copy(password = "")
                } else {
                    val newData = authResult.data.copy(
                        name = normalizedName.ifBlank { authResult.data.name },
                        serverUrl = normalizedPortalUrl,
                        stalkerMacAddress = normalizedMacAddress,
                        stalkerDeviceProfile = normalizedDeviceProfile,
                        stalkerDeviceTimezone = normalizedTimezone,
                        stalkerDeviceLocale = normalizedLocale,
                        epgSyncMode = epgSyncMode,
                        xtreamFastSyncEnabled = false,
                        m3uVodClassificationEnabled = false
                    )
                    val newId = providerDao.insert(newData.toSecureEntity())
                    newData.copy(id = newId).copy(password = "")
                }

                providerDao.setActive(providerData.id)
                when (val syncResult = syncManager.sync(providerData.id, force = false, onProgress = onProgress)) {
                    is Result.Success -> {
                        val finalStatus = if (syncManager.currentSyncState(providerData.id) is SyncState.Partial) {
                            ProviderStatus.PARTIAL
                        } else {
                            ProviderStatus.ACTIVE
                        }
                        updateProviderSyncStatus(providerData.id, finalStatus, System.currentTimeMillis())
                        maybeScheduleBackgroundEpgSync(providerData.id)
                        Result.success(providerData.copy(status = finalStatus))
                    }
                    is Result.Error -> {
                        updateProviderSyncStatus(providerData.id, ProviderStatus.ERROR)
                        Result.error(
                            "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: ${syncResult.message}",
                            syncResult.exception
                        )
                    }
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    /**
     * Delegates to [SyncManager] — the single source of truth for the full sync pipeline.
     */
    override suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean,
        movieFastSyncOverride: Boolean?,
        epgSyncModeOverride: ProviderEpgSyncMode?,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> {
        return when (
            val syncResult = syncManager.sync(
                providerId,
                force = force,
                movieFastSyncOverride = movieFastSyncOverride,
                epgSyncModeOverride = epgSyncModeOverride,
                onProgress = onProgress
            )
        ) {
            is Result.Success -> {
                val finalStatus = if (syncManager.currentSyncState(providerId) is SyncState.Partial) {
                    ProviderStatus.PARTIAL
                } else {
                    ProviderStatus.ACTIVE
                }
                updateProviderSyncStatus(providerId, finalStatus, System.currentTimeMillis())
                maybeScheduleBackgroundEpgSync(providerId)
                syncResult
            }
            is Result.Error -> {
                updateProviderSyncStatus(providerId, ProviderStatus.ERROR)
                syncResult
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int
    ): Result<List<Program>> {
        val providerEntity = providerDao.getById(providerId)
            ?: return Result.error("Provider $providerId not found")
        return when (providerEntity.type) {
            ProviderType.XTREAM_CODES -> when (val providerContextResult = createXtreamLiveProgramProviderContext(providerId)) {
                is Result.Success -> {
                    val result = fetchXtreamProgramsForLiveStream(
                        providerId = providerId,
                        streamId = streamId,
                        epgChannelId = epgChannelId,
                        limit = limit,
                        xtreamProvider = providerContextResult.data
                    )
                    if (result is Result.Success && result.data.isNotEmpty()) {
                        cacheProgramsForChannel(providerId, result.data)
                        refreshCachedEpgMetadata(providerId)
                    }
                    result
                }
                is Result.Error -> Result.error(providerContextResult.message, providerContextResult.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
            ProviderType.STALKER_PORTAL -> {
                val stalkerProvider = createStalkerProviderFromEntity(providerEntity)
                val channelKey = epgChannelId?.takeIf { it.isNotBlank() } ?: streamId.toString()
                when (val result = stalkerProvider.getShortEpg(channelKey, limit)) {
                    is Result.Success -> {
                        if (result.data.isNotEmpty()) {
                            cacheProgramsForChannel(providerId, result.data)
                            refreshCachedEpgMetadata(providerId)
                        }
                        result
                    }
                    is Result.Error -> Result.error(result.message, result.exception)
                    is Result.Loading -> Result.error("Unexpected loading state")
                }
            }
            ProviderType.M3U -> Result.error("On-demand guide lookup is unavailable for this provider.")
        }
    }

    override suspend fun getProgramsForLiveStreams(
        providerId: Long,
        requests: List<LiveStreamProgramRequest>,
        limit: Int
    ): Map<LiveStreamProgramRequest, Result<List<Program>>> {
        val normalizedRequests = requests
            .filter { it.streamId > 0L }
            .distinct()
        if (normalizedRequests.isEmpty()) {
            return emptyMap()
        }

        val providerEntity = providerDao.getById(providerId)
            ?: return normalizedRequests.associateWith { Result.error("Provider $providerId not found") }

        return when (providerEntity.type) {
            ProviderType.XTREAM_CODES -> when (val providerContextResult = createXtreamLiveProgramProviderContext(providerId)) {
                is Result.Success -> coroutineScope {
                    val requestDispatcher = Dispatchers.IO.limitedParallelism(XTREAM_GUIDE_BATCH_CONCURRENCY)
                    normalizedRequests
                        .map { request ->
                            async(requestDispatcher) {
                                request to fetchXtreamProgramsForLiveStream(
                                    providerId = providerId,
                                    streamId = request.streamId,
                                    epgChannelId = request.epgChannelId,
                                    limit = limit,
                                    xtreamProvider = providerContextResult.data
                                )
                            }
                        }
                        .awaitAll()
                        .also { results ->
                            val cachedPrograms = results
                                .mapNotNull { (_, result) -> (result as? Result.Success)?.data }
                                .flatten()
                            if (cachedPrograms.isNotEmpty()) {
                                cacheProgramsForChannels(providerId, cachedPrograms)
                                refreshCachedEpgMetadata(providerId)
                            }
                        }
                        .toMap()
                }
                is Result.Error -> normalizedRequests.associateWith {
                    Result.error(providerContextResult.message, providerContextResult.exception)
                }
                is Result.Loading -> normalizedRequests.associateWith {
                    Result.error("Unexpected loading state")
                }
            }
            ProviderType.STALKER_PORTAL -> {
                val stalkerProvider = createStalkerProviderFromEntity(providerEntity)
                val results = normalizedRequests.associateWith { request ->
                    stalkerProvider.getShortEpg(
                        request.epgChannelId?.takeIf { it.isNotBlank() } ?: request.streamId.toString(),
                        limit
                    )
                }
                val cachedPrograms = results.values
                    .mapNotNull { (it as? Result.Success)?.data }
                    .flatten()
                if (cachedPrograms.isNotEmpty()) {
                    cacheProgramsForChannels(providerId, cachedPrograms)
                    refreshCachedEpgMetadata(providerId)
                }
                results
            }
            ProviderType.M3U -> normalizedRequests.associateWith {
                Result.error("On-demand guide lookup is unavailable for this provider.")
            }
        }
    }

    override suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String? {
        return buildCatchUpUrls(providerId, streamId, start, end).firstOrNull()
    }

    override suspend fun buildCatchUpUrls(providerId: Long, streamId: Long, start: Long, end: Long): List<String> {
        val providerEntity = providerDao.getById(providerId) ?: return emptyList()
        val provider = providerEntity.toPublicDomain()
        val providerPassword = credentialCrypto.decryptIfNeeded(providerEntity.password)
        val channel = channelDao.getById(streamId)
        val resolvedStreamId = channel?.streamId?.takeIf { it > 0 } ?: streamId
        return when (provider.type) {
            ProviderType.XTREAM_CODES -> createXtreamProvider(
                providerId = providerId,
                serverUrl = provider.serverUrl,
                username = provider.username,
                password = providerPassword,
                allowedOutputFormats = provider.allowedOutputFormats
            ).buildCatchUpUrls(resolvedStreamId, start, end)
            ProviderType.M3U -> {
                val source = channel?.catchUpSource ?: return emptyList()
                buildM3uCatchUpUrls(source, start, end)
            }
            ProviderType.STALKER_PORTAL -> emptyList()
        }
    }

    suspend fun createXtreamProvider(
        providerId: Long,
        serverUrl: String,
        username: String,
        password: String,
        allowedOutputFormats: List<String> = emptyList()
    ): IptvProvider {
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        return XtreamProvider(
            providerId = providerId,
            api = xtreamApiService,
            serverUrl = serverUrl,
            username = username,
            password = password,
            allowedOutputFormats = allowedOutputFormats,
            enableBase64TextCompatibility = enableBase64TextCompatibility
        )
    }

    private fun createStalkerProvider(
        providerId: Long,
        portalUrl: String,
        macAddress: String,
        deviceProfile: String,
        timezone: String,
        locale: String
    ): StalkerProvider {
        return StalkerProvider(
            providerId = providerId,
            api = stalkerApiService,
            portalUrl = portalUrl,
            macAddress = macAddress,
            deviceProfile = deviceProfile,
            timezone = timezone,
            locale = locale
        )
    }

    private fun createStalkerProviderFromEntity(entity: ProviderEntity): StalkerProvider {
        return createStalkerProvider(
            providerId = entity.id,
            portalUrl = entity.serverUrl,
            macAddress = entity.stalkerMacAddress,
            deviceProfile = entity.stalkerDeviceProfile,
            timezone = entity.stalkerDeviceTimezone,
            locale = entity.stalkerDeviceLocale
        )
    }

    private fun ProviderEntity.toPublicDomain(): Provider {
        return toDomain().copy(password = "")
    }

    private suspend fun createXtreamLiveProgramProviderContext(providerId: Long): Result<XtreamProvider> {
        if (providerId <= 0L) {
            return Result.error("Live stream context is unavailable.")
        }

        val providerEntity = providerDao.getById(providerId)
            ?: return Result.error("Provider $providerId not found")
        val provider = providerEntity.toPublicDomain()
        if (provider.type != ProviderType.XTREAM_CODES) {
            return Result.error("On-demand guide lookup is available only for Xtream providers.")
        }

        val providerPassword = try {
            credentialCrypto.decryptIfNeeded(providerEntity.password)
        } catch (e: CredentialDecryptionException) {
            return Result.error(e.message ?: CredentialDecryptionException.MESSAGE, e)
        }

        return Result.success(
            createXtreamProvider(
                providerId = providerId,
                serverUrl = provider.serverUrl,
                username = provider.username,
                password = providerPassword,
                allowedOutputFormats = provider.allowedOutputFormats
            ) as XtreamProvider
        )
    }

    private suspend fun fetchXtreamProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int,
        xtreamProvider: XtreamProvider
    ): Result<List<Program>> {
        if (providerId <= 0L || streamId <= 0L) {
            return Result.error("Live stream context is unavailable.")
        }

        val shortProgramsResult = xtreamProvider.getShortEpg(
            channelId = streamId.toString(),
            limit = limit.coerceAtLeast(1)
        )
        val shortPrograms = (shortProgramsResult as? Result.Success)?.data
            ?.sortedBy { it.startTime }
            .orEmpty()
        if (shortPrograms.isNotEmpty()) {
            return Result.success(
                normalizeXtreamPrograms(
                    providerId = providerId,
                    channelId = epgChannelId ?: streamId.toString(),
                    programs = shortPrograms
                )
            )
        }

        return when (val fullProgramsResult = xtreamProvider.getEpg(streamId.toString())) {
            is Result.Success -> {
                val normalizedPrograms = normalizeXtreamPrograms(
                    providerId = providerId,
                    channelId = epgChannelId ?: streamId.toString(),
                    programs = fullProgramsResult.data.sortedBy { it.startTime }
                )
                Result.success(normalizedPrograms)
            }
            is Result.Error -> {
                val shortError = shortProgramsResult as? Result.Error
                val combinedMessage = listOfNotNull(
                    shortError?.message?.takeIf { it.isNotBlank() },
                    fullProgramsResult.message.takeIf { it.isNotBlank() }
                )
                    .distinct()
                    .joinToString(separator = " / ")
                    .ifBlank { "Failed to load on-demand guide" }
                Result.error(combinedMessage, fullProgramsResult.exception ?: shortError?.exception)
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private fun Provider.toSecureEntity(): ProviderEntity {
        val encryptedPassword = credentialCrypto.encryptIfNeeded(password)
        return copy(password = encryptedPassword).toEntity()
    }

    private suspend fun updateProviderSyncStatus(
        providerId: Long,
        status: ProviderStatus,
        lastSyncedAt: Long? = null
    ) {
        val current = providerDao.getById(providerId) ?: return
        val updated = current.copy(
            status = status,
            lastSyncedAt = lastSyncedAt ?: current.lastSyncedAt
        )
        providerDao.update(updated)
    }

    private suspend fun maybeScheduleBackgroundEpgSync(providerId: Long) {
        val provider = providerDao.getById(providerId) ?: return
        if (provider.epgSyncMode != ProviderEpgSyncMode.BACKGROUND) {
            return
        }
        // The previous implementation launched a coroutine that slept for 15s and then
        // scheduled the worker. That kept a coroutine alive (and held onto its captures)
        // even when the user immediately backed out of the screen. WorkManager's own
        // initialDelay is the right place for that wait — it's persisted, cancellable,
        // and doesn't pin any process state.
        syncManager.scheduleBackgroundEpgSync(providerId)
    }

    private fun normalizeXtreamPrograms(
        providerId: Long,
        channelId: String,
        programs: List<Program>
    ): List<Program> {
        return programs.map { program ->
            program.copy(
                providerId = providerId,
                channelId = channelId
            )
        }
    }

    private suspend fun cacheProgramsForChannel(providerId: Long, programs: List<Program>) {
        val channelId = programs.firstOrNull()?.channelId ?: return
        transactionRunner.inTransaction {
            programDao.deleteForChannel(providerId, channelId)
            programDao.insertAll(programs.map { it.toEntity().copy(providerId = providerId) })
        }
    }

    private suspend fun cacheProgramsForChannels(providerId: Long, programs: List<Program>) {
        if (programs.isEmpty()) return
        val programsByChannel = programs.groupBy { it.channelId }
        transactionRunner.inTransaction {
            programsByChannel.forEach { (channelId, channelPrograms) ->
                programDao.deleteForChannel(providerId, channelId)
                programDao.insertAll(channelPrograms.map { it.toEntity().copy(providerId = providerId) })
            }
        }
    }

    private suspend fun refreshCachedEpgMetadata(providerId: Long) {
        val now = System.currentTimeMillis()
        val metadata = (syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)).copy(
            lastEpgSync = now,
            lastEpgSuccess = now,
            epgCount = programDao.countByProvider(providerId)
        )
        syncMetadataRepository.updateMetadata(metadata)
    }
}

internal fun buildM3uCatchUpUrls(source: String, start: Long, end: Long): List<String> {
    val trimmedSource = source.trim()
    if (trimmedSource.isBlank()) return emptyList()

    val durationSeconds = (end - start).coerceAtLeast(0L)
    val durationMinutes = (durationSeconds / 60L).coerceAtLeast(1L)
    val startDate = java.time.Instant.ofEpochSecond(start).atZone(java.time.ZoneOffset.UTC)
    val endDate = java.time.Instant.ofEpochSecond(end).atZone(java.time.ZoneOffset.UTC)
    val compactStart = startDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    val compactEnd = endDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

    val replacements = linkedMapOf(
        "{start}" to start.toString(),
        "{end}" to end.toString(),
        "{duration}" to durationSeconds.toString(),
        "{duration_seconds}" to durationSeconds.toString(),
        "{duration_minutes}" to durationMinutes.toString(),
        "{utc}" to start.toString(),
        "{utcend}" to end.toString(),
        "{lutc}" to end.toString(),
        "{timestamp}" to start.toString(),
        "{Y}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyy")),
        "{m}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("MM")),
        "{d}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("dd")),
        "{H}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("HH")),
        "{M}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("mm")),
        "{S}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("ss")),
        "{Ymd}" to startDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")),
        "{YmdHis}" to compactStart,
        "{utc:yyyyMMddHHmmss}" to compactStart,
        "{utcend:yyyyMMddHHmmss}" to compactEnd
    )

    val expanded = replacements.entries.fold(trimmedSource) { current, (placeholder, value) ->
        current.replace(placeholder, value)
    }

    return listOf(expanded).distinct()
}
