package com.braveplayer.music.discovery

import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PersonalizedMix(
    val id: String,
    val title: String,
    val description: String,
    val songs: List<Song>,
    val subtitle: String = "",
    val category: String = "MIX"
)

/**
 * Generates multiple personalized, continuously refreshed mixes:
 * - Daily Mix 1: Favorite artist & top played songs
 * - Daily Mix 2: Complementary artist & diverse genre cluster
 * - Chill & Focus Mix: Melodic acoustic and calm downtempo tracks
 * - Discovery Mix: Novel artists outside the user's frequent listening rotation
 */
class MixGenerator(
    private val recommendationEngine: MusicRecommendationEngine,
    private val dbHelper: PlaylistDatabaseHelper
) {

    /**
     * Generates all available personalized mixes concurrently.
     */
    suspend fun generateAllMixes(baseUrl: String, limit: Int = 20): List<PersonalizedMix> = withContext(Dispatchers.IO) {
        listOf(
            generateDailyMix(baseUrl, limit),
            generateDailyMix2(baseUrl, limit),
            generateChillMix(baseUrl, limit),
            generateDiscoveryMix(baseUrl, limit)
        )
    }

    /**
     * Generates "Daily Mix 1" blending user favorites, frequently played songs,
     * and closely related recommendations around their top artist.
     */
    suspend fun generateDailyMix(baseUrl: String, limit: Int = 20): PersonalizedMix = withContext(Dispatchers.IO) {
        val resultSongs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()

        val hiddenIds = dbHelper.getHiddenSongIds()
        val notInterestedIds = dbHelper.getNotInterestedSongIds()
        seenIds.addAll(hiddenIds + notInterestedIds)

        val liked = dbHelper.getAllLikedSongs().filter { it.id !in seenIds }
        val mostPlayed = dbHelper.getMostPlayedSongs(limit).map { it.first }.filter { it.id !in seenIds }

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

        // Cold-start fallback: If resultSongs is still empty, populate from trending tracks
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

        val topArtist = dbHelper.getArtistPlayCounts().keys.firstOrNull { it.isNotBlank() }
            ?: liked.firstOrNull()?.let { recommendationEngine.extractPrimaryArtist(it.artists) }
            ?: "Your Top Tracks"

        PersonalizedMix(
            id = "daily_mix_1",
            title = "Daily Mix 1",
            subtitle = "Based on $topArtist",
            description = "A personalized session blending your most played favorites and familiar sounds.",
            songs = resultSongs.take(limit),
            category = "FAVORITES"
        )
    }

    /**
     * Generates "Daily Mix 2" based on secondary artist / diverse genre cluster.
     */
    suspend fun generateDailyMix2(baseUrl: String, limit: Int = 20): PersonalizedMix = withContext(Dispatchers.IO) {
        val resultSongs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        seenIds.addAll(dbHelper.getHiddenSongIds() + dbHelper.getNotInterestedSongIds())

        val artistCounts = dbHelper.getArtistPlayCounts()
        // Pick secondary artist from history, or secondary liked song's artist
        val secondaryArtist = artistCounts.keys.drop(1).firstOrNull { it.isNotBlank() }
            ?: dbHelper.getAllLikedSongs().drop(1).firstOrNull()?.let { recommendationEngine.extractPrimaryArtist(it.artists) }
            ?: "Global Energy"

        val candidates = recommendationEngine.fetchCandidateSongs(baseUrl, secondaryArtist)
        for (song in candidates) {
            if (song.getBestPlaybackUrl() != null && seenIds.add(song.id)) {
                resultSongs.add(song)
            }
            if (resultSongs.size >= limit) break
        }

        if (resultSongs.size < limit) {
            val fallback = recommendationEngine.fetchCandidateSongs(baseUrl, "Dance Pop Hits")
            for (song in fallback) {
                if (song.getBestPlaybackUrl() != null && seenIds.add(song.id)) {
                    resultSongs.add(song)
                }
                if (resultSongs.size >= limit) break
            }
        }

        PersonalizedMix(
            id = "daily_mix_2",
            title = "Daily Mix 2",
            subtitle = "Based on $secondaryArtist",
            description = "An energetic blend exploring diverse artists and upbeat rhythms.",
            songs = resultSongs.take(limit),
            category = "ENERGY"
        )
    }

    /**
     * Generates "Chill & Focus Mix" featuring melodic acoustic, lofi, or ambient tracks.
     */
    suspend fun generateChillMix(baseUrl: String, limit: Int = 20): PersonalizedMix = withContext(Dispatchers.IO) {
        val resultSongs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        seenIds.addAll(dbHelper.getHiddenSongIds() + dbHelper.getNotInterestedSongIds())

        val chillQueries = listOf("Acoustic Pop", "Chill Lofi", "Relaxing Acoustic", "Calm Melodies")
        for (query in chillQueries) {
            val candidates = recommendationEngine.fetchCandidateSongs(baseUrl, query)
            for (song in candidates) {
                if (song.getBestPlaybackUrl() != null && seenIds.add(song.id)) {
                    resultSongs.add(song)
                }
                if (resultSongs.size >= limit) break
            }
            if (resultSongs.size >= limit) break
        }

        PersonalizedMix(
            id = "chill_mix",
            title = "Chill & Focus Mix",
            subtitle = "Acoustic & Downtempo",
            description = "Calm melodies and acoustic vibes curated for relaxation and focused listening.",
            songs = resultSongs.take(limit),
            category = "CHILL"
        )
    }

    /**
     * Generates a "Discovery Mix" introducing novel artists not frequently played by the user.
     */
    suspend fun generateDiscoveryMix(baseUrl: String, limit: Int = 20): PersonalizedMix = withContext(Dispatchers.IO) {
        val frequentArtists = dbHelper.getArtistPlayCounts().keys.map { it.lowercase() }.toSet()
        val likedIds = dbHelper.getLikedSongIds()
        val recentIds = dbHelper.getRecentSongIds(50)
        val hiddenIds = dbHelper.getHiddenSongIds()
        val notInterestedIds = dbHelper.getNotInterestedSongIds()
        val excludeIds = likedIds + recentIds + hiddenIds + notInterestedIds

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
            subtitle = "Fresh Horizons",
            description = "Fresh tracks and emerging artists tailored to your tastes, outside your usual rotation.",
            songs = finalSongs,
            category = "DISCOVERY"
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
