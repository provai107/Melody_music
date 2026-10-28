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
class SearchHistoryDatabaseTest {

    private lateinit var context: Context
    private lateinit var dbHelper: PlaylistDatabaseHelper

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dbHelper = PlaylistDatabaseHelper(context)
        dbHelper.clearSearchHistory()
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    @Test
    fun testEmptySearchHistoryInitially() {
        val history = dbHelper.getRecentSearches()
        assertThat(history).isEmpty()
    }

    @Test
    fun testRecordAndRetrieveSearchQuery() {
        val item = SearchHistoryItem(
            queryText = "Arijit Singh",
            title = "Arijit Singh",
            subtitle = "Search query",
            contentType = "Search"
        )
        val id = dbHelper.recordSearchHistory(item)
        assertThat(id).isGreaterThan(0)

        val history = dbHelper.getRecentSearches()
        assertThat(history).hasSize(1)
        assertThat(history[0].title).isEqualTo("Arijit Singh")
        assertThat(history[0].contentType).isEqualTo("Search")
    }

    @Test
    fun testRecordSongSearch() {
        val song = Song(
            id = "song_rec_1",
            title = "Apna Bana Le",
            artists = "Arijit Singh, Sachin-Jigar",
            album = "Bhediya",
            imageUrl = "https://c.saavncdn.com/apna_bana_le.jpg",
            durationSec = 261,
            mediaUrl = null,
            mediaUrlDefault = null,
            previewUrl = null
        )
        val id = dbHelper.recordSongSearch(song)
        assertThat(id).isGreaterThan(0)

        val history = dbHelper.getRecentSearches()
        assertThat(history).hasSize(1)
        assertThat(history[0].title).isEqualTo("Apna Bana Le")
        assertThat(history[0].subtitle).isEqualTo("Song • Arijit Singh, Sachin-Jigar")
        assertThat(history[0].songId).isEqualTo("song_rec_1")
        assertThat(history[0].imageUrl).isEqualTo("https://c.saavncdn.com/apna_bana_le.jpg")
    }

    @Test
    fun testDeduplicationOfSearchEntries() {
        val item1 = SearchHistoryItem(queryText = "Coldplay", title = "Coldplay", subtitle = "Search query", contentType = "Search")
        dbHelper.recordSearchHistory(item1)

        val item2 = SearchHistoryItem(queryText = "Dua Lipa", title = "Dua Lipa", subtitle = "Search query", contentType = "Search")
        dbHelper.recordSearchHistory(item2)

        assertThat(dbHelper.getRecentSearches()).hasSize(2)

        // Record Coldplay again
        dbHelper.recordSearchHistory(item1)

        val history = dbHelper.getRecentSearches()
        assertThat(history).hasSize(2)
        // Coldplay should now be newest (at index 0)
        assertThat(history[0].title).isEqualTo("Coldplay")
        assertThat(history[1].title).isEqualTo("Dua Lipa")
    }

    @Test
    fun testRemoveSingleSearchEntry() {
        val id1 = dbHelper.recordSearchHistory(SearchHistoryItem(queryText = "Artist A", title = "Artist A", subtitle = "Search"))
        val id2 = dbHelper.recordSearchHistory(SearchHistoryItem(queryText = "Artist B", title = "Artist B", subtitle = "Search"))

        val initial = dbHelper.getRecentSearches()
        assertThat(initial).hasSize(2)

        val removed = dbHelper.removeSearchHistoryItem(id2)
        assertThat(removed).isTrue()

        val after = dbHelper.getRecentSearches()
        assertThat(after).hasSize(1)
        assertThat(after[0].id).isEqualTo(id1)
    }

    @Test
    fun testClearAllSearchHistory() {
        dbHelper.recordSearchHistory(SearchHistoryItem(queryText = "Track 1", title = "Track 1", subtitle = "Song"))
        dbHelper.recordSearchHistory(SearchHistoryItem(queryText = "Track 2", title = "Track 2", subtitle = "Song"))

        assertThat(dbHelper.getRecentSearches()).hasSize(2)

        dbHelper.clearSearchHistory()
        assertThat(dbHelper.getRecentSearches()).isEmpty()
    }

    @Test
    fun testRejectBlankSearch() {
        val blankId = dbHelper.recordSearchHistory(SearchHistoryItem(queryText = "   ", title = "  ", subtitle = ""))
        assertThat(blankId).isEqualTo(-1L)
        assertThat(dbHelper.getRecentSearches()).isEmpty()
    }

