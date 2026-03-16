# 03 — Data Layer Issues

Database, parsing, sync, data integrity, and repository implementation issues.

---

## 1. ✅ FIXED — EPG Date Parsing Silently Returns Epoch 0

> See [01_CRITICAL_BLOCKERS.md #13](01_CRITICAL_BLOCKERS.md#13-xml-date-parsing-returns-epoch-0-silently)

---

## 2. ✅ FIXED — M3U Input Not Length-Bounded

> See [01_CRITICAL_BLOCKERS.md #14](01_CRITICAL_BLOCKERS.md#14-m3u-input-not-length-bounded-dos-vector)

---

## 3. ⚠️ MITIGATED — EPG Staging Uses Negative Provider IDs

> See [01_CRITICAL_BLOCKERS.md #5](01_CRITICAL_BLOCKERS.md#5-epg-staging-uses-negative-provider-ids--data-corruption-risk)

---

## 4. ✅ FIXED — Category ID Collision Across Providers

> See [01_CRITICAL_BLOCKERS.md #6](01_CRITICAL_BLOCKERS.md#6-m3u-category-id-collision-across-providers)

---

## 5. ⏭️ DEFERRED — Database Migration 8→9 Unsafe ID Remapping

> See [01_CRITICAL_BLOCKERS.md #7](01_CRITICAL_BLOCKERS.md#7-database-migration-89-id-remapping-can-corrupt-references)

---

## 6. ✅ FIXED — Credentials Can Leak in Logs

> See [01_CRITICAL_BLOCKERS.md #9](01_CRITICAL_BLOCKERS.md#9-credentials-can-leak-into-crash-logs)

---

## 7. ✅ FIXED — No Foreign Key Constraints

> See [01_CRITICAL_BLOCKERS.md #12](01_CRITICAL_BLOCKERS.md#12-no-foreign-key-constraints-in-room-database)

---

## 8. ✅ FIXED — M3U URL Validation Insufficient

**File:** `data/src/main/java/com/streamvault/data/parser/M3uParser.kt` (lines 312–320)  
**File:** `data/src/main/java/com/streamvault/data/sync/SyncManager.kt` (lines 448–451)  
**Severity:** 🟠 HIGH

While `UrlSecurityPolicy.isAllowedImportedUrl()` exists, M3U entries can still contain:
- Encoded newlines (`%0A`, `%0D%0A`) for HTTP header injection
- `file:///` protocol for local file access
- `javascript:` or `data:` URIs (if rendered in any UI context)

**Fix:** After URL parsing, validate with `URI.create()` and explicitly reject non-`http`/`https` schemes:
```kotlin
val uri = try { URI.create(url) } catch (_: Exception) { return null }
if (uri.scheme !in listOf("http", "https")) return null
```

---

## 9. ✅ FIXED — FTS Search Results Silently Truncated at 300

**File:** `data/src/main/java/com/streamvault/data/local/dao/Daos.kt` (lines 83–100)  
**Severity:** 🟠 HIGH

All search queries use `LIMIT 300` with no indication to the user that more results exist. If a provider has 10,000 channels and the search matches 2,000, the user only sees 300 with no "load more" or "showing X of Y" indicator.

**Fix:** Either implement paginated search or return the total count alongside results:
```sql
SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH :query
```

---

## 10. ⚠️ MITIGATED — Large EPG Files Can Cause OOM

**File:** `data/src/main/java/com/streamvault/data/sync/SyncManager.kt` (lines 561–576)  
**Severity:** 🟠 HIGH

The EPG download uses a 64KB `GZIPInputStream` buffer, but for very large EPG files (500MB+ compressed, common with large IPTV providers), the full decompressed XML can exhaust the Java heap on memory-constrained TV devices.

**Mitigation:** The XMLTV parser does use streaming (XmlPullParser), which is good. But the download response body itself may buffer significant amounts. Add:
1. A file size check before download (HEAD request)
2. A maximum file size limit (e.g., 200MB compressed)
3. Progress reporting during EPG parse

---

## 11. ✅ FIXED — PlaybackHistory Restore Uses N+1 Query Pattern

**File:** `data/src/main/java/com/streamvault/data/local/dao/Daos.kt` (lines 248–264)  
**Severity:** 🟠 HIGH

```sql
UPDATE movies SET watch_progress = (
    SELECT resume_position_ms FROM playback_history 
    WHERE playback_history.content_id = movies.id 
    AND playback_history.content_type = 'MOVIE'
    AND playback_history.provider_id = :providerId
) WHERE id IN (...)
```

The correlated subquery executes once per matched movie row. With 10,000+ movies, this query can take several seconds on low-end TV hardware.

**Fix:** Use a JOIN-based UPDATE or batch the operation with explicit chunking.

---

## 12. ✅ FIXED — RecordingManager Race Condition

**File:** `data/src/main/java/com/streamvault/data/manager/RecordingManagerImpl.kt` (lines 50–52)  
**Severity:** 🟠 HIGH

The `stateMutex` protects `itemsState` but `activeJobs` is accessed from multiple coroutines without consistent mutex protection. Multiple concurrent recording operations could cause `ConcurrentModificationException` or lost updates.

---

## 13. ✅ FIXED — M3U Parser Unquoted Attribute Values

**File:** `data/src/main/java/com/streamvault/data/parser/M3uParser.kt` (lines 309–330)  
**Severity:** 🟡 MEDIUM

If an M3U file uses unquoted attribute values with spaces:
```
#EXTINF:-1 group-title=My Group Name,Channel Name
```

The parser will extract only "My" as the group title because whitespace terminates unquoted value parsing. This causes channels to be placed in fragmented/wrong categories.

---

## 14. ✅ FIXED — Adult Content Classifier Is English-Only

**File:** `data/src/main/java/com/streamvault/data/util/AdultContentClassifier.kt` (lines 6–19)  
**Severity:** 🟡 MEDIUM

The classifier uses a hardcoded English keyword list. Content in Spanish, German, French, Russian, Arabic, etc. will bypass classification entirely. For an app with 26 language locales, this is a significant gap in parental controls.

---

## 15. ✅ FIXED — XMLTV Date Format Support Limited

**File:** `data/src/main/java/com/streamvault/data/parser/XmltvParser.kt` (lines 39–43)  
**Severity:** 🟡 MEDIUM

Only 3 date patterns are supported. Real-world XMLTV files use many more formats:
- `20240315` (date only, no time)
- `2024-03-15T15:30:00+01:00` (ISO 8601)
- Timezone offsets with minutes (`+0530`)

Missing formats cause programs to fall through to the epoch-0 fallback.

---

## 16. ✅ FIXED — Backup File Has No Integrity Check

**File:** `data/src/main/java/com/streamvault/data/manager/BackupManagerImpl.kt` (lines 53–101)  
**Severity:** 🟡 MEDIUM

Exported and imported backup files have no checksum, signature, or version validation. A corrupted backup will import corrupted data silently. A backup from an incompatible version could break the database.

**Fix:** Add a CRC32 checksum and a schema version header to backup files.

---

## 17. ✅ FIXED — SimpleDateFormat Thread Safety

**File:** `data/src/main/java/com/streamvault/data/parser/XmltvParser.kt` (lines 39–43)  
**Severity:** 🔵 LOW

```kotlin
private val dateFormats = listOf(
    SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
)
```

`SimpleDateFormat` is not thread-safe. While each parser instance creates its own, if shared or reused across coroutines, date parsing will silently produce wrong results. Consider using `java.time.DateTimeFormatter`.

---

## 18. ✅ FIXED — No Pagination Metadata in Repositories

**File:** `data/src/main/java/com/streamvault/data/repository/MovieRepositoryImpl.kt` (lines 150–162)  
**Severity:** 🟡 MEDIUM

Movie/Series pagination queries don't return the total count. The UI has no way to know if more pages exist, so it either loads too many pages or shows no "load more" option.

---

## 19. ✅ FIXED — PIN Hashing - RNG Initialization

**File:** `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt` (lines 28–31)  
**Severity:** 🔵 LOW

```kotlin
val salt = ByteArray(PIN_SALT_BYTES).also { SecureRandom().nextBytes(it) }
```

Creating a new `SecureRandom()` instance per call may use a weaker default provider on some older Android versions. Using `SecureRandom.getInstanceStrong()` or caching the instance would be more robust.

---

## 20. ✅ N/A — Migration 2→3 Lacks Data Validation

**File:** `data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt` (lines 47–57)  
**Severity:** 🔵 LOW

ALTER TABLE migrations add columns but don't validate whether existing data satisfies any new constraints or defaults that the code expects. This could leave the database in an inconsistent state after upgrade.

---

## 21. ✅ FIXED — Unused Extra Attributes in M3U Entries

**File:** `data/src/main/java/com/streamvault/data/parser/M3uParser.kt` (line 40)  
**Severity:** 🔵 LOW

The parser captures unknown M3U attributes into an `extraAttributes` map that is never consumed anywhere. This adds memory overhead per entry for no benefit. Either remove it or document intended future use.

---

## 22. ✅ N/A — Xtream Unknown Status Not Logged

**File:** `data/src/main/java/com/streamvault/data/remote/xtream/XtreamProvider.kt` (lines 82–90)  
**Severity:** 🔵 LOW

When the Xtream API returns an unknown account status, it maps to `UNKNOWN` without logging. This makes it difficult to diagnose provider compatibility issues.
