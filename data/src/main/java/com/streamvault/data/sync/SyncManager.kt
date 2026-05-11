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
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelGuideSyncEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.XtreamContentIndexEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.http.buildAppRequestProfile
import com.streamvault.data.remote.http.toGenericRequestProfile
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
import com.streamvault.domain.model.Result
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

private const val TAG = "SyncManager"
private const val XTREAM_FALLBACK_STAGE_BATCH_SIZE = 500
private const val LIVE_CATEGORY_SEQUENTIAL_MODE_WARNING =
    "Live category sync downgraded to sequential mode after provider stress signals."
private const val XTREAM_RECOVERY_ABORT_WARNING_SUFFIX =
    "recovery stopped early after repeated provider stress signals; keeping recovered results."
private const val XTREAM_AVOID_FULL_CATALOG_COOLDOWN_MILLIS = 6 * 60 * 60 * 1000L
private const val XTREAM_MOVIE_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val XTREAM_SERIES_REQUEST_TIMEOUT_MILLIS = 60_000L
private const val XTREAM_SQLITE_LOOKUP_CHUNK_SIZE = 900
private const val STALKER_GUIDE_PROGRAM_BATCH_SIZE = 500
private const val XTREAM_ONBOARDING_PHASE_STARTING = "STARTING"
private const val XTREAM_ONBOARDING_PHASE_FETCHING = "FETCHING"
private const val XTREAM_ONBOARDING_PHASE_RECOVERING = "RECOVERING"
private const val XTREAM_ONBOARDING_PHASE_STAGED = "STAGED"
private const val XTREAM_ONBOARDING_PHASE_COMMITTING = "COMMITTING"
private const val XTREAM_ONBOARDING_PHASE_COMPLETED = "COMPLETED"
private const val XTREAM_ONBOARDING_PHASE_FAILED = "FAILED"
/**
 * Maximum number of programs we will accept from a single per-channel `get_epg_info`
 * call before treating the response as a portal that ignores `ch_id` and returns the
 * full bulk EPG. Probed real-world portals return at most ~150 programs/channel for a
 * 6h period; 5 000 leaves ample headroom while still catching multi-megabyte payloads.
 */
private const val STALKER_PER_CHANNEL_RECORD_SANITY_CAP = 5_000

/**
 * Sentinel exception raised inside the streamed per-channel EPG callback when we detect
 * that the portal is returning the bulk payload regardless of the requested `ch_id`.
 * Caught and converted into a warning by [SyncManager.syncStalkerPortalEpg].
 */
private class StalkerBrokenPerChannelEpgException(channelName: String) :
    RuntimeException("Stalker portal returned bulk-shaped EPG for per-channel request ($channelName)")

internal suspend fun <K, V> chunkedLookupById(
    ids: List<K>,
    chunkSize: Int,
    fetch: suspend (List<K>) -> List<V>,
    keySelector: (V) -> K
): Map<K, V> {
    if (ids.isEmpty()) return emptyMap()
    return ids
        .distinct()
        .chunked(chunkSize)
        .flatMap { chunk -> fetch(chunk) }
        .associateBy(keySelector)
}

