package com.braveplayer.music

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.api.SearchResult
import com.braveplayer.music.discovery.MixGenerator
import com.braveplayer.music.discovery.MusicRecommendationEngine
import com.braveplayer.music.discovery.PersonalizedMix
import com.braveplayer.music.media.SongMediaItemMapper
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.Playlist
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.service.PlaybackService
import com.braveplayer.music.settings.AppSettings
import com.braveplayer.music.settings.FilterEngineProvider
import com.braveplayer.music.ui.SongAdapter
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Main Activity integrating JioSaavn API search, state handling, queue & playlist management,
 * liked songs, autoplay, personalized mixes, listening history, and background playback via Media3.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1002
    }

    private lateinit var appSettings: AppSettings
    private lateinit var filterListManager: FilterListManager
    private lateinit var metadataService: MusicMetadataService
    private lateinit var playlistDbHelper: PlaylistDatabaseHelper
    private lateinit var recommendationEngine: MusicRecommendationEngine
    private lateinit var mixGenerator: MixGenerator
    private lateinit var songAdapter: SongAdapter

    // Settings UI
    private lateinit var etBaseUrl: EditText
    private lateinit var btnSaveBaseUrl: Button
    private lateinit var btnResetBaseUrl: Button

    // Search UI & States
    private lateinit var etSearchQuery: EditText
    private lateinit var btnSearch: Button
    private lateinit var pbSearchLoading: ProgressBar
    private lateinit var tvEmptyResults: TextView
    private lateinit var layoutError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetrySearch: Button
    private lateinit var rvSearchResults: RecyclerView

    // Playback UI
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvTrackArtist: TextView
    private lateinit var btnLikeCurrent: Button
    private lateinit var tvPlaybackStatus: TextView
    private lateinit var tvPlaybackPosition: TextView
    private lateinit var tvTrackDuration: TextView
    private lateinit var sbPlaybackProgress: SeekBar
    private lateinit var btnRewind15: Button
    private lateinit var btnPrev: Button
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnNext: Button
    private lateinit var btnForward15: Button
    private lateinit var btnRepeat: Button
    private lateinit var btnShuffle: Button
    private lateinit var btnAutoplay: Button
    private lateinit var btnQueue: Button
    private lateinit var btnLikedSongs: Button
    private lateinit var btnMixes: Button
    private lateinit var btnHistory: Button
    private lateinit var btnPlaylists: Button
    private lateinit var tvTelemetry: TextView

    // MediaController connection
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Search Job & Generation tracking
    private var searchJob: Job? = null
    private var searchGenerationId: Long = 0L
    private var lastQuery: String = ""

    // Seek tracking
    private var isUserTrackingSeek: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            updatePositionDisplay()
            updateTelemetryDisplay()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appSettings = AppSettings(this)
        filterListManager = FilterEngineProvider.filterListManager
        metadataService = MusicMetadataService(filterListManager)
        playlistDbHelper = PlaylistDatabaseHelper(this)
        recommendationEngine = MusicRecommendationEngine(metadataService, playlistDbHelper)
        mixGenerator = MixGenerator(recommendationEngine, playlistDbHelper)

        initViews()
        requestNotificationPermissionIfNeeded()
        connectToPlaybackService()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    private fun initViews() {
        // Base URL settings
        etBaseUrl = findViewById(R.id.etBaseUrl)
        btnSaveBaseUrl = findViewById(R.id.btnSaveBaseUrl)
        btnResetBaseUrl = findViewById(R.id.btnResetBaseUrl)

        etBaseUrl.setText(appSettings.getBaseUrl())

        btnSaveBaseUrl.setOnClickListener {
            val saved = appSettings.setBaseUrl(etBaseUrl.text.toString())
            etBaseUrl.setText(saved)
            Toast.makeText(this, "Base URL updated: $saved", Toast.LENGTH_SHORT).show()
        }

        btnResetBaseUrl.setOnClickListener {
            val defaultUrl = appSettings.resetToDefault()
            etBaseUrl.setText(defaultUrl)
            Toast.makeText(this, "Reset to default: $defaultUrl", Toast.LENGTH_SHORT).show()
        }

        // Search inputs
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnSearch)
        pbSearchLoading = findViewById(R.id.pbSearchLoading)
        tvEmptyResults = findViewById(R.id.tvEmptyResults)
        layoutError = findViewById(R.id.layoutError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetrySearch = findViewById(R.id.btnRetrySearch)
        rvSearchResults = findViewById(R.id.rvSearchResults)

        songAdapter = SongAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongSelected = { song -> playSongNow(song) },
            onSongAddToQueue = { song -> addSongToQueue(song) },
            onSongOptions = { song -> showSongOptionDialog(song) },
            onSongLikeToggled = { song -> toggleLikeSong(song) },
            isSongLiked = { songId -> playlistDbHelper.isSongLiked(songId) }
        )
        rvSearchResults.layoutManager = LinearLayoutManager(this)
        rvSearchResults.adapter = songAdapter

        btnSearch.setOnClickListener {
            performSearch(etSearchQuery.text.toString())
        }

        etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(etSearchQuery.text.toString())
                true
            } else {
                false
            }
        }

        btnRetrySearch.setOnClickListener {
            if (lastQuery.isNotBlank()) {
                performSearch(lastQuery)
            }
        }

        // Playback UI Elements
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvTrackArtist = findViewById(R.id.tvTrackArtist)
        btnLikeCurrent = findViewById(R.id.btnLikeCurrent)
        tvPlaybackStatus = findViewById(R.id.tvPlaybackStatus)
        tvPlaybackPosition = findViewById(R.id.tvPlaybackPosition)
        tvTrackDuration = findViewById(R.id.tvTrackDuration)
        sbPlaybackProgress = findViewById(R.id.sbPlaybackProgress)

        btnRewind15 = findViewById(R.id.btnRewind15)
        btnPrev = findViewById(R.id.btnPrev)
        btnPlay = findViewById(R.id.btnPlay)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        btnNext = findViewById(R.id.btnNext)
        btnForward15 = findViewById(R.id.btnForward15)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnAutoplay = findViewById(R.id.btnAutoplay)
        btnQueue = findViewById(R.id.btnQueue)
        btnLikedSongs = findViewById(R.id.btnLikedSongs)
        btnMixes = findViewById(R.id.btnMixes)
        btnHistory = findViewById(R.id.btnHistory)
        btnPlaylists = findViewById(R.id.btnPlaylists)
        tvTelemetry = findViewById(R.id.tvTelemetry)

        // Transport control actions
        btnPlay.setOnClickListener { mediaController?.play() }
        btnPause.setOnClickListener { mediaController?.pause() }
        btnStop.setOnClickListener { mediaController?.stop() }

        btnRewind15.setOnClickListener {
            mediaController?.let { c ->
                val newPos = (c.currentPosition - 15000L).coerceAtLeast(0L)
                c.seekTo(newPos)
            }
        }

        btnForward15.setOnClickListener {
            mediaController?.let { c ->
                val newPos = c.currentPosition + 15000L
                c.seekTo(newPos)
            }
        }

        btnPrev.setOnClickListener { mediaController?.seekToPreviousMediaItem() }
        btnNext.setOnClickListener { mediaController?.seekToNextMediaItem() }

        btnRepeat.setOnClickListener { cycleRepeatMode() }
        btnShuffle.setOnClickListener { toggleShuffle() }

        // Autoplay toggle
        updateAutoplayButton()
        btnAutoplay.setOnClickListener {
            val newEnabled = !appSettings.isAutoplayEnabled()
            appSettings.setAutoplayEnabled(newEnabled)
            updateAutoplayButton()
            val text = if (newEnabled) "Autoplay enabled" else "Autoplay disabled"
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        btnQueue.setOnClickListener { showQueueDialog() }
        btnLikedSongs.setOnClickListener { showLikedSongsDialog() }
        btnMixes.setOnClickListener { showMixesDialog() }
        btnHistory.setOnClickListener { showHistoryDialog() }
        btnPlaylists.setOnClickListener { showPlaylistsDialog() }

        btnLikeCurrent.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            toggleLikeSong(song)
        }

        sbPlaybackProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = mediaController?.duration ?: 0L
                    if (duration > 0) {
                        val currentMs = (progress.toLong() * duration) / 1000L
                        tvPlaybackPosition.text = formatTime(currentMs)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeek = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val duration = mediaController?.duration ?: 0L
                if (duration > 0) {
                    val seekMs = (progress.toLong() * duration) / 1000L
                    mediaController?.seekTo(seekMs)
                }
                isUserTrackingSeek = false
            }
        })
    }

    private fun updateAutoplayButton() {
        val enabled = appSettings.isAutoplayEnabled()
        btnAutoplay.text = if (enabled) "Autoplay: On" else "Autoplay: Off"
    }

    private fun toggleLikeSong(song: Song) {
        val isLiked = playlistDbHelper.isSongLiked(song.id)
        if (isLiked) {
            playlistDbHelper.unlikeSong(song.id)
            Toast.makeText(this, "Removed '${song.title}' from Liked Songs", Toast.LENGTH_SHORT).show()
        } else {
            playlistDbHelper.likeSong(song)
            Toast.makeText(this, "Added '${song.title}' to Liked Songs ♥", Toast.LENGTH_SHORT).show()
        }
        updateCurrentLikeButtonState()
        songAdapter.notifyDataSetChanged()
    }

    private fun updateCurrentLikeButtonState() {
        val item = mediaController?.currentMediaItem
        if (item != null) {
            val isLiked = playlistDbHelper.isSongLiked(item.mediaId)
            btnLikeCurrent.text = if (isLiked) "♥" else "♡"
            btnLikeCurrent.setTextColor(if (isLiked) Color.parseColor("#E91E63") else Color.parseColor("#CCCCCC"))
            btnLikeCurrent.visibility = View.VISIBLE
        } else {
            btnLikeCurrent.visibility = View.GONE
        }
    }

    private fun connectToPlaybackService() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                mediaController = controller
                setupControllerListener(controller)
                syncUiWithPlayer()
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
        controllerFuture = future
    }

    private fun setupControllerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlaybackStatusText(controller)
                updateTelemetryDisplay()
                updateCurrentLikeButtonState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackStatusText(controller)
                updateTelemetryDisplay()
                updateCurrentLikeButtonState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncUiWithPlayer()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updateRepeatModeButton(repeatMode)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateShuffleButton(shuffleModeEnabled)
            }

            override fun onPlayerError(error: PlaybackException) {
                tvPlaybackStatus.text = "Playback Error: ${error.errorCodeName} (Retrying fallback/skipping)"
                Toast.makeText(this@MainActivity, "Stream error: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                updateTelemetryDisplay()
            }
        })
    }

    private fun syncUiWithPlayer() {
        val controller = mediaController ?: return
        val currentItem = controller.currentMediaItem
        if (currentItem != null) {
            tvTrackTitle.text = currentItem.mediaMetadata.title ?: "Unknown Title"
            val artist = currentItem.mediaMetadata.artist ?: "Unknown Artist"
            val album = currentItem.mediaMetadata.albumTitle ?: ""
            tvTrackArtist.text = if (album.isNotEmpty()) "$artist • $album" else artist
        } else {
            tvTrackTitle.text = getString(R.string.sample_track_title)
            tvTrackArtist.text = getString(R.string.sample_track_artist)
        }

        updatePlaybackStatusText(controller)
        updateRepeatModeButton(controller.repeatMode)
        updateShuffleButton(controller.shuffleModeEnabled)
        updateAutoplayButton()
        updateCurrentLikeButtonState()
        updatePositionDisplay()
        updateTelemetryDisplay()
    }

    private fun updatePlaybackStatusText(controller: MediaController) {
        val stateText = when (controller.playbackState) {
            Player.STATE_IDLE -> "Status: Idle"
            Player.STATE_BUFFERING -> "Status: Buffering..."
            Player.STATE_READY -> if (controller.playWhenReady) "Status: Playing" else "Status: Paused"
            Player.STATE_ENDED -> "Status: Ended"
            else -> "Status: Unknown"
        }
        tvPlaybackStatus.text = stateText
    }

    private fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val nextMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextMode
        updateRepeatModeButton(nextMode)
    }

    private fun updateRepeatModeButton(repeatMode: Int) {
        btnRepeat.text = when (repeatMode) {
            Player.REPEAT_MODE_ALL -> "Repeat: All"
            Player.REPEAT_MODE_ONE -> "Repeat: One"
            else -> "Repeat: Off"
        }
    }

    private fun toggleShuffle() {
        val controller = mediaController ?: return
        val newShuffle = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = newShuffle
        updateShuffleButton(newShuffle)
    }

    private fun updateShuffleButton(shuffleEnabled: Boolean) {
        btnShuffle.text = if (shuffleEnabled) "Shuffle: On" else "Shuffle: Off"
    }

    private fun playSongNow(song: Song) {
        val playbackUrl = song.getBestPlaybackUrl()
        if (playbackUrl == null) {
            Toast.makeText(this, "Playback stream unavailable for '${song.title}'", Toast.LENGTH_LONG).show()
            tvPlaybackStatus.text = "Status: Playback URL unavailable"
            return
        }

        tvTrackTitle.text = song.title
        tvTrackArtist.text = "${song.artists} • ${song.album}"
        tvPlaybackStatus.text = "Status: Connecting..."

        val mediaItem = SongMediaItemMapper.toMediaItem(song)
        mediaController?.let { controller ->
            controller.stop()
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
    }

    private fun addSongToQueue(song: Song) {
        val playbackUrl = song.getBestPlaybackUrl()
        if (playbackUrl == null) {
            Toast.makeText(this, "Cannot queue '${song.title}': No valid stream URL", Toast.LENGTH_SHORT).show()
            return
        }

        val mediaItem = SongMediaItemMapper.toMediaItem(song)
        mediaController?.let { controller ->
            controller.addMediaItem(mediaItem)
            if (controller.playbackState == Player.STATE_IDLE || controller.mediaItemCount == 1) {
                controller.prepare()
            }
            Toast.makeText(this, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRadioForSong(song: Song) {
        val playbackUrl = song.getBestPlaybackUrl()
        if (playbackUrl == null) {
            Toast.makeText(this, "Cannot start radio: No valid stream URL for '${song.title}'", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Starting Radio for '${song.title}'...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val recs = try {
                recommendationEngine.getRecommendations(
                    baseUrl = appSettings.getBaseUrl(),
                    seedSong = song,
                    excludeSongIds = setOf(song.id),
                    limit = 15
                )
            } catch (_: Exception) {
                emptyList()
            }

            val queue = listOf(song) + recs
            val mediaItems = queue.map { SongMediaItemMapper.toMediaItem(it) }

            mediaController?.let { controller ->
                controller.stop()
                controller.setMediaItems(mediaItems)
                controller.prepare()
                controller.play()
            }

            Toast.makeText(this@MainActivity, "Radio started: ${queue.size} songs queued", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        lastQuery = trimmed
        showSearchState(State.LOADING)

        // Cancel any in-flight search and increment generation ID to avoid stale race conditions
        searchJob?.cancel()
        val localGenId = ++searchGenerationId
        val baseUrl = appSettings.getBaseUrl()

        searchJob = lifecycleScope.launch {
            val result = metadataService.searchSongs(baseUrl, trimmed)
            if (localGenId != searchGenerationId) {
                return@launch // Outdated search response, ignore
            }

            when (result) {
                is SearchResult.Success -> {
                    songAdapter.submitList(result.songs)
                    showSearchState(State.RESULTS)
                }
                is SearchResult.Empty -> {
                    showSearchState(State.EMPTY)
                }
                is SearchResult.Error -> {
                    tvErrorMessage.text = result.message
                    showSearchState(State.ERROR)
                }
            }
            updateTelemetryDisplay()
        }
    }

    private enum class State { LOADING, EMPTY, ERROR, RESULTS }

    private fun showSearchState(state: State) {
        pbSearchLoading.visibility = if (state == State.LOADING) View.VISIBLE else View.GONE
        tvEmptyResults.visibility = if (state == State.EMPTY) View.VISIBLE else View.GONE
        layoutError.visibility = if (state == State.ERROR) View.VISIBLE else View.GONE
        rvSearchResults.visibility = if (state == State.RESULTS) View.VISIBLE else View.GONE
    }

    private fun showQueueDialog() {
        val controller = mediaController ?: run {
            Toast.makeText(this, "Player not connected", Toast.LENGTH_SHORT).show()
            return
        }

        val count = controller.mediaItemCount
        if (count == 0) {
            AlertDialog.Builder(this)
                .setTitle("Playback Queue")
                .setMessage("The queue is currently empty.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val currentIndex = controller.currentMediaItemIndex
        val items = Array(count) { i ->
            val item = controller.getMediaItemAt(i)
            val title = item.mediaMetadata.title ?: "Track ${i + 1}"
            val artist = item.mediaMetadata.artist ?: ""
            val isLiked = playlistDbHelper.isSongLiked(item.mediaId)
            val likeIcon = if (isLiked) " ♥" else ""
            val prefix = if (i == currentIndex) "▶ " else "${i + 1}. "
            "$prefix$title - $artist$likeIcon"
        }

        AlertDialog.Builder(this)
            .setTitle("Queue ($count tracks)")
            .setItems(items) { _, which ->
                showQueueItemOptionsDialog(which)
            }
            .setNegativeButton("Clear Queue") { _, _ ->
                controller.clearMediaItems()
                Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showQueueItemOptionsDialog(index: Int) {
        val controller = mediaController ?: return
        if (index < 0 || index >= controller.mediaItemCount) return
        val item = controller.getMediaItemAt(index)
        val song = SongMediaItemMapper.fromMediaItem(item)
        val isLiked = playlistDbHelper.isSongLiked(song.id)
        val likeOption = if (isLiked) "Unlike Song" else "Like Song ♥"

        val options = arrayOf("Play This Track", "Start Radio", likeOption, "Remove From Queue", "Move Up", "Move Down")
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        controller.seekToDefaultPosition(index)
                        controller.play()
                    }
                    1 -> startRadioForSong(song)
                    2 -> toggleLikeSong(song)
                    3 -> {
                        controller.removeMediaItem(index)
                        Toast.makeText(this, "Removed track from queue", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        if (index > 0) {
                            controller.moveMediaItem(index, index - 1)
                        }
                    }
                    5 -> {
                        if (index < controller.mediaItemCount - 1) {
                            controller.moveMediaItem(index, index + 1)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLikedSongsDialog() {
        val likedSongs = playlistDbHelper.getAllLikedSongs()
        if (likedSongs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Liked Songs")
                .setMessage("No liked songs yet. Tap the heart icon on any song to save it here.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = likedSongs.map { "${it.title} - ${it.artists}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Liked Songs (${likedSongs.size})")
            .setItems(items) { _, which ->
                val song = likedSongs[which]
                showSongOptionDialog(song)
            }
            .setPositiveButton("▶ Play All") { _, _ ->
                val mediaItems = likedSongs.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.prepare()
                    c.play()
                    Toast.makeText(this, "Playing all liked songs", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("🔀 Shuffle All") { _, _ ->
                val mediaItems = likedSongs.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.shuffleModeEnabled = true
                    c.prepare()
                    c.play()
                    Toast.makeText(this, "Shuffling liked songs", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showMixesDialog() {
        val mixOptions = arrayOf("Daily Mix", "Discovery Mix")
        AlertDialog.Builder(this)
            .setTitle("Personalized Mixes")
            .setItems(mixOptions) { _, which ->
                when (which) {
                    0 -> generateAndShowMix("Daily Mix")
                    1 -> generateAndShowMix("Discovery Mix")
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun generateAndShowMix(mixType: String) {
        val baseUrl = appSettings.getBaseUrl()
        Toast.makeText(this, "Generating $mixType...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val mix: PersonalizedMix = try {
                if (mixType == "Daily Mix") {
                    mixGenerator.generateDailyMix(baseUrl)
                } else {
                    mixGenerator.generateDiscoveryMix(baseUrl)
                }
            } catch (e: Exception) {
                PersonalizedMix(mixType.lowercase(), mixType, "Failed to generate mix: ${e.message}", emptyList())
            }

            if (mix.songs.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(mix.title)
                    .setMessage("Could not generate ${mix.title} right now. Listen to more music or search songs first!")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            showPersonalizedMixDetails(mix)
        }
    }

    private fun showPersonalizedMixDetails(mix: PersonalizedMix) {
        val songList = mix.songs.map { "${it.title} - ${it.artists}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${mix.title} (${mix.songs.size} tracks)")
            .setMessage(mix.description)
            .setItems(songList) { _, which ->
                val song = mix.songs[which]
                showSongOptionDialog(song)
            }
            .setPositiveButton("▶ Play") { _, _ ->
                val mediaItems = mix.songs.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.prepare()
                    c.play()
                }
            }
            .setNeutralButton("💾 Save Playlist") { _, _ ->
                showSaveMixAsPlaylistDialog(mix)
            }
            .setNegativeButton("🔄 Refresh") { _, _ ->
                generateAndShowMix(mix.title)
            }
            .show()
    }

    private fun showSaveMixAsPlaylistDialog(mix: PersonalizedMix) {
        val input = EditText(this).apply {
            setText(mix.title)
        }
        AlertDialog.Builder(this)
            .setTitle("Save as Playlist")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { mix.title }
                val id = mixGenerator.saveMixAsPlaylist(mix, name)
                if (id != -1L) {
                    Toast.makeText(this, "Saved playlist '$name' with ${mix.songs.size} tracks", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to save playlist (name may exist)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHistoryDialog() {
        val options = arrayOf("Recently Played", "Most Played", "Clear History")
        AlertDialog.Builder(this)
            .setTitle("Listening History")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRecentlyPlayedDialog()
                    1 -> showMostPlayedDialog()
                    2 -> {
                        playlistDbHelper.clearHistory()
                        Toast.makeText(this, "Listening history cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showRecentlyPlayedDialog() {
        val recent = playlistDbHelper.getRecentlyPlayedSongs(50)
        if (recent.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Recently Played")
                .setMessage("No listening history recorded yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = recent.map { "${it.title} - ${it.artists}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Recently Played (${recent.size})")
            .setItems(items) { _, which ->
                showSongOptionDialog(recent[which])
            }
            .setPositiveButton("▶ Play All") { _, _ ->
                val mediaItems = recent.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.prepare()
                    c.play()
                }
            }
            .setNegativeButton("Back") { _, _ -> showHistoryDialog() }
            .show()
    }

    private fun showMostPlayedDialog() {
        val mostPlayed = playlistDbHelper.getMostPlayedSongs(50)
        if (mostPlayed.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Most Played")
                .setMessage("No meaningful plays recorded yet (tracks must play for at least 30 seconds).")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = mostPlayed.map { "${it.first.title} - ${it.first.artists} (${it.second} plays)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Most Played Tracks (${mostPlayed.size})")
            .setItems(items) { _, which ->
                showSongOptionDialog(mostPlayed[which].first)
            }
            .setPositiveButton("▶ Play All") { _, _ ->
                val mediaItems = mostPlayed.map { SongMediaItemMapper.toMediaItem(it.first) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.prepare()
                    c.play()
                }
            }
            .setNegativeButton("Back") { _, _ -> showHistoryDialog() }
            .show()
    }

    private fun showPlaylistsDialog() {
        val playlists = playlistDbHelper.getAllPlaylists()
        val options = mutableListOf<String>()
        options.add("➕ Create New Playlist")
        for (p in playlists) {
            options.add("📁 ${p.name} (${p.songCount} songs)")
        }

        AlertDialog.Builder(this)
            .setTitle("Playlists")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreatePlaylistDialog()
                } else {
                    val selectedPlaylist = playlists[which - 1]
                    showPlaylistDetailDialog(selectedPlaylist)
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this).apply {
            hint = "Playlist Name"
        }
        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val id = playlistDbHelper.createPlaylist(name)
                    if (id != -1L) {
                        Toast.makeText(this, "Playlist '$name' created", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to create playlist (name may already exist)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPlaylistDetailDialog(playlist: Playlist) {
        val songs = playlistDbHelper.getSongsInPlaylist(playlist.id)
        val actions = arrayOf(
            "▶ Play All into Queue (${songs.size} tracks)",
            "📋 View / Remove Tracks",
            "🗑 Delete Playlist"
        )

        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        if (songs.isEmpty()) {
                            Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
                        } else {
                            val mediaItems = songs.map { SongMediaItemMapper.toMediaItem(it) }
                            mediaController?.let { controller ->
                                controller.stop()
                                controller.setMediaItems(mediaItems)
                                controller.prepare()
                                controller.play()
                                Toast.makeText(this, "Playing playlist '${playlist.name}'", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    1 -> showPlaylistSongsDialog(playlist, songs)
                    2 -> {
                        playlistDbHelper.deletePlaylist(playlist.id)
                        Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setPositiveButton("Back") { _, _ -> showPlaylistsDialog() }
            .show()
    }

    private fun showPlaylistSongsDialog(playlist: Playlist, songs: List<Song>) {
        if (songs.isEmpty()) {
            Toast.makeText(this, "No tracks in playlist", Toast.LENGTH_SHORT).show()
            return
        }

        val songTitles = songs.map { "${it.title} - ${it.artists}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${playlist.name} Tracks")
            .setItems(songTitles) { _, which ->
                val song = songs[which]
                AlertDialog.Builder(this)
                    .setTitle(song.title)
                    .setItems(arrayOf("Play Now", "Add to Queue", "Start Radio", "Remove from Playlist")) { _, action ->
                        when (action) {
                            0 -> playSongNow(song)
                            1 -> addSongToQueue(song)
                            2 -> startRadioForSong(song)
                            3 -> {
                                playlistDbHelper.removeSongFromPlaylist(playlist.id, song.id)
                                Toast.makeText(this, "Removed '${song.title}' from playlist", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showSongOptionDialog(song: Song) {
        val isLiked = playlistDbHelper.isSongLiked(song.id)
        val likeText = if (isLiked) "Unlike Song" else "Like Song ♥"
        val options = arrayOf("Play Now", "Add to Queue", "Start Radio", likeText, "Add to Playlist...")
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playSongNow(song)
                    1 -> addSongToQueue(song)
                    2 -> startRadioForSong(song)
                    3 -> toggleLikeSong(song)
                    4 -> showAddToPlaylistDialog(song)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val playlists = playlistDbHelper.getAllPlaylists()
        if (playlists.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Add to Playlist")
                .setMessage("No playlists found. Create one first.")
                .setPositiveButton("Create Playlist") { _, _ -> showCreatePlaylistDialog() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val names = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Playlist")
            .setItems(names) { _, which ->
                val playlist = playlists[which]
                val added = playlistDbHelper.addSongToPlaylist(playlist.id, song)
                if (added) {
                    Toast.makeText(this, "Added to '${playlist.name}'", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to add song to playlist", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePositionDisplay() {
        mediaController?.let { controller ->
            val durationMs = if (controller.duration > 0) controller.duration else 0L
            val currentMs = controller.currentPosition.coerceAtLeast(0L)
            tvPlaybackPosition.text = formatTime(currentMs)
            tvTrackDuration.text = formatTime(durationMs)
            if (!isUserTrackingSeek && durationMs > 0) {
                val progress = ((currentMs * 1000L) / durationMs).toInt()
                sbPlaybackProgress.progress = progress
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        val minutes = sec / 60
        val seconds = sec % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun updateTelemetryDisplay() {
        val stats = filterListManager.getStatisticsSnapshot()
        val text = "Queries: ${stats.totalRequestsEvaluated} | Allowed: ${stats.allowedRequests} | Blocked: ${stats.blockedRequests} | Audio CDN Bypass: ${stats.audioStreamBypasses}"
        tvTelemetry.text = text
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(positionUpdateRunnable)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(positionUpdateRunnable)
        // Background playback continues! Do NOT pause mediaController.
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
    }
}
