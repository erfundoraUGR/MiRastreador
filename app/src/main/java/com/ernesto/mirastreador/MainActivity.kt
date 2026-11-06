package com.ernesto.mirastreador

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private var marker: Marker? = null
    private var route: Polyline? = null

    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es"))

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh(center = true)
    }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.RECEIVE_SMS] == false || res[Manifest.permission.SEND_SMS] == false) {
                Toast.makeText(this, "Sin permiso de SMS la app no puede funcionar", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        tvStatus = findViewById(R.id.tvStatus)
        tvDetail = findViewById(R.id.tvDetail)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(22.4067, -79.9531)) // Santa Clara, si aún no hay datos

        findViewById<MaterialButton>(R.id.btnLocate).setOnClickListener {
            sendCommand(TrackerStore.fillCommand(this, "smslink{p}"), confirm = false)
        }
        findViewById<MaterialButton>(R.id.btnCall).setOnClickListener {
            val phone = TrackerStore.phone(this)
            if (phone.isBlank()) { showSettings(); return@setOnClickListener }
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        }
        findViewById<MaterialButton>(R.id.btnMaps).setOnClickListener { openExternalMap() }
        findViewById<MaterialButton>(R.id.btnCommand).setOnClickListener { showCommandDialog() }

        requestNeededPermissions()
        if (TrackerStore.phone(this).isBlank()) showSettings()
        refresh(center = true)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        ContextCompat.registerReceiver(
            this, updateReceiver, IntentFilter(TrackerStore.ACTION_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh(center = false)
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
    }

    // ---------- Menú ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_settings -> showSettings()
            R.id.menu_history -> showHistory()
            R.id.menu_last_reply -> showLastReply()
            R.id.menu_clear -> AlertDialog.Builder(this)
                .setTitle("Borrar historial")
                .setMessage("Se borrarán todas las posiciones guardadas en este teléfono.")
                .setPositiveButton("Borrar") { _, _ -> TrackerStore.clearHistory(this); refresh(true) }
                .setNegativeButton("Cancelar", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Pantalla ----------

    private fun refresh(center: Boolean) {
        val history = TrackerStore.history(this)
        title = TrackerStore.name(this)

        route?.let { map.overlays.remove(it) }
        marker?.let { map.overlays.remove(it) }
        route = null; marker = null

        val last = history.lastOrNull()
        if (last == null) {
            tvStatus.text = "Sin posiciones todavía"
            tvDetail.text = "Pulsa «Pedir ubicación». La respuesta del rastreador aparecerá aquí sola."
            map.invalidate()
            return
        }

        if (history.size > 1) {
            val line = Polyline()
            line.setPoints(history.takeLast(50).map { GeoPoint(it.lat, it.lon) })
            line.outlinePaint.color = Color.parseColor("#2E6BE6")
            line.outlinePaint.strokeWidth = 6f
            map.overlays.add(line)
            route = line
        }

        val point = GeoPoint(last.lat, last.lon)
        val m = Marker(map)
        m.position = point
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.title = TrackerStore.name(this)
        m.snippet = fmt.format(Date(last.deviceTime))
        map.overlays.add(m)
        marker = m
        if (center) map.controller.animateTo(point)
        map.invalidate()

        tvStatus.text = "Última posición: ${fmt.format(Date(last.deviceTime))} (${ago(last.deviceTime)})"
        tvDetail.text = String.format(
            Locale.US, "%.5f, %.5f  ·  %.1f km/h  ·  %d posiciones guardadas",
            last.lat, last.lon, last.speedKmh, history.size
        )
    }

    private fun ago(t: Long): String {
        val min = (System.currentTimeMillis() - t) / 60000
        return when {
            min < 1 -> "ahora"
            min < 60 -> "hace $min min"
            min < 1440 -> "hace ${min / 60} h"
            else -> "hace ${min / 1440} días"
        }
    }

    private fun openExternalMap() {
        val last = TrackerStore.history(this).lastOrNull()
        if (last == null) {
            Toast.makeText(this, "Aún no hay ninguna posición", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", last.lat, last.lon, last.lat, last.lon))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "No hay una app de mapas instalada", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- SMS ----------

    private fun sendCommand(text: String, confirm: Boolean) {
        val phone = TrackerStore.phone(this)
        if (phone.isBlank()) { showSettings(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions(); return
        }
        val doSend = {
            try {
                val sms = smsManager()
                sms.sendTextMessage(phone, null, text, null, null)
                Toast.makeText(this, "Enviado: $text\nEsperando respuesta…", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo enviar el SMS: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        if (!confirm) { doSend(); return }
        AlertDialog.Builder(this)
            .setTitle("Enviar comando")
            .setMessage("Se enviará este SMS al rastreador ($phone):\n\n$text")
            .setPositiveButton("Enviar") { _, _ -> doSend() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()

    private fun showCommandDialog() {
        val input = EditText(this).apply {
            hint = "p. ej. smslink{p}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("Comando personalizado")
            .setMessage("Escribe el comando. {p} se sustituye por tu contraseña del rastreador.")
            .setView(padded(input))
            .setPositiveButton("Continuar") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) sendCommand(TrackerStore.fillCommand(this, t), confirm = true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Diálogos ----------

    private fun showSettings() {
        val name = EditText(this).apply { hint = "Nombre (p. ej. Moto)"; setText(TrackerStore.name(this@MainActivity)) }
        val phone = EditText(this).apply {
            hint = "Número de la SIM del rastreador"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(TrackerStore.phone(this@MainActivity))
        }
        val pass = EditText(this).apply {
            hint = "Contraseña del rastreador"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(TrackerStore.password(this@MainActivity))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name); addView(phone); addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle("Configuración")
            .setView(padded(box))
            .setCancelable(TrackerStore.phone(this).isNotBlank())
            .setPositiveButton("Guardar") { _, _ ->
                TrackerStore.saveConfig(this, name.text.toString(), phone.text.toString(), pass.text.toString())
                refresh(center = false)
            }
            .show()
    }

    private fun showHistory() {
        val list = TrackerStore.history(this).reversed()
        if (list.isEmpty()) {
            Toast.makeText(this, "El historial está vacío", Toast.LENGTH_SHORT).show(); return
        }
        val items = list.map {
            String.format(Locale.US, "%s  ·  %.1f km/h", fmt.format(Date(it.deviceTime)), it.speedKmh)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Historial (${list.size})")
            .setItems(items) { _, i ->
                val p = list[i]
                map.controller.animateTo(GeoPoint(p.lat, p.lon))
                map.controller.setZoom(17.0)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showLastReply() {
        val r = TrackerStore.lastReply(this)
        AlertDialog.Builder(this)
            .setTitle("Último SMS del rastreador")
            .setMessage(if (r == null) "Todavía no ha llegado ninguno." else "${fmt.format(Date(r.second))}\n\n${r.first}")
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun padded(v: android.view.View): LinearLayout {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(v)
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }
}
