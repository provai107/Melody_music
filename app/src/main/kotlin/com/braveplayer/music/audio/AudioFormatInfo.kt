package com.braveplayer.music.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * Encapsulates the detected real-time audio format, codec, sample rate, bit depth,
 * and fidelity tier of the active playback stream.
 *
 * Strict Compliance:
 * - AAC or MP3 sources are NEVER labeled as lossless.
 * - Only genuine FLAC, ALAC, or uncompressed PCM with bit depth >= 16 are labeled as lossless.
 */
data class AudioFormatInfo(
    val codec: String = "AAC",
    val bitrateKbps: Int = 320,
    val sampleRateHz: Int = 44100,
    val bitDepth: Int = 16,
    val channelCount: Int = 2,
    val isLossless: Boolean = false,
    val isSpatialActive: Boolean = false,
    val isDolbyAtmosAvailable: Boolean = false
) {
    val tierLabel: String
        get() = when {
            isLossless -> "LOSSLESS"
            bitrateKbps >= 320 -> "HIGH QUALITY"
            bitrateKbps >= 160 -> "STANDARD"
            else -> "DATA SAVER"
        }

    val fidelityLabel: String
        get() = if (isLossless) "Lossless" else "High Fidelity"

    val badgeText: String
        get() = when {
            isLossless -> "LOSSLESS"
            bitrateKbps >= 320 -> "320K"
            bitrateKbps >= 256 -> "256K"
            bitrateKbps >= 160 -> "HQ"
            else -> "96K"
        }

    val channelsDescription: String
        get() = when (channelCount) {
            1 -> "Mono"
            2 -> if (isSpatialActive) "Spatialized Stereo" else "Stereo"
            6 -> "5.1 Surround"
            8 -> "7.1 Surround"
            else -> "$channelCount Channels"
        }

    val technicalSummary: String
        get() = buildString {
            append(codec)
            if (bitrateKbps > 0) append(" • ${bitrateKbps} kbps")
            if (sampleRateHz > 0) append(" • ${"%.1f".format(sampleRateHz / 1000.0)} kHz")
            if (bitDepth > 0) append(" • ${bitDepth}-bit")
            append(" • $channelsDescription")
            if (isLossless) append(" [Lossless Audio]")
            if (isDolbyAtmosAvailable) append(" [Dolby Atmos]")
        }

    companion object {
        fun default(): AudioFormatInfo = AudioFormatInfo()

        /**
         * Resolves format information from an ExoPlayer [Format] and media URL.
         */
        fun fromFormat(
            format: Format?,
            mediaUrl: String? = null,
            isSpatialActive: Boolean = false,
            isDolbyAtmosAvailable: Boolean = false
        ): AudioFormatInfo {
            val urlLower = mediaUrl?.lowercase() ?: ""
            val mime = format?.sampleMimeType?.lowercase() ?: ""

            // 1. Identify Codec
            val codec = when {
                mime.contains("flac") || urlLower.endsWith(".flac") -> "FLAC"
                mime.contains("alac") || urlLower.endsWith(".alac") -> "ALAC"
                mime.contains("opus") || urlLower.endsWith(".opus") -> "Opus"
                mime.contains("mpeg") || mime.contains("mp3") || urlLower.endsWith(".mp3") -> "MP3"
                mime.contains("mp4a") || mime.contains("aac") || urlLower.endsWith(".mp4") || urlLower.endsWith(".m4a") -> "AAC"
                mime.contains("vorbis") -> "Vorbis"
                mime.contains("wav") || urlLower.endsWith(".wav") -> "PCM WAV"
                else -> if (urlLower.contains(".mp4") || urlLower.contains(".m4a")) "AAC" else "AAC"
            }

            // 2. Strict Lossless determination:
            // AAC and MP3 are lossy by definition and MUST NEVER be labeled as lossless.
            val isLosslessCodec = (codec == "FLAC" || codec == "ALAC" || codec == "PCM WAV")

            // 3. Resolve Bitrate
            val rawBitrate = format?.bitrate ?: -1
            val bitrateKbps = when {
                rawBitrate > 0 -> rawBitrate / 1000
                urlLower.contains("_320") -> 320
                urlLower.contains("_160") -> 160
                urlLower.contains("_96") -> 96
                isLosslessCodec -> 1411 // standard CD lossless baseline
                else -> 320
            }

            // 4. Resolve Sample Rate
            val sampleRateHz = when {
                format != null && format.sampleRate > 0 -> format.sampleRate
                else -> 44100
            }

            // 5. Resolve Bit Depth
            val bitDepth = when {
                format != null && format.pcmEncoding == C.ENCODING_PCM_24BIT -> 24
                format != null && format.pcmEncoding == C.ENCODING_PCM_32BIT -> 32
                isLosslessCodec && (sampleRateHz >= 96000 || urlLower.contains("24bit")) -> 24
                else -> 16
            }

            // 6. Channel Count
            val channelCount = format?.channelCount?.takeIf { it > 0 } ?: 2

            return AudioFormatInfo(
                codec = codec,
                bitrateKbps = bitrateKbps,
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
                channelCount = channelCount,
                isLossless = isLosslessCodec,
                isSpatialActive = isSpatialActive,
                isDolbyAtmosAvailable = isDolbyAtmosAvailable
            )
        }
    }
}
