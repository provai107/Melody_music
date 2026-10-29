package com.braveplayer.music.api

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.model.RequestType
import com.braveplayer.filter.network.FilteringOkHttpInterceptor
import com.braveplayer.music.model.ArtistProfile
import com.braveplayer.music.model.ArtistSearchResult
import com.braveplayer.music.model.ArtistSortOption
import com.braveplayer.music.model.ArtistSongSorter
import com.braveplayer.music.model.SearchResponseParser
import com.braveplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed class SearchResult {
    data class Success(val songs: List<Song>) : SearchResult()
    data class Empty(val query: String) : SearchResult()
    data class Error(val message: String, val statusCode: Int = -1) : SearchResult()
}

/**
 * Service handling metadata and API operations with strict isolation from audio playback streams.
 * Uses an OkHttpClient wired with [FilteringOkHttpInterceptor].
 */
class MusicMetadataService(
    private val filterListManager: FilterListManager
) {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(
            FilteringOkHttpInterceptor(
                filterListManager = filterListManager,
                defaultRequestType = RequestType.METADATA_API
            )
        )
        .build()

    suspend fun searchSongs(baseUrl: String, query: String, maxRetries: Int = 2): SearchResult = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext SearchResult.Empty(query)
        }

        val encoded = try {
            URLEncoder.encode(trimmedQuery, "UTF-8")
        } catch (_: Exception) {
            trimmedQuery
        }

        val searchUrl = "$baseUrl/search?query=$encoded"
        val request = Request.Builder()
            .url(searchUrl)
            .header("X-Player-Request-Type", RequestType.METADATA_API.name)
            .build()

        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val response = httpClient.newCall(request).execute()
                if (response.header("X-Filter-Blocked") == "true") {
                    return@withContext SearchResult.Error("API request blocked by filter: ${response.header("X-Filter-Rule")}")
                }

                if (!response.isSuccessful) {
                    if (response.code in 500..599 && attempt < maxRetries) {
                        kotlinx.coroutines.delay(200L * (attempt + 1))
                        continue
                    }
                    return@withContext SearchResult.Error(
                        "Backend error: HTTP ${response.code} ${response.message}",
                        response.code
                    )
                }

                val body = response.body?.string() ?: ""
                val songs = SearchResponseParser.parseSearchResponse(body)
                return@withContext if (songs.isEmpty()) {
                    SearchResult.Empty(trimmedQuery)
                } else {
                    SearchResult.Success(songs)
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(200L * (attempt + 1))
                    continue
                }
            } catch (e: Exception) {
                return@withContext SearchResult.Error("Unexpected error: ${e.message}")
            }
        }
        SearchResult.Error("Network error: ${lastException?.message ?: "Failed to connect to backend"}")
    }

    suspend fun queryTrackMetadata(url: String): MetadataQueryResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("X-Player-Request-Type", RequestType.METADATA_API.name)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val isBlocked = response.header("X-Filter-Blocked") == "true"
            val rule = response.header("X-Filter-Rule")
            val code = response.code

            MetadataQueryResult(
                statusCode = code,
                isBlocked = isBlocked,
                matchedRule = rule,
                body = response.body?.string() ?: ""
            )
        } catch (e: IOException) {
            MetadataQueryResult(
                statusCode = -1,
                isBlocked = false,
                matchedRule = null,
                body = "Network error: ${e.message}"
            )
        }
    }

    suspend fun fetchSongDetails(baseUrl: String, songId: String, fallbackQuery: String? = null): Song? = withContext(Dispatchers.IO) {
        val trimmedId = songId.trim()
        if (trimmedId.isEmpty()) return@withContext null

        try {
            val url = "$baseUrl/song/$trimmedId"
            val request = Request.Builder()
                .url(url)
                .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val songs = SearchResponseParser.parseSearchResponse(body)
                // Require strict stable ID match; never substitute an unrelated track
                val match = songs.firstOrNull { it.id == trimmedId }
                if (match != null && match.getBestPlaybackUrl() != null) {
                    return@withContext match
                }
            }
        } catch (_: Exception) {}

        // Fallback to search query but ONLY accept results matching the exact stable song ID
        val queryToSearch = fallbackQuery?.takeIf { it.isNotBlank() } ?: trimmedId
        val searchRes = searchSongs(baseUrl, queryToSearch, maxRetries = 1)
        if (searchRes is SearchResult.Success) {
            val exact = searchRes.songs.firstOrNull { it.id == trimmedId }
            if (exact != null && exact.getBestPlaybackUrl() != null) {
                return@withContext exact
            }
        }
        null
    }

    companion object {
        const val JIOSAAVN_API_BASE = "https://www.jiosaavn.com/api.php?_format=json&_marker=0&api_version=4&ctx=web6dot0"
    }

    suspend fun searchArtists(baseUrl: String, query: String): List<ArtistSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val encoded = try {
            URLEncoder.encode(trimmed, "UTF-8")
        } catch (_: Exception) {
            trimmed
        }

        // 1. Direct JioSaavn provider artist search
        try {
            val url = "$JIOSAAVN_API_BASE&__call=search.getArtistResults&q=$encoded&p=1&n=10"
            val request = Request.Builder()
                .url(url)
                .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val results = SearchResponseParser.parseArtistSearchResults(body)
                if (results.isNotEmpty()) {
                    return@withContext results
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback: derive distinct matching artists from song search results
        try {
            when (val searchRes = searchSongs(baseUrl, trimmed, maxRetries = 1)) {
                is SearchResult.Success -> {
                    val matching = mutableListOf<ArtistSearchResult>()
                    val seenNames = mutableSetOf<String>()
                    val lowerQuery = trimmed.lowercase()
                    for (song in searchRes.songs) {
                        val names = song.artists.split(",", "&", "feat.", "ft.", "/").map { it.trim() }
                        for (name in names) {
                            if (name.lowercase().contains(lowerQuery) && seenNames.add(name.lowercase())) {
                                matching.add(
                                    ArtistSearchResult(
                                        id = "local_${name.hashCode()}",
                                        name = name,
                                        role = "Artist",
                                        imageUrl = song.imageUrl
                                    )
                                )
                            }
                        }
                    }
                    if (matching.isNotEmpty()) return@withContext matching
                }
                else -> Unit
            }
        } catch (_: Exception) {}

        emptyList()
    }

    suspend fun getArtistProfile(artistId: String, artistName: String? = null): ArtistProfile? = withContext(Dispatchers.IO) {
        val trimmedId = artistId.trim()
        val trimmedName = artistName?.trim().orEmpty()

        if (trimmedId.isNotEmpty() && !trimmedId.startsWith("local_")) {
            try {
                val url = "$JIOSAAVN_API_BASE&__call=artist.getArtistPageDetails&artistId=$trimmedId"
                val request = Request.Builder()
                    .url(url)
                    .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val profile = SearchResponseParser.parseArtistDetails(body)
                    if (profile != null) {
                        return@withContext profile
                    }
                }
            } catch (_: Exception) {}
        }

        // If artistId not present or lookup failed, try searching the artist by name first
        if (trimmedName.isNotEmpty()) {
            val searchResults = searchArtists("", trimmedName)
            val matched = searchResults.firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }
                ?: searchResults.firstOrNull()
            if (matched != null && matched.id.isNotEmpty() && !matched.id.startsWith("local_")) {
                try {
                    val url = "$JIOSAAVN_API_BASE&__call=artist.getArtistPageDetails&artistId=${matched.id}"
                    val request = Request.Builder()
                        .url(url)
                        .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val profile = SearchResponseParser.parseArtistDetails(body)
                        if (profile != null) {
                            return@withContext profile
                        }
                    }
                } catch (_: Exception) {}
            }

            // Fallback bare profile
            return@withContext ArtistProfile(
                id = trimmedId.ifBlank { "artist_${trimmedName.hashCode()}" },
                name = trimmedName,
                role = "Artist",
                imageUrl = matched?.imageUrl
            )
        }

        null
    }

    suspend fun getArtistTracks(
        baseUrl: String,
        artistId: String?,
        artistName: String,
        sort: ArtistSortOption = ArtistSortOption.POPULARITY
    ): List<Song> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()

        // 1. Fetch from search endpoint (provides guaranteed playable media streams)
        val cleanName = artistName.trim()
        if (cleanName.isNotEmpty()) {
            when (val searchRes = searchSongs(baseUrl, cleanName, maxRetries = 1)) {
                is SearchResult.Success -> {
                    for (song in searchRes.songs) {
                        val normTitle = song.title.lowercase().trim()
                        if (seenIds.add(song.id) && seenTitles.add(normTitle)) {
                            collected.add(song)
                        }
                    }
                }
                else -> Unit
            }
        }

        // 2. Fetch more tracks from provider artist catalog if artistId available
        val trimmedId = artistId?.trim()
        if (!trimmedId.isNullOrEmpty() && !trimmedId.startsWith("local_")) {
            try {
                val url = "$JIOSAAVN_API_BASE&__call=artist.getArtistMoreSong&artistId=$trimmedId&p=1&n=20"
                val request = Request.Builder()
                    .url(url)
                    .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val catalogSongs = SearchResponseParser.parseArtistMoreSongs(body)
                    for (song in catalogSongs) {
                        val normTitle = song.title.lowercase().trim()
                        if (seenIds.add(song.id) && seenTitles.add(normTitle)) {
                            collected.add(song)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        ArtistSongSorter.sort(collected, sort)
    }

    data class MetadataQueryResult(
        val statusCode: Int,
        val isBlocked: Boolean,
        val matchedRule: String?,
        val body: String
    )
}
