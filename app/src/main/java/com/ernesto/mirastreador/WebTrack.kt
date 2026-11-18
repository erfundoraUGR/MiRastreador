package com.ernesto.mirastreador

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Seguimiento por datos móviles (sin SMS): consulta la misma API que usa el panel
 * web del rastreador (dagps.net / gps110.org), aprovechando que el TRAKER ya le
 * reporta su posición por GPRS en tiempo real a ese servidor.
 *
 * Requiere, por cada rastreador, el usuario y contraseña de la cuenta del panel web,
 * y el "user_id" que se obtiene una sola vez inspeccionando ese panel (ver
 * Ajustes → Seguimiento web). El panel exige una sesión iniciada además de esos dos
 * valores, así que primero se hace un login (igual que haría el navegador) y se
 * obtiene el "mds" dinámicamente en cada inicio de sesión.
 */
object WebTrack {
    private const val TAG = "WebTrack"
    private const val BASE = "https://www.dagps.net"
    private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 10_000

    // Cookie de sesión y mds guardados por rastreador, para no mezclar cuentas si hay varios.
    private val sessionCookie = mutableMapOf<String, String>()
    private val sessionMds = mutableMapOf<String, String>()

    private data class HttpResponse(val body: String, val cookie: String?, val location: String?, val finalUrl: String)

    private fun request(url: String, cookie: String?, postBody: String? = null): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", UA)
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (postBody != null) {
                val bytes = postBody.toByteArray(Charsets.UTF_8)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val newCookie = conn.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(";") }
            val location = conn.getHeaderField("Location")
            val finalUrl = conn.url.toString()
            return HttpResponse(body, newCookie, location, finalUrl)
        } finally {
            conn.disconnect()
        }
    }

    /** Igual que hace el formulario del panel: pwd = MD5(MD5(contraseña)), nunca en texto plano. */
    private fun md5(s: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun login(id: String, user: String, pass: String): Boolean {
        // 1. Obtener cookie inicial visitando la página de login
        val initRes = request("$BASE/LoginByUser.aspx", null)
        val initialCookie = initRes.cookie

        val body = listOf(
            "userName" to user,
            "pwd_" to "",
            "pwd" to md5(md5(pass)),
            "loginType" to "USER",
            "loginUrl" to "https://www.dagps.net/Skins/DefaultIndex/",
            "timeZone" to "-4",
            "language" to "en",
            "checkPwd" to "1",
            "monitor" to "0",
            "rand" to Math.random().toString()
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        // Añadimos campos dummy para igualar la petición real
        val finalBody = body + "&x=63&y=10"

        // 2. Hacer el POST usando la cookie inicial
        val res = request("$BASE/LoginByUser.aspx?method=loginSystem", initialCookie, finalBody)
        if (res.body.contains("loginError.aspx") || res.cookie == null) {
            Log.w(TAG, "Login fallido para $id. Respuesta: ${res.body}")
            sessionCookie.remove(id)
            sessionMds.remove(id)
            return false
        }

        val mdsRegex = Regex("""mds=([a-zA-Z0-9_-]+)""")
        val mds = (res.location?.let { mdsRegex.find(it)?.groupValues?.get(1) })
            ?: (mdsRegex.find(res.finalUrl)?.groupValues?.get(1))
            ?: (mdsRegex.find(res.body)?.groupValues?.get(1))

        if (mds == null) {
            Log.w(TAG, "No se encontró mds en la respuesta de login para $id")
            sessionCookie.remove(id)
            sessionMds.remove(id)
            return false
        }

        sessionCookie[id] = res.cookie
        sessionMds[id] = mds
        return true
    }

    /** Pide la posición actual al panel web. Null si falla o si falta configurar algo. */
    fun fetch(ctx: Context, id: String): Position? = try {
        fetchInternal(ctx, id)
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo obtener la posición web de $id: ${e.message}")
        null
    }

    private fun fetchInternal(ctx: Context, id: String): Position? {
        val user = TrackerStore.webUser(ctx, id)
        val pass = TrackerStore.webPass(ctx, id)
        val userId = TrackerStore.webUserId(ctx, id)
        if (user.isBlank() || pass.isBlank() || userId.isBlank()) return null
        if (sessionCookie[id] == null && !login(id, user, pass)) return null
        val mds = sessionMds[id] ?: return null

        repeat(2) { attempt ->
            val timestamp = System.currentTimeMillis().toString()
            val url = "$BASE/TrackService.aspx?method=getOnlineGpsInfoByIDUtc" +
                "&callback=TrackOBJ.updateDataCallBack" +
                "&school_id=$userId&custid=$userId&userIDs=$userId" +
                "&mapType=GOOGLE&option=en&t=$timestamp&mds=$mds&timestamp=$timestamp"
            val (body, _) = request(url, sessionCookie[id])

            // El panel contesta con esto cuando la sesión ya no es válida
            if (body.contains("loginouts") || body.contains("logout.aspx")) {
                sessionCookie.remove(id)
                sessionMds.remove(id)
                if (attempt == 0 && login(id, user, pass)) return@repeat
                return null
            }
            return parse(body)
        }
        return null
    }

    /** Respuesta esperada: startTrack({"key": {"weidu":3, "jingdu":2, ...}, "records": [[...]], "groups": []}) */
    private fun parse(body: String): Position? {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end < 0 || end <= start) return null
        val obj = JSONObject(body.substring(start, end + 1))
        val key = obj.optJSONObject("key") ?: return null
        val records = obj.optJSONArray("records") ?: return null
        if (records.length() == 0) return null
        val row = records.getJSONArray(0)

        fun idx(name: String): Int {
            val i = key.optInt(name, -1)
            return if (i in 0 until row.length()) i else -1
        }

        val iLat = idx("weidu")
        val iLon = idx("jingdu")
        if (iLat < 0 || iLon < 0) return null
        val lat = row.optDouble(iLat, 0.0)
        val lon = row.optDouble(iLon, 0.0)
        if (lat == 0.0 && lon == 0.0) return null // sin señal GPS

        val speed = idx("su").let { if (it >= 0) row.optDouble(it, 0.0) else 0.0 }
        val dtIdx = idx("datetime").let { if (it >= 0) it else idx("heart_time") }
        val deviceTime = if (dtIdx >= 0) row.optLong(dtIdx, System.currentTimeMillis()) else System.currentTimeMillis()

        return Position(lat, lon, speed, deviceTime, System.currentTimeMillis())
    }

    /** Para el botón "Probar conexión" de Ajustes: hace un login+consulta real y explica el resultado. */
    fun test(ctx: Context, id: String): String {
        val user = TrackerStore.webUser(ctx, id)
        val pass = TrackerStore.webPass(ctx, id)
        val userId = TrackerStore.webUserId(ctx, id)
        if (user.isBlank() || pass.isBlank() || userId.isBlank())
            return "Faltan datos: completa usuario, contraseña y user_id."
        return try {
            sessionCookie.remove(id) // fuerza un login nuevo para la prueba
            sessionMds.remove(id)
            if (!login(id, user, pass)) return "No se pudo iniciar sesión: revisa el usuario y la contraseña."
            val pos = fetchInternal(ctx, id)
                ?: return "Sesión iniciada, pero no llegó una posición válida: revisa el user_id."
            String.format(Locale.US, "Conexión correcta. Posición: %.5f, %.5f", pos.lat, pos.lon)
        } catch (e: Exception) {
            "Error de red: ${e.message}"
        }
    }
}
