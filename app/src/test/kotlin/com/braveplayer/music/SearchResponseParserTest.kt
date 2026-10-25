package com.braveplayer.music

import com.braveplayer.music.model.SearchResponseParser
import com.braveplayer.music.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchResponseParserTest {

    private val sampleValidJson = """
    {
      "type": "query",
      "result": [
        {
          "id": "song_1",
          "title": "Kesariya &amp; &quot;Love&quot;",
          "album": "Brahmastra &quot;Part One&quot;",
          "duration": 268,
          "image": "https://c.saavncdn.com/123/kesariya.jpg",
          "media_url": "https://aac.saavncdn.com/123/stream_320.mp3",
          "media_url_default": "https://aac.saavncdn.com/123/stream_96.mp4",
          "vlink": "https://jiotunepreview.jio.com/123.mp3",
          "artistMap": {
            "primary_artists": [
              { "id": 1, "name": "Arijit Singh" },
              { "id": 2, "name": "Pritam" }
            ]
          }
        },
        {
          "id": "song_2",
          "title": "Channa Mereya",
          "album": "Ae Dil Hai Mushkil",
          "duration": 289,
          "image": "https://c.saavncdn.com/456/channa.jpg",
          "media_url": "https://aac.saavncdn.com/456/channa_320.mp3",
          "media_url_default": null,
          "vlink": null,
          "subtitle": "Arijit Singh"
        }
      ]
    }
    """.trimIndent()

    @Test
    fun testParseValidSearchResponseWithMultipleSongs() {
        val songs = SearchResponseParser.parseSearchResponse(sampleValidJson)
        assertThat(songs).hasSize(2)

        val first = songs[0]
        assertThat(first.id).isEqualTo("song_1")
        assertThat(first.title).isEqualTo("Kesariya & \"Love\"")
        assertThat(first.album).isEqualTo("Brahmastra \"Part One\"")
        assertThat(first.artists).isEqualTo("Arijit Singh, Pritam")
        assertThat(first.durationSec).isEqualTo(268)
        assertThat(first.formattedDuration).isEqualTo("4:28")
        assertThat(first.imageUrl).isEqualTo("https://c.saavncdn.com/123/kesariya.jpg")
        assertThat(first.mediaUrlDefault).isEqualTo("https://aac.saavncdn.com/123/stream_96.mp4")

        val second = songs[1]
        assertThat(second.id).isEqualTo("song_2")
        assertThat(second.title).isEqualTo("Channa Mereya")
        assertThat(second.artists).isEqualTo("Arijit Singh")
        assertThat(second.durationSec).isEqualTo(289)
        assertThat(second.formattedDuration).isEqualTo("4:49")
    }

    @Test
    fun testParseEmptyResults() {
        val emptyJson = """{"type": "query", "result": []}"""
        val songs = SearchResponseParser.parseSearchResponse(emptyJson)
        assertThat(songs).isEmpty()

        val emptyString = ""
        assertThat(SearchResponseParser.parseSearchResponse(emptyString)).isEmpty()
    }

    @Test
    fun testParseErrorResponse() {
        val errorJson = """
        {
          "error": "Unknown URL",
          "error code": 1001,
          "message": "Please send links from https://www.jiosaavn.com only"
        }
        """.trimIndent()

        val songs = SearchResponseParser.parseSearchResponse(errorJson)
        assertThat(songs).isEmpty()
    }

    @Test
    fun testPlaybackUrlValidationPriority() {
        // Priority 1: mediaUrlDefault is valid .mp4
        val songWithDefault = Song(
            id = "1",
            title = "Test",
            artists = "Artist",
            album = "Album",
            imageUrl = null,
            durationSec = 120,
            mediaUrl = "https://aac.saavncdn.com/stream_320.mp3",
            mediaUrlDefault = "https://aac.saavncdn.com/stream_96.mp4",
            previewUrl = "https://jiotunepreview.jio.com/sample.mp3"
        )
        assertThat(songWithDefault.getBestPlaybackUrl()).isEqualTo("https://aac.saavncdn.com/stream_96.mp4")

        // Priority 2: mediaUrlDefault is null, mediaUrl is valid .mp3
        val songWithMediaUrl = songWithDefault.copy(mediaUrlDefault = null)
        assertThat(songWithMediaUrl.getBestPlaybackUrl()).isEqualTo("https://aac.saavncdn.com/stream_320.mp3")

        // Priority 3: both mediaUrl and mediaUrlDefault are null, previewUrl is used
        val songWithPreviewOnly = songWithDefault.copy(mediaUrlDefault = null, mediaUrl = null)
        assertThat(songWithPreviewOnly.getBestPlaybackUrl()).isEqualTo("https://jiotunepreview.jio.com/sample.mp3")

        // Priority 4: All candidate URLs are null or empty
        val songNoUrls = songWithDefault.copy(mediaUrlDefault = null, mediaUrl = null, previewUrl = null)
        assertThat(songNoUrls.getBestPlaybackUrl()).isNull()

        // Invalid scheme or missing audio extension returns null
        val songInvalid = songWithDefault.copy(
            mediaUrlDefault = "ftp://invalid.com/audio.txt",
            mediaUrl = "not_a_url",
            previewUrl = ""
        )
        assertThat(songInvalid.getBestPlaybackUrl()).isNull()
    }

    @Test
    fun testHtmlEntityDecoding() {
        val input = "Rock &amp; Roll with &quot;Queen&quot; &#039;Best&#039;"
        val cleaned = SearchResponseParser.cleanHtmlEntities(input)
        assertThat(cleaned).isEqualTo("Rock & Roll with \"Queen\" 'Best'")
    }

    @Test
    fun testMissingOptionalFieldsDoesNotCrash() {
        val minimalJson = """
        [
          {
            "id": "minimal_1",
            "title": "Minimal Song"
          }
        ]
        """.trimIndent()

        val songs = SearchResponseParser.parseSearchResponse(minimalJson)
        assertThat(songs).hasSize(1)
        val song = songs[0]
        assertThat(song.id).isEqualTo("minimal_1")
        assertThat(song.title).isEqualTo("Minimal Song")
        assertThat(song.artists).isEqualTo("Unknown Artist")
        assertThat(song.album).isEqualTo("Unknown Album")
        assertThat(song.durationSec).isEqualTo(0)
        assertThat(song.formattedDuration).isEqualTo("--:--")
        assertThat(song.getBestPlaybackUrl()).isNull()
    }
}
