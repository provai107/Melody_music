package com.braveplayer.music.model

/**
 * Represents a music track metadata entity returned by the music API.
 */
data class Song(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val imageUrl: String? = null,
    val durationSec: Int,
    val mediaUrl: String? = null,
    val mediaUrlDefault: String? = null,
    val previewUrl: String? = null,
    val releaseDate: String? = null,
    val playCount: Long? = null,
    val year: String? = null
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
     * Adaptively resolves the stream URL matching the requested audio quality preference:
     * - LOSSLESS: Native FLAC/ALAC stream if available, otherwise graceful fallback to 320kbps High Quality
     * - HIGH: 320 kbps high-fidelity stream
     * - STANDARD: 160 kbps balanced stream
     * - SAVER: 96 kbps data saver stream
     */
    fun getPlaybackUrl(quality: String = "HIGH"): String? {
        val candidates = getCandidatePlaybackUrls()
        if (candidates.isEmpty()) return null

        val losslessCandidate = candidates.firstOrNull {
            val lower = it.lowercase()
            lower.contains(".flac") || lower.contains(".alac") || lower.contains(".wav")
        }

        return when (quality) {
            "LOSSLESS" -> {
                losslessCandidate ?: getPlaybackUrl("HIGH")
            }
            "HIGH" -> {
                candidates.firstOrNull { it.contains("_320") }
                    ?: candidates.firstOrNull { it.contains("_160") }?.replace("_160", "_320")
                    ?: candidates.firstOrNull { it.contains("_96") }?.replace("_96", "_320")
                    ?: candidates.first()
            }
            "STANDARD" -> {
                candidates.firstOrNull { it.contains("_160") }
                    ?: candidates.firstOrNull { it.contains("_320") }?.replace("_320", "_160")
                    ?: candidates.firstOrNull { it.contains("_96") }?.replace("_96", "_160")
                    ?: candidates.first()
            }
            "SAVER" -> {
                candidates.firstOrNull { it.contains("_96") }
                    ?: candidates.firstOrNull { it.contains("_160") }?.replace("_160", "_96")
                    ?: candidates.firstOrNull { it.contains("_320") }?.replace("_320", "_96")
                    ?: candidates.first()
            }
            else -> getBestPlaybackUrl()
        }
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
            val hasValidScheme = lower.startsWith("http://") || lower.startsWith("https://") ||
                    lower.startsWith("file://") || lower.startsWith("content://") || lower.startsWith("/")
            val hasValidAudioIndicator = lower.contains(".mp4") || lower.contains(".mp3") ||
                    lower.contains(".m4a") || lower.contains(".aac") || lower.contains(".opus") ||
                    lower.contains("videoplayback")
            return hasValidScheme && hasValidAudioIndicator
        }
    }
}
