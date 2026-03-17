# StreamVault Pre-Release Audit: Missing Features & Completeness

> **Audit Date:** March 17, 2026  
> **Focus:** Missing functionality, partial implementations, feature gaps for a premium IPTV player

---

## 1. Player Module — Missing Premium Features

### 1.1 No Quality/Bitrate Selection [HIGH]

**Current:** Player uses Media3's default adaptive bitrate selection with no user control.  
**Expected:** Premium IPTV players allow users to:
- View available quality options (Auto, 1080p, 720p, 480p, etc.)
- Lock to a specific quality
- Set quality preferences per network type (WiFi vs Ethernet)

**Implementation Path:** Use `DefaultTrackSelector.parameters.setMaxVideoSize()` with user preference. The `constrainResolutionForMultiView` boolean already demonstrates this capability.

### 1.2 No Playback Speed Control [MEDIUM]

**Current:** No speed controls exposed.  
**Expected:** 0.5x, 0.75x, 1x, 1.25x, 1.5x, 2x for VOD content.  
**Implementation Path:** `ExoPlayer.setPlaybackParameters(PlaybackParameters(speed))` — trivial to add.

### 1.3 No Audio Track Language Auto-Selection [MEDIUM]

**Current:** Audio track can be selected manually but doesn't default to user's locale.  
**Expected:** Auto-select audio track matching device/app language.  
**Implementation Path:** Use `DefaultTrackSelector.Parameters.setPreferredAudioLanguage()`.

### 1.4 No Subtitle Styling [LOW]

**Current:** Subtitle track can be toggled but styling (size, color, background) is not configurable.  
**Expected:** Standard TV apps allow subtitle customization per accessibility requirements.

### 1.5 No Stream Failover [MEDIUM]

**Current:** `Channel.alternativeStreams` exists as `List<String>` but the player doesn't use it.  
**Expected:** When primary stream fails, automatically try alternative stream URLs.  
**Implementation Path:** On `PlayerError.NetworkError`, try next URL from `alternativeStreams` before showing error.

### 1.6 No Seek Thumbnails / Trick Play [LOW]

**Current:** Seek bar shows position but no visual preview.  
**Expected:** Premium players show thumbnails while scrubbing for VOD content.

### 1.7 No DRM Support [LOW for IPTV]

**Current:** No Widevine, PlayReady, or ClearKey DRM scaffolding.  
**Expected:** Some premium IPTV providers use DRM-protected streams.  
**Note:** Most IPTV providers don't use DRM. Lower priority unless targeting premium content providers.

---

## 2. Domain Module — Severely Underdeveloped Use Case Layer

### 2.1 Only 1 Use Case Exists [HIGH]

**Current:** `GetCustomCategories` is the only use case in the entire domain layer.  
**Expected:** A well-architected app should have 15-25+ use cases encapsulating business logic.

**Critical missing use cases:**

| Use Case | Purpose |
|----------|---------|
| `SearchContent` | Cross-provider, cross-content-type search |
| `GetContinueWatching` | Smart resume list with series awareness |
| `MarkAsWatched` | Explicit watched marking with progress threshold |
| `ValidateAndAddProvider` | Full validation → login → sync orchestration |
| `SyncProvider` | Orchestrated sync with progress and error handling |
| `GetRecommendations` | Based on watch history and favorites |
| `ScheduleRecording` | With conflict detection and storage checks |
| `ExportBackup` / `ImportBackup` | With encryption and conflict resolution |
| `UnlockParentalCategory` | PIN validation with attempt tracking |

**Impact:** Business logic is spread across ViewModels and repositories instead of being centralized in testable use cases.

### 2.2 Domain Models Missing Fields [MEDIUM]

