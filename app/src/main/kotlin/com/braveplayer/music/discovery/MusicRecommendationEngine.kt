package com.braveplayer.music.discovery

import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-signal intelligent recommendation engine computing candidate relevance
 * using the 7-factor weighted scoring algorithm:
 * - User taste similarity (25%)
 * - Artist preference (15%)
 * - Genre similarity (15%)
 * - Listening history (15%)
 * - Current song similarity (15%)
 * - User engagement (10%)
 * - Popularity and discovery (5%)
 *
 * Backed by modular engines: [DiscoveryEngine], [NextSongPredictor], [RecommendationScorer],
 * and [UserTasteAnalyzer].
 */
class MusicRecommendationEngine(
    private val metadataService: MusicMetadataService,
    private val dbHelper: PlaylistDatabaseHelper
) {

    private val tasteAnalyzer = UserTasteAnalyzer(dbHelper)
    private val discoveryEngine = DiscoveryEngine(metadataService, dbHelper, tasteAnalyzer)
    private val recommendationScorer = RecommendationScorer()
    private val nextSongPredictor = NextSongPredictor(discoveryEngine, tasteAnalyzer, recommendationScorer, dbHelper)

    val predictor: NextSongPredictor get() = nextSongPredictor
    val discovery: DiscoveryEngine get() = discoveryEngine
    val scorer: RecommendationScorer get() = recommendationScorer
    val analyzer: UserTasteAnalyzer get() = tasteAnalyzer

    companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        fun normalizeTitle(rawTitle: String): String = MusicSimilarityService.normalizeTitle(rawTitle)
        fun areTitlesTooSimilar(titleA: String, titleB: String): Boolean = MusicSimilarityService.areTitlesTooSimilar(titleA, titleB)
        fun inferGenres(song: Song): Set<String> = MusicSimilarityService.inferGenres(song)
        fun parseSongYear(song: Song): Int? = MusicSimilarityService.parseSongYear(song)
        fun extractPrimaryArtist(artists: String): String = MusicSimilarityService.extractPrimaryArtist(artists)
        fun extractSecondaryArtist(artists: String): String? = MusicSimilarityService.extractSecondaryArtist(artists)
    }

    /**
     * Retrieves ranked recommendations based on a seed song, session context, and user preferences.
     */
    suspend fun getRecommendations(
        baseUrl: String,
        seedSong: Song,
        excludeSongIds: Set<String> = emptySet(),
        excludeTitles: Set<String> = emptySet(),
        limit: Int = 10,
        personalizationEnabled: Boolean = true,
        avoidRecentlyPlayed: Boolean = true,
        diversityMode: String = AppSettings.DIVERSITY_BALANCED,
        recommendationInfluence: String = AppSettings.INFLUENCE_MEDIUM,
        excludeDislikedTracks: Boolean = true,
        sessionContext: SessionContext = SessionContext(currentSong = seedSong),
        additionalCandidates: List<Song> = emptyList()
    ): List<Song> = withContext(Dispatchers.IO) {
        nextSongPredictor.predictNextSongs(
            count = limit,
            baseUrl = baseUrl,
            seedSong = seedSong,
            sessionContext = sessionContext,
            upcomingQueueSongIds = excludeSongIds,
            upcomingQueueTitles = excludeTitles,
            additionalCandidates = additionalCandidates,
            personalizationEnabled = personalizationEnabled,
            avoidRecentlyPlayed = avoidRecentlyPlayed,
            diversityMode = diversityMode,
            recommendationInfluence = recommendationInfluence,
            excludeDislikedTracks = excludeDislikedTracks
        )
    }

    suspend fun fetchCandidateSongs(baseUrl: String, query: String): List<Song> =
        discoveryEngine.fetchCandidateSongs(baseUrl, query)

    /**
     * Ranks candidates using the modular 7-factor weighted scoring and artist cooldown logic.
     */
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
        recommendationInfluence: String = AppSettings.INFLUENCE_MEDIUM,
        personalizationEnabled: Boolean = true,
        sessionContext: SessionContext = SessionContext(currentSong = seedSong)
    ): List<Song> {
        val tasteProfile = UserTasteProfile(
            topArtists = artistPlayCounts,
            likedSongIds = likedIds,
            skippedSongIds = skippedIds,
            notInterestedSongIds = notInterestedIds,
            notInterestedArtists = notInterestedArtists
        )
        return nextSongPredictor.rankCandidates(
            candidates = candidates,
            seedSong = seedSong,
            sessionContext = sessionContext,
            excludeSongIds = excludeSongIds,
            excludeTitles = excludeTitles,
            tasteProfile = tasteProfile,
            limit = limit,
            diversityMode = diversityMode,
            recommendationInfluence = recommendationInfluence,
            recentSearchQueries = recentSearchQueries,
            personalizationEnabled = personalizationEnabled
        )
    }

    fun extractPrimaryArtist(artists: String): String = MusicSimilarityService.extractPrimaryArtist(artists)

    fun extractSecondaryArtist(artists: String): String? = MusicSimilarityService.extractSecondaryArtist(artists)

    fun clearCache() {
        discoveryEngine.clearCache()
    }
}
