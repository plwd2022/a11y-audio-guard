package com.plwd.audiochannelguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) {
            if (AudioGuardApp.isGuardEnabled(context)) {
                if (!AudioGuardService.start(context)) {
                    ServiceGuard.enqueueRestart(context)
                }
            }
        }
    }
}
