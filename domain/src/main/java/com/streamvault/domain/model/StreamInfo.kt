package com.streamvault.domain.model

data class StreamInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val streamType: StreamType = StreamType.UNKNOWN,
    val containerExtension: String? = null,
    val catchUpUrl: String? = null
) {
    init {
        require(url.isNotBlank()) { "StreamInfo url must not be blank" }
    }
}

enum class StreamType {
    HLS,
    DASH,
    MPEG_TS,
    PROGRESSIVE,
    UNKNOWN
}

enum class DecoderMode {
    AUTO,
    HARDWARE,
    SOFTWARE
}
