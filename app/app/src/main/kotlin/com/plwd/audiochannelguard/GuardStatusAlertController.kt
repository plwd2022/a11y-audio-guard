package com.plwd.audiochannelguard

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class GuardStatusAlertController(context: Context) {

    data class Snapshot(
        val status: GuardStatus,
        val headsetName: String?,
        val hasHeadset: Boolean,
        val heldRouteMessage: String?,
        val canManuallyReleaseHeldRoute: Boolean,
    )

    private enum class AlertKind {
        RECOVERING,
        FIXED,
        HELD_ROUTE,
        HEADSET_DISCONNECTED,
    }

    private data class AlertSpec(
        val kind: AlertKind,
        val key: String,
        val text: String,
        val timeoutMs: Long,
        val cooldownMs: Long,
        val delayMs: Long = 0L,
        val showReleaseAction: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var initialized = false
    private var lastHadHeadset = false
    private var activeSpec: AlertSpec? = null
    private var lastPostedKey: String? = null
    private var lastPostedAtElapsedMs = 0L
    private var pendingSpec: AlertSpec? = null
    private var pendingShowRunnable: Runnable? = null
    private var pendingClearRunnable: Runnable? = null

    fun onStateChanged(snapshot: Snapshot) {
        val hadHeadsetBefore = if (initialized) lastHadHeadset else snapshot.hasHeadset
        initialized = true
        lastHadHeadset = snapshot.hasHeadset

        if (!shouldUseStatusAlerts()) {
            clear()
            return
        }

        val spec = buildAlertSpec(snapshot, hadHeadsetBefore)
        if (spec == null) {
            cancelPendingShow()
            clearIfNoLongerRelevant(snapshot)
            return
        }

        if (spec.delayMs > 0L) {
            scheduleDelayed(spec)
        } else {
            cancelPendingShow()
            showNow(spec)
        }
    }

    fun clear() {
        cancelPendingShow()
        cancelPendingClear()
        activeSpec = null
        lastPostedKey = null
        lastPostedAtElapsedMs = 0L
        notificationManager.cancel(AudioGuardService.ALERT_NOTIFICATION_ID)
    }

    private fun shouldUseStatusAlerts(): Boolean {
        if (!AudioGuardApp.isStatusAlertWhenPersistentHiddenEnabled(appContext)) {
            return false
        }
        if (!notificationManager.areNotificationsEnabled()) {
            return false
        }

        val alertChannel =
            notificationManager.getNotificationChannel(AudioGuardService.ALERT_CHANNEL_ID)
                ?: return false
        val persistentChannel =
            notificationManager.getNotificationChannel(AudioGuardService.PERSISTENT_CHANNEL_ID)
                ?: return false

        if (alertChannel.importance == NotificationManager.IMPORTANCE_NONE) {
            return false
        }

        return persistentChannel.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun buildAlertSpec(snapshot: Snapshot, hadHeadsetBefore: Boolean): AlertSpec? {
        snapshot.heldRouteMessage?.let { message ->
            return AlertSpec(
                kind = AlertKind.HELD_ROUTE,
                key = "held:$message:${snapshot.canManuallyReleaseHeldRoute}",
                text = message,
                timeoutMs = 20_000L,
                cooldownMs = 10_000L,
                showReleaseAction = snapshot.canManuallyReleaseHeldRoute,
            )
        }

        return when (snapshot.status) {
            GuardStatus.FIXED -> AlertSpec(
                kind = AlertKind.FIXED,
                key = "fixed:${snapshot.headsetName.orEmpty()}",
                text = "已将读屏声音收回到 ${snapshot.headsetName ?: "耳机"}",
                timeoutMs = 6_000L,
                cooldownMs = 15_000L,
            )

            GuardStatus.FIXED_BUT_SPEAKER_ROUTE -> AlertSpec(
                kind = AlertKind.FIXED,
                key = "fixed_speaker:${snapshot.headsetName.orEmpty()}",
                text = "已收回到 ${snapshot.headsetName ?: "耳机"}，如当前正常请忽略",
                timeoutMs = 8_000L,
                cooldownMs = 15_000L,
            )

            GuardStatus.HIJACKED -> AlertSpec(
                kind = AlertKind.RECOVERING,
                key = "hijacked:${snapshot.headsetName.orEmpty()}",
                text = "检测到读屏声音可能外放，正在尝试恢复",
                timeoutMs = 15_000L,
                cooldownMs = 10_000L,
                delayMs = 1_200L,
            )

            GuardStatus.NO_HEADSET -> {
                if (!hadHeadsetBefore) {
                    null
                } else {
                    AlertSpec(
                        kind = AlertKind.HEADSET_DISCONNECTED,
                        key = "disconnect",
                        text = "耳机已断开，保护会在重新接入后恢复",
                        timeoutMs = 5_000L,
                        cooldownMs = 8_000L,
                    )
                }
            }

            GuardStatus.NORMAL -> null
        }
    }

    private fun scheduleDelayed(spec: AlertSpec) {
        if (pendingSpec == spec) return

        cancelPendingShow()
        pendingSpec = spec
        pendingShowRunnable = Runnable {
            if (pendingSpec == spec) {
                pendingSpec = null
                pendingShowRunnable = null
                showNow(spec)
            }
        }.also { handler.postDelayed(it, spec.delayMs) }
    }

    private fun showNow(spec: AlertSpec) {
        val now = SystemClock.elapsedRealtime()
        if (spec.key == lastPostedKey && now - lastPostedAtElapsedMs < spec.cooldownMs) {
            return
        }

        notificationManager.notify(
            AudioGuardService.ALERT_NOTIFICATION_ID,
            buildNotification(spec)
        )
        activeSpec = spec
        lastPostedKey = spec.key
        lastPostedAtElapsedMs = now
        scheduleClear(spec)
    }

    private fun buildNotification(spec: AlertSpec): Notification {
        val builder = NotificationCompat.Builder(appContext, AudioGuardService.ALERT_CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setSmallIcon(R.drawable.ic_headset)
            .setContentIntent(createMainActivityPendingIntent())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(spec.timeoutMs)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (spec.showReleaseAction) {
            builder.addAction(
                R.drawable.ic_headset,
                "尝试解除外放占用",
                AudioGuardService.createReleaseHeldRoutePendingIntent(appContext)
            )
        }

        return builder.build()
    }

    private fun createMainActivityPendingIntent(): PendingIntent {
        val contentIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            0,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun scheduleClear(spec: AlertSpec) {
        cancelPendingClear()
        pendingClearRunnable = Runnable {
            if (activeSpec?.key == spec.key) {
                notificationManager.cancel(AudioGuardService.ALERT_NOTIFICATION_ID)
                activeSpec = null
            }
            pendingClearRunnable = null
        }.also { handler.postDelayed(it, spec.timeoutMs) }
    }

    private fun clearIfNoLongerRelevant(snapshot: Snapshot) {
        when (activeSpec?.kind) {
            AlertKind.RECOVERING -> {
                if (snapshot.status != GuardStatus.HIJACKED) {
                    clear()
                }
            }

            AlertKind.HELD_ROUTE -> {
                if (snapshot.heldRouteMessage == null) {
                    clear()
                }
            }

            AlertKind.HEADSET_DISCONNECTED -> {
                if (snapshot.hasHeadset) {
                    clear()
                }
            }

            AlertKind.FIXED, null -> Unit
        }
    }

    private fun cancelPendingShow() {
        pendingShowRunnable?.let(handler::removeCallbacks)
        pendingShowRunnable = null
        pendingSpec = null
    }

    private fun cancelPendingClear() {
        pendingClearRunnable?.let(handler::removeCallbacks)
        pendingClearRunnable = null
    }
}
