package com.plwd.audiochannelguard

import android.app.Notification
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class AudioGuardService : Service() {

    companion object {
        const val CHANNEL_ID = "audio_guard_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "AudioGuardService"
        private const val ACTION_TRY_RELEASE_HELD_ROUTE =
            "com.plwd.audiochannelguard.action.TRY_RELEASE_HELD_ROUTE"

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

        fun start(context: Context): Boolean {
            return startServiceSafely(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioGuardService::class.java))
        }

        fun requestReleaseHeldRoute(context: Context): Boolean {
            return startServiceSafely(context, ACTION_TRY_RELEASE_HELD_ROUTE)
        }

        private fun startServiceSafely(context: Context, action: String? = null): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AudioGuardService::class.java).apply {
                this.action = action
            }
            return try {
                appContext.startForegroundService(intent)
                true
            } catch (exception: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Foreground service start not allowed: action=$action", exception)
                false
            } catch (exception: IllegalStateException) {
                Log.w(TAG, "Foreground service start failed: action=$action", exception)
                false
            } catch (exception: SecurityException) {
                Log.w(TAG, "Foreground service start blocked by security policy: action=$action", exception)
                false
            }
        }
    }

    interface OnServiceRebindListener {
        fun onRebind(monitor: AudioRouteMonitor)
    }

    private lateinit var monitor: AudioRouteMonitor
    private val monitorStatusListener: (GuardStatus) -> Unit = { status ->
        updateNotification(status)
    }
    private val monitorEnhancedStateListener: (EnhancedState) -> Unit = {
        if (::monitor.isInitialized) {
            updateNotification(monitor.getStatus())
        }
    }

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
        monitor.setEnhancedModeEnabled(AudioGuardApp.isEnhancedModeEnabled(this))
        monitor.setClassicBluetoothSoftGuardEnabled(AudioGuardApp.isClassicBluetoothSoftGuardEnabled(this))
        monitor.setClassicBluetoothWidebandEnabled(AudioGuardApp.isClassicBluetoothWidebandEnabled(this))
        monitor.addStatusListener(monitorStatusListener)
        monitor.addEnhancedStateListener(monitorEnhancedStateListener)

        startForeground(NOTIFICATION_ID, buildNotification("正在保护读屏声音"))
        monitor.start()
        AudioFixTile.requestTileRefresh(this)
        rebindListeners.forEach { it.onRebind(monitor) }
    }

    override fun onDestroy() {
        if (::monitor.isInitialized) {
            monitor.removeStatusListener(monitorStatusListener)
            monitor.removeEnhancedStateListener(monitorEnhancedStateListener)
            monitor.stop()
        }
        instance = null
        AudioFixTile.requestTileRefresh(this)
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
        if (::monitor.isInitialized &&
            intent?.action == ACTION_TRY_RELEASE_HELD_ROUTE
        ) {
            monitor.tryManualReleaseHeldRoute("通知栏尝试解除外放占用")
            updateNotification(monitor.getStatus())
        }
        return START_STICKY
    }

    private fun updateNotification(status: GuardStatus) {
        val heldRouteMessage = monitor.getHeldRouteMessage()
        val text = when {
            heldRouteMessage != null -> heldRouteMessage
            else -> when (monitor.getEnhancedState()) {
            EnhancedState.CLEAR_PROBE -> "增强保护观察中"
            EnhancedState.SUSPENDED_BY_CALL -> "增强保护已暂停（通话中）"
            EnhancedState.WAITING_HEADSET ->
                if (monitor.isEnhancedModeEnabled()) "增强保护等待耳机" else defaultStatusText(status)

            EnhancedState.ACTIVE -> {
                if (status == GuardStatus.FIXED || status == GuardStatus.FIXED_BUT_SPEAKER_ROUTE) {
                    val deviceName = monitor.findConnectedHeadset()?.productName ?: "耳机"
                    "增强保护中，已收回到 $deviceName"
                } else {
                    "增强保护中"
                }
            }

            EnhancedState.DISABLED -> defaultStatusText(status)
        }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
        AudioFixTile.requestTileRefresh(this)
    }

    private fun defaultStatusText(status: GuardStatus): String {
        return when (status) {
            GuardStatus.NORMAL -> "正在保护读屏声音"
            GuardStatus.FIXED -> {
                val deviceName = monitor.findConnectedHeadset()?.productName ?: "耳机"
                "已将读屏声音收回到 $deviceName"
            }
            GuardStatus.FIXED_BUT_SPEAKER_ROUTE -> {
                val deviceName = monitor.findConnectedHeadset()?.productName ?: "耳机"
                "已收回到 $deviceName，如当前正常请忽略"
            }
            GuardStatus.HIJACKED -> "检测到读屏声音可能外放"
            GuardStatus.NO_HEADSET -> "未检测到耳机"
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentIntent(pendingContentIntent)
            .setOngoing(true)

        if (::monitor.isInitialized && monitor.canManuallyReleaseHeldRoute()) {
            val actionIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, AudioGuardService::class.java).apply {
                    action = ACTION_TRY_RELEASE_HELD_ROUTE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_headset, "尝试解除外放占用", actionIntent)
        }

        return builder.build()
    }
}
