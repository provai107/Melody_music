package com.braveplayer.music.discovery

import com.braveplayer.music.model.GenreCardTheme
import com.braveplayer.music.model.PopularArtistItem
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import java.util.Calendar

/**
 * Intelligent genre personalization and artist discovery engine.
 * Computes multi-signal genre affinity from user settings preferences, liked songs,
 * listening history, most-played counts, and search patterns.
 */
class GenrePersonalizationEngine(
    private val dbHelper: PlaylistDatabaseHelper,
    private val appSettings: AppSettings? = null
) {

    companion object {
        private val GENRE_KEYWORDS: Map<String, List<String>> = mapOf(
            "pop" to listOf("pop", "hits", "billboard", "taylor", "ariana", "dua lipa", "ed sheeran", "shawn", "bieber", "maroon", "charlie"),
            "hiphop" to listOf("hip hop", "hiphop", "rap", "trap", "drake", "eminem", "kendrick", "travis", "post malone", "divine", "mc stan", "sidhu", "raftaar"),
            "rock" to listOf("rock", "metal", "queen", "linkin", "coldplay", "imagine dragons", "metallica", "nirvana", "ac/dc", "guitar", "band"),
            "edm" to listOf("edm", "dance", "electronic", "alan walker", "marshmello", "chainsmokers", "garrix", "avicii", "tiesto", "dj", "remix"),
            "bollywood" to listOf("bollywood", "hindi", "arijit", "shreya", "pritam", "neha kakkar", "jubin", "atif", "badshah", "sonu", "alka", "kumar sanu"),
            "punjabi" to listOf("punjabi", "diljit", "ap dhillon", "shubh", "karan aujla", "sidhu moose", "gurinder", "ammy", "hardy", "b praak"),
            "lofi" to listOf("lofi", "lo-fi", "chillhop", "study", "relax", "rain", "beats", "sleep", "aesthetic", "slowed"),
            "rnb" to listOf("r&b", "rnb", "soul", "weeknd", "sza", "bruno mars", "usher", "frank ocean", "chris brown", "giveon"),
            "indie" to listOf("indie", "alternative", "folk", "prateek kuhad", "anuv jain", "clairo", "phoebe", "cavetown", "boygenius"),
            "acoustic" to listOf("acoustic", "unplugged", "guitar", "piano", "stripped", "fingerstyle", "ukulele"),
            "classical" to listOf("classical", "orchestra", "symphony", "mozart", "beethoven", "bach", "chopin", "instrumental", "concerto"),
            "romantic" to listOf("love", "romantic", "romance", "ballad", "heart", "dil", "pyaar", "ishq", "tum", "mohabbat", "humsafar"),
            "chill" to listOf("chill", "relax", "peace", "calm", "meditation", "ambient", "zen", "breathe"),
            "workout" to listOf("workout", "gym", "energy", "fitness", "motivation", "power", "pump", "training", "beast"),
            "focus" to listOf("focus", "study", "flow", "coding", "work", "concentration", "brain", "reading"),
            "party" to listOf("party", "club", "celebration", "fest", "banger", "nightclub", "bass")
        )

        val CURATED_POPULAR_ARTISTS = listOf(
            PopularArtistItem("art_arijit", "Arijit Singh", "Top Artist", "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=200&q=80", "Arijit Singh Hits"),
            PopularArtistItem("art_diljit", "Diljit Dosanjh", "Top Artist", "https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=200&q=80", "Diljit Dosanjh"),
            PopularArtistItem("art_weeknd", "The Weeknd", "Top Artist", "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=200&q=80", "The Weeknd"),
            PopularArtistItem("art_taylor", "Taylor Swift", "Top Artist", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=200&q=80", "Taylor Swift"),
            PopularArtistItem("art_shreya", "Shreya Ghoshal", "Top Artist", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=200&q=80", "Shreya Ghoshal"),
            PopularArtistItem("art_billie", "Billie Eilish", "Top Artist", "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=200&q=80", "Billie Eilish"),
            PopularArtistItem("art_badshah", "Badshah", "Top Artist", "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=200&q=80", "Badshah"),
            PopularArtistItem("art_apdhillon", "AP Dhillon", "Top Artist", "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=200&q=80", "AP Dhillon"),
            PopularArtistItem("art_edsheeran", "Ed Sheeran", "Top Artist", "https://images.unsplash.com/photo-1510915361894-db8b60106cb1?w=200&q=80", "Ed Sheeran"),
            PopularArtistItem("art_anirudh", "Anirudh", "Top Artist", "https://images.unsplash.com/photo-1498038432885-c6f3f1b912ee?w=200&q=80", "Anirudh Ravichander")
        )
    }

    /**
     * Computes dynamically ranked favourite genres based on user settings preferences,
     * history, liked songs, play counts, and search queries.
     * Falls back smoothly to popular catalog genres when user data is scarce.
     */
    fun getFavouriteGenres(
        limit: Int = 6,
        userSelectedGenres: Set<String>? = null
    ): List<GenreCardTheme> {
        val result = mutableListOf<GenreCardTheme>()
        val seenThemeIds = mutableSetOf<String>()

        // 1. Explicit user preferences from AppSettings or argument
        val preferred = userSelectedGenres ?: appSettings?.getFavouriteGenres()
        if (preferred != null) {
            for (genreName in preferred) {
                if (result.size >= limit) break
                val theme = findThemeByName(genreName)
                if (theme != null && seenThemeIds.add(theme.id)) {
                    result.add(theme)
                }
            }
        }

        // 2. Multi-signal affinity from history, likes, and searches
        val scores = mutableMapOf<String, Double>()
        MusicCatalog.GENRE_THEMES.forEach { scores[it.id] = 0.0 }

        val likedSongs: List<Song> = try { dbHelper.getAllLikedSongs() } catch (_: Exception) { emptyList() }
        for (song in likedSongs) {
            addSongScore(scores, song, 3.0)
        }

        val recentSongs: List<Song> = try { dbHelper.getRecentlyPlayedSongs(50) } catch (_: Exception) { emptyList() }
        for (song in recentSongs) {
            addSongScore(scores, song, 1.5)
        }

        val mostPlayed: List<Pair<Song, Int>> = try { dbHelper.getMostPlayedSongs(30) } catch (_: Exception) { emptyList() }
        for ((song, playCount) in mostPlayed) {
            addSongScore(scores, song, 0.5 * playCount.coerceAtMost(10))
        }

        val recentSearches = try { dbHelper.getRecentSearches(20) } catch (_: Exception) { emptyList() }
        for (search in recentSearches) {
            val queryText = (search.queryText + " " + search.title).lowercase()
            for ((genreId, keywords) in GENRE_KEYWORDS) {
                if (keywords.any { queryText.contains(it) }) {
                    scores[genreId] = (scores[genreId] ?: 0.0) + 2.0
                }
            }
        }

        // Rank genres with positive affinity
        val ranked = scores.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .mapNotNull { entry -> MusicCatalog.GENRE_THEMES.firstOrNull { it.id == entry.key } }

        for (theme in ranked) {
            if (result.size >= limit) break
            if (seenThemeIds.add(theme.id)) {
                result.add(theme)
            }
        }

        // 3. Graceful fallback: If less than limit, append popular defaults
        val defaultFallbackIds = listOf("bollywood", "pop", "punjabi", "romantic", "hiphop", "edm", "lofi")
        for (id in defaultFallbackIds) {
            if (result.size >= limit) break
            val theme = MusicCatalog.GENRE_THEMES.firstOrNull { it.id == id }
            if (theme != null && seenThemeIds.add(theme.id)) {
                result.add(theme)
            }
        }

        // Fill remaining from catalog if still under limit
        for (theme in MusicCatalog.GENRE_THEMES) {
            if (result.size >= limit) break
            if (seenThemeIds.add(theme.id)) {
                result.add(theme)
            }
        }

        return result.take(limit)
    }

    private fun findThemeByName(name: String): GenreCardTheme? {
        val clean = name.trim().lowercase()
        val norm = clean.replace("-", "").replace(" ", "")
        return MusicCatalog.GENRE_THEMES.firstOrNull {
            it.id.equals(clean, ignoreCase = true) ||
            it.displayName.equals(clean, ignoreCase = true) ||
            it.id.replace("-", "").replace(" ", "").equals(norm, ignoreCase = true)
        }
    }

    /**
     * Returns curated genres suggested for discovery based on complementary styles
     * and current time of day, excluding the user's top favourite genres.
     */
    fun getRecommendedGenres(
        limit: Int = 6,
        excludedGenreIds: Set<String> = emptySet(),
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): List<GenreCardTheme> {
        val timeBoosts = when (currentHour) {
            in 5..11 -> listOf("acoustic", "classical", "chill", "indie", "pop")
            in 12..16 -> listOf("pop", "rock", "workout", "edm", "hiphop")
            in 17..21 -> listOf("romantic", "lofi", "rnb", "bollywood", "party")
            else -> listOf("lofi", "chill", "acoustic", "focus", "ambient")
        }

        val available = MusicCatalog.GENRE_THEMES.filter { it.id !in excludedGenreIds }
        val recommended = mutableListOf<GenreCardTheme>()

        // Add time-boosted genres first
        for (id in timeBoosts) {
            val theme = available.firstOrNull { it.id == id }
            if (theme != null && !recommended.contains(theme)) {
                recommended.add(theme)
            }
        }

        // Add remaining available genres for diversity
        for (theme in available) {
            if (recommended.size >= limit) break
            if (!recommended.contains(theme)) {
                recommended.add(theme)
            }
        }

        return recommended.take(limit)
    }

    /**
     * Returns all genres in the catalog for Browse All.
     */
    fun getAllGenres(): List<GenreCardTheme> {
        return MusicCatalog.GENRE_THEMES
    }

    /**
     * Builds a list of popular artists dynamically merging user preferences,
     * play history, and curated high-profile artist recommendations.
     */
    fun getPopularArtists(
        limit: Int = 10,
        userSelectedSingers: Set<String>? = null
    ): List<PopularArtistItem> {
        val result = mutableListOf<PopularArtistItem>()
        val seenNames = mutableSetOf<String>()

        // 1. User's explicit favourite singers from AppSettings or parameter
        val preferredSingers = userSelectedSingers ?: appSettings?.getFavouriteSingers()
        if (preferredSingers != null) {
            for (singer in preferredSingers) {
                if (result.size >= limit) break
                val cleanName = singer.trim()
                if (cleanName.isNotBlank() && seenNames.add(cleanName.lowercase())) {
                    val matchingCurated = CURATED_POPULAR_ARTISTS.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
                    val imageUrl = matchingCurated?.imageUrl
                        ?: if (cleanName.contains("Atif", ignoreCase = true)) "https://c.saavncdn.com/180/Atif-Aslam-Hindi-2019-20190401163644-500x500.jpg" else null
                    result.add(
                        PopularArtistItem(
                            id = "fav_${cleanName.hashCode()}",
                            name = cleanName,
                            role = "Favourite Artist",
                            imageUrl = imageUrl,
                            query = "$cleanName Songs"
                        )
                    )
                }
            }
        }

        // 2. User's top played artists from local database
        val playCounts: Map<String, Int> = try { dbHelper.getArtistPlayCounts() } catch (_: Exception) { emptyMap() }
        val topUserArtists = playCounts.entries
            .filter { it.key.isNotBlank() && !it.key.equals("Unknown Artist", ignoreCase = true) }
            .sortedByDescending { it.value }
            .take(5)

        for (entry in topUserArtists) {
            if (result.size >= limit) break
            val artistName = entry.key.trim()
            val cleanName = artistName.split(",", "&", "feat.", "ft.")[0].trim()
            if (cleanName.isNotBlank() && seenNames.add(cleanName.lowercase())) {
                result.add(
                    PopularArtistItem(
                        id = "user_${cleanName.hashCode()}",
                        name = cleanName,
                        role = "Your Top Artist",
                        imageUrl = null, // Will fall back to avatar with letter
                        query = "$cleanName Songs",
                        playCount = entry.value
                    )
                )
            }
        }

        // 3. Curated popular artists
        for (artist in CURATED_POPULAR_ARTISTS) {
            if (result.size >= limit) break
            if (seenNames.add(artist.name.lowercase())) {
                result.add(artist)
            }
        }

        return result.take(limit)
    }

    private fun addSongScore(scores: MutableMap<String, Double>, song: Song, weight: Double) {
        val haystack = "${song.title} ${song.artists} ${song.album}".lowercase()
        for ((genreId, keywords) in GENRE_KEYWORDS) {
            if (keywords.any { haystack.contains(it) }) {
                scores[genreId] = (scores[genreId] ?: 0.0) + weight
            }
        }
    }
}
