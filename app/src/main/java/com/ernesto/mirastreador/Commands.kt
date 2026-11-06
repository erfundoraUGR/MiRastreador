package com.ernesto.mirastreador

import android.content.Context

/** Un comando SMS. {p} se sustituye por la contraseña del rastreador. */
data class TrackerCommand(
    val id: String,
    val label: String,
    val template: String,
    val help: String,
    val danger: Boolean = false
)

/**
 * Comandos predefinidos. La sintaxis varía según el firmware del rastreador,
 * por eso cada uno se puede editar manteniéndolo pulsado en la pantalla de comandos.
 */
object Commands {
    val all = listOf(
        TrackerCommand("locate", "Pedir ubicación", "smslink{p}",
            "El rastreador responde con sus coordenadas y un enlace de mapa."),
        TrackerCommand("status", "Estado del equipo", "check{p}",
            "Batería, señal GSM y GPS."),
        TrackerCommand("params", "Ver configuración", "param{p}",
            "APN, servidor, intervalos y números configurados."),
        TrackerCommand("balance", "Consultar saldo de la SIM", "balance{p} *222#",
            "Pide al rastreador que consulte el saldo de su SIM (*222# en Cubacel) y te reenvíe la respuesta. No todos los modelos lo admiten."),
        TrackerCommand("arm", "Activar alarma de movimiento", "arm{p}",
            "El rastreador avisa por SMS si el vehículo se mueve o vibra."),
        TrackerCommand("disarm", "Desactivar alarma", "disarm{p}",
            "Deja de enviar avisos de movimiento."),
        TrackerCommand("stop", "Cortar motor", "stop{p}",
            "Requiere el relé instalado. Úsalo SOLO con el vehículo detenido: cortar el motor en marcha es peligroso.",
            danger = true),
        TrackerCommand("resume", "Restablecer motor", "resume{p}",
            "Vuelve a permitir el arranque del motor."),
        TrackerCommand("reset", "Reiniciar rastreador", "reset{p}",
            "Reinicia el equipo. No borra la configuración.")
    )

    fun byId(id: String): TrackerCommand = all.first { it.id == id }

    /** Plantilla actual (la editada por el usuario o la predeterminada). */
    fun template(ctx: Context, cmd: String): String =
        TrackerStore.commandTemplate(ctx, cmd) ?: byId(cmd).template

    /** Texto final listo para enviar al rastreador indicado. */
    fun text(ctx: Context, cmd: String, trackerId: String = TrackerStore.currentId(ctx)): String =
        TrackerStore.fillCommand(ctx, template(ctx, cmd), trackerId)
}
