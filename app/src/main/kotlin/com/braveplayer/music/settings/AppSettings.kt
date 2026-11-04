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
        private const val KEY_RECOMMENDATION_INFLUENCE = "recommendation_influence"
        private const val KEY_EXCLUDE_DISLIKED = "exclude_disliked_tracks"
        private const val KEY_REDUCED_INTENSITY = "reduced_accent_intensity"
        private const val KEY_AUDIO_QUALITY = "audio_quality"
        private const val KEY_SPATIAL_AUDIO_ENABLED = "spatial_audio_enabled"
        private const val KEY_SPATIAL_AUDIO_MODE = "spatial_audio_mode"
        private const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
        private const val KEY_EQUALIZER_PRESET = "equalizer_preset"
        private const val KEY_VOLUME_NORMALIZATION = "volume_normalization_enabled"
        private const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"
        private const val KEY_GAPLESS_PLAYBACK = "gapless_playback"
        private const val KEY_CLEAR_QUEUE_ON_PLAY = "clear_queue_on_play"
        private const val KEY_FAVOURITE_GENRES = "favourite_genres"
        private const val KEY_FAVOURITE_SINGERS = "favourite_singers"
        private const val KEY_PREFERRED_LANGUAGES = "preferred_languages"
        const val KEY_JAM_SERVER_HOST = "jam_server_host"
        const val KEY_JAM_SERVER_PORT = "jam_server_port"

        const val QUALITY_LOSSLESS = "LOSSLESS"
        const val QUALITY_HIGH = "HIGH"
        const val QUALITY_STANDARD = "STANDARD"
        const val QUALITY_SAVER = "SAVER"

        const val SPATIAL_MODE_STEREO = "STEREO"
        const val SPATIAL_MODE_SPATIAL = "SPATIAL"
        const val SPATIAL_MODE_IMMERSIVE = "IMMERSIVE"

        const val DIVERSITY_BALANCED = "BALANCED"
        const val DIVERSITY_FAMILIAR = "FAMILIAR"
        const val DIVERSITY_DISCOVERY = "DISCOVERY"

        const val INFLUENCE_LOW = "LOW"
        const val INFLUENCE_MEDIUM = "MEDIUM"
        const val INFLUENCE_HIGH = "HIGH"

        const val SHUFFLE_MODE_NORMAL = "NORMAL"
        const val SHUFFLE_MODE_SHUFFLE = "SHUFFLE"
        const val SHUFFLE_MODE_SMART = "SMART_SHUFFLE"

        val DEFAULT_FAVOURITE_GENRES = setOf("Bollywood", "Pop", "Punjabi", "Romantic", "Lo-Fi")
        val DEFAULT_FAVOURITE_SINGERS = setOf("Arijit Singh", "Atif Aslam", "Darshan Raval", "Shreya Ghoshal", "Pritam", "Diljit Dosanjh")
        val DEFAULT_PREFERRED_LANGUAGES = setOf("Hindi", "English", "Punjabi")

        val ALL_GENRE_OPTIONS = listOf(
            "Bollywood", "Pop", "Punjabi", "Romantic", "Lo-Fi",
            "Hip Hop", "Rock", "EDM", "R&B", "Indie",
            "Acoustic", "Classical", "Chill", "Workout", "Focus", "Party"
        )

        val POPULAR_SINGER_OPTIONS = listOf(
            "Arijit Singh", "Atif Aslam", "Darshan Raval", "Shreya Ghoshal",
            "Pritam", "Diljit Dosanjh", "The Weeknd", "Taylor Swift",
            "Billie Eilish", "Badshah", "AP Dhillon", "Ed Sheeran",
            "Anirudh", "Sachin-Jigar", "Neha Kakkar", "Jubin Nautiyal",
            "Karan Aujla", "Sidhu Moose Wala", "Yo Yo Honey Singh", "Sonu Nigam", "KK"
        )

        val ALL_LANGUAGE_OPTIONS = listOf(
            "Hindi", "English", "Punjabi", "Tamil", "Telugu",
            "Bengali", "Bhojpuri", "Gujarati", "Marathi", "Kannada", "Malayalam"
        )

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

    fun getRecommendationInfluence(): String {
        return prefs.getString(KEY_RECOMMENDATION_INFLUENCE, INFLUENCE_MEDIUM) ?: INFLUENCE_MEDIUM
    }

    fun setRecommendationInfluence(influence: String) {
        prefs.edit().putString(KEY_RECOMMENDATION_INFLUENCE, influence).apply()
    }

    fun shouldExcludeDislikedTracks(): Boolean {
        return prefs.getBoolean(KEY_EXCLUDE_DISLIKED, true)
    }

    fun setExcludeDislikedTracks(exclude: Boolean) {
        prefs.edit().putBoolean(KEY_EXCLUDE_DISLIKED, exclude).apply()
    }

    fun isReducedIntensityEnabled(): Boolean {
        return prefs.getBoolean(KEY_REDUCED_INTENSITY, false)
    }

    fun setReducedIntensityEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REDUCED_INTENSITY, enabled).apply()
    }

    fun getAudioQuality(): String {
        return prefs.getString(KEY_AUDIO_QUALITY, QUALITY_HIGH) ?: QUALITY_HIGH
    }

    fun setAudioQuality(quality: String) {
        prefs.edit().putString(KEY_AUDIO_QUALITY, quality).apply()
    }

    fun isSpatialAudioEnabled(): Boolean {
        return prefs.getBoolean(KEY_SPATIAL_AUDIO_ENABLED, false)
    }

    fun setSpatialAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPATIAL_AUDIO_ENABLED, enabled).apply()
    }

    fun getSpatialAudioMode(): String {
        return prefs.getString(KEY_SPATIAL_AUDIO_MODE, SPATIAL_MODE_STEREO) ?: SPATIAL_MODE_STEREO
    }

    fun setSpatialAudioMode(mode: String) {
        prefs.edit().putString(KEY_SPATIAL_AUDIO_MODE, mode).apply()
    }

    fun isEqualizerEnabled(): Boolean {
        return prefs.getBoolean(KEY_EQUALIZER_ENABLED, false)
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EQUALIZER_ENABLED, enabled).apply()
    }

    fun getEqualizerPreset(): String {
        return prefs.getString(KEY_EQUALIZER_PRESET, "Flat") ?: "Flat"
    }

    fun setEqualizerPreset(preset: String) {
        prefs.edit().putString(KEY_EQUALIZER_PRESET, preset).apply()
    }

    fun isVolumeNormalizationEnabled(): Boolean {
        return prefs.getBoolean(KEY_VOLUME_NORMALIZATION, true)
    }

    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOLUME_NORMALIZATION, enabled).apply()
    }

    fun getCrossfadeDurationSeconds(): Int {
        return prefs.getInt(KEY_CROSSFADE_SECONDS, 0)
    }

    fun setCrossfadeDurationSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_CROSSFADE_SECONDS, seconds).apply()
    }

    fun isGaplessPlaybackEnabled(): Boolean {
        return prefs.getBoolean(KEY_GAPLESS_PLAYBACK, true)
    }

    fun setGaplessPlaybackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GAPLESS_PLAYBACK, enabled).apply()
    }

    fun shouldClearQueueOnPlay(): Boolean {
        return prefs.getBoolean(KEY_CLEAR_QUEUE_ON_PLAY, false)
    }

    fun setClearQueueOnPlay(clear: Boolean) {
        prefs.edit().putBoolean(KEY_CLEAR_QUEUE_ON_PLAY, clear).apply()
    }

    fun getFavouriteGenres(): Set<String> {
        val raw = prefs.getStringSet(KEY_FAVOURITE_GENRES, null)
        return if (raw.isNullOrEmpty()) DEFAULT_FAVOURITE_GENRES else raw
    }

    fun setFavouriteGenres(genres: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVOURITE_GENRES, genres).apply()
    }

    fun getFavouriteSingers(): Set<String> {
        val raw = prefs.getStringSet(KEY_FAVOURITE_SINGERS, null)
        return if (raw.isNullOrEmpty()) DEFAULT_FAVOURITE_SINGERS else raw
    }

    fun setFavouriteSingers(singers: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVOURITE_SINGERS, singers).apply()
    }

    fun getPreferredLanguages(): Set<String> {
        val raw = prefs.getStringSet(KEY_PREFERRED_LANGUAGES, null)
        return if (raw.isNullOrEmpty()) DEFAULT_PREFERRED_LANGUAGES else raw
    }

    fun setPreferredLanguages(languages: Set<String>) {
        prefs.edit().putStringSet(KEY_PREFERRED_LANGUAGES, languages).apply()
    }

    fun resetMusicPreferences() {
        prefs.edit()
            .remove(KEY_FAVOURITE_GENRES)
            .remove(KEY_FAVOURITE_SINGERS)
            .remove(KEY_PREFERRED_LANGUAGES)
            .apply()
    }

    fun resetToDefault(): String {
        prefs.edit().putString(KEY_BASE_URL, DEFAULT_BASE_URL).apply()
        return DEFAULT_BASE_URL
    }

    fun getJamServerHost(): String {
        return prefs.getString(KEY_JAM_SERVER_HOST, "melody-music-szk2.onrender.com") ?: "melody-music-szk2.onrender.com"
    }

    fun setJamServerHost(host: String) {
        prefs.edit().putString(KEY_JAM_SERVER_HOST, host.trim()).apply()
    }

    fun getJamServerPort(): Int {
        return prefs.getInt(KEY_JAM_SERVER_PORT, 443)
    }

    fun setJamServerPort(port: Int) {
        prefs.edit().putInt(KEY_JAM_SERVER_PORT, port).apply()
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
