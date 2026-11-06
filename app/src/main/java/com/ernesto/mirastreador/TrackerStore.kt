package com.ernesto.mirastreador

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Zona segura: centro y radio en metros. */
data class Fence(val lat: Double, val lon: Double, val radius: Int)

/** Configuración e historial, guardados solo en el teléfono. */
object TrackerStore {
    private const val PREFS = "tracker"
    private const val MAX_HISTORY = 2000
    const val ACTION_UPDATE = "com.ernesto.mirastreador.UPDATE"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Datos del rastreador ----------

    fun phone(ctx: Context): String = prefs(ctx).getString("phone", "") ?: ""
    fun password(ctx: Context): String = prefs(ctx).getString("password", "123456") ?: "123456"
    fun name(ctx: Context): String = prefs(ctx).getString("name", "Mi vehículo") ?: "Mi vehículo"

    fun saveConfig(ctx: Context, name: String, phone: String, password: String) {
        prefs(ctx).edit()
            .putString("name", name.trim().ifBlank { "Mi vehículo" })
            .putString("phone", phone.trim())
            .putString("password", password.trim().ifBlank { "123456" })
            .apply()
    }

    /** Sustituye {p} por la contraseña del rastreador. */
    fun fillCommand(ctx: Context, template: String): String =
        template.replace("{p}", password(ctx))

    // ---------- Comandos editables ----------

    fun commandTemplate(ctx: Context, id: String): String? = prefs(ctx).getString("cmd_$id", null)

    fun setCommandTemplate(ctx: Context, id: String, template: String?) {
        val e = prefs(ctx).edit()
        if (template.isNullOrBlank()) e.remove("cmd_$id") else e.putString("cmd_$id", template.trim())
        e.apply()
    }

    // ---------- Seguimiento automático y alertas ----------

    fun autoMinutes(ctx: Context): Int = prefs(ctx).getInt("autoMinutes", 0)
    fun setAutoMinutes(ctx: Context, m: Int) = prefs(ctx).edit().putInt("autoMinutes", m).apply()

    fun speedLimit(ctx: Context): Int = prefs(ctx).getInt("speedLimit", 0)
    fun setSpeedLimit(ctx: Context, v: Int) = prefs(ctx).edit().putInt("speedLimit", v).apply()

    fun fence(ctx: Context): Fence? {
        val p = prefs(ctx)
        if (!p.getBoolean("fenceOn", false)) return null
        return Fence(
            Double.fromBits(p.getLong("fenceLatBits", 0L)),
            Double.fromBits(p.getLong("fenceLonBits", 0L)),
            p.getInt("fenceRadius", 500)
        )
    }

    fun setFence(ctx: Context, f: Fence?) {
        val e = prefs(ctx).edit()
        if (f == null) {
            e.putBoolean("fenceOn", false).putBoolean("fenceOutside", false)
        } else {
            e.putBoolean("fenceOn", true)
                .putLong("fenceLatBits", f.lat.toRawBits())
                .putLong("fenceLonBits", f.lon.toRawBits())
                .putInt("fenceRadius", f.radius)
        }
        e.apply()
    }

    fun fenceOutside(ctx: Context): Boolean = prefs(ctx).getBoolean("fenceOutside", false)
    fun setFenceOutside(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("fenceOutside", v).apply()

    // ---------- Solicitudes y respuestas ----------

    fun lastRequestAt(ctx: Context): Long = prefs(ctx).getLong("lastRequestAt", 0L)
    fun setLastRequestAt(ctx: Context, t: Long) = prefs(ctx).edit().putLong("lastRequestAt", t).apply()

    fun setLastReply(ctx: Context, body: String, at: Long) {
        prefs(ctx).edit().putString("lastReply", body).putLong("lastReplyAt", at).apply()
    }

    fun lastReply(ctx: Context): Pair<String, Long>? {
        val p = prefs(ctx)
        val body = p.getString("lastReply", null) ?: return null
        return body to p.getLong("lastReplyAt", 0L)
    }

    // ---------- Historial ----------

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
        // El rastreador a veces repite la misma posición: no se duplica
        if (list.any { it.deviceTime == p.deviceTime && it.lat == p.lat && it.lon == p.lon }) return
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
}
