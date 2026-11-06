package com.ernesto.mirastreador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.Locale

/** Lee los SMS que llegan del número del rastreador, aunque la app esté cerrada. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val from = parts[0].originatingAddress
        if (!SmsParser.sameNumber(from, TrackerStore.phone(ctx))) return

        val body = parts.joinToString("") { it.messageBody ?: "" }
        val now = System.currentTimeMillis()
        val name = TrackerStore.name(ctx)
        TrackerStore.setLastReply(ctx, body, now)

        val pos = SmsParser.parse(body, now)
        if (pos != null) TrackerStore.add(ctx, pos)

        var alarmed = false

        // 1. Alarmas que envía el propio rastreador
        SmsParser.alarmKind(body)?.let { kind ->
            Alerts.alarm(ctx, "¡$kind! · $name", body.take(300))
            alarmed = true
        }

        if (pos != null) {
            // 2. Zona segura (se avisa solo al salir o al volver)
            TrackerStore.fence(ctx)?.let { f ->
                val d = GeoUtils.distance(pos.lat, pos.lon, f.lat, f.lon)
                val outside = d > f.radius
                if (outside != TrackerStore.fenceOutside(ctx)) {
                    TrackerStore.setFenceOutside(ctx, outside)
                    if (outside) {
                        Alerts.alarm(ctx, "$name salió de la zona segura",
                            "Está a ${GeoUtils.fmtDist(d)} del centro de la zona.")
                        alarmed = true
                    } else {
                        Alerts.info(ctx, "$name volvió a la zona segura",
                            "Está a ${GeoUtils.fmtDist(d)} del centro.", Alerts.ID_FENCE)
                    }
                }
            }

            // 3. Límite de velocidad
            val limit = TrackerStore.speedLimit(ctx)
            if (limit > 0 && pos.speedKmh > limit) {
                Alerts.alarm(ctx, "Exceso de velocidad · $name",
                    String.format(Locale.US, "%.0f km/h (límite %d km/h)", pos.speedKmh, limit))
                alarmed = true
            }

            if (!alarmed) {
                Alerts.info(ctx, "Nueva posición de $name",
                    String.format(Locale.US, "%.5f, %.5f · %.1f km/h", pos.lat, pos.lon, pos.speedKmh),
                    Alerts.ID_POSITION)
            }
        } else if (!alarmed) {
            Alerts.info(ctx, "Respuesta del rastreador", body.take(300), Alerts.ID_REPLY)
        }

        ctx.sendBroadcast(Intent(TrackerStore.ACTION_UPDATE).setPackage(ctx.packageName))
    }
}
