package com.ernesto.mirastreador

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private val autoValues = intArrayOf(0, 15, 30, 60, 120, 240)
    private val autoLabels = arrayOf("Desactivado", "Cada 15 minutos", "Cada 30 minutos", "Cada hora", "Cada 2 horas", "Cada 4 horas")

    private lateinit var etName: EditText
    private lateinit var etPhone: EditText
    private lateinit var etPass: EditText
    private lateinit var etSpeed: EditText
    private lateinit var spAuto: Spinner
    private lateinit var tvAutoInfo: TextView
    private lateinit var tvFence: TextView
    private lateinit var btnFenceOff: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Configuración"

        etName = findViewById(R.id.etName)
        etPhone = findViewById(R.id.etPhone)
        etPass = findViewById(R.id.etPass)
        etSpeed = findViewById(R.id.etSpeed)
        spAuto = findViewById(R.id.spAuto)
        tvAutoInfo = findViewById(R.id.tvAutoInfo)
        tvFence = findViewById(R.id.tvFence)
        btnFenceOff = findViewById(R.id.btnFenceOff)

        etName.setText(TrackerStore.name(this))
        etPhone.setText(TrackerStore.phone(this))
        etPass.setText(TrackerStore.password(this))
        val limit = TrackerStore.speedLimit(this)
        etSpeed.setText(if (limit > 0) limit.toString() else "")

        spAuto.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, autoLabels)
        spAuto.setSelection(maxOf(0, autoValues.indexOf(TrackerStore.autoMinutes(this))))
        spAuto.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateAutoInfo(autoValues[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnFenceOff.setOnClickListener {
            TrackerStore.setFence(this, null)
            updateFence()
        }
        findViewById<MaterialButton>(R.id.btnTestAlarm).setOnClickListener {
            if (!Alerts.alarm(this, "Prueba de alarma", "Así sonará una alarma del rastreador."))
                toast("Activa las notificaciones de Mi Rastreador en los ajustes del teléfono")
        }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Borrar historial")
                .setMessage("Se borrarán todas las posiciones guardadas en este teléfono.")
                .setPositiveButton("Borrar") { _, _ -> TrackerStore.clearHistory(this); toast("Historial borrado") }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        updateFence()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun updateAutoInfo(m: Int) {
        tvAutoInfo.text = if (m == 0) "No se enviarán SMS automáticos."
        else "Tu teléfono enviará unos ${1440 / m} SMS al día y el rastreador responderá otros tantos (revisa el saldo de ambas SIM). " +
            "Android puede retrasar algunos envíos cuando el teléfono está en reposo."
    }

    private fun updateFence() {
        val f = TrackerStore.fence(this)
        tvFence.text = if (f == null) "Desactivada. Para crearla, usa el menú «Zona segura» o mantén pulsado sobre el mapa."
        else "Activa, radio ${GeoUtils.fmtDist(f.radius.toFloat())}."
        btnFenceOff.visibility = if (f == null) View.GONE else View.VISIBLE
    }

    private fun save() {
        val phone = etPhone.text.toString().trim()
        if (phone.filter { it.isDigit() }.length < 6) {
            etPhone.error = "Escribe el número de la SIM del rastreador"
            return
        }
        TrackerStore.saveConfig(this, etName.text.toString(), phone, etPass.text.toString())
        TrackerStore.setAutoMinutes(this, autoValues[spAuto.selectedItemPosition])
        TrackerStore.setSpeedLimit(this, etSpeed.text.toString().trim().toIntOrNull() ?: 0)
        AutoTrackWorker.schedule(this)
        toast("Configuración guardada")
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
