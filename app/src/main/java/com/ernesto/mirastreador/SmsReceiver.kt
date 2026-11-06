package com.ernesto.mirastreador

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
        TrackerStore.setLastReply(ctx, body, now)

        val pos = SmsParser.parse(body, now)
        if (pos != null) TrackerStore.add(ctx, pos)

        notify(ctx, pos, body)
        ctx.sendBroadcast(Intent(TrackerStore.ACTION_UPDATE).setPackage(ctx.packageName))
    }

    private fun notify(ctx: Context, pos: Position?, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Respuestas del rastreador", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (pos != null) "Nueva posición de ${TrackerStore.name(ctx)}" else "Respuesta del rastreador"
        val text = if (pos != null)
            String.format(Locale.US, "%.5f, %.5f · %.1f km/h", pos.lat, pos.lon, pos.speedKmh)
        else body.take(120)

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1, n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val CHANNEL = "respuestas"
    }
}
