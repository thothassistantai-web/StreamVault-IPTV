# 02 — Player Engine Issues

All issues related to media playback, decoding, buffering, and streaming.

---

## 1. ✅ FIXED — Decoder Mode Logic Is Reversed

> See [01_CRITICAL_BLOCKERS.md #2](01_CRITICAL_BLOCKERS.md#2-decoder-mode-logic-is-reversed)

---

## 2. ✅ FIXED — Buffer Configuration Too Low for IPTV

**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (lines 90–97)  
**Severity:** 🟠 HIGH

```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        15000, // min buffer — 15s
        30000, // max buffer — 30s
        2500,  // playback start threshold
        5000   // rebuffer threshold
    )
    .build()
```

- 15-second minimum buffer is inadequate for live IPTV streams over variable-quality networks
- 30-second max buffer doesn't provide enough runway for network jitter
- Users will experience frequent rebuffering

**Recommendation:** Make buffer sizes configurable per use case:
- **Single-stream playback:** 30s min / 60s max / 5s start / 10s rebuffer
- **Multi-view (4 streams):** Keep current 15s/30s to manage memory
- Expose as a user-facing setting (Low/Medium/High buffer)

---

## 3. ✅ FIXED — No MediaSession Integration

> See [01_CRITICAL_BLOCKERS.md #3](01_CRITICAL_BLOCKERS.md#3-no-mediasession-integration--tv-remote-wont-work)

---

## 4. ✅ FIXED — No Network Error Recovery

> See [01_CRITICAL_BLOCKERS.md #16](01_CRITICAL_BLOCKERS.md#16-no-error-recovery-strategy-in-player)

---

## 5. ✅ FIXED — Video Format Frame Rate Is Wrong

> See [01_CRITICAL_BLOCKERS.md #4](01_CRITICAL_BLOCKERS.md#4-pixelwidthheightratio-stored-as-frame-rate)

---

## 6. ✅ FIXED — Error Classification Is Fragile (String Matching)

**File:** `player/src/main/java/com/streamvault/player/PlayerEngine.kt` (lines 44–56)  
**Severity:** 🟠 HIGH

```kotlin
fun fromException(e: Throwable): PlayerError {
    val msg = e.message ?: "Unknown playback error"
    return when {
        msg.contains("Unable to connect", ignoreCase = true) -> NetworkError(msg)
        msg.contains("timeout", ignoreCase = true) -> NetworkError(msg)
        msg.contains("Response code: 4", ignoreCase = true) -> SourceError(msg)
        msg.contains("Response code: 5", ignoreCase = true) -> NetworkError(msg)
        msg.contains("decoder", ignoreCase = true) -> DecoderError(msg)
        msg.contains("codec", ignoreCase = true) -> DecoderError(msg)
        else -> UnknownError(msg)
    }
}
```

String-matching error messages is fragile — messages change across Media3 versions. This also misclassifies:
- 401/403 as generic `SourceError` (should be `AuthError`)
- DRM failures as `UnknownError`
- Manifest parse errors as `UnknownError`

**Fix:** Use `PlaybackException.errorCode` for reliable classification:
```kotlin
fun fromException(e: Throwable): PlayerError = when (e) {
    is PlaybackException -> when (e.errorCode) {
        ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> NetworkError(e.message ?: "Network error")
        ERROR_CODE_DECODER_INIT_FAILED,
        ERROR_CODE_DECODER_QUERY_FAILED -> DecoderError(e.message ?: "Decoder error")
        ERROR_CODE_IO_BAD_HTTP_STATUS -> SourceError(e.message ?: "Source error")
        else -> UnknownError(e.message ?: "Unknown error")
    }
    else -> UnknownError(e.message ?: "Unknown error")
}
```

---

## 7. ✅ FIXED — No Explicit Network Timeout on Data Source

**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (lines 218–237)  
**Severity:** 🟠 HIGH

The `OkHttpDataSource.Factory` relies on the injected OkHttpClient's timeouts. If that client has generous defaults (e.g., 30s connect, 60s read), live streams stuck in buffering won't timeout for a very long time. Users will see an infinite spinner.

**Fix:** Set explicit timeouts appropriate for live streaming:
```kotlin
OkHttpDataSource.Factory(okHttpClient)
    .setDefaultRequestProperties(headers)
    .setConnectTimeoutMs(10_000)
    .setReadTimeoutMs(15_000)
```

---

## 8. ✅ FIXED — No Background/Foreground Lifecycle Handling

**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt`  
**Severity:** 🟠 HIGH

The player has no awareness of app lifecycle transitions. When the user presses the Home button on the TV remote:
- Player continues buffering and decoding in the background
- Battery/power drain on TV devices
- Network bandwidth consumed unnecessarily
- No pause-on-background for provider connection limits

**Fix:** Make the player lifecycle-aware or ensure the hosting ViewModel/Activity pauses playback on `ON_STOP`.

---

## 9. ✅ FIXED — Track Selection Has Redundant API Calls

**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (lines 347–413)  
**Severity:** 🟡 MEDIUM

Both `selectAudioTrack()` and `selectSubtitleTrack()` first disable-then-enable track types in separate `trackSelectionParameters` assignments, causing two parameter updates when one would suffice.

```kotlin
// Step 1: Enable type (unnecessary extra update)
player.trackSelectionParameters = player.trackSelectionParameters
    .buildUpon()
    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
    .build()

// Step 2: Then set override
player.trackSelectionParameters = player.trackSelectionParameters
    .buildUpon()
    .setOverrideForType(...)
    .build()
```

These should be combined into a single parameter update to avoid an intermediate track selection state.

---

## 10. ✅ FIXED — Polling Redundant with Position Discontinuity Listener

**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (lines 427–437)  
**Severity:** 🟡 MEDIUM

A 500ms polling loop updates `_currentPosition` and `_duration`, but `onPositionDiscontinuity()` already updates position. This creates redundant StateFlow emissions.

For multi-view (4 players), this means 4 × 2Hz = 8 position updates per second on the main thread, plus redundant emissions from the discontinuity listener.

**Recommendation:** Either remove polling and rely on the listener (less smooth) or reduce polling to 1Hz and sync with listener events.

---

## 11. ✅ FIXED — No Bitrate / Quality Metrics for Diagnostics

**File:** `player/src/main/java/com/streamvault/player/PlayerEngine.kt` (PlayerStats data class)  
**Severity:** 🟡 MEDIUM

```kotlin
data class PlayerStats(
    val videoCodec: String = "Unknown",
    val audioCodec: String = "Unknown",
    val videoBitrate: Int = 0,
    val droppedFrames: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)
```

Missing for premium diagnostics:
- Current bandwidth estimate
- Buffered duration percentage
- Adaptive bitrate switching events
- Rebuffering count and cumulative time
- Audio sample rate and channel count
- Latency (for live streams)

---

## 12. ✅ FIXED — Stream Type Detection Incomplete

**File:** `player/src/main/java/com/streamvault/player/StreamTypeDetector.kt` (lines 14–32)  
**Severity:** 🟡 MEDIUM

The URL-based detection misses:
- Smooth Streaming (`.ism` / `.isml`)
- WebRTC streams
- RTSP/RTMP (common in IPTV)
- URLs with query parameters masking the extension (e.g., `http://x.com/stream?token=abc`)

The fallback `StreamType.UNKNOWN` delegates to ExoPlayer's content-type sniffing, which works but adds startup latency for the first chunk download.

---

## 13. ✅ FIXED — Audio Content Type Not Stream-Aware

**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (lines 106–110)  
**Severity:** 🔵 LOW

```kotlin
.setAudioAttributes(
    AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.CONTENT_TYPE_MOVIE)
        .build(),
    true
)
```

`CONTENT_TYPE_MOVIE` is always used regardless of whether the content is live TV, music, or a movie. While functionally fine, the Android audio system can optimize audio routing differently for `CONTENT_TYPE_MUSIC` vs `CONTENT_TYPE_MOVIE`.

---

## 14. ⏭️ DEFERRED — No Chromecast / Cast Support

**Severity:** 🟡 MEDIUM (for premium release)

No Google Cast integration exists. Premium IPTV apps typically support casting from a phone/tablet to a Chromecast or TV. While this app targets Android TV directly, the Media3 Cast extension could enable cross-device scenarios.

---

## 15. ✅ FIXED — Decoder Reuse Evaluation Not Logged

**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (lines 117–130)  
**Severity:** 🔵 LOW

The `DecoderReuseEvaluation` parameter in `onVideoInputFormatChanged` is never inspected. On some TV chipsets, decoder reuse failures are the root cause of black screens between channel switches. Logging this would aid debugging.

---

## 16. ✅ FIXED — Coroutine Scope Cleanup Not Guaranteed

**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (line 46)  
**Severity:** 🔵 LOW

```kotlin
private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
```

If `release()` is never called (e.g., due to a leak in the hosting ViewModel), the coroutine scope runs indefinitely. Consider using `LifecycleCoroutineScope` or adding a finalizer.
