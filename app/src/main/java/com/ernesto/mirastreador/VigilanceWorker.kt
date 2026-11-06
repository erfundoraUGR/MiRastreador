package com.ernesto.mirastreador

import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Modo vigilancia: tras una alarma, pide la ubicación cada pocos minutos
 * durante un tiempo, para seguir el recorrido casi en tiempo real.
 */
class VigilanceWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString("id") ?: return Result.success()
        if (id !in TrackerStore.ids(ctx)) return Result.success()

        if (System.currentTimeMillis() >= TrackerStore.vigilUntil(ctx, id)) {
            if (TrackerStore.vigilUntil(ctx, id) > 0) {
                TrackerStore.setVigilUntil(ctx, 0L, id)
                Alerts.info(ctx, "Vigilancia terminada · ${TrackerStore.name(ctx, id)}",
                    "Ya no se piden ubicaciones automáticas por la alarma.", Alerts.ID_VIGIL, id)
                notifyChange(ctx)
            }
            return Result.success()
        }

        SmsSender.send(ctx, Commands.text(ctx, "locate", id), id)
        enqueueNext(ctx, id, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private fun name(id: String) = "vig_$id"

        /** Inicia (o prolonga) la vigilancia de un rastreador. */
        fun start(ctx: Context, id: String, sendNow: Boolean) {
            val wasActive = active(ctx, id)
            val until = System.currentTimeMillis() + TrackerStore.vigilDuration(ctx, id) * 60_000L
            TrackerStore.setVigilUntil(ctx, until, id)
            if (sendNow) SmsSender.send(ctx, Commands.text(ctx, "locate", id), id)
            enqueueNext(ctx, id, ExistingWorkPolicy.REPLACE)
            if (!wasActive) {
                Alerts.info(ctx, "Modo vigilancia activado · ${TrackerStore.name(ctx, id)}",
                    "Se pedirá la ubicación cada ${TrackerStore.vigilInterval(ctx, id)} min durante " +
                        "${TrackerStore.vigilDuration(ctx, id)} min.", Alerts.ID_VIGIL, id)
            }
            notifyChange(ctx)
        }

        fun stop(ctx: Context, id: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork(name(id))
            TrackerStore.setVigilUntil(ctx, 0L, id)
            notifyChange(ctx)
        }

        fun active(ctx: Context, id: String): Boolean =
            TrackerStore.vigilUntil(ctx, id) > System.currentTimeMillis()

        private fun enqueueNext(ctx: Context, id: String, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<VigilanceWorker>()
                .setInitialDelay(TrackerStore.vigilInterval(ctx, id).toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("id" to id))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(name(id), policy, req)
        }

        private fun notifyChange(ctx: Context) {
            ctx.sendBroadcast(Intent(TrackerStore.ACTION_UPDATE).setPackage(ctx.packageName))
        }
    }
}
