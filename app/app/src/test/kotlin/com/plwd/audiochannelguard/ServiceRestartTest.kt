package com.plwd.audiochannelguard

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33], manifest = Config.NONE, application = Application::class)
class ServiceRestartTest {
    @Test fun `background start rejection is distinguishable from transient failure`() {
        val app = RuntimeEnvironment.getApplication()
        val denied = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? {
                throw ForegroundServiceStartNotAllowedException("Background start denied")
            }
        }
        assertEquals(GuardServiceStartResult.BACKGROUND_RESTRICTED, AudioGuardService.startForRecovery(denied))
        val transient = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? {
                throw IllegalStateException("Temporary failure")
            }
        }
        assertEquals(GuardServiceStartResult.FAILED, AudioGuardService.startForRecovery(transient))
    }

    @Test fun `recovery prompt opens activity and is removed when protection is stopped`() {
        val app = RuntimeEnvironment.getApplication()
        AudioGuardApp.setGuardEnabledSync(app, true)
        AudioGuardService.showRestartRequiredNotification(app)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val notification = notifications.allNotifications.single()
        val pending = shadowOf(notification.contentIntent)
        assertTrue(pending.isActivity)
        assertEquals(MainActivity::class.java.name, pending.savedIntent.component?.className)
        AudioGuardApp.setGuardEnabledSync(app, false)
        AudioGuardService.stop(app)
        assertTrue(notifications.allNotifications.isEmpty())
    }
}
