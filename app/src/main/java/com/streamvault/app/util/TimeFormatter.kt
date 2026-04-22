package com.streamvault.app.util

/**
 * Formats a playback position in milliseconds as a human-readable time string.
 *
 * Examples:
 *   3_723_000 ms  →  "1:02:03"
 *     143_000 ms  →  "2:23"
 *       5_000 ms  →  "0:05"
 */
fun formatPositionMs(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
