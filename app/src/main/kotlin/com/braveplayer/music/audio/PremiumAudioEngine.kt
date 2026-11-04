package com.braveplayer.music.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Premium Audio Engine coordinating High-Quality Audio Playback, Spatial Audio Processing,
 * Equalizer, Volume Normalization, and Crossfade.
 */
class PremiumAudioEngine(
    private val context: Context,
    private val appSettings: AppSettings
) {
    companion object {
        @Volatile
        private var INSTANCE: PremiumAudioEngine? = null

        fun getInstance(context: Context, appSettings: AppSettings = AppSettings(context)): PremiumAudioEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PremiumAudioEngine(context.applicationContext, appSettings).also { INSTANCE = it }
            }
        }

        fun resetForTesting() {
            INSTANCE = null
        }
    }

    val spatialController = SpatialAudioController(context, appSettings)
    val equalizerController = EqualizerController(appSettings)
    val volumeNormalizationController = VolumeNormalizationController(appSettings)
    val crossfadeController = CrossfadeController(appSettings)

    private var attachedPlayer: ExoPlayer? = null

    private val _formatInfo = MutableStateFlow(AudioFormatInfo.default())
    val formatInfo: StateFlow<AudioFormatInfo> = _formatInfo.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            updateFormatFromTracks(tracks)
        }
    }

    /**
     * Attaches the active ExoPlayer instance to the Premium Audio Engine pipeline.
     */
    fun attachPlayer(exoPlayer: ExoPlayer) {
        if (attachedPlayer == exoPlayer) return
        detachPlayer()

        attachedPlayer = exoPlayer
        exoPlayer.addListener(playerListener)
        crossfadeController.attachPlayer(exoPlayer)

        val sessionId = exoPlayer.audioSessionId
        if (sessionId > 0) {
            onAudioSessionIdChanged(sessionId)
        }

        updateFormatFromTracks(exoPlayer.currentTracks)
    }

    fun detachPlayer() {
        attachedPlayer?.removeListener(playerListener)
        crossfadeController.attachPlayer(null)
        spatialController.release()
        equalizerController.release()
        volumeNormalizationController.release()
        attachedPlayer = null
    }

    fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        spatialController.attachAudioSession(audioSessionId)
        equalizerController.attachAudioSession(audioSessionId)
        volumeNormalizationController.attachAudioSession(audioSessionId)
    }

    private fun updateFormatFromTracks(tracks: Tracks) {
        var activeFormat: Format? = null

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        activeFormat = group.getTrackFormat(i)
                        break
                    }
                }
            }
            if (activeFormat != null) break
        }

        val isSpatial = spatialController.isSpatialEnabled.value &&
            spatialController.spatialMode.value != SpatialMode.STEREO
        val isDolby = spatialController.isDolbyAtmosSupported.value

        _formatInfo.value = AudioFormatInfo.fromFormat(
            format = activeFormat,
            mediaUrl = null,
            isSpatialActive = isSpatial,
            isDolbyAtmosAvailable = isDolby
        )
    }

    fun updateCurrentMediaUrl(url: String?) {
        val current = _formatInfo.value
        val isSpatial = spatialController.isSpatialEnabled.value &&
            spatialController.spatialMode.value != SpatialMode.STEREO
        val isDolby = spatialController.isDolbyAtmosSupported.value

        _formatInfo.value = AudioFormatInfo.fromFormat(
            format = null,
            mediaUrl = url,
            isSpatialActive = isSpatial,
            isDolbyAtmosAvailable = isDolby
        ).copy(
            bitrateKbps = if (current.bitrateKbps > 0) current.bitrateKbps else 320,
            sampleRateHz = if (current.sampleRateHz > 0) current.sampleRateHz else 44100
        )
    }
}
