# Playback Production Stabilization Plan

## Purpose

This plan is for making the current branch production-ready after discovering that the Fire Cube playback crash was not caused by decoder startup, renderer selection, provider URLs, preview-to-fullscreen, or Media3 buffering. The confirmed fatal crash was an Android 9 SQLite parser failure caused by `INSERT ... ON CONFLICT ... DO UPDATE` in playback compatibility persistence.

The current branch should remain the release candidate. The goal is not to revert the branch to `1.0.9`, and not to restore `1.0.8`. The goal is to keep the useful intent of `1.0.9`, keep the broader current-branch product work, and remove the speculative playback changes that were added while the root cause was still unknown.

## Production Target

The production target is:

Current branch features + safe `1.0.9` playback intent + confirmed SQLite fix + release-safe crash reporting.

Default playback must be boring and reliable. Advanced behavior should be progressive, evidence-based, and bounded.

## Confirmed Facts

- `v1.0.8` at `d66d2f2e2f9da4815cd7c96af65e86d70e8b0f39` was the last known broadly working release.
- `master` / `1.0.9` introduced useful playback ideas but also introduced the Fire OS crash.
- The crash report from release build showed:
  - Device: Amazon AFTKA, Android 9, SDK 28.
  - Exception: `android.database.sqlite.SQLiteException`.
  - Message: syntax error near `ON`.
  - Failing operation: insert/update into `playback_compatibility_records`.
- The confirmed fix is replacing modern SQLite UPSERT syntax with Android-9-safe `UPDATE` + `INSERT OR IGNORE` logic.
- The current dirty branch already contains some good playback corrections:
  - Default `AUTO` renderer plan avoids custom audio sink unless AV sync is enabled.
  - Managed codec selector is no longer intended for normal `AUTO`.
  - SQLite-safe DAO writes are already implemented.
  - Release crash reporting UI exists and proved valuable.
- The current dirty branch still contains risky speculative playback changes:
  - `PlaybackPolicyController` controls runtime startup behavior.
  - Startup guard state can suppress recovery in `PlayerViewModel`.
  - Buffer timings are policy-driven and were changed during debugging.
  - Policy diagnostics are exposed into player diagnostics UI.
  - Known-bad compatibility handling is tied to startup guard state.

## What 1.0.9 Was Trying To Do

`1.0.9` was trying to make playback more adaptive and support more devices:

- Remember bad decoder/device/format/surface combinations.
- Prefer hardware, software, or compatibility codec ordering based on mode.
- Retry software decoding after decoder failures.
- Support audio/video sync offset.
- Detect video stalls and attempt recovery.
- Expose useful diagnostics for support.
- Improve playback failure UX with retries, alternate streams, and notices.

Those are good goals. The problem was not the feature intent. The problem was that too many decisions happened too early, and one support feature was allowed to crash playback.

## Production Principles

### 1. Default `AUTO` must be progressive, not preemptive

`AUTO` should absolutely decide on its own. However, it should make decisions after evidence, not before startup.

The correct `AUTO` sequence:

1. Start with stock Media3 behavior.
2. If decoder initialization fails, retry software once.
3. If playback starts but video stalls, perform bounded stall recovery.
4. If the same decoder/device/format/surface fails repeatedly, avoid that path on later attempts.
5. If playback succeeds, record success and reduce confidence in older failures.

The wrong `AUTO` sequence:

1. Change renderer factory before the stream starts.
2. Wrap audio sink by default.
3. Apply compatibility history before decoder and format are known.
4. Force TextureView because a device might be risky.
5. Block first recovery because startup is inside a guard window.

### 2. Diagnostics must never be fatal

Playback telemetry, compatibility history, crash-report collection, and diagnostics UI must never crash playback. Any database or reporting failure in those paths must be swallowed and logged.

### 3. One recovery layer owns each decision

Decoder fallback, stall recovery, surface fallback, and UI notices must not fight each other. The engine should own low-level playback retries. The ViewModel should own user-facing recovery UX and content-level fallback like alternate streams.

### 4. Compatibility data is advisory

Compatibility records should guide future attempts only after enough evidence. They should not decide first startup behavior on unknown streams.

