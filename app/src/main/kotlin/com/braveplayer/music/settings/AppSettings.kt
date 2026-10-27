package com.braveplayer.music.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages configurable application settings, including the backend API base URL.
 * Defaults to "http://10.0.2.2:5000" for Android Studio emulator host mapping.
 */
class AppSettings(context: Context) {

    companion object {
        private const val PREFS_NAME = "brave_music_settings"
        private const val KEY_BASE_URL = "backend_base_url"
        private const val KEY_AUTOPLAY = "autoplay_enabled"

        // Default free cloud API URL hosted on Vercel
        const val DEFAULT_BASE_URL = "https://melody-api-psi.vercel.app"
        const val DEFAULT_EMULATOR_BASE_URL = "https://melody-api-psi.vercel.app"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(): String {
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun setBaseUrl(rawUrl: String): String {
        val sanitized = sanitizeUrl(rawUrl)
        prefs.edit().putString(KEY_BASE_URL, sanitized).apply()
        return sanitized
    }

    fun isAutoplayEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTOPLAY, true)
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY, enabled).apply()
    }

    fun resetToDefault(): String {
        prefs.edit().putString(KEY_BASE_URL, DEFAULT_BASE_URL).apply()
        return DEFAULT_BASE_URL
    }

    fun sanitizeUrl(input: String): String {
        var trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return DEFAULT_EMULATOR_BASE_URL
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        return trimmed.trimEnd('/')
    }
}
