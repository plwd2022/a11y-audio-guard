package com.plwd.audiochannelguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class AudioGuardApp : Application() {

    companion object {
        private const val PREFS_NAME = "audio_guard_prefs"
        private const val KEY_ENABLED = "guard_enabled"

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
        ServiceGuard.schedulePeriodicCheck(this)
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