## Stage 0 - Safety Snapshot And Inventory

### Actions

- Create a safety branch or commit before touching playback files.
- Record the current dirty playback-related files and tests.
- Keep this plan in `docs/playback-production-stabilization-plan.md` as the implementation checklist.

### Why

The branch contains many unrelated product changes. Playback cleanup must not accidentally erase provider sync, onboarding, settings, localization, diagnostics, or UI work.

### Acceptance Criteria

- Current worktree state can be recovered.
- The implementation scope is limited to playback runtime, playback diagnostics, compatibility persistence safety, and tests.

## Stage 1 - Keep Confirmed Fixes

### Actions

- Keep Android-9-safe DAO writes for `playback_compatibility_records`.
- Keep Android-9-safe DAO writes for `search_history`, because it had the same SQLite syntax risk.
- Keep release crash logger, crash report viewer, delete action, and share action.
- Keep FileProvider support needed by crash report sharing.

### Why

These are proven or high-confidence production fixes:

- The playback compatibility DAO fix directly addresses the Fire Cube fatal crash.
- Search history had the same SQLite incompatibility pattern.
- The crash logger turned an impossible random-user report into a concrete root cause.

### Acceptance Criteria

- No main-source SQL contains `ON CONFLICT ... DO UPDATE` or `excluded.`.
- Release crash report screen still compiles and displays the latest crash report.
- Sharing the crash report still uses the Android share sheet.

## Stage 2 - Remove The Speculative Runtime Policy Layer

### Actions

- Stop using `PlaybackPolicyController` from `Media3PlayerEngine`.
- Stop deriving buffer sizes from `PlaybackPolicyController.bufferPolicy()`.
- Stop calling `PlaybackPolicyController.decideAutomaticAction()` before decoder fallback, audio renderer recovery, texture fallback, or stall recovery.
- Remove `startupGuardActive` as a runtime decision.
- Remove `PlayerViewModel.handlePlaybackError()` logic that suppresses normal recovery when policy startup guard is active.
- Remove policy-only diagnostics from active UI state unless keeping inert defaults is simpler for compatibility.

### Why

This layer was created while we believed the crash was startup/render related. It was not. Keeping it risks changing playback behavior without a proven reason. It can also explain later "stuck buffering" reports because it changed buffer policy and recovery timing.

### Acceptance Criteria

- Playback recovery is not blocked by startup guard state.
- `PlayerViewModel` no longer checks `policyStartupGuardActive` to decide whether to handle errors.
- `Media3PlayerEngine` no longer uses `PlaybackPolicyController` to approve or deny recovery.
- Buffer values are no longer sourced from `PlaybackPolicyController`.

## Stage 3 - Rebuild Default Renderer Behavior Cleanly

### Actions

- Keep a renderer plan/helper if useful, but make the behavior explicit and testable.
- For `DecoderMode.AUTO` with AV sync off:
  - Use plain `DefaultRenderersFactory`.
  - Do not create `AudioVideoOffsetAudioSink`.
  - Do not install `PlaybackCodecSelector`.
  - Do not use compatibility DB to filter decoders before playback facts are known.
  - Use `SurfaceView` for `PlayerSurfaceMode.AUTO`.
- For AV sync enabled:
  - Wrap the default audio sink with `AudioVideoOffsetAudioSink`.
  - Do not change decoder selection just because AV sync is enabled.
- For explicit `SOFTWARE`, `HARDWARE`, or `COMPATIBILITY`:
  - Use `PlaybackCodecSelector` to order decoders.
  - `SOFTWARE` prefers software decoders.
  - `HARDWARE` prefers hardware decoders.
  - `COMPATIBILITY` prefers software decoders and may apply existing resolution caps.
- Do not force TextureView just because compatibility mode is selected.

### Why

This keeps the good part of the dirty implementation: default startup is less invasive than `1.0.9`. It also preserves user-controlled decoder modes and AV sync support.

### Acceptance Criteria

- `AUTO` default path is identifiable as stock Media3 in tests or diagnostics.
- AV sync off never instantiates `AudioVideoOffsetAudioSink`.
- Explicit software/compatibility modes still use managed codec ordering.
- Surface mode `AUTO` defaults to SurfaceView.

