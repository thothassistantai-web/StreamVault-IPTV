# External Playback And Copy URL Validation

## Completed State

- Latest committed StreamVault work: `ae26790 Add external playback mode`.
- Debug install conflict fix is applied:
  - `app/build.gradle.kts` sets debug `applicationIdSuffix = ".debug"` and `versionNameSuffix = "-debug"`.
  - `app/src/main/AndroidManifest.xml` uses `android:label="@string/app_name"`.
  - `app/src/debug/res/values/strings.xml` defines `StreamVault Debug`.
- Verified debug APK built with package `com.streamvault.app.debug` and label `StreamVault Debug`.
- Copy URL works for movie and series playback URLs.
- Direct VLC playback works after resolving provider URLs before external launch.

## External Playback Fix

- External launch happens in `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt`.
- `streamUrl` can be an internal/logical URL like `xtream://...` or `stalker://...`, not the resolved playable URL.
- `ExternalPlayerLauncher` correctly rejects `xtream` and `stalker`.
- `PlayerViewModel.prepare()` resolves playable URLs through `resolvePlaybackStreamInfo(...)` and stores:
  - `currentResolvedPlaybackUrl = preparedStreamInfo.url`
  - `currentResolvedStreamInfo = preparedStreamInfo`
- `PlayerViewModel.externalPlaybackUrl` exposes the resolved playable URL to Compose.
- `PlayerScreen` now waits for `externalPlaybackUrl` and does not fall back to raw logical provider URLs.
- `beginPlaybackSession()` clears stale resolved URL/state before each prepare.

## Copy URL Fix

- `MovieDetailScreen.kt` has `Copy URL` next to movie `Play` and uses `MovieDetailViewModel.resolveCopyStreamUrl()`.
- `SeriesDetailScreen.kt` has `Copy URL` for resume episode and episode rows, using `SeriesDetailViewModel.resolveCopyStreamUrl(episode)`.
- Copied URLs resolve through repository/provider preparation before hitting clipboard.
- Live channels do not currently have a separate detail page with a Play button; they launch directly from `HomeScreen`, so no live Copy URL button was added in this pass.

## Verification

- `gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain` passed.
- `gradlew.bat :app:testDebugUnitTest --tests com.streamvault.app.player.external.* --no-daemon --console=plain` passed.
- `gradlew.bat :app:assembleDebug --no-daemon --console=plain` passed.
- APK badging:
  - package: `com.streamvault.app.debug`
  - label: `StreamVault Debug`
- User installed/ran debug app and confirmed:
  - Copy URL works.
  - Direct VLC playback works.

## Follow-Up

- External-player mode still prepares internal playback in the background after launching the chooser/player. Next fix should keep internal playback stopped unless no external app is chosen.
- Remove `Ask every time` from Default playback app because external mode already falls back to internal playback when no external player is selected.
