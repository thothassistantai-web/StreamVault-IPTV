package com.streamvault.app.ui.model

enum class LiveTvChannelMode {
    COMFORTABLE,
    COMPACT,
    PRO;

    companion object {
        fun fromStorage(value: String?): LiveTvChannelMode =
            entries.firstOrNull { it.name == value } ?: COMFORTABLE
    }
}
