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
class LikedSongsDatabaseTest {

    private lateinit var context: Context
    private lateinit var dbHelper: PlaylistDatabaseHelper

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dbHelper = PlaylistDatabaseHelper(context)
    }

    @After
    fun tearDown() {
        dbHelper.close()
    }

    @Test
    fun testLikeAndUnlikeSong() {
        val song = Song(
            id = "liked_1",
            title = "Kesariya",
            artists = "Arijit Singh",
            album = "Brahmastra",
            imageUrl = "https://c.saavncdn.com/kesariya.jpg",
            durationSec = 268,
            mediaUrl = "https://aac.saavncdn.com/kesariya.mp3",
            mediaUrlDefault = "https://aac.saavncdn.com/kesariya.mp4",
            previewUrl = null
        )

        assertThat(dbHelper.isSongLiked("liked_1")).isFalse()

        // Like song
        val liked = dbHelper.likeSong(song)
        assertThat(liked).isTrue()
        assertThat(dbHelper.isSongLiked("liked_1")).isTrue()

        val allLiked = dbHelper.getAllLikedSongs()
        assertThat(allLiked).hasSize(1)
        assertThat(allLiked[0].title).isEqualTo("Kesariya")
        assertThat(dbHelper.getLikedSongIds()).contains("liked_1")

        // Unlike song
        val unliked = dbHelper.unlikeSong("liked_1")
        assertThat(unliked).isTrue()
        assertThat(dbHelper.isSongLiked("liked_1")).isFalse()
        assertThat(dbHelper.getAllLikedSongs()).isEmpty()
    }

    @Test
    fun testDuplicateLikeDoesNotCreateDuplicateRows() {
        val song = Song(
            id = "liked_dup",
            title = "Tum Hi Ho",
            artists = "Arijit Singh",
            album = "Aashiqui 2",
            imageUrl = null,
            durationSec = 262,
            mediaUrl = "https://aac.saavncdn.com/tumhiho.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        dbHelper.likeSong(song)
        dbHelper.likeSong(song)
        dbHelper.likeSong(song)

        val liked = dbHelper.getAllLikedSongs()
        assertThat(liked).hasSize(1)
        assertThat(liked[0].id).isEqualTo("liked_dup")
    }

    @Test
    fun testLikedSongsSortedByTimestampDescending() {
        val song1 = Song("s1", "First Song", "Artist", "Album", null, 100, "https://aac.saavncdn.com/1.mp3", null, null)
        val song2 = Song("s2", "Second Song", "Artist", "Album", null, 200, "https://aac.saavncdn.com/2.mp3", null, null)

        dbHelper.likeSong(song1)
        Thread.sleep(10) // Ensure distinct timestamps
        dbHelper.likeSong(song2)

        val allLiked = dbHelper.getAllLikedSongs()
        assertThat(allLiked).hasSize(2)
        assertThat(allLiked[0].id).isEqualTo("s2") // Newer like first
        assertThat(allLiked[1].id).isEqualTo("s1")
    }
}
