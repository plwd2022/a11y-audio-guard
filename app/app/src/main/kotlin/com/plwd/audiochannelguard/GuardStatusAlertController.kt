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

    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var initialized = false
    private var lastHadHeadset = false
    private var activeSpec: GuardAlertSpec? = null
    private var lastPostedKey: String? = null
    private var lastPostedAtElapsedMs = 0L
    private var pendingSpec: GuardAlertSpec? = null
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

        val input = snapshot.toDecisionInput(hadHeadsetBefore)
        val spec = GuardAlertResolver.resolveSpec(input)
        if (spec == null) {
            cancelPendingShow()
            clearIfNoLongerRelevant(input)
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

    private fun scheduleDelayed(spec: GuardAlertSpec) {
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

    private fun showNow(spec: GuardAlertSpec) {
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

    private fun buildNotification(spec: GuardAlertSpec): Notification {
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

    private fun scheduleClear(spec: GuardAlertSpec) {
        cancelPendingClear()
        pendingClearRunnable = Runnable {
            if (activeSpec?.key == spec.key) {
                notificationManager.cancel(AudioGuardService.ALERT_NOTIFICATION_ID)
                activeSpec = null
            }
            pendingClearRunnable = null
        }.also { handler.postDelayed(it, spec.timeoutMs) }
    }

    private fun clearIfNoLongerRelevant(input: GuardAlertDecisionInput) {
        if (GuardAlertResolver.shouldClearActiveAlert(activeSpec?.kind, input)) {
            clear()
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

    private fun Snapshot.toDecisionInput(hadHeadsetBefore: Boolean): GuardAlertDecisionInput {
        return GuardAlertDecisionInput(
            status = status,
            headsetName = headsetName,
            hasHeadset = hasHeadset,
            heldRouteMessage = heldRouteMessage,
            canManuallyReleaseHeldRoute = canManuallyReleaseHeldRoute,
            hadHeadsetBefore = hadHeadsetBefore,
        )
    }
}
