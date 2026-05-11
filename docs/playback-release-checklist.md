# Playback Release Checklist

## What Changed

- Fixed Fire OS / Android 9 playback-start crashes caused by SQLite UPSERT syntax in playback compatibility persistence.
- Added release-safe local crash reporting so users can view, share, and delete the latest fatal crash report.
- Restored boring default playback startup:
  - `AUTO` starts with stock Media3 behavior
  - custom AV sync sink is used only when AV sync is enabled
  - managed codec selection is reserved for explicit decoder modes
- Made automatic recovery bounded:
  - one automatic decoder fallback per media in `AUTO`
  - bounded stall recovery
  - bounded surface fallback
- Made compatibility history advisory only:
  - non-fatal reads and writes
  - no recording for unknown decoder/mime/resolution
  - no known-bad influence on clean first startup
- Simplified diagnostics to show actual runtime playback information instead of removed policy-era fields.

## Build Gate

- `:player:testDebugUnitTest`
- `:data:testDebugUnitTest --tests com.streamvault.data.repository.PlaybackCompatibilityRepositoryImplTest`
- `:app:assembleDebug`
- `:app:assembleRelease`

## Manual QA

### Fire Cube / Fire TV

- Play live TV with default settings and confirm playback continues past 30 seconds.
- Play VOD with default settings and confirm playback continues past 30 seconds.
- Confirm no crash report is created during normal playback.
- If playback fails intentionally or unexpectedly, confirm the latest crash report can be viewed, shared, and deleted from Settings.

### Generic Android TV

- Play live TV with default settings and confirm normal startup.
- Play VOD with default settings and confirm normal startup.
- Manually switch decoder mode to `SOFTWARE` and confirm playback still starts.
- Enable AV sync adjustment and confirm playback still starts and the offset is applied only when enabled.

## Notes

- The app `debugUnitTest` lane still has a pre-existing Hilt generated-source failure unrelated to this playback stabilization work. This did not block `assembleDebug` or `assembleRelease`.
- This branch contains substantial unrelated in-flight changes, so final ship confidence should include a normal regression sweep outside playback.
