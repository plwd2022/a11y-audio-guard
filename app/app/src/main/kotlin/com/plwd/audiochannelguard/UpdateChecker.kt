package com.plwd.audiochannelguard

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val currentVersionName: String,
    val latestVersionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkDownloadUrl: String,
    val downloadUrls: List<String>,
    val apkFileName: String,
    val apkSizeBytes: Long,
)

sealed interface UpdateCheckResult {
    data class HasUpdate(val updateInfo: UpdateInfo) : UpdateCheckResult
    data class UpToDate(val updateInfo: UpdateInfo) : UpdateCheckResult

    object NoNetwork : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

class UpdateChecker(private val context: Context) {

    companion object {
        private const val REPO_OWNER = "plwd2022"
        private const val REPO_NAME = "a11y-audio-guard"
        private const val REQUEST_TIMEOUT_MS = 15000
    }

    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            return@withContext UpdateCheckResult.NoNetwork
        }

        val currentVersionName = getCurrentVersionName()
        val release = try {
            fetchLatestRelease()
        } catch (exception: Exception) {
            return@withContext UpdateCheckResult.Error("检查更新失败: ${exception.message}")
        }

        val latestVersionName = normalizeVersionName(release.tagName)
        val apkAsset = release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank()
        }
            ?: return@withContext UpdateCheckResult.Error("最新版本未附带 APK 文件")
        val downloadUrls = buildDownloadUrls(apkAsset.downloadUrl)
        val updateInfo = UpdateInfo(
            currentVersionName = currentVersionName,
            latestVersionName = latestVersionName,
            releaseName = release.name.ifBlank { latestVersionName },
            releaseNotes = release.body.ifBlank { "暂无更新说明" },
            releaseUrl = release.htmlUrl,
            apkDownloadUrl = apkAsset.downloadUrl,
            downloadUrls = downloadUrls,
            apkFileName = apkAsset.name,
            apkSizeBytes = apkAsset.sizeBytes
        )

        if (!isNewerVersion(release.tagName, currentVersionName)) {
            return@withContext UpdateCheckResult.UpToDate(updateInfo)
        }

        return@withContext UpdateCheckResult.HasUpdate(updateInfo)
    }

    private fun fetchLatestRelease(): GitHubRelease {
        val url = URL("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AudioChannelGuard/${getCurrentVersionName()}")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val message = connection.responseMessage ?: "HTTP $responseCode"
                throw IOException("GitHub 返回 $responseCode: $message")
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            parseRelease(JSONObject(responseBody))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(jsonObject: JSONObject): GitHubRelease {
        val assetsJson = jsonObject.optJSONArray("assets")
            ?: throw IOException("GitHub 返回的发布信息缺少 assets")
        val assets = buildList {
            for (index in 0 until assetsJson.length()) {
                val asset = assetsJson.optJSONObject(index) ?: continue
                add(
                    GitHubAsset(
                        name = asset.optString("name"),
                        downloadUrl = asset.optString("browser_download_url"),
                        sizeBytes = asset.optLong("size")
                    )
                )
            }
        }

        val tagName = jsonObject.optString("tag_name")
        val htmlUrl = jsonObject.optString("html_url")
        if (tagName.isBlank() || htmlUrl.isBlank()) {
            throw IOException("GitHub 返回的发布信息不完整")
        }

        return GitHubRelease(
            tagName = tagName,
            name = jsonObject.optString("name"),
            body = jsonObject.optString("body"),
            htmlUrl = htmlUrl,
            assets = assets
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun normalizeVersionName(versionName: String): String {
        return versionName.removePrefix("v").removePrefix("V")
    }

    private fun isNewerVersion(latestVersionName: String, currentVersionName: String): Boolean {
        val latest = normalizeVersionName(latestVersionName)
        val current = normalizeVersionName(currentVersionName)

        val latestParts = latest.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }

        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrNull(index) ?: 0
            val currentPart = currentParts.getOrNull(index) ?: 0
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }

        val latestIsPreRelease = latest.contains("-")
        val currentIsPreRelease = current.contains("-")
        return currentIsPreRelease && !latestIsPreRelease
    }

    private fun buildDownloadUrls(originalUrl: String): List<String> {
        val proxyServers = listOf(
            "https://ghproxy.net/",
            "https://gitdl.cn/",
            "https://gh.ddlc.top/",
            "https://hub.fastgit.xyz/",
            "https://download.fastgit.org/"
        )
        return buildList {
            proxyServers.forEach { proxy ->
                add(proxy + originalUrl)
            }
            add(originalUrl)
        }.distinct()
    }

    private fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                0
            )
            packageInfo.versionName?.ifBlank { "未知版本" } ?: "未知版本"
        } catch (_: Exception) {
            "未知版本"
        }
    }
}

private data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val assets: List<GitHubAsset>,
)

private data class GitHubAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)