| Model | Missing Field | Purpose |
|-------|--------------|---------|
| `Program` | `rating` | Content rating (PG-13, R, etc.) |
| `Program` | `imageUrl` | Program thumbnail for EPG grid |
| `Program` | `genre` / `category` | EPG filtering by genre |
| `Channel` | `qualityOptions` | Available stream qualities |
| `StreamInfo` | `expirationTime` | Token-based stream URL expiry |
| `StreamInfo` | `drmInfo` | DRM license/config |
| `PlaybackHistory` | `watchedStatus` | Explicit watched vs auto-detected |
| `Provider` | `apiVersion` | Xtream API version compatibility |

### 2.3 Backup Data Incomplete [MEDIUM]

**File:** `domain/src/main/java/com/streamvault/domain/manager/BackupManager.kt`

`BackupData` is missing:
- Parental control PIN (critical — lost on restore)
- Recording schedules
- Blocked channels/categories
- Playback preferences (quality, subtitle language)

### 2.4 Repository Interface Gaps

**Missing methods across repositories:**

| Repository | Missing Method | Purpose |
|-----------|---------------|---------|
| ChannelRepository | `getChannelsByNumber()` | EPG guides organize by channel number |
| ChannelRepository | `getChannelsWithoutErrors()` | Skip problematic streams |
| EpgRepository | `searchPrograms()` | Find programs across all channels |
| EpgRepository | `getProgramsByCategory()` | Category-based EPG browsing |
| MovieRepository | `getRecommendations()` | Based on watch history |
| MovieRepository | `getRelatedContent()` | Similar genre/cast |
| PlaybackHistoryRepository | `markAsWatched()` | Explicit watched marking |
| PlaybackHistoryRepository | `getUnwatchedCount()` | For series tracking |
| LibraryBrowseQuery | `sortBy` field | Name, date, rating, watch count |
| LibraryBrowseQuery | `filterBy` field | Unwatched, incomplete, language |
| LibraryBrowseQuery | `searchQuery` field | Combined search + browse |

---

## 3. Data Module — Implementation Gaps

### 3.1 Watch Progress Inconsistency [MEDIUM]

**Description:**  
Movie watch progress is duplicated between two locations:
- `movies.watch_progress` column (updated via `movieDao.updateWatchProgress()`)
- `playback_history.resume_position_ms` (updated during playback)

These can get out of sync. Episode watch progress in particular may show `watch_progress=0` in the episodes table while `playback_history` has a valid resume position.

**Action:** Single source of truth — either use playback_history exclusively, or add a sync mechanism.

### 3.2 Stale Favorite Cleanup Not Automated [MEDIUM]

**Description:**  
`FavoriteRepositoryImpl` has methods to clean up favorites for deleted content:
- `deleteMissingLiveFavorites()`
- `deleteMissingMovieFavorites()`
- `deleteMissingSeriesFavorites()`

But these are **never called** by any visible code path. The `SyncWorker` → `DatabaseMaintenanceManager` chain doesn't seem to invoke them.

**Impact:** Favorites for deleted channels/movies/series remain in the database forever as orphaned entries.

**Action:** Wire cleanup methods into the maintenance worker or post-sync hook.

### 3.3 Xtream Expiration Date — Limited Format Support [LOW]

**File:** `data/src/main/java/com/streamvault/data/remote/xtream/XtreamProvider.kt`

Only 2 date formats are tried (`yyyy-MM-dd HH:mm:ss`, `yyyy-MM-dd`). Some Xtream providers use additional formats. Also missing timezone handling (assumes local time instead of UTC).

### 3.4 Search Results Not Ranked [LOW]

**Description:**  
FTS4 search queries return results in arbitrary order. Room FTS4 doesn't provide ranking by default.

**Impact:** Search for "Sports" may show "Sports Bar" before "Sports Channel" with no relevance ordering.

**Recommendation:**  
Consider adding a simple relevance heuristic (exact match first, prefix match second, contains match third).

---

## 4. Test Coverage Gaps

### 4.1 Current Test Inventory

