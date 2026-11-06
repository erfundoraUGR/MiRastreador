package com.ernesto.mirastreador

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.DashPathEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
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
    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLabel: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var chipFence: TextView
    private lateinit var chipAuto: TextView
    private lateinit var chipDistance: TextView
    private lateinit var chipWait: TextView

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
            handler.postDelayed(this, 5_000)
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
        tvName = findViewById(R.id.tvName)
        tvStatus = findViewById(R.id.tvStatus)
        tvLabel = findViewById(R.id.tvLabel)
        tvCoords = findViewById(R.id.tvCoords)
        tvSpeed = findViewById(R.id.tvSpeed)
        chipFence = findViewById(R.id.chipFence)
        chipAuto = findViewById(R.id.chipAuto)
        chipDistance = findViewById(R.id.chipDistance)
        chipWait = findViewById(R.id.chipWait)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(22.4067, -79.9531)) // Santa Clara, si aún no hay datos
        map.overlays.add(MapEventsOverlay(this))

        findViewById<MaterialButton>(R.id.btnLocate).setOnClickListener { requestLocation() }
        findViewById<View>(R.id.tileCommands).setOnClickListener {
            startActivity(Intent(this, CommandsActivity::class.java))
        }
        findViewById<View>(R.id.tileHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<View>(R.id.tileFence).setOnClickListener { showFenceMenu() }
        findViewById<View>(R.id.tileRoute).setOnClickListener { openDirections() }
        findViewById<ImageButton>(R.id.fabVehicle).setOnClickListener { centerVehicle() }
        findViewById<ImageButton>(R.id.fabMe).setOnClickListener { centerMe() }
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        btnMenu.setOnClickListener { showMenu(btnMenu) }

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

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menuInflater.inflate(R.menu.main, pm.menu)
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_fence -> showFenceMenu()
                R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.menu_last_reply -> showLastReply()
            }
            true
        }
        pm.show()
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
                line.outlinePaint.color = ACCENT
                line.outlinePaint.strokeWidth = 10f
                line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                dynamic.add(line)
                recent.dropLast(1).forEachIndexed { i, p ->
                    dynamic.add(dotMarker(p, if (i == 0) R.drawable.dot_green else R.drawable.dot_blue))
                }
            }
            val m = Marker(map)
            m.position = GeoPoint(last.lat, last.lon)
            m.setIcon(ContextCompat.getDrawable(this, R.drawable.pin_vehicle))
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

    private fun dotMarker(p: Position, icon: Int): Marker {
        val m = Marker(map)
        m.position = GeoPoint(p.lat, p.lon)
        m.setIcon(ContextCompat.getDrawable(this, icon))
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.title = fmtShort.format(Date(p.deviceTime))
        m.snippet = speedText(p)
        return m
    }

    private fun fenceOverlay(f: Fence): Polygon {
        val poly = Polygon()
        poly.setPoints(Polygon.pointsAsCircle(GeoPoint(f.lat, f.lon), f.radius.toDouble()))
        poly.fillPaint.color = Color.argb(26, 46, 160, 67)
        poly.outlinePaint.color = Color.rgb(46, 160, 67)
        poly.outlinePaint.strokeWidth = 4f
        poly.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 18f), 0f)
        return poly
    }

    private fun setupMyLocation() {
        if (meOverlay != null) return
        val o = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        ContextCompat.getDrawable(this, R.drawable.dot_me)?.toBitmap()?.let { bmp ->
            o.setPersonIcon(bmp)
            o.setPersonHotspot(bmp.width / 2f, bmp.height / 2f)
            o.setDirectionArrow(bmp, bmp)
        }
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

    // ---------- Textos y chips ----------

    private fun refreshText() {
        tvName.text = TrackerStore.name(this)
        val last = TrackerStore.history(this).lastOrNull()

        if (last == null) {
            tvStatus.text = "Sin posiciones todavía"
            tvLabel.text = "Sin datos"
            tvCoords.text = "Pide la ubicación"
            tvSpeed.text = "–"
        } else {
            tvStatus.text = (if (last.speedKmh < 3) "Detenido" else "En movimiento") + " · " + ago(last.deviceTime)
            val time = if (DateUtils.isToday(last.deviceTime)) fmtHour.format(Date(last.deviceTime))
            else fmtShort.format(Date(last.deviceTime))
            tvLabel.text = "Última posición · $time"
            tvCoords.text = String.format(Locale.US, "%.5f, %.5f", last.lat, last.lon).replace("-", "−")
            tvSpeed.text = String.format(Locale.US, "%.1f", last.speedKmh)
        }

        // Zona segura
        val f = TrackerStore.fence(this)
        if (f == null || last == null) {
            chipFence.visibility = View.GONE
        } else {
            val outside = GeoUtils.distance(last.lat, last.lon, f.lat, f.lon) > f.radius
            if (outside) chip(chipFence, "Fuera de la zona segura", "#FDECEA", "#B42318", R.drawable.ic_shield)
            else chip(chipFence, "Dentro de la zona segura", "#E6F4EA", "#1B6E30", R.drawable.ic_shield)
        }

        // Seguimiento automático
        val auto = TrackerStore.autoMinutes(this)
        if (auto > 0) chip(chipAuto, "Auto · cada ${autoLabel(auto)}", "#E8EFFC", "#1F4FB0", null)
        else chipAuto.visibility = View.GONE

        // Distancia hasta mí
        val me = meOverlay?.myLocation
        if (me != null && last != null) {
            val d = GeoUtils.distance(me.latitude, me.longitude, last.lat, last.lon)
            chip(chipDistance, "A ${GeoUtils.fmtDist(d)} de ti", "#F1ECFC", "#5B34C4", null)
        } else chipDistance.visibility = View.GONE

        // Esperando respuesta
        val req = TrackerStore.lastRequestAt(this)
        val rep = TrackerStore.lastReply(this)?.second ?: 0L
        if (req > rep) {
            val s = (System.currentTimeMillis() - req) / 1000
            if (s < 300) chip(chipWait, "Esperando respuesta… $s s", "#FFF4D6", "#7A4E00", null)
            else chip(chipWait, "Sin respuesta desde las ${fmtHour.format(Date(req))}", "#FDECEA", "#B42318", null)
        } else chipWait.visibility = View.GONE
    }

    private fun chip(v: TextView, text: String, bg: String, fg: String, icon: Int?) {
        v.text = text
        v.setTextColor(Color.parseColor(fg))
        ViewCompat.setBackgroundTintList(v, ColorStateList.valueOf(Color.parseColor(bg)))
        if (icon != null) {
            val d = ContextCompat.getDrawable(this, icon)
            val size = (14 * resources.displayMetrics.density).toInt()
            d?.setBounds(0, 0, size, size)
            v.setCompoundDrawablesRelative(d, null, null, null)
            TextViewCompat.setCompoundDrawableTintList(v, ColorStateList.valueOf(Color.parseColor(fg)))
        } else {
            v.setCompoundDrawablesRelative(null, null, null, null)
        }
        v.visibility = View.VISIBLE
    }

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
        private val ACCENT = Color.parseColor("#2E6BE6")
    }
}
