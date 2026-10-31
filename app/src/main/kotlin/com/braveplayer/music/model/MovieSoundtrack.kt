package com.braveplayer.music.model

/**
 * Represents a movie or album soundtrack collection containing its official songs.
 */
data class MovieSoundtrack(
    val id: String,
    val title: String,
    val imageUrl: String? = null,
    val year: String? = null,
    val subtitle: String? = null,
    val songs: List<Song> = emptyList()
)
