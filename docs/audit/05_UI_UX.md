# 05 — UI & UX Issues

Screens, navigation, accessibility, focus management, and presentation issues.

---

## 1. ✅ FIXED — D-Pad Focus Trap Scenarios

**Severity:** 🔴 CRITICAL

Multiple screens have scenarios where the D-Pad focus can become trapped or lost:

### PlayerScreen — Overlay Focus Loss
**File:** `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt` (lines 280–290)

Complex nested overlay state (channel list, category list, EPG, channel info, track selection, program history, split dialog, diagnostics) makes it easy for focus to become lost when overlays are dismissed. No "default focus" fallback exists when the target of `FocusRequester.requestFocus()` is no longer in the composition.

### HomeScreen — Category-Channel Focus Hand-off
**File:** `app/src/main/java/com/streamvault/app/ui/screens/home/HomeScreen.kt` (lines 350–400)

```kotlin
var shouldRestoreCategoryFocus by remember { mutableStateOf(false) }
var shouldRestoreChannelFocus by remember { mutableStateOf(false) }
```

When a user selects a category, focus moves to the first channel. Pressing D-Pad left should return to the category sidebar. But if the category list has scrolled, the target category may not be visible, and `requestFocus()` fails silently.

### MultiViewScreen — Grid Escape
**File:** `app/src/main/java/com/streamvault/app/ui/screens/multiview/MultiViewScreen.kt` (lines 120–140)

If the user navigates out of the 2×2 player grid, there's no safe path back in. No focus restoration is attempted.

### Modal Dialogs — Post-Delete Focus
**PinDialog** and **CategoryOptionsDialog** can dismiss after deleting the underlying content (e.g., deleting a category group). Focus restoration tries to focus the deleted item — fails silently.

---

## 2. ✅ FIXED — Missing Error State UI on Content Screens

**Severity:** 🔴 CRITICAL

### MoviesScreen
**File:** `app/src/main/java/com/streamvault/app/ui/screens/movies/MoviesScreen.kt` (lines 90–110)

```kotlin
if (uiState.isLoading) { /* loading */ }
else if (uiState.moviesByCategory.isEmpty()) { /* empty */ }
else { /* content */ }
// ← No error state branch
```

If the API call fails, the user sees a blank screen with no retry option. Same issue in **SeriesScreen**.

### HomeScreen
No error state if category load fails. No timeout indicator for slow EPG loads. No skeleton loading for the category sidebar.

### FavoritesScreen
No UI for:
- Empty favorites (blank screen)
- All items filtered out by parental controls
- Favorite content that became unavailable

### SearchScreen
When query is under 2 characters, shows blank instead of a search hint or suggestion.

---

## 3. ✅ FIXED — Accessibility — Missing Content Descriptions

**Severity:** 🔴 CRITICAL

### FocusableCard — No Semantics
**File:** `app/src/main/java/com/streamvault/app/ui/components/Cards.kt`

The primary card component used throughout the app has no `contentDescription` parameter and no `Modifier.semantics` block. TalkBack announces "button" with no context for every card.

### ChannelCard — Image Not Labeled
Channel logos have no `contentDescription`. TalkBack cannot announce what channel is focused.

### CategoryRow — No Heading Semantics
```kotlin
Text(text = title, style = MaterialTheme.typography.titleMedium)
```
Missing `Modifier.semantics { heading() }` — TalkBack won't navigate by section headings.

### CustomKeyboard — Keys Not Announced
```kotlin
KeyboardButton(text = key, onClick = { onKeyPress(key) })
```
TalkBack says "button" for every key instead of announcing the letter.

### PinDialog — No Input Feedback
No announcement of current PIN length, no feedback on invalid PIN.

### Player Controls — No State Announcements
Control buttons lack descriptions. No announcement when playback state changes (play/pause/buffering).

---

## 4. ✅ N/A — RTL Layout Not Fully Mirrored

