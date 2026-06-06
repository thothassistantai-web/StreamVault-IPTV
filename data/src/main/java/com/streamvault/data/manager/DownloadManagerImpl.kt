package com.streamvault.data.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.streamvault.data.local.dao.DownloadDao
import com.streamvault.data.local.entity.DownloadEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.ResolvedStreamUrl
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadRequest
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.DownloadStorageConfig
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.DownloadManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class DownloadManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val preferencesRepository: PreferencesRepository,
    private val okHttpClient: OkHttpClient,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    private val applicationScope: CoroutineScope
) : DownloadManager {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val playbackPausedIds = ConcurrentHashMap.newKeySet<String>()
    private val schedulerMutex = Mutex()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var playbackActive = false

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                applicationScope.launch(Dispatchers.IO) {
                    downloadDao.getRetryablePausedOnce(MAX_RETRIES)
                        .filter { it.retryCount == 0 }
                        .forEach { scheduleExisting(it.id, keepPaused = true) }
                    startNextQueued()
                }
            }
        })
    }

    override fun observeAllDownloads(): Flow<List<DownloadItem>> {
        return downloadDao.getAll().map { downloads -> downloads.map { it.toDomain() } }
    }

    override fun observeDownload(id: String): Flow<DownloadItem?> {
        return downloadDao.getById(id).map { it?.toDomain() }
    }

    override fun observeStorageState(): Flow<DownloadStorageConfig> {
        return preferencesRepository.downloadTreeUri.combine(observeAllDownloads()) { treeUri, _ ->
            DownloadStorageConfig(
                treeUri = treeUri,
                displayName = treeUri?.substringAfterLast('/')?.ifBlank { treeUri },
                outputDirectory = null,
                availableBytes = null,
                isWritable = !treeUri.isNullOrBlank()
            )
        }
    }

    override suspend fun enqueueDownload(request: DownloadRequest): Result<DownloadItem> {
        return runCatching {
            val entity = DownloadEntity.fromRequest(
                request = request,
                outputUri = null,
                outputDisplayPath = null
            )
            downloadDao.insert(entity)
            startNextQueued()
            entity.toDomain()
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.error("Failed to enqueue download", it) }
        )
    }

    override suspend fun resumeDownload(id: String): Result<Unit> {
        return runCatching {
            downloadDao.getByIdOnce(id)?.let { entity ->
                if (entity.status != DownloadStatus.COMPLETED && entity.status != DownloadStatus.CANCELLED) {
                    deleteOutput(entity)
                    downloadDao.update(
                        entity.copy(
                            outputUri = null,
                            outputDisplayPath = null,
                            status = DownloadStatus.PENDING,
                            bytesWritten = 0L,
                            totalBytes = null,
                            supportsResume = false,
                            retryCount = 0,
                            failureReason = null
                        )
                    )
                }
            }
            startNextQueued()
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.error("Failed to resume download", it) }
        )
    }

    override suspend fun cancelDownload(id: String): Result<Unit> {
        return runCatching {
            activeCalls.remove(id)?.cancel()
            activeJobs.remove(id)?.cancelAndJoin()
            downloadDao.getByIdOnce(id)?.let { entity ->
                downloadDao.update(entity.copy(status = DownloadStatus.CANCELLED))
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.error("Failed to cancel download", it) }
        )
    }

    override fun onPlaybackStarted() {
        playbackActive = true
        applicationScope.launch(Dispatchers.IO) { runCatching { pauseActiveDownloadsForPlayback() } }
    }

    override fun onPlaybackStopped() {
        playbackActive = false
        applicationScope.launch(Dispatchers.IO) { runCatching { startNextQueued() } }
    }

    override suspend fun deleteDownload(id: String): Result<Unit> {
        return runCatching {
            activeCalls.remove(id)?.cancel()
            activeJobs.remove(id)?.cancelAndJoin()
            downloadDao.getByIdOnce(id)?.let { deleteOutput(it) }
            downloadDao.deleteById(id)
            startNextQueued()
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.error("Failed to delete download", it) }
        )
    }

    override suspend fun updateStorageConfig(
        treeUri: String?,
        displayName: String?
    ): Result<DownloadStorageConfig> {
        return runCatching {
            preferencesRepository.setDownloadTreeUri(treeUri)
            DownloadStorageConfig(
                treeUri = treeUri,
                displayName = displayName,
                outputDirectory = null,
                availableBytes = null,
                isWritable = !treeUri.isNullOrBlank()
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.error("Failed to update download folder", it) }
        )
    }

    private suspend fun scheduleExisting(id: String, keepPaused: Boolean = false) {
        schedulerMutex.withLock {
            if (activeJobs.containsKey(id)) return
            val entity = downloadDao.getByIdOnce(id) ?: return
            if (entity.status == DownloadStatus.COMPLETED || entity.status == DownloadStatus.CANCELLED) return
            if (playbackActive || activeJobs.isNotEmpty()) {
                if (!keepPaused && entity.status != DownloadStatus.PAUSED) {
                    downloadDao.update(entity.copy(status = DownloadStatus.PENDING, failureReason = null))
                }
                return
            }
            startDownloadJob(entity)
        }
    }

    private fun startDownloadJob(entity: DownloadEntity) {
        activeJobs[entity.id] = applicationScope.launch(Dispatchers.IO) {
            captureDownload(entity)
        }
    }

    private suspend fun startNextQueued() {
        schedulerMutex.withLock {
            if (playbackActive || activeJobs.isNotEmpty()) return
            val next = downloadDao.getQueuedOnce()
                .firstOrNull {
                    !activeJobs.containsKey(it.id) &&
                        (it.status == DownloadStatus.PENDING || it.retryCount == 0) &&
                        it.retryCount < MAX_RETRIES
                }
                ?: return
            startDownloadJob(next)
        }
    }

    private suspend fun pauseActiveDownloadsForPlayback() {
        activeJobs.entries.toList().forEach { (id, job) ->
            playbackPausedIds.add(id)
            activeCalls.remove(id)?.cancel()
            job.cancel(PlaybackStartedCancellation())
            job.join()
        }
    }

    private suspend fun captureDownload(initial: DownloadEntity) {
        var current = downloadDao.getByIdOnce(initial.id) ?: initial
        current = current.copy(status = DownloadStatus.DOWNLOADING, failureReason = null)
        downloadDao.update(current)

        try {
            val resumeFrom = current.bytesWritten.takeIf {
                current.supportsResume && it > 0L && hasAppendTarget(current)
            } ?: 0L
            val resolvedStream = resolveFreshDownloadStream(current)
            val requestUrl = resolvedStream?.url?.takeIf { it.isNotBlank() } ?: current.streamUrl
            if (requestUrl != current.streamUrl) {
                current = current.copy(streamUrl = requestUrl)
                downloadDao.update(current)
            }
            val requestBuilder = Request.Builder().url(requestUrl).get()
            resolvedStream?.headers.orEmpty().forEach { (name, value) ->
                requestBuilder.header(name, value)
            }
            resolvedStream?.userAgent?.takeIf { it.isNotBlank() }?.let { userAgent ->
                requestBuilder.header("User-Agent", userAgent)
            }
            if (resumeFrom > 0L) requestBuilder.header("Range", "bytes=$resumeFrom-")
            val call = okHttpClient.newCall(requestBuilder.build())
            activeCalls[initial.id] = call
            call.execute().use { response ->
                if (!response.isSuccessful) throw HttpDownloadException(response.code)
                val body = response.body ?: error("Empty response body")
                val rangeAccepted = resumeFrom > 0L && response.code == HttpURLConnection.HTTP_PARTIAL
                val restartFromZero = resumeFrom > 0L && response.code == HttpURLConnection.HTTP_OK
                if (restartFromZero) {
                    deleteOutput(current)
                    current = current.copy(bytesWritten = 0L, outputUri = null, outputDisplayPath = null, supportsResume = false)
                }
                val append = rangeAccepted
                val contentLength = body.contentLength().takeIf { it > 0L }
                val totalBytes = when {
                    append && contentLength != null -> resumeFrom + contentLength
                    else -> contentLength
                }
                val supportsResume = response.header("Accept-Ranges")
                    ?.contains("bytes", ignoreCase = true) == true || rangeAccepted
                val target = runCatching { createOutputTarget(current, response.header("Content-Type"), append) }
                    .getOrElse { error ->
                        if (!append) throw error
                        throw RestartFromZeroException(error)
                    }
                var bytesWritten = if (append && current.outputUri != null) resumeFrom else 0L
                var lastProgressUpdate = bytesWritten
                current = current.copy(
                    outputUri = target.uri.toString(),
                    outputDisplayPath = target.displayPath,
                    bytesWritten = bytesWritten,
                    totalBytes = totalBytes,
                    supportsResume = supportsResume,
                    status = DownloadStatus.DOWNLOADING
                )
                downloadDao.update(current)

                target.output.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesWritten += read.toLong()
                            current = current.copy(bytesWritten = bytesWritten)

                            if (bytesWritten - lastProgressUpdate >= PROGRESS_UPDATE_BYTES) {
                                current = current.copy(
                                    outputUri = target.uri.toString(),
                                    outputDisplayPath = target.displayPath,
                                    bytesWritten = bytesWritten,
                                    totalBytes = totalBytes,
                                    supportsResume = supportsResume,
                                    status = DownloadStatus.DOWNLOADING
                                )
                                downloadDao.update(current)
                                lastProgressUpdate = bytesWritten
                            }
                        }
                    }
                }

                downloadDao.update(
                    current.copy(
                        outputUri = target.uri.toString(),
                        outputDisplayPath = target.displayPath,
                        status = DownloadStatus.COMPLETED,
                        bytesWritten = bytesWritten,
                        totalBytes = totalBytes ?: bytesWritten,
                        supportsResume = supportsResume,
                        retryCount = 0,
                        completedAt = System.currentTimeMillis(),
                        failureReason = null
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            downloadDao.getByIdOnce(initial.id)?.let { entity ->
                if (cancelled is PlaybackStartedCancellation || playbackPausedIds.contains(initial.id)) {
                    resetInterruptedDownload(current, DownloadStatus.PAUSED, "Waiting for playback to stop")
                } else {
                    downloadDao.update(entity.copy(status = DownloadStatus.CANCELLED))
                }
            }
            throw cancelled
        } catch (restart: RestartFromZeroException) {
            val reset = current.copy(
                bytesWritten = 0L,
                outputUri = null,
                outputDisplayPath = null,
                supportsResume = false
            )
            deleteOutput(current)
            downloadDao.update(reset)
            captureDownload(reset)
        } catch (error: Throwable) {
            if (downloadDao.getByIdOnce(initial.id) != null) {
                if (playbackPausedIds.contains(initial.id)) {
                    resetInterruptedDownload(current, DownloadStatus.PAUSED, "Waiting for playback to stop")
                } else {
                    handleDownloadFailure(current, error)
                }
            }
        } finally {
            activeCalls.remove(initial.id)
            playbackPausedIds.remove(initial.id)
            activeJobs.remove(initial.id)
            startNextQueued()
        }
    }

    private suspend fun handleDownloadFailure(entity: DownloadEntity, error: Throwable) {
        val reason = error.message ?: error::class.java.simpleName
        if (!isTransient(error) || entity.retryCount >= MAX_RETRIES) {
            resetInterruptedDownload(entity, DownloadStatus.FAILED, reason)
            return
        }

        val retryCount = entity.retryCount + 1
        resetInterruptedDownload(entity, DownloadStatus.PAUSED, reason, retryCount)
        applicationScope.launch(Dispatchers.IO) {
            delay(backoffMs(retryCount))
            downloadDao.getByIdOnce(entity.id)
                ?.takeIf { it.status == DownloadStatus.PAUSED && it.retryCount == retryCount }
                ?.let { downloadDao.update(it.copy(status = DownloadStatus.PENDING)) }
            startNextQueued()
        }
    }

    private suspend fun resetInterruptedDownload(
        entity: DownloadEntity,
        status: DownloadStatus,
        reason: String,
        retryCount: Int = 0
    ) {
        deleteOutput(entity)
        downloadDao.update(
            entity.copy(
                outputUri = null,
                outputDisplayPath = null,
                status = status,
                bytesWritten = 0L,
                totalBytes = null,
                supportsResume = false,
                retryCount = retryCount,
                completedAt = null,
                failureReason = reason
            )
        )
    }

    private fun hasAppendTarget(entity: DownloadEntity): Boolean {
        val path = entity.outputDisplayPath
        return !entity.outputUri.isNullOrBlank() || (!path.isNullOrBlank() && File(path).exists())
    }

    private fun isTransient(error: Throwable): Boolean {
        val httpCode = (error as? HttpDownloadException)?.code
        return error is SocketException ||
            error is SocketTimeoutException ||
            error is ProtocolException ||
            httpCode != null && httpCode >= 500 ||
            !isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun resolveFreshDownloadStream(entity: DownloadEntity): ResolvedStreamUrl? {
        val logicalUrl = entity.sourceStreamUrl?.takeIf { it.isNotBlank() } ?: entity.streamUrl
        return runCatching {
            xtreamStreamUrlResolver.resolveWithMetadata(
                url = logicalUrl,
                fallbackProviderId = entity.providerId,
                fallbackStreamId = entity.sourceStreamId ?: entity.contentId,
                fallbackContentType = entity.contentType.toPlaybackContentType(),
                fallbackContainerExtension = entity.containerExtension,
                preferStableUrl = true
            )
        }.getOrNull()
    }

    private fun DownloadContentType.toPlaybackContentType(): ContentType = when (this) {
        DownloadContentType.MOVIE -> ContentType.MOVIE
        DownloadContentType.SERIES_EPISODE -> ContentType.SERIES_EPISODE
    }

    private fun backoffMs(retryCount: Int): Long = when (retryCount) {
        1 -> 5_000L
        2 -> 15_000L
        3 -> 45_000L
        else -> 300_000L
    }

    private suspend fun createOutputTarget(
        entity: DownloadEntity,
        contentTypeHeader: String?,
        append: Boolean
    ): OutputTarget = withContext(Dispatchers.IO) {
        if (append && !entity.outputDisplayPath.isNullOrBlank()) {
            val file = File(entity.outputDisplayPath)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                return@withContext OutputTarget(uri, file.absolutePath, FileOutputStream(file, true).buffered())
            }
        }
        if (append && !entity.outputUri.isNullOrBlank()) {
            val uri = Uri.parse(entity.outputUri)
            val output = context.contentResolver.openOutputStream(uri, "wa")
                ?: throw FileNotFoundException("Could not append selected download file")
            return@withContext OutputTarget(uri, entity.outputDisplayPath ?: uri.toString(), output)
        }

        val mimeType = contentTypeHeader
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.startsWith("video/") }
            ?: "video/mp4"
        val fileName = buildFileName(entity, mimeType)
        val treeUri = preferencesRepository.downloadTreeUri.first()

        if (!treeUri.isNullOrBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            val file = tree?.createFile(mimeType, fileName)
                ?: error("Could not create download file in selected folder")
            val output = context.contentResolver.openOutputStream(file.uri)
                ?: error("Could not open selected download file")
            return@withContext OutputTarget(
                uri = file.uri,
                displayPath = file.name ?: fileName,
                output = output
            )
        }

        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            "StreamVault"
        ).apply { mkdirs() }
        val file = uniqueFile(directory, fileName)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        OutputTarget(
            uri = uri,
            displayPath = file.absolutePath,
            output = file.outputStream()
        )
    }

    private fun deleteOutput(entity: DownloadEntity) {
        var deleted = false
        entity.outputUri
            ?.takeIf { it.isNotBlank() }
            ?.let { outputUri ->
                val uri = Uri.parse(outputUri)
                deleted = runCatching {
                    DocumentFile.fromSingleUri(context, uri)?.delete() == true
                }.getOrDefault(false)
                if (!deleted) {
                    deleted = runCatching {
                        context.contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false)
                }
            }

        val displayPath = entity.outputDisplayPath?.takeIf { it.isNotBlank() } ?: return
        val file = File(displayPath)
        if (!deleted && file.isAbsolute) {
            runCatching { file.delete() }
        }
    }

    private fun buildFileName(entity: DownloadEntity, mimeType: String): String {
        val extension = entity.containerExtension?.takeIf { it.isNotBlank() }
            ?: extensionFromUrl(entity.streamUrl)
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: "mp4"
        val prefix = when (entity.contentType) {
            DownloadContentType.MOVIE -> entity.contentName
            DownloadContentType.SERIES_EPISODE -> listOfNotNull(
                entity.contentName,
                entity.seasonNumber?.let { "S$it" },
                entity.episodeNumber?.let { "E$it" }
            ).joinToString(" ")
        }
        val safeName = prefix
            .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { entity.id }
        return "$safeName.$extension"
    }

    private fun extensionFromUrl(url: String): String? {
        val segment = Uri.parse(url).lastPathSegment ?: return null
        val extension = segment.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.length in 2..5 }
        return extension?.takeIf { ext -> ext.all { it.isLetterOrDigit() } }
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        var candidate = File(directory, fileName)
        var index = 2
        while (candidate.exists()) {
            val suffix = if (extension.isBlank()) " ($index)" else " ($index).$extension"
            candidate = File(directory, "$baseName$suffix")
            index += 1
        }
        return candidate
    }

    private data class OutputTarget(
        val uri: Uri,
        val displayPath: String,
        val output: OutputStream
    )

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        const val PROGRESS_UPDATE_BYTES = 512 * 1024
        const val MAX_RETRIES = 5
    }

    private class HttpDownloadException(val code: Int) : Exception("HTTP $code")

    private class RestartFromZeroException(cause: Throwable) : Exception(cause)

    private class PlaybackStartedCancellation : CancellationException("Playback started")
}
