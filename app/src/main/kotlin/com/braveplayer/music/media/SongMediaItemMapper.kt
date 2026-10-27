package com.braveplayer.music.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.braveplayer.music.model.Song

/**
 * Maps between domain model [Song] and AndroidX Media3 [MediaItem].
 * Stores metadata and alternate candidate playback URLs in extras for seamless fallback.
 */
object SongMediaItemMapper {

    const val EXTRA_SONG_ID = "song_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTISTS = "artists"
    const val EXTRA_ALBUM = "album"
    const val EXTRA_IMAGE_URL = "image_url"
    const val EXTRA_DURATION_SEC = "duration_sec"
    const val EXTRA_MEDIA_URL = "media_url"
    const val EXTRA_MEDIA_URL_DEF = "media_url_default"
    const val EXTRA_PREVIEW_URL = "preview_url"
    const val EXTRA_CANDIDATE_URLS = "candidate_urls"
    const val EXTRA_CURRENT_URL_INDEX = "current_url_index"
    const val EXTRA_QUEUE_ORIGIN = "queue_origin"
    const val EXTRA_CONTEXT_NAME = "context_name"

    const val ORIGIN_MANUAL = "MANUAL"
    const val ORIGIN_CONTEXT = "CONTEXT"
    const val ORIGIN_AUTOPLAY = "AUTOPLAY"

    fun toMediaItem(
        song: Song,
        origin: String = ORIGIN_CONTEXT,
        contextName: String? = null
    ): MediaItem {
        val candidateUrls = song.getCandidatePlaybackUrls()
        val rawUri = song.getBestPlaybackUrl() ?: ""
        val primaryUri = if (rawUri.startsWith("/")) Uri.fromFile(java.io.File(rawUri)).toString() else rawUri

        val extras = Bundle().apply {
            putString(EXTRA_SONG_ID, song.id)
            putString(EXTRA_TITLE, song.title)
            putString(EXTRA_ARTISTS, song.artists)
            putString(EXTRA_ALBUM, song.album)
            putString(EXTRA_IMAGE_URL, song.imageUrl)
            putInt(EXTRA_DURATION_SEC, song.durationSec)
            putString(EXTRA_MEDIA_URL, song.mediaUrl)
            putString(EXTRA_MEDIA_URL_DEF, song.mediaUrlDefault)
            putString(EXTRA_PREVIEW_URL, song.previewUrl)
            putStringArrayList(EXTRA_CANDIDATE_URLS, ArrayList(candidateUrls))
            putInt(EXTRA_CURRENT_URL_INDEX, 0)
            putString(EXTRA_QUEUE_ORIGIN, origin)
            if (!contextName.isNullOrBlank()) {
                putString(EXTRA_CONTEXT_NAME, contextName)
            }
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artists)
            .setAlbumTitle(song.album)
            .setArtworkUri(if (!song.imageUrl.isNullOrBlank()) Uri.parse(song.imageUrl) else null)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(primaryUri)
            .setMediaMetadata(metadata)
            .build()
    }

    fun getQueueOrigin(mediaItem: MediaItem): String {
        return mediaItem.mediaMetadata.extras?.getString(EXTRA_QUEUE_ORIGIN) ?: ORIGIN_CONTEXT
    }

    fun getContextName(mediaItem: MediaItem): String? {
        return mediaItem.mediaMetadata.extras?.getString(EXTRA_CONTEXT_NAME)
    }

    fun fromMediaItem(mediaItem: MediaItem): Song {
        val extras = mediaItem.mediaMetadata.extras
        if (extras != null && extras.containsKey(EXTRA_SONG_ID)) {
            return Song(
                id = extras.getString(EXTRA_SONG_ID, mediaItem.mediaId),
                title = extras.getString(EXTRA_TITLE, mediaItem.mediaMetadata.title?.toString() ?: "Unknown Title"),
                artists = extras.getString(EXTRA_ARTISTS, mediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist"),
                album = extras.getString(EXTRA_ALBUM, mediaItem.mediaMetadata.albumTitle?.toString() ?: "Unknown Album"),
                imageUrl = extras.getString(EXTRA_IMAGE_URL, mediaItem.mediaMetadata.artworkUri?.toString()),
                durationSec = extras.getInt(EXTRA_DURATION_SEC, 0),
                mediaUrl = extras.getString(EXTRA_MEDIA_URL),
                mediaUrlDefault = extras.getString(EXTRA_MEDIA_URL_DEF),
                previewUrl = extras.getString(EXTRA_PREVIEW_URL)
            )
        }

        val uriString = mediaItem.requestMetadata.mediaUri?.toString()
            ?: mediaItem.localConfiguration?.uri?.toString()

        return Song(
            id = mediaItem.mediaId,
            title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown Title",
            artists = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist",
            album = mediaItem.mediaMetadata.albumTitle?.toString() ?: "Unknown Album",
            imageUrl = mediaItem.mediaMetadata.artworkUri?.toString(),
            durationSec = 0,
            mediaUrl = uriString,
            mediaUrlDefault = null,
            previewUrl = null
        )
    }

    /**
     * Builds a new MediaItem with the fallback candidate URL at candidateIndex.
     */
    fun createFallbackMediaItem(mediaItem: MediaItem, candidateIndex: Int): MediaItem? {
        val extras = mediaItem.mediaMetadata.extras ?: return null
        val candidates = extras.getStringArrayList(EXTRA_CANDIDATE_URLS) ?: return null
        if (candidateIndex < 0 || candidateIndex >= candidates.size) return null

        val newUri = candidates[candidateIndex]
        val updatedExtras = Bundle(extras).apply {
            putInt(EXTRA_CURRENT_URL_INDEX, candidateIndex)
        }

        val updatedMetadata = mediaItem.mediaMetadata.buildUpon()
            .setExtras(updatedExtras)
            .build()

        return mediaItem.buildUpon()
            .setUri(newUri)
            .setMediaMetadata(updatedMetadata)
            .build()
    }
}
