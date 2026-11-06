package com.ernesto.mirastreador

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recorridos día a día, con resumen y exportación. */
class HistoryActivity : SecureActivity() {

    private lateinit var map: MapView
    private lateinit var tvDay: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvStatCount: TextView
    private lateinit var tvStatDist: TextView
    private lateinit var tvStatSpeed: TextView
    private lateinit var listView: ListView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

    private var days: List<String> = emptyList()
    private var dayIndex = 0
    private var dayPositions: List<Position> = emptyList()

    private val es = Locale("es")
    private val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayFmt = SimpleDateFormat("EEEE d 'de' MMMM", es)
    private val timeFmt = SimpleDateFormat("HH:mm", es)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_history)
        setupBar("Historial · ${TrackerStore.name(this)}")

        map = findViewById(R.id.map)
        tvDay = findViewById(R.id.tvDay)
        tvRange = findViewById(R.id.tvRange)
        tvStatCount = findViewById(R.id.tvStatCount)
        tvStatDist = findViewById(R.id.tvStatDist)
        tvStatSpeed = findViewById(R.id.tvStatSpeed)
        listView = findViewById(R.id.list)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)

        MapSetup.apply(this, map)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(15.0)

        btnPrev.setOnClickListener { if (dayIndex > 0) { dayIndex--; showDay() } }
        btnNext.setOnClickListener { if (dayIndex < days.size - 1) { dayIndex++; showDay() } }
        listView.setOnItemClickListener { _, _, pos, _ ->
            val p = dayPositions[dayPositions.size - 1 - pos]
            map.controller.setZoom(17.0)
            map.controller.animateTo(GeoPoint(p.lat, p.lon))
        }
        load()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_export_day -> {
                if (dayPositions.isEmpty()) return true
                exportDialog(dayPositions, "recorrido_" + keyFmt.format(Date(dayPositions.first().deviceTime)))
            }
            R.id.menu_export_all -> {
                val all = TrackerStore.history(this)
                if (all.isNotEmpty()) exportDialog(all, "historial_completo")
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun load() {
        val all = TrackerStore.history(this)
        days = all.map { keyFmt.format(Date(it.deviceTime)) }.distinct().sorted()
        dayIndex = days.size - 1
        showDay()
    }

    private fun showDay() {
        if (days.isEmpty()) {
            tvDay.text = "Sin datos"
            tvRange.text = "Aún no hay posiciones guardadas"
            tvStatCount.text = "0"; tvStatDist.text = "–"; tvStatSpeed.text = "–"
            setArrow(btnPrev, false); setArrow(btnNext, false)
            listView.adapter = null
            return
        }
        val key = days[dayIndex]
        dayPositions = TrackerStore.history(this).filter { keyFmt.format(Date(it.deviceTime)) == key }

        tvDay.text = dayFmt.format(Date(dayPositions.first().deviceTime)).replaceFirstChar { it.uppercase() }
        tvRange.text = timeFmt.format(Date(dayPositions.first().deviceTime)) + " – " +
            timeFmt.format(Date(dayPositions.last().deviceTime))
        setArrow(btnPrev, dayIndex > 0)
        setArrow(btnNext, dayIndex < days.size - 1)

        tvStatCount.text = dayPositions.size.toString()
        tvStatDist.text = GeoUtils.fmtDist(GeoUtils.pathLength(dayPositions))
        tvStatSpeed.text = String.format(Locale.US, "%.0f", dayPositions.maxOf { it.speedKmh })

        listView.adapter = PositionAdapter(dayPositions.reversed())
        drawMap()
    }

    private fun setArrow(b: ImageButton, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.3f
    }

    private fun drawMap() {
        map.overlays.clear()
        val points = dayPositions.map { GeoPoint(it.lat, it.lon) }
        if (points.size > 1) {
            val line = Polyline()
            line.setPoints(points)
            line.outlinePaint.color = Color.parseColor("#2E6BE6")
            line.outlinePaint.strokeWidth = 10f
            line.outlinePaint.strokeCap = Paint.Cap.ROUND
            map.overlays.add(line)
        }
        dayPositions.forEachIndexed { i, p ->
            val m = Marker(map)
            m.position = GeoPoint(p.lat, p.lon)
            m.title = timeFmt.format(Date(p.deviceTime))
            m.snippet = String.format(Locale.US, "%.1f km/h", p.speedKmh)
            if (i < dayPositions.size - 1) {
                val icon = if (i == 0) R.drawable.dot_green else R.drawable.dot_blue
                m.setIcon(ContextCompat.getDrawable(this, icon))
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            } else {
                m.setIcon(ContextCompat.getDrawable(this, R.drawable.pin_vehicle))
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(m)
        }
        map.invalidate()

        map.post {
            val small = points.size < 2 || GeoUtils.distance(
                dayPositions.minOf { it.lat }, dayPositions.minOf { it.lon },
                dayPositions.maxOf { it.lat }, dayPositions.maxOf { it.lon }) < 150
            if (small) {
                map.controller.setZoom(17.0)
                map.controller.setCenter(points.last())
            } else {
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 80)
            }
        }
    }

    private fun exportDialog(list: List<Position>, baseName: String) {
        AlertDialog.Builder(this)
            .setTitle("Exportar ${list.size} posiciones")
            .setItems(arrayOf("GPX (OsmAnd, Maps.me, Google Earth…)", "CSV (Excel, hojas de cálculo)")) { _, i ->
                GeoUtils.share(this, list, baseName, asGpx = i == 0)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Lista de posiciones del día, de la más reciente a la más antigua. */
    private inner class PositionAdapter(private val items: List<Position>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(i: Int): Any = items[i]
        override fun getItemId(i: Int): Long = i.toLong()

        override fun getView(i: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_position, parent, false)
            val p = items[i]
            v.findViewById<TextView>(R.id.tvTime).text = timeFmt.format(Date(p.deviceTime))
            v.findViewById<TextView>(R.id.tvCoords).text = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)
            v.findViewById<TextView>(R.id.tvSpeed).text = String.format(Locale.US, "%.1f km/h", p.speedKmh)
            val dot = when (i) {
                0 -> R.drawable.pin_dot_accent
                items.size - 1 -> R.drawable.dot_green
                else -> R.drawable.dot_blue
            }
            v.findViewById<View>(R.id.dot).background = ContextCompat.getDrawable(this@HistoryActivity, dot)
            return v
        }
    }
}