## Stage 4 - Make `AUTO` Recovery Progressive

### Actions

- Engine-level decoder failure:
  - On first decoder or unsupported-format failure, retry once with software decoding.
  - Do not require user action for the first software retry.
  - Do not loop forever. One automatic software retry per media identity is enough.
- ViewModel-level decoder UX:
  - Keep the existing user notice that software retry is happening.
  - Do not suppress that notice due to startup guard.
- Video stall recovery:
  - Keep `VideoStallDetector`.
  - First stall: reattach/reprepare current player path.
  - Second stall: try software or compatibility only if not already tried for this media.
  - Third stall: surface fallback or user-facing decoder notice.
  - After bounded attempts, stop automatic loops and emit a user-visible error.
- Audio renderer recovery:
  - Keep cooldown-based reprepare.
  - Do not gate it behind startup policy.
  - If it repeats within cooldown, emit a decoder/audio playback error.
- Surface fallback:
  - Keep fallback only after evidence of render/surface failure.
  - Do not automatically switch surface before startup is stable.

### Why

This gives `AUTO` real intelligence while avoiding the risky part of `1.0.9`: preemptive changes before evidence. The result should feel automatic to users but still be debuggable.

### Acceptance Criteria

- First decoder failure triggers exactly one software retry.
- Repeated decoder failures do not loop.
- First stall recovery does not immediately jump to compatibility mode.
- Recovery decisions are ordered and observable in logs/diagnostics.

## Stage 5 - Harden Compatibility History

### Actions

- Wrap `recordCompatibilityFailure()` and `recordCompatibilitySuccess()` repository calls in `runCatching`.
- Log failures with sanitized messages only.
- Keep `currentCompatibilityKey()` strict:
  - No record if decoder is unknown.
  - No record if video mime/resolution are unknown enough to produce noisy data.
  - Include stream type, mime, resolution bucket, decoder, and surface.
- Known-bad application:
  - Apply only after format and decoder are known.
  - Require high confidence, such as multiple failures and no newer success.
  - Do not apply known-bad records during first startup.
- Success recording:
  - Record success once playback is genuinely started, not merely prepared.
  - A newer success should reduce or neutralize a previous failure for the same key.

### Why

The compatibility database should improve future playback. It must not become a second crash vector or a source of bad early decisions.

### Acceptance Criteria

- A database exception from compatibility recording cannot terminate playback.
- Unknown decoder/format data is not recorded as known bad.
- Known-bad records do not affect first startup before actual playback facts exist.

## Stage 6 - Simplify Diagnostics

### Actions

- Keep diagnostics that help support:
  - active decoder name
  - active decoder policy/mode
  - render surface type
  - video stall count
  - last video frame age
  - buffer/rebuffer stats
  - crash report details
- Remove or stop displaying policy-only fields:
  - startup guard active
  - strict device profile
  - policy buffer profile
  - policy render path
  - policy known-bad state
  - policy last action
  - policy auto recovery allowed
- If removing fields causes large UI churn, leave them in data classes with neutral defaults but stop using them for runtime behavior.

### Why

Diagnostics should explain actual playback behavior. Policy-only fields are noise if the policy layer is removed.

### Acceptance Criteria

- Diagnostics UI still helps identify decoder/surface/stall/crash issues.
- No visible diagnostic implies that startup guard is controlling playback.

## Stage 7 - Buffer Policy

### Actions

- Revert policy-driven buffer changes.
- Use simple explicit buffer rules:
  - Live default: conservative enough to zap reasonably, not so high that users see long stuck-buffering.
  - VOD default: stable larger buffer.
  - Compatibility live: may use slightly larger live buffer only when compatibility mode is explicitly active.
- Do not tune buffers further without device testing.

### Recommended Initial Values

- Live normal: `8_000 min`, `30_000 max`, `1_500 playback`, `5_000 rebuffer`.
- Live compatibility: `15_000 min`, `45_000 max`, `1_500 playback`, `5_000 rebuffer`.
- VOD: `50_000 min`, `120_000 max`, `1_500 playback`, `5_000 rebuffer`.

