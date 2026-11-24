package com.mosurabeats.fieldbook

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secrets (the Claude API key) encrypted with a hardware-backed Android Keystore key. Never included in backups. */
object SecureStore {
    private const val ALIAS = "fieldbook_secret"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) { sp = ctx.getSharedPreferences("secure", Context.MODE_PRIVATE) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun get(name: String): String? {
        val v = sp.getString(name, null) ?: return null
        return runCatching {
            val raw = Base64.decode(v, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw.copyOfRange(12, raw.size)))
        }.getOrNull()
    }

    fun set(name: String, value: String?) {
        if (value.isNullOrEmpty()) { sp.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.toByteArray())
        sp.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }
}

/** Fingerprint / face / screen-lock gate for the trainee records. */
class LockManager {
    var locked by mutableStateOf(Prefs.lockEnabled)
    var lastError by mutableStateOf<String?>(null)
    private var prompting = false

    fun available(ctx: Context) =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    fun appWentToBackground() { if (Prefs.lockEnabled) locked = true }

    private fun prompt(act: FragmentActivity, title: String, done: (Boolean) -> Unit) {
        if (prompting) return
        if (!available(act)) { done(true); return } // no screen lock set on the phone: nothing to check against
        prompting = true
        val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { prompting = false; done(true) }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { prompting = false; lastError = msg.toString(); done(false) }
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title)
            .setSubtitle("Trainee records are protected")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build())
    }

    fun unlock(act: FragmentActivity) {
        if (!Prefs.lockEnabled) { locked = false; return }
        prompt(act, "Unlock FieldBook") { ok -> if (ok) { locked = false; lastError = null } }
    }

    fun setEnabled(act: FragmentActivity, on: Boolean) {
        if (!on) { Prefs.lockEnabled = false; locked = false; return }
        prompt(act, "Turn on the FieldBook lock") { ok -> if (ok) { Prefs.lockEnabled = true; locked = false } }
    }
}
