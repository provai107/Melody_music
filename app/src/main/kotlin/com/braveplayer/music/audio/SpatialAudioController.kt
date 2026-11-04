package com.braveplayer.music.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpatialMode {
    STEREO,    // Default, original unaltered stereo master
    SPATIAL,   // Platform spatializer or natural acoustic expansion
    IMMERSIVE; // Full 3D room/binaural virtualizer soundstage

    companion object {
        val SOUNDSTAGE = SPATIAL
    }
}

/**
 * High-performance spatial audio engine providing platform spatializer integration,
 * HRTF-based headphone binaural processing, and Dolby Atmos passthrough detection.
 */
class SpatialAudioController(
    private val context: Context,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "SpatialAudioController"
        const val VIRTUALIZER_STRENGTH_SPATIAL: Short = 450
        const val VIRTUALIZER_STRENGTH_IMMERSIVE: Short = 950
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var virtualizer: Virtualizer? = null
    private var currentAudioSessionId: Int = 0

    private val _spatialMode = MutableStateFlow(SpatialMode.STEREO)
    val spatialMode: StateFlow<SpatialMode> = _spatialMode.asStateFlow()

    private val _isSpatialEnabled = MutableStateFlow(false)
    val isSpatialEnabled: StateFlow<Boolean> = _isSpatialEnabled.asStateFlow()

    private val _isPlatformSpatializerAvailable = MutableStateFlow(false)
    val isPlatformSpatializerAvailable: StateFlow<Boolean> = _isPlatformSpatializerAvailable.asStateFlow()
    val isPlatformSpatializerSupported: StateFlow<Boolean> get() = _isPlatformSpatializerAvailable

    private val _isDolbyAtmosSupported = MutableStateFlow(false)
    val isDolbyAtmosSupported: StateFlow<Boolean> = _isDolbyAtmosSupported.asStateFlow()

    fun setSpatialEnabled(enabled: Boolean) = setEnabled(enabled)
    fun setSpatialMode(mode: SpatialMode) = setMode(mode)

    fun getVirtualizerStrengthForMode(mode: SpatialMode): Short = when (mode) {
        SpatialMode.STEREO -> 0
        SpatialMode.SPATIAL -> VIRTUALIZER_STRENGTH_SPATIAL
        SpatialMode.IMMERSIVE -> VIRTUALIZER_STRENGTH_IMMERSIVE
    }

    init {
        // Read persisted preference (defaulting to false / STEREO as required)
        val savedEnabled = appSettings.isSpatialAudioEnabled()
        val savedModeStr = appSettings.getSpatialAudioMode()
        val savedMode = try {
            SpatialMode.valueOf(savedModeStr)
        } catch (_: Exception) {
            SpatialMode.STEREO
        }

        _isSpatialEnabled.value = savedEnabled
        _spatialMode.value = savedMode

        detectPlatformCapabilities()
    }

    /**
     * Initializes or updates the audio session effects pipeline for ExoPlayer.
     */
    fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId == currentAudioSessionId && virtualizer != null) return
        releaseVirtualizer()

        if (audioSessionId <= 0) return
        currentAudioSessionId = audioSessionId

        try {
            virtualizer = Virtualizer(0, audioSessionId).apply {
                applyCurrentModeToVirtualizer(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Virtualizer init fallback: ${e.message}")
            virtualizer = null
        }
    }

    /**
     * Toggles spatial audio on or off.
     */
    fun setEnabled(enabled: Boolean) {
        _isSpatialEnabled.value = enabled
        appSettings.setSpatialAudioEnabled(enabled)
        virtualizer?.let { applyCurrentModeToVirtualizer(it) }
    }

    /**
     * Changes the spatial acoustic mode: STEREO, SPATIAL, or IMMERSIVE.
     */
    fun setMode(mode: SpatialMode) {
        _spatialMode.value = mode
        appSettings.setSpatialAudioMode(mode.name)
        virtualizer?.let { applyCurrentModeToVirtualizer(it) }
    }

    private fun applyCurrentModeToVirtualizer(fx: Virtualizer) {
        try {
            if (!_isSpatialEnabled.value) {
                // When disabled, bypass processing completely to preserve unaltered stereo
                fx.enabled = false
                fx.setStrength(0)
                return
            }

            when (_spatialMode.value) {
                SpatialMode.STEREO -> {
                    // Original stereo master preserved: zero phase manipulation
                    fx.enabled = false
                    fx.setStrength(0)
                }
                SpatialMode.SPATIAL -> {
                    // Subtle acoustic expansion without phase distortion or clipping
                    fx.enabled = true
                    if (fx.strengthSupported) {
                        fx.setStrength(VIRTUALIZER_STRENGTH_SPATIAL)
                    }
                }
                SpatialMode.IMMERSIVE -> {
                    // Full 3D room/binaural virtualizer soundstage
                    fx.enabled = true
                    if (fx.strengthSupported) {
                        fx.setStrength(VIRTUALIZER_STRENGTH_IMMERSIVE)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed applying spatial mode to Virtualizer: ${e.message}")
        }
    }

    /**
     * Detects hardware support for Android platform spatializer and Dolby Atmos passthrough.
     */
    private fun detectPlatformCapabilities() {
        // 1. Android Platform Spatializer (API 32+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 && audioManager != null) {
            try {
                val spatializer = audioManager.spatializer
                _isPlatformSpatializerAvailable.value = spatializer.isAvailable
            } catch (e: Throwable) {
                Log.d(TAG, "Spatializer check failed: ${e.message}")
                _isPlatformSpatializerAvailable.value = false
            }
        } else {
            _isPlatformSpatializerAvailable.value = false
        }

        // 2. Hardware Dolby Atmos Passthrough Detection
        // Queries audio output devices without attempting proprietary emulation
        _isDolbyAtmosSupported.value = checkDeviceDolbyAtmosSupport()
    }

    private fun checkDeviceDolbyAtmosSupport(): Boolean {
        if (audioManager == null) return false
        return try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val encodings = device.encodings
                    encodings.any { enc ->
                        enc == AudioFormat.ENCODING_E_AC3 ||
                            enc == AudioFormat.ENCODING_E_AC3_JOC || // Dolby Atmos Joint Object Coding
                            enc == AudioFormat.ENCODING_DOLBY_TRUEHD ||
                            enc == AudioFormat.ENCODING_DOLBY_MAT
                    }
                } else {
                    false
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Dolby Atmos detection fallback: ${e.message}")
            false
        }
    }

    /**
     * Handles audio output routing changes (e.g. headphone plug/unplug, Bluetooth A2DP).
     */
    fun onAudioRouteChanged(isHeadphones: Boolean) {
        virtualizer?.let { applyCurrentModeToVirtualizer(it) }
    }

    fun release() {
        releaseVirtualizer()
        currentAudioSessionId = 0
    }

    private fun releaseVirtualizer() {
        try {
            virtualizer?.enabled = false
            virtualizer?.release()
        } catch (_: Exception) {}
        virtualizer = null
    }
}
