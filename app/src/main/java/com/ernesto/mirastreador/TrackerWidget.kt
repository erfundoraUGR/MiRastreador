package com.ernesto.mirastreador

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.RemoteViews
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Widget de la pantalla de inicio: última posición y botón para pedir la ubicación. */
class TrackerWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = views(ctx)
        ids.forEach { mgr.updateAppWidget(it, v) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_LOCATE) {
            val err = SmsSender.send(ctx, Commands.text(ctx, "locate"))
            Toast.makeText(ctx, err ?: "Ubicación solicitada", Toast.LENGTH_SHORT).show()
            updateAll(ctx)
        }
    }

    companion object {
        const val ACTION_LOCATE = "com.ernesto.mirastreador.WIDGET_LOCATE"

        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, TrackerWidget::class.java))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx))
            } catch (_: Exception) {
            }
        }

        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_tracker)
            val es = Locale("es")
            val last = TrackerStore.history(ctx).lastOrNull()
            v.setTextViewText(R.id.wName, TrackerStore.name(ctx))

            if (last == null) {
                v.setTextViewText(R.id.wLine1, "Sin posiciones todavía")
                v.setTextViewText(R.id.wLine2, "Toca el botón para pedir la ubicación")
            } else {
                val t = if (DateUtils.isToday(last.deviceTime)) SimpleDateFormat("HH:mm", es).format(Date(last.deviceTime))
                else SimpleDateFormat("dd/MM HH:mm", es).format(Date(last.deviceTime))
                val state = if (last.speedKmh < 3) "Detenido" else "En movimiento"
                v.setTextViewText(R.id.wLine1, String.format(Locale.US, "%s · %s · %.1f km/h", state, t, last.speedKmh))
                v.setTextViewText(R.id.wLine2, String.format(Locale.US, "%.5f, %.5f", last.lat, last.lon))
            }

            val req = TrackerStore.lastRequestAt(ctx)
            val rep = TrackerStore.lastReply(ctx)?.second ?: 0L
            if (req > rep && System.currentTimeMillis() - req < 5 * 60_000) {
                v.setTextViewText(R.id.wLine2, "Esperando respuesta del rastreador…")
            }

            val open = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.wRoot, open)

            val locate = PendingIntent.getBroadcast(
                ctx, 11, Intent(ctx, TrackerWidget::class.java).setAction(ACTION_LOCATE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.wLocate, locate)
            return v
        }
    }
}
