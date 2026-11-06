package com.ernesto.mirastreador

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/** Bloqueo con huella, rostro o el PIN/patrón del teléfono. */
object LockHelper {
    private val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun info(): BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Mi Rastreador")
        .setSubtitle("Desbloquea para continuar")
        .setAllowedAuthenticators(AUTH)
        .build()
}

/**
 * Base de todas las pantallas: pide desbloqueo al abrir la app o al volver
 * tras más de un minuto fuera, si el bloqueo está activado.
 */
open class SecureActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        checkLock()
    }

    override fun onPause() {
        super.onPause()
        if (!prompting) lastActive = System.currentTimeMillis()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Barra superior clara para las pantallas secundarias. */
    protected fun setupBar(title: String) {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0f
        supportActionBar?.title = title
    }

    private fun checkLock() {
        if (!TrackerStore.lockOn(this) || prompting) return
        if (unlocked && System.currentTimeMillis() - lastActive < GRACE_MS) return
        if (!LockHelper.available(this)) return

        val content = findViewById<View>(android.R.id.content)
        content.visibility = View.INVISIBLE
        prompting = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    unlocked = true
                    lastActive = System.currentTimeMillis()
                    content.visibility = View.VISIBLE
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    unlocked = false
                    // Cancelado por el sistema (p. ej. al girar la pantalla): se volverá a pedir
                    if (errorCode != BiometricPrompt.ERROR_CANCELED) finishAffinity()
                }
            })
        prompt.authenticate(LockHelper.info())
    }

    companion object {
        private const val GRACE_MS = 60_000L
        private var unlocked = false
        private var prompting = false
        private var lastActive = 0L
    }
}
