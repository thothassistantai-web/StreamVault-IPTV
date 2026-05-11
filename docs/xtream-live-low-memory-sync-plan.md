# Xtream Live Low-Memory Sync Plan

Date: 2026-05-10

## Goal

Improve Xtream Live reliability on weak Android TV / Fire TV devices without making categories lazy-empty and without changing VOD behavior.

The app should still build a complete Live TV catalog during provider sync. Categories should not appear as empty shells that only load when clicked.

## Current Position

The full Xtream Live path is already mostly memory-safe:

```text
response.body.byteStream()
-> bounded stream
-> Gson JsonReader
-> read one JSON array element
-> decode to XtreamLiveStreamRow
-> collect device-sized raw batch
-> map batch to Channel
-> stage ChannelEntity rows
-> clear batch
-> commit staged catalog at end
```

Current device batch sizes:

```text
LOW: 100 rows
MID: 300 rows
HIGH: 500 rows
```

The main remaining issue is policy: low devices may still attempt the provider-wide `get_live_streams` endpoint first. Even when streamed, that endpoint can be risky because the provider returns one huge response.

The category-by-category path already exists and uses the same thin live row DTO shape, but it currently holds one category result in memory before returning it to the strategy.

## Terminology

Use these names consistently in settings, logs, and tests:

```text
STREAM_ALL: provider-wide get_live_streams
CATEGORY_BY_CATEGORY: get_live_streams&category_id=X for each category
AUTO: app chooses based on device, sync reason, provider health, and hidden categories
```

## Non-Goals

- Do not implement click-to-load categories.
- Do not leave category channel counts unknown until clicked.
- Do not make categories appear empty after provider add.
- Do not change VOD, movies, or series sync in this plan.
- Do not download all logos during sync.
- Do not bulk-load EPG during Live catalog sync.

## Target Behavior

For low-memory devices:

```text
Add/sync Xtream provider
-> authenticate
-> get live categories
-> skip provider-wide get_live_streams
-> sync live channels category-by-category
-> save the same thin live catalog rows
-> provider becomes usable with populated categories
```

For lazy work, only keep these lazy:

```text
logos: image cache loads when visible
EPG: short EPG loads when guide/player needs it
playback URL: resolved only when user presses Play
playback compatibility/errors: updated locally after playback attempts
```

## Advanced Setting: Xtream Live Sync Method

Advanced settings should expose an Xtream Live sync method with three choices:

```text
AUTO
CATEGORY_BY_CATEGORY
STREAM_ALL
```

Recommended meaning:

```text
AUTO:
- default for normal users
- low devices use CATEGORY_BY_CATEGORY
- low-memory runtime state uses CATEGORY_BY_CATEGORY
- provider stress / avoid-full metadata can select CATEGORY_BY_CATEGORY
- foreground initial sync on high devices may use STREAM_ALL if safe

CATEGORY_BY_CATEGORY:
- force category-by-category Live sync
- still produces a complete Live catalog
- uses the same thin row persistence as STREAM_ALL

STREAM_ALL:
- force provider-wide get_live_streams when technically allowed
- mostly useful for debugging or known-small providers
- should still use the streaming parser and thin rows
- should still fall back to CATEGORY_BY_CATEGORY if the full response fails
```

The setting must not enable click-to-load categories. Even forced `CATEGORY_BY_CATEGORY` means the app syncs all eligible visible categories during provider sync.

## Sync Reason Policy

Not every sync reason should use the same method.

Recommended policy:

```text
Initial provider add / foreground onboarding:
- AUTO follows device/provider policy
- LOW: CATEGORY_BY_CATEGORY
- MID: consider CATEGORY_BY_CATEGORY for safer onboarding
- HIGH: STREAM_ALL is allowed in AUTO if provider is healthy

Scheduled background sync / stale refresh:
- use CATEGORY_BY_CATEGORY in AUTO
- skip hidden categories when possible
- preserve already stored hidden category channels instead of deleting them
- avoid provider-wide get_live_streams even on high devices

Manual Sync button in Settings:
- use CATEGORY_BY_CATEGORY in AUTO
- skip hidden categories when possible
- preserve hidden stored rows
- if user explicitly forced STREAM_ALL, honor it when safe, but still fallback to CATEGORY_BY_CATEGORY on failure

Repair / diagnostics / developer-oriented force sync:
- may honor STREAM_ALL for testing known-small providers
- should log clearly that STREAM_ALL was user-forced
```

Rationale:

- Background sync has no user-facing need to race through a full provider-wide response.
- Hidden categories are user intent; fetching them repeatedly wastes memory, network, and provider capacity.
- Category-by-category gives better progress, better partial recovery, and easier skip logic.
- The force setting should exist for control/debugging, not as the normal path for weak devices.

## Phase 1: Low Device Category-First Policy

Make low devices skip full Live catalog sync and go directly to category-by-category Live sync.

Recommended policy:

```text
LOW device:
- full get_live_streams: skipped
- category concurrency: 1
- batch size: 100

MID device:
- keep existing behavior initially, or optionally category-first for initial onboarding
- category concurrency: 2
- batch size: 300

HIGH device:
- keep existing full streaming-first behavior initially
- fallback to category-by-category if full fails
- batch size: 500
```

Likely implementation point:

```kotlin
CatalogSyncRuntimeProfile.shouldAttemptFullLiveCatalog(...)
```

Conceptual policy:

