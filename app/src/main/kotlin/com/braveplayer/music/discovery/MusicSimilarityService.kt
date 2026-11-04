package com.braveplayer.music.discovery

import com.braveplayer.music.model.Song
import kotlin.math.abs

/**
 * Service calculating granular musical and metadata similarity between tracks:
 * Genre overlap, artist relationships, temporal era, and album/movie continuity.
 */
object MusicSimilarityService {

    /**
     * Cleans and normalizes song titles by stripping extraneous tags
     * (e.g. "(feat. ...)", "(remix)", "(acoustic)", "[official video]", etc.)
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
            "pakistani" to listOf("pakistani", "coke studio", "atif aslam", "rahat", "ali zafar", "qawwali", "ghazal", "nusrat", "kaifi khalil", "urdu", "pakistan", "nescafe"),
            "sufi" to listOf("sufi", "qawwali", "ghazal", "nusrat", "rahat", "bulleya", "maula", "ali maula", "khwaja", "damadam"),
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

    fun extractPrimaryArtist(artists: String): String {
        return artists.split(",", "&", "/").firstOrNull()?.trim() ?: artists.trim()
    }

    fun extractSecondaryArtist(artists: String): String? {
        val parts = artists.split(",", "&", "/")
        return if (parts.size > 1) parts[1].trim() else null
    }

    /**
     * Calculates genre similarity between two tracks using Jaccard index (0.0 to 1.0).
     */
    fun genreSimilarity(songA: Song, songB: Song): Double {
        val genresA = inferGenres(songA)
        val genresB = inferGenres(songB)
        if (genresA.isEmpty() || genresB.isEmpty()) return 0.2 // default low neutral
        val intersection = genresA.intersect(genresB).size
        val union = genresA.union(genresB).size
        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    /**
     * Calculates artist relationship affinity between two tracks (0.0 to 1.0).
     */
    fun artistSimilarity(songA: Song, songB: Song): Double {
        val artistA = extractPrimaryArtist(songA.artists).lowercase()
        val artistB = extractPrimaryArtist(songB.artists).lowercase()
        if (artistA.isEmpty() || artistB.isEmpty()) return 0.0
        if (artistA == artistB) return 1.0

        if (artistA.contains(artistB) || artistB.contains(artistA)) return 0.75

        // Check shared featured collaborators
        val listA = songA.artists.split(",", "&", "/").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val listB = songB.artists.split(",", "&", "/").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val sharedCollaborators = listA.intersect(listB.toSet())
        if (sharedCollaborators.isNotEmpty()) return 0.60

        return 0.0
    }

    /**
     * Calculates temporal/era closeness between two tracks (0.0 to 1.0).
     */
    fun eraSimilarity(songA: Song, songB: Song): Double {
        val yearA = parseSongYear(songA) ?: return 0.5
        val yearB = parseSongYear(songB) ?: return 0.5
        val diff = abs(yearA - yearB)
        return when {
            diff <= 1 -> 1.0
            diff <= 3 -> 0.85
            diff <= 5 -> 0.70
            diff <= 10 -> 0.50
            diff <= 20 -> 0.25
            else -> 0.10
        }
    }

    /**
     * Checks album or soundtrack continuity (0.0 or 1.0).
     */
    fun albumContinuity(songA: Song, songB: Song): Double {
        val albumA = songA.album.trim().lowercase()
        val albumB = songB.album.trim().lowercase()
        if (albumA.isEmpty() || albumB.isEmpty()) return 0.0
        if (albumA == "unknown album" || albumA == "unknown" || albumA == "single" || albumA == "ep") return 0.0
        return if (albumA == albumB) 1.0 else 0.0
    }

    /**
     * Calculates overall musical similarity between two songs (0.0 to 1.0).
     */
    fun calculateOverallSimilarity(songA: Song, songB: Song): Double {
        val gSim = genreSimilarity(songA, songB)
        val aSim = artistSimilarity(songA, songB)
        val eSim = eraSimilarity(songA, songB)
        val albSim = albumContinuity(songA, songB)

        return (gSim * 0.40) + (aSim * 0.35) + (eSim * 0.15) + (albSim * 0.10)
    }
}
