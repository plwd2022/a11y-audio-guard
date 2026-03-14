package com.plwd.audiochannelguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import java.security.MessageDigest

class AudioGuardApp : Application() {

    companion object {
        private const val PREFS_NAME = "audio_guard_prefs"
        private const val KEY_ENABLED = "guard_enabled"

        // Release keystore (plwd_cn.keystore) signing certificate SHA-256
        private const val EXPECTED_CERT_HASH =
            "222b4c298ca06cb38792288d3b5bfa5c77c00e423cc2ffc0b024b185e447fb52"

        var isTampered = false
            private set

        fun isGuardEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        fun setGuardEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (!verifySignature()) {
            isTampered = true
            Toast.makeText(this, "签名校验失败，应用可能被篡改", Toast.LENGTH_LONG).show()
        }
        ServiceGuard.schedulePeriodicCheck(this)
    }

    private fun verifySignature(): Boolean {
        try {
            val info = packageManager.getPackageInfo(
                packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signers = info.signingInfo?.apkContentsSigners ?: return false
            val digest = MessageDigest.getInstance("SHA-256")
            return signers.any { sig ->
                digest.reset()
                digest.digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) } == EXPECTED_CERT_HASH
            }
        } catch (_: Exception) {
            return false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            AudioGuardService.CHANNEL_ID,
            "声道守护",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示声道守护运行状态"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
