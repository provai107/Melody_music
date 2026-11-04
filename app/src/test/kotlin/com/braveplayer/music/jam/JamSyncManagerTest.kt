package com.braveplayer.music.jam

import com.braveplayer.music.jam.model.JamPlaybackState
import com.braveplayer.music.jam.model.JamSyncResponse
import com.braveplayer.music.jam.sync.JamSyncManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JamSyncManagerTest {

    private lateinit var syncManager: JamSyncManager

    @Before
    fun setup() {
        syncManager = JamSyncManager()
    }

    @Test
    fun testProcessSyncResponseCalculatesRttAndClockOffset() {
        val clientSendTime = 10000L
        val serverTimeAtReceive = 10050L // 50ms server time ahead of midpoint

        val syncResponse = JamSyncResponse(
            clientTimestamp = clientSendTime,
            serverTimestamp = serverTimeAtReceive,
            playbackState = JamPlaybackState(
                trackId = "song_1",
                isPlaying = true,
                positionMs = 5000L,
                serverTimestamp = serverTimeAtReceive
            ),
            calculatedPositionMs = 5000L,
            stateVersion = 1L
        )

        syncManager.processSyncResponse(syncResponse)

        // Verify RTT is positive
        assertTrue(syncManager.getEstimatedRtt() >= 0L)
        // Verify clock offset calculated
        assertNotNull(syncManager.getClockOffset())
    }

    @Test
    fun testTargetPositionWhenPaused() {
        val pausedState = JamPlaybackState(
            trackId = "song_1",
            isPlaying = false,
            positionMs = 30000L,
            serverTimestamp = System.currentTimeMillis()
        )

        syncManager.onPlaybackStateUpdated(pausedState)
        val target = syncManager.calculateCurrentTargetPositionMs()

        // When paused, target position is strictly the paused position
        assertEquals(30000L, target)
    }

    @Test
    fun testTargetPositionAdvancesWhenPlaying() {
        val now = System.currentTimeMillis()
        val playingState = JamPlaybackState(
            trackId = "song_1",
            isPlaying = true,
            positionMs = 10000L,
            serverTimestamp = now - 500 // 500ms elapsed
        )

        syncManager.onPlaybackStateUpdated(playingState)
        val target = syncManager.calculateCurrentTargetPositionMs()

        // Target position should be approximately 10000 + 500 = 10500ms
        assertTrue("Target position should advance when playing", target >= 10400L)
    }

    @Test
    fun testSyncThresholdConstants() {
        assertEquals(150L, JamSyncManager.THRESHOLD_IN_SYNC_MS)
        assertEquals(1200L, JamSyncManager.THRESHOLD_SEEK_CORRECTION_MS)
        assertEquals(0.97f, JamSyncManager.SPEED_SLOWER, 0.001f)
        assertEquals(1.03f, JamSyncManager.SPEED_FASTER, 0.001f)
        assertEquals(1.00f, JamSyncManager.SPEED_NORMAL, 0.001f)
    }
}
