package com.nexafbproxy.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(config: ProxyConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USER, encrypt(config.username))
            .putString(KEY_PASS, encrypt(config.password))
            .apply()
    }

    fun load(defaultHost: String, defaultPort: Int): ProxyConfig {
        val host = prefs.getString(KEY_HOST, defaultHost) ?: defaultHost
        val port = prefs.getInt(KEY_PORT, defaultPort)

        return try {
            ProxyConfig(
                host = host,
                port = port,
                username = decrypt(prefs.getString(KEY_USER, "") ?: ""),
                password = decrypt(prefs.getString(KEY_PASS, "") ?: "")
            )
        } catch (_: Exception) {
            // A restored backup can invalidate the Android Keystore key.
            prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
            ProxyConfig(host, port, "", "")
        }
    }

    fun clearCredentials() {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val blob = cipher.iv + encrypted
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        if (blob.isEmpty()) return ""
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.size > IV_LENGTH) { "Encrypted value is invalid" }

        val iv = raw.copyOfRange(0, IV_LENGTH)
        val encrypted = raw.copyOfRange(IV_LENGTH, raw.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, iv)
        )

        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    companion object {
        private const val PREFS = "secure_proxy"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"

        private const val KEY_ALIAS = "nexa_fb_proxy_aes"
        private const val IV_LENGTH = 12
    }
}
