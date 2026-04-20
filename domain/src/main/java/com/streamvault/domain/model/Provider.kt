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
    val stalkerMacAddress: String = "",
    val stalkerDeviceProfile: String = "",
    val stalkerDeviceTimezone: String = "",
    val stalkerDeviceLocale: String = "",
    val isActive: Boolean = true,
    val maxConnections: Int = 1,
    val expirationDate: Long? = null,
    val apiVersion: String? = null,
    val allowedOutputFormats: List<String> = emptyList(),
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.UPFRONT,
    val xtreamFastSyncEnabled: Boolean = true,
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
    STALKER_PORTAL
}

enum class ProviderEpgSyncMode {
    UPFRONT,
    BACKGROUND,
    SKIP
}

enum class ProviderStatus {
    ACTIVE,
    PARTIAL,
    EXPIRED,
    DISABLED,
    ERROR,
    UNKNOWN
}
