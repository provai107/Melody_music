package com.braveplayer.music.model

/**
 * Represents a music track metadata entity returned by the music API.
 */
data class Song(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val imageUrl: String?,
    val durationSec: Int,
    val mediaUrl: String?,
    val mediaUrlDefault: String?,
    val previewUrl: String?
) {
    /**
     * Determines the best available playback URL in order of reliability:
     * 1. mediaUrlDefault (direct AAC/MP4 media stream from CDN)
     * 2. mediaUrl (standard direct stream)
     * 3. previewUrl (sample/preview stream)
     *
     * Returns null if no valid HTTP/HTTPS playback stream is available.
     */
    fun getBestPlaybackUrl(): String? {
        return getCandidatePlaybackUrls().firstOrNull()
    }

    /**
     * Returns all candidate playback URLs in order of preference:
     * 1. mediaUrlDefault
     * 2. mediaUrl
     * 3. previewUrl
     */
    fun getCandidatePlaybackUrls(): List<String> {
        val candidates = listOfNotNull(mediaUrlDefault, mediaUrl, previewUrl)
        return candidates.map { it.trim() }.filter { isValidAudioUrl(it) }
    }

    val formattedDuration: String
        get() {
            if (durationSec <= 0) return "--:--"
            val min = durationSec / 60
            val sec = durationSec % 60
            return "%d:%02d".format(min, sec)
        }

    companion object {
        fun isValidAudioUrl(url: String): Boolean {
            if (url.isBlank()) return false
            val lower = url.lowercase()
            val hasValidScheme = lower.startsWith("http://") || lower.startsWith("https://")
            val hasValidAudioIndicator = lower.contains(".mp4") || lower.contains(".mp3") ||
                    lower.contains(".m4a") || lower.contains(".aac") || lower.contains(".opus") ||
                    lower.contains("videoplayback")
            return hasValidScheme && hasValidAudioIndicator
        }
    }
}
