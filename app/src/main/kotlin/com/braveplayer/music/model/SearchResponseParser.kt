package com.braveplayer.music.model

import org.json.JSONArray
import org.json.JSONObject

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Parser for FastAPI JioSaavn API responses.
 * Handles single-song results, query results arrays, empty responses, and error payloads.
 */
object SearchResponseParser {

    private const val DES_KEY = "38346591"

    /**
     * Decrypts encrypted media URLs returned by JioSaavn API into high-quality 320kbps
     * and standard 160kbps AAC media stream URLs.
     */
    fun decryptMediaUrl(encryptedUrl: String): Pair<String?, String?> {
        val trimmed = encryptedUrl.trim()
        if (trimmed.isEmpty()) return Pair(null, null)
        return try {
            val keyBytes = DES_KEY.toByteArray(Charsets.UTF_8)
            val keySpec = SecretKeySpec(keyBytes, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = try {
                android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(trimmed)
            }
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val decryptedUrl = String(decryptedBytes, Charsets.UTF_8).trim()
            if (decryptedUrl.isNotEmpty() && (decryptedUrl.startsWith("http://") || decryptedUrl.startsWith("https://"))) {
                val url320 = decryptedUrl.replace("_96.mp4", "_320.mp4").replace("_96.mp3", "_320.mp4")
                val url160 = decryptedUrl.replace("_96.mp4", "_160.mp4").replace("_96.mp3", "_160.mp4")
                Pair(url320, url160)
            } else {
                Pair(null, null)
            }
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

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

            // Find array in any known field: "result", "results", "songs", "data", "list"
            val array = root.optJSONArray("result")
                ?: root.optJSONArray("results")
                ?: root.optJSONArray("songs")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("list")
                ?: root.optJSONObject("data")?.optJSONArray("songs")
                ?: root.optJSONObject("data")?.optJSONArray("results")

            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        parseSongObject(obj)?.let { resultsList.add(it) }
                    }
                }
                return resultsList
            }

