package com.streamvault.domain.manager

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

/**
 * Backs up and restores the local configuration (providers, favorites, groups,
 * preferences) to a private Google Drive `appDataFolder`. The folder is owned
 * by the app on Drive — invisible to the user in the standard Drive UI — and
 * is wiped automatically by Google when the app is uninstalled.
 *
 * The payload reuses the existing [BackupManager] JSON format, so a Drive push
 * is functionally equivalent to a local `Export to file` and a Drive pull
 * feeds the same `Inspect/Import` flow (with preview + conflict resolution).
 *
 * All operations are suspend and return [Result]; UI layers should never see
 * an exception escape.
 */
interface DriveBackupSyncManager {

    /** Last published auth state. UI observes this to render the sign-in section. */
    val authState: Flow<DriveAuthState>

    /** Last sync timestamps + outcome. UI observes this to show "Last push" / "Last pull". */
    val syncStatus: Flow<DriveSyncStatus>

    /**
     * Builds the platform-specific Sign-In intent the caller must launch with an
     * `ActivityResultLauncher`. The resulting `Intent` data is forwarded back to
     * [completeSignIn].
     *
     * Returns [Result.Error] if Google Play Services is missing.
     */
    suspend fun beginSignIn(): Result<DriveSignInRequest>

    /**
     * Completes the OAuth flow with the data returned by the launched Sign-In
     * intent. On success, [authState] emits [DriveAuthState.SignedIn].
     */
    suspend fun completeSignIn(signInData: Any?): Result<DriveAccount>

    /** Revokes scopes and clears cached account locally. */
    suspend fun signOut(): Result<Unit>

    /**
     * Exports the current configuration to a temporary local file, uploads it
     * to Drive `appDataFolder` (overwriting the previous backup if any).
     */
    suspend fun pushBackup(): Result<DriveSyncStatus>

    /**
     * Downloads the latest backup from Drive `appDataFolder` to a temporary
     * local file, then hands the URI back to the caller — typically wired into
     * the existing [BackupManager.inspectBackup] flow so the preview + conflict
     * resolution UI is reused unchanged.
     *
     * Returns [Result.Error] with code [DriveSyncError.NO_REMOTE_BACKUP] if
     * nothing has been pushed yet.
     */
    suspend fun pullBackup(): Result<DriveBackupArtifact>
}

/** Public Sign-In state for the UI. */
sealed class DriveAuthState {
    /** Not signed in (initial, after sign-out, after explicit revoke). */
    data object SignedOut : DriveAuthState()
    /** Sign-In is mid-flight (intent launched but not completed yet). */
    data object Pending : DriveAuthState()
    /** Active account. `email` may be null on platforms where the API hides it. */
    data class SignedIn(val account: DriveAccount) : DriveAuthState()
}

data class DriveAccount(
    val email: String?,
    val displayName: String?,
)

data class DriveSyncStatus(
    val lastPushAtMs: Long? = null,
    val lastPullAtMs: Long? = null,
    val lastErrorMessage: String? = null,
)

/** Carries the intent the UI must launch. Kept opaque (`Any?`) for platform independence. */
data class DriveSignInRequest(val intent: Any?)

/** Local artifact produced by [DriveBackupSyncManager.pullBackup]. */
data class DriveBackupArtifact(
    /** SAF-compatible `file://` URI string the existing [BackupManager.inspectBackup] can read. */
    val localUriString: String,
    val sizeBytes: Long,
)

/** Stable identifiers UI may match against to localize messages. */
object DriveSyncError {
    const val PLAY_SERVICES_UNAVAILABLE = "drive_play_services_unavailable"
    const val NOT_SIGNED_IN = "drive_not_signed_in"
    const val NO_REMOTE_BACKUP = "drive_no_remote_backup"
    const val NETWORK = "drive_network"
    const val AUTH_FAILED = "drive_auth_failed"
    const val EXPORT_FAILED = "drive_export_failed"
    const val IMPORT_FAILED = "drive_import_failed"
}
