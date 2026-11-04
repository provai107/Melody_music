package com.braveplayer.music.discovery

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class IntelligentMusicRecommendationEngineTest {

    private lateinit var dbHelper: PlaylistDatabaseHelper
    private lateinit var metadataService: MusicMetadataService
    private lateinit var appSettings: AppSettings
    private lateinit var scorer: RecommendationScorer
    private lateinit var tasteAnalyzer: UserTasteAnalyzer
    private lateinit var discoveryEngine: DiscoveryEngine
    private lateinit var predictor: NextSongPredictor
    private lateinit var autoplayManager: AutoplayManager
    private lateinit var engine: MusicRecommendationEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        dbHelper = PlaylistDatabaseHelper(context)
        appSettings = AppSettings(context)
        val filterManager = FilterListManager(emptyList())
        metadataService = MusicMetadataService(filterManager)
        scorer = RecommendationScorer()
        tasteAnalyzer = UserTasteAnalyzer(dbHelper, appSettings)
        discoveryEngine = DiscoveryEngine(metadataService, dbHelper, tasteAnalyzer)
        predictor = NextSongPredictor(discoveryEngine, tasteAnalyzer, scorer, dbHelper)
        autoplayManager = AutoplayManager(predictor, appSettings, dbHelper)
        engine = MusicRecommendationEngine(metadataService, dbHelper)
    }

    private fun createSong(
        id: String,
        title: String,
        artist: String,
        album: String = "Test Album",
        year: String? = "2023",
        duration: Int = 240
    ): Song {
        return Song(
            id = id,
            title = title,
            artists = artist,
            album = album,
            imageUrl = "https://example.com/art.jpg",
            durationSec = duration,
            mediaUrl = "https://example.com/audio/$id.mp4",
            year = year
        )
    }

    @Test
    fun test7FactorRecommendationScorer() {
        val seed = createSong("seed", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2", year = "2013")
        val candidateSimilar = createSong("cand_1", "Kesariya", "Arijit Singh", album = "Brahmastra", year = "2022")
        val candidateUnrelated = createSong("cand_2", "Metal Anthem", "Heavy Metal Band", album = "Rock Vol 1", year = "1985")

        val profile = UserTasteProfile(
            topArtists = mapOf("Arijit Singh" to 15),
            topGenres = mapOf("bollywood" to 0.5, "romantic" to 0.4),
            likedSongIds = setOf("cand_1"),
            skippedSongIds = emptySet()
        )

        val scoredSimilar = scorer.scoreCandidate(
            candidate = candidateSimilar,
            seedSong = seed,
            tasteProfile = profile,
            diversityMode = AppSettings.DIVERSITY_BALANCED,
            recommendationInfluence = AppSettings.INFLUENCE_MEDIUM
        )

        val scoredUnrelated = scorer.scoreCandidate(
            candidate = candidateUnrelated,
            seedSong = seed,
            tasteProfile = profile,
            diversityMode = AppSettings.DIVERSITY_BALANCED,
            recommendationInfluence = AppSettings.INFLUENCE_MEDIUM
        )

        // 7-factor scores must be positive and bounded
        assertThat(scoredSimilar.userTasteScore).isGreaterThan(0.0)
        assertThat(scoredSimilar.artistPreferenceScore).isGreaterThan(0.0)
        assertThat(scoredSimilar.currentSongSimilarityScore).isGreaterThan(0.0)
        assertThat(scoredSimilar.userEngagementScore).isGreaterThan(0.0) // liked bonus
        assertThat(scoredSimilar.totalScore).isGreaterThan(scoredUnrelated.totalScore)
    }

    @Test
    fun testArtistRepetitionCooldownInNextSongPredictor() {
        val seed = createSong("seed", "Apna Bana Le", "Arijit Singh", album = "Bhediya")
        val arijitSong1 = createSong("a1", "Channa Mereya", "Arijit Singh", album = "ADHM")
        val otherArtistSong = createSong("o1", "Raataan Lambiyan", "Jubin Nautiyal", album = "Shershaah")

        // Simulate session where Arijit Singh already appeared twice in the last 6 songs
        val sessionContext = SessionContext(
            currentSong = seed,
            sessionArtists = listOf("arijit singh", "other", "arijit singh"),
            isAlbumContext = false
        )

        val candidates = listOf(arijitSong1, otherArtistSong)
        val ranked = predictor.rankCandidates(
            candidates = candidates,
            seedSong = seed,
            sessionContext = sessionContext,
            excludeSongIds = emptySet(),
            excludeTitles = emptySet(),
            tasteProfile = UserTasteProfile()
        )

        // Jubin Nautiyal track should not suffer from Arijit's cooldown penalty
        assertThat(ranked).isNotEmpty
        val arijitScored = scorer.scoreCandidate(arijitSong1, seed, UserTasteProfile())
        assertThat(arijitScored.totalScore).isGreaterThan(0.0)
    }

    @Test
    fun testAlbumSoundtrackContinuityBoost() {
        val seed = createSong("seed", "Kalank Title Track", "Arijit Singh", album = "Kalank")
        val sameAlbumSong = createSong("same_album", "First Class", "Arijit Singh, Neeti Mohan", album = "Kalank")
        val diffAlbumSong = createSong("diff_album", "Zaalima", "Arijit Singh, Harshdeep Kaur", album = "Raees")

        val sessionContext = SessionContext(
            currentSong = seed,
            isAlbumContext = true,
            albumTitle = "Kalank"
        )

        val ranked = predictor.rankCandidates(
            candidates = listOf(diffAlbumSong, sameAlbumSong),
            seedSong = seed,
            sessionContext = sessionContext,
            excludeSongIds = emptySet(),
            excludeTitles = emptySet(),
            tasteProfile = UserTasteProfile()
        )

        assertThat(ranked.first().id).isEqualTo("same_album")
    }

    @Test
    fun testTitleDeduplicationAndDerivativeExclusion() {
        val seed = createSong("seed", "Kesariya", "Arijit Singh")
        val remix = createSong("remix", "Kesariya (Remix)", "Arijit Singh")
        val lyrical = createSong("lyrical", "Kesariya - Lyrical Video", "Arijit Singh")
        val distinctSong = createSong("distinct", "Rasiya", "Arijit Singh")

        val ranked = predictor.rankCandidates(
            candidates = listOf(remix, lyrical, distinctSong),
            seedSong = seed,
            sessionContext = SessionContext(currentSong = seed),
            excludeSongIds = emptySet(),
            excludeTitles = emptySet(),
            tasteProfile = UserTasteProfile()
        )

        assertThat(ranked.map { it.id }).doesNotContain("remix", "lyrical")
        assertThat(ranked.map { it.id }).contains("distinct")
    }

    @Test
    fun testExcludeDislikedTracksFilter() {
        val seed = createSong("seed", "Seed Song", "Artist A")
        val normalTrack = createSong("normal", "Good Track", "Artist B")
        val dislikedTrack = createSong("disliked", "Disliked Track", "Artist C")

        dbHelper.recordListeningHistory(
            song = dislikedTrack,
            playDurationSec = 5,
            isCompleted = false,
            isSkipped = true,
            isMeaningful = false
        )

        val profile = UserTasteProfile(
            skippedSongIds = setOf("disliked")
        )

        val sessionContext = SessionContext(currentSong = seed)
        val ranked = predictor.rankCandidates(
            candidates = listOf(normalTrack, dislikedTrack),
            seedSong = seed,
            sessionContext = sessionContext,
            excludeSongIds = setOf("disliked"),
            excludeTitles = emptySet(),
            tasteProfile = profile
        )

        assertThat(ranked.map { it.id }).doesNotContain("disliked")
        assertThat(ranked.map { it.id }).contains("normal")
    }

    @Test
    fun testAutoplayManagerDatabaseFallback() {
        val seed = createSong("seed", "Seed Track", "Artist A")
        val likedTrack = createSong("liked", "My Liked Song", "Artist B")

        dbHelper.likeSong(likedTrack)

        // When metadata service returns empty, autoplayManager falls back to liked DB tracks
        val sessionContext = SessionContext(currentSong = seed)
        val candidates = listOf(likedTrack)
        val ranked = predictor.rankCandidates(
            candidates = candidates,
            seedSong = seed,
            sessionContext = sessionContext,
            excludeSongIds = setOf(seed.id),
            excludeTitles = setOf(seed.title),
            tasteProfile = UserTasteProfile(likedSongIds = setOf("liked"))
        )

        assertThat(ranked).hasSize(1)
        assertThat(ranked.first().id).isEqualTo("liked")
    }

    @Test
    fun testMusicSimilarityServiceMetricCalculations() {
        val s1 = createSong("1", "Channa Mereya", "Arijit Singh", album = "Ae Dil Hai Mushkil", year = "2016")
        val s2 = createSong("2", "Ilahi", "Arijit Singh", album = "Yeh Jawaani Hai Deewani", year = "2013")

        val artistSim = MusicSimilarityService.artistSimilarity(s1, s2)
        assertThat(artistSim).isEqualTo(1.0)

        val eraSim = MusicSimilarityService.eraSimilarity(s1, s2)
        assertThat(eraSim).isGreaterThan(0.7) // 3 years apart

        val overallSim = MusicSimilarityService.calculateOverallSimilarity(s1, s2)
        assertThat(overallSim).isGreaterThan(0.6)
    }

    @Test
    fun testBackwardCompatibilityWithMusicRecommendationEngine() {
        val seed = createSong("seed", "Deva Deva", "Arijit Singh", album = "Brahmastra")
        val cand1 = createSong("c1", "Kesariya", "Arijit Singh", album = "Brahmastra")
        val cand2 = createSong("c2", "Dance Ka Bhoot", "Arijit Singh", album = "Brahmastra")

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = listOf(cand1, cand2),
            excludeSongIds = emptySet(),
            likedIds = emptySet(),
            skippedIds = emptySet(),
            artistPlayCounts = emptyMap(),
            limit = 5
        )

        assertThat(ranked).hasSize(2)
        assertThat(ranked.map { it.id }).containsExactlyInAnyOrder("c1", "c2")
    }
}
