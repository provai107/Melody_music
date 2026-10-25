package com.braveplayer.music.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for FastAPI JioSaavn API responses.
 * Handles single-song results, query results arrays, empty responses, and error payloads.
 */
object SearchResponseParser {

    fun parseSearchResponse(jsonString: String): List<Song> {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty() || trimmed == "[]" || trimmed == "{}") {
            return emptyList()
        }

        val resultsList = mutableListOf<Song>()

        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        parseSongObject(obj)?.let { resultsList.add(it) }
                    }
                }
                return resultsList
            }

            val root = JSONObject(trimmed)

            // Check if response indicates an error
            if (root.has("error")) {
                return emptyList()
            }

            if (root.has("result")) {
                val resultField = root.get("result")
                when (resultField) {
                    is JSONArray -> {
                        for (i in 0 until resultField.length()) {
                            val obj = resultField.optJSONObject(i)
                            if (obj != null) {
                                parseSongObject(obj)?.let { resultsList.add(it) }
                            }
                        }
                    }
                    is JSONObject -> {
                        // Could be single song result or album details
                        parseSongObject(resultField)?.let { resultsList.add(it) }
                    }
                }
            } else if (root.has("id") && (root.has("title") || root.has("song"))) {
                // Root is a single song object
                parseSongObject(root)?.let { resultsList.add(it) }
            }
        } catch (_: Exception) {
            // Malformed JSON returns empty list
            return emptyList()
        }

        return resultsList
    }

    private fun parseSongObject(json: JSONObject): Song? {
        val id = json.optString("id", "")
        if (id.isEmpty()) return null

        val rawTitle = json.optString("title", json.optString("song", "Unknown Title"))
        val title = cleanHtmlEntities(rawTitle)

        val rawAlbum = json.optString("album", "Unknown Album")
        val album = cleanHtmlEntities(rawAlbum)

        // Artists extraction
        val artists = extractArtists(json)

        val imageUrl = json.optString("image", "").ifBlank { null }
        val durationSec = json.optInt("duration", 0)

        val mediaUrl = json.optString("media_url", "").ifBlank { null }
        val mediaUrlDefault = json.optString("media_url_default", "").ifBlank { null }
        val previewUrl = json.optString("vlink", "").ifBlank { null }

        return Song(
            id = id,
            title = title,
            artists = artists,
            album = album,
            imageUrl = imageUrl,
            durationSec = durationSec,
            mediaUrl = mediaUrl,
            mediaUrlDefault = mediaUrlDefault,
            previewUrl = previewUrl
        )
    }

    private fun extractArtists(json: JSONObject): String {
        // First try artistMap.primary_artists
        if (json.has("artistMap")) {
            val map = json.optJSONObject("artistMap")
            if (map != null && map.has("primary_artists")) {
                val primaryArray = map.optJSONArray("primary_artists")
                if (primaryArray != null && primaryArray.length() > 0) {
                    val names = mutableListOf<String>()
                    for (i in 0 until primaryArray.length()) {
                        val artistObj = primaryArray.optJSONObject(i)
                        val name = artistObj?.optString("name")
                        if (!name.isNullOrBlank()) {
                            names.add(cleanHtmlEntities(name))
                        }
                    }
                    if (names.isNotEmpty()) {
                        return names.joinToString(", ")
                    }
                }
            }
        }

        // Fallback to subtitle or music
        val subtitle = json.optString("subtitle", "")
        if (subtitle.isNotBlank()) return cleanHtmlEntities(subtitle)

        val music = json.optString("music", "")
        if (music.isNotBlank()) return cleanHtmlEntities(music)

        return "Unknown Artist"
    }

    fun cleanHtmlEntities(text: String): String {
        return text
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }
}
