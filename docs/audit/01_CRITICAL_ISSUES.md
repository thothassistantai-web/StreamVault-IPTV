# StreamVault Pre-Release Audit: Critical & High Issues

> **Audit Date:** March 17, 2026  
> **Scope:** Full codebase review across all 4 modules (app, data, domain, player)  
> **Verdict:** App has a strong architectural foundation but has several critical items that must be resolved before production release.

---

## CRITICAL-1: Dead Code — ~2,300 Lines of Unreachable Code in Movies & Series Screens

**Files:**
- `app/src/main/java/com/streamvault/app/ui/screens/movies/MoviesScreen.kt` (Line 192)
- `app/src/main/java/com/streamvault/app/ui/screens/series/SeriesScreen.kt` (Line 192)

**Description:**  
Both `MoviesScreen.kt` (1,342 lines) and `SeriesScreen.kt` (1,364 lines) contain a `return@AppScreenScaffold` at line 192, making everything after that line completely unreachable. This means:
- The full category sidebar with search/filter/reorder functionality (~300+ lines each) is dead code
- Category browsing, library lens navigation (top-rated, fresh, continue-watching), and reorder features are non-functional
- Approximately **1,150 + 1,172 = 2,322 lines** of dead code

**Impact:** Major feature set (VOD category management) is silently broken. Users cannot browse movies/series by category through the sidebar.

**Action:** Either remove the `return@AppScreenScaffold` to restore the feature, or delete the dead code if the new approach (before the return) fully replaces it.

---

## CRITICAL-2: Non-Functional UI Elements — 8 Empty onClick Handlers

**Files:**
- `MoviesScreen.kt` — Lines 951, 961, 971, 1152
- `SeriesScreen.kt` — Lines 983, 993, 1003, 1174

