package com.braveplayer.music.model

import java.text.Collator
import java.util.Locale
import java.util.regex.Pattern

/**
 * Full domain profile for an artist.
 */
data class ArtistProfile(
    val id: String,
    val name: String,
    val role: String = "Artist",
    val imageUrl: String? = null,
    val followerCount: Long? = null,
    val fanCount: Long? = null,
    val isVerified: Boolean = false,
    val bio: String? = null,
    val subtitle: String? = null,
    val topSongs: List<Song> = emptyList(),
    val topAlbums: List<ArtistAlbumItem> = emptyList(),
    val similarArtists: List<ArtistProfile> = emptyList(),
    val isFollowed: Boolean = false
) {
    /**
     * Formats follower count into readable metrics like "108.8M Followers" or "9.4M Monthly Listeners".
     */
    val formattedFollowerCount: String?
        get() {
            val count = followerCount?.takeIf { it > 0 } ?: fanCount?.takeIf { it > 0 } ?: return null
            return formatMetricCount(count) + if (fanCount != null && fanCount > 0) " Monthly Listeners" else " Followers"
        }

    companion object {
        fun formatMetricCount(count: Long): String {
            return when {
                count >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", count / 1_000_000_000.0)
                count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
                count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
                else -> count.toString()
            }
        }
    }
}

/**
 * Album or single entry in an artist's discography.
 */
data class ArtistAlbumItem(
    val id: String,
    val title: String,
    val year: String? = null,
    val imageUrl: String? = null
)

/**
 * Compact model for an artist appearing in search suggestions and search results.
 */
data class ArtistSearchResult(
    val id: String,
    val name: String,
    val role: String = "Artist",
    val imageUrl: String? = null,
    val listeners: String? = null
)

/**
 * Persisted record of an artist followed by the user.
 */
data class FollowedArtist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val followedAt: Long = System.currentTimeMillis()
)

/**
 * Available sorting modes for an artist's songs catalog.
 */
enum class ArtistSortOption(val displayName: String) {
    POPULARITY("Popularity — Highest to Lowest"),
    RELEASE_DATE("Release Date — Newest to Oldest"),
    ALPHABETICAL("Alphabetical — A to Z")
}

/**
 * Deterministic sorter for artist tracks adhering to exact acceptance criteria:
 * 1. Release Date: newest dated tracks first; undated tracks placed last; year/month precision handled consistently.
 * 2. Alphabetical: case-insensitive, Unicode and multilingual stable; stable tie-break.
 * 3. Popularity: provider's actual play count/metric descending; deterministic secondary sort on ties.
 */
object ArtistSongSorter {

    private val YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b")
    private val ISO_DATE_PATTERN = Pattern.compile("\\b(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?\\b")
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }

    fun sort(songs: List<Song>, option: ArtistSortOption): List<Song> {
        if (songs.size <= 1) return songs

        return when (option) {
            ArtistSortOption.RELEASE_DATE -> {
                songs.sortedWith { s1, s2 ->
                    val d1 = extractSortableDate(s1)
                    val d2 = extractSortableDate(s2)

                    when {
                        d1 != null && d2 != null -> {
                            val cmp = d2.compareTo(d1) // Descending (newest first)
                            if (cmp != 0) cmp else compareTitles(s1, s2)
                        }
                        d1 != null && d2 == null -> -1 // Dated songs come first
                        d1 == null && d2 != null -> 1  // Undated songs come last
                        else -> compareTitles(s1, s2)
                    }
                }
            }

            ArtistSortOption.ALPHABETICAL -> {
                songs.sortedWith { s1, s2 ->
                    compareTitles(s1, s2)
                }
            }

            ArtistSortOption.POPULARITY -> {
                songs.sortedWith { s1, s2 ->
                    val p1 = s1.playCount
                    val p2 = s2.playCount

                    when {
                        p1 != null && p2 != null -> {
                            val cmp = p2.compareTo(p1) // Descending (highest first)
                            if (cmp != 0) cmp else secondaryTieBreak(s1, s2)
                        }
                        p1 != null && p2 == null -> -1
                        p1 == null && p2 != null -> 1
                        else -> secondaryTieBreak(s1, s2)
                    }
                }
            }
        }
    }

    /**
     * Extracts an integer date key in YYYYMMDD format for date comparisons.
     * Precision rules:
     * - YYYY-MM-DD -> YYYY*10000 + MM*100 + DD
     * - YYYY-MM -> YYYY*10000 + MM*100 + 1
     * - YYYY -> YYYY*10000 + 101
     * Returns null if no valid date or year can be resolved.
     */
    fun extractSortableDate(song: Song): Long? {
        val rawDate = song.releaseDate?.trim()
        if (!rawDate.isNullOrBlank()) {
            val isoMatcher = ISO_DATE_PATTERN.matcher(rawDate)
            if (isoMatcher.find()) {
                val y = isoMatcher.group(1)?.toIntOrNull() ?: 0
                val m = isoMatcher.group(2)?.toIntOrNull() ?: 1
                val d = isoMatcher.group(3)?.toIntOrNull() ?: 1
                return y * 10000L + (m.coerceIn(1, 12) * 100L) + d.coerceIn(1, 31)
            }
            val yearMatcher = YEAR_PATTERN.matcher(rawDate)
            if (yearMatcher.find()) {
                val y = yearMatcher.group(1)?.toIntOrNull()
                if (y != null) return y * 10000L + 101L
            }
        }

        val rawYear = song.year?.trim()
        if (!rawYear.isNullOrBlank()) {
            val yearMatcher = YEAR_PATTERN.matcher(rawYear)
            if (yearMatcher.find()) {
                val y = yearMatcher.group(1)?.toIntOrNull()
                if (y != null) return y * 10000L + 101L
            }
        }

        // Fallback: check album or title for 4-digit year e.g. (2024)
        val albumYear = YEAR_PATTERN.matcher(song.album)
        if (albumYear.find()) {
            val y = albumYear.group(1)?.toIntOrNull()
            if (y != null) return y * 10000L + 101L
        }

        val titleYear = YEAR_PATTERN.matcher(song.title)
        if (titleYear.find()) {
            val y = titleYear.group(1)?.toIntOrNull()
            if (y != null) return y * 10000L + 101L
        }

        return null
    }

    private fun compareTitles(s1: Song, s2: Song): Int {
        val cmp = collator.compare(s1.title.trim(), s2.title.trim())
        return if (cmp != 0) cmp else s1.id.compareTo(s2.id)
    }

    private fun secondaryTieBreak(s1: Song, s2: Song): Int {
        val d1 = extractSortableDate(s1)
        val d2 = extractSortableDate(s2)
        if (d1 != null && d2 != null && d1 != d2) {
            return d2.compareTo(d1)
        }
        return compareTitles(s1, s2)
    }
}
