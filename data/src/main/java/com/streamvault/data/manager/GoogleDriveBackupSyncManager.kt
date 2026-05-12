package com.streamvault.data.manager

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.DriveAccount
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveBackupArtifact
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.DriveSignInRequest
import com.streamvault.domain.manager.DriveSyncError
import com.streamvault.domain.manager.DriveSyncStatus
import com.streamvault.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation backed by Google Sign-In + the Drive REST `appDataFolder` scope.
 *
 * - **No OAuth Client ID lives in this file** — Google Play Services resolves the
 *   client by matching the running app's `(packageName, signing SHA-1)` against
 *   the OAuth credentials registered in the Google Cloud Console project. Each
 *   maintainer / signing key must register its own OAuth client (see
 *   `docs/GOOGLE_DRIVE_SETUP.md`).
 * - The backup payload is the existing [BackupManager] JSON v5 export — we just
 *   pipe it through a temp file under [Context.getCacheDir] and upload/download
 *   via multipart REST. No extra format, no extra schema.
 * - Sensitive provider passwords are already stripped by [BackupManager.exportConfig].
 */
@Singleton
class GoogleDriveBackupSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
) : DriveBackupSyncManager {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _authState = MutableStateFlow<DriveAuthState>(DriveAuthState.SignedOut)
    override val authState: StateFlow<DriveAuthState> = _authState.asStateFlow()

    private val _syncStatus = MutableStateFlow(DriveSyncStatus())
    override val syncStatus: StateFlow<DriveSyncStatus> = _syncStatus.asStateFlow()

    init {
        // Restore last known account silently on cold start.
        val cached = GoogleSignIn.getLastSignedInAccount(context)
        if (cached != null && hasAppDataScope(cached)) {
            _authState.value = DriveAuthState.SignedIn(cached.toDriveAccount())
        }
    }

    override suspend fun beginSignIn(): Result<DriveSignInRequest> = withContext(Dispatchers.IO) {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (availability != ConnectionResult.SUCCESS) {
            return@withContext Result.Error(
                message = DriveSyncError.PLAY_SERVICES_UNAVAILABLE,
                exception = null,
            )
        }
        _authState.value = DriveAuthState.Pending
        Result.Success(DriveSignInRequest(intent = buildClient().signInIntent))
    }

    override suspend fun completeSignIn(signInData: Any?): Result<DriveAccount> =
        withContext(Dispatchers.IO) {
            try {
                val data = signInData as? android.content.Intent
                Log.d("DriveSync", "completeSignIn intent=$data extras=${data?.extras}")
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).awaitTask()
                Log.d("DriveSync", "account=$account email=${account?.email} grantedScopes=${account?.grantedScopes}")
                if (account == null) {
                    Log.w("DriveSync", "completeSignIn: account is null (user cancelled or sign-in failed silently)")
                    _authState.value = DriveAuthState.SignedOut
                    return@withContext Result.Error(DriveSyncError.AUTH_FAILED)
                }
                if (!hasAppDataScope(account)) {
                    Log.w("DriveSync", "completeSignIn: drive.appdata scope NOT granted, grantedScopes=${account.grantedScopes}")
                    _authState.value = DriveAuthState.SignedOut
                    return@withContext Result.Error(DriveSyncError.AUTH_FAILED)
                }
                val driveAccount = account.toDriveAccount()
                Log.d("DriveSync", "completeSignIn: SUCCESS for ${driveAccount.email}")
                _authState.value = DriveAuthState.SignedIn(driveAccount)
                Result.Success(driveAccount)
            } catch (apiError: ApiException) {
                Log.e("DriveSync", "completeSignIn: ApiException statusCode=${apiError.statusCode} message=${apiError.message}", apiError)
                _authState.value = DriveAuthState.SignedOut
                Result.Error(DriveSyncError.AUTH_FAILED, apiError)
            } catch (t: Throwable) {
                Log.e("DriveSync", "completeSignIn: Throwable", t)
                _authState.value = DriveAuthState.SignedOut
                Result.Error(DriveSyncError.AUTH_FAILED, t)
            }
        }

    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            buildClient().signOut().awaitTask()
            _authState.value = DriveAuthState.SignedOut
            Result.Success(Unit)
        } catch (t: Throwable) {
            Result.Error(DriveSyncError.AUTH_FAILED, t)
        }
    }

    /**
     * Lightweight `await()` shim for `Task<T>` — avoids adding the
     * `kotlinx-coroutines-play-services` artifact just to bridge two callbacks.
     */
    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { cont ->
        if (isComplete) {
            val exception = exception
            if (exception == null) {
                @Suppress("UNCHECKED_CAST")
                if (isCanceled) cont.resume(null) else cont.resume(result as T)
            } else {
                cont.resumeWithException(exception)
            }
            return@suspendCancellableCoroutine
        }
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.resume(null) }
    }

    override suspend fun pushBackup(): Result<DriveSyncStatus> = withContext(Dispatchers.IO) {
        val account = requireSignedInAccount() ?: return@withContext Result.Error(DriveSyncError.NOT_SIGNED_IN)
        val token = fetchAccessToken(account) ?: return@withContext Result.Error(DriveSyncError.AUTH_FAILED)

        val tempFile = File(context.cacheDir, TEMP_BACKUP_FILE_NAME).apply { delete() }
        val exportUri = tempFile.toUri().toString()

        val exportResult = backupManager.exportConfig(exportUri)
        if (exportResult is Result.Error) {
            return@withContext Result.Error(DriveSyncError.EXPORT_FAILED, exportResult.exception)
        }
        if (!tempFile.exists() || tempFile.length() == 0L) {
            return@withContext Result.Error(DriveSyncError.EXPORT_FAILED)
        }

        val uploadOk = try {
            uploadAppDataFile(token, tempFile)
        } catch (io: IOException) {
            return@withContext Result.Error(DriveSyncError.NETWORK, io)
        } finally {
            tempFile.delete()
        }
        if (!uploadOk) {
            return@withContext Result.Error(DriveSyncError.NETWORK)
        }

        val updated = _syncStatus.value.copy(
            lastPushAtMs = System.currentTimeMillis(),
            lastErrorMessage = null,
        )
        _syncStatus.value = updated
        Result.Success(updated)
    }

    override suspend fun pullBackup(): Result<DriveBackupArtifact> = withContext(Dispatchers.IO) {
        Log.d("DriveSync", "pullBackup: start")
        val account = requireSignedInAccount() ?: run {
            Log.w("DriveSync", "pullBackup: NOT_SIGNED_IN (account is null)")
            return@withContext Result.Error(DriveSyncError.NOT_SIGNED_IN)
        }
        val token = fetchAccessToken(account) ?: run {
            Log.w("DriveSync", "pullBackup: AUTH_FAILED (no token)")
            return@withContext Result.Error(DriveSyncError.AUTH_FAILED)
        }
        Log.d("DriveSync", "pullBackup: token OK, looking up remote backup id")

        val fileId = try {
            findRemoteBackupId(token)
        } catch (io: IOException) {
            Log.e("DriveSync", "pullBackup: NETWORK error while finding remote id", io)
            return@withContext Result.Error(DriveSyncError.NETWORK, io)
        }
        if (fileId == null) {
            Log.w("DriveSync", "pullBackup: NO_REMOTE_BACKUP (no file found in appDataFolder)")
            return@withContext Result.Error(DriveSyncError.NO_REMOTE_BACKUP)
        }
        Log.d("DriveSync", "pullBackup: remote fileId=$fileId, downloading")

        val target = File(context.cacheDir, TEMP_BACKUP_FILE_NAME).apply { delete() }
        val downloadOk = try {
            downloadAppDataFile(token, fileId, target)
        } catch (io: IOException) {
            Log.e("DriveSync", "pullBackup: NETWORK error during download", io)
            return@withContext Result.Error(DriveSyncError.NETWORK, io)
        }
        if (!downloadOk || !target.exists() || target.length() == 0L) {
            Log.w("DriveSync", "pullBackup: download failed or empty file (ok=$downloadOk exists=${target.exists()} size=${target.length()})")
            target.delete()
            return@withContext Result.Error(DriveSyncError.NETWORK)
        }
        Log.d("DriveSync", "pullBackup: downloaded ${target.length()} bytes to ${target.absolutePath}")

        _syncStatus.value = _syncStatus.value.copy(
            lastPullAtMs = System.currentTimeMillis(),
            lastErrorMessage = null,
        )
        Result.Success(
            DriveBackupArtifact(
                localUriString = target.toUri().toString(),
                sizeBytes = target.length(),
            ),
        )
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun buildClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_APP_DATA))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    private fun hasAppDataScope(account: GoogleSignInAccount): Boolean =
        GoogleSignIn.hasPermissions(account, Scope(SCOPE_APP_DATA))

    private fun requireSignedInAccount(): GoogleSignInAccount? {
        val cached = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        if (!hasAppDataScope(cached)) return null
        return cached
    }

    /**
     * Synchronous OAuth2 token retrieval. Must be called off the main thread.
     * The token is never logged.
     */
    private fun fetchAccessToken(account: GoogleSignInAccount): String? = try {
        val androidAccount = account.account ?: return null
        GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$SCOPE_APP_DATA")
    } catch (t: Throwable) {
        null
    }

    private fun findRemoteBackupId(authToken: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?spaces=appDataFolder" +
            "&q=" + uriEncode("name='$BACKUP_FILE_NAME'") +
            "&fields=files(id,modifiedTime,size)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            val files = JSONObject(body).optJSONArray("files") ?: return null
            if (files.length() == 0) return null
            return files.getJSONObject(0).getString("id")
        }
    }

    private fun uploadAppDataFile(authToken: String, payload: File): Boolean {
        val existingId = findRemoteBackupId(authToken)
        val boundary = "streamvault-${System.nanoTime()}"
        val (httpMethod, endpoint) = if (existingId != null) {
            "PATCH" to "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart"
        } else {
            "POST" to "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        }

        val metadata = JSONObject().apply {
            put("name", BACKUP_FILE_NAME)
            if (existingId == null) {
                put("parents", JSONArray().put("appDataFolder"))
            }
        }.toString()

        val body = MultipartRelatedBody(boundary, metadata, payload)
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $authToken")
            .method(httpMethod, body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun downloadAppDataFile(authToken: String, fileId: String, target: File): Boolean {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $authToken")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val source = response.body?.byteStream() ?: return false
            target.outputStream().use { sink -> source.copyTo(sink) }
            return true
        }
    }

    private fun GoogleSignInAccount.toDriveAccount(): DriveAccount =
        DriveAccount(email = email, displayName = displayName)

    private fun uriEncode(value: String): String =
        // URLEncoder.encode(String, Charset) is API 33+ only.
        // We support minSdk 28, so use the String charset name overload.
        java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val SCOPE_APP_DATA = "https://www.googleapis.com/auth/drive.appdata"
        const val BACKUP_FILE_NAME = "streamvault_backup.json"
        const val TEMP_BACKUP_FILE_NAME = "drive_sync_backup.json"
    }
}

/**
 * `multipart/related` body the Drive REST API expects for a single-shot create
 * or update — metadata part first, then the JSON payload, separated by the
 * given [boundary]. Streams the payload from disk to avoid loading the full
 * backup in memory.
 */
private class MultipartRelatedBody(
    private val boundary: String,
    private val metadataJson: String,
    private val payload: File,
) : okhttp3.RequestBody() {

    override fun contentType(): okhttp3.MediaType =
        "multipart/related; boundary=$boundary".toMediaTypeOrNull()!!

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("--$boundary\r\n")
        sink.writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        sink.writeUtf8(metadataJson)
        sink.writeUtf8("\r\n--$boundary\r\n")
        sink.writeUtf8("Content-Type: application/json\r\n\r\n")
        val payloadBody = payload.asRequestBody("application/json".toMediaTypeOrNull())
        payloadBody.writeTo(sink)
        sink.writeUtf8("\r\n--$boundary--\r\n")
    }

    @Suppress("unused")
    private fun emptyRequestBody() = "".toRequestBody(null)
}
