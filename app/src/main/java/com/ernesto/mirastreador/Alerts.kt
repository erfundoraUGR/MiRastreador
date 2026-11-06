package com.ernesto.mirastreador

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notificaciones: informativas (posición, respuestas) y alarmas con sonido. */
object Alerts {
    private const val CH_INFO = "respuestas"
    private const val CH_ALARM = "alarmas"

    const val ID_POSITION = 1
    const val ID_REPLY = 2
    const val ID_FENCE = 3

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_INFO) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_INFO, "Respuestas del rastreador", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            val ch = NotificationChannel(CH_ALARM, "Alarmas", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Alarmas del rastreador, salida de la zona segura y exceso de velocidad"
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            ch.enableLights(true)
            ch.lightColor = Color.RED
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ch.setSound(sound, attrs)
            nm.createNotificationChannel(ch)
        }
    }

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun info(ctx: Context, title: String, text: String, id: Int) {
        if (!allowed(ctx)) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        show(ctx, id, n)
    }

    /** Devuelve false si no hay permiso para mostrar notificaciones. */
    fun alarm(ctx: Context, title: String, text: String): Boolean {
        if (!allowed(ctx)) return false
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(Color.parseColor("#C62828"))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        show(ctx, (System.currentTimeMillis() % 100000).toInt() + 100, n)
        return true
    }

    private fun show(ctx: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
