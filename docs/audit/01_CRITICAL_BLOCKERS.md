# 01 — Critical Blockers

All ship-blocking issues that will cause crashes, data loss, or security vulnerabilities in production.

---

## 1. ✅ FIXED — Missing `proguard-rules.pro` — Release Builds Will Fail

**Module:** Build Configuration  
**File:** `app/build.gradle.kts`  
**Severity:** 🔴 SHIP BLOCKER

The release build type enables minification and resource shrinking but references a ProGuard rules file that does not exist:

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"  // ← FILE DOES NOT EXIST
        )
    }
}
```

**Impact:** R8 will strip critical classes (Hilt components, Retrofit interfaces, Room entities, GSON models) causing runtime crashes in release builds.

**Fix:** Create `app/proguard-rules.pro` with keep rules for:
- Hilt-generated components
- Retrofit service interfaces and GSON-serialized models
- Room database entities and DAOs
- Media3/ExoPlayer decoder internals
- Domain model data classes used in serialization

---

## 2. ✅ FIXED — Decoder Mode Logic Is Reversed

**Module:** Player  
**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (lines 81–86)  
**Severity:** 🔴 SHIP BLOCKER

```kotlin
when (currentDecoderMode) {
    DecoderMode.AUTO -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    DecoderMode.HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF   // ← WRONG
    DecoderMode.SOFTWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER // ← WRONG
}
```

- Selecting **HARDWARE** actually *disables* extension renderers (software path used)
- Selecting **SOFTWARE** *enables* extension renderers (hardware path used)
- The user-facing setting does the opposite of what it claims

**Fix:**
```kotlin
DecoderMode.HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
DecoderMode.SOFTWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
```

---

## 3. ✅ FIXED — No MediaSession Integration — TV Remote Won't Work

**Module:** Player  
**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt`  
**Severity:** 🔴 SHIP BLOCKER

The `media3-session` library is declared as a dependency but never used. No `MediaSession` is created. This means:

- Android TV remote control buttons (play/pause/stop) won't control playback
- Fire TV Stick voice commands won't work
- System media notifications are absent
- Violates Android TV app quality guidelines

**Fix:** Create a `MediaSession` in the player engine and expose it for the UI to bind:
```kotlin
private var mediaSession: MediaSession? = null

// In player initialization:
mediaSession = MediaSession.Builder(context, exoPlayer).build()

// In release():
mediaSession?.release()
```

---

## 4. ✅ FIXED — `pixelWidthHeightRatio` Stored as Frame Rate

**Module:** Player  
**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (line 180)  
**Severity:** 🔴 CRITICAL

```kotlin
override fun onVideoSizeChanged(videoSize: VideoSize) {
    _videoFormat.value = VideoFormat(
        width = videoSize.width,
        height = videoSize.height,
        frameRate = videoSize.pixelWidthHeightRatio // ← THIS IS ASPECT RATIO, NOT FPS
    )
}
```

`pixelWidthHeightRatio` is typically 1.0 (square pixels) and has nothing to do with frame rate. The diagnostics/stats overlay will show completely wrong frame rate data.

**Fix:** Capture frame rate from `onVideoInputFormatChanged` callback instead:
```kotlin
override fun onVideoInputFormatChanged(eventTime: EventTime, format: Format, ...) {
    lastFrameRate = format.frameRate
}
```

---

## 5. ⚠️ MITIGATED — EPG Staging Uses Negative Provider IDs — Data Corruption Risk

**Module:** Data  
**File:** `data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt` (lines 48–49)  
**Severity:** 🔴 CRITICAL

```kotlin
val stagingProviderId = -providerId
programDao.deleteByProvider(stagingProviderId)
// ... insert programs with staging ID ...
programDao.moveToProvider(stagingProviderId, providerId)
```

Using negative provider IDs as a staging mechanism creates a race condition: if sync is interrupted between insert and move, orphaned programs with negative IDs remain permanently. Concurrent syncs for different providers could also collide.

**Fix:** Add a `sync_status` column (`STAGING` / `ACTIVE`) to the programs table instead of manipulating provider IDs.

---

## 6. ✅ FIXED — M3U Category ID Collision Across Providers

**Module:** Data  
**File:** `data/src/main/java/com/streamvault/data/sync/SyncManager.kt` (lines 419–430)  
**Severity:** 🔴 CRITICAL

```kotlin
class CategoryAccumulator(providerId: Long, type: String, startId: Long) {
    private val categoryIds = LinkedHashMap<String, Long>()
    fun idFor(name: String): Long {
        return categoryIds.getOrPut(name) { startId + categoryIds.size }
    }
}
```

