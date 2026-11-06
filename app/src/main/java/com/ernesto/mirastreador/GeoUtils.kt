package com.ernesto.mirastreador

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GeoUtils {

    /** Distancia en metros entre dos puntos. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0]
    }

    fun fmtDist(m: Float): String =
        if (m < 1000) String.format(Locale.US, "%.0f m", m)
        else String.format(Locale.US, "%.1f km", m / 1000f)

    /** Distancia total recorrida (en línea recta entre posiciones consecutivas). */
    fun pathLength(list: List<Position>): Float {
        var total = 0f
        for (i in 1 until list.size) {
            total += distance(list[i - 1].lat, list[i - 1].lon, list[i].lat, list[i].lon)
        }
        return total
    }

    // ---------- Exportación ----------

    private fun gpx(list: List<Position>, name: String): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        iso.timeZone = TimeZone.getTimeZone("UTC")
        val safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Mi Rastreador\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>").append(safe).append("</name><trkseg>\n")
        list.forEach {
            sb.append(String.format(Locale.US, "    <trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time></trkpt>\n",
                it.lat, it.lon, iso.format(Date(it.deviceTime))))
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun csv(list: List<Position>): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("fecha_hora_local,latitud,longitud,velocidad_kmh\n")
        list.forEach {
            sb.append(String.format(Locale.US, "%s,%.6f,%.6f,%.1f\n", f.format(Date(it.deviceTime)), it.lat, it.lon, it.speedKmh))
        }
        return sb.toString()
    }

    /** Guarda las posiciones como GPX o CSV y abre el menú de compartir. */
    fun share(ctx: Context, list: List<Position>, baseName: String, asGpx: Boolean) {
        val dir = File(ctx.cacheDir, "exports")
        dir.mkdirs()
        val file = File(dir, baseName + if (asGpx) ".gpx" else ".csv")
        file.writeText(if (asGpx) gpx(list, baseName) else csv(list))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
        send.type = if (asGpx) "application/gpx+xml" else "text/csv"
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Compartir recorrido"))
    }
}
