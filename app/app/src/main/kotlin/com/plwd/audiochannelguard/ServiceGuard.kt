package com.plwd.audiochannelguard

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ServiceGuard(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val WORK_PERIODIC = "audio_guard_keep_alive"
        private const val WORK_RESTART = "audio_guard_restart"

        fun schedulePeriodicCheck(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceGuard>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueRestart(context: Context) {
            val request = OneTimeWorkRequestBuilder<ServiceGuard>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_RESTART,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override fun doWork(): Result {
        if (AudioGuardApp.isGuardEnabled(applicationContext) && !AudioGuardService.isRunning()) {
            AudioGuardService.start(applicationContext)
        }
        return Result.success()
    }
}