### Why

These are close to `1.0.9` intent while avoiding the later broad dirty change that made normal live playback use compatibility-like buffering.

### Acceptance Criteria

- Normal live playback does not use the later universal `15_000/45_000` policy unless intentionally chosen.
- VOD does not inherit live compatibility buffering.
- Buffer values are easy to find and not hidden behind startup policy.

## Stage 8 - Tests

### Unit Tests

- `PlaybackCodecSelectorTest`
  - AUTO ordering does not alter decoder order when selector is not installed.
  - SOFTWARE prefers software decoders.
  - HARDWARE prefers hardware decoders.
  - COMPATIBILITY prefers software decoders.
  - Known-bad decoders sort lower only when selector is active.
- `DecoderPreferencePolicyTest`
  - AUTO first failure returns SOFTWARE once.
  - Repeated failure for same media returns null.
  - Explicit SOFTWARE and COMPATIBILITY do not retry again.
- `VideoStallDetectorTest`
  - No stall during startup grace.
  - No stall while buffering.
  - Stall only when playback is ready, playing, buffered, and frames stop advancing.
- Renderer plan tests:
  - AUTO + AV sync off = stock path.
  - AUTO + AV sync on = audio sink wrapper only.
  - SOFTWARE/COMPATIBILITY = managed selector.
- Compatibility persistence tests:
  - DAO SQL uses Android-9-safe insert/update.
  - Repository/engine recording failures are swallowed.
- Player recovery tests:
  - startup guard no longer suppresses first decoder retry.
  - first decoder error causes software retry notice.
  - repeated decoder errors stop after bounded attempts.

### Build Checks

- `:player:testDebugUnitTest`
- relevant app player ViewModel tests
- relevant data DAO/repository tests
- `:app:assembleDebug`
- `:app:assembleRelease`

### Manual Device Checks

- Fire Cube / Fire TV:
  - Live TV default settings starts and plays beyond 30 seconds.
  - VOD default settings starts and plays beyond 30 seconds.
  - No crash report is generated on normal playback.
  - If playback fails, crash report screen remains accessible.
- Generic Android TV:
  - Default AUTO still plays live and VOD.
  - Software mode still works when manually selected.
  - AV sync offset still changes sync only when enabled/adjusted.

## Stage 9 - Release Gate

### Required Before Shipping

- Debug build passes.
- Release build passes.
- Targeted player/data/app tests pass.
- No remaining main-source `ON CONFLICT ... DO UPDATE`.
- No runtime use of `PlaybackPolicyController` for startup/recovery decisions.
- Compatibility DB writes are non-fatal.
- Default `AUTO` is confirmed stock on startup.
- Crash report UI can view/share/delete report.

### Recommended Release Notes

- Fixed playback crash on Fire OS / Android 9 devices caused by compatibility history persistence.
- Improved release crash reports so users can view and share diagnostics.
- Improved default playback startup stability.
- Preserved decoder compatibility options for problematic streams/devices.

## Out Of Scope

- Provider URL resolution changes.
- Xtream/Stalker sync behavior changes.
- Preview-to-fullscreen redesign.
- Localization cleanup unrelated to new/changed strings.
- Large player UI redesign beyond diagnostics fields that are removed or neutralized.

## Implementation Order

1. Snapshot current branch.
2. Remove runtime use of `PlaybackPolicyController`.
3. Restore simple buffer selection.
4. Remove startup guard suppression in `PlayerViewModel`.
5. Harden compatibility record writes with `runCatching`.
6. Verify renderer plan behavior for AUTO, AV sync, and explicit decoder modes.
7. Simplify diagnostics fields.
8. Update/add tests.
9. Run debug and release builds.
10. Run graphify update if code files changed.

## Final Expected Behavior

Default users should not need settings changes. On Fire Cube, live TV and VOD should start using normal Media3 behavior. If a decoder problem happens, AUTO should retry software once by itself. If playback stalls, recovery should be bounded and progressive. Compatibility history should help future attempts but never crash playback. Crash reports should remain available for unexpected failures.