```kotlin
fun shouldAttemptFullLiveCatalog(isInitialLiveOnboarding: Boolean): Boolean =
    tier != DeviceSyncTier.LOW && !snapshot.isCurrentlyLowOnMemory
```

Optional onboarding-safe policy:

```kotlin
if (isInitialLiveOnboarding && tier == DeviceSyncTier.MID) return false
```

Expected benefit:

- Avoids the single huge Live provider response on weak devices.
- Uses existing category sync machinery.
- Keeps a complete Live catalog.
- Small, low-risk first patch.

## Phase 1B: Add Sync Method Selection

Add a persisted advanced setting for Xtream Live sync method:

```text
AUTO
CATEGORY_BY_CATEGORY
STREAM_ALL
```

The effective method should be resolved from:

```text
user setting
device tier
current low-memory state
sync reason
provider avoid-full metadata
hidden category state
```

Suggested resolver shape:

```kotlin
fun resolveXtreamLiveSyncMethod(
    userMode: XtreamLiveSyncMode,
    runtimeProfile: CatalogSyncRuntimeProfile,
    syncReason: SyncReason,
    metadata: SyncMetadata,
    hiddenLiveCategoryIds: Set<Long>
): EffectiveXtreamLiveSyncMethod
```

This keeps policy out of the low-level fetcher and makes tests easier.

## Phase 2: Batch-Stage Category Streaming

Refactor category-by-category Live sync so each category uses the same staged batch approach as full streaming.

Current category shape:

```text
get_live_streams&category_id=X
-> stream rows
-> collect all rows for this category
-> map category rows
-> return category channels
-> strategy flattens category results
-> commit/upsert later
```

Preferred category shape:

```text
get_live_streams&category_id=X
-> stream row
-> collect 100/300/500 rows
-> map batch
-> stage ChannelEntity rows
-> clear batch
-> continue same category
-> next category
-> commit staged catalog at end
```

This matters for providers with giant categories such as:

```text
All
Live
Uncategorized
General
```

Expected benefit:

- Category-first becomes robust even if one category contains thousands of channels.
- Avoids holding a full category result in memory.
- Keeps the same thin DTO and persisted catalog shape.

Design note:

The current category strategy returns `CatalogStrategyResult<Channel>` with channel lists. To fully batch-stage while reading, the category strategy likely needs a staged result path that returns:

```text
stagedSessionId
acceptedCount
categories
warnings
strategyFeedback
```

instead of carrying all channels back in memory.

This phase is explicitly the refactor: category-by-category should stage batches while reading each category. It is separate from simply choosing category-by-category as the sync method.

## Phase 3: Adaptive Provider Memory Policy

Remember providers that should avoid full Live catalog sync after failures or stress signals.

Existing metadata already has concepts like:

```text
liveAvoidFullUntil
liveSequentialFailuresRemembered
liveHealthySyncStreak
```

Desired behavior:

```text
If full Live sync fails because response is too large, parse-stressed, low-memory, timeout, or reset:
-> mark provider as segmented/category-first for a cooldown window
-> next sync skips full get_live_streams
```

Low devices should not depend on this adaptive path; they should be category-first deterministically.

The adaptive policy should feed AUTO mode. It should not override an explicit CATEGORY_BY_CATEGORY user choice. An explicit STREAM_ALL choice can request full sync, but full sync should still fail over to category-by-category if the full response is unsafe or fails.

## Phase 4: Progress And UX Tuning

Keep sync progress useful but not noisy.

Recommended progress pattern:

```text
Downloading Live TV by category 0/N...
Downloading Live TV by category 5/N...
Downloading Live TV by category 10/N...
```

Avoid:

```text
Progress update per channel
Compose state update per channel
Logo or EPG loading during Live catalog import
```

Provider add should still communicate that Live TV is importing if the catalog is not yet committed.

## Phase 5: Tests

Add or adjust tests around:

```text
LOW profile skips full get_live_streams
LOW profile requests get_live_streams with category_id
LOW profile uses category concurrency 1
category sync persists same thin live row fields as full sync
failed full sync records avoid-full metadata
category staged streaming handles large category without returning all rows
hidden categories remain preserved/merged correctly
initial onboarding state still completes after category-first sync
AUTO resolves background/stale sync to category-by-category
AUTO resolves Settings manual sync to category-by-category
forced CATEGORY_BY_CATEGORY skips full get_live_streams
forced STREAM_ALL attempts full get_live_streams when safe
forced STREAM_ALL falls back to category-by-category on unsafe full failure
hidden categories are skipped during category sync and preserved locally
```

Useful existing test area:

```text
data/src/test/java/com/streamvault/data/sync/SyncManagerXtreamLiveStrategyTest.kt
data/src/test/java/com/streamvault/data/sync/SyncManagerTest.kt
```

## Verification Commands

Run focused tests first:

```text
./gradlew :data:testDebugUnitTest --tests "*SyncManagerXtreamLiveStrategyTest*"
```

Then compile app Kotlin:

```text
./gradlew :app:compileDebugKotlin
```

Before release confidence:

```text
./gradlew assembleRelease
```

## Recommended Order

1. Phase 1 first: low device category-first policy.
2. Phase 1B: add AUTO / CATEGORY_BY_CATEGORY / STREAM_ALL policy resolver and advanced setting.
3. Validate existing category sync behavior on tests and compile.
4. Phase 2 next: batch-stage category streaming.
5. Add adaptive provider memory policy after the deterministic low-device path is stable.
6. Polish progress/status UX only after the sync behavior is correct.
