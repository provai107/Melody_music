package com.braveplayer.music.model

/**
 * Data model for a release item in the Latest Releases carousel and full listing.
 */
data class ReleaseItem(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val releaseTag: String, // e.g. "NEW", "2026", "LATEST", "ALBUM"
    val releaseDate: String?, // e.g. "2026", "Jan 2026", "2025"
    val imageUrl: String?,
    val song: Song,
    val isAlbum: Boolean = false,
    val albumId: String? = null
)

/**
 * Visual and metadata theme for vibrant reference-inspired genre cards.
 */
data class GenreCardTheme(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val emoji: String,
    val query: String,
    val backgroundColorHex: String,
    val artworkUrl: String? = null
)

/**
 * Data model for circular artist cards in the Popular Artists carousel.
 */
data class PopularArtistItem(
    val id: String,
    val name: String,
    val role: String = "Artist",
    val imageUrl: String? = null,
    val query: String,
    val playCount: Int = 0
)
