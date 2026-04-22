package com.streamvault.data.sync

import android.content.Context
import android.util.Log
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelGuideSyncEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamRequestException
import com.streamvault.data.remote.xtream.XtreamResponseTooLargeException
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

private const val TAG = "SyncManager"
private const val XTREAM_CATALOG_PAGE_SIZE = 250
private const val XTREAM_CATALOG_MAX_PAGES = 200
private const val XTREAM_FALLBACK_STAGE_BATCH_SIZE = 500
private const val MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Movies category-bulk sync downgraded to sequential mode after provider stress signals."
private const val LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Live category sync downgraded to sequential mode after provider stress signals."
private const val SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Series category sync downgraded to sequential mode after provider stress signals."
private const val MOVIE_PAGED_SEQUENTIAL_MODE_WARNING =
    "Movies paged sync downgraded to sequential mode after provider stress signals."
private const val SERIES_PAGED_SEQUENTIAL_MODE_WARNING =
    "Series paged sync downgraded to sequential mode after provider stress signals."
private const val XTREAM_RECOVERY_ABORT_WARNING_SUFFIX =
    "recovery stopped early after repeated provider stress signals; keeping recovered results."
private const val XTREAM_AVOID_FULL_CATALOG_COOLDOWN_MILLIS = 6 * 60 * 60 * 1000L
private const val XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val STALKER_GUIDE_PROGRAM_BATCH_SIZE = 500

enum class SyncRepairSection {
    LIVE,
    EPG,
    MOVIES,
    SERIES
}

