package com.streamvault.app.plugins

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Background watchdog: when the StepDaddy Gateway plugin is enabled, probe loopback health
 * and wake the gateway after crashes. Refreshes the plugin M3U provider when the gateway
 * transitions back to ready.
 */
class GatewayRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GatewayRecoveryEntryPoint {
        fun gatewayLifecycleManager(): GatewayLifecycleManager
        fun pluginManager(): StreamVaultPluginManager
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            GatewayRecoveryEntryPoint::class.java,
        )
        val lifecycle = entryPoint.gatewayLifecycleManager()
        val pluginManager = entryPoint.pluginManager()
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val gatewayPlugins = pluginManager.discoverPlugins()
            .filter { it.enabled && lifecycle.isStepDaddyGatewayPlugin(it) }
        if (gatewayPlugins.isEmpty()) {
            return Result.success()
        }

        val plugin = gatewayPlugins.first()
        val baseUrl = lifecycle.resolveBaseForPlugin(plugin)
        val snapshot = lifecycle.probeHealth(baseUrl)
        val wasReady = prefs.getBoolean(KEY_GATEWAY_READY, false)

        if (!snapshot.ready) {
            Log.i(TAG, "Gateway offline — waking ($baseUrl)")
            lifecycle.wakeGateway("recovery-worker")
            lifecycle.markRecoveryTransition(ready = false)
            prefs.edit().putBoolean(KEY_GATEWAY_READY, false).apply()
            return Result.success()
        }

        val recovered = lifecycle.markRecoveryTransition(ready = true)
        if (!wasReady || recovered) {
            Log.i(TAG, "Gateway online — refreshing plugin playlist")
            pluginManager.refreshEnabledGatewayPlugins { progress ->
                Log.d(TAG, "Recovery sync: $progress")
            }
        }
        prefs.edit().putBoolean(KEY_GATEWAY_READY, true).apply()
        return Result.success()
    }

    companion object {
        private const val TAG = "GatewayRecoveryWorker"
        private const val UNIQUE_WORK = "gateway_recovery_watchdog"
        private const val PREFS_NAME = "streamvault_gateway_recovery"
        private const val KEY_GATEWAY_READY = "gateway_ready"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<GatewayRecoveryWorker>(15, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "Scheduled gateway recovery watchdog (15m)")
        }

        private const val WORK_TAG = "gateway_recovery"
    }
}
