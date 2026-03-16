# 04 — Domain Layer Issues

Architecture, models, use cases, and validation issues.

---

## 1. ✅ FIXED — Domain Model Contains UI Logic

**File:** `domain/src/main/java/com/streamvault/domain/model/Program.kt` (lines 15–25)  
**Severity:** 🟠 HIGH

```kotlin
val progressPercent: Float
    get() {
        if (!isNowPlaying) return 0f
        val now = System.currentTimeMillis()  // ← Domain accessing system clock
        if (now < startTime || endTime <= startTime) return 0f
        return ((now - startTime).toFloat() / (endTime - startTime).toFloat()).coerceIn(0f, 1f)
    }
```

This violates Clean Architecture — the domain model directly calls `System.currentTimeMillis()`. This makes unit testing non-deterministic and couples the domain to the Android runtime.

**Fix:** Move progress calculation to a utility function that accepts `currentTimeMs` as a parameter, or compute in the ViewModel.

---

## 2. ⏭️ DEFERRED — Severely Under-Developed Use Case Layer

**File:** `domain/src/main/java/com/streamvault/domain/usecase/`  
**Severity:** 🟠 HIGH

Only **1 use case** exists: `GetCustomCategories`. An IPTV app of this scope should have 15–20+ use cases to properly encapsulate business logic. Without them, business logic is scattered across repositories and ViewModels.

**Missing use cases:**
| Use Case | Purpose |
|----------|---------|
| `GetLiveChannels` | Filter, sort, paginate channels |
| `GetMovies` / `GetSeries` | Browse content with filters |
| `SearchContent` | Cross-content-type search |
| `ToggleFavorite` | Add/remove with validation |
| `ManageProvider` | Add/update/delete with auth |
| `GetEpgForChannel` | EPG with timezone handling |
| `RecordPlaybackHistory` | Track with dedup logic |
| `ValidateStreamUrl` | URL validation and type detection |
| `ExportBackup` / `ImportBackup` | Data backup with integrity |
| `SetParentalControls` | PIN management and category locking |

**Impact:** Business logic duplication, harder to test, harder to maintain.

---

## 3. ✅ FIXED (race) / ⚠️ BY DESIGN (persistence) — ParentalControlManager — Race Condition and No Persistence

