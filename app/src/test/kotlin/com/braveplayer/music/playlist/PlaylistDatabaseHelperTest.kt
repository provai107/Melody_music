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
class PlaylistDatabaseHelperTest {

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
    fun testCreateAndGetAllPlaylists() {
        val id1 = dbHelper.createPlaylist("Chill Vibes")
        val id2 = dbHelper.createPlaylist("Workout Hits")

        assertThat(id1).isGreaterThan(0L)
        assertThat(id2).isGreaterThan(0L)

        val playlists = dbHelper.getAllPlaylists()
        assertThat(playlists).hasSize(2)
        assertThat(playlists.map { it.name }).containsExactlyInAnyOrder("Chill Vibes", "Workout Hits")
    }

    @Test
    fun testAddAndGetSongsInPlaylist() {
        val playlistId = dbHelper.createPlaylist("Favorites")
        val song1 = Song(
            id = "s1",
            title = "Song One",
            artists = "Artist A",
            album = "Album X",
            imageUrl = "https://c.saavncdn.com/s1.jpg",
            durationSec = 210,
            mediaUrl = "https://aac.saavncdn.com/s1.mp3",
            mediaUrlDefault = "https://aac.saavncdn.com/s1.mp4",
            previewUrl = null
        )
        val song2 = Song(
            id = "s2",
            title = "Song Two",
            artists = "Artist B",
            album = "Album Y",
            imageUrl = null,
            durationSec = 180,
            mediaUrl = "https://aac.saavncdn.com/s2.mp3",
            mediaUrlDefault = null,
            previewUrl = "https://jiotunepreview.jio.com/s2.mp3"
        )

        val added1 = dbHelper.addSongToPlaylist(playlistId, song1)
        val added2 = dbHelper.addSongToPlaylist(playlistId, song2)
        assertThat(added1).isTrue()
        assertThat(added2).isTrue()

        val songs = dbHelper.getSongsInPlaylist(playlistId)
        assertThat(songs).hasSize(2)
        assertThat(songs[0].id).isEqualTo("s1")
        assertThat(songs[0].title).isEqualTo("Song One")
        assertThat(songs[0].mediaUrlDefault).isEqualTo("https://aac.saavncdn.com/s1.mp4")
        assertThat(songs[1].id).isEqualTo("s2")
        assertThat(songs[1].title).isEqualTo("Song Two")
    }

    @Test
    fun testRemoveSongFromPlaylist() {
        val playlistId = dbHelper.createPlaylist("Road Trip")
        val song = Song(
            id = "track_to_remove",
            title = "Remove Me",
            artists = "Unknown",
            album = "None",
            imageUrl = null,
            durationSec = 100,
            mediaUrl = "https://aac.saavncdn.com/track.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        dbHelper.addSongToPlaylist(playlistId, song)
        assertThat(dbHelper.getSongsInPlaylist(playlistId)).hasSize(1)

        val removed = dbHelper.removeSongFromPlaylist(playlistId, "track_to_remove")
        assertThat(removed).isTrue()
        assertThat(dbHelper.getSongsInPlaylist(playlistId)).isEmpty()
    }

    @Test
    fun testDeletePlaylistCascadesToSongs() {
        val playlistId = dbHelper.createPlaylist("Temporary")
        val song = Song(
            id = "temp_song",
            title = "Temp Track",
            artists = "Temp Artist",
            album = "Temp Album",
            imageUrl = null,
            durationSec = 60,
            mediaUrl = "https://aac.saavncdn.com/temp.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        dbHelper.addSongToPlaylist(playlistId, song)
        assertThat(dbHelper.getAllPlaylists()).hasSize(1)
        assertThat(dbHelper.getSongsInPlaylist(playlistId)).hasSize(1)

        val deleted = dbHelper.deletePlaylist(playlistId)
        assertThat(deleted).isTrue()
        assertThat(dbHelper.getAllPlaylists()).isEmpty()
        assertThat(dbHelper.getSongsInPlaylist(playlistId)).isEmpty()
    }

    @Test
    fun testSaveAndGetDownloadedSongs() {
        val song = Song(
            id = "dl_1",
            title = "Downloaded Track",
            artists = "Offline Artist",
            album = "Offline Album",
            imageUrl = "https://c.saavncdn.com/dl1.jpg",
            durationSec = 200,
            mediaUrl = "https://aac.saavncdn.com/dl1.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        assertThat(dbHelper.isSongDownloaded("dl_1")).isFalse()
        val rowId = dbHelper.saveDownloadedSong(song, "/storage/emulated/0/Music/BravePlayer/Downloaded_Track.mp3")
        assertThat(rowId).isGreaterThan(0L)
        assertThat(dbHelper.isSongDownloaded("dl_1")).isTrue()

        val allDl = dbHelper.getAllDownloadedSongs()
        assertThat(allDl).hasSize(1)
        assertThat(allDl[0].id).isEqualTo("dl_1")
        assertThat(allDl[0].title).isEqualTo("Downloaded Track")
        assertThat(allDl[0].mediaUrl).isEqualTo("/storage/emulated/0/Music/BravePlayer/Downloaded_Track.mp3")

        val deleted = dbHelper.deleteDownloadedSong("dl_1")
        assertThat(deleted).isTrue()
        assertThat(dbHelper.isSongDownloaded("dl_1")).isFalse()
        assertThat(dbHelper.getAllDownloadedSongs()).isEmpty()
    }
}
