package com.plwd.audiochannelguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

sealed interface UpdateInstallResult {
    object Started : UpdateInstallResult
    object PermissionRequired : UpdateInstallResult
    data class Error(val message: String) : UpdateInstallResult
}

class UpdateInstallManager(private val context: Context) {

    fun installApk(apkFile: File): UpdateInstallResult {
        val validationError = validateDownloadedApk(apkFile)
        if (validationError != null) {
            return UpdateInstallResult.Error(validationError)
        }

        if (!canInstallApk()) {
            return UpdateInstallResult.PermissionRequired
        }

        val apkUri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.updatefileprovider",
                apkFile
            )
        } catch (_: Exception) {
            return UpdateInstallResult.Error("无法生成安装包访问地址")
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            }
        }

        return try {
            context.startActivity(intent)
            UpdateInstallResult.Started
        } catch (exception: Exception) {
            UpdateInstallResult.Error("无法启动安装界面: ${exception.message}")
        }
    }

    fun canInstallApk(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun createInstallPermissionSettingsIntent(): Intent {
        return Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    internal fun validateDownloadedApk(apkFile: File): String? {
        if (!apkFile.exists()) return "安装包不存在"
        if (!apkFile.isFile || !apkFile.canRead()) return "安装包不可读"
        if (!apkFile.name.endsWith(".apk", ignoreCase = true)) return "安装包格式不正确"
        if (apkFile.length() <= 0L) return "安装包为空"

        val packageInfo = readArchiveInfo(apkFile) ?: return "安装包损坏或无法解析"
        if (packageInfo.packageName != context.packageName) {
            return "安装包包名不匹配"
        }
        if (!matchesExpectedSigner(packageInfo)) {
            return "安装包签名不匹配"
        }
        return null
    }

    private fun readArchiveInfo(apkFile: File): PackageInfo? {
        return try {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun matchesExpectedSigner(packageInfo: PackageInfo): Boolean {
        return try {
            val expectedHash = AudioGuardApp.getExpectedCertHash()
            val digest = MessageDigest.getInstance("SHA-256")
            val signers = packageInfo.signingInfo?.apkContentsSigners ?: return false
            signers.any { signature ->
                digest.reset()
                digest.digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) } == expectedHash
            }
        } catch (_: Exception) {
            false
        }
    }
}
