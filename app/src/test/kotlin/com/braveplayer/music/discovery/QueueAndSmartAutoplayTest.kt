package com.braveplayer.music.discovery

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.media.SongMediaItemMapper
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QueueAndSmartAutoplayTest {

    private lateinit var appSettings: AppSettings
    private lateinit var dbHelper: PlaylistDatabaseHelper
    private lateinit var engine: MusicRecommendationEngine
    private lateinit var mixGenerator: MixGenerator

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        appSettings = AppSettings(context)
        dbHelper = PlaylistDatabaseHelper(context)
        val filterManager = FilterListManager(emptyList())
        val metadataService = MusicMetadataService(filterManager)
        engine = MusicRecommendationEngine(metadataService, dbHelper)
        mixGenerator = MixGenerator(engine, dbHelper)
    }

    @Test
    fun testQueueOriginAndContextTaggingInMediaItem() {
        val song = Song(
            id = "q_1",
            title = "Yellow",
            artists = "Coldplay",
            album = "Parachutes",
            imageUrl = "https://images.example.com/yellow.jpg",
            durationSec = 269,
            mediaUrl = "https://aac.saavncdn.com/yellow.mp4",
            mediaUrlDefault = null,
            previewUrl = null
        )

        // 1. Manual Queue Item
        val manualItem = SongMediaItemMapper.toMediaItem(
            song = song,
            origin = SongMediaItemMapper.ORIGIN_MANUAL,
            contextName = "Queued"
        )
        assertThat(SongMediaItemMapper.getQueueOrigin(manualItem)).isEqualTo(SongMediaItemMapper.ORIGIN_MANUAL)
        assertThat(SongMediaItemMapper.getContextName(manualItem)).isEqualTo("Queued")

        // 2. Context Item (from Daily Mix)
        val contextItem = SongMediaItemMapper.toMediaItem(
            song = song,
            origin = SongMediaItemMapper.ORIGIN_CONTEXT,
            contextName = "Daily Mix 1"
        )
        assertThat(SongMediaItemMapper.getQueueOrigin(contextItem)).isEqualTo(SongMediaItemMapper.ORIGIN_CONTEXT)
        assertThat(SongMediaItemMapper.getContextName(contextItem)).isEqualTo("Daily Mix 1")

        // 3. Autoplay Recommendation
        val autoplayItem = SongMediaItemMapper.toMediaItem(
            song = song,
            origin = SongMediaItemMapper.ORIGIN_AUTOPLAY,
            contextName = "Similar to Clocks"
        )
        assertThat(SongMediaItemMapper.getQueueOrigin(autoplayItem)).isEqualTo(SongMediaItemMapper.ORIGIN_AUTOPLAY)
        assertThat(SongMediaItemMapper.getContextName(autoplayItem)).isEqualTo("Similar to Clocks")
    }

    @Test
    fun testQueueInsertionPriority() {
        // Timeline: [Current Playing, Context 1, Context 2, Autoplay 1, Autoplay 2]
        val currentTrack = "current"
        val context1 = "context_1"
        val context2 = "context_2"
        val autoplay1 = "autoplay_1"
        val autoplay2 = "autoplay_2"

        val timeline = mutableListOf(currentTrack, context1, context2, autoplay1, autoplay2)
        val origins = mutableListOf(
            SongMediaItemMapper.ORIGIN_CONTEXT,
            SongMediaItemMapper.ORIGIN_CONTEXT,
            SongMediaItemMapper.ORIGIN_CONTEXT,
            SongMediaItemMapper.ORIGIN_AUTOPLAY,
            SongMediaItemMapper.ORIGIN_AUTOPLAY
        )

        // "Play Next" inserts immediately after currentTrack (index 1)
        val playNextItem = "play_next_item"
        timeline.add(1, playNextItem)
        origins.add(1, SongMediaItemMapper.ORIGIN_MANUAL)

        assertThat(timeline[1]).isEqualTo("play_next_item")

        // "Add to Queue" inserts before first AUTOPLAY track
        val firstAutoplayIndex = origins.indexOf(SongMediaItemMapper.ORIGIN_AUTOPLAY)
        assertThat(firstAutoplayIndex).isGreaterThan(1)

        val addToQueueItem = "add_to_queue_item"
        timeline.add(firstAutoplayIndex, addToQueueItem)
        origins.add(firstAutoplayIndex, SongMediaItemMapper.ORIGIN_MANUAL)

        // Verify ordering: Manual items played BEFORE Autoplay recommendations
        val manualIndices = timeline.indices.filter { origins[it] == SongMediaItemMapper.ORIGIN_MANUAL }
        val autoplayIndices = timeline.indices.filter { origins[it] == SongMediaItemMapper.ORIGIN_AUTOPLAY }

        assertThat(manualIndices.maxOrNull()!!).isLessThan(autoplayIndices.minOrNull()!!)
    }

    @Test
    fun testGenerateMultipleDistinctMixes() {
        runBlocking {
            // Seed database with listening history
        val topSong = Song("s1", "Viva La Vida", "Coldplay", "Viva La Vida", null, 240, "https://aac.saavncdn.com/1.mp3", null, null)
        val secondSong = Song("s2", "Starboy", "The Weeknd", "Starboy", null, 230, "https://aac.saavncdn.com/2.mp3", null, null)
        dbHelper.recordListeningHistory(topSong, 240, isCompleted = true, isSkipped = false, isMeaningful = true)
        dbHelper.recordListeningHistory(secondSong, 230, isCompleted = true, isSkipped = false, isMeaningful = true)
        dbHelper.likeSong(topSong)

        val baseUrl = "http://127.0.0.1:5000"

        val daily1 = mixGenerator.generateDailyMix(baseUrl, limit = 10)
        assertThat(daily1.title).contains("Daily Mix 1")
        assertThat(daily1.category).isEqualTo("FAVORITES")
        assertThat(daily1.songs).isNotEmpty()

        val daily2 = mixGenerator.generateDailyMix2(baseUrl, limit = 10)
        assertThat(daily2.title).contains("Daily Mix 2")
        assertThat(daily2.category).isEqualTo("ENERGY")

        val chill = mixGenerator.generateChillMix(baseUrl, limit = 10)
        assertThat(chill.title).contains("Chill")
        assertThat(chill.category).isEqualTo("CHILL")

        val discovery = mixGenerator.generateDiscoveryMix(baseUrl, limit = 10)
        assertThat(discovery.title).contains("Discovery Mix")
        assertThat(discovery.category).isEqualTo("DISCOVERY")
        }
    }

    @Test
    fun testSaveMixAsPlaylistCreatesAccessiblePlaylist() {
        val s1 = Song("mix_a", "Song A", "Artist A", "Album A", null, 200, "https://aac.saavncdn.com/a.mp3", null, null)
        val s2 = Song("mix_b", "Song B", "Artist B", "Album B", null, 180, "https://aac.saavncdn.com/b.mp3", null, null)

        val mix = PersonalizedMix(
            id = "chill_mix",
            title = "Chill & Focus Mix",
            subtitle = "Acoustic & Downtempo",
            description = "Test chill description",
            songs = listOf(s1, s2),
            category = "CHILL"
        )

        val playlistId = mixGenerator.saveMixAsPlaylist(mix)
        assertThat(playlistId).isGreaterThan(0L)

        val allPlaylists = dbHelper.getAllPlaylists()
        assertThat(allPlaylists.any { it.id == playlistId && it.name == "Chill & Focus Mix" }).isTrue()

        val songsInDb = dbHelper.getSongsInPlaylist(playlistId)
        assertThat(songsInDb).hasSize(2)
        assertThat(songsInDb.map { it.title }).containsExactly("Song A", "Song B")
    }
}
