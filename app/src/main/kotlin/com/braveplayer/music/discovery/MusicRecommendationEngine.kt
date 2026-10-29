package com.braveplayer.music.discovery

import android.util.LruCache
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.api.SearchResult
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-signal local recommendation engine computing candidate relevance
 * using intelligent next-track scoring: same genre/style, release time / era,
 * same singer / collaborators, user listening history, liked songs,
 * search interactions, completion rates, skip penalties, not-interested feedback,
 * hidden song filtering, and selectable recommendation diversity.
 *
 * Strictly prevents repeating identical songs, remixes, lyrical variants, or
 * tracks already queued or recently played.
 */
class MusicRecommendationEngine(
    private val metadataService: MusicMetadataService,
    private val dbHelper: PlaylistDatabaseHelper
) {

    companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache

        /**
         * Cleans and normalizes song titles by stripping extraneous edition/format tags
         * such as "(feat. ...)", "(remix)", "(reprise)", "[official video]", "(slowed)", etc.
         */
        fun normalizeTitle(rawTitle: String): String {
            return rawTitle.lowercase()
                .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
                .replace(Regex("(?i)\\b(remix|acoustic|reprise|version|live|deluxe|remastered|lyrical|cover|slowed|reverb|edit|soundtrack|audio|video)\\b"), " ")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Checks whether two song titles refer to substantially the same track
         * (e.g. original vs remix, live version, alternate release).
         */
        fun areTitlesTooSimilar(titleA: String, titleB: String): Boolean {
            val normA = normalizeTitle(titleA)
            val normB = normalizeTitle(titleB)
            if (normA.isEmpty() || normB.isEmpty()) return false
            if (normA == normB) return true

            // Substring inclusion check if length ratio is high
            if ((normA.contains(normB) || normB.contains(normA)) &&
                (minOf(normA.length, normB.length).toDouble() / maxOf(normA.length, normB.length)) >= 0.60) {
                return true
            }

            // Word token Jaccard similarity
            val wordsA = normA.split(" ").filter { it.length > 2 }.toSet()
            val wordsB = normB.split(" ").filter { it.length > 2 }.toSet()
            if (wordsA.isNotEmpty() && wordsB.isNotEmpty()) {
                val common = wordsA.intersect(wordsB).size
                val total = wordsA.union(wordsB).size
                if (common.toDouble() / total >= 0.55) {
                    return true
                }
            }
            return false
        }

        /**
         * Infers matching genres/styles for a song from its title, artist, and album.
         */
        fun inferGenres(song: Song): Set<String> {
            val text = "${song.title} ${song.artists} ${song.album}".lowercase()
            val matches = mutableSetOf<String>()
            val genreKeywords = mapOf(
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

            for ((genre, keywords) in genreKeywords) {
                if (keywords.any { text.contains(it) }) {
                    matches.add(genre)
                }
            }
            return matches
        }

        /**
         * Parses 4-digit release year from a song's metadata.
         */
        fun parseSongYear(song: Song): Int? {
            val yStr = song.year ?: song.releaseDate
            if (yStr.isNullOrBlank()) return null
            val match = Regex("\\b(19\\d\\d|20\\d\\d)\\b").find(yStr)
            return match?.value?.toIntOrNull()
        }
    }

    // In-memory cache for candidate queries: query -> Pair(timestamp, List<Song>)
    private val candidateCache = LruCache<String, Pair<Long, List<Song>>>(60)

    /**
     * Retrieves ranked recommendations based on a seed song, user preferences, and listening history.
     * Recommends based on same genre, release era, and same singer/collaborators,
     * while strictly deduplicating against the seed song and queued tracks.
     */
    suspend fun getRecommendations(
        baseUrl: String,
        seedSong: Song,
        excludeSongIds: Set<String> = emptySet(),
        excludeTitles: Set<String> = emptySet(),
        limit: Int = 10,
        personalizationEnabled: Boolean = true,
        avoidRecentlyPlayed: Boolean = true,
        diversityMode: String = AppSettings.DIVERSITY_BALANCED
    ): List<Song> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<Song>()
        val hiddenIds = dbHelper.getHiddenSongIds()
        val notInterestedIds = dbHelper.getNotInterestedSongIds()
        val notInterestedArtists = dbHelper.getNotInterestedArtists()

        val likedIds = if (personalizationEnabled) dbHelper.getLikedSongIds() else emptySet()
        val skippedIds = if (personalizationEnabled) dbHelper.getSkippedSongIds() else emptySet()
        val artistPlayCounts = if (personalizationEnabled) dbHelper.getArtistPlayCounts() else emptyMap()
        val recentIds = if (personalizationEnabled && avoidRecentlyPlayed) dbHelper.getRecentSongIds(25) else emptySet()
        val recentSearches = if (personalizationEnabled) dbHelper.getRecentSearches(10).map { it.queryText.lowercase().trim() } else emptyList()

        // Construct bounded queries based on same singer, same genre, and release era
        // IMPORTANT: Never query seedSong.title directly to avoid loops of the exact same song
        val queries = mutableListOf<String>()
        val primaryArtist = extractPrimaryArtist(seedSong.artists)
        val coArtist = extractSecondaryArtist(seedSong.artists)
        val inferredGenres = inferGenres(seedSong)
        val seedYear = parseSongYear(seedSong)

        // 1. Same Singer / Collaborator queries
        if (primaryArtist.isNotBlank() && primaryArtist.lowercase() !in notInterestedArtists) {
            queries.add("$primaryArtist Hits")
            queries.add(primaryArtist)
        }
        if (!coArtist.isNullOrBlank() && coArtist.lowercase() !in notInterestedArtists) {
            queries.add(coArtist)
        }

        // 2. Same Genre / Style queries
        for (genre in inferredGenres.take(2)) {
            val capGenre = genre.replaceFirstChar { it.uppercase() }
            queries.add("$capGenre Hits")
        }

        // 3. Release Time / Era queries
        if (seedYear != null) {
            val eraQuery = when {
                seedYear >= 2020 -> "2020s Hits"
                seedYear >= 2010 -> "2010s Hits"
                seedYear >= 2000 -> "2000s Hits"
                seedYear >= 1990 -> "90s Hits"
                else -> "Retro Hits"
            }
            queries.add(eraQuery)
        }

        // 4. Contextual discovery queries based on diversity mode
        when (diversityMode) {
            AppSettings.DIVERSITY_FAMILIAR -> {
                val topArtist = artistPlayCounts.keys.firstOrNull { it.isNotBlank() && it != primaryArtist }
                if (topArtist != null && topArtist.lowercase() !in notInterestedArtists) {
                    queries.add("$topArtist Songs")
                }
            }
            AppSettings.DIVERSITY_DISCOVERY -> {
                if (inferredGenres.isNotEmpty()) {
                    queries.add("${inferredGenres.first().replaceFirstChar { it.uppercase() }} Best of")
                } else {
                    queries.add("Top Trending Songs")
                }
            }
            else -> {
                if (queries.size < 3) {
                    queries.add("Popular Hits")
                }
            }
        }

        for (query in queries.distinct().take(4)) {
            val songs = fetchCandidateSongs(baseUrl, query)
            candidates.addAll(songs)
        }

        rankCandidates(
            seedSong = seedSong,
            candidates = candidates,
            excludeSongIds = excludeSongIds + recentIds + hiddenIds,
            excludeTitles = excludeTitles,
            likedIds = likedIds,
            skippedIds = skippedIds,
            artistPlayCounts = artistPlayCounts,
            limit = limit,
            notInterestedIds = notInterestedIds,
            notInterestedArtists = notInterestedArtists,
            recentSearchQueries = recentSearches,
            diversityMode = diversityMode,
            personalizationEnabled = personalizationEnabled
        )
    }

    suspend fun fetchCandidateSongs(baseUrl: String, query: String): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val cached = candidateCache.get(trimmed)
        if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
            return cached.second
        }

        return try {
            val result = metadataService.searchSongs(baseUrl, trimmed, maxRetries = 1)
            if (result is SearchResult.Success) {
                candidateCache.put(trimmed, Pair(now, result.songs))
                result.songs
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun rankCandidates(
        seedSong: Song,
        candidates: List<Song>,
        excludeSongIds: Set<String>,
        excludeTitles: Set<String> = emptySet(),
        likedIds: Set<String> = emptySet(),
        skippedIds: Set<String> = emptySet(),
        artistPlayCounts: Map<String, Int> = emptyMap(),
        limit: Int = 10,
        notInterestedIds: Set<String> = emptySet(),
        notInterestedArtists: Set<String> = emptySet(),
        recentSearchQueries: List<String> = emptyList(),
        diversityMode: String = AppSettings.DIVERSITY_BALANCED,
        personalizationEnabled: Boolean = true
    ): List<Song> {
        val seenIds = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()
        val scoredList = mutableListOf<Pair<Song, Double>>()

        val seedArtist = extractPrimaryArtist(seedSong.artists).lowercase()
        val seedAlbum = seedSong.album.trim().lowercase()
        val seedGenres = inferGenres(seedSong)
        val seedYear = parseSongYear(seedSong)

        for (candidate in candidates) {
            if (candidate.getBestPlaybackUrl() == null) continue
            if (candidate.id == seedSong.id) continue
            if (candidate.id in excludeSongIds) continue
            if (!seenIds.add(candidate.id)) continue

            // 1. REJECT identical or derivative title of the seed song
            // Prevents recommending the same song (remix, lyrical, acoustic, etc.)
            if (areTitlesTooSimilar(candidate.title, seedSong.title)) continue

            // 2. REJECT title if it already exists in the queue or recent playback history
            if (excludeTitles.any { areTitlesTooSimilar(candidate.title, it) }) continue

            // 3. Deduplicate across candidates themselves
            val candNormTitle = normalizeTitle(candidate.title)
            if (candNormTitle.isNotEmpty() && !seenTitles.add(candNormTitle)) continue

            // Permanently filter out "Not Interested" tracks
            if (candidate.id in notInterestedIds) continue

            var score = 0.0
            val candidatePrimaryArtist = extractPrimaryArtist(candidate.artists).lowercase()
            val candidateAlbum = candidate.album.trim().lowercase()
            val candGenres = inferGenres(candidate)
            val candYear = parseSongYear(candidate)

            // 1. SAME SINGER / ARTIST AFFINITY
            if (candidatePrimaryArtist == seedArtist && seedArtist.isNotEmpty()) {
                score += 35.0
            } else if (seedArtist.isNotEmpty() && (candidatePrimaryArtist.contains(seedArtist) || seedArtist.contains(candidatePrimaryArtist))) {
                score += 25.0
            } else {
                // Check if candidate shares featured artists
                val candArtistsList = candidate.artists.split(",", "&", "/").map { it.trim().lowercase() }
                val seedArtistsList = seedSong.artists.split(",", "&", "/").map { it.trim().lowercase() }
                if (candArtistsList.any { it in seedArtistsList && it.isNotEmpty() }) {
                    score += 20.0
                }
            }

            // 2. SAME GENRE / STYLE AFFINITY
            val sharedGenres = seedGenres.intersect(candGenres)
            if (sharedGenres.isNotEmpty()) {
                score += 30.0 + (sharedGenres.size - 1) * 5.0
            }

            // 3. RELEASE TIME / ERA MATCHING
            if (seedYear != null && candYear != null) {
                val yearDiff = kotlin.math.abs(candYear - seedYear)
                when {
                    yearDiff <= 2 -> score += 25.0 // Very close release period
                    yearDiff <= 5 -> score += 15.0 // Same era
                    yearDiff <= 10 -> score += 8.0 // Neighboring period
                    yearDiff > 25 -> score -= 10.0 // Very distant era
                }
            }

            // 4. Album context affinity (only if different track!)
            if (seedAlbum.isNotEmpty() && seedAlbum != "unknown album" && candidateAlbum == seedAlbum) {
                score += 15.0
            }

            // 5. Not interested artist penalty
            if (candidatePrimaryArtist in notInterestedArtists) {
                score -= 40.0
            }

            // 6. Behavioral signals (only if personalization enabled)
            if (personalizationEnabled) {
                // Liked track bonus
                if (candidate.id in likedIds) {
                    score += if (diversityMode == AppSettings.DIVERSITY_FAMILIAR) 40.0 else 25.0
                }

                // History Skip penalty
                if (candidate.id in skippedIds) {
                    score -= 30.0
                }

                // Search query match bonus
                if (recentSearchQueries.any { q -> candidate.title.lowercase().contains(q) || candidate.artists.lowercase().contains(q) }) {
                    score += 15.0
                }

                // Diversity-dependent artist affinity
                val artistPlays = artistPlayCounts[extractPrimaryArtist(candidate.artists)] ?: 0
                when (diversityMode) {
                    AppSettings.DIVERSITY_FAMILIAR -> {
                        if (artistPlays > 0) {
                            score += minOf(artistPlays * 4.0, 35.0)
                        }
                    }
                    AppSettings.DIVERSITY_DISCOVERY -> {
                        if (artistPlays > 3) {
                            score -= 20.0
                        } else if (artistPlays == 0 && candidatePrimaryArtist != seedArtist) {
                            score += 30.0
                        }
                    }
                    else -> { // BALANCED
                        if (artistPlays > 0) {
                            score += minOf(artistPlays * 2.5, 20.0)
                        }
                    }
                }
            }

            // 7. Standard track duration bonus (2 to 7 minutes)
            if (candidate.durationSec in 120..420) {
                score += 5.0
            }

            scoredList.add(Pair(candidate, score))
        }

        scoredList.sortByDescending { it.second }

        // Apply artist diversity cap: prevent excessive tracks from the same artist in the output batch
        val finalResult = mutableListOf<Song>()
        val artistCount = mutableMapOf<String, Int>()
        val maxPerArtist = if (diversityMode == AppSettings.DIVERSITY_FAMILIAR) 3 else 2

        for ((song, _) in scoredList) {
            if (finalResult.size >= limit) break
            val artist = extractPrimaryArtist(song.artists).lowercase()
            val count = artistCount[artist] ?: 0
            if (count < maxPerArtist) {
                finalResult.add(song)
                artistCount[artist] = count + 1
            }
        }

        // If cap was too restrictive and we have room, fill remaining up to limit
        if (finalResult.size < limit) {
            for ((song, _) in scoredList) {
                if (finalResult.size >= limit) break
                if (song !in finalResult) {
                    finalResult.add(song)
                }
            }
        }

        return finalResult
    }

    fun extractPrimaryArtist(artists: String): String {
        return artists.split(",", "&", "/").firstOrNull()?.trim() ?: artists.trim()
    }

    fun extractSecondaryArtist(artists: String): String? {
        val parts = artists.split(",", "&", "/")
        return if (parts.size > 1) parts[1].trim() else null
    }

    fun clearCache() {
        candidateCache.evictAll()
    }
}
