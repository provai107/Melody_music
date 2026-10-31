package com.braveplayer.music.discovery

import android.util.Log
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.model.ReleaseItem
import com.braveplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Manages remote curation overrides for Trending Songs and Latest Releases.
 * Dynamically merges remote configuration from GitHub raw JSON without requiring APK updates.
 */
object RemoteFeedManager {

    private const val TAG = "RemoteFeedManager"
    const val REMOTE_CONFIG_URL =
        "https://raw.githubusercontent.com/provai107/Melody_music/main/config/curated_feed.json"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes cache TTL

    data class CuratedConfig(
        val excludedSongIds: Set<String> = emptySet(),
        val excludedTitles: Set<String> = emptySet(),
        val pinnedTrending: List<CuratedEntry> = emptyList(),
        val pinnedReleases: List<CuratedEntry> = emptyList(),
        val customTracks: List<Song> = emptyList(),
        val trendingQueries: List<String> = emptyList()
    )

    data class CuratedEntry(
        val id: String? = null,
        val searchQuery: String? = null,
        val position: Int = 0
    )

    @Volatile
    private var cachedConfig: CuratedConfig? = null
    @Volatile
    private var lastFetchTimestamp: Long = 0L

    fun getCachedConfig(): CuratedConfig? = cachedConfig

    fun setCachedConfigForTesting(config: CuratedConfig?) {
        cachedConfig = config
        lastFetchTimestamp = if (config != null) System.currentTimeMillis() else 0L
    }

