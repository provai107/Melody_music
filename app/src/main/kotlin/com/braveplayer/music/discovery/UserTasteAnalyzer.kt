package com.braveplayer.music.discovery

import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Analyzes the user's historical listening profile, engagement patterns,
 * explicit preferences, and behavioral signals (likes, skips, completions).
 */
data class UserTasteProfile(
    val topArtists: Map<String, Int> = emptyMap(),
    val topGenres: Map<String, Double> = emptyMap(),
    val likedSongIds: Set<String> = emptySet(),
    val skippedSongIds: Set<String> = emptySet(),
    val notInterestedSongIds: Set<String> = emptySet(),
    val notInterestedArtists: Set<String> = emptySet(),
    val hiddenSongIds: Set<String> = emptySet(),
    val preferredLanguages: Set<String> = emptySet(),
    val favoriteGenres: Set<String> = emptySet(),
    val favoriteArtists: Set<String> = emptySet()
) {
    /**
     * Calculates the user's affinity score for a given artist (0.0 to 1.0).
     */
    fun getArtistAffinity(artist: String): Double {
        val cleanArtist = artist.trim().lowercase()
        if (cleanArtist.isEmpty()) return 0.0
        if (cleanArtist in notInterestedArtists) return 0.0

        // Explicit favorite artist bonus
        val isExplicitFavorite = favoriteArtists.any { it.trim().lowercase() == cleanArtist }
        val favoriteBonus = if (isExplicitFavorite) 0.5 else 0.0

        // Play count frequency scaling (logarithmic curve capped at 1.0)
        val playCount = topArtists.entries.firstOrNull { it.key.trim().lowercase() == cleanArtist }?.value ?: 0
        val playAffinity = when {
            playCount >= 20 -> 0.5
            playCount >= 10 -> 0.4
            playCount >= 5 -> 0.3
            playCount >= 2 -> 0.2
            playCount == 1 -> 0.1
            else -> 0.0
        }

        return (favoriteBonus + playAffinity).coerceIn(0.0, 1.0)
    }

    /**
     * Calculates the user's affinity score for a given genre (0.0 to 1.0).
     */
    fun getGenreAffinity(genre: String): Double {
        val cleanGenre = genre.trim().lowercase()
        if (cleanGenre.isEmpty()) return 0.0

        val isExplicitFavorite = favoriteGenres.any { it.trim().lowercase() == cleanGenre }
        val explicitBonus = if (isExplicitFavorite) 0.5 else 0.0
        val historyAffinity = topGenres[cleanGenre] ?: 0.0

        return (explicitBonus + historyAffinity).coerceIn(0.0, 1.0)
    }

    /**
     * Returns true if the song or artist has been flagged as not interested or hidden.
     */
    fun isExcluded(songId: String, artist: String): Boolean {
        if (songId in notInterestedSongIds || songId in hiddenSongIds) return true
        val cleanArtist = artist.trim().lowercase()
        return cleanArtist in notInterestedArtists
    }
}

class UserTasteAnalyzer(
    private val dbHelper: PlaylistDatabaseHelper,
    private val appSettings: AppSettings? = null
) {
    suspend fun analyzeProfile(personalizationEnabled: Boolean = true): UserTasteProfile = withContext(Dispatchers.IO) {
        val notInterestedSongIds = dbHelper.getNotInterestedSongIds()
        val notInterestedArtists = dbHelper.getNotInterestedArtists()
        val hiddenSongIds = dbHelper.getHiddenSongIds()

        if (!personalizationEnabled) {
            return@withContext UserTasteProfile(
                notInterestedSongIds = notInterestedSongIds,
                notInterestedArtists = notInterestedArtists,
                hiddenSongIds = hiddenSongIds
            )
        }

        val likedIds = dbHelper.getLikedSongIds()
        val skippedIds = dbHelper.getSkippedSongIds()
        val artistPlayCounts = dbHelper.getArtistPlayCounts()

        // Infer genre distribution from recently played songs and liked songs
        val genreScores = mutableMapOf<String, Double>()
        val recentHistory = dbHelper.getRecentlyPlayedSongs(40)
        val likedSongs = dbHelper.getAllLikedSongs().take(30)
        val sampleSongs = (recentHistory + likedSongs).distinctBy { it.id }

        for (song in sampleSongs) {
            val genres = MusicSimilarityService.inferGenres(song)
            val isLiked = song.id in likedIds
            val weight = if (isLiked) 1.5 else 1.0
            for (g in genres) {
                val clean = g.lowercase()
                genreScores[clean] = (genreScores[clean] ?: 0.0) + weight
            }
        }

        // Normalize genre scores to 0.0 - 0.5 range
        val maxScore = genreScores.values.maxOrNull() ?: 1.0
        val normalizedGenres = genreScores.mapValues { (_, score) ->
            if (maxScore > 0.0) (score / maxScore) * 0.5 else 0.0
        }

        val favoriteGenres = AppSettings.DEFAULT_FAVOURITE_GENRES
        val favoriteArtists = AppSettings.DEFAULT_FAVOURITE_SINGERS
        val preferredLanguages = AppSettings.DEFAULT_PREFERRED_LANGUAGES

        UserTasteProfile(
            topArtists = artistPlayCounts,
            topGenres = normalizedGenres,
            likedSongIds = likedIds,
            skippedSongIds = skippedIds,
            notInterestedSongIds = notInterestedSongIds,
            notInterestedArtists = notInterestedArtists,
            hiddenSongIds = hiddenSongIds,
            preferredLanguages = preferredLanguages,
            favoriteGenres = favoriteGenres,
            favoriteArtists = favoriteArtists
        )
    }
}
