package com.braveplayer.music.jam

import com.braveplayer.music.jam.model.*
import com.braveplayer.music.model.Song
import org.junit.Assert.*
import org.junit.Test

class JamModelsTest {

    @Test
    fun testSongSummaryConversion() {
        val song = Song(
            id = "s_12345",
            title = "Aadat",
            artists = "Atif Aslam",
            album = "Jal Pari",
            imageUrl = "https://c.saavncdn.com/123/aadat.jpg",
            durationSec = 280,
            mediaUrlDefault = "https://aac.saavncdn.com/123/aadat_320.mp4"
        )

        val summary = JamSongSummary.fromSong(song)
        assertEquals("s_12345", summary.id)
        assertEquals("Aadat", summary.title)
        assertEquals("Atif Aslam", summary.artists)
        assertEquals("Jal Pari", summary.album)
        assertEquals("https://c.saavncdn.com/123/aadat.jpg", summary.imageUrl)
        assertEquals(280, summary.durationSec)
        assertEquals("https://aac.saavncdn.com/123/aadat_320.mp4", summary.mediaUrlDefault)

        val reconverted = summary.toSong()
        assertEquals(song.id, reconverted.id)
        assertEquals(song.title, reconverted.title)
        assertEquals(song.artists, reconverted.artists)
        assertEquals(song.album, reconverted.album)
        assertEquals(song.durationSec, reconverted.durationSec)
    }

    @Test
    fun testJamRoomDefaults() {
        val room = JamRoom(
            roomId = "room_abc123",
            roomCode = "JAM-4821",
            hostGuestId = "gst_host01"
        )

        assertEquals("room_abc123", room.roomId)
        assertEquals("JAM-4821", room.roomCode)
        assertEquals("gst_host01", room.hostGuestId)
        assertEquals("PRIVATE", room.visibility)
        assertFalse(room.settings.allowGuestControl)
        assertTrue(room.settings.allowGuestQueueAdd)
        assertTrue(room.settings.allowGuestQueueRemoveOwn)
        assertTrue(room.queue.isEmpty())
        assertTrue(room.participants.isEmpty())
    }

    @Test
    fun testJamParticipantRoles() {
        val host = JamParticipant(
            participantId = "p_01",
            guestId = "gst_01",
            displayName = "Host Atif",
            role = JamRole.HOST,
            connectionStatus = JamConnectionStatus.CONNECTED
        )

        val guest = JamParticipant(
            participantId = "p_02",
            guestId = "gst_02",
            displayName = "Guest Arijit",
            role = JamRole.GUEST,
            connectionStatus = JamConnectionStatus.CONNECTED
        )

        assertEquals(JamRole.HOST, host.role)
        assertEquals(JamRole.GUEST, guest.role)
        assertEquals(JamConnectionStatus.CONNECTED, host.connectionStatus)
    }
}
