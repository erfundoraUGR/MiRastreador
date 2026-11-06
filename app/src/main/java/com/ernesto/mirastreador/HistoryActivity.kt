package com.ernesto.mirastreador

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recorridos día a día, con resumen y exportación. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var tvDay: TextView
    private lateinit var tvSummary: TextView
    private lateinit var listView: ListView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

    private var days: List<String> = emptyList()
    private var dayIndex = 0
    private var dayPositions: List<Position> = emptyList()

    private val es = Locale("es")
    private val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayFmt = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", es)
    private val timeFmt = SimpleDateFormat("HH:mm", es)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Historial"

        map = findViewById(R.id.map)
        tvDay = findViewById(R.id.tvDay)
        tvSummary = findViewById(R.id.tvSummary)
        listView = findViewById(R.id.list)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

        btnPrev.setOnClickListener { if (dayIndex > 0) { dayIndex--; showDay() } }
        btnNext.setOnClickListener { if (dayIndex < days.size - 1) { dayIndex++; showDay() } }
        listView.setOnItemClickListener { _, _, pos, _ ->
            val p = dayPositions[pos]
            map.controller.setZoom(17.0)
            map.controller.animateTo(GeoPoint(p.lat, p.lon))
        }
        load()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

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
            tvSummary.text = "Aún no hay posiciones guardadas."
            btnPrev.isEnabled = false
            btnNext.isEnabled = false
            listView.adapter = null
            return
        }
        val key = days[dayIndex]
        dayPositions = TrackerStore.history(this).filter { keyFmt.format(Date(it.deviceTime)) == key }

        tvDay.text = dayFmt.format(Date(dayPositions.first().deviceTime)).replaceFirstChar { it.uppercase() }
        btnPrev.isEnabled = dayIndex > 0
        btnNext.isEnabled = dayIndex < days.size - 1
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else 0.3f
        btnNext.alpha = if (btnNext.isEnabled) 1f else 0.3f

        val n = dayPositions.size
        val dist = GeoUtils.pathLength(dayPositions)
        val maxSpeed = dayPositions.maxOf { it.speedKmh }
        val first = timeFmt.format(Date(dayPositions.first().deviceTime))
        val last = timeFmt.format(Date(dayPositions.last().deviceTime))
        tvSummary.text = String.format(Locale.US,
            "%d posiciones (%s – %s)  ·  %s recorridos aprox.  ·  vel. máx. %.0f km/h",
            n, first, last, GeoUtils.fmtDist(dist), maxSpeed)

        val items = dayPositions.map {
            String.format(Locale.US, "%s   %.1f km/h   %.5f, %.5f",
                timeFmt.format(Date(it.deviceTime)), it.speedKmh, it.lat, it.lon)
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        drawMap()
    }

    private fun drawMap() {
        map.overlays.clear()
        val points = dayPositions.map { GeoPoint(it.lat, it.lon) }
        if (points.size > 1) {
            val line = Polyline()
            line.setPoints(points)
            line.outlinePaint.color = Color.parseColor("#2E6BE6")
            line.outlinePaint.strokeWidth = 6f
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
            .setItems(arrayOf("GPX (Google Earth, OsmAnd, Maps.me…)", "CSV (Excel, hojas de cálculo)")) { _, i ->
                GeoUtils.share(this, list, baseName, asGpx = i == 0)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
