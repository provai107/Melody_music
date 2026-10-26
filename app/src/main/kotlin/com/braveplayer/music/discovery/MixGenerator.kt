package com.braveplayer.music.discovery

import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PersonalizedMix(
    val id: String,
    val title: String,
    val description: String,
    val songs: List<Song>
)

/**
 * Generates personalized mixes including Daily Mix and Discovery Mix.
 * Discovery Mix actively introduces artists outside the user's frequent listening rotation.
 */
class MixGenerator(
    private val recommendationEngine: MusicRecommendationEngine,
    private val dbHelper: PlaylistDatabaseHelper
) {

    /**
     * Generates a "Daily Mix" blending user favorites, frequently played songs,
     * and closely related recommendations.
     */
    suspend fun generateDailyMix(baseUrl: String, limit: Int = 20): PersonalizedMix = withContext(Dispatchers.IO) {
        val resultSongs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()

        val liked = dbHelper.getAllLikedSongs()
        val mostPlayed = dbHelper.getMostPlayedSongs(limit).map { it.first }

        // Interleave top most played and liked tracks
        for (i in 0 until maxOf(liked.size, mostPlayed.size)) {
            if (i < mostPlayed.size && seenIds.add(mostPlayed[i].id)) {
                resultSongs.add(mostPlayed[i])
            }
            if (i < liked.size && seenIds.add(liked[i].id)) {
                resultSongs.add(liked[i])
            }
            if (resultSongs.size >= limit / 2) break
        }

        // If we have a seed track, pull recommendations to enrich the mix
        val seed = resultSongs.firstOrNull() ?: liked.firstOrNull() ?: mostPlayed.firstOrNull()
        if (seed != null && resultSongs.size < limit) {
            val recs = recommendationEngine.getRecommendations(
                baseUrl = baseUrl,
                seedSong = seed,
                excludeSongIds = seenIds,
                limit = limit - resultSongs.size
            )
            for (rec in recs) {
                if (seenIds.add(rec.id)) {
                    resultSongs.add(rec)
                }
            }
        }

        // Cold-start fallback: If resultSongs is still empty (e.g. brand new user), populate from trending tracks
        if (resultSongs.isEmpty()) {
            val fallbackSeeds = listOf("Top Hits", "Trending", "Popular Hits")
            for (query in fallbackSeeds) {
                val candidates = recommendationEngine.fetchCandidateSongs(baseUrl, query)
                for (s in candidates) {
                    if (seenIds.add(s.id)) {
                        resultSongs.add(s)
                    }
                    if (resultSongs.size >= limit) break
                }
                if (resultSongs.isNotEmpty()) break
            }
        }

        PersonalizedMix(
            id = "daily_mix",
            title = "Daily Mix",
            description = "A personalized blend of your top tracks, liked songs, and familiar sounds.",
            songs = resultSongs.take(limit)
        )
    }

    /**
     * Generates a "Discovery Mix" introducing novel artists not frequently played by the user.
     */
    suspend fun generateDiscoveryMix(baseUrl: String, limit: Int = 20): PersonalizedMix = withContext(Dispatchers.IO) {
        val frequentArtists = dbHelper.getArtistPlayCounts().keys.map { it.lowercase() }.toSet()
        val likedIds = dbHelper.getLikedSongIds()
        val recentIds = dbHelper.getRecentSongIds(50)
        val excludeIds = likedIds + recentIds

        val seedTracks = dbHelper.getAllLikedSongs().take(3).ifEmpty {
            dbHelper.getRecentlyPlayedSongs(3)
        }.ifEmpty {
            recommendationEngine.fetchCandidateSongs(baseUrl, "Top Hits").take(3)
        }

        val allCandidates = mutableListOf<Song>()
        for (seed in seedTracks) {
            val queries = listOf(
                seed.artists.split(",", "&", "/").firstOrNull()?.trim() ?: seed.artists,
                seed.album
            ).filter { it.isNotBlank() && it != "Unknown Album" }

            for (q in queries) {
                val songs = recommendationEngine.fetchCandidateSongs(baseUrl, q)
                allCandidates.addAll(songs)
            }
        }

        // If candidates still empty (cold start / network empty), pull fresh discovery search
        if (allCandidates.isEmpty()) {
            allCandidates.addAll(recommendationEngine.fetchCandidateSongs(baseUrl, "Discover Weekly"))
        }

        val scored = rankDiscoveryCandidates(allCandidates, frequentArtists, excludeIds)
        val finalSongs = if (scored.isNotEmpty()) {
            scored.take(limit)
        } else {
            allCandidates.take(limit)
        }

        PersonalizedMix(
            id = "discovery_mix",
            title = "Discovery Mix",
            description = "Fresh tracks and new artists tailored to your tastes but outside your daily rotation.",
            songs = finalSongs
        )
    }

    /**
     * Ranks candidates specifically for the Discovery Mix.
     * Rewards novelty (artists NOT in frequent rotation) and filters out known tracks.
     */
    fun rankDiscoveryCandidates(
        candidates: List<Song>,
        frequentArtists: Set<String>,
        excludeSongIds: Set<String>
    ): List<Song> {
        val seenIds = mutableSetOf<String>()
        val seenArtists = mutableSetOf<String>()
        val list = mutableListOf<Pair<Song, Double>>()

        for (song in candidates) {
            if (song.getBestPlaybackUrl() == null) continue
            if (song.id in excludeSongIds) continue
            if (!seenIds.add(song.id)) continue

            val primaryArtist = recommendationEngine.extractPrimaryArtist(song.artists).lowercase()
            var score = 0.0

            // Novelty boost: artist is NOT in user's frequent rotation
            if (primaryArtist.isNotEmpty() && !frequentArtists.contains(primaryArtist)) {
                score += 50.0
                // Artist diversity in discovery mix: favor unique artists
                if (seenArtists.add(primaryArtist)) {
                    score += 20.0
                }
            } else {
                // Familiar artist penalty in discovery mix
                score -= 30.0
            }

            if (song.durationSec in 120..420) {
                score += 5.0
            }

            list.add(Pair(song, score))
        }

        list.sortByDescending { it.second }
        return list.map { it.first }
    }

    /**
     * Persists a generated mix as a permanent SQLite playlist.
     */
    fun saveMixAsPlaylist(mix: PersonalizedMix, customName: String? = null): Long {
        val playlistName = customName?.trim()?.ifEmpty { null } ?: mix.title
        val playlistId = dbHelper.createPlaylist(playlistName)
        if (playlistId != -1L) {
            for (song in mix.songs) {
                dbHelper.addSongToPlaylist(playlistId, song)
            }
        }
        return playlistId
    }
}
