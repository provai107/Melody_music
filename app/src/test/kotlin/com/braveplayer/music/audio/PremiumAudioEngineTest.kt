package com.braveplayer.music.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.braveplayer.music.model.Song
import com.braveplayer.music.settings.AppSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PremiumAudioEngineTest {

    private lateinit var appSettings: AppSettings
    private lateinit var premiumAudioEngine: PremiumAudioEngine

    @Before
    fun setup() {
        PremiumAudioEngine.resetForTesting()
        val context = RuntimeEnvironment.getApplication()
        appSettings = AppSettings(context)
        premiumAudioEngine = PremiumAudioEngine.getInstance(context, appSettings)
    }

    // =========================================================================
    // 1. High-Quality Audio & Format Truthfulness Tests
    // =========================================================================

    @Test
    fun testAacAndMp3AreNeverLabeledLossless() {
        val aacFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setAverageBitrate(320_000)
            .setSampleRate(44100)
            .setChannelCount(2)
            .build()

        val aacInfo = AudioFormatInfo.fromFormat(aacFormat)
        assertThat(aacInfo.codec).isEqualTo("AAC")
        assertThat(aacInfo.isLossless).isFalse()
        assertThat(aacInfo.fidelityLabel).isNotEqualTo("Lossless")
        assertThat(aacInfo.fidelityLabel).contains("High Fidelity")

        val mp3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MPEG)
            .setAverageBitrate(320_000)
            .setSampleRate(44100)
            .setChannelCount(2)
            .build()

        val mp3Info = AudioFormatInfo.fromFormat(mp3Format)
        assertThat(mp3Info.codec).isEqualTo("MP3")
        assertThat(mp3Info.isLossless).isFalse()
        assertThat(mp3Info.fidelityLabel).isNotEqualTo("Lossless")
    }

    @Test
    fun testFlacAndAlacAreCorrectlyLabeledLossless() {
        val flacFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setAverageBitrate(900_000)
            .setSampleRate(48000)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build()

        val flacInfo = AudioFormatInfo.fromFormat(flacFormat)
        assertThat(flacInfo.codec).isEqualTo("FLAC")
        assertThat(flacInfo.isLossless).isTrue()
        assertThat(flacInfo.fidelityLabel).isEqualTo("Lossless")
        assertThat(flacInfo.bitDepth).isEqualTo(24)
        assertThat(flacInfo.sampleRateHz).isEqualTo(48000)

        val alacFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_ALAC)
            .setAverageBitrate(850_000)
            .setSampleRate(44100)
            .setChannelCount(2)
            .build()

        val alacInfo = AudioFormatInfo.fromFormat(alacFormat)
        assertThat(alacInfo.codec).isEqualTo("ALAC")
        assertThat(alacInfo.isLossless).isTrue()
        assertThat(alacInfo.fidelityLabel).isEqualTo("Lossless")
    }

    @Test
    fun testUrlExtensionFallbackCodecDetection() {
        val flacInfo = AudioFormatInfo.fromFormat(
            format = null,
            mediaUrl = "https://cdn.example.com/master_track.flac"
        )
        assertThat(flacInfo.codec).isEqualTo("FLAC")
        assertThat(flacInfo.isLossless).isTrue()

        val aacInfo = AudioFormatInfo.fromFormat(
            format = null,
            mediaUrl = "https://aac.saavncdn.com/song_320.m4a"
        )
        assertThat(aacInfo.codec).isEqualTo("AAC")
        assertThat(aacInfo.isLossless).isFalse()
    }

    // =========================================================================
    // 2. Adaptive Bitrate Selection & Song URL Resolution
    // =========================================================================

    @Test
    fun testAdaptiveBitrateSelectionBasedOnPreferences() {
        val song = Song(
            id = "test_song_adaptive",
            title = "Test Song",
            artists = "Test Artist",
            album = "Test Album",
            imageUrl = null,
            durationSec = 210,
            mediaUrl = "https://aac.saavncdn.com/stream_320.mp3",
            mediaUrlDefault = "https://aac.saavncdn.com/stream_96.mp4",
            previewUrl = "https://preview.saavncdn.com/preview.mp3"
        )

        // HIGH quality should prefer mediaUrl (320 kbps)
        val highUrl = song.getPlaybackUrl(AppSettings.QUALITY_HIGH)
        assertThat(highUrl).isEqualTo("https://aac.saavncdn.com/stream_320.mp3")

        // SAVER quality should prefer mediaUrlDefault (96 kbps)
        val saverUrl = song.getPlaybackUrl(AppSettings.QUALITY_SAVER)
        assertThat(saverUrl).isEqualTo("https://aac.saavncdn.com/stream_96.mp4")

        // LOSSLESS preference falls back gracefully to highest available quality (320k)
        val losslessUrl = song.getPlaybackUrl(AppSettings.QUALITY_LOSSLESS)
        assertThat(losslessUrl).isEqualTo("https://aac.saavncdn.com/stream_320.mp3")
    }

    @Test
    fun testSongQualityResolutionGracefulFallback() {
        val songOnlyFixed = Song(
            id = "song_fixed_only",
            title = "Fixed Stream Track",
            artists = "Solo",
            album = "Single",
            imageUrl = null,
            durationSec = 180,
            mediaUrl = null,
            mediaUrlDefault = "https://cdn.example.com/stream_fixed.mp4"
        )

        // When HIGH is requested on a stream without bitrate variants, graceful fallback to base stream
        assertThat(songOnlyFixed.getPlaybackUrl(AppSettings.QUALITY_HIGH))
            .isEqualTo("https://cdn.example.com/stream_fixed.mp4")

        val songUpgradeFallback = Song(
            id = "song_upgrade",
            title = "Upgradable Track",
            artists = "Solo",
            album = "Single",
            imageUrl = null,
            durationSec = 180,
            mediaUrl = null,
            mediaUrlDefault = "https://aac.saavncdn.com/stream_96.mp4"
        )
        // Adaptive bitrate upscaling from 96k to 320k
        assertThat(songUpgradeFallback.getPlaybackUrl(AppSettings.QUALITY_HIGH))
            .isEqualTo("https://aac.saavncdn.com/stream_320.mp4")

        val emptySong = Song(
            id = "song_empty",
            title = "Empty",
            artists = "None",
            album = "None",
            imageUrl = null,
            durationSec = 0
        )
        assertThat(emptySong.getPlaybackUrl(AppSettings.QUALITY_HIGH)).isNull()
    }

    // =========================================================================
    // 3. Spatial Audio Engine & Modes
    // =========================================================================

    @Test
    fun testSpatialAudioModesAndStrengths() {
        val spatialController = premiumAudioEngine.spatialController

        // Default mode is Stereo
        assertThat(spatialController.spatialMode.value).isEqualTo(SpatialMode.STEREO)
        assertThat(spatialController.getVirtualizerStrengthForMode(SpatialMode.STEREO)).isEqualTo(0.toShort())

        // Soundstage mode (natural subtle binaural wideness)
        spatialController.setSpatialMode(SpatialMode.SOUNDSTAGE)
        assertThat(spatialController.spatialMode.value).isEqualTo(SpatialMode.SOUNDSTAGE)
        assertThat(spatialController.getVirtualizerStrengthForMode(SpatialMode.SOUNDSTAGE)).isEqualTo(450.toShort())

        // Immersive 3D mode (maximum head-centric depth)
        spatialController.setSpatialMode(SpatialMode.IMMERSIVE)
        assertThat(spatialController.spatialMode.value).isEqualTo(SpatialMode.IMMERSIVE)
        assertThat(spatialController.getVirtualizerStrengthForMode(SpatialMode.IMMERSIVE)).isEqualTo(950.toShort())
    }

    @Test
    fun testSpatialAudioEnableToggleAndPersistence() {
        val spatialController = premiumAudioEngine.spatialController

        spatialController.setSpatialEnabled(true)
        assertThat(spatialController.isSpatialEnabled.value).isTrue()
        assertThat(appSettings.isSpatialAudioEnabled()).isTrue()

        spatialController.setSpatialEnabled(false)
        assertThat(spatialController.isSpatialEnabled.value).isFalse()
        assertThat(appSettings.isSpatialAudioEnabled()).isFalse()
    }

    // =========================================================================
    // 4. Equalizer Presets & Clamping
    // =========================================================================

    @Test
    fun testEqualizerPresetsBandGains() {
        val eq = premiumAudioEngine.equalizerController

        // Flat preset
        eq.applyPreset(EqualizerPreset.FLAT)
        assertThat(eq.activePreset.value).isEqualTo(EqualizerPreset.FLAT)
        val flatGains = eq.calculateGainsForBands(EqualizerPreset.FLAT, -1000, 1000)
        assertThat(flatGains).containsOnly(0.toShort())

        // Bass Boost preset
        eq.applyPreset(EqualizerPreset.BASS_BOOST)
        assertThat(eq.activePreset.value).isEqualTo(EqualizerPreset.BASS_BOOST)
        val bassGains = eq.calculateGainsForBands(EqualizerPreset.BASS_BOOST, -1000, 1000)
        // Band 0 (60 Hz) and Band 1 (230 Hz) should have positive gain
        assertThat(bassGains[0]).isGreaterThan(0.toShort())
        assertThat(bassGains[1]).isGreaterThan(0.toShort())

        // Vocal Clarity preset
        eq.applyPreset(EqualizerPreset.VOCAL_CLARITY)
        assertThat(eq.activePreset.value).isEqualTo(EqualizerPreset.VOCAL_CLARITY)
        val vocalGains = eq.calculateGainsForBands(EqualizerPreset.VOCAL_CLARITY, -1000, 1000)
        // Mid-range (Band 2 / 910 Hz and Band 3 / 3.6 kHz) should be elevated
        assertThat(vocalGains[2]).isGreaterThan(0.toShort())
        assertThat(vocalGains[3]).isGreaterThan(0.toShort())
    }

    @Test
    fun testEqualizerGainClampingProtectsHardwareBounds() {
        val eq = premiumAudioEngine.equalizerController
        val gains = eq.calculateGainsForBands(EqualizerPreset.ROCK, -500, 500)
        for (gain in gains) {
            assertThat(gain).isBetween((-500).toShort(), 500.toShort())
        }
    }

    // =========================================================================
    // 5. Volume Normalization & Crossfade
    // =========================================================================

    @Test
    fun testVolumeNormalizationToggle() {
        val volNorm = premiumAudioEngine.volumeNormalizationController

        volNorm.setEnabled(true)
        assertThat(volNorm.isEnabled.value).isTrue()
        assertThat(appSettings.isVolumeNormalizationEnabled()).isTrue()

        volNorm.setEnabled(false)
        assertThat(volNorm.isEnabled.value).isFalse()
        assertThat(appSettings.isVolumeNormalizationEnabled()).isFalse()
    }

    @Test
    fun testCrossfadeDurationClampingAndPersistence() {
        val crossfade = premiumAudioEngine.crossfadeController

        crossfade.setCrossfadeDuration(6)
        assertThat(crossfade.crossfadeDurationSeconds.value).isEqualTo(6)
        assertThat(appSettings.getCrossfadeDurationSeconds()).isEqualTo(6)

        // Negative duration clamped to 0
        crossfade.setCrossfadeDuration(-5)
        assertThat(crossfade.crossfadeDurationSeconds.value).isEqualTo(0)

        // Exorbitant duration clamped to 12
        crossfade.setCrossfadeDuration(30)
        assertThat(crossfade.crossfadeDurationSeconds.value).isEqualTo(12)
    }
}
