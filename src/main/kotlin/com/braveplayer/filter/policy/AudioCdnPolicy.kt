package com.braveplayer.filter.policy

import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.FilterResult
import com.braveplayer.filter.model.RequestType

/**
 * Enforces Audio CDN protection and preserves provider-required advertisements.
 *
 * Guarantees that:
 * 1. Broad filter lists (e.g. EasyList) are NEVER applied to audio CDNs.
 * 2. Provider-required ads (such as server-side dynamic ad inserts, sponsored media segments,
 *    and audio ad manifests) are NOT blocked, ensuring stream continuity and API terms compliance.
 * 3. Audio chunk streaming, seeking, and adaptive bitrate media segments are never broken.
 */
class AudioCdnPolicy(
    val customProtectedCdnDomains: Set<String> = emptySet()
) {
    private val defaultProtectedCdnApex = setOf(
        "googlevideo.com",
        "gvt1.com",
        "sndcdn.com",
        "akamaized.net",
        "cloudfront.net",
        "fastly.net",
        "scdn.co",
        "audio-ak-spotify-com.akamaized.net",
        "audio4-ak-spotify-com.akamaized.net",
        "saavncdn.com",
        "jio.com",
        "soundhelix.com"
    )

    private val audioFileExtensions = setOf(
        ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav",
        ".m3u8", ".mpd", ".ts", ".m4s"
    )

    /**
     * Evaluates whether an audio playback request qualifies for CDN protection or provider ad bypass.
     * Returns a non-null [FilterResult] if this request is protected or bypassed; returns null
     * if the request is not an audio request and should proceed to general rule filtering.
     */
    fun evaluateAudioRequest(request: FilterRequest): FilterResult? {
        if (request.requestType != RequestType.AUDIO_PLAYBACK) {
            // General metadata or API requests are not handled by the audio CDN policy
            return null
        }

        val host = request.host

        // 1. Check if the destination is a protected audio CDN
        if (isProtectedCdnHost(host)) {
            return FilterResult.audioBypass(
                reason = "Protected Audio CDN stream destination ($host). Broad filter lists bypassed."
            )
        }

        // 2. Check for audio media file extensions / streaming segments in path
        val path = request.path.lowercase()
        if (audioFileExtensions.any { path.endsWith(it) || path.contains("$it?") }) {
            return FilterResult.audioBypass(
                reason = "Legitimate audio segment request detected (${request.path}). Bypassing broad ad filters."
            )
        }

        // 3. Provider-Required Advertisements Preservation
        // Server-side ad inserts, dynamic audio advertisements, or sponsored media chunks
        if (isProviderRequiredAd(request)) {
            return FilterResult.audioBypass(
                reason = "Provider-required audio advertisement segment preserved to honor API terms."
            )
        }

        // Not a protected CDN host or audio media segment; allow general filter rules to evaluate
        return null
    }

    private fun isProtectedCdnHost(host: String): Boolean {
        if (customProtectedCdnDomains.contains(host)) return true

        return defaultProtectedCdnApex.any { cdnApex ->
            host == cdnApex || host.endsWith(".$cdnApex")
        }
    }

    private fun isProviderRequiredAd(request: FilterRequest): Boolean {
        val url = request.url.lowercase()
        // Typical provider audio ad / midroll query markers in legitimate media streams
        return url.contains("adformat=") ||
                url.contains("videoplayback") && url.contains("ctier=") ||
                url.contains("/ad_segment/") ||
                url.contains("dai_stream=") ||
                url.contains("audio_ad_pod=")
    }
}
