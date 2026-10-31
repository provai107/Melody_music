package com.braveplayer.music.model

import java.util.Locale
import kotlin.math.log10

/**
 * Intelligent deduplication, voiceover/spam filtering, and relevance ranking engine for search.
 * Ensures:
 * 1. Duplicate songs with the same name and same singers across different album banners are strictly deduplicated.
 * 2. Amateur voiceovers, speeches, sermons, bayans, poems, dialogues, and low-quality karaoke/derivatives are filtered out.
 * 3. Movie queries match their official soundtrack albums accurately.
 * 4. High-quality official and famous tracks are ranked first.
 */
object SongDeduplicator {

    private val NEGATIVE_SPAM_PHRASES = listOf(
        "voice over", "voiceover", "voice-over",
        "dialogue", "dialogues",
        "monologue", "monologues",
        "bayan", "khutbah", "tafseer", "sermon", "speech",
        "poetry", "poem", "shayari", "mushaira", "ghazal recitation",
        "originally performed by", "backing business", "pristine karaoke", "karaoke", "backing track",
        "ringtone", "caller tune", "jiotune", "teaser clip", "status clip", "whatsapp status",
        "slowed and reverb", "slowed + reverb", "slowed reverb", "slowed & reverb", "slowed and reverbered", "slowed",
        "lofi", "lo-fi", "lo fi"
    )

    private val MILD_DERIVATIVE_WORDS = listOf(
        "remix", "lofi", "lo-fi", "slowed", "acoustic", "cover", "reprise", "unplugged"
    )

