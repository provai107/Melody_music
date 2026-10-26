package com.braveplayer.music.lyrics

import com.braveplayer.filter.model.RequestType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

sealed class LyricsResult {
    data class Success(
        val lyrics: String,
        val copyright: String?,
        val snippet: String?
    ) : LyricsResult()

    data class Unavailable(val message: String) : LyricsResult()
    data class Error(val message: String) : LyricsResult()
}

/**
 * Service for fetching and formatting song lyrics from:
 * 1. Primary: Configured JioSaavn backend API
 * 2. Fallback: LRCLIB open public community lyrics database
 */
class LyricsService(
    private val httpClient: OkHttpClient
) {
    suspend fun fetchLyrics(
        baseUrl: String,
        songId: String,
        title: String = "",
        artist: String = ""
    ): LyricsResult = withContext(Dispatchers.IO) {
        // 1. Try JioSaavn backend if songId is provided
        val trimmedId = songId.trim()
        if (trimmedId.isNotEmpty()) {
            val jioResult = fetchFromJioSaavn(baseUrl, trimmedId)
            if (jioResult is LyricsResult.Success) {
                return@withContext jioResult
            }
        }

        // 2. Fallback to LRCLIB if title is present
        if (title.isNotBlank()) {
            val lrclibResult = fetchFromLrclib(title, artist)
            if (lrclibResult is LyricsResult.Success) {
                return@withContext lrclibResult
            }
        }

        LyricsResult.Unavailable("Lyrics are not available for this track.")
    }

    private fun fetchFromJioSaavn(baseUrl: String, songId: String): LyricsResult {
        val url = "${baseUrl.trimEnd('/')}/lyrics/$songId"
        val request = Request.Builder()
            .url(url)
            .header("X-Player-Request-Type", RequestType.METADATA_API.name)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (response.header("X-Filter-Blocked") == "true") {
                return LyricsResult.Error("Request blocked by filter")
            }
            if (!response.isSuccessful) {
                return LyricsResult.Unavailable("JioSaavn lyrics unavailable")
            }
            val body = response.body?.string().orEmpty()
            parseLyricsResponse(body)
        } catch (e: Exception) {
            LyricsResult.Unavailable("Failed to connect to backend: ${e.message}")
        }
    }

    private fun fetchFromLrclib(title: String, artist: String): LyricsResult {
        val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
        val primaryArtist = artist.split(",").firstOrNull()?.trim().orEmpty()

        // Attempt 1: Exact match via /api/get
        val exactLyrics = queryLrclibGet(cleanTitle, primaryArtist)
        if (exactLyrics != null) {
            return LyricsResult.Success(
                lyrics = exactLyrics,
                copyright = "Lyrics provided by LRCLIB",
                snippet = null
            )
        }

        // Attempt 2: Search with title and artist
        val searchWithArtist = "$cleanTitle $primaryArtist".trim()
        val searchResult1 = queryLrclibSearch(searchWithArtist)
        if (searchResult1 != null) {
            return LyricsResult.Success(
                lyrics = searchResult1,
                copyright = "Lyrics provided by LRCLIB",
                snippet = null
            )
        }

        // Attempt 3: Search with clean title only
        if (cleanTitle.isNotBlank()) {
            val searchResult2 = queryLrclibSearch(cleanTitle)
            if (searchResult2 != null) {
                return LyricsResult.Success(
                    lyrics = searchResult2,
                    copyright = "Lyrics provided by LRCLIB",
                    snippet = null
                )
            }
        }

        return LyricsResult.Unavailable("Lyrics are not available for this track.")
    }

    private fun queryLrclibGet(title: String, artist: String): String? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BraveMusicPlayer/1.0 (https://github.com/braveplayer/music)")
                .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            extractLyricsFromLrclibJson(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryLrclibSearch(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BraveMusicPlayer/1.0 (https://github.com/braveplayer/music)")
                .header("X-Player-Request-Type", RequestType.METADATA_API.name)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()

            val array = JSONArray(body)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val plain = item.optString("plainLyrics", "")
                if (plain.isNotBlank()) return plain.trim()
                val synced = item.optString("syncedLyrics", "")
                if (synced.isNotBlank()) {
                    val stripped = stripLrcTimestamps(synced)
                    if (stripped.isNotBlank()) return stripped
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun extractLyricsFromLrclibJson(jsonString: String): String? {
        return try {
            val json = JSONObject(jsonString)
            val plain = json.optString("plainLyrics", "")
            if (plain.isNotBlank()) {
                plain.trim()
            } else {
                val synced = json.optString("syncedLyrics", "")
                if (synced.isNotBlank()) {
                    stripLrcTimestamps(synced).takeIf { it.isNotBlank() }
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun stripLrcTimestamps(lrc: String): String {
        return lrc.lines()
            .map { it.replace(Regex("\\[\\d+:\\d+(\\.\\d+)?\\]"), "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun parseLyricsResponse(jsonString: String): LyricsResult {
        if (jsonString.isBlank()) {
            return LyricsResult.Unavailable("Lyrics not available for this track")
        }

        return try {
            val json = JSONObject(jsonString)

            if (json.optString("status") == "failure") {
                return LyricsResult.Unavailable("Lyrics not available for this track")
            }

            val rawLyrics = json.optString("lyrics", "")
            if (rawLyrics.isBlank()) {
                return LyricsResult.Unavailable("Lyrics not available for this track")
            }

            val formatted = cleanLyricsHtml(rawLyrics)
            if (formatted.isBlank()) {
                return LyricsResult.Unavailable("Lyrics not available for this track")
            }

            val copyright = json.optString("lyrics_copyright", "").takeIf { it.isNotBlank() }
            val snippet = json.optString("snippet", "").takeIf { it.isNotBlank() }

            LyricsResult.Success(
                lyrics = formatted,
                copyright = copyright,
                snippet = snippet
            )
        } catch (e: Exception) {
            LyricsResult.Error("Failed to parse lyrics response: ${e.message}")
        }
    }

    private fun cleanLyricsHtml(raw: String): String {
        val withNewlines = raw.replace("(?i)<br\\s*/?>".toRegex(), "\n")
        return withNewlines
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}
