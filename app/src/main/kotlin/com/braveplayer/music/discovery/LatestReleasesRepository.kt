package com.braveplayer.music.discovery

import android.util.LruCache
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.api.SearchResult
import com.braveplayer.music.model.ReleaseItem
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository responsible for fetching, parsing, caching, and serving Latest Music Releases.
 * Implements resilient caching with 1-hour TTL and graceful offline fallbacks.
 */
class LatestReleasesRepository(
    private val metadataService: MusicMetadataService,
    private val dbHelper: PlaylistDatabaseHelper,
    private val appSettings: com.braveplayer.music.settings.AppSettings? = null
) {

    companion object {
        const val CACHE_EXPIRATION_MS = 60 * 60 * 1000L // 1 hour cache TTL

        val DEFAULT_RELEASE_QUERIES = listOf(
            "New Releases 2026",
            "Latest Hindi Songs 2026",
            "Latest English Hits 2026",
            "Latest Punjabi 2026",
            "Trending Singles 2026"
        )

        /**
         * Parses the release year strictly from metadata year or releaseDate.
         * Never guesses year from album/playlist compilation titles.
         */
        fun parseReleaseYear(song: Song): Int? {
            val y = song.year?.trim()?.toIntOrNull()
            if (y != null) return y

            val dateStr = song.releaseDate ?: ""
            val match = Regex("\\b(19\\d\\d|20\\d\\d)\\b").find(dateStr)
            if (match != null) return match.value.toIntOrNull()

            return null
        }

        /**
         * Strictly validates that a track belongs to genuine current/recent releases (2025 or 2026).
         * Discards older catalog tracks (<= 2024).
         */
        fun isRecentRelease(song: Song): Boolean {
            val year = parseReleaseYear(song)
            if (year != null) {
                return year >= 2025
            }

            val dateStr = song.releaseDate ?: ""
            if (dateStr.startsWith("2026") || dateStr.startsWith("2025")) {
                return true
            }

            return false
        }
    }

    private var cachedReleases: List<ReleaseItem> = emptyList()
    private var lastFetchTimestamp: Long = 0L

    /**
     * Fetches latest music releases from the API adapter, caching results for fast retrieval.
     * Strictly prioritizes genuine 2026 and 2025 releases matching user's preferred languages.
     */
    suspend fun fetchLatestReleases(
        baseUrl: String,
        forceRefresh: Boolean = false,
        limit: Int = 25,
        preferredLanguages: Set<String>? = null
    ): List<ReleaseItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedReleases.isNotEmpty() && (now - lastFetchTimestamp < CACHE_EXPIRATION_MS)) {
            return@withContext cachedReleases
        }

        val langs = preferredLanguages ?: appSettings?.getPreferredLanguages() ?: emptySet()
        val collectedSongs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()

        fun addSong(song: Song, forceAccept: Boolean = false) {
            if (song.durationSec in 1..30) return
            if (song.getBestPlaybackUrl() == null) return
            if (!forceAccept && !isRecentRelease(song)) return

            val cleanTitle = song.title.lowercase().trim()
            if (seenIds.add(song.id) && seenTitles.add(cleanTitle)) {
                collectedSongs.add(song)
            }
        }

        val isCustomBackend = baseUrl.isNotBlank() && baseUrl != AppSettings.DEFAULT_BASE_URL
        var backendError = false

        if (isCustomBackend) {
            // Verify custom backend connectivity
            try {
                when (val result = metadataService.searchSongs(baseUrl, "New Releases 2026")) {
                    is SearchResult.Success -> {
                        for (song in result.songs) addSong(song)
                    }
                    is SearchResult.Error -> {
                        backendError = true
                    }
                    else -> Unit
                }
            } catch (_: Exception) {
                backendError = true
            }
        }

        // If not a failing custom backend, fetch genuine JioSaavn new releases/albums
        if (!backendError) {
            try {
                val albumSongs = metadataService.fetchLatestAlbumSongs(langs, limit = limit)
                for (song in albumSongs) {
                    if (isRecentRelease(song)) {
                        addSong(song, forceAccept = true)
                    }
                }
            } catch (_: Exception) {}

            // Secondary Source: Query targeted release queries if more songs needed
            if (collectedSongs.size < limit) {
                val dynamicQueries = mutableListOf<String>()
                if (langs.isNotEmpty()) {
                    for (lang in langs) {
                        dynamicQueries.add("Latest $lang Songs 2026")
                        dynamicQueries.add("New $lang Releases 2026")
                    }
                }
                dynamicQueries.addAll(DEFAULT_RELEASE_QUERIES)

                for (query in dynamicQueries.distinct()) {
                    try {
                        when (val result = metadataService.searchSongs(baseUrl, query)) {
                            is SearchResult.Success -> {
                                for (song in result.songs) {
                                    addSong(song)
                                }
                            }
                            is SearchResult.Error -> {
                                if (collectedSongs.isEmpty() && isCustomBackend) {
                                    break
                                }
                            }
                            else -> Unit
                        }
                    } catch (_: Exception) {}

                    if (collectedSongs.size >= limit * 2) break
                }
            }
        }

        // 3. Sort releases: newest release dates and 2026 tracks first
        val sortedSongs = collectedSongs.sortedWith(
            compareByDescending<Song> { it.releaseDate ?: "" }
                .thenByDescending { parseReleaseYear(it) ?: 0 }
        )

        val releaseItems = if (sortedSongs.isNotEmpty()) {
            sortedSongs.map { song -> createReleaseItem(song) }
        } else {
            // Offline / Error fallback to user history or liked songs
            createFallbackReleases()
        }

        if (releaseItems.isNotEmpty()) {
            cachedReleases = releaseItems.take(limit)
            lastFetchTimestamp = now
        }

        return@withContext cachedReleases
    }

    fun getCachedReleases(): List<ReleaseItem> = cachedReleases

    private fun createReleaseItem(song: Song): ReleaseItem {
        val titleLower = song.title.lowercase()
        val albumLower = song.album.lowercase()
        val year = parseReleaseYear(song)

        val tag = when {
            year == 2026 || titleLower.contains("2026") || albumLower.contains("2026") -> "2026"
            year == 2025 || titleLower.contains("2025") || albumLower.contains("2025") -> "2025"
            titleLower.contains("remix") || albumLower.contains("remix") -> "REMIX"
            song.album.isNotBlank() && song.album != "Unknown Album" && !albumLower.contains("single") -> "ALBUM"
            else -> "NEW"
        }

        val dateDisplay = when {
            tag == "2026" -> "Released 2026"
            tag == "2025" -> "Released 2025"
            tag == "ALBUM" -> "New Album"
            else -> "New Release"
        }

        return ReleaseItem(
            id = song.id,
            title = song.title,
            artists = if (song.artists.isNotBlank()) song.artists else "Featured Artist",
            album = song.album,
            releaseTag = tag,
            releaseDate = dateDisplay,
            imageUrl = song.imageUrl,
            song = song,
            isAlbum = tag == "ALBUM"
        )
    }

    private fun createFallbackReleases(): List<ReleaseItem> {
        val candidates = mutableListOf<Song>()
        try {
            candidates.addAll(dbHelper.getRecentlyPlayedSongs(15))
            if (candidates.size < 10) {
                candidates.addAll(dbHelper.getAllLikedSongs())
            }
        } catch (_: Exception) {
            // Ignore DB errors
        }

        val seen = mutableSetOf<String>()
        return candidates.filter { seen.add(it.id) }.map { createReleaseItem(it) }
    }
}
