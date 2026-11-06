package com.ernesto.mirastreador

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Pide la ubicación por SMS cada cierto tiempo, aunque la app esté cerrada. */
class AutoTrackWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (TrackerStore.autoMinutes(ctx) <= 0) return Result.success()
        SmsSender.send(ctx, Commands.text(ctx, "locate"))
        return Result.success()
    }

    companion object {
        private const val NAME = "seguimiento_automatico"

        /** Programa (o cancela) el seguimiento según la configuración guardada. */
        fun schedule(ctx: Context) {
            val minutes = TrackerStore.autoMinutes(ctx)
            val wm = WorkManager.getInstance(ctx)
            if (minutes <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<AutoTrackWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