private data class XtreamLiveCommitResult(
    val acceptedCount: Int,
    val warnings: List<String>
)

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
    private val xtreamContentIndexDao: XtreamContentIndexDao,
    private val xtreamIndexJobDao: XtreamIndexJobDao,
    private val xtreamLiveOnboardingDao: XtreamLiveOnboardingDao,
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
            json = xtreamJson,
            defaultRequestProfile = buildAppRequestProfile(
                versionName = null,
                ownerTag = "sync/xtream"
            )
        )
    }
    private val xtreamCatalogApiService: XtreamApiService by lazy { xtreamCatalogHttpService }
    private val xtreamFetcher: SyncManagerXtreamFetcher by lazy {
        SyncManagerXtreamFetcher(
            xtreamCatalogApiService = xtreamCatalogApiService,
            xtreamCatalogHttpService = xtreamCatalogHttpService,
            xtreamSupport = xtreamSupport,
            sanitizeThrowableMessage = ::sanitizeThrowableMessage
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
            isCurrentlyLowOnMemory = applicationContext::isCurrentlyLowOnMemoryForSync,
            stageChannelItems = catalogStager::stageChannelItems
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

    fun scheduleProviderSyncResume(providerId: Long) {
        runCatching {
            ProviderSyncWorker.enqueueProvider(applicationContext, providerId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule provider resume work for provider $providerId: ${sanitizeThrowableMessage(error)}")
        }
    }

    fun scheduleXtreamIndexSync(providerId: Long, section: ContentType? = null, force: Boolean = false) {
        runCatching {
            XtreamIndexWorker.enqueue(
                context = applicationContext,
                providerId = providerId,
                section = section?.name,
                force = force
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule Xtream index work for provider $providerId (${section?.name ?: "all"}): ${sanitizeThrowableMessage(error)}")
        }
    }

    suspend fun prioritizeXtreamIndexCategory(
        providerId: Long,
        section: ContentType,
        categoryId: Long
    ) {
        if (section != ContentType.MOVIE && section != ContentType.SERIES) return
        val now = System.currentTimeMillis()
        val updated = xtreamIndexJobDao.requestCategoryPriority(
            providerId = providerId,
            section = section.name,
            categoryId = categoryId,
            requestedAt = now
        )
        if (updated == 0) {
            upsertXtreamIndexJob(
                providerId = providerId,
                section = section.name,
                state = "QUEUED",
                now = now,
                priorityCategoryId = categoryId,
                priorityRequestedAt = now
            )
        }
        scheduleXtreamIndexSync(providerId, section, force = false)
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

        val startedAt = System.currentTimeMillis()
        updateXtreamEpgJobState(
            provider = provider,
            state = "RUNNING",
            now = startedAt,
            lastAttemptAt = startedAt,
            lastError = null
        )
        publishSyncState(providerId, SyncState.Syncing("Downloading EPG..."))

        try {
            val epgResult = syncProviderEpg(
                provider = provider,
                metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id),
                now = startedAt,
                force = force,
                onProgress = onProgress
            )
            val finishedAt = System.currentTimeMillis()
            val epgCount = programDao.countByProvider(providerId)
            updateXtreamEpgJobState(
                provider = provider,
                state = when {
                    epgResult.warnings.isEmpty() -> "SUCCESS"
                    epgResult.hasRetryableFailure -> "FAILED_RETRYABLE"
                    else -> "PARTIAL"
                },
                now = finishedAt,
                indexedRows = epgCount,
                lastSuccessAt = finishedAt.takeIf { epgResult.warnings.isEmpty() },
                lastError = epgResult.warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
            )
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (epgResult.warnings.isEmpty()) "SUCCESS" else "PARTIAL"
            )
            publishSyncState(
                providerId,
                if (epgResult.warnings.isEmpty()) {
                    SyncState.Success()
                } else {
                    SyncState.Partial(
                        message = "EPG sync completed with warnings",
                        warnings = epgResult.warnings,
                        hasRetryableEpgFailure = epgResult.hasRetryableFailure
                    )
                }
            )
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "EPG sync failed")
            Log.e(TAG, "EPG sync failed for provider $providerId: ${syncErrorSanitizer.throwableMessage(e)}")
            val failedAt = System.currentTimeMillis()
            updateXtreamEpgJobState(
                provider = provider,
                state = if (isRetryableEpgException(e)) "FAILED_RETRYABLE" else "FAILED_PERMANENT",
                now = failedAt,
                indexedRows = programDao.countByProvider(providerId),
                lastError = safeMessage
            )
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    private fun shouldSyncEpgUpfront(provider: Provider): Boolean =
        provider.epgSyncMode == ProviderEpgSyncMode.UPFRONT

    private suspend fun updateXtreamEpgJobState(
        provider: Provider,
        state: String,
        now: Long,
        indexedRows: Int? = null,
        lastAttemptAt: Long? = null,
        lastSuccessAt: Long? = null,
        lastError: String? = null
    ) {
        if (provider.type != ProviderType.XTREAM_CODES || provider.epgSyncMode == ProviderEpgSyncMode.SKIP) return
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = "EPG",
            state = state,
            now = now,
            totalCategories = 1,
            completedCategories = if (state == "SUCCESS") 1 else 0,
            nextCategoryIndex = if (state == "SUCCESS") 1 else 0,
            failedCategories = if (state == "FAILED_RETRYABLE" || state == "FAILED_PERMANENT") 1 else 0,
            indexedRows = indexedRows,
            lastAttemptAt = lastAttemptAt,
            lastSuccessAt = lastSuccessAt,
            lastError = lastError
        )
    }

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null,
        trackInitialLiveOnboarding: Boolean = false
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
                    ProviderType.XTREAM_CODES -> syncXtreamIndexFirst(
                        provider = provider,
                        force = force,
                        onProgress = onProgress,
                        trackInitialLiveOnboarding = trackInitialLiveOnboarding,
                        syncReason = if (trackInitialLiveOnboarding) {
                            XtreamLiveSyncReason.INITIAL_ONBOARDING
                        } else {
                            XtreamLiveSyncReason.FOREGROUND
                        }
                    )
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
            if (provider.type == ProviderType.XTREAM_CODES && trackInitialLiveOnboarding) {
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FAILED,
                    lastError = sanitizeThrowableMessage(e)
                )
            }
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    fun resetState(providerId: Long? = null) {
        syncStateTracker.reset(providerId)
    }

    suspend fun rebuildXtreamIndex(
        providerId: Long,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")
        if (providerEntity.type != ProviderType.XTREAM_CODES) {
            return@lock com.streamvault.domain.model.Result.error("Index rebuild is only available for Xtream providers")
        }

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()

        publishSyncState(providerId, SyncState.Syncing("Preparing index rebuild..."))
        val now = System.currentTimeMillis()
        val warnings = mutableListOf<String>()

        try {
            withContext(Dispatchers.IO) {
                progress(providerId, onProgress, "Marking existing index rows stale...")
                val staleRows = xtreamContentIndexDao.markVodAndSeriesRowsStaleForRebuild(providerId)
                Log.i(TAG, "Marked $staleRows Xtream VOD/series index rows STALE_REMOTE for provider $providerId rebuild.")

                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)

                syncXtreamCategoryShell(
                    provider = provider,
                    api = api,
                    contentType = ContentType.MOVIE,
                    label = "Movies",
                    now = now,
                    onProgress = onProgress
                ).getOrElse { error ->
                    warnings += "Movies index rebuild could not be queued."
                    upsertXtreamIndexJob(
                        providerId = providerId,
                        section = ContentType.MOVIE.name,
                        state = xtreamIndexFailureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    0
                }
                scheduleXtreamIndexSync(providerId, ContentType.MOVIE, force = true)

                syncXtreamCategoryShell(
                    provider = provider,
                    api = api,
                    contentType = ContentType.SERIES,
                    label = "Series",
                    now = now,
                    onProgress = onProgress
                ).getOrElse { error ->
                    warnings += "Series index rebuild could not be queued."
                    upsertXtreamIndexJob(
                        providerId = providerId,
                        section = ContentType.SERIES.name,
                        state = xtreamIndexFailureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    0
                }
                scheduleXtreamIndexSync(providerId, ContentType.SERIES, force = true)

                val metadata = syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)
                syncMetadataRepository.updateMetadata(
                    metadata.copy(
                        lastMovieAttempt = now,
                        movieCatalogStale = true,
                        movieSyncMode = VodSyncMode.UNKNOWN
                    )
                )
            }
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (warnings.isEmpty()) "SUCCESS" else "PARTIAL"
            )
            publishSyncState(
                providerId,
                if (warnings.isEmpty()) {
                    SyncState.Success()
                } else {
                    SyncState.Partial("Index rebuild queued with warnings", warnings)
                }
            )
            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: CancellationException) {
            resetState(providerId)
            throw e
        } catch (e: Exception) {
            val safeMessage = syncErrorSanitizer.userMessage(e, "Index rebuild failed")
            Log.e(TAG, "Xtream index rebuild failed for provider $providerId: ${syncErrorSanitizer.throwableMessage(e)}")
            updateSyncStatusMetadata(providerId = providerId, status = "ERROR")
            publishSyncState(providerId, SyncState.Error(safeMessage, e))
            com.streamvault.domain.model.Result.error(safeMessage, e)
        }
    }

    suspend fun retrySection(
        providerId: Long,
        section: SyncRepairSection,
        movieFastSyncOverride: Boolean? = null,
        syncReason: XtreamLiveSyncReason = XtreamLiveSyncReason.MANUAL_SETTINGS,
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
            val outcome = withContext(Dispatchers.IO) {
                when (section) {
                    SyncRepairSection.LIVE -> syncLiveOnly(provider, syncReason, onProgress)
                    SyncRepairSection.EPG -> {
                        syncEpgOnly(provider, onProgress)
                        SyncOutcome()
                    }
                    SyncRepairSection.MOVIES -> syncMoviesOnly(provider, onProgress)
                    SyncRepairSection.SERIES -> syncSeriesOnly(provider, onProgress)
                }
            }
            updateSyncStatusMetadata(
                providerId = providerId,
                status = if (outcome.partial) "PARTIAL" else "SUCCESS"
            )
            publishSyncState(
                providerId,
                if (outcome.partial) {
                    SyncState.Partial("Section retry completed with warnings", outcome.warnings)
                } else {
                    SyncState.Success()
                }
            )
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

    private suspend fun syncXtreamIndexFirst(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?,
        trackInitialLiveOnboarding: Boolean = false,
        syncReason: XtreamLiveSyncReason = XtreamLiveSyncReason.FOREGROUND
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
        val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
        val now = System.currentTimeMillis()
        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)

        if (trackInitialLiveOnboarding) {
            recordXtreamLiveOnboardingState(
                provider = provider,
                phase = XTREAM_ONBOARDING_PHASE_STARTING,
                now = now,
                clearError = true,
                runtimeProfile = runtimeProfile
            )
        }

        upsertXtreamIndexJob(
            providerId = provider.id,
            section = ContentType.LIVE.name,
            state = "RUNNING",
            now = now,
            lastAttemptAt = now
        )
        val liveOutcome = runCatching {
            val recoveredLiveCommit = if (trackInitialLiveOnboarding) {
                recoverXtreamLiveOnboardingSession(
                    provider = provider,
                    hiddenLiveCategoryIds = hiddenLiveCategoryIds
                )
            } else {
                null
            }
            if (recoveredLiveCommit != null) {
                warnings += recoveredLiveCommit.warnings
                val acceptedCount = recoveredLiveCommit.acceptedCount
                val completedAt = System.currentTimeMillis()
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_COMPLETED,
                    now = completedAt,
                    acceptedRowCount = acceptedCount,
                    stagedFlushCount = stagedFlushCountFor(acceptedCount),
                    clearError = true,
                    completedAt = completedAt,
                    clearStagedSession = true,
                    runtimeProfile = runtimeProfile
                )
                metadata = metadata.copy(
                    lastLiveSync = now,
                    lastLiveSuccess = now,
                    liveCount = acceptedCount
                )
                syncMetadataRepository.updateMetadata(metadata)
                return@runCatching acceptedCount
            }

            if (trackInitialLiveOnboarding) {
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FETCHING,
                    now = System.currentTimeMillis(),
                    clearError = true,
                    runtimeProfile = runtimeProfile
                )
            }
            progress(provider.id, onProgress, "Downloading Live TV...")
            val liveSyncResult = syncXtreamLiveCatalog(
                provider = provider,
                api = api,
                existingMetadata = metadata,
                hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                onProgress = onProgress,
                runtimeProfile = runtimeProfile,
                trackInitialLiveOnboarding = trackInitialLiveOnboarding,
                syncReason = syncReason
            )
            if (trackInitialLiveOnboarding) {
                val stagedAcceptedCount = liveSyncResult.stagedAcceptedCount
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = if (liveSyncResult.stagedSessionId != null) {
                        XTREAM_ONBOARDING_PHASE_STAGED
                    } else {
                        XTREAM_ONBOARDING_PHASE_COMMITTING
                    },
                    now = System.currentTimeMillis(),
                    stagedSessionId = liveSyncResult.stagedSessionId,
                    importStrategy = liveSyncResult.catalogResult.strategyNameOrNull(),
                    acceptedRowCount = stagedAcceptedCount,
                    stagedFlushCount = stagedFlushCountFor(stagedAcceptedCount),
                    clearError = true,
                    runtimeProfile = runtimeProfile,
                    syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                )
            }
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

            val acceptedCount = when (val liveResult = liveSyncResult.catalogResult) {
                is CatalogStrategyResult.Success -> {
                    if (trackInitialLiveOnboarding) {
                        recordXtreamLiveOnboardingState(
                            provider = provider,
                            phase = XTREAM_ONBOARDING_PHASE_COMMITTING,
                            now = System.currentTimeMillis(),
                            stagedSessionId = liveSyncResult.stagedSessionId,
                            importStrategy = liveResult.strategyName,
                            acceptedRowCount = liveSyncResult.stagedAcceptedCount,
                            stagedFlushCount = stagedFlushCountFor(liveSyncResult.stagedAcceptedCount),
                            clearError = true,
                            runtimeProfile = runtimeProfile,
                            syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                        )
                    }
                    finalizeXtreamLiveCatalog(
                        providerId = provider.id,
                        liveSyncResult = liveSyncResult,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds
                    ).also { commitResult ->
                        warnings += commitResult.warnings
                    }.acceptedCount
                }
                is CatalogStrategyResult.Partial -> {
                    if (trackInitialLiveOnboarding) {
                        recordXtreamLiveOnboardingState(
                            provider = provider,
                            phase = XTREAM_ONBOARDING_PHASE_COMMITTING,
                            now = System.currentTimeMillis(),
                            stagedSessionId = liveSyncResult.stagedSessionId,
                            importStrategy = liveResult.strategyName,
                            acceptedRowCount = liveSyncResult.stagedAcceptedCount,
                            stagedFlushCount = stagedFlushCountFor(liveSyncResult.stagedAcceptedCount),
                            clearError = true,
                            runtimeProfile = runtimeProfile,
                            syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                        )
                    }
                    finalizeXtreamLiveCatalog(
                        providerId = provider.id,
                        liveSyncResult = liveSyncResult,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                        partialCompletionWarning = "Live TV sync completed partially."
                    ).also { commitResult ->
                        warnings += commitResult.warnings
                    }.acceptedCount
                }
                is CatalogStrategyResult.EmptyValid -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    if (existingChannelCount == 0) {
                        throw IllegalStateException("Live TV catalog was empty.")
                    }
                    warnings += liveSyncResult.warnings + liveResult.warnings +
                        "Live TV refresh returned an empty valid catalog; keeping previous channel library."
                    existingChannelCount
                }
                is CatalogStrategyResult.Failure -> {
                    val existingChannelCount = channelDao.getCount(provider.id).first()
                    if (existingChannelCount == 0) {
                        throw IllegalStateException(
                            "Failed to fetch live streams: ${syncErrorSanitizer.throwableMessage(liveResult.error)}"
                        )
                    }
                    warnings += liveSyncResult.warnings + liveResult.warnings +
                        "Live TV sync degraded; keeping previous channel library."
                    existingChannelCount
                }
            }

            if (trackInitialLiveOnboarding) {
                if (acceptedCount > 0) {
                    val completedAt = System.currentTimeMillis()
                    recordXtreamLiveOnboardingState(
                        provider = provider,
                        phase = XTREAM_ONBOARDING_PHASE_COMPLETED,
                        now = completedAt,
                        acceptedRowCount = acceptedCount,
                        stagedFlushCount = stagedFlushCountFor(acceptedCount),
                        clearError = true,
                        completedAt = completedAt,
                        clearStagedSession = true,
                        runtimeProfile = runtimeProfile,
                        syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                    )
                } else {
                    recordXtreamLiveOnboardingState(
                        provider = provider,
                        phase = XTREAM_ONBOARDING_PHASE_FAILED,
                        acceptedRowCount = acceptedCount,
                        lastError = "Live TV did not finish with any committed channels.",
                        clearStagedSession = true,
                        runtimeProfile = runtimeProfile,
                        syncProfileStrategy = liveSyncResult.profileStrategyName(runtimeProfile, trackInitialLiveOnboarding)
                    )
                }
            }

            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                liveCount = acceptedCount,
                liveAvoidFullUntil = liveAvoidFullUntil,
                liveSequentialFailuresRemembered = liveProviderAdaptation.rememberSequential,
                liveHealthySyncStreak = liveProviderAdaptation.healthyStreak
            )
            syncMetadataRepository.updateMetadata(metadata)
            acceptedCount
        }
        val liveCount = liveOutcome.getOrElse { error ->
            if (trackInitialLiveOnboarding) {
                recordXtreamLiveOnboardingState(
                    provider = provider,
                    phase = XTREAM_ONBOARDING_PHASE_FAILED,
                    lastError = sanitizeThrowableMessage(error),
                    runtimeProfile = runtimeProfile
                )
            }
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = ContentType.LIVE.name,
                state = xtreamIndexFailureState(error),
                now = now,
                lastAttemptAt = now,
                lastError = sanitizeThrowableMessage(error)
            )
            throw error
        }
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = ContentType.LIVE.name,
            state = "QUEUED",
            now = now,
            totalCategories = 1,
            completedCategories = 0,
            indexedRows = liveCount,
            lastAttemptAt = now,
            lastError = null
        )
        scheduleXtreamIndexSync(provider.id, ContentType.LIVE)

        val movieCategoryCount = syncXtreamCategoryShell(
            provider = provider,
            api = api,
            contentType = ContentType.MOVIE,
            label = "Movies",
            now = now,
            onProgress = onProgress
        ).getOrElse { error ->
            warnings += "Movies categories could not be loaded; movie indexing will retry later."
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = ContentType.MOVIE.name,
                state = xtreamIndexFailureState(error),
                now = now,
                lastAttemptAt = now,
                lastError = sanitizeThrowableMessage(error)
            )
            0
        }
        if (movieCategoryCount > 0) {
            scheduleXtreamIndexSync(provider.id, ContentType.MOVIE)
        }
        val seriesCategoryCount = syncXtreamCategoryShell(
            provider = provider,
            api = api,
            contentType = ContentType.SERIES,
            label = "Series",
            now = now,
            onProgress = onProgress
        ).getOrElse { error ->
            warnings += "Series categories could not be loaded; series indexing will retry later."
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = ContentType.SERIES.name,
                state = xtreamIndexFailureState(error),
                now = now,
                lastAttemptAt = now,
                lastError = sanitizeThrowableMessage(error)
            )
            0
        }
        if (seriesCategoryCount > 0) {
            scheduleXtreamIndexSync(provider.id, ContentType.SERIES)
        }

        metadata = metadata.copy(
            lastMovieAttempt = if (movieCategoryCount > 0) now else metadata.lastMovieAttempt,
            movieCatalogStale = true,
            movieSyncMode = VodSyncMode.UNKNOWN
        )
        syncMetadataRepository.updateMetadata(metadata)

        val epgState = if (provider.epgSyncMode == ProviderEpgSyncMode.SKIP) "IDLE" else "QUEUED"
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = "EPG",
            state = epgState,
            now = now,
            lastAttemptAt = if (epgState == "QUEUED") now else 0L
        )
        if (provider.epgSyncMode != ProviderEpgSyncMode.SKIP) {
            runCatching { scheduleBackgroundEpgSync(provider.id) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to schedule Xtream background EPG sync for provider ${provider.id}: ${sanitizeThrowableMessage(error)}")
                    warnings += "EPG background sync could not be scheduled; it will retry on the next launch or manual sync."
                }
        }

        if (force) {
            Log.i(TAG, "Xtream index-first sync completed for provider ${provider.id}; VOD and series index jobs are queued.")
        }
        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings.distinct())
    }

    private suspend fun recordXtreamLiveOnboardingState(
        provider: Provider,
        phase: String,
        now: Long = System.currentTimeMillis(),
        stagedSessionId: Long? = null,
        importStrategy: String? = null,
        nextCategoryIndex: Int? = null,
        acceptedRowCount: Int? = null,
        stagedFlushCount: Int? = null,
        lastError: String? = null,
        clearError: Boolean = false,
        completedAt: Long? = null,
        clearStagedSession: Boolean = false,
        runtimeProfile: CatalogSyncRuntimeProfile? = null,
        syncProfileStrategy: String? = null
    ) {
        val existing = xtreamLiveOnboardingDao.getByProvider(provider.id)
        xtreamLiveOnboardingDao.upsert(
            XtreamLiveOnboardingStateEntity(
                providerId = provider.id,
                providerType = provider.type.name,
                contentType = ContentType.LIVE.name,
                phase = phase,
                stagedSessionId = if (clearStagedSession) null else stagedSessionId ?: existing?.stagedSessionId,
                importStrategy = importStrategy ?: existing?.importStrategy,
                nextCategoryIndex = nextCategoryIndex ?: existing?.nextCategoryIndex ?: 0,
                acceptedRowCount = acceptedRowCount ?: existing?.acceptedRowCount ?: 0,
                stagedFlushCount = stagedFlushCount ?: existing?.stagedFlushCount ?: 0,
                syncProfileTier = runtimeProfile?.tier?.name ?: existing?.syncProfileTier,
                syncProfileBatchSize = runtimeProfile?.stageBatchSize ?: existing?.syncProfileBatchSize ?: 0,
                syncProfileStrategy = syncProfileStrategy ?: existing?.syncProfileStrategy,
                syncProfileLowMemory = runtimeProfile?.snapshot?.isCurrentlyLowOnMemory ?: existing?.syncProfileLowMemory ?: false,
                syncProfileMemoryClassMb = runtimeProfile?.snapshot?.memoryClassMb ?: existing?.syncProfileMemoryClassMb ?: 0,
                syncProfileAvailableMemMb = runtimeProfile?.snapshot?.availableMemMb ?: existing?.syncProfileAvailableMemMb ?: 0L,
                lastError = when {
                    clearError -> null
                    lastError != null -> lastError
                    else -> existing?.lastError
                },
                createdAt = existing?.createdAt?.takeIf { it > 0L } ?: now,
                updatedAt = now,
                completedAt = completedAt
            )
        )
    }

    private fun CatalogStrategyResult<*>.strategyNameOrNull(): String? = when (this) {
        is CatalogStrategyResult.Success -> strategyName
        is CatalogStrategyResult.Partial -> strategyName
        is CatalogStrategyResult.EmptyValid -> strategyName
        is CatalogStrategyResult.Failure -> strategyName
    }

    private fun stagedFlushCountFor(acceptedCount: Int): Int =
        if (acceptedCount <= 0) 0 else (acceptedCount + XTREAM_FALLBACK_STAGE_BATCH_SIZE - 1) / XTREAM_FALLBACK_STAGE_BATCH_SIZE

    private fun CatalogSyncPayload<Channel>.profileStrategyName(
        runtimeProfile: CatalogSyncRuntimeProfile,
        trackInitialLiveOnboarding: Boolean
    ): String {
        val baseStrategy = catalogResult.strategyNameOrNull() ?: "unknown"
        val fullPolicy = if (strategyFeedback.preferredSegmentedFirst) {
            "segmented_first"
        } else if (runtimeProfile.shouldAttemptFullLiveCatalog(trackInitialLiveOnboarding)) {
            "full_allowed"
        } else {
            "full_blocked"
        }
        return "$baseStrategy;$fullPolicy"
    }

    private suspend fun recoverXtreamLiveOnboardingSession(
        provider: Provider,
        hiddenLiveCategoryIds: Set<Long>
    ): XtreamLiveCommitResult? {
        val state = xtreamLiveOnboardingDao.getIncompleteByProvider(provider.id) ?: return null
        val sessionId = state.stagedSessionId ?: return null
        if (state.providerType != ProviderType.XTREAM_CODES.name || state.contentType != ContentType.LIVE.name) {
            discardXtreamLiveOnboardingSession(provider, sessionId, "Saved Live TV import did not match this provider.")
            return null
        }
        if (state.importStrategy != null && state.importStrategy != "full") {
            discardXtreamLiveOnboardingSession(provider, sessionId, "Saved Live TV import strategy could not be resumed.")
            return null
        }

        val stagedState = syncCatalogStore.stagedLiveImportState(provider.id, sessionId)
        val discardReason = when {
            stagedState.channelCount <= 0 -> "Saved Live TV import was missing staged channels."
            stagedState.movieCount > 0 || stagedState.seriesCount > 0 -> "Saved Live TV import contained rows for another catalog type."
            else -> null
        }
        if (discardReason != null) {
            discardXtreamLiveOnboardingSession(provider, sessionId, discardReason)
            return null
        }

        recordXtreamLiveOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_RECOVERING,
            stagedSessionId = sessionId,
            importStrategy = state.importStrategy ?: "full",
            acceptedRowCount = stagedState.channelCount,
            stagedFlushCount = stagedFlushCountFor(stagedState.channelCount),
            clearError = true
        )
        if (hiddenLiveCategoryIds.isNotEmpty()) {
            mergeHiddenChannelsIntoStaging(provider.id, sessionId, hiddenLiveCategoryIds)
        }
        val commitState = if (hiddenLiveCategoryIds.isNotEmpty()) {
            syncCatalogStore.stagedLiveImportState(provider.id, sessionId)
        } else {
            stagedState
        }
        recordXtreamLiveOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_COMMITTING,
            stagedSessionId = sessionId,
            importStrategy = state.importStrategy ?: "full",
            acceptedRowCount = stagedState.channelCount,
            stagedFlushCount = stagedFlushCountFor(stagedState.channelCount),
            clearError = true
        )
        syncCatalogStore.applyStagedLiveCatalog(
            providerId = provider.id,
            sessionId = sessionId,
            categories = commitState.categories.takeIf { it.isNotEmpty() }
        )
        return XtreamLiveCommitResult(
            acceptedCount = stagedState.channelCount,
            warnings = listOf("Live TV import resumed from saved staged session.")
        )
    }

    private suspend fun discardXtreamLiveOnboardingSession(
        provider: Provider,
        sessionId: Long,
        reason: String
    ) {
        Log.w(TAG, "Discarding saved Xtream Live onboarding session for provider ${provider.id}: $reason")
        syncCatalogStore.discardStagedImport(provider.id, sessionId)
        recordXtreamLiveOnboardingState(
            provider = provider,
            phase = XTREAM_ONBOARDING_PHASE_STARTING,
            lastError = reason,
            clearStagedSession = true
        )
    }

    private suspend fun syncXtreamCategoryShell(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        label: String,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): kotlin.Result<Int> = runCatching {
        progress(provider.id, onProgress, "Loading $label categories...")
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            lastAttemptAt = now
        )
        val categories = when (contentType) {
            ContentType.MOVIE -> requireResult(api.getVodCategories(), "Failed to load VOD categories")
            ContentType.SERIES -> requireResult(api.getSeriesCategories(), "Failed to load series categories")
            else -> throw IllegalArgumentException("Unsupported Xtream category shell: $contentType")
        }
        syncCatalogStore.upsertCategories(
            providerId = provider.id,
            type = contentType.name,
            categories = categories.map { category -> category.toEntity(provider.id) }
        )
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "QUEUED",
            now = now,
            totalCategories = categories.size,
            completedCategories = 0,
            nextCategoryIndex = 0,
            failedCategories = 0,
            indexedRows = 0,
            skippedMalformedRows = 0,
            lastAttemptAt = now,
            lastError = null
        )
        categories.size
    }

    suspend fun processQueuedXtreamIndexJobs(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        maxCategoriesPerSection: Int? = null,
        onProgress: ((String) -> Unit)? = null
    ): com.streamvault.domain.model.Result<Unit> = withProviderLock(providerId) lock@{
        val providerEntity = providerDao.getById(providerId)
            ?: return@lock com.streamvault.domain.model.Result.error("Provider $providerId not found")
        if (providerEntity.type != ProviderType.XTREAM_CODES) {
            return@lock com.streamvault.domain.model.Result.success(Unit)
        }

        val provider = providerEntity
            .copy(password = credentialCrypto.decryptIfNeeded(providerEntity.password))
            .toDomain()
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
        val sections = when (section) {
            ContentType.MOVIE -> listOf(ContentType.MOVIE)
            ContentType.SERIES -> listOf(ContentType.SERIES)
            ContentType.LIVE -> listOf(ContentType.LIVE)
            ContentType.SERIES_EPISODE -> emptyList()
            null -> listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)
        }

        var sawRetryableFailure = false
        val warnings = mutableListOf<String>()
        sections.forEach { contentType ->
            val job = xtreamIndexJobDao.get(providerId, contentType.name)
            if (!force && !shouldRunXtreamSummaryIndex(job)) {
                return@forEach
            }
            val failure = runCatching {
                when (contentType) {
                    ContentType.LIVE -> processXtreamLiveIndexBackfillSection(providerId, onProgress)
                    ContentType.MOVIE,
                    ContentType.SERIES -> processXtreamSummaryIndexSection(provider, api, contentType, maxCategoriesPerSection, onProgress)
                    ContentType.SERIES_EPISODE -> Unit
                }
            }.exceptionOrNull()
            if (failure != null) {
                val state = xtreamIndexFailureState(failure)
                val currentJob = xtreamIndexJobDao.get(providerId, contentType.name)
                if (currentJob?.state != "PARTIAL") {
                    val failureAt = System.currentTimeMillis()
                    upsertXtreamIndexJob(
                        providerId = providerId,
                        section = contentType.name,
                        state = state,
                        now = failureAt,
                        lastAttemptAt = failureAt,
                        lastError = sanitizeThrowableMessage(failure)
                    )
                }
                sawRetryableFailure = sawRetryableFailure || state != "FAILED_PERMANENT"
                warnings += "${contentType.name.lowercase().replaceFirstChar { it.titlecase() }} indexing failed: ${sanitizeThrowableMessage(failure)}"
            }
        }

        if (warnings.isNotEmpty()) {
            val message = warnings.joinToString("; ")
            val exception = if (sawRetryableFailure) IOException(message) else IllegalStateException(message)
            com.streamvault.domain.model.Result.error(warnings.first(), exception)
        } else {
            com.streamvault.domain.model.Result.success(Unit)
        }
    }

    private fun shouldRunXtreamSummaryIndex(job: XtreamIndexJobEntity?): Boolean {
        if (job == null) return true
        if (job.state in setOf("QUEUED", "RUNNING", "PARTIAL", "STALE", "FAILED_RETRYABLE")) return true
        return ContentCachePolicy.shouldRefresh(job.lastSuccessAt, ContentCachePolicy.CATALOG_TTL_MILLIS)
    }

    private suspend fun processXtreamSummaryIndexSection(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        maxCategories: Int?,
        onProgress: ((String) -> Unit)?
    ) {
        require(contentType == ContentType.MOVIE || contentType == ContentType.SERIES) {
            "Unsupported Xtream summary index section: $contentType"
        }
        val now = System.currentTimeMillis()
        val categories = categoryDao.getByProviderAndTypeSync(provider.id, contentType.name)
        if (categories.isEmpty()) {
            syncXtreamCategoryShell(
                provider = provider,
                api = api,
                contentType = contentType,
                label = xtreamIndexSectionLabel(contentType),
                now = now,
                onProgress = onProgress
            ).getOrThrow()
        }
        val initialJob = xtreamIndexJobDao.get(provider.id, contentType.name)
        val priorityCategoryId = initialJob?.priorityCategoryId
        val indexedCategories = categoryDao.getByProviderAndTypeSync(provider.id, contentType.name)
        val resumeJob = initialJob?.takeIf { it.state in setOf("QUEUED", "RUNNING", "PARTIAL") }
        val categoryLimit = maxCategories?.coerceAtLeast(1) ?: Int.MAX_VALUE
        var nextCategoryIndex = resumeJob?.nextCategoryIndex?.coerceIn(0, indexedCategories.size) ?: 0
        if (shouldAttemptFullXtreamSummaryStream(resumeJob, priorityCategoryId)) {
            when (val streamed = streamFullXtreamSummaryIndex(
                provider = provider,
                api = api,
                contentType = contentType,
                categories = indexedCategories,
                totalCategories = indexedCategories.size,
                now = now,
                onProgress = onProgress
            )) {
                is Result.Success -> return
                is Result.Error -> {
                    Log.w(
                        TAG,
                        "Full ${contentType.name} stream index failed for provider ${provider.id}; falling back to category slices: ${streamed.message}"
                    )
                    upsertXtreamIndexJob(
                        providerId = provider.id,
                        section = contentType.name,
                        state = "QUEUED",
                        now = System.currentTimeMillis(),
                        totalCategories = indexedCategories.size,
                        completedCategories = 0,
                        nextCategoryIndex = 0,
                        failedCategories = 0,
                        lastAttemptAt = now,
                        lastError = streamed.message
                    )
                    nextCategoryIndex = 0
                }
                Result.Loading -> Unit
            }
        }
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            totalCategories = indexedCategories.size,
            completedCategories = resumeJob?.completedCategories ?: 0,
            nextCategoryIndex = nextCategoryIndex,
            failedCategories = resumeJob?.failedCategories ?: 0,
            indexedRows = resumeJob?.indexedRows ?: 0,
            skippedMalformedRows = resumeJob?.skippedMalformedRows ?: 0,
            lastAttemptAt = now,
            lastError = null
        )

        var completedCategories = resumeJob?.completedCategories ?: 0
        var failedCategories = resumeJob?.failedCategories ?: 0
        var indexedRows = resumeJob?.indexedRows ?: 0
        var skippedMalformedRows = resumeJob?.skippedMalformedRows ?: 0
        var lastError: Throwable? = null
        var priorityHandled = false

        data class CategoryIndexWork(
            val category: CategoryEntity,
            val advancesCursor: Boolean
        )

        val workItems = buildList {
            val priorityIndex = priorityCategoryId?.let { id ->
                indexedCategories.indexOfFirst { it.categoryId == id }.takeIf { index -> index >= 0 }
            }
            if (priorityIndex != null && priorityIndex != nextCategoryIndex && size < categoryLimit) {
                add(CategoryIndexWork(indexedCategories[priorityIndex], advancesCursor = false))
            }
            while (nextCategoryIndex < indexedCategories.size && size < categoryLimit) {
                val category = indexedCategories[nextCategoryIndex]
                nextCategoryIndex++
                if (
                    priorityCategoryId != null &&
                    category.categoryId == priorityCategoryId &&
                    any { it.category.categoryId == priorityCategoryId }
                ) {
                    continue
                }
                add(CategoryIndexWork(category, advancesCursor = true))
            }
        }

        for (workItem in workItems) {
            val category = workItem.category
            progress(provider.id, onProgress, "Indexing ${xtreamIndexSectionLabel(contentType)}: ${category.name}")
            val outcome = when (contentType) {
                ContentType.MOVIE -> fetchMovieCategoryOutcome(provider, api, category.toXtreamCategory())
                ContentType.SERIES -> fetchSeriesCategoryOutcome(provider, api, category.toXtreamCategory())
                else -> error("Unsupported section")
            }
            when (val categoryOutcome = outcome.outcome) {
                is CategoryFetchOutcome.Success -> {
                    val accepted = when (contentType) {
                        ContentType.MOVIE -> upsertXtreamMovieSummaryBatch(
                            providerId = provider.id,
                            movies = categoryOutcome.items.filterIsInstance<Movie>(),
                            indexedAt = System.currentTimeMillis()
                        )
                        ContentType.SERIES -> upsertXtreamSeriesSummaryBatch(
                            providerId = provider.id,
                            series = categoryOutcome.items.filterIsInstance<Series>(),
                            indexedAt = System.currentTimeMillis()
                        )
                        else -> 0
                    }
                    if (workItem.advancesCursor) {
                        indexedRows += accepted
                        skippedMalformedRows += (categoryOutcome.rawCount - categoryOutcome.items.size).coerceAtLeast(0)
                        completedCategories++
                    }
                    if (category.categoryId == priorityCategoryId) {
                        priorityHandled = true
                        upsertXtreamIndexJob(
                            providerId = provider.id,
                            section = contentType.name,
                            state = "RUNNING",
                            now = System.currentTimeMillis(),
                            clearPriority = true
                        )
                    }
                }
                is CategoryFetchOutcome.Empty -> {
                    if (workItem.advancesCursor) {
                        completedCategories++
                    }
                    if (category.categoryId == priorityCategoryId) {
                        priorityHandled = true
                        upsertXtreamIndexJob(
                            providerId = provider.id,
                            section = contentType.name,
                            state = "RUNNING",
                            now = System.currentTimeMillis(),
                            clearPriority = true
                        )
                    }
                }
                is CategoryFetchOutcome.Failure -> {
                    if (workItem.advancesCursor) {
                        failedCategories++
                    }
                    lastError = categoryOutcome.error
                }
            }
            upsertXtreamIndexJob(
                providerId = provider.id,
                section = contentType.name,
                state = if (failedCategories > 0) "PARTIAL" else "RUNNING",
                now = System.currentTimeMillis(),
                totalCategories = indexedCategories.size,
                completedCategories = completedCategories,
                nextCategoryIndex = nextCategoryIndex,
                failedCategories = failedCategories,
                indexedRows = indexedRows,
                skippedMalformedRows = skippedMalformedRows,
                lastAttemptAt = now,
                lastError = lastError?.let(::sanitizeThrowableMessage)
            )
        }

        val finishedAt = System.currentTimeMillis()
        val hasMoreCategories = nextCategoryIndex < indexedCategories.size
        val finalState = when {
            hasMoreCategories -> "QUEUED"
            failedCategories > 0 -> "PARTIAL"
            else -> "SUCCESS"
        }
        val priorityWasNotPresent = priorityCategoryId != null &&
            indexedCategories.none { it.categoryId == priorityCategoryId }
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = finalState,
            now = finishedAt,
            totalCategories = indexedCategories.size,
            completedCategories = completedCategories,
            nextCategoryIndex = nextCategoryIndex,
            failedCategories = failedCategories,
            indexedRows = indexedRows,
            skippedMalformedRows = skippedMalformedRows,
            clearPriority = finalState == "SUCCESS" || priorityWasNotPresent || priorityHandled,
            lastAttemptAt = now,
            lastSuccessAt = if (finalState == "SUCCESS") finishedAt else null,
            lastError = lastError?.let(::sanitizeThrowableMessage)
        )
        updateXtreamSummaryMetadata(provider.id, contentType, indexedRows, finalState, finishedAt)
        if (hasMoreCategories) {
            scheduleXtreamIndexSync(provider.id, contentType, force = false)
        }
        if (!hasMoreCategories && failedCategories > 0) {
            throw IllegalStateException(
                "${xtreamIndexSectionLabel(contentType)} indexing completed partially.",
                lastError
            )
        }
    }

    private fun shouldAttemptFullXtreamSummaryStream(
        resumeJob: XtreamIndexJobEntity?,
        priorityCategoryId: Long?
    ): Boolean {
        if (priorityCategoryId != null && resumeJob?.nextCategoryIndex != 0) return false
        if (resumeJob == null) return true
        return resumeJob.nextCategoryIndex == 0 &&
            resumeJob.completedCategories == 0 &&
            resumeJob.failedCategories == 0 &&
            resumeJob.indexedRows == 0
    }

    private suspend fun streamFullXtreamSummaryIndex(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        categories: List<CategoryEntity>,
        totalCategories: Int,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): Result<Int> {
        progress(provider.id, onProgress, "Indexing ${xtreamIndexSectionLabel(contentType)}...")
        upsertXtreamIndexJob(
            providerId = provider.id,
            section = contentType.name,
            state = "RUNNING",
            now = now,
            totalCategories = totalCategories,
            completedCategories = 0,
            nextCategoryIndex = 0,
            failedCategories = 0,
            indexedRows = 0,
            skippedMalformedRows = 0,
            lastAttemptAt = now,
            lastError = null
        )

        var indexedRows = 0
        val categoryNamesById = categories.associate { it.categoryId to it.name }
        val adultCategoryIds = categories.filter { it.isAdult }.mapTo(mutableSetOf()) { it.categoryId }
        val streamResult = when (contentType) {
            ContentType.MOVIE -> api.streamVodSummaries(adultCategoryIds = adultCategoryIds) { batch ->
                val accepted = upsertXtreamMovieSummaryBatch(
                    providerId = provider.id,
                    movies = batch.withMovieCategoryNames(categoryNamesById),
                    indexedAt = System.currentTimeMillis()
                )
                indexedRows += accepted
                upsertXtreamIndexJob(
                    providerId = provider.id,
                    section = contentType.name,
                    state = "RUNNING",
                    now = System.currentTimeMillis(),
                    totalCategories = totalCategories,
                    completedCategories = 0,
                    nextCategoryIndex = 0,
                    indexedRows = indexedRows,
                    lastAttemptAt = now,
                    lastError = null
                )
            }
            ContentType.SERIES -> api.streamSeriesSummaries(adultCategoryIds = adultCategoryIds) { batch ->
                val accepted = upsertXtreamSeriesSummaryBatch(
                    providerId = provider.id,
                    series = batch.withSeriesCategoryNames(categoryNamesById),
                    indexedAt = System.currentTimeMillis()
                )
                indexedRows += accepted
                upsertXtreamIndexJob(
                    providerId = provider.id,
                    section = contentType.name,
                    state = "RUNNING",
                    now = System.currentTimeMillis(),
                    totalCategories = totalCategories,
                    completedCategories = 0,
                    nextCategoryIndex = 0,
                    indexedRows = indexedRows,
                    lastAttemptAt = now,
                    lastError = null
                )
            }
            else -> Result.error("Unsupported Xtream summary stream section: $contentType")
        }

        return when (streamResult) {
            is Result.Success -> {
                val finishedAt = System.currentTimeMillis()
                val acceptedCount = indexedRows.coerceAtLeast(streamResult.data)
                upsertXtreamIndexJob(
                    providerId = provider.id,
                    section = contentType.name,
                    state = "SUCCESS",
                    now = finishedAt,
                    totalCategories = totalCategories,
                    completedCategories = totalCategories,
                    nextCategoryIndex = totalCategories,
                    failedCategories = 0,
                    indexedRows = acceptedCount,
                    skippedMalformedRows = 0,
                    clearPriority = true,
                    lastAttemptAt = now,
                    lastSuccessAt = finishedAt,
                    lastError = null
                )
                updateXtreamSummaryMetadata(provider.id, contentType, acceptedCount, "SUCCESS", finishedAt)
                Result.success(acceptedCount)
            }
            is Result.Error -> streamResult
            Result.Loading -> Result.error("Xtream summary stream did not complete")
        }
    }

    private fun List<Movie>.withMovieCategoryNames(categoryNamesById: Map<Long, String>): List<Movie> =
        map { movie ->
            val categoryName = movie.categoryId?.let(categoryNamesById::get)
            if (categoryName == null || categoryName == movie.categoryName) movie else movie.copy(categoryName = categoryName)
        }

    private fun List<Series>.withSeriesCategoryNames(categoryNamesById: Map<Long, String>): List<Series> =
        map { series ->
            val categoryName = series.categoryId?.let(categoryNamesById::get)
            if (categoryName == null || categoryName == series.categoryName) series else series.copy(categoryName = categoryName)
        }

    private suspend fun upsertXtreamMovieSummaryBatch(
        providerId: Long,
        movies: List<Movie>,
        indexedAt: Long
    ): Int {
        if (movies.isEmpty()) return 0
        val incoming = movies.map { movie -> movie.toEntity().copy(cacheState = "SUMMARY_ONLY", detailHydratedAt = 0L, remoteStaleAt = 0L) }
        val existingByStreamId = loadMoviesByStreamIds(providerId, incoming.map { it.streamId })
        val merged = incoming.map { summary ->
            val existing = existingByStreamId[summary.streamId]
            mergeMovieSummary(existing, summary)
        }
        transactionRunner.inTransaction {
            movieDao.insertAll(merged)
            val persistedByStreamId = loadMoviesByStreamIds(providerId, merged.map { it.streamId })
            xtreamContentIndexDao.upsertAll(
                merged.map { movie ->
                    val persisted = persistedByStreamId[movie.streamId] ?: movie
                    movie.toXtreamIndexRow(providerId, persisted.id, indexedAt)
                }
            )
        }
        movieDao.restoreWatchProgress(providerId)
        return merged.size
    }

    private suspend fun upsertXtreamSeriesSummaryBatch(
        providerId: Long,
        series: List<Series>,
        indexedAt: Long
    ): Int {
        if (series.isEmpty()) return 0
        val incoming = series.map { item -> item.toEntity().copy(cacheState = "SUMMARY_ONLY", detailHydratedAt = 0L, remoteStaleAt = 0L) }
        val existingBySeriesId = loadSeriesByIds(providerId, incoming.map { it.seriesId })
        val merged = incoming.map { summary ->
            val existing = existingBySeriesId[summary.seriesId]
            mergeSeriesSummary(existing, summary)
        }
        transactionRunner.inTransaction {
            seriesDao.insertAll(merged)
            val persistedBySeriesId = loadSeriesByIds(providerId, merged.map { it.seriesId })
            xtreamContentIndexDao.upsertAll(
                merged.map { item ->
                    val persisted = persistedBySeriesId[item.seriesId] ?: item
                    item.toXtreamIndexRow(providerId, persisted.id, indexedAt)
                }
            )
        }
        return merged.size
    }

    private suspend fun loadMoviesByStreamIds(
        providerId: Long,
        streamIds: List<Long>
    ): Map<Long, MovieEntity> {
        return chunkedLookupById(
            ids = streamIds,
            chunkSize = XTREAM_SQLITE_LOOKUP_CHUNK_SIZE,
            fetch = { chunk -> movieDao.getByStreamIds(providerId, chunk) },
            keySelector = MovieEntity::streamId
        )
    }

    private suspend fun loadSeriesByIds(
        providerId: Long,
        seriesIds: List<Long>
    ): Map<Long, SeriesEntity> {
        return chunkedLookupById(
            ids = seriesIds,
            chunkSize = XTREAM_SQLITE_LOOKUP_CHUNK_SIZE,
            fetch = { chunk -> seriesDao.getBySeriesIds(providerId, chunk) },
            keySelector = SeriesEntity::seriesId
        )
    }

    private fun mergeMovieSummary(existing: MovieEntity?, summary: MovieEntity): MovieEntity {
        if (existing == null) return summary
        val preserveDetails = existing.cacheState == "DETAIL_HYDRATED" && existing.detailHydratedAt > 0L
        return summary.copy(
            id = existing.id,
            posterUrl = summary.posterUrl ?: existing.posterUrl,
            backdropUrl = if (preserveDetails) existing.backdropUrl else summary.backdropUrl,
            plot = if (preserveDetails) existing.plot else summary.plot,
            cast = if (preserveDetails) existing.cast else summary.cast,
            director = if (preserveDetails) existing.director else summary.director,
            genre = summary.genre ?: existing.genre,
            releaseDate = if (preserveDetails) existing.releaseDate else summary.releaseDate,
            duration = if (preserveDetails) existing.duration else summary.duration,
            durationSeconds = if (preserveDetails) existing.durationSeconds else summary.durationSeconds,
            year = if (preserveDetails) existing.year else summary.year,
            tmdbId = summary.tmdbId ?: existing.tmdbId,
            youtubeTrailer = summary.youtubeTrailer ?: existing.youtubeTrailer,
            watchProgress = existing.watchProgress,
            watchCount = existing.watchCount,
            lastWatchedAt = existing.lastWatchedAt,
            isUserProtected = existing.isUserProtected,
            cacheState = if (preserveDetails) existing.cacheState else "SUMMARY_ONLY",
            detailHydratedAt = if (preserveDetails) existing.detailHydratedAt else 0L,
            remoteStaleAt = 0L
        )
    }

    private fun mergeSeriesSummary(existing: SeriesEntity?, summary: SeriesEntity): SeriesEntity {
        if (existing == null) return summary
        val preserveDetails = existing.cacheState == "DETAIL_HYDRATED" && existing.detailHydratedAt > 0L
        return summary.copy(
            id = existing.id,
            providerSeriesId = summary.providerSeriesId ?: existing.providerSeriesId,
            posterUrl = summary.posterUrl ?: existing.posterUrl,
            backdropUrl = if (preserveDetails) existing.backdropUrl else summary.backdropUrl,
            plot = if (preserveDetails) existing.plot else summary.plot,
            cast = if (preserveDetails) existing.cast else summary.cast,
            director = if (preserveDetails) existing.director else summary.director,
            genre = summary.genre ?: existing.genre,
            releaseDate = if (preserveDetails) existing.releaseDate else summary.releaseDate,
            tmdbId = summary.tmdbId ?: existing.tmdbId,
            youtubeTrailer = summary.youtubeTrailer ?: existing.youtubeTrailer,
            episodeRunTime = if (preserveDetails) existing.episodeRunTime else summary.episodeRunTime,
            isUserProtected = existing.isUserProtected,
            cacheState = if (preserveDetails) existing.cacheState else "SUMMARY_ONLY",
            detailHydratedAt = if (preserveDetails) existing.detailHydratedAt else 0L,
            remoteStaleAt = 0L
        )
    }

    private fun MovieEntity.toXtreamIndexRow(
        providerId: Long,
        localContentId: Long,
        indexedAt: Long
    ): XtreamContentIndexEntity = XtreamContentIndexEntity(
        providerId = providerId,
        contentType = ContentType.MOVIE,
        remoteId = streamId.toString(),
        localContentId = localContentId.takeIf { it > 0L },
        name = name,
        categoryId = categoryId,
        categoryName = categoryName,
        imageUrl = posterUrl,
        containerExtension = containerExtension,
        rating = rating,
        addedAt = addedAt,
        isAdult = isAdult,
        indexedAt = indexedAt,
        detailHydratedAt = detailHydratedAt,
        staleState = "ACTIVE",
        errorState = null,
        syncFingerprint = syncFingerprint
    )

    private fun SeriesEntity.toXtreamIndexRow(
        providerId: Long,
        localContentId: Long,
        indexedAt: Long
    ): XtreamContentIndexEntity = XtreamContentIndexEntity(
        providerId = providerId,
        contentType = ContentType.SERIES,
        remoteId = providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesId.toString(),
        localContentId = localContentId.takeIf { it > 0L },
        name = name,
        categoryId = categoryId,
        categoryName = categoryName,
        imageUrl = posterUrl,
        rating = rating,
        remoteUpdatedAt = lastModified,
        isAdult = isAdult,
        indexedAt = indexedAt,
        detailHydratedAt = detailHydratedAt,
        staleState = "ACTIVE",
        errorState = null,
        syncFingerprint = syncFingerprint
    )

    private suspend fun updateXtreamSummaryMetadata(
        providerId: Long,
        contentType: ContentType,
        indexedRows: Int,
        finalState: String,
        now: Long
    ) {
        val metadata = syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)
        when (contentType) {
            ContentType.MOVIE -> syncMetadataRepository.updateMetadata(
                metadata.copy(
                    lastMovieSync = now,
                    lastMovieAttempt = now,
                    lastMovieSuccess = if (finalState == "SUCCESS") now else metadata.lastMovieSuccess,
                    lastMoviePartial = if (finalState != "SUCCESS") now else metadata.lastMoviePartial,
                    movieCount = if (finalState == "SUCCESS") indexedRows else metadata.movieCount.coerceAtLeast(indexedRows),
                    movieCatalogStale = finalState != "SUCCESS",
                    movieSyncMode = VodSyncMode.UNKNOWN
                )
            )
            ContentType.SERIES -> syncMetadataRepository.updateMetadata(
                metadata.copy(
                    lastSeriesSync = now,
                    lastSeriesSuccess = if (finalState == "SUCCESS") now else metadata.lastSeriesSuccess,
                    seriesCount = if (finalState == "SUCCESS") indexedRows else metadata.seriesCount.coerceAtLeast(indexedRows)
                )
            )
            else -> Unit
        }
    }

    private fun CategoryEntity.toXtreamCategory(): XtreamCategory = XtreamCategory(
        categoryId = categoryId.toString(),
        categoryName = name,
        parentId = parentId?.toInt() ?: 0,
        isAdult = isAdult
    )

    private fun xtreamIndexSectionLabel(contentType: ContentType): String = when (contentType) {
        ContentType.MOVIE -> "Movies"
        ContentType.SERIES -> "Series"
        ContentType.LIVE -> "Live TV"
        ContentType.SERIES_EPISODE -> "Episodes"
    }

    private suspend fun processXtreamLiveIndexBackfillSection(
        providerId: Long,
        onProgress: ((String) -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        progress(providerId, onProgress, "Preparing Live TV index...")
        upsertXtreamIndexJob(
            providerId = providerId,
            section = ContentType.LIVE.name,
            state = "RUNNING",
            now = now,
            lastAttemptAt = now,
            lastError = null
        )
        val categoryCount = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name).size
        val indexedRows = backfillXtreamLiveIndex(providerId, now)
        upsertXtreamIndexJob(
            providerId = providerId,
            section = ContentType.LIVE.name,
            state = "SUCCESS",
            now = System.currentTimeMillis(),
            totalCategories = categoryCount,
            completedCategories = categoryCount,
            nextCategoryIndex = categoryCount,
            failedCategories = 0,
            indexedRows = indexedRows,
            skippedMalformedRows = 0,
            lastAttemptAt = now,
            lastSuccessAt = System.currentTimeMillis(),
            lastError = null
        )
    }

    private suspend fun backfillXtreamLiveIndex(providerId: Long, indexedAt: Long): Int {
        val channels = channelDao.getByProviderSync(providerId)
        if (channels.isEmpty()) return 0
        val indexRows = channels.map { channel ->
            XtreamContentIndexEntity(
                providerId = providerId,
                contentType = ContentType.LIVE,
                remoteId = channel.streamId.toString(),
                localContentId = channel.id.takeIf { it > 0 },
                name = channel.name,
                categoryId = channel.categoryId,
                categoryName = channel.categoryName ?: channel.groupTitle,
                imageUrl = channel.logoUrl,
                isAdult = channel.isAdult,
                indexedAt = indexedAt,
                detailHydratedAt = indexedAt,
                syncFingerprint = channel.syncFingerprint
            )
        }
        transactionRunner.inTransaction {
            xtreamContentIndexDao.deleteByProviderAndType(providerId, ContentType.LIVE.name)
            xtreamContentIndexDao.upsertAll(indexRows)
        }
        return channels.size
    }

    private fun xtreamIndexFailureState(error: Throwable): String {
        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        if (chain.any { it is XtreamAuthenticationException }) {
            return "FAILED_PERMANENT"
        }
        val requestFailure = chain.filterIsInstance<XtreamRequestException>().firstOrNull()
        if (requestFailure?.statusCode == 401 || requestFailure?.statusCode == 403) {
            return "FAILED_PERMANENT"
        }
        return "FAILED_RETRYABLE"
    }

    private suspend fun upsertXtreamIndexJob(
        providerId: Long,
        section: String,
        state: String,
        now: Long,
        totalCategories: Int? = null,
        completedCategories: Int? = null,
        nextCategoryIndex: Int? = null,
        failedCategories: Int? = null,
        indexedRows: Int? = null,
        skippedMalformedRows: Int? = null,
        deletedPrunedRows: Int? = null,
        priorityCategoryId: Long? = null,
        priorityRequestedAt: Long? = null,
        clearPriority: Boolean = false,
        lastAttemptAt: Long? = null,
        lastSuccessAt: Long? = null,
        lastError: String? = null
    ) {
        val existing = xtreamIndexJobDao.get(providerId, section)
        xtreamIndexJobDao.upsert(
            (existing ?: XtreamIndexJobEntity(providerId = providerId, section = section)).copy(
                state = state,
                totalCategories = totalCategories ?: existing?.totalCategories ?: 0,
                completedCategories = completedCategories ?: existing?.completedCategories ?: 0,
                nextCategoryIndex = nextCategoryIndex ?: existing?.nextCategoryIndex ?: 0,
                failedCategories = failedCategories ?: existing?.failedCategories ?: 0,
                indexedRows = indexedRows ?: existing?.indexedRows ?: 0,
                skippedMalformedRows = skippedMalformedRows ?: existing?.skippedMalformedRows ?: 0,
                deletedPrunedRows = deletedPrunedRows ?: existing?.deletedPrunedRows ?: 0,
                priorityCategoryId = if (clearPriority) null else priorityCategoryId ?: existing?.priorityCategoryId,
                priorityRequestedAt = if (clearPriority) 0L else priorityRequestedAt ?: existing?.priorityRequestedAt ?: 0L,
                lastError = lastError,
                lastAttemptAt = lastAttemptAt ?: existing?.lastAttemptAt ?: 0L,
                lastSuccessAt = lastSuccessAt ?: existing?.lastSuccessAt ?: 0L,
                updatedAt = now
            )
        )
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
            ).warnings
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
            val liveCatalogResult = syncStalkerLiveCatalogStaged(api, provider, hiddenLiveCategoryIds, onProgress)
            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                liveCount = liveCatalogResult.acceptedCount
            )
            syncMetadataRepository.updateMetadata(metadata)
            warnings += liveCatalogResult.warnings
        }

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastMovieSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            progress(provider.id, onProgress, "Downloading Movies...")
            val categories = requireResult(api.getVodCategories(), "Failed to load movie categories")
            // Stalker uses lazy-by-category VoD loading — only categories are persisted up
            // front. Using replaceMovieCatalog with an empty sequence would run full stale
            // deletion and destroy any movies already hydrated via on-demand category loads.
            // replaceCategories updates/inserts category rows without touching movie rows.
            syncCatalogStore.replaceCategories(
                providerId = provider.id,
                type = "MOVIE",
                categories = categories.map { category ->
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = category.id,
                        name = category.name,
                        parentId = category.parentId,
                        type = ContentType.MOVIE,
                        isAdult = category.isAdult
                    )
                }
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
            // Same rationale as movies: use replaceCategories to avoid destroying any
            // already-hydrated series rows when running a category-only Stalker sync.
            syncCatalogStore.replaceCategories(
                providerId = provider.id,
                type = "SERIES",
                categories = categories.map { category ->
                    CategoryEntity(
                        providerId = provider.id,
                        categoryId = category.id,
                        name = category.name,
                        parentId = category.parentId,
                        type = ContentType.SERIES,
                        isAdult = category.isAdult
                    )
                }
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
            ).warnings
        }

        return if (warnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = warnings)
    }

    /**
     * Returned by [syncProviderEpg] so callers can distinguish between warning-only
     * degradations and transient network/IO failures that WorkManager should retry.
     */
    private data class EpgSyncResult(
        val warnings: List<String>,
        /** True when at least one EPG sub-operation failed due to a network or IO error
         *  rather than a permanent configuration problem (bad URL, bad credentials, etc.).
         *  WorkManager workers should return [Result.retry()] when this is true. */
        val hasRetryableFailure: Boolean
    )

    /**
     * Returns true for transient network/IO exceptions that are worth retrying via
     * WorkManager backoff — as opposed to permanent failures (bad URL, auth, parse error)
     * that will fail identically on every attempt.
     */
    private fun isRetryableEpgException(e: Exception): Boolean =
        e is java.io.IOException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.net.UnknownHostException ||
            e.cause?.let {
                it is java.io.IOException ||
                    it is java.net.SocketTimeoutException ||
                    it is java.net.ConnectException ||
                    it is java.net.UnknownHostException
            } == true

    private suspend fun syncProviderEpg(
        provider: Provider,
        metadata: SyncMetadata,
        now: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): EpgSyncResult {
        var updatedMetadata = metadata
        val warnings = mutableListOf<String>()
        var hasRetryableFailure = false
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
                        retryTransient {
                            requireResult(epgRepository.refreshEpg(provider.id, xmltvUrl), "Failed to refresh EPG")
                        }
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
                        if (isRetryableEpgException(e)) hasRetryableFailure = true
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
                            retryTransient {
                                requireResult(epgRepository.refreshEpg(provider.id, currentEpgUrl), "Failed to refresh EPG")
                            }
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
                            if (isRetryableEpgException(e)) hasRetryableFailure = true
                            warnings.add("EPG sync failed")
                        }
                    }
                }
            }

            ProviderType.STALKER_PORTAL -> {
                if (force || ContentCachePolicy.shouldRefresh(updatedMetadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now)) {
                    val stalkerEpgWarnings = syncStalkerPreferredEpg(
                        provider = provider,
                        now = now,
                        onProgress = onProgress
                    )
                    // Any Stalker EPG warning indicates an HTTP request failure, which is
                    // typically transient.
                    if (stalkerEpgWarnings.isNotEmpty()) hasRetryableFailure = true
                    warnings += stalkerEpgWarnings
                    updatedMetadata = syncMetadataRepository.getMetadata(provider.id) ?: updatedMetadata
                }
            }
        }

        try {
            progress(provider.id, onProgress, "Refreshing external EPG sources...")
            retryTransient {
                requireResult(
                    epgSourceRepository.refreshAllForProvider(provider.id),
                    "External EPG source refresh failed"
                )
            }
            progress(provider.id, onProgress, "Resolving EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "External EPG resolution failed (non-fatal): ${sanitizeThrowableMessage(e)}")
            if (isRetryableEpgException(e)) hasRetryableFailure = true
            warnings.add("External EPG source refresh/resolution failed.")
        }

        return EpgSyncResult(warnings = warnings, hasRetryableFailure = hasRetryableFailure)
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
            retryTransient {
                requireResult(
                    epgSourceRepository.refreshAllForProvider(provider.id),
                    "External EPG source refresh failed"
                )
            }
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
                retryTransient {
                    requireResult(
                        epgSourceRepository.refreshAllForProvider(provider.id),
                        "External EPG source refresh failed"
                    )
                }
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
        retryTransient {
            requireResult(epgRepository.refreshEpg(provider.id, epgUrl), "Failed to refresh EPG")
        }
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
        retryTransient {
            requireResult(
                epgSourceRepository.refreshAllForProvider(provider.id),
                "External EPG source refresh failed"
            )
        }
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
                    retryTransient {
                        requireResult(epgRepository.refreshEpg(provider.id, currentEpgUrl), "Failed to refresh EPG")
                    }
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
            // Stream the bulk EPG payload program-by-program; the previous buffered API
            // could materialise >30 MB JSON trees on certain Stalker portals which led to
            // OOM crashes when re-entering content screens.
            api.streamBulkEpg(periodHours = 6) { program ->
                val resolvedChannelKey = aliasToChannelKey[program.channelId] ?: return@streamBulkEpg
                if (program.endTime <= program.startTime) return@streamBulkEpg
                bulkCoveredChannelKeys += resolvedChannelKey
                insertBuffer += program.copy(
                    providerId = provider.id,
                    channelId = resolvedChannelKey
                ).toEntity()
                if (insertBuffer.size >= STALKER_GUIDE_PROGRAM_BATCH_SIZE) {
                    flushPrograms()
                }
            }.let { result ->
                if (result is com.streamvault.domain.model.Result.Error) {
                    throw result.exception ?: IllegalStateException(result.message)
                }
            }
        }.onFailure { error ->
            Log.d(TAG, "Bulk Stalker portal EPG fetch unavailable for provider ${provider.id}", error)
        }

        val fallbackGuideRequests = guideRequests.filterNot { request -> request.channelKey in bulkCoveredChannelKeys }

        // Some portals ignore `ch_id` and return the entire bulk EPG for every per-channel
        // request. After the first per-channel call we sample the response shape; if it
        // looks like the portal is repeatedly emitting the bulk payload we abort the
        // per-channel loop to avoid downloading the same multi-megabyte payload N times.
        var ignorePerChannelGuide = false

        fallbackGuideRequests.forEachIndexed { index, request ->
            if (ignorePerChannelGuide) return@forEachIndexed
            progress(provider.id, onProgress, "Downloading portal EPG... ${index + 1} of ${fallbackGuideRequests.size}")
            runCatching {
                var perChannelRecordCount = 0
                val foreignChannelIds = HashSet<String>()
                val streamResult = api.streamEpg(request.channelKey) { program ->
                    if (program.endTime <= program.startTime) return@streamEpg
                    if (program.channelId != request.channelKey) {
                        foreignChannelIds += program.channelId
                    }
                    insertBuffer += program.copy(
                        providerId = provider.id,
                        channelId = request.channelKey
                    ).toEntity()
                    perChannelRecordCount++
                    if (perChannelRecordCount >= STALKER_PER_CHANNEL_RECORD_SANITY_CAP || foreignChannelIds.size > 1) {
                        // Bail out of this single response — the portal is clearly returning
                        // the bulk payload again. The outer loop will then disable any
                        // remaining per-channel calls.
                        throw StalkerBrokenPerChannelEpgException(request.channelName)
                    }
                    if (insertBuffer.size >= STALKER_GUIDE_PROGRAM_BATCH_SIZE) {
                        flushPrograms()
                    }
                }
                if (streamResult is com.streamvault.domain.model.Result.Error) {
                    throw streamResult.exception ?: IllegalStateException(streamResult.message)
                }
            }.onFailure { error ->
                if (error is StalkerBrokenPerChannelEpgException) {
                    ignorePerChannelGuide = true
                    Log.w(
                        TAG,
                        "Stalker portal returned bulk-shaped payload for per-channel EPG request " +
                            "(${request.channelKey}); skipping remaining per-channel guide calls."
                    )
                } else {
                    failedChannels += request.channelName
                    Log.w(
                        TAG,
                        "Stalker portal EPG fetch failed for provider ${provider.id} channel ${request.channelKey}",
                        error
                    )
                }
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
        syncReason: XtreamLiveSyncReason,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val now = System.currentTimeMillis()
        val sectionWarnings = mutableListOf<String>()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(provider.id, onProgress, "Retrying Live TV...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val runtimeProfile = CatalogSyncRuntimeProfile.from(applicationContext)
                val liveSyncResult = syncXtreamLiveCatalog(
                    provider = provider,
                    api = api,
                    existingMetadata = currentMetadata,
                    hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                    onProgress = onProgress,
                    runtimeProfile = runtimeProfile,
                    trackInitialLiveOnboarding = false,
                    syncReason = syncReason
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
                        val acceptedCount = finalizeXtreamLiveCatalog(
                            providerId = provider.id,
                            liveSyncResult = liveSyncResult,
                            hiddenLiveCategoryIds = hiddenLiveCategoryIds
                        ).acceptedCount
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
                        val commitResult = finalizeXtreamLiveCatalog(
                            providerId = provider.id,
                            liveSyncResult = liveSyncResult,
                            hiddenLiveCategoryIds = hiddenLiveCategoryIds,
                            partialCompletionWarning = "Live TV retry completed partially."
                        )
                        val acceptedCount = commitResult.acceptedCount
                        syncMetadataRepository.updateMetadata(
                            currentMetadata.copy(
                                lastLiveSync = now,
                                liveCount = acceptedCount,
                                liveAvoidFullUntil = liveAvoidFullUntil,
                                liveSequentialFailuresRemembered = currentMetadata.liveSequentialFailuresRemembered || liveSequentialStress,
                                liveHealthySyncStreak = 0
                            )
                        )
                        sectionWarnings += commitResult.warnings
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
                val liveCatalogResult = syncStalkerLiveCatalogStaged(api, provider, hiddenLiveCategoryIds, onProgress)
                val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
                    .copy(
                        lastLiveSync = now,
                        lastLiveSuccess = now,
                        liveCount = liveCatalogResult.acceptedCount
                    )
                syncMetadataRepository.updateMetadata(metadata)
                sectionWarnings += liveCatalogResult.warnings
            }
        }
        return if (sectionWarnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = sectionWarnings)
    }

    private suspend fun syncMoviesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val now = System.currentTimeMillis()
        val sectionWarnings = mutableListOf<String>()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                progress(provider.id, onProgress, "Queueing Movies index...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val categoryCount = syncXtreamCategoryShell(
                    provider = provider,
                    api = api,
                    contentType = ContentType.MOVIE,
                    label = "Movies",
                    now = now,
                    onProgress = onProgress
                ).getOrElse { error ->
                    upsertXtreamIndexJob(
                        providerId = provider.id,
                        section = ContentType.MOVIE.name,
                        state = xtreamIndexFailureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    throw IllegalStateException(
                        syncErrorSanitizer.userMessage(error, "Failed to queue movie index"),
                        error
                    )
                }
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastMovieAttempt = now,
                        movieCatalogStale = true,
                        movieSyncMode = VodSyncMode.UNKNOWN
                    )
                )
                scheduleXtreamIndexSync(provider.id, ContentType.MOVIE)
                Log.i(TAG, "Queued Xtream movie index for provider ${provider.id}: $categoryCount categories.")
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
        return if (sectionWarnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = sectionWarnings)
    }

    private suspend fun syncSeriesOnly(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val sectionWarnings = mutableListOf<String>()
        when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                val now = System.currentTimeMillis()
                progress(provider.id, onProgress, "Queueing Series index...")
                val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
                val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
                val api = createXtreamSyncProvider(provider, useTextClassification, enableBase64TextCompatibility)
                val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
                val categoryCount = syncXtreamCategoryShell(
                    provider = provider,
                    api = api,
                    contentType = ContentType.SERIES,
                    label = "Series",
                    now = now,
                    onProgress = onProgress
                ).getOrElse { error ->
                    upsertXtreamIndexJob(
                        providerId = provider.id,
                        section = ContentType.SERIES.name,
                        state = xtreamIndexFailureState(error),
                        now = now,
                        lastAttemptAt = now,
                        lastError = sanitizeThrowableMessage(error)
                    )
                    throw IllegalStateException(
                        syncErrorSanitizer.userMessage(error, "Failed to queue series index"),
                        error
                    )
                }
                syncMetadataRepository.updateMetadata(
                    currentMetadata.copy(
                        lastSeriesSync = now
                    )
                )
                scheduleXtreamIndexSync(provider.id, ContentType.SERIES)
                Log.i(TAG, "Queued Xtream series index for provider ${provider.id}: $categoryCount categories.")
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
        return if (sectionWarnings.isEmpty()) SyncOutcome() else SyncOutcome(partial = true, warnings = sectionWarnings)
    }

    private suspend fun syncXtreamLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?,
        runtimeProfile: CatalogSyncRuntimeProfile = CatalogSyncRuntimeProfile.from(applicationContext),
        trackInitialLiveOnboarding: Boolean = false,
        syncReason: XtreamLiveSyncReason = XtreamLiveSyncReason.FOREGROUND
    ): CatalogSyncPayload<Channel> {
        val effectiveLiveSyncMethod = XtreamLiveSyncPolicy.resolve(
            userMode = provider.xtreamLiveSyncMode,
            runtimeProfile = runtimeProfile,
            syncReason = syncReason,
            metadata = existingMetadata,
            now = System.currentTimeMillis(),
            hiddenLiveCategoryIds = hiddenLiveCategoryIds
        )
        Log.i(
            TAG,
            "Xtream live sync method for provider ${provider.id}: user=${provider.xtreamLiveSyncMode} effective=$effectiveLiveSyncMethod reason=$syncReason profile=${runtimeProfile.diagnosticsLabel}."
        )
        return xtreamLiveStrategy.syncXtreamLiveCatalog(
            provider,
            api,
            existingMetadata,
            hiddenLiveCategoryIds,
            onProgress,
            runtimeProfile,
            trackInitialLiveOnboarding,
            effectiveLiveSyncMethod
        )
    }

    private suspend fun loadXtreamLiveFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Channel> = xtreamLiveStrategy.loadXtreamLiveFull(provider, api, CatalogSyncRuntimeProfile.from(applicationContext))

    private suspend fun loadXtreamLiveByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogSyncPayload<Channel> =
        xtreamLiveStrategy.loadXtreamLiveByCategory(provider, api, rawCategories, onProgress, preferSequential, CatalogSyncRuntimeProfile.from(applicationContext))

    private suspend fun updateSyncStatusMetadata(providerId: Long, status: String) {
        val metadata = (syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId))
            .copy(lastSyncStatus = status)
        syncMetadataRepository.updateMetadata(metadata)
    }

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

    private suspend fun finalizeXtreamLiveCatalog(
        providerId: Long,
        liveSyncResult: CatalogSyncPayload<Channel>,
        hiddenLiveCategoryIds: Set<Long>,
        partialCompletionWarning: String? = null
    ): XtreamLiveCommitResult {
        val warnings = buildList {
            addAll(liveSyncResult.warnings)
            addAll(catalogStrategySupport.strategyWarnings(liveSyncResult.catalogResult))
            partialCompletionWarning?.let(::add)
        }
        val acceptedCount = when (val liveResult = liveSyncResult.catalogResult) {
            is CatalogStrategyResult.Success -> {
                liveSyncResult.stagedSessionId?.let { sessionId ->
                    val mergedCategories = mergeVisibleLiveCategoriesWithHiddenStoredContent(
                        providerId = providerId,
                        visibleCategories = liveSyncResult.categories,
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds
                    )
                    if (hiddenLiveCategoryIds.isNotEmpty()) {
                        mergeHiddenChannelsIntoStaging(providerId, sessionId, hiddenLiveCategoryIds)
                    }
                    syncCatalogStore.applyStagedLiveCatalog(
                        providerId = providerId,
                        sessionId = sessionId,
                        categories = mergedCategories
                    )
                    liveSyncResult.stagedAcceptedCount
                } ?: run {
                    val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                        providerId = providerId,
                        visibleCategories = liveSyncResult.categories,
                        visibleChannels = liveResult.items.map { it.toEntity() },
                        hiddenLiveCategoryIds = hiddenLiveCategoryIds
                    )
                    syncCatalogStore.replaceLiveCatalog(
                        providerId = providerId,
                        categories = liveCatalog.categories,
                        channels = liveCatalog.channels
                    )
                }
            }
            is CatalogStrategyResult.Partial -> {
                val liveCatalog = mergeVisibleLiveSyncWithHiddenStoredContent(
                    providerId = providerId,
                    visibleCategories = liveSyncResult.categories,
                    visibleChannels = liveResult.items.map { it.toEntity() },
                    hiddenLiveCategoryIds = hiddenLiveCategoryIds
                )
                syncCatalogStore.upsertLiveCatalog(
                    providerId = providerId,
                    categories = liveCatalog.categories,
                    channels = liveCatalog.channels
                )
            }
            is CatalogStrategyResult.EmptyValid,
            is CatalogStrategyResult.Failure -> {
                throw IllegalArgumentException(
                    "finalizeXtreamLiveCatalog only supports success or partial results"
                )
            }
        }
        return XtreamLiveCommitResult(acceptedCount = acceptedCount, warnings = warnings)
    }

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

    private suspend fun mergeVisibleLiveCategoriesWithHiddenStoredContent(
        providerId: Long,
        visibleCategories: List<CategoryEntity>?,
        hiddenLiveCategoryIds: Set<Long>
    ): List<CategoryEntity>? {
        if (hiddenLiveCategoryIds.isEmpty()) {
            return visibleCategories
        }

        return ((visibleCategories ?: emptyList()) + categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .filter { category -> category.categoryId in hiddenLiveCategoryIds })
            .distinctBy { it.categoryId to it.type }
            .sortedBy { it.categoryId }
            .takeIf { it.isNotEmpty() }
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
            enableBase64TextCompatibility = enableBase64TextCompatibility,
            requestProfile = provider.toGenericRequestProfile(ownerTag = "provider:${provider.id}/xtream")
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

    private data class StagedStalkerLiveCatalogResult(
        val acceptedCount: Int,
        val warnings: List<String> = emptyList()
    )

    private suspend fun syncStalkerLiveCatalogStaged(
        api: StalkerProvider,
        provider: Provider,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?
    ): StagedStalkerLiveCatalogResult {
        val warnings = mutableListOf<String>()
        var categoriesErrorMessage: String? = null
        val preferredCategories = when (val categoriesResult = api.getLiveCategories()) {
            is com.streamvault.domain.model.Result.Success -> categoriesResult.data
                .map { it.toEntity(provider.id) }
                .filterNot { category -> category.categoryId in hiddenLiveCategoryIds }
            is com.streamvault.domain.model.Result.Error -> {
                Log.w(
                    TAG,
                    "Stalker live categories failed for provider ${provider.id}; streaming bulk live channels with fallback categories: ${categoriesResult.message}",
                    categoriesResult.exception
                )
                warnings += "Live categories failed; recovered using bulk live channels."
                categoriesErrorMessage = categoriesResult.message
                null
            }
            is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
        }

        progress(provider.id, onProgress, "Loading live channels...")
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.LIVE)
        val seenStreamIds = HashSet<Long>()
        val batch = ArrayList<Channel>(XTREAM_FALLBACK_STAGE_BATCH_SIZE)
        var stagedSessionId: Long? = null
        var acceptedCount = 0

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val staged = catalogStager.stageChannelItems(
                providerId = provider.id,
                items = batch,
                seenStreamIds = seenStreamIds,
                fallbackCollector = fallbackCollector,
                sessionId = stagedSessionId
            )
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            batch.clear()
        }

        try {
            when (val streamResult = api.streamLiveStreams { channel ->
                if (channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds) {
                    return@streamLiveStreams
                }
                batch += channel
                if (batch.size >= XTREAM_FALLBACK_STAGE_BATCH_SIZE) {
                    flushBatch()
                }
            }) {
                is com.streamvault.domain.model.Result.Success -> flushBatch()
                is com.streamvault.domain.model.Result.Error -> {
                    val profileDiagnostic = stalkerCatalogAccessDiagnostic(
                        api = api,
                        primaryMessage = categoriesErrorMessage.orEmpty(),
                        fallbackMessage = streamResult.message
                    )
                    throw IllegalStateException(
                        buildString {
                            append(streamResult.message.ifBlank { "Failed to load live channels" })
                            profileDiagnostic?.let {
                                append(' ')
                                append(it)
                            }
                        },
                        streamResult.exception
                    )
                }
                is com.streamvault.domain.model.Result.Loading -> throw IllegalStateException("Unexpected loading state")
            }

            val sessionId = stagedSessionId
            if (acceptedCount == 0 || sessionId == null) {
                throw IllegalStateException("Stalker bulk live catalog returned no usable channels.")
            }

            if (hiddenLiveCategoryIds.isNotEmpty()) {
                mergeHiddenChannelsIntoStaging(provider.id, sessionId, hiddenLiveCategoryIds)
            }
            val hiddenCategories = if (hiddenLiveCategoryIds.isNotEmpty()) {
                categoryDao.getByProviderAndTypeSync(provider.id, ContentType.LIVE.name)
                    .filter { category -> category.categoryId in hiddenLiveCategoryIds }
            } else {
                emptyList()
            }
            val categories = (
                mergePreferredAndFallbackCategories(
                    preferredCategories,
                    fallbackCollector.entities().takeIf { it.isNotEmpty() }
                ).orEmpty() + hiddenCategories
            )
                .distinctBy { it.categoryId to it.type }
                .takeIf { it.isNotEmpty() }
            syncCatalogStore.applyStagedLiveCatalog(provider.id, sessionId, categories)
            return StagedStalkerLiveCatalogResult(acceptedCount, warnings)
        } catch (error: CancellationException) {
            stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
            throw error
        } catch (error: Exception) {
            stagedSessionId?.let { syncCatalogStore.discardStagedImport(provider.id, it) }
            throw error
        }
    }

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

    private suspend fun <T> continueFailedCategoryOutcomes(
        provider: Provider,
        timedOutcomes: List<TimedCategoryOutcome<T>>,
        fetchSequentially: suspend (XtreamCategory) -> TimedCategoryOutcome<T>
    ): List<TimedCategoryOutcome<T>> = xtreamSupport.continueFailedCategoryOutcomes(provider, timedOutcomes, fetchSequentially)
}
