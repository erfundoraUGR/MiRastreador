package com.ernesto.mirastreador

import java.util.Calendar
import java.util.TimeZone

/** Una posición recibida del rastreador. Las horas van en milisegundos (epoch). */
data class Position(
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val deviceTime: Long,   // hora que informa el rastreador
    val receivedAt: Long    // hora en que llegó el SMS al teléfono
)

/**
 * Interpreta los SMS del rastreador. Formato conocido:
 *
 *   GPS!
 *   lat:22.41140 long:-80.00195 speed:000.6
 *   T:11/05/26 23:35
 *   IMEI:358878731169311
 *   http://maps.google.com/maps?f=q&q=22.41140,-80.00195&z=16
 *
 * La hora "T:" viene en UTC con formato MM/DD/AA HH:MM.
 */
object SmsParser {
    private val latRe = Regex("""\blat[:=]\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val lonRe = Regex("""\blon(?:g)?[:=]\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val speedRe = Regex("""\bspeed[:=]\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("""\bT[:=]\s*(\d{1,2})/(\d{1,2})/(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val linkRe = Regex("""[?&]q=(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)""")

    /**
     * Alarmas que envían estos rastreadores por iniciativa propia.
     * Se exige la palabra "alarm" o un "!" para no confundirlas con las
     * respuestas de estado (p. ej. "Power: 100%" o "SOS1: ...").
     */
    private val alarms = listOf(
        Regex("""help\s*me|\bsos\s*(!|alarm)""", RegexOption.IGNORE_CASE) to "SOS",
        Regex("""(shock|vibra\w*)\s*(alarm|!)""", RegexOption.IGNORE_CASE) to "Vibración detectada",
        Regex("""(move|moving|displacement)\s*(alarm|!)""", RegexOption.IGNORE_CASE) to "Movimiento detectado",
        Regex("""power\s*(alarm|off|cut|down|fail)""", RegexOption.IGNORE_CASE) to "Corte de alimentación",
        Regex("""low\s*bat|lowbat|battery\s*low""", RegexOption.IGNORE_CASE) to "Batería baja",
        Regex("""over\s*speed|\bspeed\s*(alarm|!)""", RegexOption.IGNORE_CASE) to "Exceso de velocidad",
        Regex("""geo-?\s*fence|out\s+of\s+(area|fence)|\bfence\s*(alarm|!)""", RegexOption.IGNORE_CASE) to "Salida de geocerca",
        Regex("""door\s*(alarm|open|!)""", RegexOption.IGNORE_CASE) to "Puerta abierta",
        Regex("""\balarm\w*\s*!""", RegexOption.IGNORE_CASE) to "Alarma"
    )

    fun parse(body: String, receivedAt: Long): Position? {
        var lat = latRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        var lon = lonRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull()

        // Si no vienen como lat:/long:, se intenta con el enlace del mapa
        if (lat == null || lon == null) {
            val m = linkRe.find(body) ?: return null
            lat = m.groupValues[1].toDoubleOrNull()
            lon = m.groupValues[2].toDoubleOrNull()
        }
        if (lat == null || lon == null) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null // sin señal GPS

        val speed = speedRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val deviceTime = parseTime(body) ?: receivedAt
        return Position(lat, lon, speed, deviceTime, receivedAt)
    }

    /** Devuelve el tipo de alarma en español, o null si el SMS no es una alarma. */
    fun alarmKind(body: String): String? =
        alarms.firstOrNull { it.first.containsMatchIn(body) }?.second

    /**
     * Saldo de la SIM en respuestas como "Saldo Principal 25.32 CUP" o "Balance: 12,50".
     * Exige un espacio o ":" antes de la cifra para no confundir "balance123456".
     */
    private val balanceRe = Regex("""(saldo|balance)[^\d\n]{0,25}?[:\s]\s*\$?(\d+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)

    fun balance(body: String): Double? =
        balanceRe.find(body)?.groupValues?.get(2)?.replace(',', '.')?.toDoubleOrNull()

    private fun parseTime(body: String): Long? {
        val m = timeRe.find(body) ?: return null
        var month = m.groupValues[1].toInt()
        var day = m.groupValues[2].toInt()
        var year = m.groupValues[3].toInt()
        if (year < 100) year += 2000
        if (month > 12 && day <= 12) { val t = month; month = day; day = t } // por si viene DD/MM
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val second = m.groupValues[6].toIntOrNull() ?: 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59) return null

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    /** Compara dos números de teléfono por sus últimos 8 dígitos (ignora +53, espacios, etc.). */
    fun sameNumber(a: String?, b: String?): Boolean {
        val da = a?.filter { it.isDigit() } ?: return false
        val db = b?.filter { it.isDigit() } ?: return false
        if (da.isEmpty() || db.isEmpty()) return false
        val n = minOf(8, da.length, db.length)
        return da.takeLast(n) == db.takeLast(n)
    }
}
