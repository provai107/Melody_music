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
}
