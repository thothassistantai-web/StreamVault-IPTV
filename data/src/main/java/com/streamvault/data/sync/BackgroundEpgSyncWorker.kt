package com.streamvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class BackgroundEpgSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackgroundEpgSyncWorkerEntryPoint {
        fun syncManager(): SyncManager
    }

    override suspend fun doWork(): Result {
        val providerId = inputData.getLong(KEY_PROVIDER_ID, INVALID_PROVIDER_ID)
        if (providerId == INVALID_PROVIDER_ID) {
            return Result.failure()
        }

        // Defer the run when the device is currently under memory pressure. The streamed
        // Stalker EPG path is heap-frugal but the surrounding sync work (channel inserts,
        // EPG resolution) can still allocate; retrying later avoids piling onto a stressed
        // system. WorkManager will re-enqueue with backoff.
        if (applicationContext.isCurrentlyLowOnMemoryForSync()) {
            Log.w(TAG, "Deferring background EPG sync for provider $providerId: device low on memory")
            return Result.retry()
        }

        val force = inputData.getBoolean(KEY_FORCE_REFRESH, false)

        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                BackgroundEpgSyncWorkerEntryPoint::class.java
            )
            when (val result = entryPoint.syncManager().syncEpg(providerId, force = force)) {
                is com.streamvault.domain.model.Result.Success -> {
                    // A successful EPG sync may still have transient partial failures
                    // (e.g. network hiccup on one provider section). Check the published
                    // sync state and retry if it signals a retryable EPG failure so
                    // WorkManager backoff can heal it without manual intervention.
                    val syncState = entryPoint.syncManager().currentSyncState(providerId)
                    if (syncState is com.streamvault.domain.model.SyncState.Partial &&
                            syncState.hasRetryableEpgFailure) {
                        Log.i(TAG, "Scheduling retry for provider $providerId: EPG completed with retryable failure")
                        Result.retry()
                    } else {
                        Result.success()
                    }
                }
                is com.streamvault.domain.model.Result.Error -> {
                    if (result.message.contains("not found", ignoreCase = true)) {
                        Result.success()
                    } else if (shouldRetry(result.exception)) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                com.streamvault.domain.model.Result.Loading -> Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background EPG work failed for provider $providerId", e)
            if (shouldRetry(e)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable?): Boolean {
        return when (error) {
            is java.io.IOException -> true
            is SQLiteException -> error.message.orEmpty().contains("locked", ignoreCase = true) ||
                error.message.orEmpty().contains("busy", ignoreCase = true)
            else -> false
        }
    }

    companion object {
        private const val TAG = "BackgroundEpgWorker"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_FORCE_REFRESH = "force_refresh"
        private const val INVALID_PROVIDER_ID = -1L
        private const val UNIQUE_WORK_PREFIX = "background-epg-sync-"
        /**
         * Default delay before the first background EPG sync runs after enqueue. This
         * replaces the in-process [kotlinx.coroutines.delay] previously used by the
         * provider repository, which kept a coroutine alive for the duration of the wait.
         */
        private const val DEFAULT_INITIAL_DELAY_SECONDS = 30L

        fun enqueue(
            context: Context,
            providerId: Long,
            force: Boolean = false,
            initialDelaySeconds: Long = DEFAULT_INITIAL_DELAY_SECONDS
        ) {
            if (providerId <= 0L) return
            val request = OneTimeWorkRequestBuilder<BackgroundEpgSyncWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_PROVIDER_ID, providerId)
                        .putBoolean(KEY_FORCE_REFRESH, force)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(initialDelaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(providerId),
                // Force-refresh requests must displace any queued stale work so the new
                // parameters (force=true) actually run; KEEP would silently drop them.
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, providerId: Long) {
            if (providerId <= 0L) return
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(providerId))
            }.onFailure { error ->
                Log.w(TAG, "Skipping background EPG cancellation for provider $providerId", error)
            }
        }

        private fun uniqueWorkName(providerId: Long): String = "$UNIQUE_WORK_PREFIX$providerId"
    }
}
