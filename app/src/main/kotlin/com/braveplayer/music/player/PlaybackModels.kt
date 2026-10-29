package com.braveplayer.music.player

import com.braveplayer.music.model.Song
import java.util.UUID

/**
 * Context describing where the current playback originated from.
 */
enum class PlaybackContextType {
    SINGLE_TRACK,
    ALBUM,
    PLAYLIST,
    SEARCH_RESULTS,
    HISTORY,
    ARTIST,
    RADIO,
    FAVORITES
}

data class PlaybackContext(
    val type: PlaybackContextType,
    val id: String,
    val title: String,
    val songs: List<Song> = emptyList()
) {
    companion object {
        fun single(song: Song) = PlaybackContext(
            type = PlaybackContextType.SINGLE_TRACK,
            id = song.id,
            title = song.title,
            songs = listOf(song)
        )

        fun playlist(playlistId: Long, name: String, songs: List<Song>) = PlaybackContext(
            type = PlaybackContextType.PLAYLIST,
            id = playlistId.toString(),
            title = name,
            songs = songs
        )

        fun album(albumName: String, songs: List<Song>) = PlaybackContext(
            type = PlaybackContextType.ALBUM,
            id = albumName,
            title = albumName,
            songs = songs
        )

        fun search(query: String, songs: List<Song>) = PlaybackContext(
            type = PlaybackContextType.SEARCH_RESULTS,
            id = query,
            title = "Search: $query",
            songs = songs
        )

        fun history(songs: List<Song>) = PlaybackContext(
            type = PlaybackContextType.HISTORY,
            id = "history",
            title = "Listening History",
            songs = songs
        )

        fun favorites(songs: List<Song>) = PlaybackContext(
            type = PlaybackContextType.FAVORITES,
            id = "favorites",
            title = "Liked Songs",
            songs = songs
        )

        fun radio(seedSong: Song, songs: List<Song>) = PlaybackContext(
            type = PlaybackContextType.RADIO,
            id = "radio_${seedSong.id}",
            title = "${seedSong.title} Radio",
            songs = songs
        )

        fun artist(artistId: String, artistName: String, songs: List<Song>) = PlaybackContext(
            type = PlaybackContextType.ARTIST,
            id = artistId,
            title = "Artist: $artistName",
            songs = songs
        )
    }
}

/**
 * Origin of a track in the playback queue.
 */
enum class QueueOrigin {
    PLAY_NEXT,     // Added via "Play Next" (highest upcoming priority)
    ADD_TO_QUEUE,  // Added via "Add to Queue" (manual user queue)
    CONTEXT,       // From active album, playlist, or search context
    AUTOPLAY       // Generated automated recommendation
}

/**
 * An item in the playback queue with a unique stable entry ID.
 */
data class QueueItem(
    val queueEntryId: String = UUID.randomUUID().toString(),
    val song: Song,
    val origin: QueueOrigin,
    val contextTitle: String? = null
)

/**
 * Repeat playback modes.
 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

/**
 * High-level playback status.
 */
enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

/**
 * Immutable snapshot of the full playback and queue state.
 */
data class QueueSnapshot(
    val current: QueueItem?,
    val playNext: List<QueueItem>,
    val manualQueue: List<QueueItem>,
    val contextTracks: List<QueueItem>,
    val autoplayQueue: List<QueueItem>,
    val history: List<QueueItem>,
    val isShuffle: Boolean,
    val repeatMode: RepeatMode,
    val autoplayEnabled: Boolean,
    val playbackContext: PlaybackContext?
) {
    /**
     * All upcoming tracks in exact playback priority order:
     * 1. Play Next items (insertion order preserved)
     * 2. Add to Queue items (insertion order preserved)
     * 3. Context tracks (original or shuffled order)
     * 4. Autoplay recommendations (if autoplay enabled)
     */
    fun allUpcoming(): List<QueueItem> {
        val list = ArrayList<QueueItem>(playNext.size + manualQueue.size + contextTracks.size + autoplayQueue.size)
        list.addAll(playNext)
        list.addAll(manualQueue)
        list.addAll(contextTracks)
        if (autoplayEnabled && repeatMode != RepeatMode.ALL) {
            list.addAll(autoplayQueue)
        }
        return list
    }

    val totalTrackCount: Int
        get() = (if (current != null) 1 else 0) + playNext.size + manualQueue.size + contextTracks.size + (if (autoplayEnabled) autoplayQueue.size else 0)

    val isEmpty: Boolean
        get() = current == null && playNext.isEmpty() && manualQueue.isEmpty() && contextTracks.isEmpty() && autoplayQueue.isEmpty()
}
