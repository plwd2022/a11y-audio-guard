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

internal enum class GuardServiceStartResult { STARTED, BACKGROUND_RESTRICTED, FAILED }

class AudioGuardService : Service() {

    companion object {
        const val PERSISTENT_CHANNEL_ID = "audio_guard_channel"
        const val ALERT_CHANNEL_ID = "audio_guard_alert_channel"
        const val PERSISTENT_NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
        private const val RESTART_NOTIFICATION_ID = 3
        private const val TAG = "AudioGuardService"
        const val ACTION_TRY_RELEASE_HELD_ROUTE =
            "com.plwd.audiochannelguard.action.TRY_RELEASE_HELD_ROUTE"

        @Volatile private var instance: AudioGuardService? = null
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
            return startServiceSafely(context) == GuardServiceStartResult.STARTED
        }

        internal fun startForRecovery(context: Context): GuardServiceStartResult {
            return startServiceSafely(context)
        }

        fun stop(context: Context) {
            cancelRestartNotification(context)
            context.stopService(Intent(context, AudioGuardService::class.java))
        }

        fun requestReleaseHeldRoute(context: Context): Boolean {
            return startServiceSafely(context, ACTION_TRY_RELEASE_HELD_ROUTE) == GuardServiceStartResult.STARTED
        }

        fun refreshNotifications() {
            instance?.refreshNotifications()
        }

        fun cancelAlertNotification(context: Context) {
            val service = instance
            if (service != null && service::alertController.isInitialized) {
                service.alertController.clear()
            } else {
                context.applicationContext.getSystemService(NotificationManager::class.java)
                    .cancel(ALERT_NOTIFICATION_ID)
            }
        }

        fun areNotificationsEnabled(context: Context): Boolean {
            return context.applicationContext.getSystemService(NotificationManager::class.java)
                .areNotificationsEnabled()
        }

        fun isPersistentChannelBlocked(context: Context): Boolean {
            return isChannelBlocked(context, PERSISTENT_CHANNEL_ID)
        }

        fun isAlertChannelBlocked(context: Context): Boolean {
            return isChannelBlocked(context, ALERT_CHANNEL_ID)
        }

        fun createReleaseHeldRoutePendingIntent(context: Context): PendingIntent {
            val appContext = context.applicationContext
            return PendingIntent.getService(
                appContext,
                1,
                Intent(appContext, AudioGuardService::class.java).apply {
                    action = ACTION_TRY_RELEASE_HELD_ROUTE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        internal fun showRestartRequiredNotification(context: Context) {
            if (!AudioGuardApp.isGuardEnabled(context) || isRunning()) return
            val pendingIntent = PendingIntent.getActivity(
                context, 3, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, PERSISTENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_headset)
                .setContentTitle("读屏声音保护已停止")
                .setContentText("系统未允许后台恢复，点击打开应用恢复保护")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            try {
                context.getSystemService(NotificationManager::class.java)
                    .notify(RESTART_NOTIFICATION_ID, notification)
            } catch (exception: SecurityException) {
                Log.w(TAG, "Restart notification permission denied", exception)
            }
        }

        private fun cancelRestartNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(RESTART_NOTIFICATION_ID)
        }

        private fun startServiceSafely(context: Context, action: String? = null): GuardServiceStartResult {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AudioGuardService::class.java).apply {
                this.action = action
            }
            return try {
                appContext.startForegroundService(intent)
                GuardServiceStartResult.STARTED
            } catch (exception: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Foreground service start not allowed: action=$action", exception)
                GuardServiceStartResult.BACKGROUND_RESTRICTED
            } catch (exception: IllegalStateException) {
                Log.w(TAG, "Foreground service start failed: action=$action", exception)
                GuardServiceStartResult.FAILED
            } catch (exception: SecurityException) {
                Log.w(TAG, "Foreground service start blocked by security policy: action=$action", exception)
                GuardServiceStartResult.BACKGROUND_RESTRICTED
            }
        }

        private fun isChannelBlocked(context: Context, channelId: String): Boolean {
            val channel = context.applicationContext.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(channelId)
            return channel?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    interface OnServiceRebindListener {
        fun onRebind(monitor: AudioRouteMonitor)
    }

    private lateinit var monitor: AudioRouteMonitor
    private lateinit var alertController: GuardStatusAlertController
    private val monitorStatusListener: (GuardStatus) -> Unit = { status ->
        refreshNotifications(status)
    }
    private val monitorEnhancedStateListener: (EnhancedState) -> Unit = {
        if (::monitor.isInitialized) {
            refreshNotifications()
        }
    }

    override fun onCreate() {
        super.onCreate()
        cancelRestartNotification(this)
        if (AudioGuardApp.isTampered) {
            startForeground(
                PERSISTENT_NOTIFICATION_ID,
                buildPersistentNotification("签名校验失败")
            )
            AudioGuardApp.setGuardEnabled(this, false)
            stopSelf()
            return
        }
        startForeground(
            PERSISTENT_NOTIFICATION_ID,
            buildPersistentNotification("正在保护读屏声音")
        )
        alertController = GuardStatusAlertController(this)

        monitor = AudioRouteMonitor(this)
        monitor.setEnhancedModeEnabled(AudioGuardApp.isEnhancedModeEnabled(this))
        monitor.setClassicBluetoothSoftGuardEnabled(AudioGuardApp.isClassicBluetoothSoftGuardEnabled(this))
        monitor.setClassicBluetoothWidebandEnabled(AudioGuardApp.isClassicBluetoothWidebandEnabled(this))
        monitor.addStatusListener(monitorStatusListener)
        monitor.addEnhancedStateListener(monitorEnhancedStateListener)

        instance = this
        monitor.start()
        refreshNotifications()
        AudioFixTile.requestTileRefresh(this)
        rebindListeners.forEach { it.onRebind(monitor) }
    }

    override fun onDestroy() {
        if (::alertController.isInitialized) {
            alertController.clear()
        } else {
            cancelAlertNotification(this)
        }
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
            refreshNotifications()
        }
        return START_STICKY
    }

    private fun refreshNotifications(status: GuardStatus? = null) {
        val notificationSnapshotInputs = monitor.getNotificationSnapshotInputs(status)
        val publicProjectionInput = notificationSnapshotInputs.publicProjectionInput
        val publicProjection = GuardPublicProjectionResolver.resolve(
            serviceRunning = true,
            input = publicProjectionInput,
        )
        updatePersistentNotification(
            publicProjection = publicProjection,
            showReleaseAction = publicProjectionInput.canManuallyReleaseHeldRoute,
        )
        if (::alertController.isInitialized) {
            alertController.onStateChanged(notificationSnapshotInputs.alertSnapshot)
        }
        AudioFixTile.requestTileRefresh(this)
    }

    private fun updatePersistentNotification(
        publicProjection: GuardPublicProjection,
        showReleaseAction: Boolean,
    ) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            PERSISTENT_NOTIFICATION_ID,
            buildPersistentNotification(
                text = publicProjection.persistentNotificationText,
                showReleaseAction = showReleaseAction,
            )
        )
    }

    private fun buildPersistentNotification(
        text: String,
        showReleaseAction: Boolean = false,
    ): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, PERSISTENT_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentIntent(pendingContentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (showReleaseAction) {
            builder.addAction(
                R.drawable.ic_headset,
                "尝试解除外放占用",
                createReleaseHeldRoutePendingIntent(this)
            )
        }

        return builder.build()
    }
}
