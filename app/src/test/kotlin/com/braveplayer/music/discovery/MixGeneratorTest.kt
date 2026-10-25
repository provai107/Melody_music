package com.braveplayer.music.discovery

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MixGeneratorTest {

    private lateinit var dbHelper: PlaylistDatabaseHelper
    private lateinit var engine: MusicRecommendationEngine
    private lateinit var mixGenerator: MixGenerator

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        dbHelper = PlaylistDatabaseHelper(context)
        val filterManager = FilterListManager(emptyList())
        val metadataService = MusicMetadataService(filterManager)
        engine = MusicRecommendationEngine(metadataService, dbHelper)
        mixGenerator = MixGenerator(engine, dbHelper)
    }

    @Test
    fun testDiscoveryMixPrioritizesUnfamiliarArtists() {
        val frequentArtist = "arijit singh"
        val novelArtist = "prateek kuhad"

        val familiarSong = Song(
            id = "fam",
            title = "Familiar Song",
            artists = "Arijit Singh",
            album = "Album",
            imageUrl = null,
            durationSec = 220,
            mediaUrl = "https://aac.saavncdn.com/fam.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        val novelSong = Song(
            id = "nov",
            title = "Cold/Mess",
            artists = "Prateek Kuhad",
            album = "Cold/Mess EP",
            imageUrl = null,
            durationSec = 250,
            mediaUrl = "https://aac.saavncdn.com/nov.mp3",
            mediaUrlDefault = null,
            previewUrl = null
        )

        val candidates = listOf(familiarSong, novelSong)
        val frequentArtistsSet = setOf(frequentArtist)

        val rankedDiscovery = mixGenerator.rankDiscoveryCandidates(
            candidates = candidates,
            frequentArtists = frequentArtistsSet,
            excludeSongIds = emptySet()
        )

        // Novel artist must rank FIRST in Discovery Mix
        assertThat(rankedDiscovery.first().id).isEqualTo("nov")
        assertThat(rankedDiscovery.last().id).isEqualTo("fam")
    }

    @Test
    fun testSaveMixAsPlaylistPersistsInDatabase() {
        val song1 = Song("m1", "Mix Song 1", "Artist 1", "Album 1", null, 180, "https://aac.saavncdn.com/1.mp3", null, null)
        val song2 = Song("m2", "Mix Song 2", "Artist 2", "Album 2", null, 210, "https://aac.saavncdn.com/2.mp3", null, null)

        val mix = PersonalizedMix(
            id = "test_mix",
            title = "Summer Chill",
            description = "Test Mix Description",
            songs = listOf(song1, song2)
        )

        val playlistId = mixGenerator.saveMixAsPlaylist(mix, "My Summer Chill")
        assertThat(playlistId).isGreaterThan(0L)

        val retrievedSongs = dbHelper.getSongsInPlaylist(playlistId)
        assertThat(retrievedSongs).hasSize(2)
        assertThat(retrievedSongs.map { it.id }).containsExactly("m1", "m2")
    }
}