@Singleton
class SyncManager @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val providerDao: ProviderDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val programDao: ProgramDao,
    private val categoryDao: CategoryDao,
    private val catalogSyncDao: CatalogSyncDao,
    private val tmdbIdentityDao: TmdbIdentityDao,
    private val stalkerApiService: StalkerApiService,
    private val xtreamJson: Json,
    private val m3uParser: M3uParser,
    private val epgRepository: EpgRepository,
    private val epgSourceRepository: EpgSourceRepository,
    private val okHttpClient: OkHttpClient,
    private val credentialCrypto: CredentialCrypto,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val transactionRunner: DatabaseTransactionRunner,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository
) {
    private val syncStateTracker = SyncStateTracker()
    private val syncErrorSanitizer = SyncErrorSanitizer()
    private val xtreamAdaptiveSyncPolicy = XtreamAdaptiveSyncPolicy()
    private val syncCatalogStore = SyncCatalogStore(
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        categoryDao = categoryDao,
        catalogSyncDao = catalogSyncDao,
        tmdbIdentityDao = tmdbIdentityDao,
        transactionRunner = transactionRunner
    )
    private val m3uImporter = SyncManagerM3uImporter(
        m3uParser = m3uParser,
        okHttpClient = okHttpClient,
        syncCatalogStore = syncCatalogStore,
        retryTransient = { block -> retryTransient(block = block) },
        progress = ::progress
    )
    private val xtreamSupport = SyncManagerXtreamSupport(
        adaptiveSyncPolicy = xtreamAdaptiveSyncPolicy,
        shouldRememberSequentialPreference = ::shouldRememberSequentialPreference,
        sanitizeThrowableMessage = ::sanitizeThrowableMessage,
        progress = ::progress,
        movieRequestTimeoutMillis = XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS,
        seriesRequestTimeoutMillis = XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS,
        recoveryAbortWarningSuffix = XTREAM_RECOVERY_ABORT_WARNING_SUFFIX
    )
    val syncState: StateFlow<SyncState> = syncStateTracker.aggregateState
    val syncStatesByProvider: StateFlow<Map<Long, SyncState>> = syncStateTracker.statesByProvider
    private val providerSyncMutexes = ConcurrentHashMap<Long, Mutex>()
    private val syncAdmissionMutex = Mutex()
    private val xtreamCatalogHttpService: OkHttpXtreamApiService by lazy {
        OkHttpXtreamApiService(
            client = okHttpClient,
            json = xtreamJson
        )
    }
    private val xtreamCatalogApiService: XtreamApiService by lazy { xtreamCatalogHttpService }
    private val xtreamFetcher: SyncManagerXtreamFetcher by lazy {
        SyncManagerXtreamFetcher(
            xtreamCatalogApiService = xtreamCatalogApiService,
            xtreamSupport = xtreamSupport,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage,
            pageSize = XTREAM_CATALOG_PAGE_SIZE
        )
    }
    private val catalogStrategySupport = SyncManagerCatalogStrategySupport(
        shouldRememberSequentialPreference = ::shouldRememberSequentialPreference,
        avoidFullCatalogCooldownMillis = XTREAM_AVOID_FULL_CATALOG_COOLDOWN_MILLIS
    )
    private val catalogStager = SyncManagerCatalogStager(
        syncCatalogStore = syncCatalogStore,
        fallbackStageBatchSize = XTREAM_FALLBACK_STAGE_BATCH_SIZE
    )
    private val xtreamLiveStrategy: SyncManagerXtreamLiveStrategy by lazy {
        SyncManagerXtreamLiveStrategy(
            xtreamCatalogApiService = xtreamCatalogApiService,
            xtreamCatalogHttpService = xtreamCatalogHttpService,
            xtreamAdaptiveSyncPolicy = xtreamAdaptiveSyncPolicy,
            xtreamSupport = xtreamSupport,
            xtreamFetcher = xtreamFetcher,
            catalogStrategySupport = catalogStrategySupport,
            syncCatalogStore = syncCatalogStore,
            progress = ::progress,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage,
            fullCatalogFallbackWarning = ::fullCatalogFallbackWarning,
            categoryFailureWarning = ::categoryFailureWarning,
            liveCategorySequentialModeWarning = LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING,
            stageChannelItems = catalogStager::stageChannelItems,
            fallbackStageBatchSize = XTREAM_FALLBACK_STAGE_BATCH_SIZE
        )
    }
    private val xtreamMovieStrategy: SyncManagerXtreamMovieStrategy by lazy {
        SyncManagerXtreamMovieStrategy(
            xtreamCatalogApiService = xtreamCatalogApiService,
            xtreamCatalogHttpService = xtreamCatalogHttpService,
            xtreamAdaptiveSyncPolicy = xtreamAdaptiveSyncPolicy,
            xtreamSupport = xtreamSupport,
            xtreamFetcher = xtreamFetcher,
            catalogStrategySupport = catalogStrategySupport,
            syncCatalogStore = syncCatalogStore,
            progress = ::progress,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage,
            fullCatalogFallbackWarning = ::fullCatalogFallbackWarning,
            categoryFailureWarning = ::categoryFailureWarning,
            pagingFailureWarning = ::pagingFailureWarning,
            stageMovieItems = catalogStager::stageMovieItems,
            stageMovieSequence = catalogStager::stageMovieSequence,
            movieCategorySequentialModeWarning = MOVIE_CATEGORY_SEQUENTIAL_MODE_WARNING,
            moviePagedSequentialModeWarning = MOVIE_PAGED_SEQUENTIAL_MODE_WARNING,
            fallbackStageBatchSize = XTREAM_FALLBACK_STAGE_BATCH_SIZE,
            maxCatalogPages = XTREAM_CATALOG_MAX_PAGES,
            pageSize = XTREAM_CATALOG_PAGE_SIZE
        )
    }
    private val xtreamSeriesStrategy: SyncManagerXtreamSeriesStrategy by lazy {
        SyncManagerXtreamSeriesStrategy(
            xtreamCatalogApiService = xtreamCatalogApiService,
            xtreamCatalogHttpService = xtreamCatalogHttpService,
            xtreamAdaptiveSyncPolicy = xtreamAdaptiveSyncPolicy,
            xtreamSupport = xtreamSupport,
            xtreamFetcher = xtreamFetcher,
            catalogStrategySupport = catalogStrategySupport,
            syncCatalogStore = syncCatalogStore,
            progress = ::progress,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage,
            fullCatalogFallbackWarning = ::fullCatalogFallbackWarning,
            categoryFailureWarning = ::categoryFailureWarning,
            pagingFailureWarning = ::pagingFailureWarning,
            stageSeriesItems = catalogStager::stageSeriesItems,
            stageSeriesSequence = catalogStager::stageSeriesSequence,
            categorySequentialModeWarning = SERIES_CATEGORY_SEQUENTIAL_MODE_WARNING,
            pagedSequentialModeWarning = SERIES_PAGED_SEQUENTIAL_MODE_WARNING,
            fallbackStageBatchSize = XTREAM_FALLBACK_STAGE_BATCH_SIZE,
            maxCatalogPages = XTREAM_CATALOG_MAX_PAGES,
            pageSize = XTREAM_CATALOG_PAGE_SIZE
        )
    }

    fun syncStateForProvider(providerId: Long): Flow<SyncState> =
        syncStatesByProvider.map { states -> states[providerId] ?: SyncState.Idle }

    fun currentSyncState(providerId: Long): SyncState =
        syncStateTracker.current(providerId)

    /** Returns true if any provider sync mutex is currently held (used by DatabaseMaintenanceManager). */
    fun isAnySyncActive(): Boolean = providerSyncMutexes.values.any { it.isLocked }
    private suspend fun <T> withProviderLock(providerId: Long, block: suspend () -> T): T {
        val mutex = syncAdmissionMutex.withLock {
            providerSyncMutexes.computeIfAbsent(providerId) { Mutex() }.also { providerMutex ->
                providerMutex.lock()
            }
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun runWhenNoSyncActive(block: suspend () -> Boolean): Boolean =
        syncAdmissionMutex.withLock {
            if (providerSyncMutexes.values.any { it.isLocked }) {
                false
            } else {
                block()
            }
        }

    suspend fun onProviderDeleted(providerId: Long) {
        BackgroundEpgSyncWorker.cancel(applicationContext, providerId)
        withProviderLock(providerId) {
            syncStateTracker.reset(providerId)
            xtreamAdaptiveSyncPolicy.forgetProvider(providerId)
            syncCatalogStore.clearProviderStaging(providerId)
            epgRepository.onProviderDeleted(providerId)
            providerSyncMutexes.remove(providerId)
        }
    }

    fun scheduleBackgroundEpgSync(providerId: Long) {
        BackgroundEpgSyncWorker.enqueue(applicationContext, providerId)
    }

    suspend fun syncEpg(
        providerId: Long,
        force: Boolean = true,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()

        publishSyncState(providerId, SyncState.Syncing("Downloading EPG..."))

        try {
            val warnings = syncProviderEpg(
                provider = provider,
                metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id),
                now = System.currentTimeMillis(),
                force = force,
                onProgress = onProgress
            )
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (warnings.isEmpty()) "SUCCESS" else "PARTIAL"
            )
            publishSyncState(
                providerId,
                if (warnings.isEmpty()) {
                    SyncState.Success()
                } else {
                    SyncState.Partial("EPG sync completed with warnings", warnings)
                }
            )
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "EPG sync failed")
            Log.e(TAG, "EPG sync failed for provider $providerId: ${syncErrorSanitizer.throwableMessage(e)}")
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    private fun shouldSyncEpgUpfront(provider: Provider): Boolean =
        provider.epgSyncMode == ProviderEpgSyncMode.UPFRONT

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
            .let { resolvedProvider ->
                resolvedProvider.copy(
                    xtreamFastSyncEnabled = movieFastSyncOverride ?: resolvedProvider.xtreamFastSyncEnabled,
                    epgSyncMode = epgSyncModeOverride ?: resolvedProvider.epgSyncMode
                )
            }
        publishSyncState(providerId, SyncState.Syncing("Starting..."))

        try {
            val outcome = withContext(Dispatchers.IO) {
                when (provider.type) {
                    ProviderType.XTREAM_CODES -> syncXtream(provider, force, onProgress)
                    ProviderType.M3U -> syncM3u(provider, force, onProgress)
                    ProviderType.STALKER_PORTAL -> syncStalker(provider, force, onProgress)
                }
            }
            providerDao.updateSyncTime(providerId, System.currentTimeMillis())
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (outcome.partial) "PARTIAL" else "SUCCESS"
            )
            publishSyncState(providerId, if (outcome.partial) {
                SyncState.Partial("Sync completed with warnings", outcome.warnings)
            } else {
                SyncState.Success()
            })
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "Sync failed")
            Log.e(TAG, "Sync failed for provider $providerId: ${syncErrorSanitizer.throwableMessage(e)}")
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    fun resetState(providerId: Long? = null) {
        syncStateTracker.reset(providerId)
    }

    suspend fun retrySection(
        providerId: Long,
        section: SyncRepairSection,
        movieFastSyncOverride: Boolean? = null,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
            .let { resolvedProvider ->
                movieFastSyncOverride?.let { override ->
                    resolvedProvider.copy(xtreamFastSyncEnabled = override)
                } ?: resolvedProvider
            }

        try {
            withContext(Dispatchers.IO) {
                when (section) {
                    SyncRepairSection.LIVE -> syncLiveOnly(provider, onProgress)
                    SyncRepairSection.EPG -> syncEpgOnly(provider, onProgress)
                    SyncRepairSection.MOVIES -> syncMoviesOnly(provider, onProgress)
                    SyncRepairSection.SERIES -> syncSeriesOnly(provider, onProgress)
                }
            }
            updateSyncStatusMetadata(providerId = providerId, status = "SUCCESS")
            publishSyncState(providerId, SyncState.Success())
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "Retry failed")
            Log.e(TAG, "Section retry failed for provider $providerId [$section]: ${syncErrorSanitizer.throwableMessage(e)}")
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    private suspend fun syncXtream(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        UrlSecurityPolicy.validateXtreamServerUrl(provider.serverUrl)?.let { message ->
            throw IllegalStateException(message)
        }
        progress(provider.id, onProgress, "Connecting to server...")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)

        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Live TV...")
            val liveSyncResult = syncXtreamLiveCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                onProgress = onProgress
            )
            val liveSequentialStress = liveSyncResult.strategyFeedback.segmentedStressDetected
            val liveProviderAdaptation = updateSequentialProviderAdaptation(
                previousRemembered = metadata.liveSequentialFailuresRemembered,
                previousHealthyStreak = metadata.liveHealthySyncStreak,
                sawSequentialStress = liveSequentialStress
            )
            val liveAvoidFullUntil = updateAvoidFullUntil(
                previousAvoidFullUntil = metadata.liveAvoidFullUntil,
                now = now,
                feedback = liveSyncResult.strategyFeedback
            )

            when (val liveResult = liveSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    val acceptedCount = liveSyncResult.stagedSessionId?.let { sessionId ->
                        if (hiddenLiveCategoryIds.isNotEmpty()) {
                            mergeHiddenChannelsIntoStaging(provider.id, sessionId, hiddenLiveCategoryIds)
                        }
                        syncCatalogStore.applyStagedLiveCatalog(
                            provider.id,
                            sessionId,
                            liveSyncResult.categories
                        )
                        liveSyncResult.stagedAcceptedCount
                    } ?: run {
                        val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                            providerId = provider.id,
                            visibleCategories = liveSyncResult.categories,
                            visibleChannels = liveResult.items.map { it.toEntity() },
                            hiddenLiveCategoryIds = hiddenLiveCategoryIds
                        )
                        syncCatalogStore.replaceLiveCatalog(
                            providerId = provider.id,
                            categories = liveCatalog.categories,
                            channels = liveCatalog.channels
                        )
                    }
                    metadata = metadata.copy(
                        lastLiveSync = now,
                        lastLiveSuccess = now,
                        liveCount = acceptedCount,
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                        liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
                    )
                    warnings += liveSyncResult.warnings + liveResult.warnings
                }
                is CatalogStrategyResult.Partial -> {
                    val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                        providerId = provider.id,
                        visibleCategories = liveSyncResult.categories,
                        visibleChannels = liveResult.items.map { it.toEntity() },
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds
                    )
                    val acceptedCount = syncCatalogStore.replaceLiveCatalog(
                        providerId = provider.id,
                        categories = liveCatalog.categories,
                        channels = liveCatalog.channels
                    )
                    metadata = metadata.copy(
                        lastLiveSync = now,
                        liveCount = acceptedCount,
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = metadata.liveSequentialFailuresRemembered || liveSequentialStress,
                        liveHealthySyncStreak = 0
                    )
                    warnings += liveSyncResult.warnings + liveResult.warnings + listOf("Live TV sync completed partially.")
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    Log.w(TAG, "Live TV sync kept existing catalog for provider ${provider.id}: empty valid result, existingCount=$existingChannelCount")
                    metadata = metadata.copy(
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = metadata.liveSequentialFailuresRemembered || liveSequentialStress,
                        liveHealthySyncStreak = 0
                    )
                    if (existingChannelCount > 0) {
                        warnings += liveSyncResult.warnings + liveResult.warnings +
                            listOf("Live TV refresh returned an empty valid catalog; keeping previous channel library.")
                    }
                }
                is CatalogStrategyResult.Failure -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    Log.w(TAG, "Live TV sync preserved previous catalog for provider ${provider.id}: strategy=${liveResult.strategyName}, existingCount=$existingChannelCount, reason=${sanitizeThrowableMessage(liveResult.error)}")
                    metadata = metadata.copy(
                        liveAvoidFullUntil = liveAvoidFullUntil,
                        liveSequentialFailuresRemembered = metadata.liveSequentialFailuresRemembered || liveSequentialStress || shouldRememberSequentialPreference(liveResult.error),
                        liveHealthySyncStreak = 0
                    )
                    warnings += liveSyncResult.warnings + liveResult.warnings +
                        if (existingChannelCount > 0) {
                            listOf("Live TV sync degraded; keeping previous channel library.")
                        } else {
                            listOf("Live TV sync failed before any usable channel catalog was available.")
                        }
                    if (existingChannelCount == 0) {
                        throw IllegalStateException(
                            "Failed to fetch live streams: ${syncErrorSanitizer.throwableMessage(liveResult.error)}"
                        )
                    }
                }
            }
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastMovieSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(
                provider.id,
                onProgress,
                "Downloading Movies..."
            )
            val movieSyncResult = syncXtreamMoviesCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                onProgress = onProgress
            )
            val sawSequentialStress = movieSyncResult.strategyFeedback.segmentedStressDetected
            val healthyMovieProviderAdaptation = updateSequentialProviderAdaptation(
                previousRemembered = metadata.movieParallelFailuresRemembered,
                previousHealthyStreak = metadata.movieHealthySyncStreak,
                sawSequentialStress = sawSequentialStress
            )
            val movieAvoidFullUntil = updateAvoidFullUntil(
                previousAvoidFullUntil = metadata.movieAvoidFullUntil,
                now = now,
                feedback = movieSyncResult.strategyFeedback
            )

            when (val catalogResult = movieSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    val acceptedCount = movieSyncResult.stagedSessionId?.let { sessionId ->
                        syncCatalogStore.applyStagedMovieCatalog(provider.id, sessionId, movieSyncResult.categories)
                        movieSyncResult.stagedAcceptedCount
                    } ?: syncCatalogStore.replaceMovieCatalog(
                        providerId = provider.id,
                        categories = movieSyncResult.categories,
                        movies = catalogResult.items.asSequence().map { movie -> movie.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMovieSuccess = now,
                        movieCount = acceptedCount,
                        movieSyncMode = movieSyncResult.syncMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = false,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = healthyMovieProviderAdaptation.rememberSequential,
                        movieHealthySyncStreak = healthyMovieProviderAdaptation.healthyStreak
                    )
                }
                is CatalogStrategyResult.Partial -> {
                    val acceptedCount = movieSyncResult.stagedSessionId?.let { sessionId ->
                        syncCatalogStore.applyStagedMovieCatalog(provider.id, sessionId, movieSyncResult.categories)
                        movieSyncResult.stagedAcceptedCount
                    } ?: syncCatalogStore.replaceMovieCatalog(
                        providerId = provider.id,
                        categories = movieSyncResult.categories,
                        movies = catalogResult.items.asSequence().map { movie -> movie.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMoviePartial = now,
                        movieCount = acceptedCount,
                        movieSyncMode = movieSyncResult.syncMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = true,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = metadata.movieParallelFailuresRemembered || sawSequentialStress,
                        movieHealthySyncStreak = 0
                    )
                    warnings += movieSyncResult.warnings + catalogResult.warnings
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingMovieCount = movieDao.getCount(provider.id).first()
                    Log.w(TAG, "Movies sync kept existing catalog for provider ${provider.id}: empty valid result, existingCount=$existingMovieCount")
                    metadata = metadata.copy(
                        lastMovieAttempt = now,
                        movieSyncMode = movieSyncResult.syncMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = metadata.movieParallelFailuresRemembered || sawSequentialStress,
                        movieHealthySyncStreak = 0
                    )
                    warnings += movieSyncResult.warnings + catalogResult.warnings +
                        if (existingMovieCount > 0) {
                            listOf("Movies refresh returned an empty valid catalog; keeping previous movie library.")
                        } else {
                            listOf("Movies refresh returned an empty valid catalog with no previous movie library.")
                        }
                }
                is CatalogStrategyResult.Failure -> {
                    val existingMovieCount = movieDao.getCount(provider.id).first()
                    val enteringLazyMode = movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY && !movieSyncResult.categories.isNullOrEmpty()
                    val finalMode = if (enteringLazyMode) {
                        VodSyncMode.LAZY_BY_CATEGORY
                    } else {
                        metadata.movieSyncMode
                    }
                    if (enteringLazyMode) {
                        movieSyncResult.categories?.let { syncCatalogStore.replaceCategories(provider.id, "MOVIE", it) }
                    }
                    Log.w(TAG, "Movies sync preserved previous catalog for provider ${provider.id}: strategy=${catalogResult.strategyName}, existingCount=$existingMovieCount, mode=${movieSyncResult.syncMode}, reason=${sanitizeThrowableMessage(catalogResult.error)}")
                    metadata = metadata.copy(
                        lastMovieSync = if (enteringLazyMode) now else metadata.lastMovieSync,
                        lastMovieAttempt = now,
                        movieSyncMode = finalMode,
                        movieWarningsCount = (movieSyncResult.warnings + catalogResult.warnings).size,
                        movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = metadata.movieParallelFailuresRemembered || sawSequentialStress || shouldRememberSequentialPreference(catalogResult.error),
                        movieHealthySyncStreak = 0
                    )
                    val isFastSyncIntentional = enteringLazyMode || catalogResult.strategyName == "fast_sync_no_categories"
                    if (!isFastSyncIntentional) {
                        warnings += movieSyncResult.warnings + catalogResult.warnings +
                            if (existingMovieCount > 0) {
                                listOf("Movies sync degraded; keeping previous movie library.")
                            } else {
                                listOf("Movies sync failed; continuing with the rest of the provider sync.")
                            }
                    }
                }
            }
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastSeriesSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Series...")
            val seriesSyncResult = syncXtreamSeriesCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                onProgress = onProgress
            )
            val seriesSequentialStress = seriesSyncResult.strategyFeedback.segmentedStressDetected
            val seriesProviderAdaptation = updateSequentialProviderAdaptation(
                previousRemembered = metadata.seriesSequentialFailuresRemembered,
                previousHealthyStreak = metadata.seriesHealthySyncStreak,
                sawSequentialStress = seriesSequentialStress
            )
            val seriesAvoidFullUntil = updateAvoidFullUntil(
                previousAvoidFullUntil = metadata.seriesAvoidFullUntil,
                now = now,
                feedback = seriesSyncResult.strategyFeedback
            )

            when (val seriesResult = seriesSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    val acceptedCount = seriesSyncResult.stagedSessionId?.let { sessionId ->
                        syncCatalogStore.applyStagedSeriesCatalog(provider.id, sessionId, seriesSyncResult.categories)
                        seriesSyncResult.stagedAcceptedCount
                    } ?: syncCatalogStore.replaceSeriesCatalog(
                        providerId = provider.id,
                        categories = seriesSyncResult.categories,
                        series = seriesResult.items.asSequence().map { it.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastSeriesSync = now,
                        lastSeriesSuccess = now,
                        seriesCount = acceptedCount,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = seriesProviderAdaptation.rememberSequential,
                        seriesHealthySyncStreak = seriesProviderAdaptation.healthyStreak
                    )
                    warnings += seriesSyncResult.warnings + seriesResult.warnings
                }
                is CatalogStrategyResult.Partial -> {
                    val acceptedCount = seriesSyncResult.stagedSessionId?.let { sessionId ->
                        syncCatalogStore.applyStagedSeriesCatalog(provider.id, sessionId, seriesSyncResult.categories)
                        seriesSyncResult.stagedAcceptedCount
                    } ?: syncCatalogStore.replaceSeriesCatalog(
                        providerId = provider.id,
                        categories = seriesSyncResult.categories,
                        series = seriesResult.items.asSequence().map { it.toEntity() }
                    )
                    metadata = metadata.copy(
                        lastSeriesSync = now,
                        seriesCount = acceptedCount,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = metadata.seriesSequentialFailuresRemembered || seriesSequentialStress,
                        seriesHealthySyncStreak = 0
                    )
                    warnings += seriesSyncResult.warnings + seriesResult.warnings + listOf("Series sync completed partially.")
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingSeriesCount = seriesDao.getCount(provider.id).first()
                    Log.w(TAG, "Series sync kept existing catalog for provider ${provider.id}: empty valid result, existingCount=$existingSeriesCount")
                    metadata = metadata.copy(
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = metadata.seriesSequentialFailuresRemembered || seriesSequentialStress,
                        seriesHealthySyncStreak = 0
                    )
                    warnings += seriesSyncResult.warnings + seriesResult.warnings +
                        if (existingSeriesCount > 0) {
                            listOf("Series refresh returned an empty valid catalog; keeping previous series library.")
                        } else {
                            listOf("Series refresh returned an empty valid catalog with no previous series library.")
                        }
                    if (existingSeriesCount == 0) {
                        throw IllegalStateException("Series catalog was empty.")
                    }
                }
                is CatalogStrategyResult.Failure -> {
                    val existingSeriesCount = seriesDao.getCount(provider.id).first()
                    val enteringLazyMode = seriesResult.strategyName == "lazy_by_category" && !seriesSyncResult.categories.isNullOrEmpty()
                    if (enteringLazyMode) {
                        seriesSyncResult.categories?.let { syncCatalogStore.replaceCategories(provider.id, "SERIES", it) }
                    }
                    Log.w(TAG, "Series sync preserved previous catalog for provider ${provider.id}: strategy=${seriesResult.strategyName}, existingCount=$existingSeriesCount, reason=${sanitizeThrowableMessage(seriesResult.error)}")
                    metadata = metadata.copy(
                        lastSeriesSync = if (enteringLazyMode) now else metadata.lastSeriesSync,
                        seriesAvoidFullUntil = seriesAvoidFullUntil,
                        seriesSequentialFailuresRemembered = metadata.seriesSequentialFailuresRemembered || seriesSequentialStress || shouldRememberSequentialPreference(seriesResult.error),
                        seriesHealthySyncStreak = 0
                    )
                    val isSeriesFastSyncIntentional = enteringLazyMode || seriesResult.strategyName == "fast_sync_no_categories"
                    if (!isSeriesFastSyncIntentional) {
                        warnings += seriesSyncResult.warnings + seriesResult.warnings +
                            if (existingSeriesCount > 0) {
                                listOf("Series sync degraded; keeping previous series library.")
                            } else {
                                listOf("Series sync failed; continuing with the rest of the provider sync.")
                            }
                    }
                }
            }
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (shouldSyncEpgUpfront(provider)) {
            warnings += syncProviderEpg(
                provider = provider,
                metadata = metadata,
                now = now,
                force = force,
                onProgress = onProgress
            )
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    private suspend fun syncM3u(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            val stats = withContext(Dispatchers.IO) { m3uImporter.importPlaylist(provider, onProgress) }
            if (stats.liveCount == 0 && stats.movieCount == 0) {
                throw IllegalStateException("Playlist is empty or contains no supported entries")
            }
            warnings += stats.warnings
            if (provider.epgUrl.isBlank() && !stats.header.tvgUrl.isNullOrBlank()) {
                providerDao.updateEpgUrl(provider.id, stats.header.tvgUrl)
            }
            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastMovieSync = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastMovieAttempt = now,
                lastMovieSuccess = now,
                liveCount = stats.liveCount,
                movieCount = stats.movieCount,
                movieSyncMode = VodSyncMode.FULL,
                movieWarningsCount = stats.warnings.size,
                movieCatalogStale = false,
                movieHealthySyncStreak = 0
            )
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (shouldSyncEpgUpfront(provider)) {
            warnings += syncProviderEpg(
                provider = provider,
                metadata = metadata,
                now = now,
                force = force,
                onProgress = onProgress
            )
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    private suspend fun syncStalker(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        UrlSecurityPolicy.validateStalkerPortalUrl(provider.serverUrl)?.let { message ->
            throw IllegalStateException(message)
        }
        progress(provider.id, onProgress, "Connecting to portal...")
        val api = createStalkerSyncProvider(provider)
        requireResult(api.authenticate(), "Failed to authenticate with portal")

        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Live TV...")
            val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
            val liveCatalogResult = loadStalkerLiveCatalog(api, provider, onProgress)
            val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                providerId = provider.id,
                visibleCategories = liveCatalogResult.categories,
                visibleChannels = liveCatalogResult.channels.map { it.toEntity() },
                hiddenLiveCategoryIds = hiddenLiveCategoryIds
            )
            val acceptedCount = syncCatalogStore.replaceLiveCatalog(
                providerId = provider.id,
                categories = liveCatalog.categories,
                channels = liveCatalog.channels
            )
            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                liveCount = acceptedCount
            )
            syncMetadataRepository.updateMetadata(metadata)
            warnings += liveCatalogResult.warnings
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastMovieSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Movies...")
            val categories = requireResult(api.getVodCategories(), "Failed to load movie categories")
            syncCatalogStore.replaceMovieCatalog(
                providerId = provider.id,
                categories = categories.map { category ->
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = category.id,
                        name = category.name,
                        parentId = category.parentId,
                        type = ContentType.MOVIE,
                        isAdult = category.isAdult
                    )
                },
                movies = emptySequence()
            )
            metadata = metadata.copy(
                lastMovieSync = now,
                lastMovieAttempt = now,
                lastMovieSuccess = now,
                movieCount = 0,
                movieSyncMode = VodSyncMode.LAZY_BY_CATEGORY,
                movieWarningsCount = 0,
                movieCatalogStale = true
            )
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastSeriesSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Series...")
            val categories = requireResult(api.getSeriesCategories(), "Failed to load series categories")
            syncCatalogStore.replaceSeriesCatalog(
                providerId = provider.id,
                categories = categories.map { category ->
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = category.id,
                        name = category.name,
                        parentId = category.parentId,
                        type = ContentType.SERIES,
                        isAdult = category.isAdult
                    )
                },
                series = emptySequence()
            )
            metadata = metadata.copy(
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                seriesCount = 0
            )
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (shouldSyncEpgUpfront(provider)) {
            warnings += syncProviderEpg(
                provider = provider,
                metadata = metadata,
                now = now,
                force = force,
                onProgress = onProgress
            )
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    private suspend fun syncProviderEpg(
        provider: Provider,
        metadata: SyncMetadata,
        now: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): List<String> {
        var updatedMetadata = metadata
        val warnings = mutableListOf<String>()
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()

        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                if (force || ContentCachePolicy.shouldRefresh(updatedMetadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now)) {
                    try {
                        progress(provider.id, onProgress, "Downloading EPG...")
                        val base = provider.serverUrl.trimEnd('/')
                        val xmltvUrl = provider.epgUrl.ifBlank {
                            XtreamUrlFactory.buildXmltvUrl(base, provider.username, provider.password)
                        }
                        UrlSecurityPolicy.validateXtreamEpgUrl(xmltvUrl)?.let { message ->
                            throw IllegalStateException(message)
                        }
                        retryTransient { epgRepository.refreshEpg(provider.id, xmltvUrl) }
                        val epgCount = programDao.countByProvider(provider.id)
                        updatedMetadata = updatedMetadata.copy(
                            lastEpgSync = now,
                            lastEpgSuccess = now,
                            epgCount = epgCount
                        )
                        syncMetadataRepository.updateMetadata(updatedMetadata)
                        if (epgCount == 0) {
                            warnings.add("EPG imported zero programs; live guide will fall back to on-demand Xtream data.")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "EPG sync failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                        warnings.add("EPG XMLTV sync failed; live guide will fall back to on-demand Xtream data.")
                    }
                }
            }

            ProviderType.M3U -> {
                val currentEpgUrl = providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
                if (!currentEpgUrl.isNullOrBlank() && (force || ContentCachePolicy.shouldRefresh(updatedMetadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now))) {
                    val epgValidationError = UrlSecurityPolicy.validateOptionalEpgUrl(currentEpgUrl)
                    if (epgValidationError != null) {
                        warnings.add(epgValidationError)
                    } else {
                        try {
                            progress(provider.id, onProgress, "Downloading EPG...")
                            retryTransient { epgRepository.refreshEpg(provider.id, currentEpgUrl) }
                            updatedMetadata = updatedMetadata.copy(
                                lastEpgSync = now,
                                lastEpgSuccess = now,
                                epgCount = programDao.countByProvider(provider.id)
                            )
                            syncMetadataRepository.updateMetadata(updatedMetadata)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "EPG sync failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                            warnings.add("EPG sync failed")
                        }
                    }
                }
            }

            ProviderType.STALKER_PORTAL -> {
                if (force || ContentCachePolicy.shouldRefresh(updatedMetadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now)) {
                    warnings += syncStalkerPreferredEpg(
                        provider = provider,
                        now = now,
                        onProgress = onProgress
                    )
                    updatedMetadata = syncMetadataRepository.getMetadata(provider.id) ?: updatedMetadata
                }
            }
        }

        try {
            progress(provider.id, onProgress, "Refreshing external EPG sources...")
            epgSourceRepository.refreshAllForProvider(provider.id)
            progress(provider.id, onProgress, "Resolving EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "External EPG resolution failed (non-fatal): ${sanitizeThrowableMessage(e)}")
            warnings.add("External EPG source refresh/resolution failed.")
        }

        return warnings
    }

    private suspend fun syncEpgOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        progress(provider.id, onProgress, "Retrying EPG...")
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        if (provider.type == ProviderType.STALKER_PORTAL) {
            syncStalkerPreferredEpg(
                provider = provider,
                now = System.currentTimeMillis(),
                onProgress = onProgress
            )
            progress(provider.id, onProgress, "Refreshing external EPG sources...")
            epgSourceRepository.refreshAllForProvider(provider.id)
            progress(provider.id, onProgress, "Resolving EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            return
        }
        val epgUrl = when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                val base = provider.serverUrl.trimEnd('/')
                provider.epgUrl.ifBlank {
                    XtreamUrlFactory.buildXmltvUrl(base, provider.username, provider.password)
                }
            }
            ProviderType.M3U -> providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
            ProviderType.STALKER_PORTAL -> providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
        }
        if (epgUrl.isBlank()) {
            if (provider.type == ProviderType.STALKER_PORTAL) {
                progress(provider.id, onProgress, "Refreshing external EPG sources...")
                epgSourceRepository.refreshAllForProvider(provider.id)
                progress(provider.id, onProgress, "Resolving EPG mappings...")
                epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
                return
            }
            throw IllegalStateException("No EPG URL configured for this provider")
        }
        val validationError = when (provider.type) {
            ProviderType.XTREAM_CODES -> UrlSecurityPolicy.validateXtreamEpgUrl(epgUrl)
            ProviderType.M3U -> UrlSecurityPolicy.validateOptionalEpgUrl(epgUrl)
            ProviderType.STALKER_PORTAL -> UrlSecurityPolicy.validateOptionalEpgUrl(epgUrl)
        }
        validationError?.let { message ->
            throw IllegalStateException(message)
        }
        retryTransient { epgRepository.refreshEpg(provider.id, epgUrl) }
        val now = System.currentTimeMillis()
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(
                lastEpgSync = now,
                lastEpgSuccess = now,
                epgCount = programDao.countByProvider(provider.id)
            )
        syncMetadataRepository.updateMetadata(metadata)

        // Also refresh external sources and run resolution
        progress(provider.id, onProgress, "Refreshing external EPG sources...")
        epgSourceRepository.refreshAllForProvider(provider.id)
        progress(provider.id, onProgress, "Resolving EPG mappings...")
        epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
    }

    private suspend fun syncStalkerPreferredEpg(
        provider: Provider,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): List<String> {
        val warnings = mutableListOf<String>()
        val currentEpgUrl = providerDao.getById(provider.id)?.epgUrl ?: provider.epgUrl
        var shouldUseNativeGuide = currentEpgUrl.isBlank()

        if (currentEpgUrl.isNotBlank()) {
            val epgValidationError = UrlSecurityPolicy.validateOptionalEpgUrl(currentEpgUrl)
            if (epgValidationError != null) {
                Log.w(TAG, "Portal XMLTV URL invalid for provider ${provider.id}: $epgValidationError")
                warnings.add("Portal XMLTV URL is invalid; using the Stalker portal guide instead.")
                shouldUseNativeGuide = true
            } else {
                try {
                    progress(provider.id, onProgress, "Downloading EPG...")
                    retryTransient { epgRepository.refreshEpg(provider.id, currentEpgUrl) }
                    val epgCount = programDao.countByProvider(provider.id)
                    if (epgCount > 0) {
                        syncMetadataRepository.updateMetadata(
                            (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)).copy(
                                lastEpgSync = now,
                                lastEpgSuccess = now,
                                epgCount = epgCount
                            )
                        )
                    } else {
                        warnings.add("Portal XMLTV imported zero programs; using the Stalker portal guide instead.")
                        shouldUseNativeGuide = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Portal XMLTV sync failed for provider ${provider.id}: ${sanitizeThrowableMessage(e)}")
                    warnings.add("Portal XMLTV sync failed; using the Stalker portal guide instead.")
                    shouldUseNativeGuide = true
                }
            }
        }

        if (shouldUseNativeGuide) {
            warnings += syncStalkerPortalEpg(
                provider = provider,
                now = now,
                onProgress = onProgress
            )
        }

        return warnings
    }

    private suspend fun syncStalkerPortalEpg(
        provider: Provider,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): List<String> {
        val channels = channelDao.getGuideSyncEntriesByProvider(provider.id)
        if (channels.isEmpty()) {
            return emptyList()
        }

        val guideRequests = channels
            .mapNotNull(::toStalkerGuideRequest)
            .distinctBy(StalkerGuideRequest::channelKey)
        if (guideRequests.isEmpty()) {
            return listOf("Stalker portal guide sync skipped because no valid guide channel IDs were available.")
        }

        val api = createStalkerSyncProvider(provider)
        val aliasToChannelKey = buildMap {
            guideRequests.forEach { request ->
                request.aliases.forEach { alias -> putIfAbsent(alias, request.channelKey) }
            }
        }
        val failedChannels = mutableListOf<String>()
        val insertBuffer = ArrayList<com.streamvault.data.local.entity.ProgramEntity>(STALKER_GUIDE_PROGRAM_BATCH_SIZE)
        var replacedExistingGuide = false
        val bulkCoveredChannelKeys = linkedSetOf<String>()

        suspend fun flushPrograms() {
            if (insertBuffer.isEmpty()) return
            val chunk = insertBuffer.toList()
            insertBuffer.clear()
            transactionRunner.inTransaction {
                if (!replacedExistingGuide) {
                    programDao.deleteByProvider(provider.id)
                    replacedExistingGuide = true
                }
                programDao.insertAll(chunk)
            }
        }

        runCatching {
            requireResult(
                api.getBulkEpg(),
                "Failed to load bulk Stalker guide"
            )
        }.onSuccess { programs ->
            programs
                .asSequence()
                .mapNotNull { program ->
                    val resolvedChannelKey = aliasToChannelKey[program.channelId] ?: return@mapNotNull null
                    bulkCoveredChannelKeys += resolvedChannelKey
                    program.copy(
                        providerId = provider.id,
                        channelId = resolvedChannelKey
                    ).toEntity()
                }
                .forEach { entity ->
                    insertBuffer += entity
                    if (insertBuffer.size >= STALKER_GUIDE_PROGRAM_BATCH_SIZE) {
                        flushPrograms()
                    }
                }
        }.onFailure { error ->
            Log.d(TAG, "Bulk Stalker portal EPG fetch unavailable for provider ${provider.id}", error)
        }

        val fallbackGuideRequests = guideRequests.filterNot { request -> request.channelKey in bulkCoveredChannelKeys }

        fallbackGuideRequests.forEachIndexed { index, request ->
            progress(provider.id, onProgress, "Downloading portal EPG... ${index + 1} of ${fallbackGuideRequests.size}")
            runCatching {
                requireResult(
                    api.getEpg(request.channelKey),
                    "Failed to load Stalker guide for ${request.channelName}"
                )
            }.onSuccess { programs ->
                programs
                    .asSequence()
                    .filter { program -> program.endTime > program.startTime }
                    .forEach { program ->
                        insertBuffer += program.copy(
                            providerId = provider.id,
                            channelId = request.channelKey
                        ).toEntity()
                        if (insertBuffer.size >= STALKER_GUIDE_PROGRAM_BATCH_SIZE) {
                            flushPrograms()
                        }
                    }
            }.onFailure { error ->
                failedChannels += request.channelName
                Log.w(
                    TAG,
                    "Stalker portal EPG fetch failed for provider ${provider.id} channel ${request.channelKey}",
                    error
                )
            }
        }

        flushPrograms()

        if (!replacedExistingGuide && failedChannels.isNotEmpty()) {
            return listOf(
                "Stalker portal guide sync failed for all ${failedChannels.size} channels; keeping existing guide data."
            )
        }

        if (!replacedExistingGuide) {
            transactionRunner.inTransaction {
                programDao.deleteByProvider(provider.id)
            }
        }

        val epgCount = programDao.countByProvider(provider.id)
        syncMetadataRepository.updateMetadata(
            (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)).copy(
                lastEpgSync = now,
                lastEpgSuccess = now,
                epgCount = epgCount
            )
        )

        val warnings = mutableListOf<String>()
        if (epgCount == 0) {
            warnings.add("Stalker portal guide import returned zero programs.")
        }
        if (failedChannels.isNotEmpty()) {
            warnings.add(
                "Stalker portal guide imported $epgCount programs, but ${failedChannels.size} channels failed (${summarizeChannelNames(failedChannels)})."
            )
        }
        return warnings
    }

    private data class StalkerGuideRequest(
        val channelKey: String,
        val channelName: String,
        val aliases: Set<String>
    )

    private fun toStalkerGuideRequest(channel: ChannelGuideSyncEntity): StalkerGuideRequest? {
        val normalizedEpgKey = channel.epgChannelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeUnless(::isLikelyPlaceholderStalkerGuideKey)
        val streamKey = channel.streamId.takeIf { it > 0L }?.toString()
        val channelKey = normalizedEpgKey ?: streamKey ?: return null
        val aliases = linkedSetOf<String>().apply {
            normalizedEpgKey?.let(::add)
            streamKey?.let(::add)
        }
        return StalkerGuideRequest(
            channelKey = channelKey,
            channelName = channel.name.ifBlank { channelKey },
            aliases = aliases
        )
    }

    private fun isLikelyPlaceholderStalkerGuideKey(value: String): Boolean {
        return when (value.trim().lowercase()) {
            "no details available",
            "n/a",
            "null",
            "none",
            "unknown" -> true
            else -> false
        }
    }

    private fun summarizeChannelNames(channelNames: List<String>): String {
        val distinctNames = channelNames.distinct()
        if (distinctNames.isEmpty()) {
            return "unknown channels"
        }
        val preview = distinctNames.take(3).joinToString()
        val remaining = distinctNames.size - 3
        return if (remaining > 0) {
            "$preview, and $remaining more"
        } else {
            preview
        }
    }

    private suspend fun syncLiveOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(provider.id, onProgress, "Retrying Live TV...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val liveSyncResult = syncXtreamLiveCatalog(
                    provider = provider,
                    api = api,
                    existingMetadata = currentMetadata,
                    hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                    onProgress = onProgress
                )
                val liveSequentialStress = liveSyncResult.strategyFeedback.segmentedStressDetected
                val liveProviderAdaptation = updateSequentialProviderAdaptation(
                    previousRemembered = currentMetadata.liveSequentialFailuresRemembered,
                    previousHealthyStreak = currentMetadata.liveHealthySyncStreak,
                    sawSequentialStress = liveSequentialStress
                )
                val liveAvoidFullUntil = updateAvoidFullUntil(
                    previousAvoidFullUntil = currentMetadata.liveAvoidFullUntil,
                    now = now,
                    feedback = liveSyncResult.strategyFeedback
                )

                when (val liveResult = liveSyncResult.catalogResult) {
                    is CatalogStrategyResult.Success -> {
                        val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                            providerId = provider.id,
                            visibleCategories = liveSyncResult.categories,
                            visibleChannels = liveResult.items.map { it.toEntity() },
                            hiddenLiveCategoryIds = hiddenLiveCategoryIds
                        )
                        val acceptedCount = syncCatalogStore.replaceLiveCatalog(
                            providerId = provider.id,
                            categories = liveCatalog.categories,
                            channels = liveCatalog.channels
                        )
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastLiveSync = now,
                                lastLiveSuccess = now,
                                liveCount = acceptedCount,
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                                liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
                            )
                        )
                    }
                    is CatalogStrategyResult.Partial -> {
                        val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                            providerId = provider.id,
                            visibleCategories = liveSyncResult.categories,
                            visibleChannels = liveResult.items.map { it.toEntity() },
                            hiddenLiveCategoryIds = hiddenLiveCategoryIds
                        )
                        val acceptedCount = syncCatalogStore.replaceLiveCatalog(
                            providerId = provider.id,
                            categories = liveCatalog.categories,
                            channels = liveCatalog.channels
                        )
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastLiveSync = now,
                                liveCount = acceptedCount,
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                                liveHealthySyncStreak = 0
                            )
                        )
                    }
                    is CatalogStrategyResult.EmptyValid -> {
                        val existingLiveCount = channelDao.getCount(provider.id).first()
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                                liveHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            if (existingLiveCount > 0) {
                                "Live TV refresh returned an empty catalog; existing library was preserved."
                            } else {
                                "Live TV catalog was empty."
                            }
                        )
                    }
                    is CatalogStrategyResult.Failure -> {
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress || shouldRememberSequentialPreference(liveResult.error),
                                liveHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            syncErrorSanitizer.userMessage(liveResult.error, "Failed to fetch live channels"),
                            liveResult.error
                        )
                    }
                }
            }
            ProviderType.M3U -> {
                progress(provider.id, onProgress, "Retrying Live TV...")
                val stats = withContext(Dispatchers.IO) {
                    m3uImporter.importPlaylist(provider, onProgress, includeLive = true, includeMovies = false)
                }
                if (stats.liveCount == 0) {
                    throw IllegalStateException("Playlist contains no live TV entries")
                }
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastLiveSync = now,
                        lastLiveSuccess = now,
                        liveCount = stats.liveCount
                )
                syncMetadataRepository.updateMetadata(metadata)
            }
            ProviderType.STALKER_PORTAL -> {
                progress(provider.id, onProgress, "Retrying Live TV...")
                val api = createStalkerSyncProvider(provider)
                val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
                val liveCatalogResult = loadStalkerLiveCatalog(api, provider, onProgress)
                val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                    providerId = provider.id,
                    visibleCategories = liveCatalogResult.categories,
                    visibleChannels = liveCatalogResult.channels.map { it.toEntity() },
                    hiddenLiveCategoryIds = hiddenLiveCategoryIds
                )
                val acceptedCount = syncCatalogStore.replaceLiveCatalog(provider.id, liveCatalog.categories, liveCatalog.channels)
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastLiveSync = now,
                        lastLiveSuccess = now,
                        liveCount = acceptedCount
                    )
                syncMetadataRepository.updateMetadata(metadata)
            }
        }
    }

    private suspend fun syncMoviesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(
                    provider.id,
                    onProgress,
                    "Retrying Movies..."
                )
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val movieSyncResult = syncXtreamMoviesCatalog(
                    provider = provider,
                    api = api,
                    existingMetadata = currentMetadata,
                    onProgress = onProgress
                )
                val sawSequentialStress = movieSyncResult.strategyFeedback.segmentedStressDetected
                val healthyMovieProviderAdaptation = updateSequentialProviderAdaptation(
                    previousRemembered = currentMetadata.movieParallelFailuresRemembered,
                    previousHealthyStreak = currentMetadata.movieHealthySyncStreak,
                    sawSequentialStress = sawSequentialStress
                )
                val movieWarnings = movieSyncResult.warnings + strategyWarnings(movieSyncResult.catalogResult)
                val movieAvoidFullUntil = updateAvoidFullUntil(
                    previousAvoidFullUntil = currentMetadata.movieAvoidFullUntil,
                    now = now,
                    feedback = movieSyncResult.strategyFeedback
                )

                val metadata = currentMetadata.copy(
                    lastMovieAttempt = now,
                    movieSyncMode = movieSyncResult.syncMode,
                    movieWarningsCount = movieWarnings.size,
                    movieCatalogStale = movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                    movieAvoidFullUntil = movieAvoidFullUntil,
                    movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress
                )

                when (val catalogResult = movieSyncResult.catalogResult) {
                    is CatalogStrategyResult.Success -> {
                        val acceptedCount = if (movieSyncResult.syncMode == VodSyncMode.FAST_SYNC) {
                            movieSyncResult.categories?.let {
                                syncCatalogStore.replaceCategories(provider.id, "MOVIE", it)
                            }
                            movieDao.getCount(provider.id).first()
                        } else {
                            movieSyncResult.stagedSessionId?.let { sessionId ->
                                syncCatalogStore.applyStagedMovieCatalog(provider.id, sessionId, movieSyncResult.categories)
                                movieSyncResult.stagedAcceptedCount
                            } ?: syncCatalogStore.replaceMovieCatalog(
                                providerId = provider.id,
                                categories = movieSyncResult.categories,
                                movies = catalogResult.items.asSequence().map { movie -> movie.toEntity() }
                            )
                        }
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                lastMovieSync = now,
                                lastMovieSuccess = now,
                                movieCount = acceptedCount,
                                movieCatalogStale = false,
                                movieAvoidFullUntil = movieAvoidFullUntil,
                                movieParallelFailuresRemembered = healthyMovieProviderAdaptation.rememberSequential,
                                movieHealthySyncStreak = healthyMovieProviderAdaptation.healthyStreak
                            )
                        )
                    }
                    is CatalogStrategyResult.Partial -> {
                        val acceptedCount = movieSyncResult.stagedSessionId?.let { sessionId ->
                            syncCatalogStore.applyStagedMovieCatalog(provider.id, sessionId, movieSyncResult.categories)
                            movieSyncResult.stagedAcceptedCount
                        } ?: syncCatalogStore.replaceMovieCatalog(
                            providerId = provider.id,
                            categories = movieSyncResult.categories,
                            movies = catalogResult.items.asSequence().map { movie -> movie.toEntity() }
                        )
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                lastMovieSync = now,
                                lastMoviePartial = now,
                                movieCount = acceptedCount,
                                movieCatalogStale = true,
                                movieAvoidFullUntil = movieAvoidFullUntil,
                                movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress,
                                movieHealthySyncStreak = 0
                            )
                        )
                    }
                    is CatalogStrategyResult.EmptyValid -> {
                        val existingMovieCount = movieDao.getCount(provider.id).first()
                        syncMetadataRepository.updateMetadata(
                            metadata.copy(
                                movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                                movieAvoidFullUntil = movieAvoidFullUntil,
                                movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress,
                                movieHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            if (existingMovieCount > 0) {
                                "Movies refresh returned an empty catalog; existing library was preserved."
                            } else {
                                "Movies refresh returned an empty catalog."
                            }
                        )
                    }
            is CatalogStrategyResult.Failure -> {
                val existingMovieCount = movieDao.getCount(provider.id).first()
                val enteringLazyMode = movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY && !movieSyncResult.categories.isNullOrEmpty()
                if (enteringLazyMode) {
                    movieSyncResult.categories?.let { syncCatalogStore.replaceCategories(provider.id, "MOVIE", it) }
                }
                syncMetadataRepository.updateMetadata(
                    metadata.copy(
                        lastMovieSync = if (enteringLazyMode) now else metadata.lastMovieSync,
                        movieCatalogStale = existingMovieCount > 0 || movieSyncResult.syncMode == VodSyncMode.LAZY_BY_CATEGORY,
                        movieAvoidFullUntil = movieAvoidFullUntil,
                        movieParallelFailuresRemembered = currentMetadata.movieParallelFailuresRemembered || sawSequentialStress || shouldRememberSequentialPreference(catalogResult.error),
                        movieHealthySyncStreak = 0
                    )
                )
                if (!enteringLazyMode) {
                    throw IllegalStateException(
                        syncErrorSanitizer.userMessage(catalogResult.error, "Failed to fetch VOD streams"),
                        catalogResult.error
                    )
                }
            }
        }
    }
            ProviderType.M3U -> {
                progress(provider.id, onProgress, "Retrying Movies...")
                val stats = withContext(Dispatchers.IO) {
                    m3uImporter.importPlaylist(provider, onProgress, includeLive = false, includeMovies = true)
                }
                if (stats.movieCount == 0) {
                    throw IllegalStateException("Playlist contains no movie entries")
                }
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMovieSuccess = now,
                        movieCount = stats.movieCount,
                        movieSyncMode = VodSyncMode.FULL,
                        movieWarningsCount = stats.warnings.size,
                        movieCatalogStale = false
                )
                syncMetadataRepository.updateMetadata(metadata)
            }
            ProviderType.STALKER_PORTAL -> {
                progress(provider.id, onProgress, "Retrying Movies...")
                val api = createStalkerSyncProvider(provider)
                val categories = requireResult(api.getVodCategories(), "Failed to load movie categories")
                val movies = loadStalkerMoviesByCategory(api, categories, onProgress)
                val acceptedCount = syncCatalogStore.replaceMovieCatalog(
                    providerId = provider.id,
                    categories = categories.map { category ->
                        CategoryEntity(
                            providerId = provider.id,
                            categoryId = category.id,
                            name = category.name,
                            parentId = category.parentId,
                            type = ContentType.MOVIE,
                            isAdult = category.isAdult
                        )
                    },
                    movies = movies.asSequence().map { it.toEntity() }
                )
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastMovieSync = now,
                        lastMovieAttempt = now,
                        lastMovieSuccess = now,
                        movieCount = acceptedCount,
                        movieSyncMode = VodSyncMode.FULL,
                        movieWarningsCount = 0,
                        movieCatalogStale = false
                    )
                syncMetadataRepository.updateMetadata(metadata)
            }
        }
    }

    private suspend fun syncSeriesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ) {
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(provider.id, onProgress, "Retrying Series...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val seriesSyncResult = syncXtreamSeriesCatalog(
                    provider = provider,
                    api = api,
                    existingMetadata = currentMetadata,
                    onProgress = onProgress
                )

                val now = System.currentTimeMillis()
                val seriesSequentialStress = seriesSyncResult.strategyFeedback.segmentedStressDetected
                val seriesProviderAdaptation = updateSequentialProviderAdaptation(
                    previousRemembered = currentMetadata.seriesSequentialFailuresRemembered,
                    previousHealthyStreak = currentMetadata.seriesHealthySyncStreak,
                    sawSequentialStress = seriesSequentialStress
                )
                val seriesAvoidFullUntil = updateAvoidFullUntil(
                    previousAvoidFullUntil = currentMetadata.seriesAvoidFullUntil,
                    now = now,
                    feedback = seriesSyncResult.strategyFeedback
                )
                when (val seriesResult = seriesSyncResult.catalogResult) {
                    is CatalogStrategyResult.Success -> {
                        val acceptedCount = seriesSyncResult.stagedSessionId?.let { sessionId ->
                            syncCatalogStore.applyStagedSeriesCatalog(provider.id, sessionId, seriesSyncResult.categories)
                            seriesSyncResult.stagedAcceptedCount
                        } ?: syncCatalogStore.replaceSeriesCatalog(
                            providerId = provider.id,
                            categories = seriesSyncResult.categories,
                            series = seriesResult.items.asSequence().map { it.toEntity() }
                        )
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastSeriesSync = now,
                                lastSeriesSuccess = now,
                                seriesCount = acceptedCount,
                                seriesAvoidFullUntil = seriesAvoidFullUntil,
                                seriesSequentialFailuresRemembered = seriesProviderAdaptation.rememberSequential,
                                seriesHealthySyncStreak = seriesProviderAdaptation.healthyStreak
                            )
                        )
                    }
                    is CatalogStrategyResult.Partial -> {
                        val acceptedCount = seriesSyncResult.stagedSessionId?.let { sessionId ->
                            syncCatalogStore.applyStagedSeriesCatalog(provider.id, sessionId, seriesSyncResult.categories)
                            seriesSyncResult.stagedAcceptedCount
                        } ?: syncCatalogStore.replaceSeriesCatalog(
                            providerId = provider.id,
                            categories = seriesSyncResult.categories,
                            series = seriesResult.items.asSequence().map { it.toEntity() }
                        )
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastSeriesSync = now,
                                seriesCount = acceptedCount,
                                seriesAvoidFullUntil = seriesAvoidFullUntil,
                                seriesSequentialFailuresRemembered = currentMetadata.seriesSequentialFailuresRemembered || seriesSequentialStress,
                                seriesHealthySyncStreak = 0
                            )
                        )
                    }
                    is CatalogStrategyResult.EmptyValid -> {
                        val existingSeriesCount = seriesDao.getCount(provider.id).first()
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                seriesAvoidFullUntil = seriesAvoidFullUntil,
                                seriesSequentialFailuresRemembered = currentMetadata.seriesSequentialFailuresRemembered || seriesSequentialStress,
                                seriesHealthySyncStreak = 0
                            )
                        )
                        throw IllegalStateException(
                            if (existingSeriesCount > 0) {
                                "Series refresh returned an empty catalog; existing library was preserved."
                            } else {
                                "Series catalog was empty."
                            }
                        )
                    }
                    is CatalogStrategyResult.Failure -> {
                        val enteringLazyMode = seriesResult.strategyName == "lazy_by_category" && !seriesSyncResult.categories.isNullOrEmpty()
                        if (enteringLazyMode) {
                            seriesSyncResult.categories?.let { syncCatalogStore.replaceCategories(provider.id, "SERIES", it) }
                        }
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastSeriesSync = if (enteringLazyMode) now else currentMetadata.lastSeriesSync,
                                seriesAvoidFullUntil = seriesAvoidFullUntil,
                                seriesSequentialFailuresRemembered = currentMetadata.seriesSequentialFailuresRemembered || seriesSequentialStress || shouldRememberSequentialPreference(seriesResult.error),
                                seriesHealthySyncStreak = 0
                            )
                        )
                        if (!enteringLazyMode) {
                            throw IllegalStateException(
                                syncErrorSanitizer.userMessage(seriesResult.error, "Failed to fetch series list"),
                                seriesResult.error
                            )
                        }
                    }
                }
            }
            ProviderType.STALKER_PORTAL -> {
                progress(provider.id, onProgress, "Retrying Series...")
                val api = createStalkerSyncProvider(provider)
                val categories = requireResult(api.getSeriesCategories(), "Failed to load series categories")
                val series = loadStalkerSeriesByCategory(api, categories, onProgress)
                val acceptedCount = syncCatalogStore.replaceSeriesCatalog(
                    providerId = provider.id,
                    categories = categories.map { category ->
                        CategoryEntity(
                            providerId = provider.id,
                            categoryId = category.id,
                            name = category.name,
                            parentId = category.parentId,
                            type = ContentType.SERIES,
                            isAdult = category.isAdult
                        )
                    },
                    series = series.asSequence().map { it.toEntity() }
                )
                val now = System.currentTimeMillis()
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastSeriesSync = now,
                        lastSeriesSuccess = now,
                        seriesCount = acceptedCount
                    )
                syncMetadataRepository.updateMetadata(metadata)
            }
            ProviderType.M3U -> {
                throw IllegalStateException("Series retry is unavailable for this provider")
            }
        }
    }

    private suspend fun syncXtreamMoviesCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        onProgress: ((String) -> Unit)?
    ): MovieCatalogSyncResult =
        xtreamMovieStrategy.syncXtreamMoviesCatalog(provider, api, existingMetadata, onProgress)

    private suspend fun loadXtreamMoviesFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Movie> = xtreamMovieStrategy.loadXtreamMoviesFull(provider, api)

    private suspend fun syncXtreamLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Channel> =
        xtreamLiveStrategy.syncXtreamLiveCatalog(provider, api, existingMetadata, hiddenLiveCategoryIds, onProgress)

    private suspend fun loadXtreamLiveFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Channel> = xtreamLiveStrategy.loadXtreamLiveFull(provider, api)

    private suspend fun loadXtreamLiveByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogStrategyResult<Channel> =
        xtreamLiveStrategy.loadXtreamLiveByCategory(provider, api, rawCategories, onProgress, preferSequential)

    private suspend fun loadXtreamMoviesByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogSyncPayload<Movie> =
        xtreamMovieStrategy.loadXtreamMoviesByCategory(provider, api, rawCategories, onProgress, preferSequential)

    private suspend fun loadXtreamMoviesByPage(
        provider: Provider,
        api: XtreamProvider,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Movie> = xtreamMovieStrategy.loadXtreamMoviesByPage(provider, api, onProgress)

    private suspend fun syncXtreamSeriesCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Series> =
        xtreamSeriesStrategy.syncXtreamSeriesCatalog(provider, api, existingMetadata, onProgress)

    private suspend fun loadXtreamSeriesFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Series> = xtreamSeriesStrategy.loadXtreamSeriesFull(provider, api)

    private suspend fun loadXtreamSeriesByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogSyncPayload<Series> =
        xtreamSeriesStrategy.loadXtreamSeriesByCategory(provider, api, rawCategories, onProgress, preferSequential)

    private suspend fun loadXtreamSeriesByPage(
        provider: Provider,
        api: XtreamProvider,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Series> = xtreamSeriesStrategy.loadXtreamSeriesByPage(provider, api, onProgress)

    private suspend fun updateSyncStatusMetadata(providerId: Long, status: String) {
        val metadata = (syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId))
            .copy(lastSyncStatus = status)
        syncMetadataRepository.updateMetadata(metadata)
    }

    private suspend fun fetchXtreamVodCategories(provider: Provider): List<XtreamCategory>? =
        xtreamMovieStrategy.fetchXtreamVodCategories(provider)

    private fun strategyWarnings(result: CatalogStrategyResult<*>): List<String> =
        catalogStrategySupport.strategyWarnings(result)

    private fun <T> shouldDowngradeCategorySync(
        totalCategories: Int,
        failures: Int,
        fastFailures: Int,
        outcomes: List<CategoryFetchOutcome<T>>
    ): Boolean = catalogStrategySupport.shouldDowngradeCategorySync(totalCategories, failures, fastFailures, outcomes)

    private fun <T> shouldRetryFailedCategories(
        totalCategories: Int,
        failures: Int,
        downgradeRecommended: Boolean,
        outcomes: List<CategoryFetchOutcome<T>>
    ): Boolean = catalogStrategySupport.shouldRetryFailedCategories(totalCategories, failures, downgradeRecommended, outcomes)

    private fun <T> shouldRetryFailedPages(
        totalPages: Int,
        failures: Int,
        outcomes: List<PageFetchOutcome<T>>
    ): Boolean = catalogStrategySupport.shouldRetryFailedPages(totalPages, failures, outcomes)

    private fun shouldRememberSequentialPreference(error: Throwable): Boolean {
        return xtreamAdaptiveSyncPolicy.isProviderStress(error) ||
            error is XtreamAuthenticationException ||
            error is XtreamParsingException ||
            (error is XtreamRequestException && error.statusCode in setOf(403, 429)) ||
            (error is XtreamNetworkException && error.message.orEmpty().contains("reset", ignoreCase = true))
    }

    private fun updateSequentialProviderAdaptation(
        previousRemembered: Boolean,
        previousHealthyStreak: Int,
        sawSequentialStress: Boolean
    ): SequentialProviderAdaptation {
        if (sawSequentialStress) {
            return SequentialProviderAdaptation(rememberSequential = true, healthyStreak = 0)
        }
        if (!previousRemembered) {
            return SequentialProviderAdaptation(rememberSequential = false, healthyStreak = 0)
        }
        val nextHealthyStreak = (previousHealthyStreak + 1).coerceAtMost(2)
        return if (nextHealthyStreak >= 2) {
            SequentialProviderAdaptation(rememberSequential = false, healthyStreak = 0)
        } else {
            SequentialProviderAdaptation(rememberSequential = true, healthyStreak = nextHealthyStreak)
        }
    }

    private fun updateAvoidFullUntil(
        previousAvoidFullUntil: Long,
        now: Long,
        feedback: XtreamStrategyFeedback
    ): Long = catalogStrategySupport.updateAvoidFullUntil(previousAvoidFullUntil, now, feedback)

    private fun shouldPreferSegmentedLiveSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean = catalogStrategySupport.shouldPreferSegmentedLiveSync(metadata, now)

    private fun shouldPreferSegmentedMovieSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean = catalogStrategySupport.shouldPreferSegmentedMovieSync(metadata, now)

    private fun shouldPreferSegmentedSeriesSync(
        metadata: SyncMetadata,
        now: Long
    ): Boolean = catalogStrategySupport.shouldPreferSegmentedSeriesSync(metadata, now)

    private fun shouldAvoidFullCatalogStrategy(error: Throwable): Boolean =
        catalogStrategySupport.shouldAvoidFullCatalogStrategy(error)

    private fun sawSegmentedStress(
        warnings: List<String>,
        result: CatalogStrategyResult<*>,
        sequentialWarnings: Set<String>
    ): Boolean = catalogStrategySupport.sawSegmentedStress(warnings, result, sequentialWarnings)

    private fun buildFallbackMovieCategories(providerId: Long, movies: List<Movie>): List<CategoryEntity> =
        catalogStrategySupport.buildFallbackMovieCategories(providerId, movies)

    private fun buildFallbackLiveCategories(providerId: Long, channels: List<Channel>): List<CategoryEntity> =
        catalogStrategySupport.buildFallbackLiveCategories(providerId, channels)

    private fun buildFallbackSeriesCategories(providerId: Long, series: List<Series>): List<CategoryEntity> =
        catalogStrategySupport.buildFallbackSeriesCategories(providerId, series)

    private fun mergePreferredAndFallbackCategories(
        preferred: List<CategoryEntity>?,
        fallback: List<CategoryEntity>?
    ): List<CategoryEntity>? = catalogStrategySupport.mergePreferredAndFallbackCategories(preferred, fallback)

    private suspend fun mergeHiddenChannelsIntoStaging(
        providerId: Long,
        sessionId: Long,
        hiddenLiveCategoryIds: Set<Long>
    ) {
        val hiddenChannels = channelDao.getByProviderSync(providerId)
            .filter { channel -> channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds }
        if (hiddenChannels.isNotEmpty()) {
            syncCatalogStore.stageChannelBatch(providerId, sessionId, hiddenChannels)
        }
    }

    private suspend fun mergeVisibleLiveSyncWithHiddenStoredContent(
        providerId: Long,
        visibleCategories: List<CategoryEntity>?,
        visibleChannels: List<ChannelEntity>,
        hiddenLiveCategoryIds: Set<Long>
    ): LiveCatalogSnapshot {
        if (hiddenLiveCategoryIds.isEmpty()) {
            return LiveCatalogSnapshot(visibleCategories, visibleChannels)
        }

        val hiddenCategories = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .filter { category -> category.categoryId in hiddenLiveCategoryIds }
        val hiddenChannels = channelDao.getByProviderSync(providerId)
            .filter { channel -> channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds }

        val mergedCategories = ((visibleCategories ?: emptyList()) + hiddenCategories)
            .distinctBy { it.categoryId to it.type }
            .sortedBy { it.categoryId }
            .takeIf { it.isNotEmpty() }
        val mergedChannels = (visibleChannels + hiddenChannels)
            .distinctBy { it.streamId }
            .sortedBy { it.number }

        return LiveCatalogSnapshot(
            categories = mergedCategories,
            channels = mergedChannels
        )
    }

    private fun progress(providerId: Long, callback: ((String) -> Unit)?, message: String) {
        publishSyncState(providerId, SyncState.Syncing(message))
        callback?.invoke(message)
    }

    private fun publishSyncState(providerId: Long, state: SyncState) {
        syncStateTracker.publish(providerId, state)
    }

    private fun redactUrlForLogs(url: String?): String {
        if (url.isNullOrBlank()) return "<empty>"
        return runCatching {
            val parsed = URI(url)
            val scheme = parsed.scheme ?: "http"
            val host = parsed.host ?: return@runCatching "<redacted>"
            val path = parsed.path.orEmpty()
            "$scheme://$host$path"
        }.getOrDefault("<redacted>")
    }

    private fun sanitizeThrowableMessage(error: Throwable?): String {
        return syncErrorSanitizer.throwableMessage(error)
    }

    private fun sanitizeLogMessage(message: String?): String {
        return syncErrorSanitizer.sanitize(message)
    }

    private fun fullCatalogFallbackWarning(sectionLabel: String, error: Throwable?): String {
        return when (error) {
            is XtreamResponseTooLargeException ->
                "$sectionLabel full catalog was too large for one request, so sync continued with a safer segmented mode."
            else ->
                "$sectionLabel full catalog request failed, so sync continued with a safer fallback mode."
        }
    }

    private fun categoryFailureWarning(sectionLabel: String, categoryName: String, error: Throwable): String {
        val safeCategoryName = sanitizeLogMessage(categoryName).takeIf { it.isNotBlank() } ?: "Unknown"
        return when (error) {
            is XtreamResponseTooLargeException ->
                "$sectionLabel category '$safeCategoryName' was too large to load safely."
            else ->
                "$sectionLabel category '$safeCategoryName' failed: " +
                    syncErrorSanitizer.userMessage(error, "Provider request failed.")
        }
    }

    private fun pagingFailureWarning(sectionLabel: String, page: Int, error: Throwable): String {
        return when (error) {
            is XtreamResponseTooLargeException ->
                "$sectionLabel paging response on page $page was too large to load safely."
            else ->
                "$sectionLabel paging failed on page $page: " +
                    syncErrorSanitizer.userMessage(error, "Provider request failed.")
        }
    }

    private suspend fun <T> executeCategoryRecoveryPlan(
        provider: Provider,
        categories: List<XtreamCategory>,
        initialConcurrency: Int,
        sectionLabel: String,
        sequentialModeWarning: String,
        onProgress: ((String) -> Unit)?,
        fetch: suspend (XtreamCategory) -> TimedCategoryOutcome<T>
    ): CategoryExecutionPlan<T> = xtreamSupport.executeCategoryRecoveryPlan(
        provider = provider,
        categories = categories,
        initialConcurrency = initialConcurrency,
        sectionLabel = sectionLabel,
        sequentialModeWarning = sequentialModeWarning,
        onProgress = onProgress,
        fetch = fetch
    )

    private fun <T> evaluatePageRecoveryPlan(
        provider: Provider,
        sectionLabel: String,
        pageWindow: List<Int>,
        outcomes: List<TimedPageOutcome<T>>,
        sequentialModeWarning: String
    ): PageExecutionPlan<T> = xtreamSupport.evaluatePageRecoveryPlan(
        provider = provider,
        sectionLabel = sectionLabel,
        pageWindow = pageWindow,
        outcomes = outcomes,
        sequentialModeWarning = sequentialModeWarning
    )

    private suspend fun <T> retryTransient(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 700L,
        block: suspend () -> T
    ): T = xtreamSupport.retryTransient(
        maxAttempts = maxAttempts,
        initialDelayMs = initialDelayMs,
        block = block
    )

    private suspend fun <T> attemptNonCancellation(block: suspend () -> T): Attempt<T> =
        xtreamSupport.attemptNonCancellation(block)

    private suspend fun <T> executeXtreamRequest(
        providerId: Long,
        stage: XtreamAdaptiveSyncPolicy.Stage,
        block: suspend () -> T
    ): T = xtreamSupport.executeXtreamRequest(providerId, stage, block)

    private suspend fun <T> withMovieRequestTimeout(
        requestLabel: String,
        block: suspend () -> T
    ): T = xtreamSupport.withMovieRequestTimeout(requestLabel, block)

    private suspend fun <T> withSeriesRequestTimeout(
        requestLabel: String,
        block: suspend () -> T
    ): T = xtreamSupport.withSeriesRequestTimeout(requestLabel, block)

    private suspend fun <T> retryXtreamCatalogTransient(providerId: Long, block: suspend () -> T): T =
        xtreamSupport.retryXtreamCatalogTransient(providerId, block)

    companion object {
        private const val PROGRESS_INTERVAL = 5_000
    }

    private fun createXtreamSyncProvider(
        provider: Provider,
        useTextClassification: Boolean = true,
        enableBase64TextCompatibility: Boolean = false
    ): XtreamProvider {
        return XtreamProvider(
            providerId = provider.id,
            api = xtreamCatalogApiService,
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            allowedOutputFormats = provider.allowedOutputFormats,
            useTextClassification = useTextClassification,
            enableBase64TextCompatibility = enableBase64TextCompatibility
        )
    }

    private fun createStalkerSyncProvider(provider: Provider): StalkerProvider {
        return StalkerProvider(
            providerId = provider.id,
            api = stalkerApiService,
            portalUrl = provider.serverUrl,
            macAddress = provider.stalkerMacAddress,
            deviceProfile = provider.stalkerDeviceProfile,
            timezone = provider.stalkerDeviceTimezone,
            locale = provider.stalkerDeviceLocale
        )
    }

    private suspend fun loadStalkerChannelsByCategory(
        api: StalkerProvider,
        categories: List<com.streamvault.domain.model.Category>,
        onProgress: ((String) -> Unit)?
    ): List<Channel> {
        progress(api.providerId, onProgress, "Loading live channels...")
        val bulkChannels = requireResult(api.getLiveStreams(null), "Failed to load live channels")
        if (categories.isEmpty()) {
            return bulkChannels
        }

        val hasResolvedCategories = bulkChannels.any { channel -> channel.categoryId != null }
        if (bulkChannels.isNotEmpty() && hasResolvedCategories) {
            return bulkChannels.distinctBy { it.streamId }
        }

        return categories.flatMap { category ->
            progress(api.providerId, onProgress, "Loading ${category.name}...")
            requireResult(api.getLiveStreams(category.id), "Failed to load live channels for ${category.name}")
        }.distinctBy { it.streamId }
    }

    private data class StalkerLiveCatalogResult(
        val categories: List<CategoryEntity>,
        val channels: List<Channel>,
        val warnings: List<String> = emptyList()
    )

    private suspend fun loadStalkerLiveCatalog(
        api: StalkerProvider,
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): StalkerLiveCatalogResult {
        return when (val categoriesResult = api.getLiveCategories()) {
            is com.streamvault.domain.model.Result.Success -> {
                val channels = loadStalkerChannelsByCategory(api, categoriesResult.data, onProgress)
                StalkerLiveCatalogResult(
                    categories = categoriesResult.data.map { it.toEntity(provider.id) },
                    channels = channels
                )
            }
            is com.streamvault.domain.model.Result.Error -> {
                Log.w(
                    TAG,
                    "Stalker live categories failed for provider ${provider.id}; trying bulk live fallback: ${categoriesResult.message}",
                    categoriesResult.exception
                )
                when (val bulkResult = api.getLiveStreams(null)) {
                    is com.streamvault.domain.model.Result.Success -> {
                        val channels = bulkResult.data.distinctBy { it.streamId }
                        if (channels.isEmpty()) {
                            throw IllegalStateException(
                                "${categoriesResult.message.ifBlank { "Failed to load live categories" }} Bulk live fallback returned no channels.",
                                categoriesResult.exception
                            )
                        }
                        StalkerLiveCatalogResult(
                            categories = synthesizeStalkerLiveCategories(provider.id, channels),
                            channels = channels,
                            warnings = listOf("Live categories failed; recovered using bulk live channels.")
                        )
                    }
                    is com.streamvault.domain.model.Result.Error -> {
                        val profileDiagnostic = stalkerCatalogAccessDiagnostic(
                            api = api,
                            primaryMessage = categoriesResult.message,
                            fallbackMessage = bulkResult.message
                        )
                        throw IllegalStateException(
                            buildString {
                                append(categoriesResult.message.ifBlank { "Failed to load live categories" })
                                bulkResult.message.takeIf { it.isNotBlank() }?.let {
                                    append(" Bulk live fallback also failed: ")
                                    append(it)
                                }
                                profileDiagnostic?.let {
                                    append(' ')
                                    append(it)
                                }
                            },
                            bulkResult.exception ?: categoriesResult.exception
                        )
                    }
                    is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
                }
            }
            is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }

    private suspend fun stalkerCatalogAccessDiagnostic(
        api: StalkerProvider,
        primaryMessage: String,
        fallbackMessage: String?
    ): String? {
        if (!isStalkerEmptyResponse(primaryMessage) || !isStalkerEmptyResponse(fallbackMessage)) {
            return null
        }
        val profile = when (val profileResult = api.getAccountProfile()) {
            is com.streamvault.domain.model.Result.Success -> profileResult.data
            else -> return null
        }
        if (!profile.hasLikelyMissingCatalogAccess()) {
            return null
        }
        return "Portal authenticated, but the returned Stalker profile indicates this account has no accessible catalog data. Check that the MAC is activated and assigned a live/VOD package on the provider side."
    }

    private fun isStalkerEmptyResponse(message: String?): Boolean =
        !message.isNullOrBlank() && message.contains("empty response", ignoreCase = true)

    private fun StalkerProviderProfile.hasLikelyMissingCatalogAccess(): Boolean {
        val normalizedAccountId = accountId?.trim().orEmpty()
        val normalizedAccountName = accountName?.trim().orEmpty()
        return authAccess == false &&
            (normalizedAccountId.isBlank() || normalizedAccountId == "0") &&
            (normalizedAccountName.isBlank() || normalizedAccountName == "0")
    }

    private fun synthesizeStalkerLiveCategories(
        providerId: Long,
        channels: List<Channel>
    ): List<CategoryEntity> {
        return channels
            .mapNotNull { channel ->
                val categoryId = channel.categoryId ?: return@mapNotNull null
                CategoryEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    name = channel.categoryName?.takeIf { it.isNotBlank() } ?: "Category $categoryId",
                    type = ContentType.LIVE,
                    isAdult = channel.isAdult
                )
            }
            .distinctBy { it.categoryId }
    }

    private suspend fun loadStalkerMoviesByCategory(
        api: StalkerProvider,
        categories: List<com.streamvault.domain.model.Category>,
        onProgress: ((String) -> Unit)?
    ): List<Movie> {
        if (categories.isEmpty()) {
            return requireResult(api.getVodStreams(null), "Failed to load movies")
        }
        return categories.flatMap { category ->
            progress(api.providerId, onProgress, "Loading ${category.name}...")
            requireResult(api.getVodStreams(category.id), "Failed to load movies for ${category.name}")
        }.distinctBy { it.streamId }
    }

    private suspend fun loadStalkerSeriesByCategory(
        api: StalkerProvider,
        categories: List<com.streamvault.domain.model.Category>,
        onProgress: ((String) -> Unit)?
    ): List<Series> {
        if (categories.isEmpty()) {
            return requireResult(api.getSeriesList(null), "Failed to load series")
        }
        return categories.flatMap { category ->
            progress(api.providerId, onProgress, "Loading ${category.name}...")
            requireResult(api.getSeriesList(category.id), "Failed to load series for ${category.name}")
        }.distinctBy { it.seriesId }
    }

    private fun <T> requireResult(result: com.streamvault.domain.model.Result<T>, fallbackMessage: String): T {
        return when (result) {
            is com.streamvault.domain.model.Result.Success -> result.data
            is com.streamvault.domain.model.Result.Error -> throw IllegalStateException(result.message.ifBlank { fallbackMessage }, result.exception)
            is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }

    private fun logXtreamCatalogFallback(
        provider: Provider,
        section: String,
        stage: String,
        elapsedMs: Long,
        itemCount: Int?,
        error: Throwable?,
        nextStep: String
    ) = xtreamSupport.logXtreamCatalogFallback(provider, section, stage, elapsedMs, itemCount, error, nextStep)

    private suspend fun fetchLiveCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Channel> = xtreamFetcher.fetchLiveCategoryOutcome(provider, api, category)

    private suspend fun fetchMovieCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Movie> = xtreamFetcher.fetchMovieCategoryOutcome(provider, api, category)

    private suspend fun fetchSeriesCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Series> = xtreamFetcher.fetchSeriesCategoryOutcome(provider, api, category)

    private suspend fun fetchMoviePageOutcome(
        provider: Provider,
        api: XtreamProvider,
        page: Int
    ): TimedPageOutcome<Movie> = xtreamFetcher.fetchMoviePageOutcome(provider, api, page)

    private suspend fun fetchSeriesPageOutcome(
        provider: Provider,
        api: XtreamProvider,
        page: Int
    ): TimedPageOutcome<Series> = xtreamFetcher.fetchSeriesPageOutcome(provider, api, page)

    private suspend fun <T> continueFailedPageOutcomes(
        provider: Provider,
        timedOutcomes: List<TimedPageOutcome<T>>,
        fetchSequentially: suspend (Int) -> TimedPageOutcome<T>
    ): List<TimedPageOutcome<T>> = xtreamSupport.continueFailedPageOutcomes(provider, timedOutcomes, fetchSequentially)

    private suspend fun <T> continueFailedCategoryOutcomes(
        provider: Provider,
        timedOutcomes: List<TimedCategoryOutcome<T>>,
        fetchSequentially: suspend (XtreamCategory) -> TimedCategoryOutcome<T>
    ): List<TimedCategoryOutcome<T>> = xtreamSupport.continueFailedCategoryOutcomes(provider, timedOutcomes, fetchSequentially)
}
