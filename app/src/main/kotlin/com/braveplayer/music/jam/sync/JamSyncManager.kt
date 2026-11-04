package com.braveplayer.music.jam.sync

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.braveplayer.music.jam.model.JamPlaybackState
import com.braveplayer.music.jam.model.JamSyncResponse
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * High-precision playback synchronization and drift compensation engine for Melody Jam.
 * Implements NTP-style clock alignment, latency compensation, and gentle speed trimming.
 */
class JamSyncManager(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {

    companion object {
        const val THRESHOLD_IN_SYNC_MS = 150L     // Under 150ms: perfectly in sync, no-op
        const val THRESHOLD_SEEK_CORRECTION_MS = 1200L // Over 1200ms: hard seek required
        const val SPEED_SLOWER = 0.97f            // Smooth trim if local player is ahead
        const val SPEED_FASTER = 1.03f            // Smooth trim if local player is behind
        const val SPEED_NORMAL = 1.00f
    }

    private var attachedPlayer: Player? = null
    private var isHost: Boolean = false
    private var lastAuthoritativeState: JamPlaybackState? = null

    // NTP clock offset: serverTime = System.currentTimeMillis() + clockOffsetMs
    private val clockOffsetMs = AtomicLong(0L)
    private val estimatedRttMs = AtomicLong(0L)

    private var syncLoopJob: Job? = null
    private var onSyncRequestListener: (() -> Unit)? = null
    private var onDriftReported: ((driftMs: Long, isCorrecting: Boolean) -> Unit)? = null

    fun setOnSyncRequestListener(listener: () -> Unit) {
        onSyncRequestListener = listener
    }

    fun setOnDriftReported(listener: (driftMs: Long, isCorrecting: Boolean) -> Unit) {
        onDriftReported = listener
    }

    fun attachPlayer(player: Player, isHost: Boolean) {
        this.attachedPlayer = player
        this.isHost = isHost
        if (!isHost) {
            startPeriodicSync()
        } else {
            stopPeriodicSync()
            player.playbackParameters = PlaybackParameters(SPEED_NORMAL)
        }
    }

    fun detachPlayer() {
        stopPeriodicSync()
        attachedPlayer?.playbackParameters = PlaybackParameters(SPEED_NORMAL)
        this.attachedPlayer = null
    }

    fun setIsHost(isHost: Boolean) {
        this.isHost = isHost
        if (isHost) {
            stopPeriodicSync()
            attachedPlayer?.playbackParameters = PlaybackParameters(SPEED_NORMAL)
        } else {
            startPeriodicSync()
        }
    }

    /**
     * Updates NTP clock offset and RTT from server sync response.
     */
    fun processSyncResponse(response: JamSyncResponse) {
        val now = System.currentTimeMillis()
        val rtt = (now - response.clientTimestamp).coerceAtLeast(1L)
        estimatedRttMs.set(rtt)

        // NTP clock offset formula: T_server - (T_client_send + RTT / 2)
        val serverTimeAtMidpoint = response.serverTimestamp
        val clientMidpoint = response.clientTimestamp + (rtt / 2)
        val offset = serverTimeAtMidpoint - clientMidpoint
        clockOffsetMs.set(offset)

        lastAuthoritativeState = response.playbackState
        evaluateDriftAndCorrect()
    }

    /**
     * Updates playback state received from real-time WebSocket broadcast.
     */
    fun onPlaybackStateUpdated(state: JamPlaybackState) {
        lastAuthoritativeState = state
        if (!isHost) {
            evaluateDriftAndCorrect()
        }
    }

    /**
     * Calculates current authoritative room position compensating for latency and clock offset.
     */
    fun calculateCurrentTargetPositionMs(): Long {
        val state = lastAuthoritativeState ?: return attachedPlayer?.currentPosition ?: 0L
        if (!state.isPlaying) {
            return state.positionMs
        }

        val localNow = System.currentTimeMillis()
        val currentServerTime = localNow + clockOffsetMs.get()
        val elapsedSinceUpdate = (currentServerTime - state.serverTimestamp).coerceAtLeast(0L)
        return state.positionMs + elapsedSinceUpdate
    }

    /**
     * Evaluates difference between local ExoPlayer position and authoritative room position.
     * Applies appropriate micro-adjustments or seek commands.
     */
    fun evaluateDriftAndCorrect() {
        if (isHost) return // Host is authoritative timeline; never correct host

        coroutineScope.launch(Dispatchers.Main) {
            val currentPlayer = attachedPlayer ?: return@launch
            val state = lastAuthoritativeState ?: return@launch
            val targetPos = calculateCurrentTargetPositionMs()
            val localPos = currentPlayer.currentPosition
            val driftMs = localPos - targetPos // positive = local is ahead, negative = local is behind
            val absDrift = abs(driftMs)

            if (!state.isPlaying) {
                // Paused: halt playback completely on guest so music does not play in background
                if (currentPlayer.isPlaying || currentPlayer.playWhenReady) {
                    currentPlayer.playWhenReady = false
                    currentPlayer.pause()
                }
                if (absDrift > 200L) {
                    currentPlayer.seekTo(targetPos.coerceAtLeast(0L))
                }
                currentPlayer.playbackParameters = PlaybackParameters(SPEED_NORMAL)
                onDriftReported?.invoke(absDrift, false)
                return@launch
            }

            // Playing: ensure local player is playing
            if (!currentPlayer.isPlaying && currentPlayer.playbackState != Player.STATE_ENDED) {
                currentPlayer.playWhenReady = true
                currentPlayer.play()
            }

            // Apply 3-tier correction strategy
            when {
                absDrift < THRESHOLD_IN_SYNC_MS -> {
                    // Tier 1: Perfect sync (< 150ms). Restore normal 1.0x playback speed.
                    if (currentPlayer.playbackParameters.speed != SPEED_NORMAL) {
                        currentPlayer.playbackParameters = PlaybackParameters(SPEED_NORMAL)
                    }
                    onDriftReported?.invoke(absDrift, false)
                }

                absDrift in THRESHOLD_IN_SYNC_MS..THRESHOLD_SEEK_CORRECTION_MS -> {
                    // Tier 2: Minor drift (150ms - 1200ms). Apply gentle speed trim.
                    val targetSpeed = if (driftMs > 0) SPEED_SLOWER else SPEED_FASTER
                    if (currentPlayer.playbackParameters.speed != targetSpeed) {
                        currentPlayer.playbackParameters = PlaybackParameters(targetSpeed)
                    }
                    onDriftReported?.invoke(absDrift, true)
                }

                else -> {
                    // Tier 3: Significant drift (> 1200ms). Hard seek directly to target position.
                    currentPlayer.seekTo(targetPos.coerceAtLeast(0L))
                    currentPlayer.playbackParameters = PlaybackParameters(SPEED_NORMAL)
                    onDriftReported?.invoke(0L, false)
                }
            }
        }
    }

    private fun startPeriodicSync() {
        syncLoopJob?.cancel()
        syncLoopJob = coroutineScope.launch {
            while (isActive) {
                delay(4000L) // Request sync verification every 4 seconds
                if (!isHost && attachedPlayer != null) {
                    onSyncRequestListener?.invoke()
                    evaluateDriftAndCorrect()
                }
            }
        }
    }

    private fun stopPeriodicSync() {
        syncLoopJob?.cancel()
        syncLoopJob = null
    }

    fun getEstimatedRtt(): Long = estimatedRttMs.get()
    fun getClockOffset(): Long = clockOffsetMs.get()
}
