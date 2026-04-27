# Changelog

All notable product changes are recorded in this document.

## [1.0.9] - 2026-04-25

### Added

#### Player
- A/V sync offset controls — global and per-channel overrides; accessible from the channel info overlay (live) and player controls overlay (VOD).
- Compatibility Mode — improved decoder/surface fallback for stalled video playback.
- Video Surface selection — choose Auto, SurfaceView, or TextureView in Playback settings for device-specific rendering issues.
- Time format preference (System default, 12-hour, 24-hour) in Settings.
- Sleep timers — Stop Playback After and Allow Standby After Idle, with presets, countdowns, and warning overlays; defaults saved in Playback settings.
- Auto-Play Next Episode for series — 10-second **Up Next** countdown with Play Now and Cancel; toggle in Playback settings (on by default).

#### VOD
- Infinite scroll preference for Movies and Series — auto-load more rows and posters while browsing, or keep a manual Load More button.

### Fixed

- Fixed excessive live TV buffering from 1.0.8 — minimum buffer threshold reduced to 8 seconds (was effectively 30 seconds).
- Fixed inconsistent AM/PM and 24-hour timestamps — selected time format now applied consistently across Guide, Player overlays, Home, History, Dashboard, and Settings.
- Fixed EPG guide cutting off at 60 channels per category — additional channels now load automatically on scroll.
- Fixed a crash when opening Settings → EPG Sources on some devices by making the EPG source and provider list item keys unique.
- Fixed background EPG sync from stalling Live TV category loading after provider add.
- Fixed Stalker live sync loading large `get_all_channels` catalogs in a streaming, batched path to avoid out-of-memory crashes.
- Fixed Stalker live sync when portal category requests fail — the app now stages bulk live channels first and recovers fallback categories instead of leaving Live TV empty.
- Fixed Stalker native EPG fallback on portals that ignore per-channel `ch_id` requests and keep returning the full bulk guide — sync now detects the broken response shape and stops repeating the large download loop.
- Fixed modern VOD/series shelves to load on demand in smaller visible batches, with paged Stalker category loading and throttled Xtream category previews.
- Fixed Stalker series details using local numeric IDs instead of provider-native series IDs, which broke series info loading on portals that use composite IDs.
- Fixed Stalker series titles being overwritten by season-shell or numeric fallback names when detail payloads omitted the real title.
- Fixed shell-only Stalker series seasons to follow up with derived `season_id` requests and use explicit episode commands when the portal provides them.
- Fixed shell-based Stalker episode playback on stricter portals by passing the episode number into `create_link` instead of always using `series=0`.

---
## [1.0.8] - 2026-04-22

### Added

- Added resume action on movie and series detail screens — shows formatted position and episode context (e.g. "Resume · S2 E3 · 23:45").

### Fixed

- Fixed Pro-mode Live TV preview to fullscreen handoff so live playback can continue more smoothly without media-session crashes.
- Fixed manual EPG add flow crashing instead of showing a proper error.
- Fixed M3U/M3U8 playlists using `#EXTGRP` loading all Live TV channels into one group.
- Fixed Stalker live sync over-requesting channels on provider add.
- Fixed Stalker EPG sync making redundant requests for duplicate or placeholder guide keys.
- Fixed Stalker background EPG sync skipping the bulk portal request and going straight to per-channel fetches.
- Fixed new Stalker providers defaulting to upfront EPG sync instead of background sync.
- Fixed Stalker playback on portals requiring MAG-style headers or fresh direct-URL resolution.
- Fixed Stalker probe failures and HTTP `456` errors being reported too generically.
- Fixed Xtream fast sync leaving movie and series categories temporarily empty.
- Fixed VOD browse pagination skipping items or leaving groups incomplete.
- Fixed VOD resume position, episode progress, and continue-watching state not updating reliably.
- Fixed silent audio when a stream's codec (e.g. EAC3, AC3) is unsupported — player now surfaces a clear error.
- Fixed Xtream Live TV taking a very long time to load on first sync with large providers (50k+ channels).

---

## [1.0.7] - 2026-04-20

### Added

- Added Stalker Portal provider support.

### Fixed

