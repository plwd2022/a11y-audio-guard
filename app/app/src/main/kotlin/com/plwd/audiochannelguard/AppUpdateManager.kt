package com.plwd.audiochannelguard

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed interface UpdateDownloadState {
    object Idle : UpdateDownloadState
    data class Pending(val updateInfo: UpdateInfo) : UpdateDownloadState
    data class Downloading(
        val updateInfo: UpdateInfo,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytes: Long,
    ) : UpdateDownloadState

    data class Completed(
        val updateInfo: UpdateInfo,
        val filePath: String,
    ) : UpdateDownloadState

    data class Failed(
        val updateInfo: UpdateInfo?,
        val message: String,
    ) : UpdateDownloadState
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "app_update_prefs"
        private const val WORK_NAME_DOWNLOAD = "app_update_download"
        private const val STORAGE_RESERVE_BYTES = 10L * 1024L * 1024L
        private const val KEY_WORK_ID = "download_work_id"
        private const val KEY_UPDATE_INFO = "saved_update_info"
        private const val KEY_DOWNLOAD_PATH = "download_path"
        private const val KEY_PENDING_CLEANUP_PATH = "pending_cleanup_path"
        private const val KEY_PENDING_CLEANUP_REQUESTED_AT = "pending_cleanup_requested_at"

        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(appContext)
    private val installManager = UpdateInstallManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeWorkId = MutableStateFlow(loadWorkId())
    private val activeUpdateInfo = MutableStateFlow(loadUpdateInfo())
    private val activeDownloadPath = MutableStateFlow(loadDownloadPath())
    private val _downloadState = MutableStateFlow<UpdateDownloadState>(initialDownloadState())
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    init {
        scope.launch {
            activeWorkId.flatMapLatest { workId ->
                if (workId == null) {
                    flowOf(null)
                } else {
                    observeWorkInfo(workId)
                }
            }.collectLatest { workInfo ->
                _downloadState.value = mapDownloadState(workInfo)
            }
        }
    }

    suspend fun startDownload(updateInfo: UpdateInfo): Boolean = withContext(Dispatchers.IO) {
        val targetFile = buildTargetFile(updateInfo)
        cleanupDownloadedApks()
        clearPendingCleanup()
        ensureEnoughStorage(updateInfo.apkSizeBytes)

        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    UpdateDownloadWorker.KEY_DOWNLOAD_URLS to updateInfo.downloadUrls.toTypedArray(),
                    UpdateDownloadWorker.KEY_TARGET_PATH to targetFile.absolutePath,
                    UpdateDownloadWorker.KEY_APK_FILE_NAME to updateInfo.apkFileName,
                    UpdateDownloadWorker.KEY_VERSION_NAME to updateInfo.latestVersionName,
                    UpdateDownloadWorker.KEY_EXPECTED_SIZE_BYTES to updateInfo.apkSizeBytes,
                )
            )
            .build()

        persistUpdateInfo(updateInfo)
        persistDownloadPath(targetFile.absolutePath)
        persistWorkId(request.id)

        activeUpdateInfo.value = updateInfo
        activeDownloadPath.value = targetFile.absolutePath
        activeWorkId.value = request.id
        _downloadState.value = UpdateDownloadState.Pending(updateInfo)

        workManager.enqueueUniqueWork(
            WORK_NAME_DOWNLOAD,
            ExistingWorkPolicy.REPLACE,
            request
        )
        true
    }

    suspend fun retryDownload(): Boolean {
        val updateInfo = activeUpdateInfo.value ?: return false
        return startDownload(updateInfo)
    }

    suspend fun cancelDownload() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(WORK_NAME_DOWNLOAD)
        cleanupDownloadedApks()
        clearActiveDownloadState()
    }

    suspend fun installDownloadedApk(): UpdateInstallResult = withContext(Dispatchers.IO) {
        val state = _downloadState.value as? UpdateDownloadState.Completed
            ?: return@withContext UpdateInstallResult.Error("当前没有可安装的更新包")

        val apkFile = File(state.filePath)
        val result = installManager.installApk(apkFile)
        if (result is UpdateInstallResult.Started) {
            markPendingCleanup(apkFile.absolutePath)
        }
        return@withContext result
    }

    fun createInstallPermissionSettingsIntent(): Intent {
        return installManager.createInstallPermissionSettingsIntent()
    }

    fun handleAppLaunchCleanup() {
        val pendingPath = prefs.getString(KEY_PENDING_CLEANUP_PATH, null)
        val requestedAt = prefs.getLong(KEY_PENDING_CLEANUP_REQUESTED_AT, 0L)
        if (!pendingPath.isNullOrBlank() && requestedAt > 0L && getPackageLastUpdateTime() >= requestedAt) {
            File(pendingPath).delete()
            cleanupDownloadedApks()
            clearActiveDownloadState()
            clearPendingCleanup()
            return
        }

        val currentPath = activeDownloadPath.value
        if (!currentPath.isNullOrBlank() && !File(currentPath).exists()) {
            clearActiveDownloadState()
            cleanupDownloadedApks()
            return
        }

        cleanupDownloadedApks(keepPath = currentPath?.takeIf { File(it).exists() })
    }

    private fun mapDownloadState(workInfo: WorkInfo?): UpdateDownloadState {
        val updateInfo = activeUpdateInfo.value
        val downloadPath = activeDownloadPath.value
        val downloadFileExists = !downloadPath.isNullOrBlank() && File(downloadPath).exists()

        if (workInfo == null) {
            return if (updateInfo != null && downloadFileExists && !downloadPath.isNullOrBlank()) {
                UpdateDownloadState.Completed(updateInfo, downloadPath)
            } else {
                if (!downloadFileExists && activeWorkId.value == null) {
                    clearActiveDownloadState()
                }
                UpdateDownloadState.Idle
            }
        }

        return when (workInfo.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                if (updateInfo != null) UpdateDownloadState.Pending(updateInfo) else UpdateDownloadState.Idle
            }

            WorkInfo.State.RUNNING -> {
                if (updateInfo == null) {
                    UpdateDownloadState.Idle
                } else {
                    UpdateDownloadState.Downloading(
                        updateInfo = updateInfo,
                        progress = workInfo.progress.getFloat(UpdateDownloadWorker.KEY_PROGRESS, 0f),
                        downloadedBytes = workInfo.progress.getLong(UpdateDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                        totalBytes = workInfo.progress.getLong(UpdateDownloadWorker.KEY_TOTAL_BYTES, 0L),
                        speedBytes = workInfo.progress.getLong(UpdateDownloadWorker.KEY_SPEED_BYTES, 0L),
                    )
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                val filePath = workInfo.outputData.getString(UpdateDownloadWorker.KEY_FILE_PATH)
                    ?: downloadPath
                if (updateInfo != null && !filePath.isNullOrBlank() && File(filePath).exists()) {
                    persistDownloadPath(filePath)
                    activeDownloadPath.value = filePath
                    UpdateDownloadState.Completed(updateInfo, filePath)
                } else {
                    UpdateDownloadState.Failed(updateInfo, "下载完成，但安装包不存在")
                }
            }

            WorkInfo.State.FAILED -> {
                val errorMessage = workInfo.outputData.getString(UpdateDownloadWorker.KEY_ERROR_MESSAGE)
                    ?: "下载失败"
                UpdateDownloadState.Failed(updateInfo, errorMessage)
            }

            WorkInfo.State.CANCELLED -> {
                clearActiveDownloadState()
                UpdateDownloadState.Idle
            }
        }
    }

    private fun initialDownloadState(): UpdateDownloadState {
        val updateInfo = activeUpdateInfo.value
        val downloadPath = activeDownloadPath.value
        return if (updateInfo != null && !downloadPath.isNullOrBlank() && File(downloadPath).exists()) {
            UpdateDownloadState.Completed(updateInfo, downloadPath)
        } else {
            UpdateDownloadState.Idle
        }
    }

    private fun buildTargetFile(updateInfo: UpdateInfo): File {
        val safeName = updateInfo.apkFileName
            .ifBlank { "AudioChannelGuard-${updateInfo.latestVersionName}.apk" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(getUpdatesDir(), safeName)
    }

    private fun getUpdatesDir(): File {
        return File(appContext.filesDir, "updates").apply { mkdirs() }
    }

    private fun cleanupDownloadedApks(keepPath: String? = null) {
        getUpdatesDir().listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("apk", ignoreCase = true) && file.absolutePath != keepPath) {
                file.delete()
            }
        }
    }

    private fun ensureEnoughStorage(requiredBytes: Long) {
        if (requiredBytes <= 0L) return
        val availableBytes = getUpdatesDir().usableSpace.coerceAtLeast(0L)
        if (availableBytes < requiredBytes + STORAGE_RESERVE_BYTES) {
            throw IllegalStateException("存储空间不足，无法下载更新包")
        }
    }

    private fun markPendingCleanup(filePath: String) {
        prefs.edit()
            .putString(KEY_PENDING_CLEANUP_PATH, filePath)
            .putLong(KEY_PENDING_CLEANUP_REQUESTED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearPendingCleanup() {
        prefs.edit()
            .remove(KEY_PENDING_CLEANUP_PATH)
            .remove(KEY_PENDING_CLEANUP_REQUESTED_AT)
            .apply()
    }

    private fun clearActiveDownloadState() {
        prefs.edit()
            .remove(KEY_WORK_ID)
            .remove(KEY_UPDATE_INFO)
            .remove(KEY_DOWNLOAD_PATH)
            .apply()
        activeWorkId.value = null
        activeUpdateInfo.value = null
        activeDownloadPath.value = null
        _downloadState.value = UpdateDownloadState.Idle
    }

    private fun persistWorkId(workId: UUID) {
        prefs.edit().putString(KEY_WORK_ID, workId.toString()).apply()
    }

    private fun loadWorkId(): UUID? {
        val workId = prefs.getString(KEY_WORK_ID, null) ?: return null
        return try {
            UUID.fromString(workId)
        } catch (_: Exception) {
            null
        }
    }

    private fun persistDownloadPath(path: String) {
        prefs.edit().putString(KEY_DOWNLOAD_PATH, path).apply()
    }

    private fun loadDownloadPath(): String? {
        return prefs.getString(KEY_DOWNLOAD_PATH, null)
    }

    private fun persistUpdateInfo(updateInfo: UpdateInfo) {
        val json = JSONObject().apply {
            put("currentVersionName", updateInfo.currentVersionName)
            put("latestVersionName", updateInfo.latestVersionName)
            put("releaseName", updateInfo.releaseName)
            put("releaseNotes", updateInfo.releaseNotes)
            put("releaseUrl", updateInfo.releaseUrl)
            put("apkDownloadUrl", updateInfo.apkDownloadUrl)
            put("apkFileName", updateInfo.apkFileName)
            put("apkSizeBytes", updateInfo.apkSizeBytes)
            put("downloadUrls", JSONArray(updateInfo.downloadUrls))
        }
        prefs.edit().putString(KEY_UPDATE_INFO, json.toString()).apply()
    }

    private fun loadUpdateInfo(): UpdateInfo? {
        val jsonString = prefs.getString(KEY_UPDATE_INFO, null) ?: return null
        return try {
            val json = JSONObject(jsonString)
            val urlsJson = json.optJSONArray("downloadUrls")
            val downloadUrls = buildList {
                if (urlsJson != null) {
                    for (index in 0 until urlsJson.length()) {
                        val value = urlsJson.optString(index)
                        if (value.isNotBlank()) add(value)
                    }
                }
            }
            UpdateInfo(
                currentVersionName = json.optString("currentVersionName"),
                latestVersionName = json.optString("latestVersionName"),
                releaseName = json.optString("releaseName"),
                releaseNotes = json.optString("releaseNotes"),
                releaseUrl = json.optString("releaseUrl"),
                apkDownloadUrl = json.optString("apkDownloadUrl"),
                downloadUrls = downloadUrls,
                apkFileName = json.optString("apkFileName"),
                apkSizeBytes = json.optLong("apkSizeBytes"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getPackageLastUpdateTime(): Long {
        return try {
            val packageInfo = appContext.packageManager.getPackageInfo(
                appContext.packageName,
                0
            )
            packageInfo.lastUpdateTime
        } catch (_: Exception) {
            0L
        }
    }

    private fun observeWorkInfo(workId: UUID) = flow {
        while (currentCoroutineContext().isActive) {
            val workInfo = runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull()
            emit(workInfo)
            if (workInfo == null || workInfo.state.isFinished) {
                break
            }
            delay(500L)
        }
    }
}
