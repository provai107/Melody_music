package com.braveplayer.music.ui

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.MainActivity
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.SearchHistoryItem
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchHistoryAndShareRegressionTest {

    @Test
    fun testCurrentSearchResultPlaybackRemainsUnchanged() {
        val searchResultSong = Song(
            id = "search_res_123",
            title = "Levitating",
            artists = "Dua Lipa",
            album = "Future Nostalgia",
            imageUrl = "https://saavncdn.com/levitating.jpg",
            durationSec = 203,
            mediaUrl = "https://aac.saavncdn.com/levitating.mp4",
            mediaUrlDefault = "https://aac.saavncdn.com/levitating_def.mp4",
            previewUrl = null
        )

        var playedSong: Song? = null
        var recordedSong: Song? = null

        // This simulates SongAdapter.onSongSelected from MainActivity
        val onSongSelected: (Song) -> Unit = { selected ->
            recordedSong = selected
            playedSong = selected
        }

        onSongSelected(searchResultSong)

        assertThat(playedSong).isNotNull
        assertThat(playedSong?.id).isEqualTo("search_res_123")
        assertThat(playedSong?.title).isEqualTo("Levitating")
        assertThat(playedSong?.getBestPlaybackUrl()).isEqualTo("https://aac.saavncdn.com/levitating_def.mp4")
        assertThat(recordedSong?.id).isEqualTo("search_res_123")
    }

    @Test
    fun testSelectingHistoricalSongPlaysExactSongWithStableId() {
        val historicalItem = SearchHistoryItem(
            id = 42L,
            queryText = "Flowers",
            title = "Flowers",
            subtitle = "Song • Miley Cyrus",
            imageUrl = "https://saavncdn.com/flowers.jpg",
            contentType = "Song",
            songId = "miley_flowers_777",
            mediaUrl = "https://aac.saavncdn.com/flowers.mp4",
            mediaUrlDefault = "https://aac.saavncdn.com/flowers_def.mp4",
            previewUrl = null,
            durationSec = 200,
            album = "Endless Summer Vacation",
            artists = "Miley Cyrus"
        )

        val restoredSong = historicalItem.toSong()

        assertThat(restoredSong).isNotNull
        assertThat(restoredSong?.id).isEqualTo("miley_flowers_777")
        assertThat(restoredSong?.title).isEqualTo("Flowers")
        assertThat(restoredSong?.artists).isEqualTo("Miley Cyrus")
        assertThat(restoredSong?.getBestPlaybackUrl()).isEqualTo("https://aac.saavncdn.com/flowers_def.mp4")
    }

    @Test
    fun testSongsWithIdenticalTitlesRetainDistinctIdentity() {
        val item1 = SearchHistoryItem(
            id = 1L,
            queryText = "One",
            title = "One",
            subtitle = "Song • U2",
            songId = "u2_one_1",
            artists = "U2",
            album = "Achtung Baby",
            mediaUrl = "https://stream.com/u2_one.mp4"
        )
        val item2 = SearchHistoryItem(
            id = 2L,
            queryText = "One",
            title = "One",
            subtitle = "Song • Metallica",
            songId = "metallica_one_2",
            artists = "Metallica",
            album = "...And Justice for All",
            mediaUrl = "https://stream.com/metallica_one.mp4"
        )

        val song1 = item1.toSong()
        val song2 = item2.toSong()

        assertThat(song1?.id).isNotEqualTo(song2?.id)
        assertThat(song1?.artists).isEqualTo("U2")
        assertThat(song2?.artists).isEqualTo("Metallica")
        assertThat(song1?.getBestPlaybackUrl()).isEqualTo("https://stream.com/u2_one.mp4")
        assertThat(song2?.getBestPlaybackUrl()).isEqualTo("https://stream.com/metallica_one.mp4")
    }

    @Test
    fun testFetchSongDetailsRefusesToSubstituteMismatchedSong() {
        // API returns a different song with ID "different_id_999" when querying for "target_id_123"
        val jsonDifferentSong = """
        [
          {
            "id": "different_id_999",
            "title": "Random Other Song",
            "album": "Other Album",
            "duration": 180,
            "media_url": "https://aac.saavncdn.com/other.mp4"
          }
        ]
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonDifferentSong.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val filterManager = FilterListManager(emptyList())
        val metadataService = MusicMetadataService(filterManager)

        // Inject the mocked HTTP client via reflection
        val field = MusicMetadataService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(metadataService, mockClient)

        runBlocking {
            // Looking for "target_id_123", but API only returns "different_id_999".
            // It MUST return null, and NEVER substitute the different song!
            val result = metadataService.fetchSongDetails("https://api.example.com", "target_id_123", "Target Song Title")
            assertThat(result).isNull()
        }
    }

    @Test
    fun testFetchSongDetailsReturnsExactMatchWhenIdMatches() {
        val jsonExactSong = """
        [
          {
            "id": "target_id_123",
            "title": "Target Song Title",
            "album": "Target Album",
            "duration": 210,
            "media_url": "https://aac.saavncdn.com/target.mp4",
            "media_url_default": "https://aac.saavncdn.com/target_def.mp4"
          }
        ]
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonExactSong.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val filterManager = FilterListManager(emptyList())
        val metadataService = MusicMetadataService(filterManager)

        val field = MusicMetadataService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(metadataService, mockClient)

        runBlocking {
            val result = metadataService.fetchSongDetails("https://api.example.com", "target_id_123", "Target Song Title")
            assertThat(result).isNotNull
            assertThat(result?.id).isEqualTo("target_id_123")
            assertThat(result?.title).isEqualTo("Target Song Title")
            assertThat(result?.getBestPlaybackUrl()).isEqualTo("https://aac.saavncdn.com/target_def.mp4")
        }
    }

    @Test
    fun testShareActionGeneratesVerifiedApkUrlAndClickableHttps() {
        val song = Song(
            id = "song_share_1",
            title = "Shape of You",
            artists = "Ed Sheeran",
            album = "÷ (Divide)",
            imageUrl = "https://saavncdn.com/shape.jpg",
            durationSec = 233,
            mediaUrl = "https://aac.saavncdn.com/shape.mp4",
            mediaUrlDefault = null,
            previewUrl = null
        )

        val message = MainActivity.buildShareMessage(song)

        assertThat(message).contains("Listening to \"Shape of You\" by Ed Sheeran on Melody 🎵")
        assertThat(message).contains("https://github.com/provai107/Melody_music/releases/latest/download/Melody.apk")
        assertThat(MainActivity.OFFICIAL_APK_DOWNLOAD_URL).isEqualTo("https://github.com/provai107/Melody_music/releases/latest/download/Melody.apk")

        // Generic fallback when no song is playing
        val genericMessage = MainActivity.buildShareMessage(null)
        assertThat(genericMessage).isEqualTo("Listen with Melody 🎵 Download the latest version here: https://github.com/provai107/Melody_music/releases/latest/download/Melody.apk")

        // Ensure recipients receive a direct APK download link on https://
        assertThat(MainActivity.OFFICIAL_APK_DOWNLOAD_URL).startsWith("https://")
        assertThat(MainActivity.OFFICIAL_APK_DOWNLOAD_URL).endsWith("/Melody.apk")
        assertThat(MainActivity.OFFICIAL_APK_DOWNLOAD_URL).doesNotContain("vercel.app")
        assertThat(MainActivity.OFFICIAL_APK_DOWNLOAD_URL).doesNotContain(".git")
    }
}
