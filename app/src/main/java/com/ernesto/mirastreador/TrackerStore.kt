package com.ernesto.mirastreador

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Zona segura: centro y radio en metros. */
data class Fence(val lat: Double, val lon: Double, val radius: Int)

data class TrackerInfo(val id: String, val name: String, val phone: String)

/**
 * Configuración e historial, guardados solo en el teléfono.
 *
 * Cada rastreador tiene su propio archivo de preferencias. El primero ("t1")
 * usa el archivo de las versiones anteriores, así no se pierde nada al actualizar.
 * Casi todas las funciones aceptan el id del rastreador; si se omite, se usa el actual.
 */
object TrackerStore {
    private const val MAX_HISTORY = 2000
    const val ACTION_UPDATE = "com.ernesto.mirastreador.UPDATE"
    const val LEGACY_ID = "t1"

    fun appPrefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun prefsName(id: String): String = if (id == LEGACY_ID) "tracker" else "tracker_$id"
    private fun p(ctx: Context, id: String): SharedPreferences =
        ctx.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE)

    // ---------- Lista de rastreadores ----------

    fun ids(ctx: Context): List<String> {
        val raw = appPrefs(ctx).getString("ids", null)
        if (raw.isNullOrBlank()) {
            appPrefs(ctx).edit().putString("ids", LEGACY_ID).apply()
            return listOf(LEGACY_ID)
        }
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun currentId(ctx: Context): String {
        val ids = ids(ctx)
        val c = appPrefs(ctx).getString("current", null)
        return if (c != null && c in ids) c else ids.first()
    }

    fun setCurrent(ctx: Context, id: String) {
        if (id in ids(ctx)) appPrefs(ctx).edit().putString("current", id).apply()
    }

    fun addTracker(ctx: Context): String {
        val list = ids(ctx)
        val id = "t" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        appPrefs(ctx).edit().putString("ids", (list + id).joinToString(",")).putString("current", id).apply()
        p(ctx, id).edit().putString("name", "Vehículo ${list.size + 1}").apply()
        return id
    }

    fun removeTracker(ctx: Context, id: String) {
        val list = ids(ctx)
        if (list.size <= 1 || id !in list) return
        p(ctx, id).edit().clear().apply()
        val rest = list - id
        appPrefs(ctx).edit().putString("ids", rest.joinToString(",")).putString("current", rest.first()).apply()
    }

    fun all(ctx: Context): List<TrackerInfo> = ids(ctx).map { TrackerInfo(it, name(ctx, it), phone(ctx, it)) }

    fun findByPhone(ctx: Context, number: String?): String? =
        ids(ctx).firstOrNull { SmsParser.sameNumber(number, phone(ctx, it)) }

    // ---------- Datos del rastreador ----------

    fun phone(ctx: Context, id: String = currentId(ctx)): String = p(ctx, id).getString("phone", "") ?: ""
    fun password(ctx: Context, id: String = currentId(ctx)): String = p(ctx, id).getString("password", "123456") ?: "123456"
    fun name(ctx: Context, id: String = currentId(ctx)): String = p(ctx, id).getString("name", "Mi vehículo") ?: "Mi vehículo"

    fun saveConfig(ctx: Context, name: String, phone: String, password: String, id: String = currentId(ctx)) {
        p(ctx, id).edit()
            .putString("name", name.trim().ifBlank { "Mi vehículo" })
            .putString("phone", phone.trim())
            .putString("password", password.trim().ifBlank { "123456" })
            .apply()
    }

    /** Sustituye {p} por la contraseña del rastreador. */
    fun fillCommand(ctx: Context, template: String, id: String = currentId(ctx)): String =
        template.replace("{p}", password(ctx, id))

    // ---------- Comandos editables (comunes a todos los rastreadores) ----------

    fun commandTemplate(ctx: Context, cmd: String): String? =
        appPrefs(ctx).getString("cmd_$cmd", null) ?: p(ctx, LEGACY_ID).getString("cmd_$cmd", null)

    fun setCommandTemplate(ctx: Context, cmd: String, template: String?) {
        val e = appPrefs(ctx).edit()
        if (template.isNullOrBlank()) e.remove("cmd_$cmd") else e.putString("cmd_$cmd", template.trim())
        e.apply()
        p(ctx, LEGACY_ID).edit().remove("cmd_$cmd").apply()
    }

    // ---------- Ajustes generales ----------

    fun lockOn(ctx: Context): Boolean = appPrefs(ctx).getBoolean("lockOn", false)
    fun setLockOn(ctx: Context, v: Boolean) = appPrefs(ctx).edit().putBoolean("lockOn", v).apply()

    fun offlineOn(ctx: Context): Boolean = appPrefs(ctx).getBoolean("offlineOn", false)
    fun setOfflineOn(ctx: Context, v: Boolean) = appPrefs(ctx).edit().putBoolean("offlineOn", v).apply()
    fun offlineFile(ctx: Context): String? = appPrefs(ctx).getString("offlineFile", null)
    fun setOfflineFile(ctx: Context, path: String?) = appPrefs(ctx).edit().putString("offlineFile", path).apply()

    fun flag(ctx: Context, key: String): Boolean = appPrefs(ctx).getBoolean(key, false)
    fun setFlag(ctx: Context, key: String) = appPrefs(ctx).edit().putBoolean(key, true).apply()

    // ---------- Seguimiento automático ----------

    fun autoMinutes(ctx: Context, id: String = currentId(ctx)): Int = p(ctx, id).getInt("autoMinutes", 0)
    fun setAutoMinutes(ctx: Context, m: Int, id: String = currentId(ctx)) = p(ctx, id).edit().putInt("autoMinutes", m).apply()

    /** Horario del seguimiento (horas 0–23). -1 = todo el día. */
    fun windowStart(ctx: Context, id: String = currentId(ctx)): Int = p(ctx, id).getInt("winStart", -1)
    fun windowEnd(ctx: Context, id: String = currentId(ctx)): Int = p(ctx, id).getInt("winEnd", -1)
    fun setWindow(ctx: Context, start: Int, end: Int, id: String = currentId(ctx)) =
        p(ctx, id).edit().putInt("winStart", start).putInt("winEnd", end).apply()

    fun misses(ctx: Context, id: String = currentId(ctx)): Int = p(ctx, id).getInt("misses", 0)
    fun setMisses(ctx: Context, v: Int, id: String = currentId(ctx)) = p(ctx, id).edit().putInt("misses", v).apply()
    fun autoPaused(ctx: Context, id: String = currentId(ctx)): Boolean = p(ctx, id).getBoolean("autoPaused", false)
    fun setAutoPaused(ctx: Context, v: Boolean, id: String = currentId(ctx)) = p(ctx, id).edit().putBoolean("autoPaused", v).apply()

    // ---------- Modo vigilancia ----------

    fun vigilOn(ctx: Context, id: String = currentId(ctx)): Boolean = p(ctx, id).getBoolean("vigilOn", true)
    fun vigilInterval(ctx: Context, id: String = currentId(ctx)): Int = p(ctx, id).getInt("vigilInterval", 2)
    fun vigilDuration(ctx: Context, id: String = currentId(ctx)): Int = p(ctx, id).getInt("vigilDuration", 20)
    fun setVigil(ctx: Context, on: Boolean, interval: Int, duration: Int, id: String = currentId(ctx)) =
        p(ctx, id).edit().putBoolean("vigilOn", on).putInt("vigilInterval", interval).putInt("vigilDuration", duration).apply()
    fun vigilUntil(ctx: Context, id: String = currentId(ctx)): Long = p(ctx, id).getLong("vigilUntil", 0L)
    fun setVigilUntil(ctx: Context, t: Long, id: String = currentId(ctx)) = p(ctx, id).edit().putLong("vigilUntil", t).apply()

    // ---------- Alertas ----------

    fun speedLimit(ctx: Context, id: String = currentId(ctx)): Int = p(ctx, id).getInt("speedLimit", 0)
    fun setSpeedLimit(ctx: Context, v: Int, id: String = currentId(ctx)) = p(ctx, id).edit().putInt("speedLimit", v).apply()

    fun fence(ctx: Context, id: String = currentId(ctx)): Fence? {
        val sp = p(ctx, id)
        if (!sp.getBoolean("fenceOn", false)) return null
        return Fence(
            Double.fromBits(sp.getLong("fenceLatBits", 0L)),
            Double.fromBits(sp.getLong("fenceLonBits", 0L)),
            sp.getInt("fenceRadius", 500)
        )
    }

    fun setFence(ctx: Context, f: Fence?, id: String = currentId(ctx)) {
        val e = p(ctx, id).edit()
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

    fun fenceOutside(ctx: Context, id: String = currentId(ctx)): Boolean = p(ctx, id).getBoolean("fenceOutside", false)
    fun setFenceOutside(ctx: Context, v: Boolean, id: String = currentId(ctx)) = p(ctx, id).edit().putBoolean("fenceOutside", v).apply()

    // ---------- Saldo de la SIM del rastreador ----------

    fun balance(ctx: Context, id: String = currentId(ctx)): Double? =
        p(ctx, id).getString("balance", null)?.toDoubleOrNull()
    fun balanceAt(ctx: Context, id: String = currentId(ctx)): Long = p(ctx, id).getLong("balanceAt", 0L)
    fun setBalance(ctx: Context, v: Double, at: Long, id: String = currentId(ctx)) =
        p(ctx, id).edit().putString("balance", v.toString()).putLong("balanceAt", at).apply()
    fun balanceMin(ctx: Context, id: String = currentId(ctx)): Double =
        p(ctx, id).getString("balanceMin", null)?.toDoubleOrNull() ?: 0.0
    fun setBalanceMin(ctx: Context, v: Double, id: String = currentId(ctx)) =
        p(ctx, id).edit().putString("balanceMin", v.toString()).apply()

    // ---------- Seguimiento web (sin SMS, vía la cuenta del panel del rastreador) ----------

    fun webOn(ctx: Context, id: String = currentId(ctx)): Boolean = p(ctx, id).getBoolean("webOn", false)
    fun webUser(ctx: Context, id: String = currentId(ctx)): String = p(ctx, id).getString("webUser", "") ?: ""
    fun webPass(ctx: Context, id: String = currentId(ctx)): String = p(ctx, id).getString("webPass", "") ?: ""
    fun webUserId(ctx: Context, id: String = currentId(ctx)): String = p(ctx, id).getString("webUserId", "") ?: ""
    fun webMds(ctx: Context, id: String = currentId(ctx)): String = p(ctx, id).getString("webMds", "") ?: ""

    fun setWebConfig(
        ctx: Context, on: Boolean, user: String, pass: String, userId: String, mds: String,
        id: String = currentId(ctx)
    ) {
        p(ctx, id).edit()
            .putBoolean("webOn", on)
            .putString("webUser", user.trim())
            .putString("webPass", pass.trim())
            .putString("webUserId", userId.trim())
            .putString("webMds", mds.trim())
            .apply()
    }

    // ---------- Solicitudes y respuestas ----------

    fun lastRequestAt(ctx: Context, id: String = currentId(ctx)): Long = p(ctx, id).getLong("lastRequestAt", 0L)
    fun setLastRequestAt(ctx: Context, t: Long, id: String = currentId(ctx)) = p(ctx, id).edit().putLong("lastRequestAt", t).apply()

    fun setLastReply(ctx: Context, body: String, at: Long, id: String = currentId(ctx)) {
        p(ctx, id).edit().putString("lastReply", body).putLong("lastReplyAt", at).apply()
    }

    fun lastReply(ctx: Context, id: String = currentId(ctx)): Pair<String, Long>? {
        val sp = p(ctx, id)
        val body = sp.getString("lastReply", null) ?: return null
        return body to sp.getLong("lastReplyAt", 0L)
    }

    // ---------- Historial ----------

    fun history(ctx: Context, id: String = currentId(ctx)): List<Position> {
        val raw = p(ctx, id).getString("history", null) ?: return emptyList()
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

    fun add(ctx: Context, pos: Position, id: String = currentId(ctx)) {
        val list = history(ctx, id).toMutableList()
        // El rastreador a veces repite la misma posición: no se duplica
        if (list.any { it.deviceTime == pos.deviceTime && it.lat == pos.lat && it.lon == pos.lon }) return
        list.add(pos)
        list.sortBy { it.deviceTime }
        while (list.size > MAX_HISTORY) list.removeAt(0)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("lat", it.lat); put("lon", it.lon); put("spd", it.speedKmh)
                put("dt", it.deviceTime); put("rx", it.receivedAt)
            })
        }
        p(ctx, id).edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory(ctx: Context, id: String = currentId(ctx)) {
        p(ctx, id).edit().remove("history").apply()
    }
}