    /**
     * Fetches the latest remote configuration, with 15-minute in-memory caching.
     */
    suspend fun fetchConfig(httpClient: OkHttpClient, forceRefresh: Boolean = false): CuratedConfig =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh && cachedConfig != null && (now - lastFetchTimestamp < CACHE_TTL_MS)) {
                return@withContext cachedConfig!!
            }

            try {
                val request = Request.Builder()
                    .url(REMOTE_CONFIG_URL)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val config = parseConfig(body)
                    cachedConfig = config
                    lastFetchTimestamp = now
                    return@withContext config
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch remote curated feed config, using fallback", e)
            }

            cachedConfig ?: CuratedConfig()
        }

    fun parseConfig(jsonString: String): CuratedConfig {
        return try {
            val root = JSONObject(jsonString)

            val excludedIds = mutableSetOf<String>()
            val excludedArr = root.optJSONArray("excluded_song_ids")
            if (excludedArr != null) {
                for (i in 0 until excludedArr.length()) {
                    val id = excludedArr.optString(i, "").trim()
                    if (id.isNotEmpty()) excludedIds.add(id)
                }
            }

            val excludedTitles = mutableSetOf<String>()
            val excludedTitlesArr = root.optJSONArray("excluded_titles")
            if (excludedTitlesArr != null) {
                for (i in 0 until excludedTitlesArr.length()) {
                    val t = excludedTitlesArr.optString(i, "").trim().lowercase()
                    if (t.isNotEmpty()) excludedTitles.add(t)
                }
            }

            fun parseEntries(key: String): List<CuratedEntry> {
                val list = mutableListOf<CuratedEntry>()
                val arr = root.optJSONArray(key) ?: return list
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").takeIf { it.isNotBlank() }
                    val query = item.optString("search_query", item.optString("query", "")).takeIf { it.isNotBlank() }
                    val pos = item.optInt("position", item.optInt("pinned_position", i))
                    if (id != null || query != null) {
                        list.add(CuratedEntry(id = id, searchQuery = query, position = pos))
                    }
                }
                return list
            }

            val customTracks = mutableListOf<Song>()
            val customArr = root.optJSONArray("custom_tracks")
            if (customArr != null) {
                for (i in 0 until customArr.length()) {
                    val item = customArr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "custom_${System.currentTimeMillis()}_$i")
                    val title = item.optString("title", "Custom Track")
                    val artists = item.optString("artists", item.optString("artist", "Featured Artist"))
                    val album = item.optString("album", "Special Release")
                    val imageUrl = item.optString("imageUrl", item.optString("image", "")).ifBlank { null }
                    val mediaUrl = item.optString("mediaUrl", item.optString("audio_url", ""))
                    val duration = item.optInt("duration", item.optInt("durationSec", 180))
                    val year = item.optString("year", "2026")
                    if (mediaUrl.isNotBlank()) {
                        customTracks.add(
                            Song(
                                id = id,
                                title = title,
                                artists = artists,
                                album = album,
                                imageUrl = imageUrl,
                                durationSec = duration,
                                mediaUrl = mediaUrl,
                                mediaUrlDefault = mediaUrl,
                                previewUrl = null,
                                year = year,
                                releaseDate = "2026-01-01"
                            )
                        )
                    }
                }
            }

            val trendingQueries = mutableListOf<String>()
            val queriesArr = root.optJSONArray("trending_queries")
            if (queriesArr != null) {
                for (i in 0 until queriesArr.length()) {
                    val q = queriesArr.optString(i, "").trim()
                    if (q.isNotEmpty()) trendingQueries.add(q)
                }
            }

            CuratedConfig(
                excludedSongIds = excludedIds,
                excludedTitles = excludedTitles,
                pinnedTrending = parseEntries("pinned_trending") + parseEntries("extra_songs"),
                pinnedReleases = parseEntries("pinned_releases"),
                customTracks = customTracks,
                trendingQueries = trendingQueries
            )
        } catch (_: Exception) {
            CuratedConfig()
        }
    }

    /**
     * Applies remote curation rules to trending songs:
     * 1. Filters out excluded songs.
     * 2. Injects custom tracks and pinned songs (resolved by ID or query).
     */
    suspend fun applyTrendingOverrides(
        baseSongs: List<Song>,
        httpClient: OkHttpClient,
        metadataService: MusicMetadataService
    ): List<Song> = withContext(Dispatchers.IO) {
        val config = fetchConfig(httpClient)
        if (config.excludedSongIds.isEmpty() && config.excludedTitles.isEmpty() &&
            config.pinnedTrending.isEmpty() && config.customTracks.isEmpty()
        ) {
            return@withContext baseSongs
        }

        val filtered = baseSongs.filterNot { song ->
            config.excludedSongIds.contains(song.id) ||
                config.excludedTitles.contains(song.title.lowercase().trim())
        }.toMutableList()

        val seenIds = filtered.map { it.id }.toMutableSet()
        val toPrepend = mutableListOf<Song>()

        for (custom in config.customTracks) {
            if (seenIds.add(custom.id)) {
                toPrepend.add(custom)
            }
        }

        for (entry in config.pinnedTrending) {
            val resolvedSong: Song? = if (!entry.id.isNullOrBlank()) {
                metadataService.fetchSongById(entry.id)
            } else if (!entry.searchQuery.isNullOrBlank()) {
                metadataService.fetchDirectJioSaavnSongs(entry.searchQuery, limit = 1).firstOrNull()
            } else {
                null
            }

            if (resolvedSong != null && seenIds.add(resolvedSong.id)) {
                val insertPos = entry.position.coerceIn(0, toPrepend.size)
                toPrepend.add(insertPos, resolvedSong)
            }
        }

        (toPrepend + filtered).distinctBy { it.id }
    }

    /**
     * Applies remote curation rules to latest release items:
     * 1. Filters out excluded items.
     * 2. Injects custom tracks and pinned releases.
     */
    suspend fun applyReleaseOverrides(
        baseReleases: List<ReleaseItem>,
        httpClient: OkHttpClient,
        metadataService: MusicMetadataService,
        createReleaseItemFn: (Song) -> ReleaseItem
    ): List<ReleaseItem> = withContext(Dispatchers.IO) {
        val config = fetchConfig(httpClient)
        if (config.excludedSongIds.isEmpty() && config.excludedTitles.isEmpty() &&
            config.pinnedReleases.isEmpty() && config.customTracks.isEmpty()
        ) {
            return@withContext baseReleases
        }

        val filtered = baseReleases.filterNot { item ->
            config.excludedSongIds.contains(item.id) ||
                config.excludedSongIds.contains(item.song.id) ||
                config.excludedTitles.contains(item.title.lowercase().trim())
        }.toMutableList()

        val seenIds = filtered.map { it.id }.toMutableSet()
        val toPrepend = mutableListOf<ReleaseItem>()

        for (custom in config.customTracks) {
            if (seenIds.add(custom.id)) {
                toPrepend.add(createReleaseItemFn(custom))
            }
        }

        for (entry in config.pinnedReleases) {
            val resolvedSong: Song? = if (!entry.id.isNullOrBlank()) {
                metadataService.fetchSongById(entry.id)
            } else if (!entry.searchQuery.isNullOrBlank()) {
                metadataService.fetchDirectJioSaavnSongs(entry.searchQuery, limit = 1).firstOrNull()
            } else {
                null
            }

            if (resolvedSong != null && seenIds.add(resolvedSong.id)) {
                toPrepend.add(createReleaseItemFn(resolvedSong))
            }
        }

        (toPrepend + filtered).distinctBy { it.id }
    }
}
