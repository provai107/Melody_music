package com.braveplayer.music.discovery

import com.braveplayer.music.model.ArtistProfile
import com.braveplayer.music.model.ArtistSortOption
import com.braveplayer.music.model.ArtistSongSorter
import com.braveplayer.music.model.SearchResponseParser
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.util.ArtistClickHelper
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ArtistDiscoveryTest {

    private lateinit var dbHelper: PlaylistDatabaseHelper

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        dbHelper = PlaylistDatabaseHelper(context)
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    private fun createSong(
        id: String,
        title: String,
        releaseDate: String? = null,
        playCount: Long? = null,
        year: String? = null
    ): Song {
        return Song(
            id = id,
            title = title,
            artists = "Singer",
            album = "Album",
            imageUrl = null,
            durationSec = 200,
            mediaUrl = "https://example.com/$id.m4a",
            mediaUrlDefault = null,
            previewUrl = null,
            releaseDate = releaseDate,
            playCount = playCount,
            year = year
        )
    }

    @Test
    fun testArtistSongSortingByReleaseDate() {
        val songNewest = createSong(id = "1", title = "Track 2026", releaseDate = "2026-06-15")
        val song2024Mid = createSong(id = "2", title = "Track 2024 Mid", releaseDate = "2024-05-10")
        val song2024Early = createSong(id = "3", title = "Track 2024 Early", releaseDate = "2024-01-01")
        val songYearOnly = createSong(id = "4", title = "Track 2020", year = "2020")
        val songNoDate = createSong(id = "5", title = "Track Undated")

        val unsorted = listOf(songYearOnly, songNoDate, song2024Mid, songNewest, song2024Early)
        val sorted = ArtistSongSorter.sort(unsorted, ArtistSortOption.RELEASE_DATE)

        assertThat(sorted.map { it.id }).containsExactly("1", "2", "3", "4", "5")
    }

    @Test
    fun testArtistSongSortingAlphabetical() {
        val s1 = createSong(id = "1", title = "Believer")
        val s2 = createSong(id = "2", title = "airplane")
        val s3 = createSong(id = "3", title = "Zebra")
        val s4 = createSong(id = "4", title = "Café")
        val s5 = createSong(id = "5", title = "believer") // identical title tie-break by id

        val sorted = ArtistSongSorter.sort(listOf(s3, s1, s4, s2, s5), ArtistSortOption.ALPHABETICAL)
        assertThat(sorted.map { it.id }).containsExactly("2", "1", "5", "4", "3")
    }

    @Test
    fun testArtistSongSortingByPopularity() {
        val s1 = createSong(id = "1", title = "Mid", playCount = 50_000L)
        val s2 = createSong(id = "2", title = "Viral", playCount = 20_000_000L)
        val s3 = createSong(id = "3", title = "Low", playCount = 1_000L)
        val s4 = createSong(id = "4", title = "Zero", playCount = 0L)
        val s5 = createSong(id = "5", title = "Unknown", playCount = null)

        val sorted = ArtistSongSorter.sort(listOf(s1, s5, s3, s2, s4), ArtistSortOption.POPULARITY)
        assertThat(sorted.map { it.id }).containsExactly("2", "1", "3", "4", "5")
    }

    @Test
    fun testArtistSongSortingImmutability() {
        val original = listOf(
            createSong(id = "b", title = "B"),
            createSong(id = "a", title = "A")
        )
        val sorted = ArtistSongSorter.sort(original, ArtistSortOption.ALPHABETICAL)
        assertThat(original[0].id).isEqualTo("b")
        assertThat(sorted[0].id).isEqualTo("a")
    }

    @Test
    fun testArtistClickHelperSplitting() {
        assertThat(ArtistClickHelper.splitArtistNames("Arijit Singh, Shreya Ghoshal"))
            .containsExactly("Arijit Singh", "Shreya Ghoshal")

        assertThat(ArtistClickHelper.splitArtistNames("Coldplay & BTS"))
            .containsExactly("Coldplay", "BTS")

        assertThat(ArtistClickHelper.splitArtistNames("Justin Bieber feat. Ludacris"))
            .containsExactly("Justin Bieber", "Ludacris")

        assertThat(ArtistClickHelper.splitArtistNames("Drake ft. Rihanna"))
            .containsExactly("Drake", "Rihanna")

        assertThat(ArtistClickHelper.splitArtistNames("Marshmello x Khalid"))
            .containsExactly("Marshmello", "Khalid")

        assertThat(ArtistClickHelper.splitArtistNames("Taylor Swift / Post Malone"))
            .containsExactly("Taylor Swift", "Post Malone")

        assertThat(ArtistClickHelper.splitArtistNames("Adele"))
            .containsExactly("Adele")

        assertThat(ArtistClickHelper.splitArtistNames(""))
            .isEmpty()

        assertThat(ArtistClickHelper.splitArtistNames("   "))
            .isEmpty()
    }

    @Test
    fun testFollowedArtistsDatabaseOperations() {
        assertThat(dbHelper.getFollowedArtistCount()).isEqualTo(0)
        assertThat(dbHelper.isArtistFollowed("art_coldplay")).isFalse()

        // Follow artist 1
        dbHelper.followArtist("art_coldplay", "Coldplay", "https://img.saavn.com/coldplay_500x500.jpg")
        assertThat(dbHelper.isArtistFollowed("art_coldplay")).isTrue()
        assertThat(dbHelper.getFollowedArtistCount()).isEqualTo(1)

        // Follow artist 2
        dbHelper.followArtist("art_dualipa", "Dua Lipa", null)
        assertThat(dbHelper.isArtistFollowed("art_dualipa")).isTrue()
        assertThat(dbHelper.getFollowedArtistCount()).isEqualTo(2)

        val followedList = dbHelper.getAllFollowedArtists()
        assertThat(followedList).hasSize(2)
        val names = followedList.map { it.name }
        assertThat(names).contains("Coldplay", "Dua Lipa")

        // Unfollow artist 1
        dbHelper.unfollowArtist("art_coldplay")
        assertThat(dbHelper.isArtistFollowed("art_coldplay")).isFalse()
        assertThat(dbHelper.isArtistFollowed("art_dualipa")).isTrue()
        assertThat(dbHelper.getFollowedArtistCount()).isEqualTo(1)

        // Unfollow artist 2
        dbHelper.unfollowArtist("art_dualipa")
        assertThat(dbHelper.isArtistFollowed("art_dualipa")).isFalse()
        assertThat(dbHelper.getFollowedArtistCount()).isEqualTo(0)
    }

    @Test
    fun testSearchResponseParserArtistSearch() {
        val json = """
            {
                "results": [
                    {
                        "id": "459320",
                        "name": "Arijit Singh",
                        "role": "Singer",
                        "image": "https://c.saavncdn.com/artists/Arijit_Singh_50x50.jpg"
                    },
                    {
                        "id": "456269",
                        "name": "Shreya Ghoshal",
                        "role": "Singer",
                        "image": "https://c.saavncdn.com/artists/Shreya_Ghoshal_150x150.jpg"
                    }
                ]
            }
        """.trimIndent()

        val results = SearchResponseParser.parseArtistSearchResults(json)
        assertThat(results).hasSize(2)
        assertThat(results[0].id).isEqualTo("459320")
        assertThat(results[0].name).isEqualTo("Arijit Singh")
        assertThat(results[0].role).isEqualTo("Singer")
        assertThat(results[0].imageUrl).isEqualTo("https://c.saavncdn.com/artists/Arijit_Singh_500x500.jpg")

        assertThat(results[1].id).isEqualTo("456269")
        assertThat(results[1].name).isEqualTo("Shreya Ghoshal")
        assertThat(results[1].imageUrl).isEqualTo("https://c.saavncdn.com/artists/Shreya_Ghoshal_500x500.jpg")
    }

    @Test
    fun testSearchResponseParserArtistDetails() {
        val json = """
            {
                "artistId": "459320",
                "name": "Arijit Singh",
                "image": "https://c.saavncdn.com/artists/Arijit_Singh_150x150.jpg",
                "follower_count": "108800000",
                "fan_count": "9400000",
                "isVerified": true,
                "bio": [
                    {"title": "Overview", "text": "Arijit Singh is a celebrated Indian playback singer."}
                ],
                "topSongs": [
                    {
                        "id": "song_1",
                        "song": "Tum Hi Ho",
                        "singers": "Arijit Singh",
                        "media_url": "https://example.com/tum.m4a",
                        "image": "https://c.saavncdn.com/tum_50x50.jpg",
                        "duration": "262",
                        "year": "2013",
                        "release_date": "2013-04-08",
                        "play_count": "500000000"
                    }
                ],
                "topAlbums": [
                    {
                        "id": "alb_1",
                        "title": "Aashiqui 2",
                        "year": "2013",
                        "image": "https://c.saavncdn.com/aashiqui_150x150.jpg"
                    }
                ],
                "similarArtists": [
                    {
                        "id": "similar_1",
                        "name": "Atif Aslam",
                        "role": "Singer",
                        "image": "https://c.saavncdn.com/atif_50x50.jpg"
                    }
                ]
            }
        """.trimIndent()

        val profile = SearchResponseParser.parseArtistDetails(json)
        assertThat(profile).isNotNull
        assertThat(profile!!.id).isEqualTo("459320")
        assertThat(profile.name).isEqualTo("Arijit Singh")
        assertThat(profile.isVerified).isTrue()
        assertThat(profile.followerCount).isEqualTo(108800000L)
        assertThat(profile.formattedFollowerCount).contains("108.8M")
        assertThat(profile.bio).isEqualTo("Arijit Singh is a celebrated Indian playback singer.")
        assertThat(profile.imageUrl).isEqualTo("https://c.saavncdn.com/artists/Arijit_Singh_500x500.jpg")

        assertThat(profile.topSongs).hasSize(1)
        val song = profile.topSongs[0]
        assertThat(song.title).isEqualTo("Tum Hi Ho")
        assertThat(song.year).isEqualTo("2013")
        assertThat(song.releaseDate).isEqualTo("2013-04-08")
        assertThat(song.playCount).isEqualTo(500000000L)

        assertThat(profile.topAlbums).hasSize(1)
        assertThat(profile.topAlbums[0].title).isEqualTo("Aashiqui 2")
        assertThat(profile.topAlbums[0].imageUrl).isEqualTo("https://c.saavncdn.com/aashiqui_500x500.jpg")

        assertThat(profile.similarArtists).hasSize(1)
        assertThat(profile.similarArtists[0].name).isEqualTo("Atif Aslam")
    }

    @Test
    fun testMetricFormattingHelper() {
        assertThat(ArtistProfile.formatMetricCount(1_500_000_000L)).isEqualTo("1.5B")
        assertThat(ArtistProfile.formatMetricCount(108_800_000L)).isEqualTo("108.8M")
        assertThat(ArtistProfile.formatMetricCount(54_200L)).isEqualTo("54.2K")
        assertThat(ArtistProfile.formatMetricCount(842L)).isEqualTo("842")
    }
}
