package com.braveplayer.music.discovery

import android.util.LruCache
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.api.SearchResult
import com.braveplayer.music.model.ReleaseItem
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository responsible for fetching, parsing, caching, and serving Latest Music Releases.
 * Implements resilient caching with 1-hour TTL and graceful offline fallbacks.
 */
class LatestReleasesRepository(
    private val metadataService: MusicMetadataService,
    private val dbHelper: PlaylistDatabaseHelper
) {

    companion object {
        const val CACHE_EXPIRATION_MS = 60 * 60 * 1000L // 1 hour cache TTL

        val RELEASE_QUERIES = listOf(
            "New Releases 2026",
            "Latest Hindi Songs",
            "Latest English Hits",
            "Latest Punjabi",
            "Trending Singles 2026"
        )
    }

    private var cachedReleases: List<ReleaseItem> = emptyList()
    private var lastFetchTimestamp: Long = 0L

    /**
     * Fetches latest music releases from the API adapter, caching results for fast retrieval.
     */
    suspend fun fetchLatestReleases(
        baseUrl: String,
        forceRefresh: Boolean = false,
        limit: Int = 25
    ): List<ReleaseItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedReleases.isNotEmpty() && (now - lastFetchTimestamp < CACHE_EXPIRATION_MS)) {
            return@withContext cachedReleases
        }

        val collectedSongs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()

        for (query in RELEASE_QUERIES) {
            try {
                when (val result = metadataService.searchSongs(baseUrl, query)) {
                    is SearchResult.Success -> {
                        for (song in result.songs) {
                            val cleanTitle = song.title.lowercase().trim()
                            if (seenIds.add(song.id) && seenTitles.add(cleanTitle)) {
                                collectedSongs.add(song)
                            }
                        }
                    }
                    else -> Unit
                }
            } catch (_: Exception) {
                // Continue to next candidate query on error
            }

            if (collectedSongs.size >= limit * 2) break
        }

        val releaseItems = if (collectedSongs.isNotEmpty()) {
            collectedSongs.map { song -> createReleaseItem(song) }
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

        val tag = when {
            titleLower.contains("2026") || albumLower.contains("2026") -> "2026"
            titleLower.contains("remix") || albumLower.contains("remix") -> "REMIX"
            song.album.isNotBlank() && song.album != "Unknown Album" && !albumLower.contains("single") -> "ALBUM"
            else -> "NEW"
        }

        val dateDisplay = when {
            tag == "2026" -> "Released 2026"
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
