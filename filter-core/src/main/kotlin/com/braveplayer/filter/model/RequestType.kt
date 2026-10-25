package com.braveplayer.filter.model

/**
 * Classification of network requests within the music player application.
 * Differentiates API/metadata calls from audio stream playback and tracker beacons.
 */
enum class RequestType {
    /**
     * REST API queries, artist/album metadata, lyrics, search suggestions.
     */
    METADATA_API,

    /**
     * Direct audio streams, CDN audio chunks, HLS/DASH media manifests and audio segments.
     */
    AUDIO_PLAYBACK,

    /**
     * Telemetry, third-party analytics, tracking beacons, and behavioral trackers.
     */
    TRACKER_ANALYTICS,

    /**
     * Generic or uncategorized network requests.
     */
    OTHER
}