    @Test
    fun testSongsWithIdenticalTitlesRemainDistinct() {
        // Song 1: "Stay" by The Kid LAROI & Justin Bieber
        val song1 = Song(
            id = "stay_laroi_101",
            title = "Stay",
            artists = "The Kid LAROI, Justin Bieber",
            album = "F*CK LOVE 3",
            imageUrl = "https://saavncdn.com/stay_laroi.jpg",
            durationSec = 141,
            mediaUrl = "https://aac.saavncdn.com/stay_laroi.mp4",
            mediaUrlDefault = "https://aac.saavncdn.com/stay_laroi_def.mp4",
            previewUrl = "https://preview.saavncdn.com/stay_laroi.mp3"
        )
        // Song 2: "Stay" by Rihanna ft. Mikky Ekko (identical title, different stable ID)
        val song2 = Song(
            id = "stay_rihanna_202",
            title = "Stay",
            artists = "Rihanna, Mikky Ekko",
            album = "Unapologetic",
            imageUrl = "https://saavncdn.com/stay_rihanna.jpg",
            durationSec = 240,
            mediaUrl = "https://aac.saavncdn.com/stay_rihanna.mp4",
            mediaUrlDefault = "https://aac.saavncdn.com/stay_rihanna_def.mp4",
            previewUrl = "https://preview.saavncdn.com/stay_rihanna.mp3"
        )

        val id1 = dbHelper.recordSongSearch(song1)
        val id2 = dbHelper.recordSongSearch(song2)

        assertThat(id1).isGreaterThan(0)
        assertThat(id2).isGreaterThan(0)

        val history = dbHelper.getRecentSearches()
        assertThat(history).hasSize(2)

        // Both entries must exist distinctly with their respective stable IDs and metadata preserved
        val rihannaEntry = history.first { it.songId == "stay_rihanna_202" }
        assertThat(rihannaEntry.title).isEqualTo("Stay")
        assertThat(rihannaEntry.artists).isEqualTo("Rihanna, Mikky Ekko")
        assertThat(rihannaEntry.mediaUrl).isEqualTo("https://aac.saavncdn.com/stay_rihanna.mp4")
        assertThat(rihannaEntry.previewUrl).isEqualTo("https://preview.saavncdn.com/stay_rihanna.mp3")

        val laroiEntry = history.first { it.songId == "stay_laroi_101" }
        assertThat(laroiEntry.title).isEqualTo("Stay")
        assertThat(laroiEntry.artists).isEqualTo("The Kid LAROI, Justin Bieber")
        assertThat(laroiEntry.mediaUrl).isEqualTo("https://aac.saavncdn.com/stay_laroi.mp4")

        // Verifying toSong() produces the exact distinct songs
        val reconstructedRihanna = rihannaEntry.toSong()!!
        val reconstructedLaroi = laroiEntry.toSong()!!

        assertThat(reconstructedRihanna.id).isEqualTo("stay_rihanna_202")
        assertThat(reconstructedLaroi.id).isEqualTo("stay_laroi_101")
        assertThat(reconstructedRihanna.getBestPlaybackUrl()).isEqualTo("https://aac.saavncdn.com/stay_rihanna_def.mp4")
        assertThat(reconstructedLaroi.getBestPlaybackUrl()).isEqualTo("https://aac.saavncdn.com/stay_laroi_def.mp4")
    }

    @Test
    fun testSearchQueryDoesNotWipeOutHistoricalSongWithMatchingTitle() {
        val song = Song(
            id = "hello_adele_555",
            title = "Hello",
            artists = "Adele",
            album = "25",
            imageUrl = "https://saavncdn.com/hello.jpg",
            durationSec = 295,
            mediaUrl = "https://aac.saavncdn.com/hello.mp4",
            mediaUrlDefault = null,
            previewUrl = null
        )
        dbHelper.recordSongSearch(song)

        // Now user types "Hello" into search box
        val queryItem = SearchHistoryItem(
            queryText = "Hello",
            title = "Hello",
            subtitle = "Search query",
            contentType = "Search",
            songId = null
        )
        dbHelper.recordSearchHistory(queryItem)

        val history = dbHelper.getRecentSearches()
        assertThat(history).hasSize(2)

        // The song entry must STILL exist with its stable ID!
        val songEntry = history.firstOrNull { it.songId == "hello_adele_555" }
        assertThat(songEntry).isNotNull
        assertThat(songEntry?.title).isEqualTo("Hello")
        assertThat(songEntry?.artists).isEqualTo("Adele")

        // The query entry also exists
        val queryEntry = history.firstOrNull { it.songId == null }
        assertThat(queryEntry).isNotNull
        assertThat(queryEntry?.queryText).isEqualTo("Hello")
    }

    @Test
    fun testReopeningDatabaseRestoresExactSongIdentity() {
        val originalSong = Song(
            id = "starboy_weeknd_999",
            title = "Starboy",
            artists = "The Weeknd, Daft Punk",
            album = "Starboy",
            imageUrl = "https://saavncdn.com/starboy.jpg",
            durationSec = 230,
            mediaUrl = "https://aac.saavncdn.com/starboy.mp4",
            mediaUrlDefault = "https://aac.saavncdn.com/starboy_default.mp4",
            previewUrl = "https://preview.saavncdn.com/starboy.mp3"
        )
        dbHelper.recordSongSearch(originalSong)

        // Simulate app restart / closing and reopening the SQLite database
        dbHelper.close()
        val reopenedDb = PlaylistDatabaseHelper(context)
        try {
            val history = reopenedDb.getRecentSearches()
            assertThat(history).hasSize(1)

            val entry = history[0]
            val restoredSong = entry.toSong()

            assertThat(restoredSong).isNotNull
            assertThat(restoredSong?.id).isEqualTo("starboy_weeknd_999")
            assertThat(restoredSong?.title).isEqualTo("Starboy")
            assertThat(restoredSong?.artists).isEqualTo("The Weeknd, Daft Punk")
            assertThat(restoredSong?.album).isEqualTo("Starboy")
            assertThat(restoredSong?.imageUrl).isEqualTo("https://saavncdn.com/starboy.jpg")
            assertThat(restoredSong?.durationSec).isEqualTo(230)
            assertThat(restoredSong?.mediaUrl).isEqualTo("https://aac.saavncdn.com/starboy.mp4")
            assertThat(restoredSong?.mediaUrlDefault).isEqualTo("https://aac.saavncdn.com/starboy_default.mp4")
            assertThat(restoredSong?.previewUrl).isEqualTo("https://preview.saavncdn.com/starboy.mp3")
            assertThat(restoredSong?.getBestPlaybackUrl()).isEqualTo("https://aac.saavncdn.com/starboy_default.mp4")
        } finally {
            reopenedDb.close()
        }
    }
}
