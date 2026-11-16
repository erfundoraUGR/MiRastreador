package com.ernesto.mirastreador

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : SecureActivity() {

    private val autoValues = intArrayOf(0, 15, 30, 60, 120, 240)
    private val autoLabels = arrayOf("Desactivado", "Cada 15 minutos", "Cada 30 minutos", "Cada hora", "Cada 2 horas", "Cada 4 horas")
    private val vigilIntervals = intArrayOf(2, 3, 5, 10)
    private val vigilDurations = intArrayOf(10, 20, 30, 60)
    private val hours = (0..23).map { String.format(Locale.US, "%02d:00", it) }.toTypedArray()

    private lateinit var trackerId: String

    private lateinit var etName: EditText
    private lateinit var etPhone: EditText
    private lateinit var etPass: EditText
    private lateinit var etSpeed: EditText
    private lateinit var etBalanceMin: EditText
    private lateinit var swWeb: SwitchMaterial
    private lateinit var etWebUser: EditText
    private lateinit var etWebPass: EditText
    private lateinit var etWebUserId: EditText
    private lateinit var tvWebResult: TextView
    private lateinit var spAuto: Spinner
    private lateinit var spFrom: Spinner
    private lateinit var spTo: Spinner
    private lateinit var spVigilInterval: Spinner
    private lateinit var spVigilDuration: Spinner
    private lateinit var swWindow: SwitchMaterial
    private lateinit var swVigil: SwitchMaterial
    private lateinit var swLock: SwitchMaterial
    private lateinit var swOffline: SwitchMaterial
    private lateinit var tvAutoInfo: TextView
    private lateinit var tvFence: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvMapFile: TextView
    private lateinit var windowRow: View
    private lateinit var btnFenceOff: MaterialButton

    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMap(uri)
    }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) saveBackup(uri)
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        trackerId = TrackerStore.currentId(this)
        setupBar("Configuración")

        etName = findViewById(R.id.etName)
        etPhone = findViewById(R.id.etPhone)
        etPass = findViewById(R.id.etPass)
        etSpeed = findViewById(R.id.etSpeed)
        etBalanceMin = findViewById(R.id.etBalanceMin)
        swWeb = findViewById(R.id.swWeb)
        etWebUser = findViewById(R.id.etWebUser)
        etWebPass = findViewById(R.id.etWebPass)
        etWebUserId = findViewById(R.id.etWebUserId)
        tvWebResult = findViewById(R.id.tvWebResult)
        spAuto = findViewById(R.id.spAuto)
        spFrom = findViewById(R.id.spFrom)
        spTo = findViewById(R.id.spTo)
        spVigilInterval = findViewById(R.id.spVigilInterval)
        spVigilDuration = findViewById(R.id.spVigilDuration)
        swWindow = findViewById(R.id.swWindow)
        swVigil = findViewById(R.id.swVigil)
        swLock = findViewById(R.id.swLock)
        swOffline = findViewById(R.id.swOffline)
        tvAutoInfo = findViewById(R.id.tvAutoInfo)
        tvFence = findViewById(R.id.tvFence)
        tvBalance = findViewById(R.id.tvBalance)
        tvMapFile = findViewById(R.id.tvMapFile)
        windowRow = findViewById(R.id.windowRow)
        btnFenceOff = findViewById(R.id.btnFenceOff)

        // Rastreador
        etName.setText(TrackerStore.name(this, trackerId))
        etPhone.setText(TrackerStore.phone(this, trackerId))
        etPass.setText(TrackerStore.password(this, trackerId))

        // Seguimiento automático
        spinner(spAuto, autoLabels, maxOf(0, autoValues.indexOf(TrackerStore.autoMinutes(this, trackerId))))
        spAuto.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateAutoInfo(autoValues[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val ws = TrackerStore.windowStart(this, trackerId)
        val we = TrackerStore.windowEnd(this, trackerId)
        swWindow.isChecked = ws >= 0 && we >= 0
        spinner(spFrom, hours, if (ws >= 0) ws else 20)
        spinner(spTo, hours, if (we >= 0) we else 7)
        windowRow.visibility = if (swWindow.isChecked) View.VISIBLE else View.GONE
        swWindow.setOnCheckedChangeListener { _, on -> windowRow.visibility = if (on) View.VISIBLE else View.GONE }

        // Vigilancia
        swVigil.isChecked = TrackerStore.vigilOn(this, trackerId)
        spinner(spVigilInterval, vigilIntervals.map { "$it minutos" }.toTypedArray(),
            maxOf(0, vigilIntervals.indexOf(TrackerStore.vigilInterval(this, trackerId))))
        spinner(spVigilDuration, vigilDurations.map { if (it < 60) "$it minutos" else "1 hora" }.toTypedArray(),
            maxOf(0, vigilDurations.indexOf(TrackerStore.vigilDuration(this, trackerId))))

        // Alertas
        val limit = TrackerStore.speedLimit(this, trackerId)
        etSpeed.setText(if (limit > 0) limit.toString() else "")
        val bmin = TrackerStore.balanceMin(this, trackerId)
        etBalanceMin.setText(if (bmin > 0) String.format(Locale.US, "%.2f", bmin) else "")
        updateBalance()
        updateFence()

        // Seguimiento web
        swWeb.isChecked = TrackerStore.webOn(this, trackerId)
        etWebUser.setText(TrackerStore.webUser(this, trackerId))
        etWebPass.setText(TrackerStore.webPass(this, trackerId))
        etWebUserId.setText(TrackerStore.webUserId(this, trackerId))
        findViewById<MaterialButton>(R.id.btnWebTest).setOnClickListener { testWeb() }
        btnFenceOff.setOnClickListener {
            TrackerStore.setFence(this, null, trackerId)
            updateFence()
        }
        findViewById<MaterialButton>(R.id.btnTestAlarm).setOnClickListener {
            if (!Alerts.alarm(this, "Prueba de alarma", "Así sonará una alarma del rastreador.", trackerId))
                toast("Activa las notificaciones de Mi Rastreador en los ajustes del teléfono")
        }

        // Seguridad (se aplica al momento)
        swLock.isChecked = TrackerStore.lockOn(this)
        swLock.setOnCheckedChangeListener { b, on ->
            if (on && !LockHelper.available(this)) {
                b.isChecked = false
                toast("Primero configura un bloqueo de pantalla (PIN, patrón o huella) en el teléfono")
            } else {
                TrackerStore.setLockOn(this, on)
            }
        }

        // Mapa sin conexión (se aplica al momento)
        updateMapInfo()
        swOffline.isChecked = TrackerStore.offlineOn(this)
        swOffline.setOnCheckedChangeListener { b, on ->
            if (on && MapSetup.offlineFile(this) == null) {
                b.isChecked = false
                toast("Primero carga un archivo de mapa")
            } else {
                TrackerStore.setOfflineOn(this, on)
            }
        }
        findViewById<MaterialButton>(R.id.btnLoadMap).setOnClickListener { pickMap.launch(arrayOf("*/*")) }

        // Copia de seguridad
        findViewById<MaterialButton>(R.id.btnBackup).setOnClickListener {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            createBackup.launch("mirastreador_copia_$day.json")
        }
        findViewById<MaterialButton>(R.id.btnRestore).setOnClickListener { openBackup.launch(arrayOf("*/*")) }

        // Datos
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Borrar historial")
                .setMessage("Se borrarán todas las posiciones guardadas de ${TrackerStore.name(this, trackerId)}.")
                .setPositiveButton("Borrar") { _, _ -> TrackerStore.clearHistory(this, trackerId); toast("Historial borrado") }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        val btnDelete = findViewById<MaterialButton>(R.id.btnDeleteTracker)
        btnDelete.visibility = if (TrackerStore.ids(this).size > 1) View.VISIBLE else View.GONE
        btnDelete.setOnClickListener { confirmDelete() }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun spinner(sp: Spinner, labels: Array<String>, selected: Int) {
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(selected.coerceIn(0, labels.size - 1))
    }

    private fun updateAutoInfo(m: Int) {
        tvAutoInfo.text = if (m == 0) "No se enviarán SMS automáticos."
        else "Como máximo unos ${1440 / m} SMS al día desde tu teléfono, y otros tantos del rastreador. " +
            "Android puede retrasar algunos envíos cuando el teléfono está en reposo."
    }

    private fun updateFence() {
        val f = TrackerStore.fence(this, trackerId)
        tvFence.text = if (f == null) "Desactivada. Para crearla, usa «Zona» en la pantalla principal o mantén pulsado sobre el mapa."
        else "Activa, radio ${GeoUtils.fmtDist(f.radius.toFloat())}."
        btnFenceOff.visibility = if (f == null) View.GONE else View.VISIBLE
    }

    private fun updateBalance() {
        val b = TrackerStore.balance(this, trackerId)
        tvBalance.text = if (b == null) "Saldo aún desconocido. Usa «Consultar saldo de la SIM» en Comandos."
        else String.format(Locale.US, "Último saldo conocido: %.2f (%s)", b,
            SimpleDateFormat("dd/MM HH:mm", Locale("es")).format(Date(TrackerStore.balanceAt(this, trackerId))))
    }

    private fun updateMapInfo() {
        val f = MapSetup.offlineFile(this)
        tvMapFile.text = if (f == null) "Ningún mapa cargado."
        else String.format(Locale.US, "Mapa cargado: %s (%.1f MB)", f.name, f.length() / 1_048_576.0)
    }

    // ---------- Seguimiento web ----------

    private fun testWeb() {
        // Guarda primero lo que haya escrito, para probar exactamente esos valores.
        TrackerStore.setWebConfig(
            this, swWeb.isChecked,
            etWebUser.text.toString(), etWebPass.text.toString(),
            etWebUserId.text.toString(), trackerId
        )
        tvWebResult.text = "Probando…"
        Thread {
            val result = WebTrack.test(this, trackerId)
            runOnUiThread { tvWebResult.text = result }
        }.start()
    }

    // ---------- Mapa sin conexión ----------

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun importMap(uri: Uri) {
        val name = displayName(uri) ?: "mapa.mbtiles"
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext !in listOf("mbtiles", "sqlite", "gemf", "zip")) {
            toast("Formato no admitido. Usa un archivo .mbtiles o .sqlite")
            return
        }
        toast("Copiando el mapa, espera un momento…")
        Thread {
            try {
                val dir = File(filesDir, "maps")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "offline.$ext")
                contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                TrackerStore.setOfflineFile(this, out.absolutePath)
                TrackerStore.setOfflineOn(this, true)
                runOnUiThread {
                    swOffline.isChecked = true
                    updateMapInfo()
                    toast("Mapa cargado")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo cargar el mapa: ${e.message}") }
            }
        }.start()
    }

    // ---------- Copia de seguridad ----------

    private fun saveBackup(uri: Uri) {
        try {
            save(quiet = true)
            contentResolver.openOutputStream(uri)!!.use { it.write(Backup.export(this).toByteArray()) }
            toast("Copia guardada")
        } catch (e: Exception) {
            toast("No se pudo guardar la copia: ${e.message}")
        }
    }

    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Restaurar copia")
            .setMessage("Se sustituirán TODOS los datos actuales (rastreadores, historial y ajustes) por los de la copia.")
            .setPositiveButton("Restaurar") { _, _ ->
                try {
                    val json = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    val n = Backup.restore(this, json)
                    TrackerWidget.updateAll(this)
                    toast("Copia restaurada: $n rastreador(es)")
                    finish()
                } catch (e: Exception) {
                    toast("El archivo no es una copia válida de Mi Rastreador")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Guardar / eliminar ----------

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Eliminar rastreador")
            .setMessage("Se eliminarán ${TrackerStore.name(this, trackerId)}, su historial y su configuración de este teléfono.")
            .setPositiveButton("Eliminar") { _, _ ->
                AutoTrackWorker.cancel(this, trackerId)
                VigilanceWorker.stop(this, trackerId)
                TrackerStore.removeTracker(this, trackerId)
                TrackerWidget.updateAll(this)
                toast("Rastreador eliminado")
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun save(quiet: Boolean = false) {
        val phone = etPhone.text.toString().trim()
        if (!quiet && phone.filter { it.isDigit() }.length < 6) {
            etPhone.error = "Escribe el número de la SIM del rastreador"
            etPhone.requestFocus()
            return
        }
        TrackerStore.saveConfig(this, etName.text.toString(), phone, etPass.text.toString(), trackerId)

        TrackerStore.setAutoMinutes(this, autoValues[spAuto.selectedItemPosition], trackerId)
        if (swWindow.isChecked) TrackerStore.setWindow(this, spFrom.selectedItemPosition, spTo.selectedItemPosition, trackerId)
        else TrackerStore.setWindow(this, -1, -1, trackerId)
        TrackerStore.setAutoPaused(this, false, trackerId)
        TrackerStore.setMisses(this, 0, trackerId)

        TrackerStore.setVigil(this, swVigil.isChecked,
            vigilIntervals[spVigilInterval.selectedItemPosition],
            vigilDurations[spVigilDuration.selectedItemPosition], trackerId)
        if (!swVigil.isChecked) VigilanceWorker.stop(this, trackerId)

        TrackerStore.setSpeedLimit(this, etSpeed.text.toString().trim().toIntOrNull() ?: 0, trackerId)
        TrackerStore.setBalanceMin(this,
            etBalanceMin.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0, trackerId)

        TrackerStore.setWebConfig(
            this, swWeb.isChecked,
            etWebUser.text.toString(), etWebPass.text.toString(),
            etWebUserId.text.toString(), trackerId
        )

        AutoTrackWorker.schedule(this, trackerId)
        TrackerWidget.updateAll(this)
        if (!quiet) {
            toast("Configuración guardada")
            finish()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
