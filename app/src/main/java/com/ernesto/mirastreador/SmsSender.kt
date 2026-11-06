package com.ernesto.mirastreador

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

object SmsSender {

    /** Envía un SMS al rastreador. Devuelve null si todo fue bien, o el motivo del fallo. */
    fun send(ctx: Context, text: String): String? {
        val phone = TrackerStore.phone(ctx)
        if (phone.isBlank()) return "Configura primero el número del rastreador"
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "Falta el permiso para enviar SMS"
        return try {
            manager(ctx).sendTextMessage(phone, null, text, null, null)
            TrackerStore.setLastRequestAt(ctx, System.currentTimeMillis())
            null
        } catch (e: Exception) {
            "No se pudo enviar el SMS: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
}
