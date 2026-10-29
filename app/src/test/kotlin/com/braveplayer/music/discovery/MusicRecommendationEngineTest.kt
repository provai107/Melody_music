package com.braveplayer.music.discovery

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MusicRecommendationEngineTest {

    private lateinit var dbHelper: PlaylistDatabaseHelper
    private lateinit var metadataService: MusicMetadataService
    private lateinit var engine: MusicRecommendationEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        dbHelper = PlaylistDatabaseHelper(context)
        val filterManager = FilterListManager(emptyList())
        metadataService = MusicMetadataService(filterManager)
        engine = MusicRecommendationEngine(metadataService, dbHelper)
    }

    @Test
    fun testCandidateRankingScoring() {
        val seed = Song(
            id = "seed",
            title = "Apna Bana Le",
            artists = "Arijit Singh",
            album = "Bhediya",
            imageUrl = null,
            durationSec = 260,
            mediaUrl = "https://aac.saavncdn.com/seed.mp3",
            mediaUrlDefault = "https://aac.saavncdn.com/seed.mp4",
            previewUrl = null
        )

        val candidateArtistMatch = Song(
            id = "cand_1",
            title = "Channa Mereya",
            artists = "Arijit Singh",
            album = "ADHM",
            imageUrl = null,
            durationSec = 280,
            mediaUrl = "https://aac.saavncdn.com/1.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        val candidateUnrelated = Song(
            id = "cand_2",
            title = "Random Song",
            artists = "Unknown Band",
            album = "Random Album",
            imageUrl = null,
            durationSec = 200,
            mediaUrl = "https://aac.saavncdn.com/2.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        val candidateSkipped = Song(
            id = "cand_3",
            title = "Skipped Arijit Track",
            artists = "Arijit Singh",
            album = "Album",
            imageUrl = null,
            durationSec = 210,
            mediaUrl = "https://aac.saavncdn.com/3.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        val candidates = listOf(candidateUnrelated, candidateSkipped, candidateArtistMatch)

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = candidates,
            excludeSongIds = emptySet(),
            likedIds = emptySet(),
            skippedIds = setOf("cand_3"),
            artistPlayCounts = emptyMap(),
            limit = 10
        )

        // candidateArtistMatch must be ranked highest because of matching artist and no skip penalty
        assertThat(ranked.first().id).isEqualTo("cand_1")
        // candidateSkipped must be penalized below cand_1
        assertThat(ranked).contains(candidateSkipped)
    }

    @Test
    fun testExcludedSongsAndInvalidUrlsAreFiltered() {
        val seed = Song("s", "Seed", "Artist", "Album", null, 200, "https://aac.saavncdn.com/s.mp3", null, null)

        val candValid = Song("v", "Valid", "Artist", "Album", null, 200, "https://aac.saavncdn.com/v.mp3", null, null)
        val candExcluded = Song("e", "Excluded", "Artist", "Album", null, 200, "https://aac.saavncdn.com/e.mp3", null, null)
        val candNoUrl = Song("no_url", "No Stream", "Artist", "Album", null, 200, null, null, null)
        val candSameAsSeed = Song("s", "Seed", "Artist", "Album", null, 200, "https://aac.saavncdn.com/s.mp3", null, null)

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = listOf(candValid, candExcluded, candNoUrl, candSameAsSeed),
            excludeSongIds = setOf("e"),
            likedIds = emptySet(),
            skippedIds = emptySet(),
            artistPlayCounts = emptyMap(),
            limit = 10
        )

        assertThat(ranked).hasSize(1)
        assertThat(ranked[0].id).isEqualTo("v")
    }

    @Test
    fun testDeduplicationByTitleAndArtist() {
        val seed = Song("s", "Seed", "Artist", "Album", null, 200, "https://aac.saavncdn.com/s.mp3", null, null)

        val item1 = Song("id1", "Duplicate Title", "Same Artist", "Album 1", null, 200, "https://aac.saavncdn.com/1.mp3", null, null)
        val item2 = Song("id2", "Duplicate Title", "Same Artist", "Album 2", null, 200, "https://aac.saavncdn.com/2.mp3", null, null)

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = listOf(item1, item2),
            excludeSongIds = emptySet(),
            likedIds = emptySet(),
            skippedIds = emptySet(),
            artistPlayCounts = emptyMap(),
            limit = 10
        )

        assertThat(ranked).hasSize(1)
    }

    @Test
    fun testNotInterestedCandidateIsPermanentlyFiltered() {
        val seed = Song("s", "Seed", "Artist A", "Album", null, 200, "https://aac.saavncdn.com/s.mp3", null, null)
        val cand1 = Song("c1", "Song 1", "Artist A", "Album", null, 200, "https://aac.saavncdn.com/1.mp3", null, null)
        val cand2 = Song("c2", "Song 2", "Artist B", "Album", null, 200, "https://aac.saavncdn.com/2.mp3", null, null)

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = listOf(cand1, cand2),
            excludeSongIds = emptySet(),
            likedIds = emptySet(),
            skippedIds = emptySet(),
            notInterestedIds = setOf("c1")
        )

        assertThat(ranked).noneMatch { it.id == "c1" }
        assertThat(ranked).anyMatch { it.id == "c2" }
    }

    @Test
    fun testDiversityDiscoveryModeBoostsUnfamiliarArtists() {
        val seed = Song("s", "Seed", "Familiar Artist", "Album", null, 200, "https://aac.saavncdn.com/s.mp3", null, null)
        val candFamiliar = Song("c1", "Known Track", "Familiar Artist", "Album", null, 200, "https://aac.saavncdn.com/1.mp3", null, null)
        val candDiscovery = Song("c2", "Fresh Track", "New Independent Artist", "Album", null, 200, "https://aac.saavncdn.com/2.mp3", null, null)

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = listOf(candFamiliar, candDiscovery),
            excludeSongIds = emptySet(),
            likedIds = emptySet(),
            skippedIds = emptySet(),
            artistPlayCounts = mapOf("Familiar Artist" to 15),
            diversityMode = com.braveplayer.music.settings.AppSettings.DIVERSITY_DISCOVERY
        )

        // In discovery mode, unfamiliar artist receives a boost and familiar artist is penalized
        assertThat(ranked.first().id).isEqualTo("c2")
    }

    @Test
    fun testDatabaseHelperRecommendationFeedbackAndReset() {
        val song = Song("test_song", "Test Song", "Test Artist", "Test Album", null, 200, "https://aac.saavncdn.com/t.mp3", null, null)
        
        assertThat(dbHelper.isSongHidden("test_song")).isFalse()
        dbHelper.hideSong(song)
        assertThat(dbHelper.isSongHidden("test_song")).isTrue()
        assertThat(dbHelper.getHiddenSongIds()).contains("test_song")

        dbHelper.unhideSong("test_song")
        assertThat(dbHelper.isSongHidden("test_song")).isFalse()

        dbHelper.recordNotInterested(song)
        assertThat(dbHelper.getNotInterestedSongIds()).contains("test_song")
        assertThat(dbHelper.getNotInterestedArtists()).contains("test artist")

        dbHelper.resetLearnedPreferences()
        assertThat(dbHelper.getNotInterestedSongIds()).isEmpty()
        assertThat(dbHelper.getNotInterestedArtists()).isEmpty()
    }

    @Test
    fun testAntiRepetitionRejectsDerivativeAndIdenticalTitles() {
        val seed = Song(
            id = "seed_1",
            title = "Apna Bana Le",
            artists = "Arijit Singh",
            album = "Bhediya",
            imageUrl = null,
            durationSec = 260,
            mediaUrl = "https://aac.saavncdn.com/seed.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        val candRemix = Song("r1", "Apna Bana Le (Remix)", "Arijit Singh, Sachin-Jigar", "Bhediya", null, 240, "https://aac.saavncdn.com/r1.mp3", null, null)
        val candAcoustic = Song("r2", "Apna Bana Le - Acoustic Version", "Arijit Singh", "Acoustic Hits", null, 250, "https://aac.saavncdn.com/r2.mp3", null, null)
        val candLyrical = Song("r3", "Apna Bana Le [Lyrical Video]", "Arijit Singh", "Bhediya", null, 260, "https://aac.saavncdn.com/r3.mp3", null, null)
        val candSameNameDiffId = Song("r4", "Apna Bana Le", "Arijit Singh", "Top Hits 2023", null, 260, "https://aac.saavncdn.com/r4.mp3", null, null)
        val candFreshSong = Song("f1", "Kesariya", "Arijit Singh", "Brahmastra", null, 270, "https://aac.saavncdn.com/f1.mp3", null, null)

        val candidates = listOf(candRemix, candAcoustic, candLyrical, candSameNameDiffId, candFreshSong)

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = candidates,
            excludeSongIds = emptySet(),
            excludeTitles = emptySet(),
            likedIds = emptySet(),
            skippedIds = emptySet(),
            artistPlayCounts = emptyMap(),
            limit = 10
        )

        // Only Kesariya must survive; all Apna Bana Le versions must be strictly rejected
        assertThat(ranked).hasSize(1)
        assertThat(ranked.first().id).isEqualTo("f1")
        assertThat(ranked.first().title).isEqualTo("Kesariya")
    }

    @Test
    fun testExcludeTitlesFiltersQueuedSongs() {
        val seed = Song("s1", "Ilahi", "Arijit Singh", "YJHD", null, 200, "https://aac.saavncdn.com/s1.mp3", null, null)

        val candInQueue = Song("c1", "Tum Hi Ho (From Aashiqui 2)", "Arijit Singh", "Aashiqui 2", null, 260, "https://aac.saavncdn.com/c1.mp3", null, null)
        val candNotInQueue = Song("c2", "Raabta", "Arijit Singh", "Agent Vinod", null, 240, "https://aac.saavncdn.com/c2.mp3", null, null)

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = listOf(candInQueue, candNotInQueue),
            excludeSongIds = emptySet(),
            excludeTitles = setOf("Tum Hi Ho"),
            likedIds = emptySet(),
            skippedIds = emptySet()
        )

        assertThat(ranked).hasSize(1)
        assertThat(ranked.first().id).isEqualTo("c2")
        assertThat(ranked.first().title).isEqualTo("Raabta")
    }

    @Test
    fun testSameGenreAndEraAffinityScoring() {
        // Bollywood Romantic Song from 2022
        val seed = Song(
            id = "s_era",
            title = "Kesariya",
            artists = "Arijit Singh, Pritam",
            album = "Brahmastra",
            imageUrl = null,
            durationSec = 268,
            mediaUrl = "https://aac.saavncdn.com/kes.mp3",
            mediaUrlDefault = null,
            previewUrl = null,
            year = "2022"
        )

        // Matching genre (Bollywood romantic) and matching era (2021)
        val candSameEraGenre = Song(
            id = "cand_close",
            title = "Rataan Lambiyan",
            artists = "Jubin Nautiyal, Tanishk Bagchi",
            album = "Shershaah",
            imageUrl = null,
            durationSec = 230,
            mediaUrl = "https://aac.saavncdn.com/close.mp3",
            mediaUrlDefault = null,
            previewUrl = null,
            year = "2021"
        )

        // Distant era (1985) and non-matching genre (Classical)
        val candDistant = Song(
            id = "cand_distant",
            title = "Classical Concerto Symphony",
            artists = "Orchestra",
            album = "Symphony Collection",
            imageUrl = null,
            durationSec = 300,
            mediaUrl = "https://aac.saavncdn.com/dist.mp3",
            mediaUrlDefault = null,
            previewUrl = null,
            year = "1985"
        )

        val ranked = engine.rankCandidates(
            seedSong = seed,
            candidates = listOf(candDistant, candSameEraGenre),
            excludeSongIds = emptySet()
        )

        assertThat(ranked.first().id).isEqualTo("cand_close")
    }
}