- Fixed startup crashes on some Android 9 devices when secure preference storage fails to initialize.
- Fixed Picture-in-Picture setup on devices that report invalid or unsupported video aspect ratios.
- Fixed launch crashes for some upgrades from 1.0.4 or earlier caused by orphaned provider content left behind by older deletes.
- Fixed first-frame video freezes on some devices in fullscreen, live preview, and multiview playback.
- Fixed some playback failures not retrying after video had already started.

---

## [1.0.6] - 2026-04-19

### Fixed

- Fixed crash on upgrade from 1.0.4 to 1.0.5. The database migration FK integrity check (`PRAGMA foreign_key_check`) was running against the entire database instead of only the tables each migration modified. Users with any pre-existing orphaned rows (deleted provider history, stale EPG mappings, etc.) would hit an `IllegalStateException` and the app would crash on launch. Fresh installs were unaffected. The check is now scoped to the specific tables each migration writes to.

---

## [1.0.5] - 2026-04-16

### Added

#### Live Playback & DVR
- Added live rewind (timeshift) for live channels — up to 30 minutes buffer.
- Added timeline scrubber with live-edge indicator and seek controls.
- Added recording, catch-up, and restart controls directly in the player overlay.
- Added DASH stream support to the rewind engine.

#### Performance & Browsing
- Added incremental channel loading for large providers.
- Added incremental search results with automatic loading on scroll.
- Added automatic paging when reaching the end of the channel list.
- Added HTTP conditional requests (`ETag` / `If-Modified-Since`) to EPG refresh — unchanged feeds return `304 Not Modified` and skip download and parse entirely.
- Added EPG channel icon fallback — channels without a provider logo now use the icon from their matched EPG source.

#### System & Settings
- Added timeshift storage manager with automatic cleanup.
- Added auto-return on failed channels toggle.
- Added automatic update download option.
- Added options to show/hide “All Channels” and “Recent Channels”.
- Added "Hidden" option to Live TV Channel Numbering — hides channel numbers everywhere in the app (channel lists, player overlay, EPG, zap banner).
- Added live channel grouping settings with grouped/raw modes, grouped label style, and variant preference options.
- Added live channel variant selection in the player overlay, separate from adaptive stream quality selection.
#### Recording
- Schedule recordings directly from the EPG guide — Record, Record Daily, and Record Weekly buttons on any future program.
- Configurable pre/post recording padding (start early / end late) — applied automatically to all scheduled recordings.
- WiFi-only recording restriction — optionally prevent recordings from starting on mobile data.
- HLS recording quality cap — respects the per-network video quality preference instead of always picking the highest bandwidth variant.
- Recording conflict resolution dialog — shows which recordings overlap and offers to replace them instead of just showing an error.
- Recording status indicators on channel cards — REC and Scheduled badges on Dashboard and Search screens.
- Skip single occurrence of a recurring recording without cancelling the series.
- Search and filter in the recordings browser by title, channel, or status.
- Stalled-capture watchdog — recordings that stop receiving data for 60 seconds are automatically aborted.
- Automatic retry with exponential backoff on transient network failures during capture.
- Low disk space pre-flight check — scheduled recordings fail early with a clear message instead of writing partial files.
- Push notification on recording failure with channel name and reason.
- Completed recordings open in the player with resume position tracking.
#### VOD
- "Filter & Sort" button now shows active selections as a subtitle (e.g. "Favorites · Rating") and highlights with brand colour when non-default settings are applied — applies to both Movies and Series, modern and classic views.

#### Search
- Long-pressing a channel, movie, or series in Search now opens a context menu with Add/Remove Favorites, Hide, and Parental Lock/Unlock actions — consistent with the same actions available in other screens.

#### Parental Controls
- Added **PRIVATE** protection level — adult categories appear in the sidebar with a PIN lock, but are excluded from Search, EPG Guide, All Channels, and Recent so individual channel names (e.g. explicit titles) are never shown in mixed lists.
- PRIVATE is now the default protection level for new installs.
- Existing users with the old HIDDEN level are automatically migrated to the new HIDDEN (level 3); users previously on LOCKED remain on LOCKED.
- Protection level selection dialog upgraded to a title + description card layout matching other mode-selection dialogs.
- LOCKED level now correctly shows adult content in all views (EPG, Search, All Channels, Recent) — it previously blocked adult content from the EPG guide despite that not being the intended behaviour.