Category IDs are allocated sequentially (1–9999 for LIVE, 10000+ for MOVIE) without provider scoping. If two providers have categories, IDs will collide, causing channels from Provider A to appear under Provider B's categories.

**Fix:** Scope IDs per provider: `providerId * 1_000_000 + index` or use truly unique auto-generated IDs.

---

## 7. ⏭️ DEFERRED — Database Migration 8→9 ID Remapping Can Corrupt References

**Module:** Data  
**File:** `data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt` (lines 180–290)  
**Severity:** 🔴 CRITICAL

The migration rebuilds tables and attempts to remap IDs via JOIN:

```sql
INSERT INTO channel_id_map(old_id, new_id)
SELECT old.id, new.id FROM channels old
JOIN channels_new new
  ON new.provider_id = old.provider_id
 AND new.stream_id = old.stream_id
```

If duplicate `(provider_id, stream_id)` pairs exist in source data (which M3U imports don't prevent), the JOIN produces multiple matches. Favorites, history, and other references will point to wrong channels.

**Fix:** Add `DISTINCT` or `LIMIT 1` per old_id, and validate uniqueness constraints before the JOIN.

---

## 8. ✅ FIXED — ViewModels Never Cancel Background Jobs — Memory Leaks

**Module:** App  
**Files:** Multiple ViewModels  
**Severity:** 🔴 CRITICAL

`PlayerViewModel`, `HomeViewModel`, and `MultiViewViewModel` all create long-running `Job` references that are never cancelled in `onCleared()`:

**PlayerViewModel:**
```kotlin
private var channelInfoHideJob: Job? = null
private var liveOverlayHideJob: Job? = null
private var diagnosticsHideJob: Job? = null
private var numericInputCommitJob: Job? = null
private var epgJob: Job? = null
private var playlistJob: Job? = null
// None cancelled in onCleared()
```

**HomeViewModel:**
```kotlin
private var epgJob: Job? = null
private var loadChannelsJob: Job? = null
private var categoriesJob: Job? = null
private var recentChannelsJob: Job? = null
private var previewPlaybackJob: Job? = null
// None cancelled in onCleared()
```

**MultiViewViewModel:**
```kotlin
private var borderHideJob: Job? = null
private var telemetryJob: Job? = null
private val playerEngines = mutableMapOf<Int, PlayerEngine>()
// PlayerEngine instances may not release on back press
```

**Impact:** When the user navigates away, Jobs keep running, player engines keep buffering, and memory is never reclaimed. On low-RAM TV devices this will cause ANR or OOM.

**Fix:** Cancel all Jobs and release all resources in `onCleared()`:
```kotlin
override fun onCleared() {
    listOf(epgJob, playlistJob, channelInfoHideJob, ...).forEach { it?.cancel() }
    playerEngine.release()
}
```

---

## 9. ✅ FIXED — Credentials Can Leak Into Crash Logs

**Module:** Data  
**File:** `data/src/main/java/com/streamvault/data/sync/SyncManager.kt` (lines 738–753)  
**Severity:** 🔴 CRITICAL

While `redactUrlForLogs()` exists, it is not consistently applied. Exception messages from OkHttp/Retrofit often embed the full URL including credentials:

```kotlin
Log.e(TAG, "Sync failed for provider $providerId: ${e.message}", e)
// e.message → "Failed to reach http://user:pass@server:8080/get.php?..."
```

**Impact:** Credentials exposed in logcat, crash reporters (Firebase Crashlytics, etc.), and analytics.

**Fix:** Always sanitize before logging:
```kotlin
val safeMessage = redactUrlForLogs(e.message ?: "unknown error")
Log.e(TAG, "Sync failed for provider $providerId: $safeMessage")
```

---

## 10. ✅ FIXED — No Signing Configuration for Release

**Module:** Build  
**File:** `app/build.gradle.kts`  
**Severity:** 🔴 SHIP BLOCKER  

No `signingConfigs` block exists. Cannot generate a signed APK/AAB for Google Play without manual configuration.

**Fix:** Add a signing configuration that reads from environment variables or a local keystore properties file (not committed to VCS):
```kotlin
signingConfigs {
    create("release") {
        storeFile = file(keystoreProperties["storeFile"] as String)
        storePassword = keystoreProperties["storePassword"] as String
        keyAlias = keystoreProperties["keyAlias"] as String
        keyPassword = keystoreProperties["keyPassword"] as String
    }
}
```

---

## 11. ✅ FIXED (race) / ⚠️ BY DESIGN (persistence) — ParentalControlManager Has Race Condition and No Persistence

**Module:** Domain  
**File:** `domain/src/main/java/com/streamvault/domain/manager/ParentalControlManager.kt` (lines 21–28)  
**Severity:** 🔴 CRITICAL

```kotlin
fun unlockCategory(providerId: Long, categoryId: Long) {
    val current = _unlockedCategoriesByProvider.value.toMutableMap()  // ← Non-atomic read
    val providerSet = (current[providerId] ?: emptySet()).toMutableSet()
    providerSet.add(categoryId)
    current[providerId] = providerSet
    _unlockedCategoriesByProvider.value = current  // ← Race window
}
```

**Issue 1:** Between `.value` read and `.value = current`, another coroutine can modify state → lost updates.  
**Issue 2:** All unlock state is held only in memory. App restart resets all parental control unlocks — parental controls are essentially useless.  
**Issue 3:** No PIN/password is required to unlock a category.

---

## 12. ✅ FIXED — No Foreign Key Constraints in Room Database

**Module:** Data  
**File:** `data/src/main/java/com/streamvault/data/local/entity/Entities.kt`  
**Severity:** 🔴 CRITICAL

Room entities have no `@ForeignKey` annotations. Deleting a provider leaves behind orphaned channels, movies, series, favorites, EPG data, and playback history. Over time, the database accumulates ghost records consuming storage and degrading query performance.

**Fix:** Add cascading foreign keys:
```kotlin
@Entity(
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
```

---

## 13. ✅ FIXED — XML Date Parsing Returns Epoch 0 Silently

**Module:** Data  
**File:** `data/src/main/java/com/streamvault/data/parser/XmltvParser.kt` (lines 184–200)  
**Severity:** 🔴 CRITICAL

```kotlin
try {
    val cleaned = dateStr.replace("""[^\d]""".toRegex(), "")
    if (cleaned.length >= 14) {
        val basicFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        return basicFormat.parse(cleaned.substring(0, 14))?.time ?: 0
    }
} catch (_: Exception) { }
return 0  // ← Programs marked as starting at Jan 1, 1970
```

Any XMLTV program with an unparseable date gets `startTime = 0`, placing it in the distant past. These programs are silently lost from the EPG display. No warning is logged.

---

## 14. ✅ FIXED — M3U Input Not Length-Bounded (DoS Vector)

**Module:** Data  
**File:** `data/src/main/java/com/streamvault/data/parser/M3uParser.kt` (lines 299–330)  
**Severity:** 🔴 CRITICAL

Channel names, group titles, and other strings from M3U files have no maximum length validation. A malicious or malformed M3U with megabyte-length strings will:
1. Be stored in Room → database bloat
2. Rendered in UI → ANR during text layout
3. Occupy heap → OOM on low-RAM TV devices

**Fix:** Truncate all parsed strings: `name = extinf.name.take(500)`, `groupTitle = group.take(200)`.

---

## 15. ⚠️ FALSE POSITIVE — `file://` URI Exposure

**Module:** App  
**File:** `app/src/main/java/com/streamvault/app/ui/screens/provider/ProviderSetupScreen.kt` (line 91)  
**Severity:** 🔴 CRITICAL

```kotlin
m3uUrl = "file://${outFile.absolutePath}"
```

Direct `file://` URIs are forbidden on Android 7+ (API 24) for cross-process sharing and violate security best practices. On API 29+, scoped storage further restricts raw file access.

**Fix:** Use `FileProvider` to serve local M3U files with `content://` URIs.

---

## 16. ✅ FIXED — No Error Recovery Strategy in Player

**Module:** Player  
**File:** `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` (lines 172–173)  
**Severity:** 🔴 CRITICAL

```kotlin
override fun onPlayerError(error: PlaybackException) {
    _error.tryEmit(PlayerError.fromException(error))
    _playbackState.value = PlaybackState.ERROR
}
```

When a stream fails, the player transitions to `ERROR` state and stays there permanently. IPTV streams frequently experience transient network issues — the player should auto-retry with exponential backoff. Currently the only recovery path is manual user intervention (selecting a different channel and coming back).

**Fix:** Implement retry logic:
```kotlin
private var retryCount = 0
private val maxRetries = 3

override fun onPlayerError(error: PlaybackException) {
    if (retryCount < maxRetries && error.isRecoverable()) {
        retryCount++
        handler.postDelayed({ prepare(lastStreamInfo) }, retryCount * 2000L)
    } else {
        _error.tryEmit(PlayerError.fromException(error))
        _playbackState.value = PlaybackState.ERROR
    }
}
```
