package com.streamvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit

class XtreamIndexWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface XtreamIndexWorkerEntryPoint {
        fun providerDao(): ProviderDao
        fun syncManager(): SyncManager
    }

    override suspend fun doWork(): Result {
        if (applicationContext.isCurrentlyLowOnMemoryForSync()) {
            Log.w(TAG, "Deferring Xtream index work: device low on memory")
            return Result.retry()
        }

        val force = inputData.getBoolean(KEY_FORCE, false)
        val requestedProviderId = inputData.getLong(KEY_PROVIDER_ID, INVALID_PROVIDER_ID)
        val requestedSection = inputData.getString(KEY_SECTION)?.toContentTypeOrNull()

        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                XtreamIndexWorkerEntryPoint::class.java
            )
            val providers = if (requestedProviderId > 0L) {
                entryPoint.providerDao().getById(requestedProviderId)?.let(::listOf).orEmpty()
            } else {
                entryPoint.providerDao().getAllSync()
                    .filter { provider -> provider.isActive && provider.type == ProviderType.XTREAM_CODES }
            }

            var sawRetryableFailure = false
            providers
                .filter { provider -> provider.type == ProviderType.XTREAM_CODES }
                .forEach { provider ->
                    when (val result = entryPoint.syncManager().processQueuedXtreamIndexJobs(
                        providerId = provider.id,
                        section = requestedSection,
                        force = force,
                        maxCategoriesPerSection = CATEGORY_SLICE_SIZE
                    )) {
                        is com.streamvault.domain.model.Result.Error -> {
                            Log.w(TAG, "Xtream index worker failed for provider ${provider.id}: ${result.message}")
                            if (shouldRetry(result.exception)) {
                                sawRetryableFailure = true
                            }
                        }
                        else -> Unit
                    }
                }

            if (sawRetryableFailure) Result.retry() else Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Xtream index worker failed", error)
            if (shouldRetry(error)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable?): Boolean {
        return when (error) {
            is IOException -> true
            is SQLiteException -> error.message.orEmpty().contains("locked", ignoreCase = true) ||
                error.message.orEmpty().contains("busy", ignoreCase = true)
            else -> false
        }
    }

    companion object {
        private const val TAG = "XtreamIndexWorker"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_SECTION = "section"
        private const val KEY_FORCE = "force"
        private const val INVALID_PROVIDER_ID = -1L
        private const val CATEGORY_SLICE_SIZE = 2
        private const val UNIQUE_WORK_PREFIX = "xtream-index-worker-"
        private const val UNIQUE_PERIODIC_WORK_NAME = "xtream-index-periodic-worker"

        fun enqueue(
            context: Context,
            providerId: Long,
            section: String? = null,
            force: Boolean = false,
            initialDelaySeconds: Long = 0L
        ) {
            if (providerId <= 0L) return
            val request = OneTimeWorkRequestBuilder<XtreamIndexWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_PROVIDER_ID, providerId)
                        .putBoolean(KEY_FORCE, force)
                        .also { builder ->
                            section?.let { builder.putString(KEY_SECTION, it) }
                        }
                        .build()
                )
                .setConstraints(defaultConstraints())
                .setInitialDelay(initialDelaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(providerId, section),
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        fun enqueueLaunchStaleCheck(context: Context) {
            val request = OneTimeWorkRequestBuilder<XtreamIndexWorker>()
                .setConstraints(defaultConstraints())
                .setInitialDelay(20, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_WORK_PREFIX-launch-stale-check",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<XtreamIndexWorker>(6, TimeUnit.HOURS)
                .setConstraints(defaultConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun defaultConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        private fun uniqueWorkName(providerId: Long, section: String?): String =
            "$UNIQUE_WORK_PREFIX$providerId-${section.orEmpty()}"

        private fun String.toContentTypeOrNull(): ContentType? =
            runCatching { ContentType.valueOf(this) }.getOrNull()
    }
}
