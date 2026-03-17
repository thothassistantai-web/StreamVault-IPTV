package com.streamvault.player

import com.streamvault.domain.model.StreamType

/**
 * Detects stream type from URL characteristics.
 * Used when the provider doesn't explicitly specify the stream type.
 */
object StreamTypeDetector {

    fun detect(url: String): StreamType {
        val cleanUrl = url.lowercase().substringBefore("?").substringBefore("#")
        val scheme = cleanUrl.substringBefore("://")

        // RTSP / RTSPs — must be handled first; RtspMediaSource doesn't use OkHttp
        if (scheme == "rtsp" || scheme == "rtsps") return StreamType.RTSP

        return when {
            // HLS
            cleanUrl.endsWith(".m3u8") -> StreamType.HLS
            url.contains("/hls/", ignoreCase = true) -> StreamType.HLS
            url.contains(".m3u8", ignoreCase = true) -> StreamType.HLS

            // DASH
            cleanUrl.endsWith(".mpd") -> StreamType.DASH
            url.contains("/dash/", ignoreCase = true) -> StreamType.DASH
            url.contains(".mpd", ignoreCase = true) -> StreamType.DASH

            // MPEG-TS
            cleanUrl.endsWith(".ts") -> StreamType.MPEG_TS
            url.contains("/live/", ignoreCase = true) && !cleanUrl.endsWith(".mp4") -> StreamType.MPEG_TS

            // Progressive (MP4, MKV, AVI, etc.)
            cleanUrl.endsWith(".mp4") -> StreamType.PROGRESSIVE
            cleanUrl.endsWith(".mkv") -> StreamType.PROGRESSIVE
            cleanUrl.endsWith(".avi") -> StreamType.PROGRESSIVE
            cleanUrl.endsWith(".flv") -> StreamType.PROGRESSIVE
            cleanUrl.endsWith(".webm") -> StreamType.PROGRESSIVE
            cleanUrl.endsWith(".mov") -> StreamType.PROGRESSIVE

            // Unknown — let ExoPlayer figure it out via content sniffing
            else -> StreamType.UNKNOWN
        }
    }
}
