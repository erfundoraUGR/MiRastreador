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
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), MapEventsReceiver {

    private lateinit var map: MapView
    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvExtra: TextView
    private var meOverlay: MyLocationNewOverlay? = null
    private val dynamic = mutableListOf<Overlay>()

    private val es = Locale("es")
    private val fmtFull = SimpleDateFormat("dd/MM/yyyy HH:mm", es)
    private val fmtShort = SimpleDateFormat("dd/MM HH:mm", es)
    private val fmtHour = SimpleDateFormat("HH:mm", es)

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshText()
            handler.postDelayed(this, 10_000)
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshMap(center = true)
            refreshText()
        }
    }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.RECEIVE_SMS] == false || res[Manifest.permission.SEND_SMS] == false) {
                toast("Sin permiso de SMS la app no puede funcionar")
            }
            if (hasLocation()) setupMyLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        tvStatus = findViewById(R.id.tvStatus)
        tvDetail = findViewById(R.id.tvDetail)
        tvExtra = findViewById(R.id.tvExtra)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(22.4067, -79.9531)) // Santa Clara, si aún no hay datos
        map.overlays.add(MapEventsOverlay(this))

        findViewById<MaterialButton>(R.id.btnLocate).setOnClickListener { requestLocation() }
        findViewById<MaterialButton>(R.id.btnRoute).setOnClickListener { openDirections() }
        findViewById<MaterialButton>(R.id.btnCommands).setOnClickListener {
            startActivity(Intent(this, CommandsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<FloatingActionButton>(R.id.fabVehicle).setOnClickListener { centerVehicle() }
        findViewById<FloatingActionButton>(R.id.fabMe).setOnClickListener { centerMe() }

        requestNeededPermissions()
        if (hasLocation()) setupMyLocation()
        if (TrackerStore.phone(this).isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        refreshMap(center = true)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        meOverlay?.enableMyLocation()
        ContextCompat.registerReceiver(
            this, updateReceiver, IntentFilter(TrackerStore.ACTION_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshMap(center = false)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        meOverlay?.disableMyLocation()
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
    }

    // ---------- Menú ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fence -> showFenceMenu()
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_last_reply -> showLastReply()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Mapa ----------

    private fun refreshMap(center: Boolean) {
        map.overlays.removeAll(dynamic)
        dynamic.clear()

        TrackerStore.fence(this)?.let { dynamic.add(fenceOverlay(it)) }

        val history = TrackerStore.history(this)
        val last = history.lastOrNull()
        if (last != null) {
            // Recorrido de las últimas 24 horas
            val recent = history.filter { it.deviceTime >= last.deviceTime - DAY_MS }.takeLast(100)
            if (recent.size > 1) {
                val line = Polyline()
                line.setPoints(recent.map { GeoPoint(it.lat, it.lon) })
                line.outlinePaint.color = Color.parseColor("#2E6BE6")
                line.outlinePaint.strokeWidth = 6f
                dynamic.add(line)
                recent.dropLast(1).forEach { dynamic.add(dotMarker(it)) }
            }
            val m = Marker(map)
            m.position = GeoPoint(last.lat, last.lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            m.title = TrackerStore.name(this)
            m.snippet = fmtFull.format(Date(last.deviceTime)) + " · " + speedText(last)
            dynamic.add(m)
            if (center) map.controller.animateTo(m.position)
        }

        // Debajo de la capa de eventos y de "mi ubicación"
        map.overlays.addAll(0, dynamic)
        map.invalidate()
    }

    private fun dotMarker(p: Position): Marker {
        val m = Marker(map)
        m.position = GeoPoint(p.lat, p.lon)
        m.setIcon(ContextCompat.getDrawable(this, R.drawable.dot_blue))
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.title = fmtShort.format(Date(p.deviceTime))
        m.snippet = speedText(p)
        return m
    }

    private fun fenceOverlay(f: Fence): Polygon {
        val poly = Polygon()
        poly.setPoints(Polygon.pointsAsCircle(GeoPoint(f.lat, f.lon), f.radius.toDouble()))
        poly.fillPaint.color = Color.argb(45, 46, 160, 67)
        poly.outlinePaint.color = Color.rgb(46, 160, 67)
        poly.outlinePaint.strokeWidth = 4f
        return poly
    }

    private fun setupMyLocation() {
        if (meOverlay != null) return
        val o = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        o.enableMyLocation()
        o.runOnFirstFix { runOnUiThread { refreshText() } }
        map.overlays.add(o)
        meOverlay = o
    }

    // Mantener pulsado sobre el mapa → fijar zona segura
    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

    override fun longPressHelper(p: GeoPoint?): Boolean {
        if (p == null) return false
        askFenceRadius(p.latitude, p.longitude)
        return true
    }

    // ---------- Textos ----------

    private fun refreshText() {
        supportActionBar?.title = TrackerStore.name(this)
        val last = TrackerStore.history(this).lastOrNull()

        if (last == null) {
            tvStatus.text = "Sin posiciones todavía"
            tvDetail.text = "Pulsa «Pedir ubicación». La respuesta del rastreador aparecerá aquí sola."
        } else {
            tvStatus.text = movement(last) + " · " + ago(last.deviceTime)
            val parts = mutableListOf(fmtFull.format(Date(last.deviceTime)))
            parts.add(String.format(Locale.US, "%.5f, %.5f", last.lat, last.lon))
            meOverlay?.myLocation?.let { me ->
                val d = GeoUtils.distance(me.latitude, me.longitude, last.lat, last.lon)
                parts.add("a ${GeoUtils.fmtDist(d)} de ti")
            }
            TrackerStore.fence(this)?.let { f ->
                val d = GeoUtils.distance(last.lat, last.lon, f.lat, f.lon)
                parts.add(if (d > f.radius) "FUERA de la zona segura" else "dentro de la zona segura")
            }
            tvDetail.text = parts.joinToString("  ·  ")
        }

        val extra = extraText()
        tvExtra.text = extra
        tvExtra.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun extraText(): String {
        val lines = mutableListOf<String>()
        val req = TrackerStore.lastRequestAt(this)
        val rep = TrackerStore.lastReply(this)?.second ?: 0L
        if (req > rep) {
            val s = (System.currentTimeMillis() - req) / 1000
            lines.add(
                if (s < 300) "Esperando respuesta del rastreador… ($s s)"
                else "Sin respuesta desde las ${fmtHour.format(Date(req))}. Revisa el saldo y la cobertura del rastreador."
            )
        }
        val auto = TrackerStore.autoMinutes(this)
        if (auto > 0) lines.add("Seguimiento automático activo: cada ${autoLabel(auto)}")
        return lines.joinToString("\n")
    }

    private fun movement(p: Position): String =
        if (p.speedKmh < 3) "Detenido"
        else String.format(Locale.US, "En movimiento (%.0f km/h)", p.speedKmh)

    private fun speedText(p: Position): String = String.format(Locale.US, "%.1f km/h", p.speedKmh)

    private fun ago(t: Long): String {
        val min = (System.currentTimeMillis() - t) / 60000
        return when {
            min < 1 -> "ahora mismo"
            min < 60 -> "hace $min min"
            min < 1440 -> "hace ${min / 60} h"
            else -> "hace ${min / 1440} días"
        }
    }

    private fun autoLabel(m: Int): String = if (m < 60) "$m min" else if (m == 60) "1 hora" else "${m / 60} horas"

    // ---------- Acciones ----------

    private fun requestLocation() {
        val err = SmsSender.send(this, Commands.text(this, "locate"))
        when {
            err == null -> toast("Solicitud enviada. La posición aparecerá sola al llegar la respuesta.")
            TrackerStore.phone(this).isBlank() -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> { toast(err); requestNeededPermissions() }
        }
        refreshText()
    }

    private fun openDirections() {
        val last = TrackerStore.history(this).lastOrNull() ?: return toast("Aún no hay ninguna posición")
        val label = Uri.encode(TrackerStore.name(this))
        val uri = Uri.parse(String.format(Locale.US, "geo:0,0?q=%.6f,%.6f(%s)", last.lat, last.lon, label))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("No hay una app de mapas instalada")
        }
    }

    private fun centerVehicle() {
        val last = TrackerStore.history(this).lastOrNull() ?: return toast("Aún no hay ninguna posición")
        map.controller.setZoom(17.0)
        map.controller.animateTo(GeoPoint(last.lat, last.lon))
    }

    private fun centerMe() {
        if (!hasLocation()) { requestNeededPermissions(); return }
        setupMyLocation()
        val me = meOverlay?.myLocation ?: return toast("Buscando tu ubicación… activa el GPS del teléfono")
        map.controller.setZoom(17.0)
        map.controller.animateTo(me)
    }

    // ---------- Zona segura ----------

    private fun showFenceMenu() {
        val f = TrackerStore.fence(this)
        val state = if (f != null) "Zona segura activa, radio ${GeoUtils.fmtDist(f.radius.toFloat())}.\n\n"
        else "No hay ninguna zona segura definida.\n\n"
        val b = AlertDialog.Builder(this)
            .setTitle("Zona segura")
            .setMessage(state +
                "Si llega una posición fuera de la zona, sonará una alarma. " +
                "Funciona mejor con el seguimiento automático activado (en Configuración).\n\n" +
                "Para poner el centro en otro sitio, mantén pulsado sobre el mapa.")
            .setPositiveButton("Usar posición del vehículo") { _, _ ->
                val last = TrackerStore.history(this).lastOrNull()
                if (last == null) toast("Aún no hay ninguna posición") else askFenceRadius(last.lat, last.lon)
            }
            .setNegativeButton("Cerrar", null)
        if (f != null) {
            b.setNeutralButton("Desactivar") { _, _ ->
                TrackerStore.setFence(this, null)
                refreshMap(false); refreshText()
                toast("Zona segura desactivada")
            }
        }
        b.show()
    }

    private fun askFenceRadius(lat: Double, lon: Double) {
        val labels = arrayOf("100 m", "250 m", "500 m", "1 km", "2 km", "5 km")
        val values = intArrayOf(100, 250, 500, 1000, 2000, 5000)
        AlertDialog.Builder(this)
            .setTitle("Radio de la zona segura")
            .setItems(labels) { _, i ->
                TrackerStore.setFence(this, Fence(lat, lon, values[i]))
                val last = TrackerStore.history(this).lastOrNull()
                TrackerStore.setFenceOutside(this,
                    last != null && GeoUtils.distance(last.lat, last.lon, lat, lon) > values[i])
                refreshMap(false); refreshText()
                toast("Zona segura activada (${labels[i]})")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLastReply() {
        val r = TrackerStore.lastReply(this)
        AlertDialog.Builder(this)
            .setTitle("Último SMS del rastreador")
            .setMessage(if (r == null) "Todavía no ha llegado ninguno." else "${fmtFull.format(Date(r.second))}\n\n${r.first}")
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // ---------- Permisos ----------

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val needed = mutableListOf(
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
