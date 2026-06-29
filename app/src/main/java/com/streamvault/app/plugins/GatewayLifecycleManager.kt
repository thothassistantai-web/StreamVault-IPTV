package com.streamvault.app.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Coordinates StepDaddy Gateway auto-start, health probes, and crash recovery for StreamVault.
 *
 * Before M3U sync, playback, or plugin health checks, callers invoke [ensureGatewayReady].
 * On failure the manager wakes the gateway [ServerService] (START action) and falls back to
 * [MainActivity], then polls `/health?lite=1` with exponential backoff until the catalog is
 * stable — mirroring TiviMate's `StepDaddyGateway.ensureReady` and gateway `GatewayStartHelper`.
 */
@Singleton
class GatewayLifecycleManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val messengerClient: PluginMessengerClient,
) {
    @Volatile
    private var lastKnownReady: Boolean = false

    @Volatile
    private var cachedHealthSnapshot: HealthSnapshot? = null

    @Volatile
    private var cachedHealthAtElapsedMs: Long = 0L

    data class HealthSnapshot(
        val healthOk: Boolean,
        val starting: Boolean,
        val channelCount: Int,
        val ready: Boolean,
    )

    sealed class EnsureResult {
        data class Ready(val channelCount: Int) : EnsureResult()
        data class Failed(val message: String) : EnsureResult()

        val success: Boolean get() = this is Ready
    }

    fun isStepDaddyGatewayPlugin(plugin: InstalledStreamVaultPlugin): Boolean =
        plugin.manifest.id == GatewayConstants.STEP_DADDY_PLUGIN_ID ||
            plugin.packageName.startsWith(GatewayConstants.GATEWAY_PACKAGE_RELEASE)

    /**
     * True when [url] targets the local StepDaddy Gateway HTTP surface (sync, playback, EPG).
     */
    fun isGatewayManagedUrl(url: String): Boolean {
        val normalized = url.substringBefore('|').trim()
        if (normalized.isBlank()) return false
        return resolveGatewayBase(normalized) != null
    }

    fun resolveGatewayBase(url: String): String? {
        val normalized = url.substringBefore('|').trim()
        if (normalized.isBlank()) return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("http", ignoreCase = true) -> 80
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> -1
        }
        val path = uri.path.orEmpty()
        val loopbackHost = host == "127.0.0.1" || host == "localhost" || host == "::1"
        val gatewayPort = port == GatewayConstants.DEFAULT_GATEWAY_PORT
        val knownPath = GatewayConstants.GATEWAY_PATH_PREFIXES.any { prefix ->
            path.startsWith(prefix) || path == prefix.trimEnd('/')
        }
        return when {
            loopbackHost && gatewayPort -> "${uri.scheme}://$host:${GatewayConstants.DEFAULT_GATEWAY_PORT}"
            loopbackHost && knownPath -> "${uri.scheme}://$host:${GatewayConstants.DEFAULT_GATEWAY_PORT}"
            knownPath && gatewayPort -> "${uri.scheme}://$host:$port"
            else -> null
        }
    }

    suspend fun resolveBaseForPlugin(plugin: InstalledStreamVaultPlugin): String {
        val values = runCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_GET_CONFIGURATION_VALUES,
                timeoutMillis = 5_000L,
            )
        }.getOrNull()
        val valuesJson = values
            ?.getString(StreamVaultPluginContract.KEY_CONFIGURATION_VALUES_JSON)
            .orEmpty()
        if (valuesJson.isNotBlank()) {
            runCatching {
                val payload = json.decodeFromString<JsonObject>(valuesJson)
                val configured = payload[StreamVaultPluginContract.CONFIG_KEY_GATEWAY_BASE]
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()
                    ?.trimEnd('/')
                if (!configured.isNullOrBlank()) {
                    return configured
                }
            }
        }
        return GatewayConstants.DEFAULT_GATEWAY_BASE
    }

    suspend fun probeHealth(baseUrl: String, allowCache: Boolean = true): HealthSnapshot = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().trimEnd('/')
        if (allowCache) {
            val cached = cachedHealthSnapshot
            val ageMs = SystemClock.elapsedRealtime() - cachedHealthAtElapsedMs
            if (cached != null && ageMs in 0 until HEALTH_PROBE_TTL_MS) {
                Log.d(TAG, "probeHealth cache hit ageMs=$ageMs channels=${cached.channelCount}")
                return@withContext cached
            }
        }
        val request = Request.Builder()
            .url("$normalized/health?lite=1")
            .get()
            .build()
        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext HealthSnapshot(
                        healthOk = false,
                        starting = true,
                        channelCount = 0,
                        ready = false,
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext HealthSnapshot(
                        healthOk = false,
                        starting = true,
                        channelCount = 0,
                        ready = false,
                    )
                }
                val payload = json.decodeFromString<JsonObject>(body)
                val ok = payload["ok"]?.jsonPrimitive?.booleanOrNull == true
                val starting = payload["starting"]?.jsonPrimitive?.booleanOrNull == true
                val channels = payload["channels"]?.jsonPrimitive?.intOrNull ?: 0
                val supplement = payload["supplementChannels"]?.jsonPrimitive?.intOrNull ?: 0
                val total = channels + supplement
                val ready = ok && !starting && total > 0
                val snapshot = HealthSnapshot(
                    healthOk = ok,
                    starting = starting || total <= 0,
                    channelCount = total,
                    ready = ready,
                )
                cachedHealthSnapshot = snapshot
                cachedHealthAtElapsedMs = SystemClock.elapsedRealtime()
                snapshot
            }
        }.getOrElse {
            HealthSnapshot(
                healthOk = false,
                starting = true,
                channelCount = 0,
                ready = false,
            )
        }
    }

    /**
     * Wake gateway FGS via START action; fall back to MainActivity when FGS start is blocked.
     */
    suspend fun wakeGateway(source: String): Boolean = withContext(Dispatchers.IO) {
        for (packageName in GatewayConstants.GATEWAY_PACKAGES) {
            if (!isPackageInstalled(packageName)) continue
            val serviceIntent = Intent(GatewayConstants.ACTION_START).apply {
                setClassName(packageName, GatewayConstants.GATEWAY_SERVICE_CLASS)
            }
            runCatching {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.i(TAG, "Started gateway FGS ($source): $packageName")
                return@withContext true
            }.onFailure { error ->
                Log.w(TAG, "FGS start failed for $packageName ($source): ${error.message}")
            }
            runCatching {
                context.startService(
                    serviceIntent.apply { action = GatewayConstants.ACTION_ENSURE_READY },
                )
                Log.i(TAG, "Nudged gateway service ($source): $packageName")
                return@withContext true
            }.onFailure { error ->
                Log.w(TAG, "Service nudge failed for $packageName ($source): ${error.message}")
            }
            val activityIntent = Intent().apply {
                setClassName(packageName, GatewayConstants.GATEWAY_MAIN_CLASS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(activityIntent)
                Log.i(TAG, "Launched gateway MainActivity ($source): $packageName")
                return@withContext true
            }.onFailure { error ->
                Log.w(TAG, "MainActivity launch failed for $packageName ($source): ${error.message}")
            }
        }
        false
    }

    /**
     * HTTP probe, cross-app wake, optional in-process plugin nudge, then poll until ready.
     */
    suspend fun ensureGatewayReady(
        baseUrl: String = GatewayConstants.DEFAULT_GATEWAY_BASE,
        source: String,
        timeoutMs: Long? = null,
        pollMs: Long? = null,
        requireCatalog: Boolean = true,
        gatewayPlugin: InstalledStreamVaultPlugin? = null,
    ): EnsureResult {
        val normalized = baseUrl.trim().trimEnd('/')
        val effectiveTimeoutMs = timeoutMs ?: if (requireCatalog) {
            DEFAULT_READY_TIMEOUT_MS
        } else {
            PLAYBACK_READY_TIMEOUT_MS
        }
        val effectivePollMs = pollMs ?: if (requireCatalog) DEFAULT_POLL_MS else PLAYBACK_POLL_MS
        val stableProbeTarget = if (requireCatalog) STABLE_READY_PROBES else PLAYBACK_STABLE_READY_PROBES
        val deadline = SystemClock.elapsedRealtime() + effectiveTimeoutMs
        var stableHits = 0
        var lastChannelCount = -1
        var wakeAttempts = 0
        var pluginNudged = false

        Log.i(
            TAG,
            "ensureGatewayReady ($source) base=$normalized catalog=$requireCatalog " +
                "timeoutMs=$effectiveTimeoutMs pollMs=$effectivePollMs",
        )

        // Playback only needs HTTP — skip catalog stability waits when gateway was recently healthy.
        if (!requireCatalog && wasLastKnownReady()) {
            val snapshot = probeHealth(normalized, allowCache = true)
            if (snapshot.healthOk) {
                markRecoveryTransition(ready = true)
                Log.i(TAG, "ensureGatewayReady ($source): fast-path ready channels=${snapshot.channelCount}")
                return EnsureResult.Ready(snapshot.channelCount)
            }
        }

        // Skip redundant /health round-trips during rapid channel zaps.
        if (!requireCatalog) {
            val cached = cachedHealthSnapshot
            val ageMs = SystemClock.elapsedRealtime() - cachedHealthAtElapsedMs
            if (cached?.healthOk == true && ageMs in 0 until HEALTH_PROBE_TTL_MS) {
                markRecoveryTransition(ready = true)
                Log.i(
                    TAG,
                    "ensureGatewayReady ($source): ttl-cache ready channels=${cached.channelCount} ageMs=$ageMs",
                )
                return EnsureResult.Ready(cached.channelCount)
            }
        }

        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = probeHealth(normalized)
            val satisfied = if (requireCatalog) snapshot.ready else snapshot.healthOk

            if (satisfied) {
                if (!requireCatalog || snapshot.channelCount == lastChannelCount) {
                    stableHits++
                } else {
                    stableHits = 1
                    lastChannelCount = snapshot.channelCount
                }
                if (!requireCatalog || stableHits >= stableProbeTarget) {
                    val recovered = markRecoveryTransition(ready = true)
                    Log.i(
                        TAG,
                        "ensureGatewayReady ($source): ready channels=${snapshot.channelCount} recovered=$recovered",
                    )
                    return EnsureResult.Ready(snapshot.channelCount)
                }
            } else {
                stableHits = 0
                lastChannelCount = -1
                if (!snapshot.healthOk && wakeAttempts < MAX_WAKE_ATTEMPTS) {
                    wakeGateway("$source-wake-$wakeAttempts")
                    wakeAttempts++
                }
                if (!pluginNudged && gatewayPlugin != null) {
                    nudgeGatewayPlugin(gatewayPlugin, source)
                    pluginNudged = true
                }
            }
            delay(effectivePollMs)
        }

        markRecoveryTransition(ready = false)
        Log.w(TAG, "ensureGatewayReady ($source): timed out after ${effectiveTimeoutMs}ms")
        return EnsureResult.Failed("StepDaddy Gateway did not become ready — start the gateway app and retry")
    }

    /** Returns true when gateway transitioned from offline to online since the last probe. */
    fun markRecoveryTransition(ready: Boolean): Boolean {
        val recovered = ready && !lastKnownReady
        lastKnownReady = ready
        return recovered
    }

    fun wasLastKnownReady(): Boolean = lastKnownReady

    internal fun cachedHealthAgeMs(): Long? {
        val cached = cachedHealthSnapshot ?: return null
        if (cachedHealthAtElapsedMs <= 0L) return null
        return SystemClock.elapsedRealtime() - cachedHealthAtElapsedMs
    }

    internal fun isHealthCacheValid(): Boolean {
        val ageMs = cachedHealthAgeMs() ?: return false
        return ageMs in 0 until HEALTH_PROBE_TTL_MS
    }

    internal fun cacheHealthSnapshotForTest(snapshot: HealthSnapshot) {
        cachedHealthSnapshot = snapshot
        cachedHealthAtElapsedMs = SystemClock.elapsedRealtime()
    }

    private suspend fun nudgeGatewayPlugin(plugin: InstalledStreamVaultPlugin, source: String) {
        runCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_ENSURE_GATEWAY,
                timeoutMillis = DEFAULT_READY_TIMEOUT_MS + 10_000L,
            )
            Log.i(TAG, "Plugin ensure-gateway nudge sent ($source)")
        }.onFailure { error ->
            Log.w(TAG, "Plugin ensure-gateway nudge failed ($source): ${error.message}")
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    companion object {
        private const val TAG = "GatewayLifecycle"
        const val DEFAULT_READY_TIMEOUT_MS = 120_000L
        const val PLAYBACK_READY_TIMEOUT_MS = 15_000L
        private const val DEFAULT_POLL_MS = 2_000L
        const val PLAYBACK_POLL_MS = 250L
        private const val STABLE_READY_PROBES = 2
        private const val PLAYBACK_STABLE_READY_PROBES = 1
        private const val MAX_WAKE_ATTEMPTS = 3
        const val HEALTH_PROBE_TTL_MS = 2_000L
    }
}
