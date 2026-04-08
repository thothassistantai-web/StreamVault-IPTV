# Changelog

All notable product changes are recorded in this document.

## 1.0.3 - 09/04/2026

### Added
- Added a full DVR workflow with scheduled and background recording, conflict detection, recording persistence, repair/reconcile support, and app-managed default storage for recordings.
- Added combined M3U live source support, including merged-provider profiles, active source selection, and provider-management controls in Settings for building combined Live TV sources.
- Added an optional Live TV provider/source browser for M3U sources, with compact in-browser switching and the setting disabled by default.
- Added in-app playback actions for completed recordings, plus an on-player recording indicator so active captures remain visible during playback.
- Added broader shipped locale coverage together with locale-aware typography fallbacks to improve multilingual rendering across the TV UI.

### Changed
- Updated provider and recording settings surfaces with improved TV focus treatment, white focus strokes, fixed combined-provider dialogs, and much denser recording cards and action layouts.
- Updated local playback handling so completed recordings open through local-file-capable player data sources and are treated as local media instead of live transport streams.
- Updated recording storage and playback flows to default to safe app-managed folders while still supporting custom storage selection when users want it.

### Fixed
- Fixed Android foreground-service crashes affecting manual and scheduled recording starts on newer Android versions by correcting the recording start path and required service permissions.
- Fixed recording rows and settings controls that were oversized, not fully navigable by D-pad, or not clickable in the recording settings surface.
- Fixed combined-provider focus styling gaps so provider management buttons and add-playlist dialogs now match the rest of the TV interface.
- Fixed corrupted and poorly rendered translated strings across shipped locales by refreshing locale resources and falling back to safer typography where needed.

## 1.0.2 - 08/04/2026

### Added
- Added in-app update discovery and download support backed by GitHub Releases, including cached release metadata, dashboard update callouts, Settings update controls, and APK install handoff through a FileProvider.
- Added bulk category visibility controls in Provider Category Controls with provider-wide `Hide All` and `Unhide All` actions.
- Added per-provider M3U VOD classification controls, including setup-time configuration, a persisted provider flag, and a refresh action to rearrange imported content after toggling the rules.
- Added playback troubleshooting controls in Settings for `System Media Session`, saved decoder mode preference, clearer playback timeout labels, and direct numeric timeout entry.
- Added targeted regression coverage for M3U header BOM handling, XMLTV relaxed parsing, and broader M3U header EPG alias support.

### Changed
- Updated M3U header EPG discovery to accept additional aliases such as `x-tvg-url`, `url-tvg`, `tvg-url`, and `url-xml`, while handling comma-separated guide URLs more reliably.
- Updated M3U and manual XMLTV ingestion to use a more tolerant shared parser so malformed entity text in otherwise usable feeds no longer fails as aggressively.
- Updated the add-provider and provider settings flows so Xtream-only advanced options stay hidden for M3U sources and provider-specific M3U behavior is configured where users expect it.
- Updated playback timeout settings to use clearer live-versus-VOD wording and a simpler typed-seconds workflow instead of long option pickers.
- Updated player initialization so main playback can react live to `MediaSession` and decoder preferences, with improved decoder fallback behavior when software extensions are available.

### Fixed
- Fixed M3U playlist imports that could miss header-declared EPG URLs when the playlist started with a UTF-8 BOM.
- Fixed provider sync so hidden live categories are skipped during Xtream live refresh and ignored during EPG resolution work, reducing unnecessary sync overhead.
- Fixed provider category control management to support bulk hide and restore flows without disturbing categories that were already hidden.
- Fixed M3U provider sync and setup behavior so discovered provider EPG URLs, VOD classification, and category rearrangement stay consistent across refreshes.
- Fixed XMLTV source refreshes that previously failed on some real-world feeds with `unterminated entity ref` parser errors.

## 1.0.1 - 08/04/2026

### Added
- Added manual EPG match management directly from the full guide, including channel-level override selection and a quick return to automatic matching.
- Added EPG resolution coverage summaries and source priority controls in Settings so provider assignments can be reviewed and reordered without leaving the app.
- Added targeted regression coverage for manual EPG source assignment behavior, M3U `url-tvg` header parsing, and gzip-compressed XMLTV imports.

### Changed
- Updated live playback EPG loading to prefer resolved multi-source mappings for current, next, history, and upcoming programme data before falling back to provider-native lookups.
- Updated Home live channel now-playing badges to use the resolved EPG pipeline, aligning the home surface with the guide and player.
- Updated EPG source refresh and assignment flows to immediately re-resolve affected providers after source refresh, enable/disable, delete, assign, unassign, and priority changes.
- Updated provider XMLTV refresh to accept both standard XMLTV files and gzip-compressed `.xml.gz` feeds discovered from M3U playlist headers.

### Fixed
- Fixed M3U playlist imports so header-declared `url-tvg` and `x-tvg-url` feeds are carried through into provider EPG refresh more reliably.
- Fixed manual overrides so they persist as explicit external mappings instead of being lost during normal assignment updates.
- Fixed guide and playback consumers that previously bypassed resolved mappings and could ignore external or manually overridden EPG matches.