**Severity:** 🟠 HIGH

While `MainActivity` correctly sets `LayoutDirection.Rtl` based on locale, several components don't respond:

### HomeScreen Sidebar
```kotlin
Row(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.width(280.dp)) { /* sidebar - always left */ }
    Column(modifier = Modifier.weight(1f)) { /* content - always right */ }
}
```
In RTL locales (Arabic, Hebrew), the sidebar should be on the right. Currently hardcoded to the left.

### SearchInput Icon
```kotlin
Icon(imageVector = Icons.Default.Search, modifier = Modifier.padding(end = 6.dp))
```
Search icon is always on the left side, should be on the right in RTL.

### CategoryRow
Row header (title on left, "See All" on right) doesn't flip in RTL.

---

## 5. ✅ FIXED — ViewModel Memory Leaks — Jobs Not Cancelled

> See [01_CRITICAL_BLOCKERS.md #8](01_CRITICAL_BLOCKERS.md#8-viewmodels-never-cancel-background-jobs--memory-leaks)

---

## 6. ✅ FIXED — LazyRow/LazyColumn Missing Stable Keys

**Severity:** 🟠 HIGH

### CategoryRow
**File:** `app/src/main/java/com/streamvault/app/ui/components/CategoryRow.kt` (lines 30–50)

```kotlin
LazyRow(...) {
    items(items = items, key = { it.hashCode() }) {  // ← WRONG
        itemContent(it)
    }
}
```

`hashCode()` is not a stable unique key. On data refresh, even identical items get different hash codes → entire row re-composes, causing visible flicker and dropped frames.

**Fix:** Use item's stable ID: `key = { it.id }`

### HomeScreen Channel Grid
```kotlin
LazyVerticalGrid(columns = GridCells.Adaptive(180.dp)) {
    items(channels) { channel ->
        ChannelCard(channel = channel)
    }
}
```
No `key` specified — any data change causes full re-composition of the grid.

### FavoritesScreen
No keys during reorder operations → full re-compose on every reorder step.

---

## 7. ✅ FIXED — Hardcoded English Strings in UI Code

**Severity:** 🟠 HIGH

| File | String | Should Be |
|------|--------|-----------|
| `HomeScreen.kt:1119` | `"Save"` | `stringResource(R.string.action_save)` |
| `HomeScreen.kt:1128` | `"Cancel"` | `stringResource(R.string.action_cancel)` |
| `HomeScreen.kt:405,1190` | `"SPLIT"`, `"MOVE"` | `stringResource(R.string.action_split)` |
| `ReorderTopBar.kt:82` | `"Cancel"` | `stringResource(R.string.action_cancel)` |
| `CustomKeyboard.kt:62` | `"Delete"` | `stringResource(R.string.action_delete)` |
| `SettingsViewModel.kt:195+` | `"Watch history and recents cleared"` | `R.string.settings_cleared_history` |
| `SettingsViewModel.kt` | `"PIN changed successfully"` | `R.string.settings_pin_changed` |
| `PlayerViewModel.kt:210+` | `"The stream stopped responding..."` | `R.string.player_error_*` |

These strings are visible to users and will appear in English regardless of locale, breaking the localization for 25 non-English languages.

---

## 8. ✅ FIXED — Player Error Recovery Not Visible to User

**Severity:** 🟠 HIGH

**File:** `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt`

When playback errors occur:
1. Error is silently logged
2. Decoder mode may be silently switched (software fallback)
3. Channel may be silently changed (stall recovery)
4. User notification (`PlayerNoticeBanner`) may not be visible if controls are hidden

Users have no idea why their stream quality changed, why the channel switched, or what recovery was attempted. A premium app should provide clear, non-intrusive feedback.

---

## 9. ✅ FIXED — DashboardScreen Missing States

**Severity:** 🟡 MEDIUM

**File:** `app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardScreen.kt` (lines 90–120)

- No indicator for provider sync in progress
- No warning banners for provider sync errors
- No "coming soon" placeholder for empty content shelves
- No last-sync-time indicator

---

## 10. ✅ FIXED — String Concatenation Pattern Breaks RTL

**Severity:** 🟡 MEDIUM

**File:** `app/src/main/java/com/streamvault/app/ui/screens/favorites/FavoritesScreen.kt` (lines 718–719)

```kotlin
stringResource(R.string.favorites_continue_short) + " ${summary.continueWatchingCount}"
```

String concatenation doesn't respect RTL text direction. In Arabic, the number should appear on the left, but concatenation always puts it on the right. Use parameterized strings: `"Continue (%1$d)"`.

---

## 11. ✅ FIXED — HomeScreen Back Handler Missing

**Severity:** 🟡 MEDIUM

**File:** `app/src/main/java/com/streamvault/app/ui/screens/home/HomeScreen.kt`

No `BackHandler` composable. When user presses back:
- If channel list overlay is open → should close overlay first
- If in reorder mode → should exit reorder first
- If sidebar is focused → should move to channel grid
- Otherwise → navigate back

Currently relies on system default back behavior which may cause premature exit from the screen.

---

## 12. ✅ FIXED — Focus Restoration Uses `remember` Instead of `rememberSaveable`

**Severity:** 🟡 MEDIUM

```kotlin
var shouldRestoreCategoryFocus by remember { mutableStateOf(false) }
```

Using `remember` means focus state is lost on configuration changes (language change, screen rotation). Should be `rememberSaveable` for robust focus restoration.

---

## 13. ✅ FIXED — No Responsive Layout for Sidebar Width

**Severity:** 🟡 MEDIUM

**File:** `app/src/main/java/com/streamvault/app/ui/screens/home/HomeScreen.kt` (lines 220–280)

```kotlin
Column(modifier = Modifier.width(280.dp)) { /* sidebar */ }
```

Hardcoded 280dp sidebar width. On large TV screens (65"+) this is too narrow; on smaller screens or tablets it may be too wide. Should scale based on screen dimensions.

---

## 14. ✅ FIXED — No Animation on Category/Content Transitions

**Severity:** 🟡 MEDIUM

When selecting a category in HomeScreen, the channel grid content changes instantly with no animation. For premium polish, a crossfade or slide transition would create smoother experience. Player controls do have proper `AnimatedVisibility` — this pattern should be extended to content screens.

---

## 15. ✅ FIXED — Missing Numeric Channel Switching in All Contexts

**Severity:** 🟡 MEDIUM

Quick channel switching via numeric pad (0-9) exists in the player but may not work consistently:
- Works during full-screen playback
- Missing during overlay (EPG, channel list, category list)
- Not available from HomeScreen for direct channel entry

---

## 16. ✅ FIXED — No Loading Skeleton for Category Sidebar

**Severity:** 🔵 LOW

Channel grid has skeleton loading, but the category sidebar shows nothing while loading. Premium apps show shimmer/skeleton placeholders for all loading sections.

---

## 17. ⚠️ MITIGATED — No Wrapping Navigation in Horizontal Lists

**Severity:** 🔵 LOW

When the user reaches the last item in a LazyRow and presses D-Pad right, nothing happens. Premium TV apps typically wrap to the first item or provide a subtle "end of list" indicator.

---

## 18. ✅ FIXED — Search Needs Minimum Query Length Hint

**Severity:** 🔵 LOW

```kotlin
if (query.length < 2) { /* blank UI */ }
```

When the user types a single character, show a hint: "Type at least 2 characters to search" instead of showing nothing.

---

## 19. ✅ FIXED — Multi-View Has No Slot Status Indicators

**Severity:** 🔵 LOW

In multi-view mode (4 players), there's no per-slot buffering indicator, no per-slot error state, and no way to see which slot is currently active without the border highlight.
