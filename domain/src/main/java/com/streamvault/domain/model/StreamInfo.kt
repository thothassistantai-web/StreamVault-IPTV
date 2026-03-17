package com.streamvault.domain.model

data class StreamInfo(
    val url: String,
    val title: String? = null,
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
    RTSP,    // PE-H03: native RTSP via Media3 RtspMediaSource
    UNKNOWN
}

enum class DecoderMode {
    AUTO,
    HARDWARE,
    SOFTWARE
}