    /**
     * Cleans and normalizes a song title:
     * - Strips bracketed movie tags: (From "..."), [From "..."], (Original Motion Picture Soundtrack), (Full Song), etc.
     * - Cleans punctuation and normalizes whitespace.
     */
    fun normalizeTitle(title: String): String {
        var t = title.lowercase(Locale.ROOT).trim()
        t = t.replace("&quot;", "").replace("&#039;", "").replace("&amp;", "and")
        // Remove bracketed expressions containing movie/version qualifiers
        t = t.replace(Regex("\\([^)]*\\)")) { match ->
            val inner = match.value.lowercase(Locale.ROOT)
            if (listOf("from", "ost", "soundtrack", "full song", "full video", "audio", "video", "lyrical", "remastered", "version", "deluxe").any { inner.contains(it) }) {
                ""
            } else {
                match.value
            }
        }
        t = t.replace(Regex("\\[[^]]*\\]")) { match ->
            val inner = match.value.lowercase(Locale.ROOT)
            if (listOf("from", "ost", "soundtrack", "full song", "full video", "audio", "video", "lyrical", "remastered", "version", "deluxe").any { inner.contains(it) }) {
                ""
            } else {
                match.value
            }
        }
        // Remove punctuation
        t = t.replace(Regex("[^a-z0-9\\s]"), " ")
        return t.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Cleans and normalizes artist names into a canonical sorted pipe-delimited string.
     */
    fun normalizeArtists(artists: String): String {
        val raw = artists.lowercase(Locale.ROOT).trim()
            .replace("&quot;", "").replace("&#039;", "").replace("&amp;", "and")
        val parts = raw.split(Regex("[,&/]|\\b(and|feat|ft|ft\\.)\\b"))
        val cleaned = mutableListOf<String>()
        for (p in parts) {
            val c = p.replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (c.isNotBlank() && c != "various artists" && c != "unknown artist" && c != "various") {
                cleaned.add(c)
            }
        }
        cleaned.sort()
        return cleaned.joinToString("|")
    }

    /**
     * Returns the primary lead artist from the artist string.
     */
    fun getPrimaryArtist(artists: String): String {
        val norm = normalizeArtists(artists)
        return norm.split("|").firstOrNull().orEmpty()
    }

    /**
     * Determines whether two songs are duplicates:
     * They have accurately the same song title and same singers across different album covers/banners.
     */
    fun areDuplicates(a: Song, b: Song): Boolean {
        val titleA = normalizeTitle(a.title)
        val titleB = normalizeTitle(b.title)
        if (titleA.isBlank() || titleB.isBlank() || titleA != titleB) {
            return false
        }

        val artistsA = normalizeArtists(a.artists)
        val artistsB = normalizeArtists(b.artists)

        // Exact match of normalized artist sets
        if (artistsA.isNotBlank() && artistsB.isNotBlank() && artistsA == artistsB) {
            return true
        }

        // Matching primary lead artist
        val primaryA = getPrimaryArtist(a.artists)
        val primaryB = getPrimaryArtist(b.artists)
        if (primaryA.isNotBlank() && primaryB.isNotBlank() && primaryA == primaryB) {
            return true
        }

        // Overlapping artists set (e.g. duet vs solo with featured artist)
        val setA = artistsA.split("|").filter { it.isNotBlank() }.toSet()
        val setB = artistsB.split("|").filter { it.isNotBlank() }.toSet()
        if (setA.isNotEmpty() && setB.isNotEmpty() && setA.intersect(setB).isNotEmpty()) {
            return true
        }

        // If artist information is missing or generic on either, match by accurate title
        if (artistsA.isBlank() || artistsB.isBlank()) {
            return true
        }

        return false
    }

    /**
     * Checks if a song represents a voiceover, sermon, religious speech, monologue,
     * low-quality karaoke cover, or derivative clip that the user did not explicitly request.
     */
    fun isVoiceoverOrSpamDerivative(song: Song, query: String): Boolean {
        val lowerQuery = query.lowercase(Locale.ROOT).trim()
        val lowerTitle = song.title.lowercase(Locale.ROOT)
        val lowerAlbum = song.album.lowercase(Locale.ROOT)
        val lowerArtists = song.artists.lowercase(Locale.ROOT)
        val fullMetadata = "$lowerTitle $lowerAlbum $lowerArtists"

        for (phrase in NEGATIVE_SPAM_PHRASES) {
            if (fullMetadata.contains(phrase)) {
                // If user specifically included this phrase in their search query, keep it
                if (lowerQuery.contains(phrase)) {
                    continue
                }
                return true
            }
        }
        return false
    }

    /**
     * Checks whether an album result accurately represents the soundtrack for a movie query.
     */
    fun isMovieAlbumMatch(query: String, albumTitle: String): Boolean {
        // Strip common movie search noise: movie, film, soundtrack, ost, songs, album
        val cleanQuery = query.lowercase(Locale.ROOT)
            .replace(Regex("(?i)\\b(movie|film|soundtrack|ost|songs?|album)\\b"), " ")
            .trim()
        val q = normalizeTitle(cleanQuery)
        val a = normalizeTitle(albumTitle)
        if (q.length < 2) return false
        if (q == a) return true

        val cleanAlbum = a.replace(Regex("(?i)\\b(original motion picture soundtrack|soundtrack|ost|deluxe|edition|part \\d+|the rule|the rise)\\b"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (cleanAlbum == q) return true
        if (a.startsWith("$q ") || a.startsWith("$q:")) return true
        return false
    }

    /**
     * Relevance scoring function:
     * - Movie tracks get highest priority (+10000)
     * - Accurate title matches get major boost (+500 / +250 / +100)
     * - Artist match gets boost (+200)
     * - Play count provides logarithmic boost (100M plays ~ +80, 10M plays ~ +70, 1M plays ~ +60)
     * - Mild derivatives (remixes, unplugged, covers) not requested get penalized (-150)
     */
    fun scoreSong(song: Song, query: String, isMovieTrack: Boolean = false): Double {
        if (isMovieTrack) return 10000.0

        var score = 100.0
        val cleanTitle = normalizeTitle(song.title)
        val cleanQuery = normalizeTitle(query)
        val lowerQuery = query.lowercase(Locale.ROOT).trim()

        if (cleanTitle == cleanQuery) {
            score += 500.0
        } else if (cleanTitle.startsWith(cleanQuery)) {
            score += 250.0
        } else if (cleanTitle.contains(cleanQuery)) {
            score += 100.0
        }

        val lowerArtists = song.artists.lowercase(Locale.ROOT)
        if (lowerArtists.contains(lowerQuery)) {
            score += 200.0
        }

        val plays = song.playCount ?: 0L
        if (plays > 0) {
            score += log10(plays.toDouble().coerceAtLeast(1.0)) * 10.0
        }

        val fullText = "${song.title} ${song.album} ${song.artists}".lowercase(Locale.ROOT)
        for (derivative in MILD_DERIVATIVE_WORDS) {
            if (fullText.contains(derivative) && !lowerQuery.contains(derivative)) {
                score -= 150.0
            }
        }

        return score
    }

    /**
     * Deduplicates and ranks songs:
     * - Ensures movie soundtrack tracks remain at the front in order
     * - Removes duplicates that share the same song title and same singers across different banners
     * - Retains the version with official album or higher play count
     * - Ranks remaining songs by relevance and popularity score
     */
    fun deduplicateAndRankSongs(
        songs: List<Song>,
        query: String,
        movieTracks: List<Song> = emptyList()
    ): List<Song> {
        val movieTrackIds = movieTracks.map { it.id }.toSet()
        val distinctList = mutableListOf<Song>()

        // 1. Add movie soundtrack tracks first (deduplicated amongst themselves)
        for (mTrack in movieTracks) {
            if (mTrack.durationSec in 1..30 || mTrack.getBestPlaybackUrl() == null) continue
            if (distinctList.none { areDuplicates(it, mTrack) }) {
                distinctList.add(mTrack)
            }
        }

        // 2. Filter voiceover/spam candidates and score remaining songs
        val validCandidates = mutableListOf<Song>()
        for (song in songs) {
            if (song.durationSec in 1..30 || song.getBestPlaybackUrl() == null) continue
            if (movieTrackIds.contains(song.id)) continue // Already added via movie tracks
            if (isVoiceoverOrSpamDerivative(song, query)) continue
            validCandidates.add(song)
        }

        // Sort candidates by relevance & popularity score descending
        validCandidates.sortByDescending { scoreSong(it, query, isMovieTrack = false) }

        // 3. Deduplicate candidates against existing songs
        for (candidate in validCandidates) {
            val existingIndex = distinctList.indexOfFirst { areDuplicates(it, candidate) }
            if (existingIndex < 0) {
                distinctList.add(candidate)
            } else {
                // If the existing track is not a movie track and candidate has significantly higher play count
                val existing = distinctList[existingIndex]
                val isExistingMovieTrack = movieTrackIds.contains(existing.id)
                if (!isExistingMovieTrack) {
                    val existingPlays = existing.playCount ?: 0L
                    val candidatePlays = candidate.playCount ?: 0L
                    if (existingPlays == 0L && candidatePlays > 0L) {
                        distinctList[existingIndex] = candidate
                    }
                }
            }
        }

        return distinctList
    }
}
