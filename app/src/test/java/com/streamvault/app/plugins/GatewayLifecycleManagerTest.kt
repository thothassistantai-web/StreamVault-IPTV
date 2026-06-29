package com.streamvault.app.plugins

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GatewayLifecycleManagerTest {
    private lateinit var context: Context
    private lateinit var manager: GatewayLifecycleManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = GatewayLifecycleManager(
            context = context,
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            messengerClient = PluginMessengerClient(context),
        )
    }

    @Test
    fun `isGatewayManagedUrl detects loopback stream proxy`() {
        assertTrue(
            manager.isGatewayManagedUrl("http://127.0.0.1:3000/tivimate-stream/espn.m3u8"),
        )
        assertTrue(
            manager.isGatewayManagedUrl("http://127.0.0.1:3000/ntv-stream/abc123.m3u8"),
        )
        assertTrue(
            manager.isGatewayManagedUrl("http://127.0.0.1:3000/streamvault-setup-playlist.m3u8"),
        )
        assertFalse(manager.isGatewayManagedUrl("https://example.com/live/stream.m3u8"))
    }

    @Test
    fun `resolveGatewayBase normalizes loopback port`() {
        assertEquals(
            "http://127.0.0.1:3000",
            manager.resolveGatewayBase("http://127.0.0.1:3000/tivimate-stream/id.m3u8"),
        )
    }

    @Test
    fun `markRecoveryTransition reports offline to online`() {
        manager.markRecoveryTransition(ready = false)
        assertFalse(manager.markRecoveryTransition(ready = false))
        assertTrue(manager.markRecoveryTransition(ready = true))
        assertFalse(manager.markRecoveryTransition(ready = true))
    }

    @Test
    fun `wasLastKnownReady supports playback fast path contract`() {
        manager.markRecoveryTransition(ready = true)
        assertTrue(manager.wasLastKnownReady())
        manager.markRecoveryTransition(ready = false)
        assertFalse(manager.wasLastKnownReady())
    }

    @Test
    fun `probeHealth TTL constant is defined for playback cache`() {
        assertTrue(GatewayLifecycleManager.HEALTH_PROBE_TTL_MS > 0L)
        assertEquals(2_000L, GatewayLifecycleManager.HEALTH_PROBE_TTL_MS)
    }

    @Test
    fun `isHealthCacheValid reflects cached probe age`() {
        manager.cacheHealthSnapshotForTest(
            GatewayLifecycleManager.HealthSnapshot(
                healthOk = true,
                starting = false,
                channelCount = 120,
                ready = true,
            ),
        )
        assertTrue(manager.isHealthCacheValid())
        assertTrue((manager.cachedHealthAgeMs() ?: Long.MAX_VALUE) < GatewayLifecycleManager.HEALTH_PROBE_TTL_MS)
    }

    @Test
    fun `isStepDaddyGatewayPlugin matches embedded gateway plugin id`() {
        val plugin = InstalledStreamVaultPlugin(
            packageName = GatewayConstants.GATEWAY_PACKAGE_DEBUG,
            serviceClassName = "com.thothassistant.stepdaddy.gateway.streamvault.StreamVaultPluginService",
            appLabel = "StepDaddy Gateway",
            manifest = StreamVaultPluginManifest(
                id = GatewayConstants.STEP_DADDY_PLUGIN_ID,
                name = "StepDaddy Gateway",
            ),
            enabled = true,
        )
        assertTrue(manager.isStepDaddyGatewayPlugin(plugin))
    }
}
