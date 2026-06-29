package com.streamvault.app.plugins

/**
 * StepDaddy Gateway cross-app wake contract used by [GatewayLifecycleManager].
 *
 * G Pad ships `com.thothassistant.stepdaddy.gateway.debug` listening on port 3000.
 */
internal object GatewayConstants {
    const val DEFAULT_GATEWAY_BASE = "http://127.0.0.1:3000"
    const val DEFAULT_GATEWAY_PORT = 3000

    const val GATEWAY_PACKAGE_RELEASE = "com.thothassistant.stepdaddy.gateway"
    const val GATEWAY_PACKAGE_DEBUG = "$GATEWAY_PACKAGE_RELEASE.debug"

    const val GATEWAY_SERVICE_CLASS = "$GATEWAY_PACKAGE_RELEASE.ServerService"
    const val GATEWAY_MAIN_CLASS = "$GATEWAY_PACKAGE_RELEASE.ui.MainActivity"

    /** Matches [ServerService] intent-filter in the gateway manifest. */
    const val ACTION_START = "com.thothassistant.stepdaddy.gateway.action.START"
    const val ACTION_ENSURE_READY = "com.thothassistant.stepdaddy.gateway.action.ENSURE_READY"

    const val STEP_DADDY_PLUGIN_ID = "com.thothassistant.stepdaddy.gateway.streamvault"

    val GATEWAY_PACKAGES = listOf(GATEWAY_PACKAGE_DEBUG, GATEWAY_PACKAGE_RELEASE)

    /** Path prefixes served only by StepDaddy Gateway loopback HTTP. */
    val GATEWAY_PATH_PREFIXES = listOf(
        "/tivimate-stream/",
        "/ntv-stream/",
        "/stream/",
        "/streamvault.m3u",
        "/streamvault.m3u8",
        "/tivimate.m3u",
        "/tivimate.m3u8",
        "/vlc.m3u",
        "/vlc.m3u8",
        "/streamvault-setup-playlist",
        "/streamvault-playlist",
        "/tivimate-setup-playlist",
        "/tivimate-playlist",
        "/dlhd-event-stream/",
        "/dlhd-event-guide/",
        "/epg.xml",
        "/health",
        "/tivimate-setup",
    )
}
