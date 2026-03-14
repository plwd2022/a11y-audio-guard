package com.plwd.audiochannelguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AudioGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
