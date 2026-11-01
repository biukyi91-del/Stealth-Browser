package com.stealth.browser.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-Keystore-backed credential storage (PRD §19).
 * Proxy passwords are stored here — NEVER in plaintext prefs, logs, or Room rows.
 */
@Singleton
class SecureCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "stealth_secure_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun store(ref: String, password: CharArray) {
        prefs.edit().putString(ref, String(password).let { it.ifBlank { null } }).apply()
        password.fill('\u0000') // scrub caller's buffer
    }

    fun read(ref: String): String? = prefs.getString(ref, null)

    fun delete(ref: String) {
        prefs.edit().remove(ref).apply()
    }
}
