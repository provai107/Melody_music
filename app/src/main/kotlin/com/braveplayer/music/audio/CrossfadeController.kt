package com.braveplayer.music.audio

import androidx.media3.common.Player
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages smooth acoustic crossfading and volume fades between playback tracks.
 */
class CrossfadeController(
    private val appSettings: AppSettings,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        const val STEP_INTERVAL_MS = 40L
    }

    private var attachedPlayer: Player? = null
    private var fadeJob: Job? = null

    private val _crossfadeSeconds = MutableStateFlow(0)
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds.asStateFlow()
    val crossfadeDurationSeconds: StateFlow<Int> get() = _crossfadeSeconds

    init {
        _crossfadeSeconds.value = appSettings.getCrossfadeDurationSeconds()
    }

    fun attachPlayer(player: Player?) {
        this.attachedPlayer = player
    }

    fun setCrossfadeDuration(seconds: Int) {
        val clamped = seconds.coerceIn(0, 12)
        _crossfadeSeconds.value = clamped
        appSettings.setCrossfadeDurationSeconds(clamped)
    }

    /**
     * Smoothly fades player volume from current level up to 1.0f.
     */
    fun fadeIn(durationMs: Long = (_crossfadeSeconds.value * 1000L).coerceAtLeast(1000L)) {
        val player = attachedPlayer ?: return
        if (_crossfadeSeconds.value == 0) {
            player.volume = 1.0f
            return
        }

        fadeJob?.cancel()
        fadeJob = scope.launch {
            player.volume = 0.0f
            val steps = (durationMs / STEP_INTERVAL_MS).coerceAtLeast(1)
            for (i in 1..steps) {
                delay(STEP_INTERVAL_MS)
                val fraction = i.toFloat() / steps.toFloat()
                player.volume = fraction.coerceIn(0f, 1f)
            }
            player.volume = 1.0f
        }
    }

    /**
     * Smoothly fades player volume down to 0.0f, then calls [onComplete].
     */
    fun fadeOut(
        durationMs: Long = (_crossfadeSeconds.value * 1000L).coerceAtLeast(1000L),
        onComplete: (() -> Unit)? = null
    ) {
        val player = attachedPlayer ?: run {
            onComplete?.invoke()
            return
        }

        if (_crossfadeSeconds.value == 0) {
            onComplete?.invoke()
            return
        }

        fadeJob?.cancel()
        fadeJob = scope.launch {
            val initialVolume = player.volume
            val steps = (durationMs / STEP_INTERVAL_MS).coerceAtLeast(1)
            for (i in 1..steps) {
                delay(STEP_INTERVAL_MS)
                val fraction = 1.0f - (i.toFloat() / steps.toFloat())
                player.volume = (initialVolume * fraction).coerceIn(0f, 1f)
            }
            player.volume = 0.0f
            onComplete?.invoke()
            // Reset back to unity volume for next track
            player.volume = 1.0f
        }
    }

    fun cancelFade() {
        fadeJob?.cancel()
        attachedPlayer?.volume = 1.0f
    }
}
