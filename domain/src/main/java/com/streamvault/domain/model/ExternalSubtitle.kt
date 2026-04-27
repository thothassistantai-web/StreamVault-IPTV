package com.streamvault.domain.model

data class ExternalSubtitle(
    val id: String,
    val language: String,
    val displayLanguage: String,
    val releaseName: String,
    val source: ExternalSubtitleSource,
    val downloadId: String = id
)

enum class ExternalSubtitleSource {
    OPENSUBTITLES
}
