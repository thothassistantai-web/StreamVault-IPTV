# Google Drive backup sync — setup guide

StreamVault can sync its backup file to a private folder of your Google Drive
(scope `drive.appdata`). The folder is owned by the app, **invisible** in the
standard Drive UI, and **wiped automatically** when the user uninstalls the app.

This page targets **maintainers and contributors** who want to build/debug the
feature locally. End users don't need to do anything — they just sign in from
*Settings → Backup & Restore → Google Drive sync*.

## Why per-build setup is required

The Google Sign-In Android SDK authenticates a running app by matching the
pair `(packageName, signing certificate SHA-1)` against the **OAuth client IDs**
registered in a Google Cloud Console project. There is **no client secret in
the code** — every fork/maintainer/release channel must register its own pair.

That means:

| Build flavour | Needs its own OAuth client | Why |
|---|---|---|
| `master` debug, local on dev machine | yes | each developer signs with their own debug keystore (different SHA-1) |
| Official release on Play Store | yes | Davidona's release key SHA-1 |
| CI build | yes (or share the dev one) | depending on signing strategy |

The code in `data/manager/GoogleDriveBackupSyncManager.kt` never references an
OAuth client ID — it just asks Play Services for the locally-installed client
matching its `(packageName, SHA-1)`. If no client is registered, sign-in fails
silently with `ApiException` status `DEVELOPER_ERROR (10)`.

## One-time setup (Google Cloud Console)

### 1. Create a Cloud project

Open <https://console.cloud.google.com/projectcreate> and create a project of
your choice (e.g. `streamvault-dev-<your-handle>`). Select it.

### 2. Enable the Google Drive API

<https://console.cloud.google.com/apis/library/drive.googleapis.com> →
**Enable**.

### 3. Configure the OAuth consent screen

<https://console.cloud.google.com/apis/credentials/consent>

- **User type** → External
- **App name** → `StreamVault` (or your fork name)
- **User support email** + **Developer contact** → your email
- **Scopes** → add `https://www.googleapis.com/auth/drive.appdata`
- **Test users** → add the Google account(s) you'll sign in with on the device

While the project stays in *Testing* state, only the listed test users can
sign in — that's fine for development. For a Play Store release you'd later
move the project to *Production* (and likely go through Google's verification
since `drive.appdata` is considered a sensitive scope).

### 4. Create the OAuth Android client(s)

<https://console.cloud.google.com/apis/credentials> → **Create credentials** →
**OAuth client ID** → **Android**.

| Field | Value |
|---|---|
| Name | `StreamVault — debug (<your handle>)` |
| Package name | `com.streamvault.app` |
| SHA-1 certificate fingerprint | from `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` |

Repeat the step for every signing certificate that will run the feature
(release key, CI key, …). You don't need to copy any returned ID anywhere —
the runtime resolves it transparently.

### 5. Add a Google account to the test device

The device or emulator that runs the build must have a Google account added
in **Settings → Accounts**, and that account must be in the OAuth consent
screen's *Test users* list.

## Verifying the setup

1. Install the debug APK.
2. *Settings → Backup & Restore → Google Drive sync → Connect to Google Drive*.
3. Pick the test user account in the system picker. The consent screen must
   ask only for the `drive.appdata` scope — if it asks for more (or fails
   with "Developer error"), you have a SHA-1 / package name mismatch.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Sign-in dialog closes immediately, `ApiException(10)` in logcat | OAuth Android client missing or wrong SHA-1 / package name |
| Sign-in succeeds but push/pull errors with `drive_auth_failed` | Drive API not enabled, or scope not granted in consent screen |
| `Google Play Services required` snackbar | Emulator built with AOSP images (no GMS). Use a "Google APIs"/"Google Play" emulator image |
| Push works, pull says `drive_no_remote_backup` | Drive `appDataFolder` is empty for this account — push first |

## Privacy notes

- The backup payload reuses the existing `BackupManager` JSON v5 export. Provider
  passwords are already stripped (`BackupManagerImpl.exportConfig`, see the
  `password = ""` line) — they are never uploaded to Drive.
- Auth tokens fetched via `GoogleAuthUtil.getToken` are never logged.
- The scope `drive.appdata` cannot read files outside the app's private folder.
