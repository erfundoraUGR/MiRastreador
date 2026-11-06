package com.ernesto.mirastreador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Panel de comandos SMS. Tocar = enviar (con confirmación). Mantener pulsado = editar. */
class CommandsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var tvReply: TextView
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale("es"))

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showReply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_commands)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Comandos"

        list = findViewById(R.id.commandList)
        tvReply = findViewById(R.id.tvReply)
        findViewById<MaterialButton>(R.id.btnCustom).setOnClickListener { customDialog() }
        buildButtons()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(TrackerStore.ACTION_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showReply()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildButtons() {
        list.removeAllViews()
        val red = Color.parseColor("#C62828")
        Commands.all.forEach { cmd ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.isAllCaps = false
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val sms = Commands.text(this, cmd.id)
            val s = SpannableString(cmd.label + "\n" + sms)
            s.setSpan(StyleSpan(Typeface.BOLD), 0, cmd.label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(RelativeSizeSpan(0.8f), cmd.label.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.text = s
            if (cmd.danger) {
                b.setTextColor(red)
                b.strokeColor = ColorStateList.valueOf(red)
            }
            b.setOnClickListener { confirmSend(cmd) }
            b.setOnLongClickListener { editTemplate(cmd); true }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(4)
            list.addView(b, lp)
        }
    }

    private fun confirmSend(cmd: TrackerCommand) {
        val text = Commands.text(this, cmd.id)
        val msg = StringBuilder()
        if (cmd.help.isNotBlank()) msg.append(cmd.help).append("\n\n")
        msg.append("Se enviará este SMS al rastreador:\n\n").append(text)
        val d = AlertDialog.Builder(this)
            .setTitle(cmd.label)
            .setMessage(msg.toString())
            .setPositiveButton(if (cmd.danger) "Sí, enviar" else "Enviar") { _, _ -> send(text) }
            .setNegativeButton("Cancelar", null)
        if (cmd.danger) d.setIcon(android.R.drawable.ic_dialog_alert)
        d.show()
    }

    private fun send(text: String) {
        val err = SmsSender.send(this, text)
        Toast.makeText(this, err ?: "Enviado: $text", Toast.LENGTH_LONG).show()
        showReply()
    }

    private fun editTemplate(cmd: TrackerCommand) {
        val input = EditText(this)
        input.setText(Commands.template(this, cmd.id))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        AlertDialog.Builder(this)
            .setTitle("Editar: ${cmd.label}")
            .setMessage("Escribe el comando tal como lo pide el manual de tu rastreador. {p} se sustituye por la contraseña.\n\nPredeterminado: ${cmd.template}")
            .setView(padded(input))
            .setPositiveButton("Guardar") { _, _ ->
                TrackerStore.setCommandTemplate(this, cmd.id, input.text.toString())
                buildButtons()
            }
            .setNeutralButton("Restaurar") { _, _ ->
                TrackerStore.setCommandTemplate(this, cmd.id, null)
                buildButtons()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun customDialog() {
        val input = EditText(this)
        input.hint = "p. ej. smslink{p}"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        AlertDialog.Builder(this)
            .setTitle("Comando personalizado")
            .setMessage("{p} se sustituye por la contraseña del rastreador.")
            .setView(padded(input))
            .setPositiveButton("Enviar") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) send(TrackerStore.fillCommand(this, t))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showReply() {
        val req = TrackerStore.lastRequestAt(this)
        val r = TrackerStore.lastReply(this)
        tvReply.text = when {
            r == null && req == 0L -> "Todavía no ha llegado ninguna respuesta."
            r == null -> "Esperando respuesta del rastreador…"
            req > r.second -> "Esperando respuesta del rastreador…\n\nAnterior (${fmt.format(Date(r.second))}):\n${r.first}"
            else -> "${fmt.format(Date(r.second))}\n\n${r.first}"
        }
    }

    private fun padded(v: android.view.View): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(v)
        return box
    }
}
