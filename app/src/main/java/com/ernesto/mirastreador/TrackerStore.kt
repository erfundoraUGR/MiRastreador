package com.ernesto.mirastreador

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Configuración e historial, guardados solo en el teléfono. */
object TrackerStore {
    private const val PREFS = "tracker"
    private const val MAX_HISTORY = 1000
    const val ACTION_UPDATE = "com.ernesto.mirastreador.UPDATE"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun phone(ctx: Context): String = prefs(ctx).getString("phone", "") ?: ""
    fun password(ctx: Context): String = prefs(ctx).getString("password", "123456") ?: "123456"
    fun name(ctx: Context): String = prefs(ctx).getString("name", "Mi vehículo") ?: "Mi vehículo"

    fun saveConfig(ctx: Context, name: String, phone: String, password: String) {
        prefs(ctx).edit()
            .putString("name", name.ifBlank { "Mi vehículo" })
            .putString("phone", phone.trim())
            .putString("password", password.trim().ifBlank { "123456" })
            .apply()
    }

    /** Sustituye {p} por la contraseña del rastreador. */
    fun fillCommand(ctx: Context, template: String): String =
        template.replace("{p}", password(ctx))

    fun history(ctx: Context): List<Position> {
        val raw = prefs(ctx).getString("history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Position(
                    o.getDouble("lat"), o.getDouble("lon"), o.optDouble("spd", 0.0),
                    o.getLong("dt"), o.getLong("rx")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, p: Position) {
        val list = history(ctx).toMutableList()
        list.add(p)
        list.sortBy { it.deviceTime }
        while (list.size > MAX_HISTORY) list.removeAt(0)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("lat", it.lat); put("lon", it.lon); put("spd", it.speedKmh)
                put("dt", it.deviceTime); put("rx", it.receivedAt)
            })
        }
        prefs(ctx).edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().remove("history").apply()
    }

    fun setLastReply(ctx: Context, body: String, at: Long) {
        prefs(ctx).edit().putString("lastReply", body).putLong("lastReplyAt", at).apply()
    }

    fun lastReply(ctx: Context): Pair<String, Long>? {
        val p = prefs(ctx)
        val body = p.getString("lastReply", null) ?: return null
        return body to p.getLong("lastReplyAt", 0L)
    }
}