> See [01_CRITICAL_BLOCKERS.md #11](01_CRITICAL_BLOCKERS.md#11-parentalcontrolmanager-has-race-condition-and-no-persistence)

---

## 4. ✅ FIXED — No Data Validation Across Domain Models

**Severity:** 🟠 HIGH

Multiple domain models accept semantically invalid values without validation:

### PlaybackHistory
```kotlin
val resumePositionMs: Long = 0      // Could be negative
val totalDurationMs: Long = 0       // Could be negative
val watchCount: Int = 1             // Could be 0 or negative
val seasonNumber: Int? = null       // Could be 0 (seasons start at 1)
val episodeNumber: Int? = null      // Could be 0
```
No invariant: `resumePositionMs ≤ totalDurationMs`

### Movie
```kotlin
val duration: String? = null        // Ambiguous format
val durationSeconds: Int = 0        // Could be negative
val rating: Float = 0f              // No range (0–10? 0–100?)
```
Two duration fields with no documented precedence.

### Channel
```kotlin
val streamUrl: String = ""          // Empty string useless — should be nullable
val errorCount: Int = 0             // View concern in domain model
```

### Series / Season / Episode
```kotlin
val seasonNumber: Int               // No positive validation
val episodeNumber: Int              // No positive validation
val durationSeconds: Int = 0        // Could be negative
val airDate: String? = null         // Format not specified
```

### VideoFormat
```kotlin
val width: Int                      // Could be negative
val height: Int                     // Could be negative
val frameRate: Float = 0f           // Could be negative
val bitrate: Int = 0                // Could be negative
```

### RecordingRequest
```kotlin
val scheduledStartMs: Long          // Could be in the past
val scheduledEndMs: Long            // Could be ≤ scheduledStartMs
```

**Fix:** Add `init` blocks with `require()` for invariants, or validate at the creation site (mapper/repository).

---

## 5. ✅ FIXED — Repository API Design Issues

### 5a. FavoriteRepository — Ambiguous API

**File:** `domain/src/main/java/com/streamvault/domain/repository/FavoriteRepository.kt`  

```kotlin
fun getFavorites(contentType: ContentType? = null): Flow<List<Favorite>>
fun getAllFavorites(contentType: ContentType): Flow<List<Favorite>>
```

What's the difference? `getFavorites(null)` vs `getAllFavorites(type)` — the naming is confusing and the behavior overlap is unclear. Consolidate into one method.

### 5b. ChannelRepository — Wrong Return Type

**File:** `domain/src/main/java/com/streamvault/domain/repository/ChannelRepository.kt`  

```kotlin
suspend fun getStreamUrl(channel: Channel): Result<String>
```

Returns a bare URL string, but `StreamInfo` data class already exists with `url`, `headers`, `userAgent`, and `streamType`. This method should return `Result<StreamInfo>`.

### 5c. EpgRepository — "Now Playing" Returns Lists

**File:** `domain/src/main/java/com/streamvault/domain/repository/EpgRepository.kt`  

```kotlin
fun getNowPlayingForChannels(...): Flow<Map<String, List<Program>>>
```

Why `List<Program>` per channel? "Now playing" should be a single program: `Map<String, Program?>`.

### 5d. MovieRepository — Asymmetric Progress API

```kotlin
suspend fun updateWatchProgress(movieId: Long, progress: Long)
// Missing: getWatchProgress()
```

Can update progress but can't read it back through the repository.

### 5e. ProviderRepository — Callback vs Flow Inconsistency

```kotlin
suspend fun loginXtream(
    ...,
    onProgress: ((String) -> Unit)? = null  // ← Callback
): Result<Provider>
```

All other repositories use `Flow` for streaming data. Provider operations use callbacks, breaking the reactive pattern.

### 5f. PlaybackHistoryRepository — No Result Wrapper

```kotlin
suspend fun recordPlayback(history: PlaybackHistory)       // ← No Result<>
suspend fun updateResumePosition(history: PlaybackHistory)  // ← No Result<>
```

These can fail (database full, constraint violation) but the caller has no way to detect failure.

---

## 6. ✅ FIXED — ChannelNormalizer Algorithm Flawed

**File:** `domain/src/main/java/com/streamvault/domain/util/ChannelNormalizer.kt` (lines 12–40)  
**Severity:** 🟡 MEDIUM

The normalizer strips quality tags (HD, FHD, SD, 4K) to group "same" channels together, but:

1. **False merges:** "BBC One HD" and "BBC One SD" map to the same ID — these are distinct channels with different stream qualities the user may want to choose between
2. **No accent normalization:** "Télé" and "Tele" are treated as different
3. **Doesn't handle:** bitrate indicators, server names, timezone suffixes
4. **Numbers mishandled:** "HBO 2" stripped differently than "HBO2"

---

## 7. ⚠️ MITIGATED — Category Model Has Redundant Protection Fields

**File:** `domain/src/main/java/com/streamvault/domain/model/Category.kt`  
**Severity:** 🟡 MEDIUM

```kotlin
data class Category(
    val isAdult: Boolean = false,
    val isUserProtected: Boolean = false  // ← Redundant with ParentalControlManager
)
```

Parental protection is tracked in two places: the `Category` model and the `ParentalControlManager`. These can get out of sync — one says protected, the other says unlocked.

---

## 8. ⏭️ DEFERRED — Missing Domain Features for Premium IPTV

**Severity:** 🟡 MEDIUM

No domain models or repository interfaces exist for:

| Feature | Impact |
|---------|--------|
| Subtitle track selection | Users can't select subtitles |
| Audio track metadata | No language labels in track picker |
| Adaptive bitrate preferences | No quality control |
| Watch history search/filter | Can't find previously watched content |
| Cross-device sync | No resume across devices |
| Content recommendations | No discovery beyond categories |
| Provider health/status model | No way to show connection quality |

---

## 9. ✅ FIXED — `GetCustomCategories` Swallows Exceptions

**File:** `domain/src/main/java/com/streamvault/domain/usecase/GetCustomCategories.kt`  
**Severity:** 🟡 MEDIUM

```kotlin
}.getOrElse {
    emptyList()  // ← Exception silently discarded
}
```

If the categories query fails, the UI shows "no categories" with no error indication. The user has no way to know something went wrong.

---

## 10. ✅ FIXED — Provider Model Stores Credentials in Plain Data Class

**File:** `domain/src/main/java/com/streamvault/domain/model/Provider.kt`  
**Severity:** 🟡 MEDIUM

```kotlin
data class Provider(
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
)
```

While credentials are encrypted at the storage layer (`CredentialCrypto`), the domain model carries plaintext credentials in memory. Any memory dump, debug log, or crash report that serializes this object will expose them.

**Mitigation:** The `toString()` auto-generated by `data class` will include username and password in any log statement. Consider overriding `toString()` to redact sensitive fields.

---

## 11. ✅ FIXED — `StreamInfo` Model Exists but Underutilized

**File:** `domain/src/main/java/com/streamvault/domain/model/StreamInfo.kt`  
**Severity:** 🔵 LOW

A comprehensive `StreamInfo` data class exists with `url`, `headers`, `userAgent`, `streamType`, etc. But `ChannelRepository.getStreamUrl()` returns `Result<String>` instead of `Result<StreamInfo>`, bypassing all the rich metadata.
