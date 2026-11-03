package com.braveplayer.music.jam.model

import com.braveplayer.music.model.Song

enum class JamRole {
    HOST,
    GUEST
}

enum class JamConnectionStatus {
    CONNECTED,
    DISCONNECTED
}

enum class JamSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    IN_ROOM,
    RECONNECTING,
    ERROR
}

data class JamSongSummary(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val imageUrl: String? = null,
    val durationSec: Int = 0,
    val mediaUrlDefault: String? = null
) {
    fun toSong(): Song {
        return Song(
            id = id,
            title = title,
            artists = artists,
            album = album,
            imageUrl = imageUrl,
            durationSec = durationSec,
            mediaUrlDefault = mediaUrlDefault
        )
    }

    companion object {
        fun fromSong(song: Song): JamSongSummary {
            return JamSongSummary(
                id = song.id,
                title = song.title,
                artists = song.artists,
                album = song.album,
                imageUrl = song.imageUrl,
                durationSec = song.durationSec,
                mediaUrlDefault = song.mediaUrlDefault ?: song.mediaUrl
            )
        }
    }
}

data class JamParticipant(
    val participantId: String,
    val guestId: String,
    val displayName: String,
    val avatarIndex: Int = 0,
    val role: JamRole = JamRole.GUEST,
    val connectionStatus: JamConnectionStatus = JamConnectionStatus.CONNECTED,
    val joinedAt: Long = 0L
)

data class JamPlaybackState(
    val trackId: String? = null,
    val songSummary: JamSongSummary? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val serverTimestamp: Long = 0L,
    val stateVersion: Long = 1L
)

data class JamQueueItem(
    val queueItemId: String,
    val trackId: String,
    val songSummary: JamSongSummary,
    val addedByGuestId: String,
    val addedByName: String,
    val addedAt: Long = 0L
)

data class JamRoomSettings(
    val allowGuestControl: Boolean = false,
    val allowGuestQueueAdd: Boolean = true,
    val allowGuestQueueRemoveOwn: Boolean = true
)

data class JamRoom(
    val roomId: String,
    val roomCode: String,
    val hostGuestId: String,
    val visibility: String = "PRIVATE",
    val createdAt: Long = 0L,
    val settings: JamRoomSettings = JamRoomSettings(),
    val playbackState: JamPlaybackState = JamPlaybackState(),
    val queue: List<JamQueueItem> = emptyList(),
    val participants: List<JamParticipant> = emptyList(),
    val serverTimestamp: Long = 0L
)

data class JamRoomPreview(
    val roomId: String,
    val roomCode: String,
    val hostDisplayName: String,
    val participantCount: Int,
    val currentTrack: JamSongSummary? = null,
    val isPlaying: Boolean = false
)

data class JamGuestSession(
    val guestId: String,
    val displayName: String,
    val token: String,
    val expiresAt: Long
)

data class JamReaction(
    val guestId: String,
    val displayName: String,
    val emoji: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class JamSyncResponse(
    val clientTimestamp: Long,
    val serverTimestamp: Long,
    val playbackState: JamPlaybackState,
    val calculatedPositionMs: Long,
    val stateVersion: Long
)
