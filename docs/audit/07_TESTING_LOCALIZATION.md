# 07 — Testing & Localization Issues

Test coverage, test quality, translations, and resource issues.

---

## Testing

### 1. ⚠️ MITIGATED — Domain & Player Modules Have Zero Test Coverage

**Severity:** 🔴 CRITICAL

| Module | Unit Tests | Instrumented Tests | Status |
|--------|-----------|-------------------|--------|
| `:app` | 4 files | 6 files | ⚠️ Partial |
| `:data` | 7 files | 3 files | ⚠️ Partial |
| `:domain` | **0 files** | **0 files** | ❌ None |
| `:player` | **0 files** | **0 files** | ❌ None |

**Impact:** The most critical modules — business logic (domain) and media playback (player) — have zero automated tests. All bugs are found only through manual testing.

**Untested critical code:**
- `ParentalControlManager` (has known race condition — no test validates this)
- `GetCustomCategories` use case (untested business logic)
- `Media3PlayerEngine` (decoder switching, track selection, error recovery)
- `StreamTypeDetector` (URL-based stream type detection)
- All domain model invariants
- `ChannelNormalizer` algorithm

---

### 2. ⏭️ DEFERRED — Existing Tests Are Surface-Level

**Severity:** 🟠 HIGH

**App module ViewModels:**
- `HomeViewModelTest` — Only tests basic state initialization, not user interactions
- `EpgViewModelTest` — Minimal coverage of EPG state machine
- `SearchViewModelTest` — Tests search query update, not search results

**Missing ViewModel tests:**
- `SettingsViewModel` (contains hardcoded strings, PIN management, sync operations)
- `MoviesViewModel` / `SeriesViewModel` (pagination, filtering)
- `PlayerViewModel` (error recovery, channel switching, multi-view)
- `MultiViewViewModel` (slot management, focus, engine lifecycle)

---

### 3. ⏭️ DEFERRED — No Integration Tests for Critical Flows

**Severity:** 🟠 HIGH

Missing end-to-end scenarios:
- Provider setup → sync → channel display flow
- M3U import → parse → store → display flow
- Parental control PIN → lock → unlock → verify flow
- Backup export → corrupt → import → verify failure handling
- Provider delete → verify cascade cleanup flow
- EPG sync → timezone handling → program display

---

### 4. ⏭️ DEFERRED — No Performance/Stress Tests

**Severity:** 🟡 MEDIUM

No tests for:
- Large M3U files (50,000+ channels)
- Large EPG files (500MB+)
- Rapid channel switching (100 switches in 30 seconds)
- Low-memory conditions
- Concurrent sync for multiple providers

---

### 5. ✅ POSITIVE — Data Layer Tests Have Good Patterns

**Severity:** ✅ POSITIVE

- `SyncManagerTest` — Tests state machine transitions with fakes
- `M3uParserTest` — Covers edge cases and assertions
- `EntityMappersTest` — Validates round-trip mapping
- `ProgramDaoTest` — Room integration tests

These serve as good templates for expanding coverage.

---

## Localization

### 3. ✅ FIXED — Hardcoded English Strings in Code

> See [05_UI_UX.md #7](05_UI_UX.md#7-hardcoded-english-strings-in-ui-code)

**Count:** 15+ hardcoded English strings across ViewModels and Composables that will appear un-translated for 25 non-English locales.

---

### 4. ✅ POSITIVE — 26 Languages Supported — Coverage Appears Good

**Severity:** ✅ POSITIVE

Supported locales:
`ar`, `cs`, `da`, `de`, `el`, `es`, `fi`, `fr`, `hu`, `in`, `it`, `iw`, `ja`, `ko`, `nb`, `nl`, `pl`, `pt`, `ro`, `ru`, `sv`, `tr`, `uk`, `vi`, `zh`

Spot-check of key strings across English, Spanish, French, and German shows proper translations. Parameterized strings use correct `%1$s` / `%1$d` format markers.

---

### 5. ✅ FIXED — String Concatenation Breaks RTL

> See [05_UI_UX.md #10](05_UI_UX.md#10-string-concatenation-pattern-breaks-rtl)

---

## Resources

### 6. ⚠️ MITIGATED — App Icon Missing Density Variants

**Severity:** 🟠 HIGH

| Density | Resolution | Present? |
|---------|-----------|----------|
| ldpi | 36×36 | ❌ |
| mdpi | 48×48 | ❌ |
| hdpi | 72×72 | ❌ |
| xhdpi | 96×96 | ❌ |
| xxhdpi | 144×144 | ❌ |
| xxxhdpi | 192×192 | ✅ |

Only the highest density icon is provided. On lower-density screens, the icon is downscaled from xxxhdpi, resulting in artifacts and an unprofessional appearance. While Android TV devices are typically hdpi/xhdpi, the Play Store listing shows the icon at various sizes.

**Fix:** Use Android Studio's Image Asset Studio to generate all density variants from a source SVG/PNG.

**Note:** The adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`) is properly configured with foreground/background separation, which is good.

---

### 7. ✅ FIXED — Missing ProGuard Rules File

> See [01_CRITICAL_BLOCKERS.md #1](01_CRITICAL_BLOCKERS.md#1-missing-proguard-rulespro--release-builds-will-fail)

---

### 8. ✅ FIXED — No Release Signing Configuration

> See [01_CRITICAL_BLOCKERS.md #10](01_CRITICAL_BLOCKERS.md#10-no-signing-configuration-for-release)

---

## Test Coverage Improvement Priority

| Priority | Area | Estimated Tests | Impact |
|----------|------|----------------|--------|
| 🔴 P0 | `Media3PlayerEngine` | 15–20 tests | Prevents playback regressions |
| 🔴 P0 | `ParentalControlManager` | 10–12 tests | Verifies thread safety, persistence |
| 🔴 P0 | `StreamTypeDetector` | 10 tests | Validates all URL patterns |
| 🟠 P1 | `ChannelNormalizer` | 8–10 tests | Validates grouping algorithm |
| 🟠 P1 | Domain model validation | 15–20 tests | Ensures data integrity |
| 🟠 P1 | `PlayerViewModel` | 12–15 tests | Error recovery, channel switching |
| 🟠 P1 | `SettingsViewModel` | 8–10 tests | PIN, sync, export |
| 🟡 P2 | Navigation integration | 5–8 tests | Back stack, deep links |
| 🟡 P2 | Backup/restore | 5–8 tests | Data integrity |
