package com.braveplayer.music.media

import com.braveplayer.music.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SongMediaItemMapperTest {

    @Test
    fun testToMediaItemAndFromMediaItemBidirectionalMapping() {
        val song = Song(
            id = "test_song_123",
            title = "Awesome Track",
            artists = "Great Artist",
            album = "Masterpiece Album",
            imageUrl = "https://c.saavncdn.com/art.jpg",
            durationSec = 245,
            mediaUrl = "https://aac.saavncdn.com/audio_320.mp3",
            mediaUrlDefault = "https://aac.saavncdn.com/audio_96.mp4",
            previewUrl = "https://jiotunepreview.jio.com/audio.mp3"
        )

        val mediaItem = SongMediaItemMapper.toMediaItem(song)
        assertThat(mediaItem.mediaId).isEqualTo("test_song_123")
        assertThat(mediaItem.mediaMetadata.title.toString()).isEqualTo("Awesome Track")
        assertThat(mediaItem.mediaMetadata.artist.toString()).isEqualTo("Great Artist")
        assertThat(mediaItem.mediaMetadata.albumTitle.toString()).isEqualTo("Masterpiece Album")
        assertThat(mediaItem.mediaMetadata.artworkUri.toString()).isEqualTo("https://c.saavncdn.com/art.jpg")

        // Reconstruct Song
        val reconstructed = SongMediaItemMapper.fromMediaItem(mediaItem)
        assertThat(reconstructed.id).isEqualTo(song.id)
        assertThat(reconstructed.title).isEqualTo(song.title)
        assertThat(reconstructed.artists).isEqualTo(song.artists)
        assertThat(reconstructed.album).isEqualTo(song.album)
        assertThat(reconstructed.imageUrl).isEqualTo(song.imageUrl)
        assertThat(reconstructed.durationSec).isEqualTo(song.durationSec)
        assertThat(reconstructed.mediaUrl).isEqualTo(song.mediaUrl)
        assertThat(reconstructed.mediaUrlDefault).isEqualTo(song.mediaUrlDefault)
        assertThat(reconstructed.previewUrl).isEqualTo(song.previewUrl)
    }

    @Test
    fun testFallbackCandidateMediaItemGeneration() {
        val song = Song(
            id = "song_with_fallbacks",
            title = "Fallback Song",
            artists = "Singer",
            album = "Album",
            imageUrl = null,
            durationSec = 180,
            mediaUrl = "https://aac.saavncdn.com/stream_320.mp3",
            mediaUrlDefault = "https://aac.saavncdn.com/stream_96.mp4",
            previewUrl = "https://jiotunepreview.jio.com/sample.mp3"
        )

        // Default HIGH quality preference resolves to 320 kbps stream
        val mediaItem = SongMediaItemMapper.toMediaItem(song)
        assertThat(mediaItem.localConfiguration?.uri.toString()).isEqualTo("https://aac.saavncdn.com/stream_320.mp3")

        // SAVER quality preference resolves to 96 kbps stream
        val mediaItemSaver = SongMediaItemMapper.toMediaItem(song, qualityPreference = "SAVER")
        assertThat(mediaItemSaver.localConfiguration?.uri.toString()).isEqualTo("https://aac.saavncdn.com/stream_96.mp4")

        // Candidate fallback at index 1: mediaUrl
        val fallback1 = SongMediaItemMapper.createFallbackMediaItem(mediaItem, 1)
        assertThat(fallback1).isNotNull
        assertThat(fallback1?.localConfiguration?.uri.toString()).isEqualTo("https://aac.saavncdn.com/stream_320.mp3")

        // Candidate fallback at index 2: previewUrl
        val fallback2 = SongMediaItemMapper.createFallbackMediaItem(mediaItem, 2)
        assertThat(fallback2).isNotNull
        assertThat(fallback2?.localConfiguration?.uri.toString()).isEqualTo("https://jiotunepreview.jio.com/sample.mp3")

        // Out of bounds candidate index returns null
        val fallbackOut = SongMediaItemMapper.createFallbackMediaItem(mediaItem, 3)
        assertThat(fallbackOut).isNull()
    }
}
