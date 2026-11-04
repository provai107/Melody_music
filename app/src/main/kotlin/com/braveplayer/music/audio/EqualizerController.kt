package com.braveplayer.music.audio

import android.media.audiofx.Equalizer
import android.util.Log
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqBand(
    val index: Short,
    val centerFreqHz: Int,
    val levelMb: Short,
    val minLevelMb: Short,
    val maxLevelMb: Short
)

enum class EqualizerPreset(val displayName: String) {
    FLAT("Flat"),
    BASS_BOOST("Bass Boost"),
    VOCAL_CLARITY("Vocal Clarity"),
    ROCK("Rock"),
    ACOUSTIC("Acoustic"),
    ELECTRONIC("Electronic"),
    CUSTOM("Custom");

    companion object {
        fun fromDisplayName(name: String): EqualizerPreset {
            return values().firstOrNull { it.displayName.equals(name, ignoreCase = true) } ?: FLAT
        }
    }
}

/**
 * Manages the 5-band audio equalizer with safe gain clamping and built-in acoustic presets.
 */
class EqualizerController(
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "EqualizerController"

        const val PRESET_FLAT = "Flat"
        const val PRESET_BASS_BOOST = "Bass Boost"
        const val PRESET_VOCAL = "Vocal Clarity"
        const val PRESET_ROCK = "Rock"
        const val PRESET_ACOUSTIC = "Acoustic"
        const val PRESET_ELECTRONIC = "Electronic"
        const val PRESET_CUSTOM = "Custom"

        val ALL_PRESETS = listOf(
            PRESET_FLAT,
            PRESET_BASS_BOOST,
            PRESET_VOCAL,
            PRESET_ROCK,
            PRESET_ACOUSTIC,
            PRESET_ELECTRONIC,
            PRESET_CUSTOM
        )
    }

    private var equalizer: Equalizer? = null
    private var currentAudioSessionId: Int = 0

    private val _isEqualizerEnabled = MutableStateFlow(false)
    val isEqualizerEnabled: StateFlow<Boolean> = _isEqualizerEnabled.asStateFlow()
    val isEnabled: StateFlow<Boolean> get() = _isEqualizerEnabled

    private val _currentPreset = MutableStateFlow(PRESET_FLAT)
    val currentPreset: StateFlow<String> = _currentPreset.asStateFlow()

    private val _activePreset = MutableStateFlow(EqualizerPreset.FLAT)
    val activePreset: StateFlow<EqualizerPreset> = _activePreset.asStateFlow()

    private val _bands = MutableStateFlow<List<EqBand>>(emptyList())
    val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

    init {
        _isEqualizerEnabled.value = appSettings.isEqualizerEnabled()
        val savedPreset = appSettings.getEqualizerPreset()
        _currentPreset.value = savedPreset
        _activePreset.value = EqualizerPreset.fromDisplayName(savedPreset)
    }

    fun applyPreset(preset: EqualizerPreset) {
        _activePreset.value = preset
        setPreset(preset.displayName)
    }

    fun calculateGainsForBands(preset: EqualizerPreset, minMb: Short, maxMb: Short): List<Short> {
        val gains = when (preset) {
            EqualizerPreset.BASS_BOOST -> listOf(600, 350, 0, 0, 0)
            EqualizerPreset.VOCAL_CLARITY -> listOf(-200, 200, 500, 350, 100)
            EqualizerPreset.ROCK -> listOf(500, 250, -100, 300, 500)
            EqualizerPreset.ACOUSTIC -> listOf(300, 150, 300, 400, 300)
            EqualizerPreset.ELECTRONIC -> listOf(600, 400, 0, 300, 600)
            else -> listOf(0, 0, 0, 0, 0)
        }
        return gains.map { it.toShort().coerceIn(minMb, maxMb) }
    }

    fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId == currentAudioSessionId && equalizer != null) return
        releaseEqualizer()

        if (audioSessionId <= 0) return
        currentAudioSessionId = audioSessionId

        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = _isEqualizerEnabled.value
                applyPresetInternal(_currentPreset.value)
                refreshBands(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer initialization fallback: ${e.message}")
            equalizer = null
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEqualizerEnabled.value = enabled
        appSettings.setEqualizerEnabled(enabled)
        try {
            equalizer?.enabled = enabled
        } catch (e: Exception) {
            Log.w(TAG, "Failed toggling equalizer: ${e.message}")
        }
    }

    fun setPreset(presetName: String) {
        _currentPreset.value = presetName
        appSettings.setEqualizerPreset(presetName)
        applyPresetInternal(presetName)
        equalizer?.let { refreshBands(it) }
    }

    fun setBandLevel(bandIndex: Short, levelMb: Short) {
        val eq = equalizer ?: return
        try {
            val range = eq.bandLevelRange
            val clamped = levelMb.coerceIn(range[0], range[1])
            eq.setBandLevel(bandIndex, clamped)
            _currentPreset.value = PRESET_CUSTOM
            appSettings.setEqualizerPreset(PRESET_CUSTOM)
            refreshBands(eq)
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting band level: ${e.message}")
        }
    }

    private fun applyPresetInternal(presetName: String) {
        val eq = equalizer ?: return
        try {
            val numBands = eq.numberOfBands.toInt()
            if (numBands <= 0) return

            // Relative target millibels for standard 5-band configuration (60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz)
            val gains = when (presetName) {
                PRESET_BASS_BOOST -> listOf(600, 350, 0, 0, 0)
                PRESET_VOCAL -> listOf(-200, 200, 500, 350, 100)
                PRESET_ROCK -> listOf(500, 250, -100, 300, 500)
                PRESET_ACOUSTIC -> listOf(300, 150, 300, 400, 300)
                PRESET_ELECTRONIC -> listOf(600, 400, 0, 300, 600)
                else -> listOf(0, 0, 0, 0, 0) // FLAT
            }

            val range = eq.bandLevelRange
            for (i in 0 until numBands) {
                val band = i.toShort()
                val targetGain = gains.getOrNull(i) ?: 0
                val clamped = targetGain.toShort().coerceIn(range[0], range[1])
                eq.setBandLevel(band, clamped)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed applying preset $presetName: ${e.message}")
        }
    }

    private fun refreshBands(eq: Equalizer) {
        try {
            val numBands = eq.numberOfBands
            val range = eq.bandLevelRange
            val list = mutableListOf<EqBand>()
            for (i in 0 until numBands) {
                val band = i.toShort()
                val freq = eq.getCenterFreq(band) / 1000 // Convert mHz to Hz
                val level = eq.getBandLevel(band)
                list.add(EqBand(band, freq, level, range[0], range[1]))
            }
            _bands.value = list
        } catch (e: Exception) {
            Log.w(TAG, "Failed refreshing bands: ${e.message}")
        }
    }

    fun release() {
        releaseEqualizer()
        currentAudioSessionId = 0
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (_: Exception) {}
        equalizer = null
    }
}
