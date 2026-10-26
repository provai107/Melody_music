package com.braveplayer.music.lyrics

import com.braveplayer.filter.model.RequestType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

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
 * Service for fetching and formatting song lyrics from the JioSaavn API backend.
 */
class LyricsService(
    private val httpClient: OkHttpClient
) {
    suspend fun fetchLyrics(baseUrl: String, songId: String): LyricsResult = withContext(Dispatchers.IO) {
        val trimmedId = songId.trim()
        if (trimmedId.isEmpty()) {
            return@withContext LyricsResult.Unavailable("Invalid song ID")
        }

        val url = "${baseUrl.trimEnd('/')}/lyrics/$trimmedId"
        val request = Request.Builder()
            .url(url)
            .header("X-Player-Request-Type", RequestType.METADATA_API.name)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.header("X-Filter-Blocked") == "true") {
                return@withContext LyricsResult.Error("Request blocked by filter")
            }

            if (!response.isSuccessful) {
                return@withContext LyricsResult.Unavailable("Lyrics not available for this track")
            }

            val body = response.body?.string().orEmpty()
            parseLyricsResponse(body)
        } catch (e: IOException) {
            LyricsResult.Error("Network error: ${e.message ?: "Failed to connect to backend"}")
        } catch (e: Exception) {
            LyricsResult.Error("Unexpected error loading lyrics: ${e.message}")
        }
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
