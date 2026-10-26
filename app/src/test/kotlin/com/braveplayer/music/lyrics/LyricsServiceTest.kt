package com.braveplayer.music.lyrics

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsServiceTest {

    private lateinit var lyricsService: LyricsService

    @Before
    fun setUp() {
        lyricsService = LyricsService(OkHttpClient())
    }

    @Test
    fun parseLyricsResponse_withValidHtmlBreaks_formatsCorrectly() {
        val json = """
            {
                "lyrics": "Hum Tere Bin Ab Reh Nahi Sakte<br>Tere Bina Kya Wajood Mera<br><br>Kyunki Tum Hi Ho",
                "script_tracking_url": "https://www.jiosaavn.com/stats.php",
                "lyrics_copyright": "Lyrics powered by JioSaavn",
                "snippet": "Tere Bina Kya Wajood Mera"
            }
        """.trimIndent()

        val result = lyricsService.parseLyricsResponse(json)
        assertTrue(result is LyricsResult.Success)

        val success = result as LyricsResult.Success
        val expectedLyrics = "Hum Tere Bin Ab Reh Nahi Sakte\nTere Bina Kya Wajood Mera\n\nKyunki Tum Hi Ho"
        assertEquals(expectedLyrics, success.lyrics)
        assertEquals("Lyrics powered by JioSaavn", success.copyright)
        assertEquals("Tere Bina Kya Wajood Mera", success.snippet)
    }

    @Test
    fun parseLyricsResponse_withHtmlEntities_unescapesEntities() {
        val json = """
            {
                "lyrics": "Don&#039;t stop the music &amp; dance",
                "lyrics_copyright": "Universal Music &amp; Publishing"
            }
        """.trimIndent()

        val result = lyricsService.parseLyricsResponse(json)
        assertTrue(result is LyricsResult.Success)

        val success = result as LyricsResult.Success
        assertEquals("Don't stop the music & dance", success.lyrics)
    }

    @Test
    fun parseLyricsResponse_withFailureStatus_returnsUnavailable() {
        val json = """
            {
                "status": "failure",
                "error": {
                    "msg": "Something went wrong please try again"
                }
            }
        """.trimIndent()

        val result = lyricsService.parseLyricsResponse(json)
        assertTrue(result is LyricsResult.Unavailable)
        assertEquals("Lyrics not available for this track", (result as LyricsResult.Unavailable).message)
    }

    @Test
    fun parseLyricsResponse_withEmptyLyrics_returnsUnavailable() {
        val json = """
            {
                "lyrics": "   "
            }
        """.trimIndent()

        val result = lyricsService.parseLyricsResponse(json)
        assertTrue(result is LyricsResult.Unavailable)
    }

    @Test
    fun parseLyricsResponse_withBlankJson_returnsUnavailable() {
        val result = lyricsService.parseLyricsResponse("")
        assertTrue(result is LyricsResult.Unavailable)
    }

    @Test
    fun stripLrcTimestamps_removesTimestampBrackets() {
        val synced = """
            [00:12.34] First line of song
            [00:15.89] Second line of song
            [01:02.00] Third line of song
        """.trimIndent()

        val plain = lyricsService.stripLrcTimestamps(synced)
        val expected = "First line of song\nSecond line of song\nThird line of song"
        assertEquals(expected, plain)
    }

    @Test
    fun extractLyricsFromLrclibJson_prioritizesPlainLyrics() {
        val json = """
            {
                "id": 12345,
                "plainLyrics": "Hello it's me\nI was wondering",
                "syncedLyrics": "[00:01.00] Hello it's me"
            }
        """.trimIndent()

        val lyrics = lyricsService.extractLyricsFromLrclibJson(json)
        assertEquals("Hello it's me\nI was wondering", lyrics)
    }

    @Test
    fun extractLyricsFromLrclibJson_fallsBackToSyncedLyricsWhenPlainEmpty() {
        val json = """
            {
                "id": 12345,
                "plainLyrics": "",
                "syncedLyrics": "[00:01.00] Hello it's me\n[00:05.00] Another line"
            }
        """.trimIndent()

        val lyrics = lyricsService.extractLyricsFromLrclibJson(json)
        assertEquals("Hello it's me\nAnother line", lyrics)
    }
}