            if (root.has("result")) {
                val resultObj = root.optJSONObject("result")
                if (resultObj != null) {
                    parseSongObject(resultObj)?.let { resultsList.add(it) }
                    return resultsList
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

        val moreInfo = json.optJSONObject("more_info")
        val durationSec = json.optInt("duration", 0).let { if (it > 0) it else moreInfo?.optInt("duration", 0) ?: 0 }

        // Strictly eliminate 15-second teaser / caller tune clips (<= 30 seconds)
        if (durationSec in 1..30) {
            return null
        }

        // Filter out ringtones, teasers, status clips, jiotune previews
        val lowerTitle = rawTitle.lowercase()
        val lowerAlbum = rawAlbum.lowercase()
        val isShortClipOrRingtone = listOf("ringtone", "jiotune", "teaser", "snippet", "status clip", "15 sec", "15sec", "15s clip", "caller tune")
            .any { lowerTitle.contains(it) || lowerAlbum.contains(it) }
        if (isShortClipOrRingtone) {
            return null
        }

        // Artists extraction
        val artists = extractArtists(json)

        val year = json.optString("year", "").ifBlank {
            moreInfo?.optString("year", "")?.ifBlank { null }
        }
        val releaseDate = json.optString("release_date", "").ifBlank {
            moreInfo?.optString("release_date", "")?.ifBlank { null }
        }
        val rawPlayCount = json.opt("play_count") ?: moreInfo?.opt("play_count")
        val playCount = when (rawPlayCount) {
            is Number -> rawPlayCount.toLong()
            is String -> rawPlayCount.trim().toLongOrNull()
            else -> null
        }

        val imageUrl = upgradeArtworkResolution(json.optString("image", "").ifBlank { null })

        var mediaUrl = json.optString("media_url", "").ifBlank {
            moreInfo?.optString("media_url", "")?.ifBlank { null }
        }
        var mediaUrlDefault = json.optString("media_url_default", "").ifBlank {
            moreInfo?.optString("media_url_default", "")?.ifBlank { null }
        }

        // Decrypt encrypted_media_url if direct media_url is missing or blank
        if (mediaUrl.isNullOrBlank() || mediaUrlDefault.isNullOrBlank()) {
            val encUrl = json.optString("encrypted_media_url", "").ifBlank {
                moreInfo?.optString("encrypted_media_url", "")?.ifBlank { null }
            }
            if (!encUrl.isNullOrBlank()) {
                val (dec320, dec160) = decryptMediaUrl(encUrl)
                if (mediaUrl.isNullOrBlank()) mediaUrl = dec320
                if (mediaUrlDefault.isNullOrBlank()) mediaUrlDefault = dec160
            }
        }

        // Upgrade .mp3 to .mp4 container for high-bitrate Saavn CDN delivery
        if (mediaUrl?.contains("saavncdn.com") == true && mediaUrl.endsWith(".mp3")) {
            mediaUrl = mediaUrl.replace(".mp3", ".mp4")
        }
        if (mediaUrlDefault?.contains("saavncdn.com") == true && mediaUrlDefault.endsWith(".mp3")) {
            mediaUrlDefault = mediaUrlDefault.replace(".mp3", ".mp4")
        }

        val previewUrl = json.optString("vlink", "").ifBlank {
            moreInfo?.optString("vlink", "")?.ifBlank { null }
        }

        return Song(
            id = id,
            title = title,
            artists = artists,
            album = album,
            imageUrl = imageUrl,
            durationSec = durationSec,
            mediaUrl = mediaUrl,
            mediaUrlDefault = mediaUrlDefault,
            previewUrl = previewUrl,
            releaseDate = releaseDate,
            playCount = playCount,
            year = year
        )
    }

    private fun extractArtists(json: JSONObject): String {
        val moreInfo = json.optJSONObject("more_info")
        val map = json.optJSONObject("artistMap") ?: moreInfo?.optJSONObject("artistMap")
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

        val music = json.optString("music", "").ifBlank {
            moreInfo?.optString("music", "") ?: ""
        }
        if (music.isNotBlank()) return cleanHtmlEntities(music)

        // Fallback to subtitle
        val subtitle = json.optString("subtitle", "").ifBlank {
            moreInfo?.optString("subtitle", "") ?: ""
        }
        if (subtitle.isNotBlank()) {
            val cleanSub = cleanHtmlEntities(subtitle)
            val hyphenIdx = cleanSub.indexOf(" - ")
            return if (hyphenIdx > 0) cleanSub.substring(0, hyphenIdx).trim() else cleanSub
        }

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

    fun isJioSaavnPlaceholder(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        return lower.contains("artist-default") ||
               lower.contains("album-default") ||
               lower.contains("playlist-default") ||
               lower.contains("default-music") ||
               lower.contains("default-film") ||
               lower.contains("default_artist") ||
               lower.contains("default_album") ||
               lower.contains("default_film") ||
               lower.contains("default-avatar") ||
               lower.contains("default_avatar") ||
               lower.contains("default-track") ||
               lower.contains("default_track") ||
               lower.contains("default-song") ||
               lower.contains("default_song") ||
               lower.contains("default-actor") ||
               lower.contains("default_actor") ||
               lower.contains("default-star") ||
               lower.contains("default_star") ||
               lower.contains("/_i/") ||
               lower.contains("default.png") ||
               lower.contains("default.jpg") ||
               lower.contains("default.jpeg") ||
               lower.contains("default.webp") ||
               lower.contains("no-image") ||
               lower.contains("no_image") ||
               lower.contains("empty-art") ||
               lower.contains("empty_art") ||
               lower.contains("placeholder")
    }

    fun upgradeArtworkResolution(url: String?): String? {
        if (url.isNullOrBlank() || isJioSaavnPlaceholder(url)) return null
        return url.replace("50x50", "500x500")
            .replace("150x150", "500x500")
    }

    /**
     * Parses search.getArtistResults responses into ArtistSearchResult models.
     */
    fun parseArtistSearchResults(jsonString: String): List<ArtistSearchResult> {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty() || trimmed == "[]" || trimmed == "{}") return emptyList()

        val results = mutableListOf<ArtistSearchResult>()
        try {
            val root = JSONObject(trimmed)
            val array = root.optJSONArray("results") ?: root.optJSONArray("data")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id", obj.optString("artistId", "")).trim()
                    val name = cleanHtmlEntities(obj.optString("name", obj.optString("title", "")))
                    if (id.isEmpty() || name.isEmpty()) continue

                    val role = obj.optString("role", "Artist").ifBlank { "Artist" }
                    val image = upgradeArtworkResolution(obj.optString("image", "").ifBlank { null })
                    val listeners = obj.optString("ctr", "").takeIf { it.isNotBlank() }

                    results.add(
                        ArtistSearchResult(
                            id = id,
                            name = name,
                            role = role,
                            imageUrl = image,
                            listeners = listeners
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    /**
     * Parses artist.getArtistPageDetails response into a rich ArtistProfile.
     */
    fun parseArtistDetails(jsonString: String): ArtistProfile? {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty() || trimmed == "[]" || trimmed == "{}") return null

        try {
            val root = JSONObject(trimmed)
            if (root.has("error")) return null

            val id = root.optString("artistId", root.optString("id", "")).trim()
            val name = cleanHtmlEntities(root.optString("name", ""))
            if (id.isEmpty() && name.isEmpty()) return null

            val image = upgradeArtworkResolution(root.optString("image", "").ifBlank { null })
            val rawFollowers = root.opt("follower_count")
            val followerCount = when (rawFollowers) {
                is Number -> rawFollowers.toLong()
                is String -> rawFollowers.trim().toLongOrNull()
                else -> null
            }
            val rawFans = root.opt("fan_count")
            val fanCount = when (rawFans) {
                is Number -> rawFans.toLong()
                is String -> rawFans.trim().toLongOrNull()
                else -> null
            }
            val isVerified = root.optBoolean("isVerified", false)
            val subtitle = cleanHtmlEntities(root.optString("subtitle", "")).ifBlank { null }

            // Parse Bio safely
            val bio = parseBioField(root.opt("bio"))

            // Parse Top Songs
            val topSongsList = mutableListOf<Song>()
            val topSongsArray = root.optJSONArray("topSongs")
            if (topSongsArray != null) {
                for (i in 0 until topSongsArray.length()) {
                    val songObj = topSongsArray.optJSONObject(i)
                    if (songObj != null) {
                        parseSongObject(songObj)?.let { topSongsList.add(it) }
                    }
                }
            }

            // Parse Top Albums
            val albumsList = mutableListOf<ArtistAlbumItem>()
            val topAlbumsArray = root.optJSONArray("topAlbums")
            if (topAlbumsArray != null) {
                for (i in 0 until topAlbumsArray.length()) {
                    val albumObj = topAlbumsArray.optJSONObject(i) ?: continue
                    val albumId = albumObj.optString("id", albumObj.optString("albumid", "")).trim()
                    val albumTitle = cleanHtmlEntities(albumObj.optString("title", albumObj.optString("name", "")))
                    if (albumId.isNotBlank() && albumTitle.isNotBlank()) {
                        albumsList.add(
                            ArtistAlbumItem(
                                id = albumId,
                                title = albumTitle,
                                year = albumObj.optString("year", "").ifBlank { null },
                                imageUrl = upgradeArtworkResolution(albumObj.optString("image", "").ifBlank { null })
                            )
                        )
                    }
                }
            }

            // Parse Similar Artists
            val similarList = mutableListOf<ArtistProfile>()
            val similarArray = root.optJSONArray("similarArtists")
            if (similarArray != null) {
                for (i in 0 until similarArray.length()) {
                    val simObj = similarArray.optJSONObject(i) ?: continue
                    val simId = simObj.optString("id", simObj.optString("artistId", "")).trim()
                    val simName = cleanHtmlEntities(simObj.optString("name", ""))
                    if (simId.isNotBlank() && simName.isNotBlank()) {
                        similarList.add(
                            ArtistProfile(
                                id = simId,
                                name = simName,
                                role = simObj.optString("role", "Artist").ifBlank { "Artist" },
                                imageUrl = upgradeArtworkResolution(simObj.optString("image", "").ifBlank { null })
                            )
                        )
                    }
                }
            }

            return ArtistProfile(
                id = id,
                name = name,
                imageUrl = image,
                followerCount = followerCount,
                fanCount = fanCount,
                isVerified = isVerified,
                bio = bio,
                subtitle = subtitle,
                topSongs = topSongsList,
                topAlbums = albumsList,
                similarArtists = similarList
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Parses artist.getArtistMoreSong or similar endpoint into playable Songs.
     */
    fun parseArtistMoreSongs(jsonString: String): List<Song> {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty() || trimmed == "[]" || trimmed == "{}") return emptyList()

        val results = mutableListOf<Song>()
        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val songObj = array.optJSONObject(i)
                    if (songObj != null) {
                        parseSongObject(songObj)?.let { results.add(it) }
                    }
                }
                return results
            }

            val root = JSONObject(trimmed)
            val topSongsObj = root.optJSONObject("topSongs")
            val dataObj = root.optJSONObject("data")
            val array = topSongsObj?.optJSONArray("songs")
                ?: topSongsObj?.optJSONArray("results")
                ?: root.optJSONArray("topSongs")
                ?: root.optJSONArray("songs")
                ?: root.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: dataObj?.optJSONArray("songs")
                ?: dataObj?.optJSONArray("results")

            if (array != null) {
                for (i in 0 until array.length()) {
                    val songObj = array.optJSONObject(i)
                    if (songObj != null) {
                        parseSongObject(songObj)?.let { results.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun parseBioField(rawBio: Any?): String? {
        if (rawBio == null) return null
        val str = rawBio.toString().trim()
        if (str.isEmpty() || str == "null") return null

        if (str.startsWith("[")) {
            try {
                val array = JSONArray(str)
                val paragraphs = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    val text = item?.optString("text", "")
                    if (!text.isNullOrBlank()) {
                        paragraphs.add(cleanHtmlEntities(text))
                    }
                }
                if (paragraphs.isNotEmpty()) {
                    return paragraphs.joinToString("\n\n")
                }
            } catch (_: Exception) {}
        }
        return cleanHtmlEntities(str).takeIf { it.isNotBlank() }
    }
}
