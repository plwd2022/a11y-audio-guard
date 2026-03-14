package com.plwd.audiochannelguard

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AudioGuardService : Service() {

    companion object {
        const val CHANNEL_ID = "audio_guard_channel"
        const val NOTIFICATION_ID = 1

        private var instance: AudioGuardService? = null
        private val rebindListeners = mutableListOf<OnServiceRebindListener>()

        fun isRunning(): Boolean = instance != null

        fun getMonitor(): AudioRouteMonitor? = instance?.monitor

        fun addRebindListener(listener: OnServiceRebindListener) {
            rebindListeners.add(listener)
        }

        fun removeRebindListener(listener: OnServiceRebindListener) {
            rebindListeners.remove(listener)
        }

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, AudioGuardService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioGuardService::class.java))
        }
    }

    interface OnServiceRebindListener {
        fun onRebind(monitor: AudioRouteMonitor)
    }

    private lateinit var monitor: AudioRouteMonitor

    override fun onCreate() {
        super.onCreate()
        if (AudioGuardApp.isTampered) {
            startForeground(NOTIFICATION_ID, buildNotification("签名校验失败"))
            AudioGuardApp.setGuardEnabled(this, false)
            stopSelf()
            return
        }
        instance = this

        monitor = AudioRouteMonitor(this)
        monitor.onStatusChanged = { status ->
            updateNotification(status)
        }

        startForeground(NOTIFICATION_ID, buildNotification("声道守护运行中"))
        monitor.start()
        rebindListeners.forEach { it.onRebind(monitor) }
    }

    override fun onDestroy() {
        if (::monitor.isInitialized) monitor.stop()
        instance = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AudioGuardApp.isGuardEnabled(this)) {
            ServiceGuard.enqueueRestart(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun updateNotification(status: GuardStatus) {
        val text = when (status) {
            GuardStatus.NORMAL -> "声道守护运行中"
            GuardStatus.FIXED -> {
                val deviceName = monitor.findConnectedHeadset()?.productName ?: "耳机"
                "已将声道恢复到 $deviceName"
            }
            GuardStatus.HIJACKED -> "检测到声道仍在内置设备"
            GuardStatus.NO_HEADSET -> "未检测到耳机"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
