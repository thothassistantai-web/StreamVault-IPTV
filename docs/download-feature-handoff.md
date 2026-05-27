# Download Feature Handoff

Date: 2026-05-26

## Current state

Download/offline playback is implemented for movies and individual series episodes. Download actions resolve an initial playable stream URL plus stable provider source metadata, enqueue a `DownloadRequest`, start the foreground notification service, capture stream bytes with OkHttp, persist progress/status in Room, and expose completed files from the Downloads tab.

LocalForge project `streamvalutfork` is back to completed with no follow-up backlog. The repair and final implementation were done manually after LocalForge completed its original 39 features.

## Key implementation paths

- `domain/src/main/java/com/streamvault/domain/model/DownloadModels.kt`
- `domain/src/main/java/com/streamvault/domain/repository/DownloadManager.kt`
- `data/src/main/java/com/streamvault/data/local/entity/DownloadEntity.kt`
- `data/src/main/java/com/streamvault/data/local/dao/DownloadDao.kt`
- `data/src/main/java/com/streamvault/data/manager/DownloadManagerImpl.kt`
- `data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt`
- `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt`
- `app/src/main/java/com/streamvault/app/service/DownloadForegroundService.kt`
- `app/src/main/java/com/streamvault/app/ui/screens/downloads/DownloadsScreen.kt`
- `app/src/main/java/com/streamvault/app/ui/screens/downloads/DownloadsViewModel.kt`
- `app/src/main/java/com/streamvault/app/ui/screens/movies/MovieDetailScreen.kt`
- `app/src/main/java/com/streamvault/app/ui/screens/movies/MovieDetailViewModel.kt`
- `app/src/main/java/com/streamvault/app/ui/screens/series/SeriesDetailScreen.kt`
- `app/src/main/java/com/streamvault/app/ui/screens/series/SeriesDetailViewModel.kt`
- `app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt`
- `app/src/main/java/com/streamvault/app/ui/components/shell/AppShell.kt`
- `tools/smoke_download.ps1`

## Behavior summary

- Movie detail and series episode rows show a Download action.
- Downloads are stored in the user-selected SAF folder when configured.
- Without a selected SAF folder, downloads fall back to app external downloads storage under `Download/StreamVault`.
- `DownloadManagerImpl` captures bytes with OkHttp and updates `DownloadEntity` status/progress.
- `DownloadEntity` stores stable source metadata (`sourceStreamUrl`, `sourceStreamId`, `containerExtension`) so restarted downloads can resolve a fresh provider URL instead of reusing a stale one-time link.
- Downloads are FIFO and single-provider-stream by design.
- If provider-backed VOD playback starts during a download, the active partial file is deleted, progress resets to zero, and the download waits until playback stops before restarting from byte 0 with a fresh URL.
- Playing a completed local download from `content://` or `file://` never pauses the queue, whether the file opens in StreamVault's internal player or an external player.
- `DownloadForegroundService` observes the active download row and updates notification text.
- Downloads tab lists rows, shows poster/status/progress/size, opens completed files via `ACTION_VIEW`, offers Resume for failed rows, and deletes both DB row and local output where possible.

## Verification completed

Commands run from repo root:

```powershell
./gradlew.bat :data:compileDebugKotlin :app:compileDebugKotlin --no-daemon --console=plain
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/smoke_download.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/smoke_external_playback.ps1
./gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Results:

- Data/app debug Kotlin compile: passed
- Download smoke: passed, `SUMMARY: 3/3 tests passed`
- External playback smoke: passed, `SUMMARY: 3/3 tests passed`
- Debug APK build: passed

Debug APK path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Known gaps and next-session checks

- Manual device test still needed with a real provider stream: tap Download, verify notification/progress, verify completed item opens in an external/internal video handler, verify delete removes the file, verify provider playback pauses an active download and restarts it from 0 after playback stops.
- Process-death resume is not implemented. Active capture runs in application-scope coroutine plus foreground service observer; if Android kills the process, incomplete rows remain and should be retried or marked failed in a future feature.
- Pause UI is not implemented. Failed rows expose Resume, which deletes any partial output, resets progress, and restarts through the FIFO scheduler with fresh provider URL resolution.
- The fallback app-external storage path is private to the app; use the Downloads screen to open files or choose a SAF folder for user-visible storage.
- Project-wide `:app:lintDebug` still fails from existing lint debt, including API-level/manifest/test/l10n issues. Compile, smoke, and debug assemble pass.