#### Database & Sync
- Search now indexes title, cast, director, and genre with BM25 relevance ranking — results are ordered by match quality.
- Program reminders — tap any guide entry to be notified before a show starts without scheduling a full recording.
- Watch progress and favorites are unified across providers for the same TMDB title — resume a movie on any provider from where you left off.
- Provider catalogs now enforce per-provider row limits to prevent malformed feeds from consuming device storage.
- EPG channel match quality is tracked per channel; channels with weak or missing EPG assignments surface in provider settings.
- Database diagnostics in Settings showing storage usage, per-table row counts, fragmentation level, and maintenance history.
- Background catalog and EPG sync jobs now survive process death and retry automatically on network recovery.
- Search history is now per content-type, timestamped, and stored in the database.
---

### Improved
- Faster loading and lower memory usage for large channel lists.
- More stable playback when switching channels.
- Live channel grouping now keeps raw provider rows intact and builds logical channels with explicit variants at read time.
- Live variant quality parsing now recognizes more tags, including `2K`, `576p`, `540p`, `fullhd`, `ultra hd`, and `uhd`.
- Live variant preference modes now support tag-first ranking and an observed-only mode based on actual playback telemetry.
- Channel grouping sub-options in Settings are visually nested under the main grouping control for clarity.
- Numeric channel entry now accepts up to 6 digits before resetting, which supports larger provider lineups.
- Better handling of live stream buffering and recovery.
- Improved recording settings layout with cleaner action grouping and a dedicated recordings browser dialog.
- Improved update UI feedback during downloads.
- EPG source delete now requires confirmation to prevent accidental removal.
- Refreshing an EPG source shows a per-source loading indicator instead of a global spinner, so multiple sources can refresh independently.
- Settings screen content scrolls up correctly when the keyboard opens, keeping text fields visible while typing.
- EPG feeds with non-UTF-8 encodings (ISO-8859-1/2, Windows-1252, etc.) now parse correctly — channel names and programme titles no longer appear garbled.
- EPG download read timeout increased from 30 s to 120 s for large or slow feeds.
- Catalog sync for large providers is significantly faster — field changes are diffed in Kotlin and applied in a single batched update instead of per-column correlated subqueries.
- EPG program data is now retained for the full catch-up window of each channel (up to 7 days) instead of a fixed 24-hour cutoff.
- EPG staging writes for large feeds are committed in a single transaction, cutting write time for 50 000-program feeds by roughly 10×.
- Series sync now skips unchanged series using server-provided last-modified timestamps, reducing API traffic on repeat refreshes.
- EPG and Xtream API responses are cached using server Cache-Control and ETag headers, eliminating redundant downloads on unchanged feeds.
- Channel and VOD browse lists now use keyset pagination — no items are skipped or duplicated during concurrent syncs and deep pages load faster.
- Movie and series watch-count sort no longer runs a subquery per row — watch count is stored on the content record.
- Playback progress is batched and flushed on a 30-second interval instead of writing to the database on every player tick.
- Partial sync no longer resets the provider refresh timer — only a complete successful sync marks a provider as up to date.

---

### Fixed

#### Playback
- Fixed incorrect channel info after auto-revert.
- Fixed repeated auto-revert causing channel switching loops.
- Fixed last-channel behavior after fallback.
- Fixed RTSP stream playback handling.
- Fixed MPEG-TS playback issues (black screen / corrupt frames).
- Fixed live buffer seeking returning incorrectly.
- Fixed player crashes on some devices.

#### Streaming & Stability
- Fixed stream detection for Xtream channels.
- Fixed DRM handling causing repeated retries.
- Fixed expired stream URLs during playback.
- Fixed heavy live-channel grouping work running too close to UI input paths by moving repository presentation transforms off the main thread.
- Fixed Xtream live recordings failing when provider direct-source URLs expired mid-recording.
- Fixed behind-live-window recovery issues.
- Fixed playback restarting due to EPG updates.

#### UI & Behavior
- Fixed update status stuck on “Downloading”.
- Fixed numeric channel input issues (0 handling).
- Fixed Picture-in-Picture activating incorrectly.
- Fixed missing channel handling after playlist refresh.
- Fixed active recording failures disappearing silently from the player without a notice.
- Fixed garbled average-speed placeholder text in recording details.
- Fixed favorites and custom groups being shared across unrelated providers. Combined M3U sources now merge member-provider favorites and groups in Home and Guide while saving changes back to the original provider.
- Fixed EPG category fallback logic.