| Module | Unit Tests | Android Tests | Coverage Estimate |
|--------|-----------|---------------|-------------------|
| **domain** | 3 files (25 tests) | 0 | ~10% |
| **data** | 6 files | 3 files | ~25% |
| **app** | 4 files | 4 files | ~15% |
| **player** | 1 file (18 tests) | 0 | ~20% |
| **TOTAL** | **14 files** | **7 files** | **~15-20%** |

### 4.2 Critical Missing Tests

| Test Category | What's Missing | Priority |
|--------------|----------------|----------|
| **Security** | CredentialCrypto encrypt/decrypt, UrlSecurityPolicy validation, ProviderInputSanitizer bounds | HIGH |
| **Database Migrations** | Full v1→v13 data integrity verification | HIGH |
| **Error Paths** | Network failure handling, malformed data recovery, timeout behavior | HIGH |
| **Player Engine** | Playback lifecycle, error recovery, audio focus, track selection | MEDIUM |
| **ViewModels** | MoviesViewModel, SeriesViewModel, SettingsViewModel, PlayerViewModel | MEDIUM |
| **Repositories** | Full CRUD operations with edge cases | MEDIUM |
| **Integration** | Provider login → sync → display → playback end-to-end flow | LOW |
| **UI/Golden** | More composable snapshot tests for visual regression | LOW |

### 4.3 CI/CD Test Pipeline Issues

- `--continue` flag masks individual test failures
- No code coverage reporting (Jacoco)
- No release build testing  
- No ProGuard/R8 validation for release APK

---

## 5. Recording System — Interface vs Implementation Gaps

### 5.1 RecordingManager Implementation Exists ✅

The `RecordingManagerImpl` is fully implemented with:
- Manual recording start/stop
- Scheduled recording
- Storage state monitoring
- Active job management

### 5.2 Missing Recording Features [LOW]

| Feature | Status |
|---------|--------|
| Recurring recordings (daily/weekly) | ❌ Not implemented |
| Recording conflict detection | ❌ Not implemented |
| Automatic storage cleanup | ❌ Not implemented |
| Pause/resume recording | ❌ Not implemented |
| Series recording rules | ❌ Not implemented |

---

## 6. Backup System — Interface vs Implementation Gaps

### 6.1 BackupManager Implementation Exists ✅

The `BackupManagerImpl` is fully implemented with:
- Export with checksum verification
- Import with conflict detection
- Version handling

### 6.2 Missing Backup Features [LOW]

| Feature | Status |
|---------|--------|
| Encrypted backup | ❌ Not implemented |
| Cloud backup (Google Drive) | ❌ Not implemented |
| Automatic scheduled backup | ❌ Not implemented |
| Selective restore (only favorites, only providers, etc.) | ❌ Not implemented |

---

## 7. Localization Completeness

**Status:** ✅ Well-implemented — 25+ languages supported via translation scripts.

All user-facing strings use `stringResource(R.string.*)`. Only non-localizable symbols (`+`, `!`, `•`) are hardcoded.

**Note:** RTL support (Hebrew, Arabic) was implemented in Phase 5 with proper mirroring.

---

## 8. Summary: Feature Readiness Matrix

| Feature Area | Readiness | Blocking? |
|-------------|-----------|-----------|
| Live TV Playback | 90% | No |
| VOD (Movies/Series) | 80% | **Yes** — dead code in category browsing |
| EPG Guide | 85% | No |
| Provider Management | 90% | No |
| Favorites & Groups | 85% | No |
| Parental Controls | 70% | **Yes** — in-memory only |
| Search | 75% | No |
| Multi-View | 80% | No |
| Settings | 85% | No |
| Player Controls | 75% | No — but missing quality selection |
| Recording | 60% | No — functional but basic |
| Backup/Restore | 70% | No — functional but missing PIN backup |
| Android TV Platform | 50% | **Yes** — missing Watch Next, voice search |
| Accessibility | 60% | No — functional but gaps |
| Test Coverage | 20% | **Yes** — too low for production confidence |
