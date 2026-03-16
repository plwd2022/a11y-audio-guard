package com.plwd.audiochannelguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_DOWNLOAD_URLS = "download_urls"
        const val KEY_TARGET_PATH = "target_path"
        const val KEY_APK_FILE_NAME = "apk_file_name"
        const val KEY_VERSION_NAME = "version_name"
        const val KEY_EXPECTED_SIZE_BYTES = "expected_size_bytes"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED_BYTES = "speed_bytes"
        const val KEY_STATUS = "status"

        private const val CHANNEL_ID = "update_download_channel"
        private const val NOTIFICATION_ID = 2001
        private const val BUFFER_SIZE = 8 * 1024
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000

        const val STATUS_PENDING = "PENDING"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
    }

    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val installManager = UpdateInstallManager(applicationContext)

    override suspend fun doWork(): Result {
        val targetPath = inputData.getString(KEY_TARGET_PATH)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "缺少下载路径"))
        val downloadUrls = inputData.getStringArray(KEY_DOWNLOAD_URLS)?.toList().orEmpty()
        if (downloadUrls.isEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "缺少下载地址"))
        }
        val fileName = inputData.getString(KEY_APK_FILE_NAME).orEmpty()
        val versionName = inputData.getString(KEY_VERSION_NAME).orEmpty()
        val expectedSizeBytes = inputData.getLong(KEY_EXPECTED_SIZE_BYTES, 0L)
        val targetFile = File(targetPath)
        targetFile.parentFile?.mkdirs()

        createNotificationChannelIfNeeded()
        setForeground(createForegroundInfo(fileName, versionName))
        setProgress(
            workDataOf(
                KEY_PROGRESS to 0f,
                KEY_DOWNLOADED_BYTES to 0L,
                KEY_TOTAL_BYTES to 0L,
                KEY_SPEED_BYTES to 0L,
                KEY_STATUS to STATUS_PENDING
            )
        )

        var lastError = "下载失败"
        for ((index, url) in downloadUrls.withIndex()) {
            try {
                downloadSingleUrl(
                    downloadUrl = url,
                    targetFile = targetFile,
                    contentTitle = when {
                        versionName.isNotBlank() -> "下载更新 $versionName"
                        fileName.isNotBlank() -> "下载更新 $fileName"
                        else -> "下载更新"
                    },
                    expectedSizeBytes = expectedSizeBytes,
                )

                updateNotification(
                    title = "下载完成",
                    text = fileName.ifBlank { "更新包已下载完成，请返回应用安装" },
                    progress = 100,
                    ongoing = false
                )
                return Result.success(workDataOf(KEY_FILE_PATH to targetPath))
            } catch (exception: Exception) {
                lastError = exception.message ?: "下载失败"
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (isStopped) {
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "下载已取消"))
                }
                if (index == downloadUrls.lastIndex) {
                    break
                }
            }
        }

        setProgress(
            workDataOf(
                KEY_PROGRESS to 0f,
                KEY_DOWNLOADED_BYTES to 0L,
                KEY_TOTAL_BYTES to 0L,
                KEY_SPEED_BYTES to 0L,
                KEY_STATUS to STATUS_FAILED
            )
        )
        updateNotification(
            title = "下载失败",
            text = lastError,
            progress = 0,
            ongoing = false
        )
        return Result.failure(workDataOf(KEY_ERROR_MESSAGE to lastError))
    }

    private suspend fun downloadSingleUrl(
        downloadUrl: String,
        targetFile: File,
        contentTitle: String,
        expectedSizeBytes: Long,
    ) {
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "AudioChannelGuard-Updater")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode: ${connection.responseMessage}")
            }

            val contentLength = connection.contentLengthLong.coerceAtLeast(0L)
            val displayTotalBytes = when {
                contentLength > 0L -> contentLength
                expectedSizeBytes > 0L -> expectedSizeBytes
                else -> 0L
            }
            var downloadedBytes = 0L
            var lastUpdateAt = System.currentTimeMillis()
            var lastUpdateBytes = 0L

            FileOutputStream(targetFile).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (isStopped) throw IOException("下载已取消")
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateAt >= 1000L) {
                            val progress = if (displayTotalBytes > 0L) {
                                downloadedBytes.toFloat() / displayTotalBytes.toFloat()
                            } else {
                                0f
                            }
                            val intervalBytes = downloadedBytes - lastUpdateBytes
                            val speed = if (now > lastUpdateAt) {
                                intervalBytes * 1000L / (now - lastUpdateAt)
                            } else {
                                0L
                            }
                            setProgress(
                                workDataOf(
                                    KEY_PROGRESS to progress.coerceIn(0f, 1f),
                                    KEY_DOWNLOADED_BYTES to downloadedBytes,
                                    KEY_TOTAL_BYTES to displayTotalBytes,
                                    KEY_SPEED_BYTES to speed,
                                    KEY_STATUS to STATUS_DOWNLOADING
                                )
                            )
                            updateNotification(
                                title = contentTitle,
                                text = buildProgressText(downloadedBytes, displayTotalBytes, speed),
                                progress = (progress * 100).toInt().coerceIn(0, 100),
                                ongoing = true
                            )
                            lastUpdateAt = now
                            lastUpdateBytes = downloadedBytes
                        }
                    }
                }
            }

            if (downloadedBytes <= 0L) {
                throw IOException("下载内容为空")
            }
            if (expectedSizeBytes > 0L && downloadedBytes != expectedSizeBytes) {
                throw IOException("下载文件大小不匹配")
            }
            installManager.validateDownloadedApk(targetFile)?.let { message ->
                throw IOException(message)
            }

            setProgress(
                workDataOf(
                    KEY_PROGRESS to 1f,
                    KEY_DOWNLOADED_BYTES to downloadedBytes,
                    KEY_TOTAL_BYTES to displayTotalBytes.coerceAtLeast(downloadedBytes),
                    KEY_SPEED_BYTES to 0L,
                    KEY_STATUS to STATUS_COMPLETED
                )
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun createForegroundInfo(fileName: String, versionName: String): ForegroundInfo {
        val title = when {
            versionName.isNotBlank() -> "下载更新 $versionName"
            fileName.isNotBlank() -> "下载更新 $fileName"
            else -> "下载更新"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentTitle(title)
            .setContentText("准备下载...")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setContentIntent(createContentPendingIntent())
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "应用更新下载",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示应用更新下载进度"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Int,
        ongoing: Boolean,
    ) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setContentIntent(createContentPendingIntent())
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createContentPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            100,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildProgressText(
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytes: Long,
    ): String {
        val downloadedText = android.text.format.Formatter.formatFileSize(applicationContext, downloadedBytes)
        val totalText = if (totalBytes > 0L) {
            android.text.format.Formatter.formatFileSize(applicationContext, totalBytes)
        } else {
            "未知大小"
        }
        val speedText = if (speedBytes > 0L) {
            "${android.text.format.Formatter.formatFileSize(applicationContext, speedBytes)}/s"
        } else {
            "计算中"
        }
        return "$downloadedText / $totalText  $speedText"
    }
}
