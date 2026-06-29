# Release process

How to cut a **StreamVault** release for sideload or GitHub Releases.

## Version bump

Edit `app/build.gradle.kts`:

```kotlin
versionCode = 30000    // monotonic integer — required for in-app updates
versionName = "3.0.0"  // user-visible semver
```

Update [CHANGELOG.md](CHANGELOG.md) — add a dated `## [x.y.z] - YYYY-MM-DD` section at the top.

## Prerequisites

| Tool | Version |
|------|---------|
| JDK | 17+ |
| Android SDK | API 36 (`ANDROID_HOME` set) |
| Release keystore | Optional; unsigned APK builds without `keystore.properties` |

Optional: `keystore.properties` at project root (gitignored):

```properties
storeFile=keystore/release.jks
storePassword=<secret>
keyAlias=streamvault
keyPassword=<secret>
```

For CI signing, configure GitHub Actions secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

## Build commands

### Local Gradle

```bash
cd StreamVault-IPTV
export ANDROID_HOME=~/Android/Sdk
./gradlew clean testDebugUnitTest :app:assembleRelease
```

Produces:

| Artifact | Path |
|----------|------|
| Release APK | `app/build/outputs/apk/release/app-release.apk` (or `-unsigned` without keystore) |
| Beta APK | `./gradlew :app:assembleBeta` → `app/build/outputs/apk/beta/` |

### GitHub Actions (recommended)

Stable releases are **manual only** — they do not publish on every push.

1. Bump `versionCode` / `versionName` and update `docs/CHANGELOG.md`.
2. Commit and push to `master`.
3. Open [Actions → Android CI and Release](https://github.com/thothassistantai-web/StreamVault-IPTV/actions/workflows/release.yml).
4. Click **Run workflow** on `master`.

The workflow:

- Runs unit tests and Kover coverage
- Builds signed release APK (when secrets are set)
- Extracts the matching CHANGELOG section for the release body
- Publishes tag `v<versionName>` with `StreamVault-<version>.apk` and `StreamVault.apk`

Beta builds on `develop` use [.github/workflows/beta.yml](../.github/workflows/beta.yml) automatically.

## GitHub Release (manual fallback)

If you built locally instead of using Actions:

```bash
git tag -a v1.0.16 -m "StreamVault 1.0.16"
git push origin v1.0.16

gh release create v1.0.16 \
  --repo thothassistantai-web/StreamVault-IPTV \
  --title "StreamVault 1.0.16" \
  --notes-file /tmp/streamvault-1.0.16-notes.md \
  app/build/outputs/apk/release/app-release.apk#StreamVault-1.0.16.apk \
  app/build/outputs/apk/release/app-release.apk#StreamVault.apk
```

Extract notes from CHANGELOG:

```bash
awk '/^## \[1.0.16\]/,/^## \[/' docs/CHANGELOG.md | head -n -1 > /tmp/streamvault-1.0.16-notes.md
```

## Pre-release checklist

- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `versionCode` incremented (must be > previous release)
- [ ] `versionName` and CHANGELOG section match
- [ ] README / PLUGIN_API / GATEWAY docs updated if behavior changed
- [ ] Release APK tested on target device (provider sync, EPG, live playback)
- [ ] No secrets in APK or release assets

## Fork remote

```text
origin  https://github.com/thothassistantai-web/StreamVault-IPTV.git
upstream  https://github.com/Davidona/StreamVault-IPTV.git  (optional)
```

Do **not** force-push `master`. Tag pushes create releases; use `git push origin v1.0.16` only after the version bump is on the branch Actions will build from.
