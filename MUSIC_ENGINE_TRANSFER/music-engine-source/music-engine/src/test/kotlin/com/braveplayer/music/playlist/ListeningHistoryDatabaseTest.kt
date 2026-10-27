package com.braveplayer.music.playlist

import android.content.Context
import com.braveplayer.music.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ListeningHistoryDatabaseTest {

    private lateinit var context: Context
    private lateinit var dbHelper: PlaylistDatabaseHelper

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dbHelper = PlaylistDatabaseHelper(context)
        dbHelper.clearHistory()
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    @Test
    fun testRecordMeaningfulPlayVsQuickSkip() {
        val song1 = Song("h1", "Long Track", "Artist A", "Album A", null, 200, "https://aac.saavncdn.com/1.mp3", null, null)
        val song2 = Song("h2", "Skipped Track", "Artist B", "Album B", null, 180, "https://aac.saavncdn.com/2.mp3", null, null)

        // Meaningful play: 45 seconds played
        dbHelper.recordListeningHistory(
            song = song1,
            playDurationSec = 45,
            isCompleted = false,
            isSkipped = false,
            isMeaningful = true
        )

        // Quick skip: only 5 seconds played
        dbHelper.recordListeningHistory(
            song = song2,
            playDurationSec = 5,
            isCompleted = false,
            isSkipped = true,
            isMeaningful = false
        )

        val recentlyPlayed = dbHelper.getRecentlyPlayedSongs()
        assertThat(recentlyPlayed).hasSize(2)

        // Most played tracks must ONLY include meaningful plays
        val mostPlayed = dbHelper.getMostPlayedSongs()
        assertThat(mostPlayed).hasSize(1)
        assertThat(mostPlayed[0].first.id).isEqualTo("h1")
        assertThat(mostPlayed[0].second).isEqualTo(1)

        // Skipped IDs must contain the quickly skipped song
        val skippedIds = dbHelper.getSkippedSongIds()
        assertThat(skippedIds).contains("h2")
        assertThat(skippedIds).doesNotContain("h1")
    }

    @Test
    fun testArtistPlayCountsAggregation() {
        val songA = Song("a1", "Track 1", "Pritam, Arijit Singh", "Album", null, 200, "https://aac.saavncdn.com/a.mp3", null, null)
        val songB = Song("a2", "Track 2", "Arijit Singh", "Album", null, 200, "https://aac.saavncdn.com/b.mp3", null, null)

        dbHelper.recordListeningHistory(songA, 60, false, false, true)
        dbHelper.recordListeningHistory(songB, 90, false, false, true)

        val artistCounts = dbHelper.getArtistPlayCounts()
        assertThat(artistCounts["Arijit Singh"]).isEqualTo(2)
        assertThat(artistCounts["Pritam"]).isEqualTo(1)
    }

    @Test
    fun testStorageGrowthCappedAtMaxRows() {
        val dummySong = Song("c", "Cap Track", "Artist", "Album", null, 100, "https://aac.saavncdn.com/c.mp3", null, null)

        // Insert 520 history entries (exceeding MAX_HISTORY_ROWS = 500)
        for (i in 1..520) {
            dbHelper.recordListeningHistory(
                song = dummySong.copy(id = "cap_$i"),
                playDurationSec = 35,
                isCompleted = false,
                isSkipped = false,
                isMeaningful = true
            )
        }

        // Query raw count in listening_history table
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM listening_history", null)
        var rowCount = 0
        cursor.use {
            if (it.moveToFirst()) {
                rowCount = it.getInt(0)
            }
        }

        assertThat(rowCount).isEqualTo(PlaylistDatabaseHelper.MAX_HISTORY_ROWS)
    }
}
