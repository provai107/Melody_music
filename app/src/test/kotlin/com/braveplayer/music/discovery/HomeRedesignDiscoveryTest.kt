package com.braveplayer.music.discovery

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.model.GenreCardTheme
import com.braveplayer.music.model.ReleaseItem
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HomeRedesignDiscoveryTest {

    private lateinit var dbHelper: PlaylistDatabaseHelper
    private lateinit var metadataService: MusicMetadataService
    private lateinit var genreEngine: GenrePersonalizationEngine
    private lateinit var releasesRepo: LatestReleasesRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        dbHelper = PlaylistDatabaseHelper(context)
        val filterManager = FilterListManager(emptyList())
        metadataService = MusicMetadataService(filterManager)
        genreEngine = GenrePersonalizationEngine(dbHelper)
        releasesRepo = LatestReleasesRepository(metadataService, dbHelper)
    }

    @Test
    fun testGenreThemesCatalogValidity() {
        val themes = MusicCatalog.GENRE_THEMES
        assertThat(themes).isNotEmpty
        assertThat(themes.size).isGreaterThanOrEqualTo(12)

        for (theme in themes) {
            assertThat(theme.id).isNotBlank()
            assertThat(theme.displayName).isNotBlank()
            assertThat(theme.subtitle).isNotBlank()
            assertThat(theme.emoji).isNotBlank()
            assertThat(theme.query).isNotBlank()
            assertThat(theme.backgroundColorHex).startsWith("#")
            assertThat(theme.backgroundColorHex.length).isIn(7, 9)
        }

        val rockTheme = MusicCatalog.findThemeForId("rock")
        assertThat(rockTheme.id).isEqualTo("rock")
        assertThat(rockTheme.displayName).isEqualTo("Rock")

        val fallbackTheme = MusicCatalog.findThemeForId("unknown_genre_123")
        assertThat(fallbackTheme).isNotNull
    }

    @Test
    fun testGenrePersonalizationGracefulFallbackForNewUser() {
        val favouriteGenres = genreEngine.getFavouriteGenres(limit = 6)
        assertThat(favouriteGenres).hasSize(6)

        // All IDs must be unique
        val ids = favouriteGenres.map { it.id }.toSet()
        assertThat(ids).hasSize(6)
    }

    @Test
    fun testGenrePersonalizationRanksLikedAndPlayedGenres() {
        val rockSong1 = Song(
            id = "rock_1",
            title = "In The End - Rock Anthem",
            artists = "Linkin Park",
            album = "Hybrid Theory",
            imageUrl = null,
            durationSec = 216,
            mediaUrl = "https://audio.cdn/rock1.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )
        val rockSong2 = Song(
            id = "rock_2",
            title = "Numb Rock",
            artists = "Linkin Park",
            album = "Meteora",
            imageUrl = null,
            durationSec = 187,
            mediaUrl = "https://audio.cdn/rock2.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )
        val punjabiSong = Song(
            id = "punjabi_1",
            title = "Lover Punjabi Urban",
            artists = "Diljit Dosanjh",
            album = "MoonChild Era",
            imageUrl = null,
            durationSec = 190,
            mediaUrl = "https://audio.cdn/p1.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        dbHelper.likeSong(rockSong1)
        dbHelper.likeSong(rockSong2)
        dbHelper.recordListeningHistory(rockSong1, playDurationSec = 210, isCompleted = true, isSkipped = false, isMeaningful = true)
        dbHelper.recordListeningHistory(punjabiSong, playDurationSec = 190, isCompleted = true, isSkipped = false, isMeaningful = true)

        val favourites = genreEngine.getFavouriteGenres(limit = 6)
        assertThat(favourites).isNotEmpty

        val topIds = favourites.take(2).map { it.id }
        assertThat(topIds).containsAnyOf("rock", "punjabi")
    }

    @Test
    fun testRecommendedGenresExcludesFavouritesAndProvidesDiversity() {
        val favouriteGenres = genreEngine.getFavouriteGenres(limit = 6)
        val favIds = favouriteGenres.map { it.id }.toSet()

        val recommended = genreEngine.getRecommendedGenres(limit = 6, excludedGenreIds = favIds)
        assertThat(recommended).isNotEmpty

        for (rec in recommended) {
            assertThat(favIds).doesNotContain(rec.id)
        }
    }

    @Test
    fun testRecommendedGenresTimeOfDayCues() {
        val morning = genreEngine.getRecommendedGenres(limit = 4, currentHour = 8)
        val morningIds = morning.map { it.id }
        assertThat(morningIds).containsAnyOf("acoustic", "chill", "classical", "indie")

        val night = genreEngine.getRecommendedGenres(limit = 4, currentHour = 23)
        val nightIds = night.map { it.id }
        assertThat(nightIds).containsAnyOf("lofi", "chill", "acoustic", "focus")
    }

    @Test
    fun testPopularArtistsCombinesHistoryAndCurated() {
        val playedSong = Song(
            id = "test_played",
            title = "Arijit Hit",
            artists = "Arijit Singh",
            album = "Album",
            imageUrl = null,
            durationSec = 200,
            mediaUrl = "https://audio.cdn/a.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )
        dbHelper.recordListeningHistory(playedSong, playDurationSec = 200, isCompleted = true, isSkipped = false, isMeaningful = true)

        val popularArtists = genreEngine.getPopularArtists(limit = 10)
        assertThat(popularArtists).isNotEmpty
        assertThat(popularArtists.size).isLessThanOrEqualTo(10)

        val names = popularArtists.map { it.name.lowercase() }
        assertThat(names).contains("arijit singh")

        // No duplicates
        val uniqueNames = names.toSet()
        assertThat(uniqueNames.size).isEqualTo(names.size)
    }

    @Test
    fun testLatestReleasesOfflineFallbackAndTagDerivation() {
        runBlocking {
            val song2026 = Song(
            id = "rel_2026",
            title = "Fresh Track 2026",
            artists = "Star Artist",
            album = "Global 2026",
            imageUrl = "https://img.cdn/art.jpg",
            durationSec = 180,
            mediaUrl = "https://audio.cdn/s.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )
        val albumSong = Song(
            id = "rel_album",
            title = "Intro Track",
            artists = "Band Name",
            album = "Super Album Release",
            imageUrl = null,
            durationSec = 210,
            mediaUrl = "https://audio.cdn/alb.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        dbHelper.recordListeningHistory(song2026, playDurationSec = 180, isCompleted = true, isSkipped = false, isMeaningful = true)
        dbHelper.likeSong(albumSong)

        // When backend search fails or returns empty, fallback engages
        val releases = releasesRepo.fetchLatestReleases(baseUrl = "https://invalid-non-existent-backend.internal")
        assertThat(releases).isNotEmpty

        val item2026 = releases.firstOrNull { it.id == "rel_2026" }
        assertThat(item2026).isNotNull
        assertThat(item2026?.releaseTag).isEqualTo("2026")

        val cached = releasesRepo.getCachedReleases()
        assertThat(cached).isEqualTo(releases)
        }
    }

    @Test
    fun testGenrePersonalizationPrioritizesUserSelectedGenres() {
        val selected = setOf("Rock", "EDM", "Indie")
        val favouriteGenres = genreEngine.getFavouriteGenres(limit = 6, userSelectedGenres = selected)
        assertThat(favouriteGenres).isNotEmpty
        val topIds = favouriteGenres.take(3).map { it.id }
        assertThat(topIds).containsExactly("rock", "edm", "indie")
    }

    @Test
    fun testPopularArtistsPrioritizesUserSelectedSingers() {
        val selectedSingers = setOf("Darshan Raval", "Atif Aslam", "Taylor Swift")
        val artists = genreEngine.getPopularArtists(limit = 10, userSelectedSingers = selectedSingers)
        assertThat(artists).isNotEmpty
        val topNames = artists.take(3).map { it.name }
        assertThat(topNames).contains("Darshan Raval", "Atif Aslam", "Taylor Swift")
        val favItem = artists.first { it.name == "Darshan Raval" }
        assertThat(favItem.role).isEqualTo("Favourite Artist")
        assertThat(favItem.imageUrl).isEqualTo("https://c.saavncdn.com/artists/Darshan_Raval_006_20250807060352_500x500.jpg")

        val atifItem = artists.first { it.name == "Atif Aslam" }
        assertThat(atifItem.imageUrl).isEqualTo("https://c.saavncdn.com/180/Atif-Aslam-Hindi-2019-20190401163644-500x500.jpg")
    }

    @Test
    fun testLatestReleasesStrictYearFiltering() {
        val song2026 = Song("s1", "Title 2026", "Artist", "Album", null, 200, "https://a.com/1.mp3", null, null, year = "2026")
        val song2025 = Song("s2", "Title 2025", "Artist", "Album", null, 200, "https://a.com/2.mp3", null, null, releaseDate = "2025-08-10")
        val song2024 = Song("s3", "Old Title", "Artist", "Album", null, 200, "https://a.com/3.mp3", null, null, year = "2024")
        val song2019 = Song("s4", "Evergreen Hit 2019", "Artist", "Album 2019", null, 200, "https://a.com/4.mp3", null, null, year = "2019")
        val songNoYearOld = Song("s5", "Old Hit 2018", "Artist", "Album", null, 200, "https://a.com/5.mp3", null, null)
        val songCompilationWith2026 = Song("s6", "Hit Song", "Artist", "Hits 2026 Compilation", null, 200, "https://a.com/6.mp3", null, null, year = "2022")

        assertThat(LatestReleasesRepository.isRecentRelease(song2026)).isTrue()
        assertThat(LatestReleasesRepository.isRecentRelease(song2025)).isTrue()
        assertThat(LatestReleasesRepository.isRecentRelease(song2024)).isFalse()
        assertThat(LatestReleasesRepository.isRecentRelease(song2019)).isFalse()
        assertThat(LatestReleasesRepository.isRecentRelease(songNoYearOld)).isFalse()
        assertThat(LatestReleasesRepository.isRecentRelease(songCompilationWith2026)).isFalse()
    }
}
