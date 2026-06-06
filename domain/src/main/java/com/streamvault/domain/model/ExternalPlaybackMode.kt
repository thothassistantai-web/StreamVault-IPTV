package com.streamvault.domain.model

enum class ExternalPlaybackMode(val storageValue: String) {
    INTERNAL_PLAYER("internal"),
    ASK_EVERY_TIME("ask"),
    EXTERNAL_PLAYER("external");

    companion object {
        fun fromStorageValue(value: String?): ExternalPlaybackMode {
            val trimmed = value?.trim()
            if (trimmed.isNullOrBlank()) return INTERNAL_PLAYER
            return entries.firstOrNull { it.storageValue.equals(trimmed, ignoreCase = true) } ?: INTERNAL_PLAYER
        }
    }
}
