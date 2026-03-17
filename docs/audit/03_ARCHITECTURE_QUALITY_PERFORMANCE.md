# StreamVault Pre-Release Audit: Architecture, Code Quality & Performance

> **Audit Date:** March 17, 2026  
> **Focus:** Patterns, redundancy, performance, magic numbers, architectural inconsistencies

---

## 1. Magic Numbers — No Centralized Configuration

**Severity:** MEDIUM  
**Scope:** Entire codebase

Dozens of numeric constants are scattered inline across files with no centralized configuration. This makes tuning and debugging difficult.

### Player Module
| Value | File | Purpose |
|-------|------|---------|
| `30,000 ms` | Media3PlayerEngine.kt | Min buffer duration |
| `60,000 ms` | Media3PlayerEngine.kt | Max buffer duration |
| `2,500 ms` | Media3PlayerEngine.kt | Playback buffer |
| `5,000 ms` | Media3PlayerEngine.kt | Rebuffer buffer |
| `10 s` | Media3PlayerEngine.kt | OkHttp connect timeout |
| `15 s` | Media3PlayerEngine.kt | OkHttp read timeout |
| `250 ms` | Media3PlayerEngine.kt | Position polling interval |
| `1,500 ms` | Media3PlayerEngine.kt | Bandwidth stats interval |
| `MAX_RETRIES = 3` | Media3PlayerEngine.kt | Retry limit |
| `2,000 ms` | Media3PlayerEngine.kt | Retry base delay |
| `0.2f` | Media3PlayerEngine.kt | Audio ducking ratio (20%) |
| `1280×720` | Media3PlayerEngine.kt | Multi-view resolution cap |

### Data Module
| Value | File | Purpose |
|-------|------|---------|
| `30 s` | NetworkTimeoutConfig.kt | Connect/read/write timeout |
| `200 MB` | EpgRepositoryImpl.kt | Max EPG file size |
| `1 hour` | EpgRepositoryImpl.kt | EPG lookback window |
| `2 hours` | EpgRepositoryImpl.kt | EPG lookahead window |
| `60 s` | EpgRepositoryImpl.kt | EPG refresh interval |
| `1,000` | SyncManager.kt | M3U batch size |
| `500` | EpgRepositoryImpl.kt | EPG batch size |
| `3 / 700ms` | SyncManager.kt | Retry attempts / initial delay |
| `120,000` | PreferencesRepository.kt | PBKDF2 iterations |

### App Module
| Value | File | Purpose |
|-------|------|---------|
| `0.15` | StreamVaultApp.kt | Image cache memory ratio |
| `100 MB` | StreamVaultApp.kt | Image disk cache size |
| `6` / `4` | StreamVaultApp.kt | Coroutine parallelism limits |
| `64` / `10` | NetworkModule.kt | OkHttp max requests / per-host |
| `120 ms` | HomeViewModel.kt | Debounce interval |
| `150 ms` / `200 ms` / `500 ms` | Various dialogs | Focus delay magic numbers |
| `30 s` | Cards.kt | EPG progress bar tick rate |
| `1,000 ms` | SkeletonLoader.kt | Shimmer animation duration |

### Recommendation
Create a `ConfigConstants.kt` object in each module or a shared config:
```kotlin
object PlayerConfig {
    const val MIN_BUFFER_MS = 30_000L
    const val MAX_BUFFER_MS = 60_000L
    const val PLAYBACK_BUFFER_MS = 2_500L
    // ...
}
```

---

## 2. State Management Fragmentation in UI Screens

**Severity:** MEDIUM  
**Files:** HomeScreen.kt, SettingsScreen.kt, MoviesScreen.kt, SeriesScreen.kt

**Description:**  
Dialog state management uses multiple independent boolean flags instead of a sealed class:
```kotlin
// Current pattern (HomeScreen):
uiState.showPinDialog
uiState.showDeleteGroupDialog
uiState.showRenameGroupDialog
uiState.showCategoryOptionsDialog
uiState.showMultiViewPlannerDialog
uiState.selectedCategoryForOptions
```

This leads to:
- Risk of multiple dialogs being open simultaneously
- Complex conditional logic to manage transitions
- Difficult to trace dialog state flow

**Recommendation:**  
Use a sealed class for dialog state:
```kotlin
sealed class DialogState {
    object None : DialogState()
    data class PinEntry(val pendingAction: PendingAction) : DialogState()
    data class CategoryOptions(val categoryId: Long) : DialogState()
    data class RenameGroup(val groupId: Long, val currentName: String) : DialogState()
    // ...
}
```

---

## 3. Focus Management — Brittle Exception Swallowing

**Severity:** MEDIUM  
**Files:** PlayerScreen.kt, PlayerControlsChrome.kt, RenameGroupDialog.kt, PinDialog.kt

**Description:**  
Multiple locations use the pattern:
```kotlin
try { focusRequester.requestFocus() } catch (_: Exception) {}
```

This silently swallows all exceptions including unexpected ones. At least 8 instances across the codebase.

