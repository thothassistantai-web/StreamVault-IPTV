package com.streamvault.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.repository.DownloadManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat

class DownloadForegroundService : Service() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DownloadServiceEntryPoint {
        fun downloadManager(): DownloadManager
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var currentDownloadId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID)
        if (downloadId.isNullOrBlank()) {
            Log.w(TAG, " onStartCommand called without download_id extra; stopping self")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        currentDownloadId = downloadId
        val entryPoint = entryPoint()
        val downloadFlow = entryPoint.downloadManager().observeDownload(downloadId)

        beginPendingCommand()

        runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(
                    downloadItem = null,
                    pendingCommand = true
                )
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to enter foreground", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        observeJob = serviceScope.launch {
            downloadFlow.collectLatest { downloadItem ->
                updateNotification(downloadItem)
                if (downloadItem != null) {
                    when (downloadItem.status) {
                        DownloadStatus.COMPLETED,
                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELLED -> {
                            updateNotification(downloadItem)
                            stopSelf()
                        }
                        else -> Unit
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        currentDownloadId = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        downloadItem: DownloadItem?,
        pendingCommand: Boolean = false
    ): Notification {
        val title = if (pendingCommand) {
            "Preparing download service"
        } else if (downloadItem != null) {
            downloadItem.contentName
        } else {
            "Preparing download service"
        }

        val contentText = when {
            pendingCommand -> "Starting download service"
            downloadItem != null -> {
                when (downloadItem.status) {
                    DownloadStatus.COMPLETED -> {
                        getString(R.string.download_completed)
                    }
                    DownloadStatus.FAILED -> {
                        val reason = downloadItem.failureReason ?: "Download failed"
                        getString(R.string.download_failed, reason)
                    }
                    DownloadStatus.CANCELLED -> {
                        getString(R.string.download_cancelled)
                    }
                    DownloadStatus.DOWNLOADING -> {
                        val totalBytesLocal = downloadItem.totalBytes
                        val progressText = if (totalBytesLocal != null && totalBytesLocal > 0L) {
                            val written = formatBytes(downloadItem.bytesWritten)
                            val total = formatBytes(totalBytesLocal)
                            "Downloading: $written / $total"
                        } else {
                            "Downloading: ${formatBytes(downloadItem.bytesWritten)} written"
                        }
                        getString(R.string.download_in_progress, downloadItem.contentName) + "\n$progressText"
                    }
                    else -> {
                        "Download in progress: ${downloadItem.contentName}"
                    }
                }
            }
            else -> "Preparing download service"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(downloadItem?.status == DownloadStatus.DOWNLOADING || pendingCommand)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(defaultContentIntent())
            .build()
    }

    private fun defaultContentIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_notification_channel) ?: "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.download_notification_channel_description) ?: "Shows download progress and completion"
        }
        manager.createNotificationChannel(channel)
    }

    private fun entryPoint(): DownloadServiceEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, DownloadServiceEntryPoint::class.java)

    private fun beginPendingCommand() {
        updateNotification(downloadItem = null, pendingCommand = true)
    }

    private fun updateNotification(downloadItem: DownloadItem?, pendingCommand: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(downloadItem, pendingCommand)
        )
    }

    private fun formatBytes(bytes: Long): String {
        val formatter = NumberFormat.getNumberInstance()
        formatter.maximumFractionDigits = 1
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "${formatter.format(bytes / 1024.0)} KB"
            bytes < 1024L * 1024L * 1024L -> "${formatter.format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${formatter.format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    companion object {
        private const val TAG = "DownloadFgService"
        private const val CHANNEL_ID = "streamvault_downloads"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_DOWNLOAD_ID = "download_id"

        fun startDownload(context: Context, downloadId: String) {
            val intent = Intent(context, DownloadForegroundService::class.java)
                .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
