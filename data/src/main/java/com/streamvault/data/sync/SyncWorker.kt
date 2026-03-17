package com.streamvault.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun databaseMaintenanceManager(): DatabaseMaintenanceManager
    }

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Starting data maintenance...")
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncWorkerEntryPoint::class.java)
            val report = entryPoint.databaseMaintenanceManager().runDailyMaintenance()
            Log.d(
                "SyncWorker",
                "Maintenance complete: oldPrograms=${report.deletedPrograms}, orphanEpisodes=${report.deletedOrphanEpisodes}, " +
                    "staleFavorites=${report.deletedStaleFavorites}, vacuumRan=${report.vacuumRan}, " +
                    "dbBytes=${report.statsAfterVacuum.mainDbBytes}, walBytes=${report.statsAfterVacuum.walBytes}, " +
                    "reclaimableBytes=${report.statsAfterVacuum.reclaimableBytes}"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Failed to run data maintenance", e)
            Result.retry()
        }
    }
}
