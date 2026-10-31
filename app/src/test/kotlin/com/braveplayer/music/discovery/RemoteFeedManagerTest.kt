package com.braveplayer.music.discovery

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.model.ReleaseItem
import com.braveplayer.music.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteFeedManagerTest {

    private lateinit var metadataService: MusicMetadataService
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        metadataService = MusicMetadataService(FilterListManager(emptyList()))
        RemoteFeedManager.setCachedConfigForTesting(null)
    }

    @After
    fun tearDown() {
        RemoteFeedManager.setCachedConfigForTesting(null)
    }

    @Test
    fun testParseCuratedConfig() {
        val json = """
        {
          "excluded_song_ids": ["bad_id_1", "bad_id_2"],
          "excluded_titles": ["old song remix"],
          "pinned_trending": [
            { "id": "pinned_song_1", "position": 0 }
          ],
          "pinned_releases": [
            { "search_query": "Mangal Rang", "position": 1 }
          ],
          "custom_tracks": [
            {
              "id": "custom_live_track",
              "title": "Unreleased Live Session",
              "artists": "Top Artist",
              "album": "Live Session 2026",
              "imageUrl": "https://img.cdn/live.jpg",
              "mediaUrl": "https://audio.cdn/live.mp3",
              "duration": 210,
              "year": "2026"
            }
          ]
        }
        """.trimIndent()

        val config = RemoteFeedManager.parseConfig(json)

        assertThat(config.excludedSongIds).containsExactlyInAnyOrder("bad_id_1", "bad_id_2")
        assertThat(config.excludedTitles).contains("old song remix")
        assertThat(config.pinnedTrending).hasSize(1)
        assertThat(config.pinnedTrending[0].id).isEqualTo("pinned_song_1")
        assertThat(config.pinnedReleases).hasSize(1)
        assertThat(config.pinnedReleases[0].searchQuery).isEqualTo("Mangal Rang")

        assertThat(config.customTracks).hasSize(1)
        val custom = config.customTracks[0]
        assertThat(custom.id).isEqualTo("custom_live_track")
        assertThat(custom.title).isEqualTo("Unreleased Live Session")
        assertThat(custom.mediaUrl).isEqualTo("https://audio.cdn/live.mp3")
        assertThat(custom.durationSec).isEqualTo(210)
    }

    @Test
    fun testApplyTrendingOverridesExcludesAndInjectsCustomTracks() {
        runBlocking {
            val config = RemoteFeedManager.CuratedConfig(
                excludedSongIds = setOf("bad_track"),
                customTracks = listOf(
                    Song(
                        id = "custom_promo",
                        title = "Featured Promo Song",
                        artists = "Artist Special",
                        album = "Single",
                        imageUrl = null,
                        durationSec = 200,
                        mediaUrl = "https://audio.cdn/promo.mp3",
                        mediaUrlDefault = "https://audio.cdn/promo.mp3",
                        previewUrl = null
                    )
                )
            )
            RemoteFeedManager.setCachedConfigForTesting(config)

            val baseSongs = listOf(
                Song("song_1", "Normal Song 1", "Artist 1", "Album 1", null, 180, "https://a.com/1.mp3", null, null),
                Song("bad_track", "Unwanted Track", "Artist 2", "Album 2", null, 190, "https://a.com/2.mp3", null, null),
                Song("song_3", "Normal Song 3", "Artist 3", "Album 3", null, 200, "https://a.com/3.mp3", null, null)
            )

            val result = RemoteFeedManager.applyTrendingOverrides(baseSongs, httpClient, metadataService)

            // Verify bad_track is removed
            assertThat(result.map { it.id }).doesNotContain("bad_track")

            // Verify custom track is placed at top
            assertThat(result.first().id).isEqualTo("custom_promo")
            assertThat(result.first().title).isEqualTo("Featured Promo Song")

            // Verify remaining normal songs exist
            assertThat(result.map { it.id }).contains("song_1", "song_3")
        }
    }

    @Test
    fun testApplyReleaseOverridesExcludesAndInjectsCustomTracks() {
        runBlocking {
            val customSong = Song(
                id = "custom_release",
                title = "Exclusive New Release",
                artists = "Artist 99",
                album = "Fresh 2026",
                imageUrl = null,
                durationSec = 240,
                mediaUrl = "https://audio.cdn/fresh.mp3",
                mediaUrlDefault = "https://audio.cdn/fresh.mp3",
                previewUrl = null
            )
            val config = RemoteFeedManager.CuratedConfig(
                excludedSongIds = setOf("rel_excluded"),
                customTracks = listOf(customSong)
            )
            RemoteFeedManager.setCachedConfigForTesting(config)

            val song1 = Song("rel_1", "Title 1", "Artist 1", "Album 1", null, 180, "https://a.com/1.mp3", null, null)
            val songExcluded = Song("rel_excluded", "Title Old", "Artist 2", "Album 2", null, 190, "https://a.com/2.mp3", null, null)
            val song3 = Song("rel_3", "Title 3", "Artist 3", "Album 3", null, 200, "https://a.com/3.mp3", null, null)

            val baseReleases = listOf(
                ReleaseItem("rel_1", "Title 1", "Artist 1", "Album 1", "2026", "Released 2026", null, song1, false),
                ReleaseItem("rel_excluded", "Title Old", "Artist 2", "Album 2", "2026", "Released 2026", null, songExcluded, false),
                ReleaseItem("rel_3", "Title 3", "Artist 3", "Album 3", "2026", "Released 2026", null, song3, false)
            )

            val result = RemoteFeedManager.applyReleaseOverrides(
                baseReleases,
                httpClient,
                metadataService
            ) { song ->
                ReleaseItem(
                    id = song.id,
                    title = song.title,
                    artists = song.artists,
                    album = song.album,
                    releaseTag = "2026",
                    releaseDate = "New Release",
                    imageUrl = song.imageUrl,
                    song = song,
                    isAlbum = false
                )
            }

            assertThat(result.map { it.id }).doesNotContain("rel_excluded")
            assertThat(result.first().id).isEqualTo("custom_release")
            assertThat(result.first().title).isEqualTo("Exclusive New Release")
            assertThat(result.map { it.id }).contains("rel_1", "rel_3")
        }
    }
}
