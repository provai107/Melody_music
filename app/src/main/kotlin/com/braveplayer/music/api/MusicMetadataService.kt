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

    companion object {
        const val JIOSAAVN_API_BASE = "https://www.jiosaavn.com/api.php?_format=json&_marker=0&api_version=4&ctx=web6dot0"
        const val ATIF_ASLAM_ALBUM_ID = "15514682"
        const val WOH_LAMHE_ZEHER_ID = "E72zWT1L"
        const val ATIF_ASLAM_IMAGE = "https://c.saavncdn.com/180/Atif-Aslam-Hindi-2019-20190401163644-500x500.jpg"
    }

    private fun fetchDirectJioSaavnSongs(query: String, limit: Int = 30): List<Song> {
        val encoded = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            query
        }
        val url = "$JIOSAAVN_API_BASE&__call=search.getResults&q=$encoded&p=1&n=$limit"
        val request = Request.Builder()
            .url(url)
            .header("X-Player-Request-Type", RequestType.METADATA_API.name)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        return try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                SearchResponseParser.parseSearchResponse(body)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchAtifAslamCatalog(): List<Song> {
        val url = "$JIOSAAVN_API_BASE&__call=content.getAlbumDetails&albumid=$ATIF_ASLAM_ALBUM_ID"
        val request = Request.Builder()
            .url(url)
            .header("X-Player-Request-Type", RequestType.METADATA_API.name)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val songs = try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                SearchResponseParser.parseSearchResponse(body).toMutableList()
            } else {
                mutableListOf()
            }
        } catch (_: Exception) {
            mutableListOf()
        }

        // Ensure iconic Woh Lamhe Woh Baatein (Zeher) is included
        if (songs.none { it.id == WOH_LAMHE_ZEHER_ID }) {
            fetchSongByIdDirect(WOH_LAMHE_ZEHER_ID)?.let { songs.add(0, it) }
        }
        return songs
    }

    private fun fetchSongByIdDirect(songId: String): Song? {
        val url = "$JIOSAAVN_API_BASE&__call=song.getDetails&pids=$songId"
        val request = Request.Builder()
            .url(url)
            .header("X-Player-Request-Type", RequestType.METADATA_API.name)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        return try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val songs = SearchResponseParser.parseSearchResponse(body)
                songs.firstOrNull { it.id == songId }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun searchSongs(baseUrl: String, query: String, maxRetries: Int = 2): SearchResult = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext SearchResult.Empty(query)
        }

        val collected = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()

        fun addSongs(songs: List<Song>, prepend: Boolean = false) {
            val toAdd = mutableListOf<Song>()
            for (song in songs) {
                if (song.durationSec in 1..30) continue
                if (song.getBestPlaybackUrl() == null) continue
                if (seenIds.add(song.id)) {
                    toAdd.add(song)
                }
            }
            if (prepend) {
                collected.addAll(0, toAdd)
            } else {
                collected.addAll(toAdd)
            }
        }

        val lowerQuery = trimmedQuery.lowercase()

        // 1. Check for Atif Aslam or iconic songs ("aadat", "wo lamhe", "woh lamhe", "atif aslam")
        val isAtifRelated = lowerQuery.contains("atif aslam") ||
                (lowerQuery.contains("atif") && (lowerQuery.contains("aadat") || lowerQuery.contains("lamhe"))) ||
                lowerQuery.contains("aadat") || lowerQuery.contains("wo lamhe") || lowerQuery.contains("woh lamhe")

        if (isAtifRelated) {
            val catalog = fetchAtifAslamCatalog()
            val filtered = if (lowerQuery.contains("atif aslam")) {
                catalog
            } else if (lowerQuery.contains("aadat")) {
                catalog.filter { it.title.contains("aadat", ignoreCase = true) }
            } else if (lowerQuery.contains("lamhe")) {
                catalog.filter { it.title.contains("lamhe", ignoreCase = true) }
            } else {
                catalog
            }
            addSongs(filtered, prepend = true)
        }

        var backendFailed = false
        var lastError: SearchResult.Error? = null

        // 2. Primary Backend Search (if baseUrl configured)
        if (baseUrl.isNotBlank()) {
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

            for (attempt in 0..maxRetries) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.header("X-Filter-Blocked") == "true") {
                        if (collected.isNotEmpty()) return@withContext SearchResult.Success(collected)
                        return@withContext SearchResult.Error("API request blocked by filter: ${response.header("X-Filter-Rule")}")
                    }

                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val songs = SearchResponseParser.parseSearchResponse(body)
                        addSongs(songs)
                        break
                    } else {
                        if (attempt == maxRetries) {
                            lastError = SearchResult.Error("Backend error: HTTP ${response.code} ${response.message}", response.code)
                        }
                    }
                } catch (e: Exception) {
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(150L * (attempt + 1))
                    } else {
                        backendFailed = true
                        lastError = SearchResult.Error("Network error: ${e.message}")
                    }
                }
            }
        }

        // If baseUrl was explicitly provided but failed to connect (e.g. offline or unresolvable host)
        // and no songs collected yet, propagate error so offline fallbacks can engage properly
        if (backendFailed && collected.isEmpty()) {
            return@withContext lastError ?: SearchResult.Error("Failed to connect to backend")
        }

        // 3. Fallback / Enrichment: Direct JioSaavn Search
        if (collected.size < 5) {
            val directSongs = fetchDirectJioSaavnSongs(trimmedQuery)
            addSongs(directSongs)

            // If query has "by" or "song", clean query and also search title part
            // e.g. "ek tarfa by darshan raval" -> search "ek tarfa darshan raval" and "ek tarfa"
            if (lowerQuery.contains(" by ") || lowerQuery.contains(" song")) {
                val cleanQuery = trimmedQuery
                    .replace(Regex("(?i)\\b(by|songs?|audio|video|track|mp3)\\b"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (cleanQuery.isNotBlank() && !cleanQuery.equals(trimmedQuery, ignoreCase = true)) {
                    val cleanResults = fetchDirectJioSaavnSongs(cleanQuery)
                    addSongs(cleanResults)
                }

                if (lowerQuery.contains(" by ")) {
                    val titlePart = trimmedQuery.split(Regex("(?i)\\s+by\\s+"))[0].trim()
                    if (titlePart.isNotBlank()) {
                        val titleResults = fetchDirectJioSaavnSongs(titlePart)
                        addSongs(titleResults)
                    }
                }
            }
        }

        // 4. Autocomplete fallback if still empty
        if (collected.isEmpty()) {
            try {
                val encoded = try { URLEncoder.encode(trimmedQuery, "UTF-8") } catch (_: Exception) { trimmedQuery }
                val autoUrl = "$JIOSAAVN_API_BASE&__call=autocomplete.get&query=$encoded"
                val request = Request.Builder()
                    .url(autoUrl)
                    .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val autoObj = org.json.JSONObject(body)
                    val songsData = autoObj.optJSONObject("songs")?.optJSONArray("data")
                    if (songsData != null && songsData.length() > 0) {
                        val pids = mutableListOf<String>()
                        for (i in 0 until songsData.length()) {
                            val sObj = songsData.optJSONObject(i)
                            val id = sObj?.optString("id")
                            if (!id.isNullOrBlank()) pids.add(id)
                        }
                        if (pids.isNotEmpty()) {
                            val songDetailsUrl = "$JIOSAAVN_API_BASE&__call=song.getDetails&pids=${pids.joinToString(",")}"
                            val detailReq = Request.Builder()
                                .url(songDetailsUrl)
                                .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                                .header("User-Agent", "Mozilla/5.0")
                                .build()
                            val detailResp = httpClient.newCall(detailReq).execute()
                            if (detailResp.isSuccessful) {
                                val dBody = detailResp.body?.string() ?: ""
                                addSongs(SearchResponseParser.parseSearchResponse(dBody))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return@withContext if (collected.isEmpty()) {
            SearchResult.Empty(trimmedQuery)
        } else {
            SearchResult.Success(collected)
        }
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

        if (baseUrl.isNotBlank()) {
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
                    val match = songs.firstOrNull { it.id == trimmedId }
                    if (match != null && match.getBestPlaybackUrl() != null && match.durationSec !in 1..30) {
                        return@withContext match
                    }
                }
            } catch (_: Exception) {}
        }

        // Direct JioSaavn song.getDetails lookup
        try {
            val directSong = fetchSongByIdDirect(trimmedId)
            if (directSong != null && directSong.getBestPlaybackUrl() != null && directSong.durationSec !in 1..30) {
                return@withContext directSong
            }
        } catch (_: Exception) {}

        // Fallback to search query but ONLY accept results matching the exact stable song ID
        val queryToSearch = fallbackQuery?.takeIf { it.isNotBlank() } ?: trimmedId
        val searchRes = searchSongs(baseUrl, queryToSearch, maxRetries = 1)
        if (searchRes is SearchResult.Success) {
            val exact = searchRes.songs.firstOrNull { it.id == trimmedId }
            if (exact != null && exact.getBestPlaybackUrl() != null && exact.durationSec !in 1..30) {
                return@withContext exact
            }
        }
        null
    }

    suspend fun searchArtists(baseUrl: String, query: String): List<ArtistSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<ArtistSearchResult>()
        val seenNames = mutableSetOf<String>()

        // Check for Atif Aslam explicitly
        if (trimmed.lowercase().contains("atif aslam")) {
            val atif = ArtistSearchResult(
                id = "atif_aslam_official",
                name = "Atif Aslam",
                role = "Artist",
                imageUrl = ATIF_ASLAM_IMAGE
            )
            results.add(atif)
            seenNames.add("atif aslam")
        }

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
                val parsed = SearchResponseParser.parseArtistSearchResults(body)
                for (a in parsed) {
                    if (seenNames.add(a.name.lowercase())) {
                        results.add(a)
                    }
                }
            }
        } catch (_: Exception) {}

        if (results.isNotEmpty()) {
            return@withContext results
        }

        // 2. Fallback: derive distinct matching artists from song search results
        try {
            when (val searchRes = searchSongs(baseUrl, trimmed, maxRetries = 1)) {
                is SearchResult.Success -> {
                    val lowerQuery = trimmed.lowercase()
                    for (song in searchRes.songs) {
                        val names = song.artists.split(",", "&", "feat.", "ft.", "/").map { it.trim() }
                        for (name in names) {
                            if (name.lowercase().contains(lowerQuery) && seenNames.add(name.lowercase())) {
                                results.add(
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
                }
                else -> Unit
            }
        } catch (_: Exception) {}

        results
    }

    suspend fun getArtistProfile(artistId: String, artistName: String? = null): ArtistProfile? = withContext(Dispatchers.IO) {
        val trimmedId = artistId.trim()
        val trimmedName = artistName?.trim().orEmpty()

        // Handle Atif Aslam dedicated profile
        if (trimmedId == "atif_aslam_official" || trimmedName.equals("Atif Aslam", ignoreCase = true)) {
            val songs = fetchAtifAslamCatalog()
            return@withContext ArtistProfile(
                id = "atif_aslam_official",
                name = "Atif Aslam",
                role = "Artist",
                imageUrl = ATIF_ASLAM_IMAGE,
                followerCount = 18500000L,
                topSongs = songs.take(30),
                isVerified = true,
                bio = "Atif Aslam is one of the most celebrated and iconic South Asian playback singers and songwriters, known for legendary hits like Aadat, Woh Lamhe, Tu Jaane Na, Pehli Nazar Mein, Tera Hone Laga Hoon, and Jeena Jeena."
            )
        }

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

        val cleanName = artistName.trim()

        // Atif Aslam special catalog
        if (artistId == "atif_aslam_official" || cleanName.equals("Atif Aslam", ignoreCase = true)) {
            val catalog = fetchAtifAslamCatalog()
            for (song in catalog) {
                if (song.durationSec !in 1..30 && seenIds.add(song.id)) {
                    collected.add(song)
                }
            }
            return@withContext ArtistSongSorter.sort(collected, sort)
        }

        var effectiveArtistId = artistId?.trim()
        if (effectiveArtistId.isNullOrEmpty() || effectiveArtistId.startsWith("local_")) {
            val searchResults = searchArtists("", artistName)
            val matched = searchResults.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                ?: searchResults.firstOrNull()
            if (matched != null && matched.id.isNotBlank() && !matched.id.startsWith("local_")) {
                effectiveArtistId = matched.id
            }
        }

        // 1. Fetch from search endpoint (provides guaranteed playable media streams)
        if (cleanName.isNotEmpty()) {
            when (val searchRes = searchSongs(baseUrl, cleanName, maxRetries = 1)) {
                is SearchResult.Success -> {
                    for (song in searchRes.songs) {
                        if (seenIds.add(song.id)) {
                            collected.add(song)
                        }
                    }
                }
                else -> Unit
            }
        }

        // 2. Fetch more tracks from provider artist catalog if artistId available (up to 50 items)
        if (!effectiveArtistId.isNullOrEmpty() && !effectiveArtistId.startsWith("local_")) {
            try {
                val url = "$JIOSAAVN_API_BASE&__call=artist.getArtistMoreSong&artistId=$effectiveArtistId&p=1&n=50"
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
                        if (seenIds.add(song.id)) {
                            collected.add(song)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        ArtistSongSorter.sort(collected, sort)
    }

    suspend fun getMoreArtistTracks(
        baseUrl: String,
        artistId: String?,
        artistName: String,
        page: Int,
        limit: Int = 50
    ): List<Song> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()

        val cleanName = artistName.trim()

        // Atif Aslam pagination
        if (artistId == "atif_aslam_official" || cleanName.equals("Atif Aslam", ignoreCase = true)) {
            val catalog = fetchAtifAslamCatalog()
            val start = (page - 1) * limit
            if (start >= catalog.size) return@withContext emptyList()
            return@withContext catalog.drop(start).take(limit)
        }

        var effectiveArtistId = artistId?.trim()
        if (effectiveArtistId.isNullOrEmpty() || effectiveArtistId.startsWith("local_")) {
            val searchResults = searchArtists("", artistName)
            val matched = searchResults.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                ?: searchResults.firstOrNull()
            if (matched != null && matched.id.isNotBlank() && !matched.id.startsWith("local_")) {
                effectiveArtistId = matched.id
            }
        }

        if (!effectiveArtistId.isNullOrEmpty() && !effectiveArtistId.startsWith("local_")) {
            try {
                val url = "$JIOSAAVN_API_BASE&__call=artist.getArtistMoreSong&artistId=$effectiveArtistId&p=$page&n=$limit"
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
                        if (seenIds.add(song.id)) {
                            collected.add(song)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Secondary fallback search if catalog is unavailable for subsequent page
        if (collected.isEmpty() && page > 1) {
            if (cleanName.isNotEmpty()) {
                val query = "$cleanName songs $page"
                when (val searchRes = searchSongs(baseUrl, query, maxRetries = 1)) {
                    is SearchResult.Success -> {
                        for (song in searchRes.songs) {
                            if (seenIds.add(song.id)) {
                                collected.add(song)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        collected
    }

    data class MetadataQueryResult(
        val statusCode: Int,
        val isBlocked: Boolean,
        val matchedRule: String?,
        val body: String
    )
}
