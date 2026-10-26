package com.braveplayer.music.lyrics

import com.braveplayer.filter.model.RequestType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class LyricLine(
    val timeMs: Long,
    val text: String
)

sealed class LyricsResult {
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val copyright: String?,
        val snippet: String?
    ) : LyricsResult()

    data class Unavailable(val message: String) : LyricsResult()
    data class Error(val message: String) : LyricsResult()
}

/**
 * Service for fetching and formatting time-synchronized and plain song lyrics.
 * Prioritizes LRCLIB for timestamp-synchronized lyrics (Karaoke / live tracking)
 * and falls back to the configured JioSaavn backend for static lyrics.
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
        // 1. Try LRCLIB first if title is available to obtain time-synced lyrics
        if (title.isNotBlank()) {
            val lrclibResult = fetchFromLrclib(title, artist)
            if (lrclibResult is LyricsResult.Success) {
                return@withContext lrclibResult
            }
        }

        // 2. Fallback to configured JioSaavn backend
        val trimmedId = songId.trim()
        if (trimmedId.isNotEmpty()) {
            val jioResult = fetchFromJioSaavn(baseUrl, trimmedId)
            if (jioResult is LyricsResult.Success) {
                return@withContext jioResult
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
        val exactResult = queryLrclibGet(cleanTitle, primaryArtist)
        if (exactResult != null) {
            return exactResult
        }

        // Attempt 2: Search with title and artist
        val searchWithArtist = "$cleanTitle $primaryArtist".trim()
        val searchResult1 = queryLrclibSearch(searchWithArtist)
        if (searchResult1 != null) {
            return searchResult1
        }

        // Attempt 3: Search with clean title only
        if (cleanTitle.isNotBlank()) {
            val searchResult2 = queryLrclibSearch(cleanTitle)
            if (searchResult2 != null) {
                return searchResult2
            }
        }

        return LyricsResult.Unavailable("Lyrics are not available for this track.")
    }

    private fun queryLrclibGet(title: String, artist: String): LyricsResult.Success? {
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

    private fun queryLrclibSearch(query: String): LyricsResult.Success? {
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
                val res = extractLyricsFromLrclibJsonObject(item)
                if (res != null) return res
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun extractLyricsFromLrclibJson(jsonString: String): LyricsResult.Success? {
        return try {
            val json = JSONObject(jsonString)
            extractLyricsFromLrclibJsonObject(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractLyricsFromLrclibJsonObject(json: JSONObject): LyricsResult.Success? {
        val synced = json.optString("syncedLyrics", "").trim()
        if (synced.isNotEmpty()) {
            val parsedLines = parseLrcLines(synced)
            if (parsedLines.isNotEmpty()) {
                return LyricsResult.Success(
                    lines = parsedLines,
                    isSynced = true,
                    copyright = "Synchronized lyrics powered by LRCLIB",
                    snippet = null
                )
            }
        }

        val plain = json.optString("plainLyrics", "").trim()
        if (plain.isNotEmpty()) {
            val plainLines = parsePlainLines(plain)
            if (plainLines.isNotEmpty()) {
                return LyricsResult.Success(
                    lines = plainLines,
                    isSynced = false,
                    copyright = "Lyrics powered by LRCLIB",
                    snippet = null
                )
            }
        }

        return null
    }

    fun parseLrcLines(lrcText: String): List<LyricLine> {
        val regex = Regex("""^\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]\s*(.*)$""")
        val result = mutableListOf<LyricLine>()
        for (rawLine in lrcText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val match = regex.matchEntire(trimmed) ?: continue
            val mins = match.groupValues[1].toLongOrNull() ?: 0L
            val secs = match.groupValues[2].toLongOrNull() ?: 0L
            val msStr = match.groupValues[3].ifEmpty { "0" }
            val ms = when (msStr.length) {
                1 -> (msStr.toLongOrNull() ?: 0L) * 100L
                2 -> (msStr.toLongOrNull() ?: 0L) * 10L
                else -> msStr.take(3).toLongOrNull() ?: 0L
            }
            val totalMs = (mins * 60L + secs) * 1000L + ms
            val text = match.groupValues[4].trim()
            if (text.isNotEmpty()) {
                result.add(LyricLine(timeMs = totalMs, text = text))
            }
        }
        return result
    }

    fun parsePlainLines(plainText: String): List<LyricLine> {
        return plainText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(timeMs = -1L, text = it) }
    }

    fun getActiveLineIndex(lines: List<LyricLine>, currentPositionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var active = -1
        for (i in lines.indices) {
            val lineTime = lines[i].timeMs
            if (lineTime in 0..currentPositionMs) {
                active = i
            } else if (lineTime > currentPositionMs) {
                break
            }
        }
        return active
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

            val lines = parsePlainLines(formatted)
            val copyright = json.optString("lyrics_copyright", "").takeIf { it.isNotBlank() }
            val snippet = json.optString("snippet", "").takeIf { it.isNotBlank() }

            LyricsResult.Success(
                lines = lines,
                isSynced = false,
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
