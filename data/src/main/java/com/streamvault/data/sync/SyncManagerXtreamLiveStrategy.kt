package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.SyncMetadata
import kotlin.system.measureTimeMillis

private const val XTREAM_LIVE_STRATEGY_TAG = "SyncManager"

internal class SyncManagerXtreamLiveStrategy(
    private val xtreamCatalogApiService: XtreamApiService,
    private val xtreamCatalogHttpService: OkHttpXtreamApiService,
    private val xtreamAdaptiveSyncPolicy: XtreamAdaptiveSyncPolicy,
    private val xtreamSupport: SyncManagerXtreamSupport,
    private val xtreamFetcher: SyncManagerXtreamFetcher,
    private val catalogStrategySupport: SyncManagerCatalogStrategySupport,
    private val syncCatalogStore: SyncCatalogStore,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val sanitizeThrowableMessage: (Throwable?) -> String,
    private val fullCatalogFallbackWarning: (String, Throwable?) -> String,
    private val categoryFailureWarning: (String, String, Throwable) -> String,
    private val liveCategorySequentialModeWarning: String,
    private val stageChannelItems: suspend (Long, List<Channel>, MutableSet<Long>, FallbackCategoryCollector, Long?) -> StagedCatalogSnapshot,
    private val fallbackStageBatchSize: Int
) {
    suspend fun syncXtreamLiveCatalog(
        provider: Provider,
        api: XtreamProvider,
        existingMetadata: SyncMetadata,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?
    ): CatalogSyncPayload<Channel> {
        Log.i(XTREAM_LIVE_STRATEGY_TAG, "Xtream live strategy start for provider ${provider.id}.")
        val rawLiveCategories = when (val attempt = xtreamSupport.attemptNonCancellation {
            xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.LIGHTWEIGHT) {
                    xtreamCatalogApiService.getLiveCategories(
                        XtreamUrlFactory.buildPlayerApiUrl(
                            serverUrl = provider.serverUrl,
                            username = provider.username,
                            password = provider.password,
                            action = "get_live_categories"
                        )
                    )
                }
            }
        }) {
            is Attempt.Success -> attempt.value
            is Attempt.Failure -> {
                Log.w(
                    XTREAM_LIVE_STRATEGY_TAG,
                    "Xtream live categories request failed for provider ${provider.id}: ${sanitizeThrowableMessage(attempt.error)}"
                )
                null
            }
        }
        val resolvedCategories = rawLiveCategories
            ?.let { categories -> api.mapCategories(ContentType.LIVE, categories) }
            ?.map { category -> category.toEntity(provider.id) }
            ?.takeIf { it.isNotEmpty() }
        val filteredRawLiveCategories = rawLiveCategories.orEmpty().filterNot { category ->
            category.categoryId.toLongOrNull() in hiddenLiveCategoryIds
        }
        val visibleResolvedCategories = resolvedCategories
            ?.filterNot { category -> category.categoryId in hiddenLiveCategoryIds }
            ?.takeIf { it.isNotEmpty() }

        var fullPayload = CatalogSyncPayload<Channel>(
            catalogResult = CatalogStrategyResult.EmptyValid("full"),
            categories = null
        )
        if (hiddenLiveCategoryIds.isEmpty()) {
            progress(provider.id, onProgress, "Downloading Live TV...")
            fullPayload = loadXtreamLiveFull(provider, api)
            when (val fullResult = fullPayload.catalogResult) {
                is CatalogStrategyResult.Success -> return fullPayload.copy(
                    categories = catalogStrategySupport.mergePreferredAndFallbackCategories(
                        visibleResolvedCategories,
                        fullPayload.categories ?: catalogStrategySupport.buildFallbackLiveCategories(provider.id, fullResult.items)
                    ),
                    warnings = emptyList(),
                    strategyFeedback = XtreamStrategyFeedback(
                        attemptedFullCatalog = true,
                        fullCatalogUnsafe = false
                    )
                )
                is CatalogStrategyResult.Partial -> return fullPayload.copy(
                    categories = catalogStrategySupport.mergePreferredAndFallbackCategories(
                        visibleResolvedCategories,
                        fullPayload.categories ?: catalogStrategySupport.buildFallbackLiveCategories(provider.id, fullResult.items)
                    ),
                    warnings = emptyList(),
                    strategyFeedback = XtreamStrategyFeedback(
                        attemptedFullCatalog = true,
                        fullCatalogUnsafe = false
                    )
                )
                else -> Unit
            }
        } else {
            Log.i(
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live full catalog skipped for provider ${provider.id} because ${hiddenLiveCategoryIds.size} live categories are hidden."
            )
        }

        progress(provider.id, onProgress, "Downloading Live TV by category...")
        val categoryResult = loadXtreamLiveByCategory(
            provider = provider,
            api = api,
            rawCategories = filteredRawLiveCategories,
            onProgress = onProgress,
            preferSequential = existingMetadata.liveSequentialFailuresRemembered
        )
        return CatalogSyncPayload(
            catalogResult = categoryResult,
            categories = when (categoryResult) {
                is CatalogStrategyResult.Success -> catalogStrategySupport.mergePreferredAndFallbackCategories(
                    visibleResolvedCategories,
                    catalogStrategySupport.buildFallbackLiveCategories(provider.id, categoryResult.items)
                )
                is CatalogStrategyResult.Partial -> catalogStrategySupport.mergePreferredAndFallbackCategories(
                    visibleResolvedCategories,
                    catalogStrategySupport.buildFallbackLiveCategories(provider.id, categoryResult.items)
                )
                else -> null
            },
            warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult),
            strategyFeedback = XtreamStrategyFeedback(
                attemptedFullCatalog = true,
                fullCatalogUnsafe = (fullPayload.catalogResult as? CatalogStrategyResult.Failure)?.error?.let(catalogStrategySupport::shouldAvoidFullCatalogStrategy) == true,
                segmentedStressDetected = catalogStrategySupport.sawSegmentedStress(
                    warnings = catalogStrategySupport.strategyWarnings(fullPayload.catalogResult),
                    result = categoryResult,
                    sequentialWarnings = setOf(liveCategorySequentialModeWarning)
                )
            )
        )
    }

    suspend fun loadXtreamLiveFull(
        provider: Provider,
        api: XtreamProvider
    ): CatalogSyncPayload<Channel> {
        val fallbackCollector = FallbackCategoryCollector(provider.id, ContentType.LIVE)
        val seenStreamIds = HashSet<Long>()
        val rawBatch = ArrayList<XtreamStream>(fallbackStageBatchSize)
        var stagedSessionId: Long? = null
        var acceptedCount = 0
        var streamedRawCount = 0
        var fullChannelsFailure: Throwable? = null

        suspend fun flushRawBatch() {
            if (rawBatch.isEmpty()) return
            val staged = stageChannelItems(
                provider.id,
                api.mapLiveStreamsSequence(rawBatch.asSequence()).toList(),
                seenStreamIds,
                fallbackCollector,
                stagedSessionId
            )
            stagedSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            rawBatch.clear()
        }

        val fullChannelsElapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                    xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.HEAVY) {
                        xtreamCatalogHttpService.streamLiveStreams(
                            XtreamUrlFactory.buildPlayerApiUrl(
                                serverUrl = provider.serverUrl,
                                username = provider.username,
                                password = provider.password,
                                action = "get_live_streams"
                            )
                        ) { stream ->
                            rawBatch += stream
                            streamedRawCount++
                            if (rawBatch.size >= fallbackStageBatchSize) {
                                flushRawBatch()
                            }
                        }.also {
                            flushRawBatch()
                        }
                    }
                }
            }) {
                is Attempt.Success -> Unit
                is Attempt.Failure -> {
                    fullChannelsFailure = attempt.error
                    stagedSessionId?.let { sessionId ->
                        syncCatalogStore.discardStagedImport(provider.id, sessionId)
                        stagedSessionId = null
                    }
                }
            }
        }

        if (streamedRawCount > 0) {
            Log.i(
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live full catalog succeeded for provider ${provider.id} in ${fullChannelsElapsedMs}ms " +
                    "with $acceptedCount accepted items from $streamedRawCount raw items."
            )
            if (acceptedCount == 0) {
                return CatalogSyncPayload(
                    catalogResult = CatalogStrategyResult.EmptyValid(
                        strategyName = "full",
                        warnings = listOf("Live full catalog returned raw items but none were usable after mapping.")
                    ),
                    categories = null
                )
            }
            return CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Success(
                    strategyName = "full",
                    items = emptyList()
                ),
                categories = fallbackCollector.entities().takeIf { it.isNotEmpty() },
                stagedSessionId = stagedSessionId,
                stagedAcceptedCount = acceptedCount
            )
        }
        return if (fullChannelsFailure != null) {
            xtreamSupport.logXtreamCatalogFallback(
                provider = provider,
                section = "live",
                stage = "full catalog",
                elapsedMs = fullChannelsElapsedMs,
                itemCount = streamedRawCount,
                error = fullChannelsFailure,
                nextStep = "category-bulk"
            )
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.Failure(
                    strategyName = "full",
                    error = fullChannelsFailure!!,
                    warnings = listOf(fullCatalogFallbackWarning("Live TV", fullChannelsFailure))
                ),
                categories = null
            )
        } else {
            xtreamSupport.logXtreamCatalogFallback(
                provider = provider,
                section = "live",
                stage = "full catalog",
                elapsedMs = fullChannelsElapsedMs,
                itemCount = streamedRawCount,
                error = null,
                nextStep = "category-bulk"
            )
            CatalogSyncPayload(
                catalogResult = CatalogStrategyResult.EmptyValid(
                    strategyName = "full",
                    warnings = listOf("Live full catalog returned an empty valid result.")
                ),
                categories = null
            )
        }
    }

    suspend fun loadXtreamLiveByCategory(
        provider: Provider,
        api: XtreamProvider,
        rawCategories: List<XtreamCategory>,
        onProgress: ((String) -> Unit)?,
        preferSequential: Boolean
    ): CatalogStrategyResult<Channel> {
        val categories = rawCategories.filter { it.categoryId.isNotBlank() }
        if (categories.isEmpty()) {
            return CatalogStrategyResult.Failure(
                strategyName = "category_bulk",
                error = IllegalStateException("No live categories available"),
                warnings = listOf("Live category-bulk strategy was unavailable because no categories were returned.")
            )
        }

        val concurrency = xtreamAdaptiveSyncPolicy.concurrencyFor(
            providerId = provider.id,
            workloadSize = categories.size,
            preferSequential = preferSequential,
            stage = XtreamAdaptiveSyncPolicy.Stage.CATEGORY
        )
        progress(provider.id, onProgress, "Downloading Live TV by category 0/${categories.size}...")

        val executionPlan = xtreamSupport.executeCategoryRecoveryPlan(
            provider = provider,
            categories = categories,
            initialConcurrency = concurrency,
            sectionLabel = "Live TV",
            sequentialModeWarning = liveCategorySequentialModeWarning,
            onProgress = onProgress,
            fetch = { category -> xtreamFetcher.fetchLiveCategoryOutcome(provider, api, category) }
        )
        var timedOutcomes = executionPlan.outcomes

        val categoryOutcomes = timedOutcomes.map { it.outcome }
        val failureCount = timedOutcomes.count { it.outcome is CategoryFetchOutcome.Failure }
        val fastFailureCount = timedOutcomes.count {
            it.elapsedMs <= 5_000L && it.outcome is CategoryFetchOutcome.Failure
        }
        val downgradeRecommended = catalogStrategySupport.shouldDowngradeCategorySync(
            categories.size,
            failureCount,
            fastFailureCount,
            categoryOutcomes
        )
        var fallbackWarnings = executionPlan.warnings
        if (concurrency > 1 && catalogStrategySupport.shouldRetryFailedCategories(
                categories.size,
                failureCount,
                downgradeRecommended,
                categoryOutcomes
            )
        ) {
            Log.w(
                XTREAM_LIVE_STRATEGY_TAG,
                "Xtream live category sync is continuing in sequential mode for failed categories on provider ${provider.id}."
            )
            timedOutcomes = xtreamSupport.continueFailedCategoryOutcomes(
                provider = provider,
                timedOutcomes = timedOutcomes,
                fetchSequentially = { category -> xtreamFetcher.fetchLiveCategoryOutcome(provider, api, category) }
            )
            fallbackWarnings = (fallbackWarnings + if (downgradeRecommended) listOf(liveCategorySequentialModeWarning) else emptyList()).distinct()
        }

        val finalOutcomes = timedOutcomes.map { it.outcome }
        val warnings = finalOutcomes
            .filterIsInstance<CategoryFetchOutcome.Failure>()
            .map { failure -> categoryFailureWarning("Live TV", failure.categoryName, failure.error) } +
            fallbackWarnings

        val channels = finalOutcomes
            .asSequence()
            .filterIsInstance<CategoryFetchOutcome.Success<Channel>>()
            .flatMap { it.items.asSequence() }
            .filter { it.streamId > 0L }
            .associateBy { it.streamId }
            .values
            .toList()
        val failedCategories = finalOutcomes.count { it is CategoryFetchOutcome.Failure }
        val emptyCategories = finalOutcomes.count { it is CategoryFetchOutcome.Empty }
        val successfulCategories = finalOutcomes.count { it is CategoryFetchOutcome.Success }
        Log.i(
            XTREAM_LIVE_STRATEGY_TAG,
            "Xtream live category strategy summary for provider ${provider.id}: successful=$successfulCategories empty=$emptyCategories failed=$failedCategories dedupedChannels=${channels.size} concurrency=$concurrency"
        )

        return when {
            channels.isNotEmpty() && failedCategories == 0 -> CatalogStrategyResult.Success("category_bulk", channels, warnings.toList())
            channels.isNotEmpty() -> CatalogStrategyResult.Partial("category_bulk", channels, warnings.toList())
            failedCategories > 0 -> CatalogStrategyResult.Failure(
                strategyName = "category_bulk",
                error = IllegalStateException("Live category-bulk sync failed for all usable categories"),
                warnings = warnings.toList()
            )
            else -> CatalogStrategyResult.EmptyValid(
                strategyName = "category_bulk",
                warnings = listOf("All live categories returned valid empty results.")
            )
        }
    }
}