#### EPG & Guide
- Fixed `.gz` EPG feeds being double-decompressed and failing to parse.
- Fixed blank URL accepted past the scheme validator when adding an EPG source.
- Fixed EPG list always appearing empty after a fresh install (list was never populated into the UI state).
- Fixed EPG sync rejecting plain HTTP URLs — M3U playlist header EPG (`x-tvg-url`), manual EPG sources, and M3U provider EPG fields now accept HTTP and HTTPS, matching the same policy already applied to Xtream providers.
- Fixed EPG search not escaping `%` and `_` wildcards, returning too many results on certain queries.
- Fixed "now & next" picking the wrong next program when programs had gaps.
- Fixed EPG channel-matching trimming whitespace inconsistently between ID index build and lookup.
- Fixed EPG source refresh not recording errors on failure, leaving stale "refreshing" status.
- Fixed EPG source refresh replacing live data non-atomically — writes now go to a staging area and swap in a single transaction.
- Fixed EPG override-candidates query loading all channels into memory instead of filtering in SQL.
- Fixed `MAX_EPG_SIZE_BYTES` duplicated across two repositories — now defined once in shared config.
- Fixed EPG source refresh not being rate-limited, allowing rapid repeated fetches.
- Fixed `jumpToDay` and `jumpToPrimeTime` using UTC epoch modulo instead of local-timezone arithmetic.
- Fixed guide timeline and program times using hardcoded 24-hour format instead of respecting the device locale.
- Fixed program progress bar gated on `isNowPlaying` flag instead of computing from timestamps.
- Fixed `EpgUiState` using `System.currentTimeMillis()` in default field values, causing inconsistent anchor times.
- Fixed guide search query not debounced, firing a database query on every keystroke.
- Fixed `nowTicker` flow restarting a new timer per collector instead of sharing a single upstream.

#### VOD
- Fixed "New to Old" sort not working for Movies. The `added` timestamp is now correctly used across the full pipeline, matching how series sorting works.

#### Provider Sync
- Fixed sync failing with "Login succeeded, but the initial sync failed" for live-only providers with no VOD or series content.
- Fixed providers with fast sync enabled showing **Partial** status incorrectly — providers now show **Active** when fast sync completes as intended.
- Fixed VOD-only providers (no live TV) failing on first sync when the server returns an empty live-streams response.

#### Database & Cache
- Fixed watch status not updating correctly after history was cleared — unwatched state is now read from the authoritative history table.
- Fixed stale data left behind when a provider was deleted while a sync was in progress.
- Fixed a background EPG job leaking a phantom reference when the job completed before it was registered.
- Fixed "now playing" showing an ended program on long-lived guide screens — current time is refreshed on each display tick.
- Fixed a forward clock jump suppressing cache refreshes beyond the corrected window.
- Fixed FTS search queries with unbalanced quotes or SQLite reserved operators causing crashes — malformed input is now sanitized before reaching the database.
- Fixed EPG provider mutex entries not being removed on provider deletion, leaking memory over long sessions.
- Fixed VACUUM maintenance running concurrently with an active sync, causing spurious sync failures.
- Fixed VACUUM pre-flight silently truncating the WAL file — replaced with a non-destructive passive checkpoint.
- Fixed favorites reordering issuing one database write per item — reordering now uses a single bulk update.
- Fixed category protection not being cleared when category IDs were remapped during a provider catalog restructure.
- Fixed sync session ID collisions after a clock correction — sessions now use random identifiers.
- Fixed database migrations that rebuild tables not verifying foreign key integrity before completing.
---

### Notes
- Live rewind works without provider catch-up support.
- Large playlists now load progressively instead of all at once.

## [1.0.4] - 2026-04-11

### Improved

#### Playback & Streaming
- Improved VOD playback compatibility with provider-resolved streams.
- Improved handling of expired Xtream stream URLs with automatic retry.
- Improved live-stream retry behavior and recovery.

#### TV Guide & Browsing
- Favorites and custom groups now appear in the TV Guide.
- Guide can now start on a selected default category.
- Improved category fallback when channels are missing.
- Improved browsing experience for Movies and Series categories.

