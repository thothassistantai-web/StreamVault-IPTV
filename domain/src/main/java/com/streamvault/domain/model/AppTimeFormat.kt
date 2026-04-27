package com.streamvault.domain.model

enum class AppTimeFormat(val storageValue: String) {
    SYSTEM("system"),
    TWELVE_HOUR("12h"),
    TWENTY_FOUR_HOUR("24h");

    companion object {
        fun fromStorage(value: String?): AppTimeFormat =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}