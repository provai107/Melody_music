package com.braveplayer.music.settings

import android.content.Context
import androidx.media3.common.Player
import com.braveplayer.music.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QueuePersistenceManagerTest {

    private lateinit var context: Context
    private lateinit var manager: QueuePersistenceManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = QueuePersistenceManager(context)
        manager.clearQueueState()
    }

    @Test
    fun testEmptyStateReturnsDefaultValues() {
        val state = manager.restoreQueueState()
        assertThat(state.songs).isEmpty()
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(state.positionMs).isEqualTo(0L)
        assertThat(state.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
        assertThat(state.shuffleMode).isFalse()
    }

    @Test
    fun testSaveAndRestoreQueueState() {
        val songs = listOf(
            Song(
                id = "song_1",
                title = "Title 1",
                artists = "Artist 1",
                album = "Album 1",
                imageUrl = "https://c.saavncdn.com/1.jpg",
                durationSec = 200,
                mediaUrl = "https://aac.saavncdn.com/1_320.mp3",
                mediaUrlDefault = "https://aac.saavncdn.com/1_96.mp4",
                previewUrl = "https://jiotunepreview.jio.com/1.mp3"
            ),
            Song(
                id = "song_2",
                title = "Title 2",
                artists = "Artist 2",
                album = "Album 2",
                imageUrl = null,
                durationSec = 150,
                mediaUrl = "https://aac.saavncdn.com/2.mp3",
                mediaUrlDefault = null,
                previewUrl = null
            )
        )

        manager.saveQueueState(
            songs = songs,
            currentIndex = 1,
            positionMs = 45000L,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleMode = true
        )

        val restored = manager.restoreQueueState()
        assertThat(restored.songs).hasSize(2)
        assertThat(restored.songs[0].id).isEqualTo("song_1")
        assertThat(restored.songs[0].title).isEqualTo("Title 1")
        assertThat(restored.songs[0].mediaUrlDefault).isEqualTo("https://aac.saavncdn.com/1_96.mp4")
        assertThat(restored.songs[1].id).isEqualTo("song_2")
        assertThat(restored.currentIndex).isEqualTo(1)
        assertThat(restored.positionMs).isEqualTo(45000L)
        assertThat(restored.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(restored.shuffleMode).isTrue()
    }

    @Test
    fun testClearQueueState() {
        val song = Song(
            id = "song_x",
            title = "Track X",
            artists = "Artist",
            album = "Album",
            imageUrl = null,
            durationSec = 120,
            mediaUrl = "https://aac.saavncdn.com/x.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        manager.saveQueueState(listOf(song), 0, 1000L, Player.REPEAT_MODE_ONE, false)
        assertThat(manager.restoreQueueState().songs).hasSize(1)

        manager.clearQueueState()
        val emptyState = manager.restoreQueueState()
        assertThat(emptyState.songs).isEmpty()
        assertThat(emptyState.positionMs).isEqualTo(0L)
    }
}
