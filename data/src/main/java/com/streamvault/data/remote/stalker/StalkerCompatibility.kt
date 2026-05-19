package com.streamvault.data.remote.stalker

import com.streamvault.data.util.UrlSecurityPolicy
import java.net.URI

enum class StalkerBootstrapStrategy {
    AUTO,
    MAC_ONLY,
    MAC_WITH_ACCOUNT_INFO,
    MAC_WITH_MODULES
}

enum class StalkerPlaybackMode {
    DIRECT_URL,
    LOCALHOST_CMD,
    PLAY_LIVE_PORTAL,
    PLAY_MOVIE_PORTAL,
    TEMP_LINK_NGINX,
    TEMP_LINK_FLUSSONIC,
    TEMP_LINK_WOWZA,
    MULTI_CMD
}

data class StalkerPortalCapabilities(
    val bootstrapStrategy: StalkerBootstrapStrategy = StalkerBootstrapStrategy.AUTO,
    val useHttpTemporaryLink: Boolean = false,
    val nginxSecureLink: Boolean = false,
    val flussonicTemporaryLink: Boolean = false,
    val wowzaTemporaryLink: Boolean = false,
    val useLoadBalancing: Boolean = false,
    val allowLocalTimeshift: Boolean = false,
    val allowLocalPvr: Boolean = false,
    val allowRemotePvr: Boolean = false,
    val archiveAvailable: Boolean = false,
    val moduleRestricted: Boolean = false,
    val ambiguousAccountState: Boolean = false
) {
    val usesTemporaryLinks: Boolean
        get() = useHttpTemporaryLink || nginxSecureLink || flussonicTemporaryLink || wowzaTemporaryLink
}

data class StalkerCommandVariant(
    val cmd: String,
    val playbackMode: StalkerPlaybackMode,
    val sourceKey: String = "cmd",
    val priority: Int = 0
)

data class StalkerPlaybackDescriptor(
    val primaryMode: StalkerPlaybackMode,
    val candidates: List<StalkerCommandVariant>,
    val capabilities: StalkerPortalCapabilities = StalkerPortalCapabilities()
)

internal fun buildStalkerPlaybackDescriptor(
    primaryCmd: String?,
    alternateCommands: List<Pair<String, String>> = emptyList(),
    capabilities: StalkerPortalCapabilities = StalkerPortalCapabilities()
): StalkerPlaybackDescriptor? {
    val candidates = orderStalkerCommandVariants(buildList {
        primaryCmd?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { cmd ->
                add(
                    StalkerCommandVariant(
                        cmd = cmd,
                        playbackMode = detectStalkerPlaybackMode(cmd, capabilities),
                        sourceKey = "cmd",
                        priority = 0
                    )
                )
            }
        alternateCommands.forEachIndexed { index, (sourceKey, value) ->
            value.trim()
                .takeIf { it.isNotBlank() }
                ?.let { cmd ->
                    add(
                        StalkerCommandVariant(
                            cmd = cmd,
                            playbackMode = detectStalkerPlaybackMode(cmd, capabilities),
                            sourceKey = sourceKey,
                            priority = index + 1
                        )
                        )
                }
        }
    })

    if (candidates.isEmpty()) return null
    return StalkerPlaybackDescriptor(
        primaryMode = if (candidates.size > 1) StalkerPlaybackMode.MULTI_CMD else candidates.first().playbackMode,
        candidates = candidates,
        capabilities = capabilities
    )
}

internal fun detectStalkerPlaybackMode(
    cmd: String,
    capabilities: StalkerPortalCapabilities = StalkerPortalCapabilities()
): StalkerPlaybackMode {
    val directCandidate = cmd.substringAfter(' ', missingDelimiterValue = cmd).trim()
    val uri = runCatching { URI(directCandidate) }.getOrNull()
    val host = uri?.host?.trim()?.lowercase().orEmpty()
    val path = uri?.path?.trim()?.lowercase().orEmpty()

    return when {
        host.isBlank() || host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" ->
            StalkerPlaybackMode.LOCALHOST_CMD

        uri?.isStalkerChannelCommandPath() == true ->
            StalkerPlaybackMode.LOCALHOST_CMD

        path.endsWith("/play/live.php") ->
            StalkerPlaybackMode.PLAY_LIVE_PORTAL

        path.endsWith("/play/movie.php") ->
            StalkerPlaybackMode.PLAY_MOVIE_PORTAL

        capabilities.flussonicTemporaryLink ->
            StalkerPlaybackMode.TEMP_LINK_FLUSSONIC

        capabilities.wowzaTemporaryLink ->
            StalkerPlaybackMode.TEMP_LINK_WOWZA

        capabilities.nginxSecureLink ||
            capabilities.useHttpTemporaryLink ||
            path.endsWith("/play/live.php") ||
            path.endsWith("/play/movie.php") ->
            StalkerPlaybackMode.TEMP_LINK_NGINX

        UrlSecurityPolicy.isAllowedStreamEntryUrl(directCandidate) ->
            StalkerPlaybackMode.DIRECT_URL

        else -> StalkerPlaybackMode.LOCALHOST_CMD
    }
}

internal fun orderStalkerCommandVariants(
    variants: List<StalkerCommandVariant>
): List<StalkerCommandVariant> = variants
    .map { variant -> variant.correctStoredCommandMode() }
    .distinctBy { it.cmd.trim() }
    .sortedWith(compareBy<StalkerCommandVariant>({ playbackModeRank(it.playbackMode) }, { it.priority }))

internal fun URI.isStalkerChannelCommandPath(): Boolean {
    val segments = path
        ?.trim('/')
        ?.split('/')
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val channelTarget = segments
        .dropLast(1)
        .zip(segments.drop(1))
        .firstOrNull { (previous, _) -> previous.equals("ch", ignoreCase = true) }
        ?.second
        ?: return false
    return channelTarget.trimEnd('_').all(Char::isDigit) &&
        channelTarget.trimEnd('_').isNotBlank()
}

private fun StalkerCommandVariant.correctStoredCommandMode(): StalkerCommandVariant {
    if (playbackMode != StalkerPlaybackMode.DIRECT_URL) {
        return this
    }
    return if (detectStalkerPlaybackMode(cmd) == StalkerPlaybackMode.LOCALHOST_CMD) {
        copy(playbackMode = StalkerPlaybackMode.LOCALHOST_CMD)
    } else {
        this
    }
}

private fun playbackModeRank(mode: StalkerPlaybackMode): Int = when (mode) {
    StalkerPlaybackMode.DIRECT_URL -> 0
    StalkerPlaybackMode.MULTI_CMD -> 1
    StalkerPlaybackMode.PLAY_LIVE_PORTAL,
    StalkerPlaybackMode.PLAY_MOVIE_PORTAL,
    StalkerPlaybackMode.LOCALHOST_CMD -> 2
    StalkerPlaybackMode.TEMP_LINK_NGINX,
    StalkerPlaybackMode.TEMP_LINK_FLUSSONIC,
    StalkerPlaybackMode.TEMP_LINK_WOWZA -> 3
}
