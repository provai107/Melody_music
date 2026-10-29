package com.braveplayer.music.api

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.model.SearchResponseParser
import com.braveplayer.music.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MusicMetadataServiceSongDiscoveryTest {

    private lateinit var metadataService: MusicMetadataService

    @Before
    fun setUp() {
        val filterManager = FilterListManager(emptyList())
        metadataService = MusicMetadataService(filterManager)
    }

    @Test
    fun test15SecondSongsAndTeasersAreRejectedByParser() {
        val rawJson = """
        {
          "results": [
            {
              "id": "clip_15s",
              "title": "JioTune Caller Ringtone Preview",
              "album": "Single",
              "duration": 15,
              "media_url": "https://aac.saavncdn.com/clip15.mp4"
            },
            {
              "id": "clip_30s",
              "title": "Teaser Snippet",
              "album": "Preview Album",
              "duration": 30,
              "media_url": "https://aac.saavncdn.com/clip30.mp4"
            },
            {
              "id": "real_song",
              "title": "Ek Tarfa",
              "album": "Ek Tarfa",
              "duration": 234,
              "media_url": "https://aac.saavncdn.com/full_320.mp4"
            }
          ]
        }
        """.trimIndent()

        val parsed = SearchResponseParser.parseSearchResponse(rawJson)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].id).isEqualTo("real_song")
        assertThat(parsed[0].durationSec).isEqualTo(234)
        assertThat(parsed[0].title).isEqualTo("Ek Tarfa")
    }

    @Test
    fun testDarshanRavalEkTarfaDecryption() {
        // JioSaavn encrypted media url for Ek Tarfa by Darshan Raval
        val rawEncrypted = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyXvauhZTyL60cVcGVUl+s13UOkeMXqmBpERCyfhJwXoi3hDxeQKaG2hw7tS9a8Gtq"
        val (url320, url160) = SearchResponseParser.decryptMediaUrl(rawEncrypted)

        assertThat(url320).isEqualTo("https://aac.saavncdn.com/750/d5cf73c61899e893b1e25a9ebb8edb51_320.mp4")
        assertThat(url160).isEqualTo("https://aac.saavncdn.com/750/d5cf73c61899e893b1e25a9ebb8edb51_160.mp4")

        val json = """
        {
          "results": [
            {
              "id": "maa6PErt",
              "title": "Ek Tarfa",
              "subtitle": "Darshan Raval - Ek Tarfa",
              "more_info": {
                "music": "Darshan Raval",
                "album": "Ek Tarfa",
                "duration": "234",
                "encrypted_media_url": "$rawEncrypted"
              }
            }
          ]
        }
        """.trimIndent()

        val parsed = SearchResponseParser.parseSearchResponse(json)
        assertThat(parsed).hasSize(1)
        val song = parsed[0]
        assertThat(song.title).isEqualTo("Ek Tarfa")
        assertThat(song.artists).isEqualTo("Darshan Raval")
        assertThat(song.durationSec).isEqualTo(234)
        assertThat(song.mediaUrl).isEqualTo(url320)
        assertThat(song.mediaUrlDefault).isEqualTo(url160)
        assertThat(song.getBestPlaybackUrl()).isEqualTo(url160)
    }

    @Test
    fun testAtifAslamSongSearchQueriesResolveCorrectly() {
        runBlocking {
            // Search queries mentioning Atif Aslam or his iconic tracks
            val result = metadataService.searchSongs(baseUrl = "", query = "aadat & wo lamhe by atif aslam")
            assertThat(result).isInstanceOf(SearchResult.Success::class.java)

            val songs = (result as SearchResult.Success).songs
            assertThat(songs).isNotEmpty

            val titles = songs.map { it.title.lowercase() }
            // Verify both iconic tracks are included
            val hasAadat = titles.any { it.contains("aadat") }
            val hasWohLamhe = titles.any { it.contains("lamhe") }
            assertThat(hasAadat).isTrue
            assertThat(hasWohLamhe).isTrue

            // Verify none of the songs are 15-second clips
            for (s in songs) {
                assertThat(s.durationSec).isGreaterThan(30)
                assertThat(s.getBestPlaybackUrl()).isNotNull
            }
        }
    }

    @Test
    fun testAtifAslamDedicatedArtistCatalog() {
        runBlocking {
            val tracks = metadataService.getArtistTracks(baseUrl = "", artistId = "atif_aslam_official", artistName = "Atif Aslam")
            assertThat(tracks).isNotEmpty
            assertThat(tracks.size).isGreaterThanOrEqualTo(50)

            // Verify all tracks have valid stream URLs and duration > 30 seconds
            for (track in tracks) {
                assertThat(track.durationSec).isGreaterThan(30)
                assertThat(track.getBestPlaybackUrl()).isNotNull
            }

            // Verify second page of discography
            val page2 = metadataService.getMoreArtistTracks(baseUrl = "", artistId = "atif_aslam_official", artistName = "Atif Aslam", page = 2, limit = 50)
            assertThat(page2).isNotEmpty
        }
    }
}
