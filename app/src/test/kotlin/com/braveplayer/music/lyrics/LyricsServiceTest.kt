package com.braveplayer.music.lyrics

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun parseLyricsResponse_withValidHtmlBreaks_formatsLinesCorrectly() {
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
        assertFalse(success.isSynced)
        assertEquals(3, success.lines.size)
        assertEquals("Hum Tere Bin Ab Reh Nahi Sakte", success.lines[0].text)
        assertEquals("Tere Bina Kya Wajood Mera", success.lines[1].text)
        assertEquals("Kyunki Tum Hi Ho", success.lines[2].text)
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
        assertEquals(1, success.lines.size)
        assertEquals("Don't stop the music & dance", success.lines[0].text)
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
    fun parseLrcLines_parsesTimestampsCorrectly() {
        val lrc = """
            [00:09.65] First line of song
            [00:12.10] Second line
            [01:04.500] Third line
        """.trimIndent()

        val lines = lyricsService.parseLrcLines(lrc)
        assertEquals(3, lines.size)

        assertEquals(9650L, lines[0].timeMs)
        assertEquals("First line of song", lines[0].text)

        assertEquals(12100L, lines[1].timeMs)
        assertEquals("Second line", lines[1].text)

        assertEquals(64500L, lines[2].timeMs)
        assertEquals("Third line", lines[2].text)
    }

    @Test
    fun getActiveLineIndex_identifiesCurrentPlayingLine() {
        val lines = listOf(
            LyricLine(5000L, "Intro"),
            LyricLine(10000L, "Verse 1"),
            LyricLine(20000L, "Chorus")
        )

        assertEquals(-1, lyricsService.getActiveLineIndex(lines, 2000L))
        assertEquals(0, lyricsService.getActiveLineIndex(lines, 5000L))
        assertEquals(0, lyricsService.getActiveLineIndex(lines, 8000L))
        assertEquals(1, lyricsService.getActiveLineIndex(lines, 10000L))
        assertEquals(1, lyricsService.getActiveLineIndex(lines, 19999L))
        assertEquals(2, lyricsService.getActiveLineIndex(lines, 20000L))
        assertEquals(2, lyricsService.getActiveLineIndex(lines, 90000L))
    }

    @Test
    fun extractLyricsFromLrclibJson_prioritizesSyncedLyrics() {
        val json = """
            {
                "id": 12345,
                "plainLyrics": "Plain hello",
                "syncedLyrics": "[00:05.00] Synced hello"
            }
        """.trimIndent()

        val result = lyricsService.extractLyricsFromLrclibJson(json)
        assertTrue(result != null)
        assertTrue(result!!.isSynced)
        assertEquals(1, result.lines.size)
        assertEquals(5000L, result.lines[0].timeMs)
        assertEquals("Synced hello", result.lines[0].text)
    }
}
