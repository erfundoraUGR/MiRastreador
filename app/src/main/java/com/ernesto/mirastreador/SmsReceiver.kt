package com.ernesto.mirastreador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.Locale

/** Lee los SMS que llegan de cualquiera de los rastreadores, aunque la app esté cerrada. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val id = TrackerStore.findByPhone(ctx, parts[0].originatingAddress) ?: return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val now = System.currentTimeMillis()
        val name = TrackerStore.name(ctx, id)

        TrackerStore.setLastReply(ctx, body, now, id)
        // El rastreador respondió: se reanuda el seguimiento si estaba pausado
        TrackerStore.setMisses(ctx, 0, id)
        if (TrackerStore.autoPaused(ctx, id)) TrackerStore.setAutoPaused(ctx, false, id)

        val pos = SmsParser.parse(body, now)
        if (pos != null) TrackerStore.add(ctx, pos, id)

        var notified = false
        var startVigil = false

        // 1. Alarmas que envía el propio rastreador
        SmsParser.alarmKind(body)?.let { kind ->
            Alerts.alarm(ctx, "¡$kind! · $name", body.take(300), id)
            notified = true
            if (kind != "Batería baja") startVigil = true
        }

        // 2. Saldo de la SIM
        SmsParser.balance(body)?.let { b ->
            TrackerStore.setBalance(ctx, b, now, id)
            val min = TrackerStore.balanceMin(ctx, id)
            if (min > 0 && b < min) {
                Alerts.alarm(ctx, "Saldo bajo · $name",
                    String.format(Locale.US, "La SIM del rastreador tiene %.2f de saldo (aviso por debajo de %.2f).", b, min), id)
                notified = true
            } else if (!notified && pos == null) {
                Alerts.info(ctx, "Saldo de la SIM · $name",
                    String.format(Locale.US, "Saldo: %.2f", b), Alerts.ID_REPLY, id)
                notified = true
            }
        }

        if (pos != null) {
            // 3. Zona segura (se avisa solo al salir o al volver)
            TrackerStore.fence(ctx, id)?.let { f ->
                val d = GeoUtils.distance(pos.lat, pos.lon, f.lat, f.lon)
                val outside = d > f.radius
                if (outside != TrackerStore.fenceOutside(ctx, id)) {
                    TrackerStore.setFenceOutside(ctx, outside, id)
                    if (outside) {
                        Alerts.alarm(ctx, "$name salió de la zona segura",
                            "Está a ${GeoUtils.fmtDist(d)} del centro de la zona.", id)
                        notified = true
                        startVigil = true
                    } else {
                        Alerts.info(ctx, "$name volvió a la zona segura",
                            "Está a ${GeoUtils.fmtDist(d)} del centro.", Alerts.ID_FENCE, id)
                    }
                }
            }

            // 4. Límite de velocidad
            val limit = TrackerStore.speedLimit(ctx, id)
            if (limit > 0 && pos.speedKmh > limit) {
                Alerts.alarm(ctx, "Exceso de velocidad · $name",
                    String.format(Locale.US, "%.0f km/h (límite %d km/h)", pos.speedKmh, limit), id)
                notified = true
            }

            if (!notified) {
                Alerts.info(ctx, "Nueva posición de $name",
                    String.format(Locale.US, "%.5f, %.5f · %.1f km/h", pos.lat, pos.lon, pos.speedKmh),
                    Alerts.ID_POSITION, id)
            }
        } else if (!notified) {
            Alerts.info(ctx, "Respuesta de $name", body.take(300), Alerts.ID_REPLY, id)
        }

        // 5. Modo vigilancia tras una alarma
        if (startVigil && TrackerStore.vigilOn(ctx, id)) {
            VigilanceWorker.start(ctx, id, sendNow = pos == null)
        }

        ctx.sendBroadcast(Intent(TrackerStore.ACTION_UPDATE).setPackage(ctx.packageName))
        TrackerWidget.updateAll(ctx)
    }
}
