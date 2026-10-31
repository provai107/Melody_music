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

        val CANONICAL_ARTIST_IMAGES = mapOf(
            "arijit singh" to "https://c.saavncdn.com/artists/Arijit_Singh_004_20241118063717_500x500.jpg",
            "diljit dosanjh" to "https://c.saavncdn.com/artists/Diljit_Dosanjh_005_20231025073054_500x500.jpg",
            "the weeknd" to "https://c.saavncdn.com/artists/The_Weeknd_002_20241003071400_500x500.jpg",
            "taylor swift" to "https://c.saavncdn.com/artists/Taylor_Swift_003_20200226074119_500x500.jpg",
            "shreya ghoshal" to "https://c.saavncdn.com/artists/Shreya_Ghoshal_007_20241101074144_500x500.jpg",
            "billie eilish" to "https://c.saavncdn.com/artists/Billie_Eilish_20190211151539_500x500.jpg",
            "badshah" to "https://c.saavncdn.com/artists/Badshah_006_20241118064015_500x500.jpg",
            "ap dhillon" to "https://c.saavncdn.com/artists/AP_Dhillon_004_20251023102150_500x500.jpg",
            "ed sheeran" to "https://c.saavncdn.com/artists/Ed_Sheeran_002_20250625073038_500x500.jpg",
            "anirudh" to "https://c.saavncdn.com/artists/Anirudh_Ravichander_003_20260121134149_500x500.jpg",
            "anirudh ravichander" to "https://c.saavncdn.com/artists/Anirudh_Ravichander_003_20260121134149_500x500.jpg",
            "darshan raval" to "https://c.saavncdn.com/artists/Darshan_Raval_006_20250807060352_500x500.jpg",
            "atif aslam" to "https://c.saavncdn.com/180/Atif-Aslam-Hindi-2019-20190401163644-500x500.jpg",
            "neha kakkar" to "https://c.saavncdn.com/artists/Neha_Kakkar_007_20241212115832_500x500.jpg",
            "pritam" to "https://c.saavncdn.com/artists/Pritam_Chakraborty-20170711073326_500x500.jpg",
            "justin bieber" to "https://c.saavncdn.com/artists/Justin_Bieber_005_20201127112218_500x500.jpg"
        )

        fun getCanonicalArtistImage(artistName: String): String? {
            val lower = artistName.lowercase().trim()
            CANONICAL_ARTIST_IMAGES[lower]?.let { return it }
            for ((key, url) in CANONICAL_ARTIST_IMAGES) {
                if (lower.contains(key) || key.contains(lower)) {
                    return url
                }
            }
            return null
        }

        val CURATED_POPULAR_ARTISTS = listOf(
            PopularArtistItem("art_arijit", "Arijit Singh", "Top Artist", "https://c.saavncdn.com/artists/Arijit_Singh_004_20241118063717_500x500.jpg", "Arijit Singh Hits"),
            PopularArtistItem("art_diljit", "Diljit Dosanjh", "Top Artist", "https://c.saavncdn.com/artists/Diljit_Dosanjh_005_20231025073054_500x500.jpg", "Diljit Dosanjh"),
            PopularArtistItem("art_darshan", "Darshan Raval", "Top Artist", "https://c.saavncdn.com/artists/Darshan_Raval_006_20250807060352_500x500.jpg", "Darshan Raval"),
            PopularArtistItem("art_atif", "Atif Aslam", "Top Artist", "https://c.saavncdn.com/180/Atif-Aslam-Hindi-2019-20190401163644-500x500.jpg", "Atif Aslam"),
            PopularArtistItem("art_weeknd", "The Weeknd", "Top Artist", "https://c.saavncdn.com/artists/The_Weeknd_002_20241003071400_500x500.jpg", "The Weeknd"),
            PopularArtistItem("art_taylor", "Taylor Swift", "Top Artist", "https://c.saavncdn.com/artists/Taylor_Swift_003_20200226074119_500x500.jpg", "Taylor Swift"),
            PopularArtistItem("art_shreya", "Shreya Ghoshal", "Top Artist", "https://c.saavncdn.com/artists/Shreya_Ghoshal_007_20241101074144_500x500.jpg", "Shreya Ghoshal"),
            PopularArtistItem("art_billie", "Billie Eilish", "Top Artist", "https://c.saavncdn.com/artists/Billie_Eilish_20190211151539_500x500.jpg", "Billie Eilish"),
            PopularArtistItem("art_badshah", "Badshah", "Top Artist", "https://c.saavncdn.com/artists/Badshah_006_20241118064015_500x500.jpg", "Badshah"),
            PopularArtistItem("art_apdhillon", "AP Dhillon", "Top Artist", "https://c.saavncdn.com/artists/AP_Dhillon_004_20251023102150_500x500.jpg", "AP Dhillon"),
            PopularArtistItem("art_edsheeran", "Ed Sheeran", "Top Artist", "https://c.saavncdn.com/artists/Ed_Sheeran_002_20250625073038_500x500.jpg", "Ed Sheeran"),
            PopularArtistItem("art_anirudh", "Anirudh", "Top Artist", "https://c.saavncdn.com/artists/Anirudh_Ravichander_003_20260121134149_500x500.jpg", "Anirudh Ravichander"),
            PopularArtistItem("art_neha", "Neha Kakkar", "Top Artist", "https://c.saavncdn.com/artists/Neha_Kakkar_007_20241212115832_500x500.jpg", "Neha Kakkar")
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
                    val imageUrl = getCanonicalArtistImage(cleanName) ?: matchingCurated?.imageUrl
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
                val imageUrl = getCanonicalArtistImage(cleanName)
                result.add(
                    PopularArtistItem(
                        id = "user_${cleanName.hashCode()}",
                        name = cleanName,
                        role = "Your Top Artist",
                        imageUrl = imageUrl,
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
