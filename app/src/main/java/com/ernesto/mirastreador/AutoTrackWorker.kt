package com.ernesto.mirastreador

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Seguimiento automático inteligente: pide la ubicación cada cierto tiempo,
 * solo dentro del horario elegido, y se pausa si el rastreador deja de responder.
 */
class AutoTrackWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString("id") ?: TrackerStore.LEGACY_ID
        if (id !in TrackerStore.ids(ctx)) return Result.success()
        if (TrackerStore.autoMinutes(ctx, id) <= 0) return Result.success()
        if (!inWindow(ctx, id)) return Result.success()
        if (TrackerStore.autoPaused(ctx, id)) return Result.success()

        // ¿Quedó sin respuesta la solicitud anterior?
        val req = TrackerStore.lastRequestAt(ctx, id)
        val rep = TrackerStore.lastReply(ctx, id)?.second ?: 0L
        if (req > 0 && req > rep) {
            val misses = TrackerStore.misses(ctx, id) + 1
            TrackerStore.setMisses(ctx, misses, id)
            if (misses >= MAX_MISSES) {
                TrackerStore.setAutoPaused(ctx, true, id)
                Alerts.info(ctx, "Seguimiento pausado · ${TrackerStore.name(ctx, id)}",
                    "El rastreador no respondió a las últimas $misses solicitudes. " +
                        "Para no gastar saldo, el seguimiento se reanudará solo cuando vuelva a responder.",
                    Alerts.ID_AUTO, id)
                return Result.success()
            }
        } else {
            TrackerStore.setMisses(ctx, 0, id)
        }

        SmsSender.send(ctx, Commands.text(ctx, "locate", id), id)
        return Result.success()
    }

    companion object {
        private const val MAX_MISSES = 3
        private const val LEGACY_NAME = "seguimiento_automatico"

        private fun name(id: String) = "auto_$id"

        fun inWindow(ctx: Context, id: String): Boolean {
            val s = TrackerStore.windowStart(ctx, id)
            val e = TrackerStore.windowEnd(ctx, id)
            if (s < 0 || e < 0 || s == e) return true
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return if (s < e) h in s until e else (h >= s || h < e)
        }

        /** Programa (o cancela) el seguimiento de un rastreador según su configuración. */
        fun schedule(ctx: Context, id: String) {
            val minutes = TrackerStore.autoMinutes(ctx, id)
            val wm = WorkManager.getInstance(ctx)
            if (minutes <= 0) {
                wm.cancelUniqueWork(name(id))
                return
            }
            val req = PeriodicWorkRequestBuilder<AutoTrackWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("id" to id))
                .build()
            wm.enqueueUniquePeriodicWork(name(id), ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context, id: String) = WorkManager.getInstance(ctx).cancelUniqueWork(name(id))

        fun scheduleAll(ctx: Context) = TrackerStore.ids(ctx).forEach { schedule(ctx, it) }

        /** Las versiones 2.x usaban una sola tarea sin id: se sustituye una vez. */
        fun migrateLegacy(ctx: Context) {
            if (TrackerStore.flag(ctx, "workMigrated3")) return
            WorkManager.getInstance(ctx).cancelUniqueWork(LEGACY_NAME)
            scheduleAll(ctx)
            TrackerStore.setFlag(ctx, "workMigrated3")
        }
    }
}