**Description:**  
Eight `VodActionChip` components display data (counts, labels) but have `onClick = { }` — they do nothing when clicked. These include:
- "Continue Watching" lens chips (show item count but don't navigate)
- "Top Rated" lens chips (show pick count but don't filter)  
- "Fresh Movies/Series" lens chips (show count but don't filter)
- Selected category display chips (show title count but don't open category)

**Impact:** Users see interactive-looking chips that respond visually to focus but perform no action. This is a broken UX for a premium app.

**Action:** Implement the lens navigation logic (scroll to section, filter content, etc.) or remove the chips entirely.

---

## CRITICAL-3: Thread Safety — XmltvParser SimpleDateFormat Race Condition

**File:** `data/src/main/java/com/streamvault/data/parser/XmltvParser.kt` (Line ~30)

**Description:**  
```kotlin
private val dateFormats = listOf(
    SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
    SimpleDateFormat("yyyyMMddHHmmss", Locale.US),
).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }
```
`SimpleDateFormat` is not thread-safe. These shared instances are reused across parsing calls without cloning. If concurrent EPG parsing triggers simultaneous date parsing, data corruption or crashes can occur.

**Impact:** Potential crashes, corrupted EPG data, or wrong program times under concurrent load.

**Action:** Clone each `SimpleDateFormat` before use, or migrate to `java.time.format.DateTimeFormatter` (thread-safe by design).

---

## CRITICAL-4: Race Condition — Concurrent EPG Sync Can Corrupt Data

**File:** `data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt` (Lines 113-149)

**Description:**  
EPG sync uses a staging provider ID (`-providerId`) for temporary storage during download. If two syncs run concurrently for the same provider:
1. Both use the same staging ID (`-providerId`)
2. Second sync overwrites the first sync's staged data
3. Final transaction can commit mixed/incomplete data

EPG parsing can take 30+ seconds, creating a large timing window.

**Impact:** Corrupted EPG data — wrong programs displayed for channels.

**Action:** Add a per-provider sync mutex/lock, or use unique staging IDs per sync operation.

---

## CRITICAL-5: Race Condition — Favorite Position Collision

**File:** `data/src/main/java/com/streamvault/data/repository/FavoriteRepositoryImpl.kt` (Line ~102)

**Description:**  
```kotlin
val maxPos = favoriteDao.getMaxPosition(groupId) ?: -1
val favorite = Favorite(position = maxPos + 1, ...)
```
Position lookup and insert are not atomic. Two concurrent `addFavorite()` calls can get the same `maxPos` value, resulting in duplicate positions.

**Impact:** Favorites list ordering breaks; items overlap in display.

**Action:** Wrap in a Room `@Transaction`, or use `INSERT ... SELECT MAX(position)+1`.

---

## HIGH-1: Hardcoded Colors Outside Theme System — MultiViewScreen

**File:** `app/src/main/java/com/streamvault/app/ui/screens/multiview/MultiViewScreen.kt`

**Description:**  
8+ hardcoded hex color values bypass the `AppColors` theme system:
- `Color(0xFF555555)` — Gray placeholder (×2)
- `Color(0xFFFF5252)` — Red error (×2)
- `Color(0xFFFFC107)` — Amber blocked (×1)
- `Color(0xFFFFE082)` — Light amber reason (×1)
- `Color(0xFF4CAF50)` — Green status (referenced)
- `Color.Black.copy(alpha=...)` — Semi-transparent overlays (×2)

**Impact:** Theming/rebranding is inconsistent; these colors won't adapt to any future light/dark theme changes.

**Action:** Replace with `AppColors` semantic tokens (e.g., `AppColors.ErrorColor`, `AppColors.Warning`, `AppColors.TextSecondary`).

---

## HIGH-2: Hardcoded Colors — DashboardScreen Shortcuts

**File:** `app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardScreen.kt` (Lines ~351-353)

**Description:**  
`DashboardShortcutType` uses hardcoded colors:
- `0xFFFFC857` (Favorites)
- `0xFF4FD1C5` (Recent)  
- `0xFF60A5FA` (Last Group)

**Action:** Map to `AppColors` tokens.

---

## HIGH-3: Missing Android TV Platform Integration

**Description:**  
Several expected Android TV platform features are completely absent:

| Feature | Status | Impact |
|---------|--------|--------|
| **Voice Search** (SearchManager / ACTION_ASSIST) | ❌ Not implemented | Users can't search by voice on the home screen |
| **Watch Next** (TvProvider integration) | ❌ Not implemented | App doesn't appear in the TV home screen's "Watch Next" row |
| **Recommendation Channels** | ❌ Not implemented | No content surfaces on the Android TV launcher |
| **TV Input Framework** (TvInputService) | ❌ Not implemented | App can't integrate with the system TV guide |
| **Chromecast/Cast Support** | ❌ Not implemented | Can't cast to other devices |

**Impact:** These are differentiators for a premium TV app. Watch Next and recommendations especially drive engagement by surfacing content on the Android TV home screen.

**Action:** Prioritize Watch Next integration and voice search as minimum requirements for a premium TV experience.

---

## HIGH-4: Parental Control — In-Memory Only, No Persistence

**File:** `domain/src/main/java/com/streamvault/domain/manager/ParentalControlManager.kt`

**Description:**  
`ParentalControlManager` stores unlocked categories in memory only:
- State is lost on app restart, process kill, or activity recreation
- No integration with a repository for persistence
- No unlock expiration/timeout (once unlocked, stays unlocked until process dies)
- No brute-force protection (no attempt tracking or lockout)

**Impact:** Users must re-enter PIN on every app launch for every locked category. No protection against PIN guessing.

**Action:**
1. Add session-scoped persistence (encrypted preferences with TTL)
2. Implement attempt tracking with lockout (e.g., 5 attempts → 15 min lockout)
3. Add configurable unlock timeout (e.g., 30 min auto-relock)

---

## HIGH-5: ConcurrentHashMap Check-Then-Act Race in SeriesRepository

**File:** `data/src/main/java/com/streamvault/data/repository/SeriesRepositoryImpl.kt` (Lines ~32-39)

**Description:**  
```kotlin
private val xtreamProviderCache = ConcurrentHashMap<Long, CachedXtreamProvider>()

private fun getOrCreateXtreamProvider(...): XtreamProvider {
    val cached = xtreamProviderCache[providerId]
    if (cached != null && cached.signature == signature) return cached.provider
    // Both threads can reach here simultaneously
    return XtreamProvider(...).also {
        xtreamProviderCache[providerId] = CachedXtreamProvider(...)
    }
}
```
The check-then-act pattern is not atomic despite using `ConcurrentHashMap`.

**Impact:** Multiple unnecessary XtreamProvider creations; wasted CPU on password decryption.

**Action:** Use `ConcurrentHashMap.computeIfAbsent()` or add a synchronized block.

---

## HIGH-6: SyncManager State Race — Multi-Provider Sync Overwrites Status

**File:** `data/src/main/java/com/streamvault/data/sync/SyncManager.kt` (Line ~64)

**Description:**  
A single `_syncState` StateFlow is shared across all provider syncs. When syncing multiple providers concurrently:
1. Provider A starts → state = `Syncing("Live TV...")`
2. Provider B starts → state = `Syncing("Movies...")` (overwrites A's status)
3. Provider A finishes → state = `Success` (but B is still running)

**Impact:** UI shows incorrect sync status; user thinks sync is complete when it isn't.

**Action:** Track per-provider sync state, or serialize provider syncs.

---

## HIGH-7: CredentialCrypto Silent Failure on Decryption

**File:** `data/src/main/java/com/streamvault/data/security/CredentialCrypto.kt`

**Description:**  
When decryption fails (corrupted data, key rotation, device factory reset), the method returns an empty string silently. No warning is shown to the user that their credentials need re-entry.

**Impact:** User experiences mysterious login failures without understanding the cause.

**Action:** Return a `Result` type or throw a specific exception that the UI can translate to "Please re-enter your credentials."

---

## HIGH-8: Network Error Classification — JSON vs Network vs Auth Errors

**File:** `data/src/main/java/com/streamvault/data/remote/xtream/OkHttpXtreamApiService.kt` (Line ~36)

**Description:**  
```kotlin
private suspend inline fun <reified T> get(endpoint: String): T {
    // ...
    json.decodeFromString<T>(body)  // Could throw JsonException
}
```
All failures (network timeout, HTTP 401/403 auth errors, malformed JSON) are treated as generic `IOException`. The retry logic (`SyncManager.retryTransient`) then retries non-retryable errors like authentication failures.

**Impact:** Wasted retries on auth errors; wrong error messages shown to users.

**Action:** Catch and classify errors separately: network (retryable), auth (non-retryable), parsing (non-retryable).
