package com.braveplayer.music.api

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.model.RequestType
import com.braveplayer.filter.network.FilteringOkHttpInterceptor
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

    data class MetadataQueryResult(
        val statusCode: Int,
        val isBlocked: Boolean,
        val matchedRule: String?,
        val body: String
    )
}
