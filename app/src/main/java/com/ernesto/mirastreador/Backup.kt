package com.ernesto.mirastreador

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Copia de seguridad completa (rastreadores, historial, zonas, comandos y ajustes) en un JSON. */
object Backup {

    private fun dump(sp: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            val e = JSONObject()
            when (v) {
                is String -> { e.put("t", "s"); e.put("v", v) }
                is Int -> { e.put("t", "i"); e.put("v", v) }
                is Long -> { e.put("t", "l"); e.put("v", v) }
                is Boolean -> { e.put("t", "b"); e.put("v", v) }
                is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    private fun load(sp: SharedPreferences, o: JSONObject) {
        val e = sp.edit().clear()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val x = o.getJSONObject(k)
            when (x.getString("t")) {
                "s" -> e.putString(k, x.getString("v"))
                "i" -> e.putInt(k, x.getInt("v"))
                "l" -> e.putLong(k, x.getLong("v"))
                "b" -> e.putBoolean(k, x.getBoolean("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat())
            }
        }
        e.commit()
    }

    fun export(ctx: Context): String {
        val root = JSONObject()
        root.put("app", "MiRastreador")
        root.put("version", 1)
        root.put("created", System.currentTimeMillis())
        root.put("settings", dump(TrackerStore.appPrefs(ctx)))
        val trackers = JSONObject()
        TrackerStore.ids(ctx).forEach { id ->
            trackers.put(id, dump(ctx.getSharedPreferences(TrackerStore.prefsName(id), Context.MODE_PRIVATE)))
        }
        root.put("trackers", trackers)
        return root.toString()
    }

    /** Sustituye todos los datos por los de la copia. Devuelve cuántos rastreadores trae. */
    fun restore(ctx: Context, json: String): Int {
        val root = JSONObject(json)
        require(root.optString("app") == "MiRastreador") { "No es una copia de Mi Rastreador" }
        val settings = root.getJSONObject("settings")
        val trackers = root.getJSONObject("trackers")

        // Detener tareas y borrar los datos actuales
        TrackerStore.ids(ctx).forEach { id ->
            AutoTrackWorker.cancel(ctx, id)
            VigilanceWorker.stop(ctx, id)
            ctx.getSharedPreferences(TrackerStore.prefsName(id), Context.MODE_PRIVATE).edit().clear().commit()
        }

        load(TrackerStore.appPrefs(ctx), settings)
        val keys = trackers.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            load(ctx.getSharedPreferences(TrackerStore.prefsName(id), Context.MODE_PRIVATE), trackers.getJSONObject(id))
        }
        TrackerStore.setFlag(ctx, "workMigrated3")
        TrackerStore.ids(ctx).forEach { TrackerStore.setVigilUntil(ctx, 0L, it) }
        AutoTrackWorker.scheduleAll(ctx)
        return trackers.length()
    }
}
