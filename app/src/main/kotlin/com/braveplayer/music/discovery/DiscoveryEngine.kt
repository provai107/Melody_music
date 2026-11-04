package com.braveplayer.music.discovery

import android.util.LruCache
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.api.SearchResult
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Three-tier candidate pool representing the three discovery categories:
 * Category A: Familiar Recommendations (user's favorites, recent, top artists)
 * Category B: Similar Music Discovery (same genre, mood, era, album, collaborators)
 * Category C: Exploration Recommendations (new artists, emerging tracks, related genres)
 */
data class CandidatePool(
    val familiar: List<Song> = emptyList(),
    val similar: List<Song> = emptyList(),
    val exploration: List<Song> = emptyList()
) {
    fun allCandidates(): List<Song> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Song>()
        for (song in familiar + similar + exploration) {
            if (seen.add(song.id)) {
                result.add(song)
            }
        }
        return result
    }
}

class DiscoveryEngine(
    private val metadataService: MusicMetadataService,
    private val dbHelper: PlaylistDatabaseHelper,
    private val tasteAnalyzer: UserTasteAnalyzer? = null
) {
    companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache
    }

    // In-memory cache for candidate queries: query -> Pair(timestamp, List<Song>)
    private val candidateCache = LruCache<String, Pair<Long, List<Song>>>(60)

    /**
     * Generates a balanced candidate pool spanning Familiar, Similar, and Exploration tracks.
     */
    suspend fun generateCandidatePool(
        baseUrl: String,
        seedSong: Song,
        tasteProfile: UserTasteProfile,
        diversityMode: String = AppSettings.DIVERSITY_BALANCED,
        additionalCandidates: List<Song> = emptyList()
    ): CandidatePool = withContext(Dispatchers.IO) {
        val familiarList = mutableListOf<Song>()
        val similarList = mutableListOf<Song>()
        val explorationList = mutableListOf<Song>()

        // 1. Category A: Familiar Candidates
        // Gather from local database (liked songs, top played songs, recent history)
        val likedSongs = dbHelper.getAllLikedSongs()
        val mostPlayed = dbHelper.getMostPlayedSongs(30).map { it.first }
        val recentHistory = dbHelper.getRecentlyPlayedSongs(20)

        // Filter out seedSong itself
        familiarList.addAll((likedSongs + mostPlayed + recentHistory).filter {
            it.id != seedSong.id && !MusicSimilarityService.areTitlesTooSimilar(it.title, seedSong.title)
        }.distinctBy { it.id }.take(25))

        // Also fetch candidate songs for user's top artists if available
        val topArtistNames = tasteProfile.topArtists.keys.filter { it.isNotBlank() }.take(3)
        val familiarQueries = topArtistNames.map { "$it Hits" }

        // 2. Category B: Similar Music Discovery
        val similarQueries = mutableListOf<String>()
        val primaryArtist = MusicSimilarityService.extractPrimaryArtist(seedSong.artists)
        val coArtist = MusicSimilarityService.extractSecondaryArtist(seedSong.artists)
        val inferredGenres = MusicSimilarityService.inferGenres(seedSong)
        val seedAlbum = seedSong.album.trim()
        val seedAlbumLower = seedAlbum.lowercase()
        val seedYear = MusicSimilarityService.parseSongYear(seedSong)

        // Soundtrack / Movie / Album query
        if (seedAlbum.isNotBlank() &&
            seedAlbumLower != "unknown album" &&
            seedAlbumLower != "unknown" &&
            seedAlbumLower != "single" &&
            seedAlbumLower != "ep" &&
            !MusicSimilarityService.areTitlesTooSimilar(seedAlbum, seedSong.title)
        ) {
            similarQueries.add("$seedAlbum Songs")
            similarQueries.add(seedAlbum)
        }

        // Primary & co-artist hits
        if (primaryArtist.isNotBlank() && primaryArtist.lowercase() !in tasteProfile.notInterestedArtists) {
            similarQueries.add("$primaryArtist Hits")
            similarQueries.add(primaryArtist)
        }
        if (!coArtist.isNullOrBlank() && coArtist.lowercase() !in tasteProfile.notInterestedArtists) {
            similarQueries.add(coArtist)
        }

        // Inferred Genre hits
        for (g in inferredGenres.take(2)) {
            val cap = g.replaceFirstChar { it.uppercase() }
            similarQueries.add("$cap Hits")
        }

        // Era query
        if (seedYear != null) {
            val era = when {
                seedYear >= 2020 -> "2020s Hits"
                seedYear >= 2010 -> "2010s Hits"
                seedYear >= 2000 -> "2000s Hits"
                seedYear >= 1990 -> "90s Hits"
                else -> "Retro Hits"
            }
            similarQueries.add(era)
        }

        // 3. Category C: Exploration Recommendations
        val explorationQueries = mutableListOf<String>()
        when (diversityMode) {
            AppSettings.DIVERSITY_DISCOVERY -> {
                explorationQueries.add("Top Trending Songs")
                explorationQueries.add("New Releases")
                if (inferredGenres.isNotEmpty()) {
                    explorationQueries.add("${inferredGenres.first().replaceFirstChar { it.uppercase() }} Best of")
                }
            }
            AppSettings.DIVERSITY_FAMILIAR -> {
                explorationQueries.add("Popular Hits")
            }
            else -> { // BALANCED
                explorationQueries.add("Top Trending Songs")
                if (inferredGenres.isNotEmpty()) {
                    explorationQueries.add("${inferredGenres.first().replaceFirstChar { it.uppercase() }} Hits")
                } else {
                    explorationQueries.add("Popular Hits")
                }
            }
        }

        // Execute API searches concurrently with caching
        coroutineScope {
            val familiarDeferred = familiarQueries.distinct().take(2).map { q ->
                async { fetchCandidateSongs(baseUrl, q) }
            }
            val similarDeferred = similarQueries.distinct().take(4).map { q ->
                async { fetchCandidateSongs(baseUrl, q) }
            }
            val explorationDeferred = explorationQueries.distinct().take(2).map { q ->
                async { fetchCandidateSongs(baseUrl, q) }
            }

            familiarDeferred.awaitAll().forEach { familiarList.addAll(it) }
            similarDeferred.awaitAll().forEach { similarList.addAll(it) }
            explorationDeferred.awaitAll().forEach { explorationList.addAll(it) }
        }

        // Append initial / additional candidates passed from search / context to similar list
        if (additionalCandidates.isNotEmpty()) {
            similarList.addAll(additionalCandidates)
        }

        CandidatePool(
            familiar = familiarList.filter { it.getBestPlaybackUrl() != null }.distinctBy { it.id },
            similar = similarList.filter { it.getBestPlaybackUrl() != null }.distinctBy { it.id },
            exploration = explorationList.filter { it.getBestPlaybackUrl() != null }.distinctBy { it.id }
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
            val result = if (baseUrl.isNotBlank()) {
                metadataService.searchSongs(baseUrl, trimmed, maxRetries = 1)
            } else {
                null
            }

            val songs = if (result is SearchResult.Success && result.songs.isNotEmpty()) {
                result.songs
            } else {
                // Direct fallback
                metadataService.fetchDirectJioSaavnSongs(trimmed, limit = 20)
            }

            if (songs.isNotEmpty()) {
                candidateCache.put(trimmed, Pair(now, songs))
            }
            songs
        } catch (_: Exception) {
            try {
                metadataService.fetchDirectJioSaavnSongs(trimmed, limit = 15)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun clearCache() {
        candidateCache.evictAll()
    }
}