**Recommendation:**  
1. Catch only `IllegalStateException` (the expected failure when FocusRequester isn't attached)
2. Log at debug level for diagnostics:
```kotlin
try { 
    focusRequester.requestFocus() 
} catch (e: IllegalStateException) { 
    Log.d(TAG, "Focus request failed: ${e.message}") 
}
```

---

## 4. Inconsistent Error Handling Patterns Across Repositories

**Severity:** MEDIUM  
**Scope:** `domain/src/main/java/com/streamvault/domain/repository/`

**Description:**  
Repository interfaces are inconsistent about whether methods return `Result<T>` or throw exceptions:

| Repository | Returns Result | Throws Exceptions | Returns Flow |
|-----------|---------------|-------------------|-------------|
| ProviderRepository | `addProvider`, `deleteProvider` | - | `getProviders` |
| ChannelRepository | `refreshChannels` | - | `getChannels`, `searchChannels` |
| EpgRepository | `refreshEpg` | - | `getProgramsForChannel` |
| PlaybackHistoryRepository | - | `recordPlayback` (suspend) | `getRecentlyWatched` |
| CategoryRepository | - | `setCategoryProtection` (suspend) | `getCategories` |

Some suspend functions return `Result<T>`, others return raw values and rely on callers to catch exceptions.

**Recommendation:**  
Standardize: all mutation operations return `Result<T>`, all query operations return `Flow<T>`.

---

## 5. Deprecated Methods Not Cleaned Up

**Severity:** LOW  
**Files:** ChannelRepository.kt, MovieRepository.kt, SeriesRepository.kt

**Description:**  
`getStreamUrl()` is deprecated in favor of `getStreamInfo()` but still exists with a default implementation that wraps the new method. These deprecated methods add confusion and code surface area.

**Recommendation:**  
Remove deprecated methods in a release cleanup since all callers should be using `getStreamInfo()` by now.

---

## 6. Performance — Offset-Based Pagination

**Severity:** LOW  
**Files:** MovieRepositoryImpl.kt, SeriesRepositoryImpl.kt (DAO queries)

**Description:**  
Paginated queries use `LIMIT/OFFSET`, which requires scanning all prior rows for each page:
- Page 1: Scans 0 rows, returns 50
- Page 100: Scans 4,950 rows, returns 50

With providers offering 50k-100k movies, deep pagination becomes increasingly slow.

**Current Reality:** Users rarely browse past page 10-20. Not a blocking issue.

**Future Optimization:** Consider keyset/cursor-based pagination for large catalogs.

---

## 7. Performance — EPG Progress Bar Tick Rate

**Severity:** LOW  
**File:** `app/src/main/java/com/streamvault/app/ui/components/Cards.kt`

**Description:**  
Channel cards update EPG progress every 30 seconds via `delay(30_000)`. With many visible channels, this creates periodic recomposition waves.

**Recommendation:**  
Only run the timer for visible cards (LazyList already helps with this, but verify with profiling).

---

## 8. Performance — Channel Grouping In-Memory

**Severity:** LOW  
**File:** `data/src/main/java/com/streamvault/data/repository/ChannelRepositoryImpl.kt`

**Description:**  
Channel grouping creates full in-memory maps:
```kotlin
entities.groupBy { channelGroupKey(it) }
    .values.map { group -> 
        group.sortedWith(compareBy({ it.errorCount }, { it.name.length }))
    }
```

With 10k+ channels per provider, this creates significant temporary allocations.

**Recommendation:**  
Accept for now; optimize with database-side grouping if profiling shows issues.

---

## 9. Missing Database Indexes

**Severity:** LOW  
**File:** `data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt`

**Missing indexes that could improve query performance:**

| Table | Missing Index | Affected Query |
|-------|--------------|----------------|
| Programs | `(provider_id, start_time, end_time)` | Broad EPG time-window queries |
| Categories | `(provider_id, type)` | Category type filtering |
| Virtual Groups | `content_type` | Favorites by content type |

**Recommendation:**  
Add these indexes in the next database migration.

---

## 10. ProGuard — Incomplete Protection Rules

**Severity:** LOW  
**File:** `app/proguard-rules.pro`

**Description:**  
ProGuard rules are well-written but missing explicit protection for:
- `CredentialCrypto` internal class names (could break Keystore alias lookup if reflection used)
- OkHttp certificate validator implementations

**Current Reality:** This likely works fine with standard R8-defaults. Test a release APK to verify.

---

## 11. Compose TV Alpha Dependency

**Severity:** MEDIUM  
**File:** `gradle/libs.versions.toml`

**Description:**  
`androidx.tv:tv-material` is at version `1.0.0-alpha12` — still in alpha. Breaking API changes are possible with updates.

**Risk:** Future updates may require migration effort. Alpha APIs may have bugs or performance issues.

**Recommendation:**  
Pin the version and only update after thorough testing. Document which specific alpha APIs are in use for future migration planning.

---

## 12. Accessibility Gaps for Android TV

**Severity:** MEDIUM  
**Scope:** Entire UI layer

| Feature | Status | Impact |
|---------|--------|--------|
| **Content descriptions** | ✅ Good coverage | Most interactive elements labeled |
| **High contrast mode** | ❌ Not implemented | Low-vision users can't distinguish focus |
| **Reduced motion** | ❌ Not implemented | Shimmer animations can be problematic |
| **Text size configuration** | ❌ Not implemented | Fixed text sizes throughout |
| **Screen reader navigation** | ⚠️ Partial | Focus system works but no TalkBack testing documented |
| **Focus ring visibility** | ⚠️ Borderline | 3dp border may be too faint on large displays |

**Recommendation:**  
At minimum, respect `Settings.Global.ANIMATOR_DURATION_SCALE` for reduced motion, and test with TalkBack enabled.
