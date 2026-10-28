package com.braveplayer.music.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages configurable application settings, including the backend API base URL.
 * Defaults to "http://10.0.2.2:5000" for Android Studio emulator host mapping.
 */
class AppSettings(context: Context) {

    companion object {
        const val PREFS_NAME = "brave_music_settings"
        private const val KEY_BASE_URL = "backend_base_url"
        const val KEY_AUTOPLAY = "autoplay_enabled"
        private const val KEY_SMART_SHUFFLE = "smart_shuffle_enabled"
        private const val KEY_SHUFFLE_MODE = "shuffle_mode"
        private const val KEY_PERSONALIZATION = "personalization_enabled"
        private const val KEY_AVOID_RECENT = "avoid_recently_played"
        private const val KEY_DIVERSITY = "recommendation_diversity"

        const val DIVERSITY_BALANCED = "BALANCED"
        const val DIVERSITY_FAMILIAR = "FAMILIAR"
        const val DIVERSITY_DISCOVERY = "DISCOVERY"

        const val SHUFFLE_MODE_NORMAL = "NORMAL"
        const val SHUFFLE_MODE_SHUFFLE = "SHUFFLE"
        const val SHUFFLE_MODE_SMART = "SMART_SHUFFLE"

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

    fun isSmartShuffleEnabled(): Boolean {
        return prefs.getBoolean(KEY_SMART_SHUFFLE, false)
    }

    fun setSmartShuffleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_SHUFFLE, enabled).apply()
    }

    fun getShuffleMode(): String {
        return prefs.getString(KEY_SHUFFLE_MODE, SHUFFLE_MODE_NORMAL) ?: SHUFFLE_MODE_NORMAL
    }

    fun setShuffleMode(mode: String) {
        prefs.edit().putString(KEY_SHUFFLE_MODE, mode).apply()
    }

    fun isPersonalizationEnabled(): Boolean {
        return prefs.getBoolean(KEY_PERSONALIZATION, true)
    }

    fun setPersonalizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSONALIZATION, enabled).apply()
    }

    fun shouldAvoidRecentlyPlayed(): Boolean {
        return prefs.getBoolean(KEY_AVOID_RECENT, true)
    }

    fun setAvoidRecentlyPlayed(avoid: Boolean) {
        prefs.edit().putBoolean(KEY_AVOID_RECENT, avoid).apply()
    }

    fun getRecommendationDiversity(): String {
        return prefs.getString(KEY_DIVERSITY, DIVERSITY_BALANCED) ?: DIVERSITY_BALANCED
    }

    fun setRecommendationDiversity(diversity: String) {
        prefs.edit().putString(KEY_DIVERSITY, diversity).apply()
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
