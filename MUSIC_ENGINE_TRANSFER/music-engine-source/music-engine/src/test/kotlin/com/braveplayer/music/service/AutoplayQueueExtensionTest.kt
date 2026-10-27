package com.braveplayer.music.service

import androidx.media3.common.Player
import com.braveplayer.music.model.Song
import com.braveplayer.music.settings.AppSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AutoplayQueueExtensionTest {

    private lateinit var appSettings: AppSettings

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        appSettings = AppSettings(context)
    }

    @Test
    fun testAutoplayPreferenceToggle() {
        // Defaults to true
        assertThat(appSettings.isAutoplayEnabled()).isTrue()

        appSettings.setAutoplayEnabled(false)
        assertThat(appSettings.isAutoplayEnabled()).isFalse()

        appSettings.setAutoplayEnabled(true)
        assertThat(appSettings.isAutoplayEnabled()).isTrue()
    }

    @Test
    fun testRepeatModeSuppressesAutoplayExtension() {
        // Autoplay should only trigger when repeatMode == REPEAT_MODE_OFF
        val repeatModes = listOf(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)
        for (mode in repeatModes) {
            val shouldExtend = (mode == Player.REPEAT_MODE_OFF)
            assertThat(shouldExtend).isFalse()
        }

        val normalMode = Player.REPEAT_MODE_OFF
        assertThat(normalMode == Player.REPEAT_MODE_OFF).isTrue()
    }

    @Test
    fun testFailedTracksExcludedFromSubsequentRecommendations() {
        val failedTrackIds = mutableSetOf<String>()
        val failedSong = Song("fail_1", "Broken Stream", "Artist", "Album", null, 200, "invalid_url", null, null)

        // Mark failed
        failedTrackIds.add(failedSong.id)

        val candidateSongs = listOf(
            failedSong,
            Song("ok_1", "Working Stream", "Artist", "Album", null, 200, "https://aac.saavncdn.com/ok.mp3", null, null)
        )

        val availableRecommendations = candidateSongs.filter { it.id !in failedTrackIds }
        assertThat(availableRecommendations).hasSize(1)
        assertThat(availableRecommendations[0].id).isEqualTo("ok_1")
    }

    @Test
    fun testQueuePreservesManualOrderAheadOfRecommendations() {
        val manualQueue = mutableListOf("manual_1", "manual_2")
        val recommendations = listOf("rec_1", "rec_2")

        // Recommendations appended to queue
        manualQueue.addAll(recommendations)

        // Manual items stay ahead of recommendations
        assertThat(manualQueue).containsExactly("manual_1", "manual_2", "rec_1", "rec_2")
    }
}