#### Settings & UX
- Split Settings into separate Playback and Browsing sections.
- Improved provider sync with faster quick-sync and clearer progress feedback.
- Added Base64 compatibility toggle for Xtream providers.
- Improved About screen with build verification.

---

### Fixed

#### Playback
- Fixed playback failures for expired or invalid stream URLs.
- Fixed RTSP/RTMP casting failing silently.
- Fixed player startup crashes in some cases.
- Fixed audio-only streams not starting correctly.
- Fixed seek-preview thumbnails not clearing properly.

#### EPG & Guide
- Fixed EPG refresh stability and fallback behavior.
- Fixed EPG updates causing unnecessary UI refreshes.
- Fixed stale EPG data overwriting active channels.

#### DVR & Scheduling
- Fixed recurring recording conflicts and scheduling issues.
- Fixed DST-related recording time drift.
- Fixed invalid recordings being scheduled from outdated guide data.

#### Providers & Data
- Fixed Xtream playlist detection and import handling.
- Fixed M3U catch-up and replay support.
- Fixed provider sync inefficiencies and repeated loading.
- Fixed provider deletion cleanup and background sync issues.

#### Updates & Security
- Fixed update downloads getting stuck.
- Enforced HTTPS for update downloads.
- Improved APK integrity verification before install.
- Fixed URL validation edge cases.

#### UI & Behavior
- Fixed navigation focus issues in TV UI.
- Fixed numeric channel input handling.
- Fixed empty-state handling for offline libraries.
- Fixed long-press dialog accidental actions.

#### Performance & Stability
- Reduced memory usage during large operations (EPG, backups, search).
- Improved database maintenance behavior to avoid blocking operations.
- Reduced unnecessary UI recompositions and background work.

## [1.0.3] - 2026-04-09

### Added

#### DVR & Recording
- Added full DVR support with scheduled and background recording.
- Added conflict detection, persistence, and recovery handling.
- Added in-player recording controls and live recording indicator.

#### Providers
- Added combined M3U live sources with merged-provider profiles.
- Added provider management and active source selection in Settings.
- Added optional in-app provider/source browser for Live TV.

#### Localization
- Added broader locale support with improved typography handling.

---

### Improved
- Improved recording and provider settings UI for better TV navigation.
- Improved playback of recordings (now treated as local media).
- Improved storage handling with safe default folders and optional custom locations.

---

### Fixed
- Fixed recording failures/crashes on newer Android versions.
- Fixed D-pad navigation and click issues in recording settings.
- Fixed focus and styling issues in combined-provider dialogs.
- Fixed incorrect or broken translations across some locales.

## [1.0.2] - 2026-04-08

### Added

#### Updates & Settings
- Added in-app update support with download and install.
- Added playback troubleshooting controls (Media Session, decoder preference, timeouts).

#### Providers & M3U
- Added bulk category controls (Hide All / Unhide All).
- Added per-provider M3U VOD classification with refresh support.

---

### Improved
- Improved M3U EPG detection with broader header support and better parsing.
- Improved XMLTV and M3U ingestion to handle malformed feeds more reliably.
- Improved provider setup flows with clearer separation between M3U and Xtream options.
- Improved playback timeout settings with simpler controls.
- Improved player initialization and decoder fallback behavior.

---

### Fixed
- Fixed M3U imports missing EPG URLs due to BOM issues.
- Fixed provider sync to skip hidden categories and reduce overhead.
- Fixed category visibility controls not preserving existing hidden states.
- Fixed M3U sync inconsistencies across refreshes.
- Fixed XMLTV parsing errors on some real-world feeds.

## [1.0.1] - 2026-04-08

### Added

#### EPG Management
- Added manual EPG matching directly from the guide.
- Added EPG source priority controls in Settings.

---

### Improved
- Improved EPG resolution across playback, guide, and Home.
- Improved EPG refresh and assignment to update immediately after changes.
- Improved XMLTV support including gzip-compressed feeds.

---

### Fixed
- Fixed M3U imports not consistently carrying EPG sources.
- Fixed manual EPG overrides not persisting correctly.
- Fixed guide and playback ignoring resolved EPG mappings in some cases.
