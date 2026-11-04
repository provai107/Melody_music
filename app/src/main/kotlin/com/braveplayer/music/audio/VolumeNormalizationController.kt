package com.braveplayer.music.audio

import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Normalizes perceived loudness across playback tracks using Android's LoudnessEnhancer
 * to avoid abrupt volume shifts without causing audio clipping.
 */
class VolumeNormalizationController(
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "VolumeNormalizationController"
        const val DEFAULT_TARGET_GAIN_MB = 120 // 1.2 dB gentle loudness alignment
    }

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentAudioSessionId: Int = 0

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    init {
        _isEnabled.value = appSettings.isVolumeNormalizationEnabled()
    }

    fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId == currentAudioSessionId && loudnessEnhancer != null) return
        releaseEnhancer()

        if (audioSessionId <= 0) return
        currentAudioSessionId = audioSessionId

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(DEFAULT_TARGET_GAIN_MB)
                    enabled = _isEnabled.value
                }
            } catch (e: Exception) {
                Log.w(TAG, "LoudnessEnhancer initialization fallback: ${e.message}")
                loudnessEnhancer = null
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        appSettings.setVolumeNormalizationEnabled(enabled)
        try {
            loudnessEnhancer?.enabled = enabled
        } catch (e: Exception) {
            Log.w(TAG, "Failed toggling volume normalization: ${e.message}")
        }
    }

    fun release() {
        releaseEnhancer()
        currentAudioSessionId = 0
    }

    private fun releaseEnhancer() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
        loudnessEnhancer = null
    }
}
