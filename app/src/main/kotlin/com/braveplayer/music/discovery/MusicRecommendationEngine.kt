package com.braveplayer.music.discovery

import android.util.LruCache
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.api.SearchResult
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spotify-inspired local recommendation engine computing candidate relevance
 * using artist similarity, album affinity, liked songs, and listening history.
 * Employs in-memory caching and bounded API queries without fake endpoints.
 */
class MusicRecommendationEngine(
    private val metadataService: MusicMetadataService,
    private val dbHelper: PlaylistDatabaseHelper
) {

    companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache
    }

    // In-memory cache for candidate queries: query -> Pair(timestamp, List<Song>)
    private val candidateCache = LruCache<String, Pair<Long, List<Song>>>(40)

    /**
     * Retrieves ranked recommendations based on a seed song, user likes, and history.
     */
    suspend fun getRecommendations(
        baseUrl: String,
        seedSong: Song,
        excludeSongIds: Set<String> = emptySet(),
        limit: Int = 10
    ): List<Song> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<Song>()
        val likedIds = dbHelper.getLikedSongIds()
        val skippedIds = dbHelper.getSkippedSongIds()
        val artistPlayCounts = dbHelper.getArtistPlayCounts()
        val recentIds = dbHelper.getRecentSongIds(20)

        // Construct bounded queries from seed song metadata
        val queries = mutableListOf<String>()
        val primaryArtist = extractPrimaryArtist(seedSong.artists)
        if (primaryArtist.isNotBlank()) {
            queries.add(primaryArtist)
        }
        if (seedSong.album.isNotBlank() && seedSong.album != "Unknown Album") {
            queries.add(seedSong.album)
        }

        // Add 1 query from user's most-played artist to provide discovery variety
        val topArtist = artistPlayCounts.keys.firstOrNull { it.isNotBlank() && it != primaryArtist }
        if (topArtist != null) {
            queries.add(topArtist)
        }

        for (query in queries) {
            val songs = fetchCandidateSongs(baseUrl, query)
            candidates.addAll(songs)
        }

        rankCandidates(
            seedSong = seedSong,
            candidates = candidates,
            excludeSongIds = excludeSongIds + recentIds,
            likedIds = likedIds,
            skippedIds = skippedIds,
            artistPlayCounts = artistPlayCounts,
            limit = limit
        )
    }

    suspend fun fetchCandidateSongs(baseUrl: String, query: String): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val cached = candidateCache.get(trimmed)
        if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
            return cached.second
        }

        return try {
            val result = metadataService.searchSongs(baseUrl, trimmed, maxRetries = 1)
            if (result is SearchResult.Success) {
                candidateCache.put(trimmed, Pair(now, result.songs))
                result.songs
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun rankCandidates(
        seedSong: Song,
        candidates: List<Song>,
        excludeSongIds: Set<String>,
        likedIds: Set<String>,
        skippedIds: Set<String>,
        artistPlayCounts: Map<String, Int>,
        limit: Int
    ): List<Song> {
        val seenIds = mutableSetOf<String>()
        val seenTitleArtist = mutableSetOf<String>()
        val scoredList = mutableListOf<Pair<Song, Double>>()

        val seedArtist = extractPrimaryArtist(seedSong.artists).lowercase()
        val seedAlbum = seedSong.album.trim().lowercase()

        for (candidate in candidates) {
            if (candidate.getBestPlaybackUrl() == null) continue
            if (candidate.id == seedSong.id) continue
            if (candidate.id in excludeSongIds) continue
            if (!seenIds.add(candidate.id)) continue

            val normKey = "${candidate.title.trim().lowercase()}|${candidate.artists.trim().lowercase()}"
            if (!seenTitleArtist.add(normKey)) continue

            var score = 0.0
            val candidateArtist = extractPrimaryArtist(candidate.artists).lowercase()
            val candidateAlbum = candidate.album.trim().lowercase()

            // 1. Artist similarity
            if (candidateArtist == seedArtist && seedArtist.isNotEmpty()) {
                score += 45.0
            } else if (seedArtist.isNotEmpty() && (candidateArtist.contains(seedArtist) || seedArtist.contains(candidateArtist))) {
                score += 30.0
            } else {
                val artistPlays = artistPlayCounts[extractPrimaryArtist(candidate.artists)] ?: 0
                if (artistPlays > 0) {
                    score += minOf(artistPlays * 3.0, 25.0)
                }
            }

            // 2. Album match
            if (seedAlbum.isNotEmpty() && seedAlbum != "unknown album" && candidateAlbum == seedAlbum) {
                score += 20.0
            }

            // 3. User Liked Song affinity
            if (candidate.id in likedIds) {
                score += 35.0
            }

            // 4. History Skip penalty
            if (candidate.id in skippedIds) {
                score -= 30.0
            }

            // 5. Standard track duration bonus (2 to 7 minutes)
            if (candidate.durationSec in 120..420) {
                score += 5.0
            }

            scoredList.add(Pair(candidate, score))
        }

        scoredList.sortByDescending { it.second }
        return scoredList.take(limit).map { it.first }
    }

    fun extractPrimaryArtist(artists: String): String {
        return artists.split(",", "&", "/").firstOrNull()?.trim() ?: artists.trim()
    }

    fun clearCache() {
        candidateCache.evictAll()
    }
}
