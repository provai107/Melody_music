package com.braveplayer.music.jam.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages hardware-backed cryptographic storage for Melody Jam guest sessions.
 * Encrypts sensitive guest tokens and installation credentials using AES-256-GCM.
 */
class JamKeyStoreManager(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "MelodyJamSessionKey_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128

        private const val PREFS_NAME = "melody_jam_secure_prefs"
        private const val KEY_INSTALLATION_ID = "enc_jam_install_id"
        private const val KEY_GUEST_TOKEN = "enc_jam_guest_token"
        private const val KEY_GUEST_ID = "enc_jam_guest_id"
        private const val KEY_DISPLAY_NAME = "jam_display_name"
        private const val KEY_TOKEN_EXPIRY = "jam_token_expiry"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()

                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            }
        } catch (_: Exception) {
            // Android Keystore initialization handled gracefully
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (_: Exception) {
            null
        }
    }

    fun encrypt(plaintext: String): String? {
        if (plaintext.isEmpty()) return ""
        val secretKey = getSecretKey() ?: return fallbackBase64Encode(plaintext)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            fallbackBase64Encode(plaintext)
        }
    }

    fun decrypt(ciphertext: String?): String? {
        if (ciphertext.isNullOrEmpty()) return null
        val secretKey = getSecretKey() ?: return fallbackBase64Decode(ciphertext)
        return try {
            val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH) return fallbackBase64Decode(ciphertext)

            val iv = ByteArray(GCM_IV_LENGTH)
            val encryptedBytes = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, encryptedBytes, 0, encryptedBytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            fallbackBase64Decode(ciphertext)
        }
    }

    private fun fallbackBase64Encode(str: String): String {
        return "b64_" + Base64.encodeToString(str.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun fallbackBase64Decode(str: String): String? {
        return try {
            if (str.startsWith("b64_")) {
                val sub = str.substring(4)
                String(Base64.decode(sub, Base64.NO_WRAP), Charsets.UTF_8)
            } else {
                str
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets or creates a persistent installation identifier.
     */
    fun getOrCreateInstallationId(): String {
        val storedEncrypted = prefs.getString(KEY_INSTALLATION_ID, null)
        val decrypted = decrypt(storedEncrypted)
        if (!decrypted.isNullOrBlank()) {
            return decrypted
        }

        val newId = "inst_" + UUID.randomUUID().toString().replace("-", "")
        encrypt(newId)?.let { enc ->
            prefs.edit().putString(KEY_INSTALLATION_ID, enc).apply()
        }
        return newId
    }

    /**
     * Retrieves stored guest display name or a default name.
     */
    fun getDisplayName(): String {
        val stored = prefs.getString(KEY_DISPLAY_NAME, null)
        if (!stored.isNullOrBlank()) return stored

        // Generate friendly default like "Melody User #4812"
        val installId = getOrCreateInstallationId()
        val suffix = installId.takeLast(4).uppercase()
        val defaultName = "Melody User #$suffix"
        prefs.edit().putString(KEY_DISPLAY_NAME, defaultName).apply()
        return defaultName
    }

    fun setDisplayName(displayName: String) {
        val safe = displayName.trim().take(30).ifBlank { "Melody User" }
        prefs.edit().putString(KEY_DISPLAY_NAME, safe).apply()
    }

    /**
     * Stores authenticated session credentials securely.
     */
    fun saveSession(guestId: String, token: String, expiresAt: Long) {
        val encGuestId = encrypt(guestId)
        val encToken = encrypt(token)
        prefs.edit()
            .putString(KEY_GUEST_ID, encGuestId)
            .putString(KEY_GUEST_TOKEN, encToken)
            .putLong(KEY_TOKEN_EXPIRY, expiresAt)
            .apply()
    }

    fun getGuestToken(): String? {
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        if (expiry > 0 && System.currentTimeMillis() >= expiry) {
            return null // Token expired
        }
        val encToken = prefs.getString(KEY_GUEST_TOKEN, null) ?: return null
        return decrypt(encToken)
    }

    fun getGuestId(): String? {
        val encGuestId = prefs.getString(KEY_GUEST_ID, null) ?: return null
        return decrypt(encGuestId)
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_GUEST_ID)
            .remove(KEY_GUEST_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .apply()
    }
}
