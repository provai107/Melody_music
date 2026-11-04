package com.braveplayer.music.discovery

import com.braveplayer.music.model.Song
import com.braveplayer.music.settings.AppSettings

/**
 * Breakdown of recommendation scoring components based on the 7-factor weighted algorithm.
 */
data class ScoredCandidate(
    val song: Song,
    val totalScore: Double,
    val userTasteScore: Double,       // 25%
    val artistPreferenceScore: Double, // 15%
    val genreSimilarityScore: Double,  // 15%
    val listeningHistoryScore: Double, // 15%
    val currentSongSimilarityScore: Double, // 15%
    val userEngagementScore: Double,   // 10%
    val discoveryScore: Double        // 5%
)

/**
 * Weighted Recommendation Scoring Engine implementing Section 2 requirements:
 *
 * | Recommendation Factor        | Default Weight |
 * | User taste similarity        | 25%            |
 * | Artist preference            | 15%            |
 * | Genre similarity             | 15%            |
 * | Listening history            | 15%            |
 * | Current song similarity      | 15%            |
 * | User engagement              | 10%            |
 * | Popularity and discovery     | 5%             |
 */
class RecommendationScorer(
    private val tasteWeight: Double = 0.25,
    private val artistWeight: Double = 0.15,
    private val genreWeight: Double = 0.15,
    private val historyWeight: Double = 0.15,
    private val currentSongWeight: Double = 0.15,
    private val engagementWeight: Double = 0.10,
    private val discoveryWeight: Double = 0.05
) {
    fun scoreCandidate(
        candidate: Song,
        seedSong: Song,
        tasteProfile: UserTasteProfile,
        diversityMode: String = AppSettings.DIVERSITY_BALANCED,
        recommendationInfluence: String = AppSettings.INFLUENCE_MEDIUM,
        recentSessionArtists: Set<String> = emptySet(),
        recentSearchQueries: List<String> = emptyList(),
        isPersonalizationEnabled: Boolean = true
    ): ScoredCandidate {
        val primaryArtist = MusicSimilarityService.extractPrimaryArtist(candidate.artists)

        // 1. User Taste Similarity (0.0 to 100.0) - 25%
        val candidateGenres = MusicSimilarityService.inferGenres(candidate)
        var tasteMatch = 0.0
        if (candidateGenres.isNotEmpty()) {
            val genreAffinities = candidateGenres.map { tasteProfile.getGenreAffinity(it) }
            tasteMatch = (genreAffinities.maxOrNull() ?: 0.0) * 80.0
        }
        // Preferred language bonus
        val text = "${candidate.title} ${candidate.artists} ${candidate.album}".lowercase()
        if (tasteProfile.preferredLanguages.any { text.contains(it.lowercase()) }) {
            tasteMatch += 20.0
        }
        val userTasteScore = (tasteMatch).coerceIn(0.0, 100.0)

        // 2. Artist Preference (0.0 to 100.0) - 15%
        val artistAffinity = tasteProfile.getArtistAffinity(primaryArtist)
        val artistPreferenceScore = if (isPersonalizationEnabled) {
            (artistAffinity * 100.0).coerceIn(0.0, 100.0)
        } else {
            50.0
        }

        // 3. Genre Similarity with Current Song (0.0 to 100.0) - 15%
        val gSim = MusicSimilarityService.genreSimilarity(candidate, seedSong)
        val genreSimilarityScore = (gSim * 100.0).coerceIn(0.0, 100.0)

        // 4. Listening History (0.0 to 100.0) - 15%
        val listeningHistoryScore = if (isPersonalizationEnabled) {
            var historyVal = 50.0 // neutral baseline
            val plays = tasteProfile.topArtists[primaryArtist] ?: 0
            if (plays > 0) {
                historyVal += minOf(plays * 5.0, 40.0)
            }
            if (candidate.id in tasteProfile.skippedSongIds) {
                historyVal -= 45.0
            }
            historyVal.coerceIn(0.0, 100.0)
        } else {
            50.0
        }

        // 5. Current Song Musical Similarity (0.0 to 100.0) - 15%
        val overallSim = MusicSimilarityService.calculateOverallSimilarity(candidate, seedSong)
        val isSameAlbum = MusicSimilarityService.albumContinuity(candidate, seedSong) > 0.0
        var simVal = overallSim * 85.0
        if (isSameAlbum) simVal += 15.0 // boost tracks from same soundtrack album
        val currentSongSimilarityScore = simVal.coerceIn(0.0, 100.0)

        // 6. User Engagement Signals (0.0 to 100.0) - 10%
        var engagementVal = 40.0
        if (isPersonalizationEnabled && candidate.id in tasteProfile.likedSongIds) {
            engagementVal += 50.0 // Liked song bonus
        }
        if (recentSearchQueries.any { q -> candidate.title.lowercase().contains(q) || candidate.artists.lowercase().contains(q) }) {
            engagementVal += 20.0 // Relevant to recent search intent
        }
        val userEngagementScore = engagementVal.coerceIn(0.0, 100.0)

        // 7. Popularity and Discovery (0.0 to 100.0) - 5%
        val plays = if (isPersonalizationEnabled) (tasteProfile.topArtists[primaryArtist] ?: 0) else 0
        val isNewArtist = plays == 0 && primaryArtist.lowercase() != MusicSimilarityService.extractPrimaryArtist(seedSong.artists).lowercase()
        val discVal = when (diversityMode) {
            AppSettings.DIVERSITY_DISCOVERY -> {
                if (isNewArtist) 100.0 else if (plays > 3) 0.0 else 25.0
            }
            AppSettings.DIVERSITY_FAMILIAR -> if (!isNewArtist) 85.0 else 20.0
            else -> if (isNewArtist) 65.0 else 50.0
        }
        val discoveryScore = discVal.coerceIn(0.0, 100.0)

        // Adjust weights based on diversityMode and influence
        val effTasteWeight: Double
        val effArtistWeight: Double
        val effGenreWeight: Double
        val effHistoryWeight: Double
        val effCurrentSongWeight: Double
        val effEngagementWeight: Double
        val effDiscoveryWeight: Double

        when (diversityMode) {
            AppSettings.DIVERSITY_FAMILIAR -> {
                effTasteWeight = 0.28
                effArtistWeight = 0.22
                effGenreWeight = 0.15
                effHistoryWeight = 0.15
                effCurrentSongWeight = 0.10
                effEngagementWeight = 0.08
                effDiscoveryWeight = 0.02
            }
            AppSettings.DIVERSITY_DISCOVERY -> {
                effTasteWeight = 0.15
                effArtistWeight = 0.04
                effGenreWeight = 0.22
                effHistoryWeight = 0.04
                effCurrentSongWeight = 0.15
                effEngagementWeight = 0.05
                effDiscoveryWeight = 0.35
            }
            else -> {
                effTasteWeight = tasteWeight
                effArtistWeight = artistWeight
                effGenreWeight = genreWeight
                effHistoryWeight = historyWeight
                effCurrentSongWeight = currentSongWeight
                effEngagementWeight = engagementWeight
                effDiscoveryWeight = discoveryWeight
            }
        }

        // Apply influence multiplier
        val influenceMultiplier = when (recommendationInfluence) {
            AppSettings.INFLUENCE_HIGH -> 1.25
            AppSettings.INFLUENCE_LOW -> 0.75
            else -> 1.0
        }

        val baseTotal = (userTasteScore * effTasteWeight) +
                (artistPreferenceScore * effArtistWeight) +
                (genreSimilarityScore * effGenreWeight) +
                (listeningHistoryScore * effHistoryWeight) +
                (currentSongSimilarityScore * effCurrentSongWeight) +
                (userEngagementScore * effEngagementWeight) +
                (discoveryScore * effDiscoveryWeight)

        // Session fatigue penalty: avoid repeatedly recommending the same artist within the session
        var sessionPenalty = 0.0
        if (primaryArtist.lowercase() in recentSessionArtists) {
            sessionPenalty = 15.0
        }

        val finalScore = (baseTotal * influenceMultiplier - sessionPenalty).coerceAtLeast(0.0)

        return ScoredCandidate(
            song = candidate,
            totalScore = finalScore,
            userTasteScore = userTasteScore,
            artistPreferenceScore = artistPreferenceScore,
            genreSimilarityScore = genreSimilarityScore,
            listeningHistoryScore = listeningHistoryScore,
            currentSongSimilarityScore = currentSongSimilarityScore,
            userEngagementScore = userEngagementScore,
            discoveryScore = discoveryScore
        )
    }
}
