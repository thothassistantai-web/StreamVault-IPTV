package com.streamvault.domain.model

data class Provider(
    val id: Long = 0,
    val name: String,
    val type: ProviderType,
    val serverUrl: String,
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val stalkerMacAddress: String = "",
    val stalkerDeviceProfile: String = "",
    val stalkerDeviceTimezone: String = "",
    val stalkerDeviceLocale: String = "",
    val stalkerSerialNumber: String = "",
    val stalkerDeviceId: String = "",
    val stalkerDeviceId2: String = "",
    val stalkerSignature: String = "",
    val stalkerAdvancedOptionsJson: String = "",
    val stalkerAuthMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    val stalkerPortalProfile: StalkerPortalProfile = StalkerPortalProfile.MAG_BASIC,
    val stalkerPortalFingerprint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    val stalkerMagPreset: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    val stalkerLastBootstrapRecipe: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    val stalkerEndpointPreference: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    val stalkerCookieMode: StalkerCookieMode = StalkerCookieMode.NONE,
    val stalkerPlaybackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    val stalkerLastPlaybackMode: String? = null,
    val stalkerCredentialsRequired: Boolean = false,
    val stalkerMacRequired: Boolean = true,
    val stalkerUsesTemporaryLinks: Boolean = false,
    val stalkerModuleRestricted: Boolean = false,
    val stalkerStrictFingerprintRequired: Boolean = false,
    val stalkerRecipeFallbackUsed: Boolean = false,
    val stalkerRecipeRediscoveryAttempts: Int = 0,
    val isActive: Boolean = true,
    val maxConnections: Int = 1,
    val expirationDate: Long? = null,
    val apiVersion: String? = null,
    val allowedOutputFormats: List<String> = emptyList(),
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.UPFRONT,
    val xtreamFastSyncEnabled: Boolean = true,
    val xtreamLiveSyncMode: ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
    val m3uVodClassificationEnabled: Boolean = false,
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    val lastSyncedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "Provider name must not be blank" }
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(lastSyncedAt >= 0) { "lastSyncedAt must be non-negative" }
    }

    override fun toString(): String =
        "Provider(id=$id, name=$name, type=$type, status=$status, isActive=$isActive)"
}

enum class ProviderType {
    XTREAM_CODES,
    M3U,
    STALKER_PORTAL,
    JELLYFIN
}

enum class ProviderEpgSyncMode {
    UPFRONT,
    BACKGROUND,
    SKIP
}

enum class ProviderXtreamLiveSyncMode {
    AUTO,
    CATEGORY_BY_CATEGORY,
    STREAM_ALL
}

enum class StalkerAuthMode {
    AUTO,
    MAC_ONLY,
    MAC_PLUS_CREDENTIALS,
    CREDENTIALS_ONLY
}

enum class StalkerPortalProfile {
    MAG_BASIC,
    MAG_STRICT,
    AUTH_REQUIRED,
    AUTH_PLUS_MAG,
    MODULE_GATED
}

enum class StalkerPortalFingerprint {
    BASIC_MAC,
    STRICT_MAG,
    AUTH_ONLY,
    AUTH_STRICT_MAG,
    MODULE_GATED,
    TEMP_LINK_STRICT
}

enum class StalkerMagPreset {
    GENERIC_SAFE,
    MAG250_LEGACY,
    MAG254_STRICT,
    MINISTRA_MODERN
}

enum class StalkerBootstrapRecipe {
    GENERIC_SAFE,
    LEGACY_MAG,
    STRICT_MAG,
    PORTAL_PREFERRED,
    LOCALIZATION_STRICT,
    AUTH_ONLY,
    AUTH_STRICT_MAG,
    MODULE_GATED
}

enum class StalkerEndpointPreference {
    AUTO,
    SERVER_LOAD,
    PORTAL
}

enum class StalkerCookieMode {
    NONE,
    CREATE_LINK,
    PLAYBACK,
    BOTH
}

enum class StalkerPlaybackBackendHint {
    AUTO,
    DIRECT,
    PLAY_LIVE,
    PLAY_MOVIE,
    TEMP_LINK,
    TEMP_LINK_STRICT
}

enum class ProviderStatus {
    ACTIVE,
    PARTIAL,
    EXPIRED,
    DISABLED,
    ERROR,
    UNKNOWN
}

class ProviderSavedWithSyncErrorException(
    val provider: Provider,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
