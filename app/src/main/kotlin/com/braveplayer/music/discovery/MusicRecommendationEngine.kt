package com.braveplayer.music.discovery

import android.util.LruCache
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.api.SearchResult
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-signal local recommendation engine computing candidate relevance
 * using multi-signal scoring: content similarity, user listening history, liked songs,
 * search interactions, completion rates, skip penalties, not-interested feedback,
 * hidden song filtering, and selectable recommendation diversity.
 */
class MusicRecommendationEngine(
    private val metadataService: MusicMetadataService,
    private val dbHelper: PlaylistDatabaseHelper
) {

    companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache
    }

    // In-memory cache for candidate queries: query -> Pair(timestamp, List<Song>)
    private val candidateCache = LruCache<String, Pair<Long, List<Song>>>(60)

    /**
     * Retrieves ranked recommendations based on a seed song, user preferences, and listening history.
     */
    suspend fun getRecommendations(
        baseUrl: String,
        seedSong: Song,
        excludeSongIds: Set<String> = emptySet(),
        limit: Int = 10,
        personalizationEnabled: Boolean = true,
        avoidRecentlyPlayed: Boolean = true,
        diversityMode: String = AppSettings.DIVERSITY_BALANCED
    ): List<Song> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<Song>()
        val hiddenIds = dbHelper.getHiddenSongIds()
        val notInterestedIds = dbHelper.getNotInterestedSongIds()
        val notInterestedArtists = dbHelper.getNotInterestedArtists()

        val likedIds = if (personalizationEnabled) dbHelper.getLikedSongIds() else emptySet()
        val skippedIds = if (personalizationEnabled) dbHelper.getSkippedSongIds() else emptySet()
        val artistPlayCounts = if (personalizationEnabled) dbHelper.getArtistPlayCounts() else emptyMap()
        val recentIds = if (personalizationEnabled && avoidRecentlyPlayed) dbHelper.getRecentSongIds(25) else emptySet()
        val recentSearches = if (personalizationEnabled) dbHelper.getRecentSearches(10).map { it.queryText.lowercase().trim() } else emptyList()

        // Construct bounded queries from seed song metadata
        val queries = mutableListOf<String>()
        val primaryArtist = extractPrimaryArtist(seedSong.artists)
        if (primaryArtist.isNotBlank() && primaryArtist.lowercase() !in notInterestedArtists) {
            queries.add(primaryArtist)
        }
        if (seedSong.album.isNotBlank() && seedSong.album != "Unknown Album") {
            queries.add(seedSong.album)
        }

        // Contextual discovery queries based on diversity mode
        when (diversityMode) {
            AppSettings.DIVERSITY_FAMILIAR -> {
                // Familiar: add user's most-played artist to keep within comfort zone
                val topArtist = artistPlayCounts.keys.firstOrNull { it.isNotBlank() && it != primaryArtist }
                if (topArtist != null && topArtist.lowercase() !in notInterestedArtists) {
                    queries.add(topArtist)
                }
            }
            AppSettings.DIVERSITY_DISCOVERY -> {
                // Discovery: query for fresh songs or acoustic/remix variants
                val discoveryTokens = listOf("Hits", "Best of", "Radio", "Top")
                queries.add("${seedSong.title} ${discoveryTokens.random()}")
            }
            else -> {
                // Balanced: add secondary artist or user's top artist
                val coArtist = extractSecondaryArtist(seedSong.artists)
                if (!coArtist.isNullOrBlank() && coArtist.lowercase() !in notInterestedArtists) {
                    queries.add(coArtist)
                } else {
                    val topArtist = artistPlayCounts.keys.firstOrNull { it.isNotBlank() && it != primaryArtist }
                    if (topArtist != null && topArtist.lowercase() !in notInterestedArtists) {
                        queries.add(topArtist)
                    }
                }
            }
        }

        for (query in queries.take(4)) {
            val songs = fetchCandidateSongs(baseUrl, query)
            candidates.addAll(songs)
        }

        rankCandidates(
            seedSong = seedSong,
            candidates = candidates,
            excludeSongIds = excludeSongIds + recentIds + hiddenIds,
            likedIds = likedIds,
            skippedIds = skippedIds,
            artistPlayCounts = artistPlayCounts,
            limit = limit,
            notInterestedIds = notInterestedIds,
            notInterestedArtists = notInterestedArtists,
            recentSearchQueries = recentSearches,
            diversityMode = diversityMode,
            personalizationEnabled = personalizationEnabled
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
        artistPlayCounts: Map<String, Int> = emptyMap(),
        limit: Int = 10,
        notInterestedIds: Set<String> = emptySet(),
        notInterestedArtists: Set<String> = emptySet(),
        recentSearchQueries: List<String> = emptyList(),
        diversityMode: String = AppSettings.DIVERSITY_BALANCED,
        personalizationEnabled: Boolean = true
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

            // Permanently filter out "Not Interested" tracks
            if (candidate.id in notInterestedIds) continue

            var score = 0.0
            val candidatePrimaryArtist = extractPrimaryArtist(candidate.artists).lowercase()
            val candidateAlbum = candidate.album.trim().lowercase()

            // 1. Artist similarity
            if (candidatePrimaryArtist == seedArtist && seedArtist.isNotEmpty()) {
                score += 45.0
            } else if (seedArtist.isNotEmpty() && (candidatePrimaryArtist.contains(seedArtist) || seedArtist.contains(candidatePrimaryArtist))) {
                score += 30.0
            }

            // 2. Album match
            if (seedAlbum.isNotEmpty() && seedAlbum != "unknown album" && candidateAlbum == seedAlbum) {
                score += 20.0
            }

            // 3. Not interested artist penalty
            if (candidatePrimaryArtist in notInterestedArtists) {
                score -= 40.0
            }

            // 4. Behavioral signals (only if personalization enabled)
            if (personalizationEnabled) {
                // Liked track bonus
                if (candidate.id in likedIds) {
                    score += if (diversityMode == AppSettings.DIVERSITY_FAMILIAR) 50.0 else 35.0
                }

                // History Skip penalty
                if (candidate.id in skippedIds) {
                    score -= 30.0
                }

                // Search query match bonus
                if (recentSearchQueries.any { q -> candidate.title.lowercase().contains(q) || candidate.artists.lowercase().contains(q) }) {
                    score += 15.0
                }

                // Diversity-dependent artist affinity
                val artistPlays = artistPlayCounts[extractPrimaryArtist(candidate.artists)] ?: 0
                when (diversityMode) {
                    AppSettings.DIVERSITY_FAMILIAR -> {
                        if (artistPlays > 0) {
                            score += minOf(artistPlays * 5.0, 45.0)
                        }
                    }
                    AppSettings.DIVERSITY_DISCOVERY -> {
                        if (artistPlays > 3) {
                            // Penalize already heavily-played artists to encourage novelty
                            score -= 20.0
                        } else if (artistPlays == 0 && candidatePrimaryArtist != seedArtist) {
                            // Boost undiscovered artists
                            score += 35.0
                        }
                    }
                    else -> { // BALANCED
                        if (artistPlays > 0) {
                            score += minOf(artistPlays * 3.0, 25.0)
                        }
                    }
                }
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

    fun extractSecondaryArtist(artists: String): String? {
        val parts = artists.split(",", "&", "/")
        return if (parts.size > 1) parts[1].trim() else null
    }

    fun clearCache() {
        candidateCache.evictAll()
    }
}
