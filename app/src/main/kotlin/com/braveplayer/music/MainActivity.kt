package com.braveplayer.music

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.braveplayer.music.lyrics.LyricsResult
import com.braveplayer.music.lyrics.LyricsService
import com.braveplayer.music.media.SongMediaItemMapper
import com.braveplayer.music.model.Song
import androidx.core.widget.NestedScrollView
import com.braveplayer.music.playlist.Playlist
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.service.PlaybackService
import com.braveplayer.music.settings.AppSettings
import com.braveplayer.music.settings.FilterEngineProvider
import com.braveplayer.music.ui.PlaylistAdapter
import com.braveplayer.music.ui.SongAdapter
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Main Activity providing a premium TIDAL-inspired dark user interface with:
 * - 3-tab Bottom Navigation (Home/Discovery, Search, My Collection)
 * - Persistent Docked Mini-Player bar
 * - Expandable Full-Screen Now Playing BottomSheet
 * - Real-time Media3 background playback synchronization
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1002
        private val coverImageCache = LruCache<String, Bitmap>(100)
    }

    private lateinit var appSettings: AppSettings
    private lateinit var filterListManager: FilterListManager
    private lateinit var metadataService: MusicMetadataService
    private lateinit var playlistDbHelper: PlaylistDatabaseHelper
    private lateinit var recommendationEngine: MusicRecommendationEngine
    private lateinit var mixGenerator: MixGenerator
    private lateinit var lyricsService: LyricsService

    // Adapters
    private lateinit var searchAdapter: SongAdapter
    private lateinit var homeRecentAdapter: SongAdapter
    private lateinit var collectionPlaylistAdapter: PlaylistAdapter

    // Bottom Navigation & Tabs
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var containerHome: View
    private lateinit var containerSearch: View
    private lateinit var containerCollection: View
    private lateinit var btnOpenSettings: ImageView

    // Home Tab UI
    private lateinit var cardDailyMix: View
    private lateinit var btnPlayDailyMix: Button
    private lateinit var cardDiscoveryMix: View
    private lateinit var btnPlayDiscoveryMix: Button
    private lateinit var btnHomeLikedShortcut: View
    private lateinit var btnHomeQueueShortcut: View
    private lateinit var rvRecentlyPlayedHome: RecyclerView

    // Search Tab UI
    private lateinit var etSearchQuery: EditText
    private lateinit var btnSearch: Button
    private lateinit var pbSearchLoading: ProgressBar
    private lateinit var tvEmptyResults: TextView
    private lateinit var layoutError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetrySearch: Button
    private lateinit var rvSearchResults: RecyclerView

    // My Collection Tab UI
    private lateinit var btnCreatePlaylistAction: Button
    private lateinit var cardCollectionLiked: View
    private lateinit var tvCollectionLikedCount: TextView
    private lateinit var cardCollectionHistory: View
    private lateinit var tvCollectionHistoryCount: TextView
    private lateinit var rvPlaylistsCollection: RecyclerView

    // Docked Mini-Player UI
    private lateinit var layoutMiniPlayer: View
    private lateinit var pbMiniProgress: ProgressBar
    private lateinit var ivMiniCover: ImageView
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniArtist: TextView
    private lateinit var btnMiniLike: ImageView
    private lateinit var btnMiniPlayPause: ImageView
    private lateinit var btnMiniNext: ImageView

    // Expandable Now Playing Sheet UI
    private lateinit var nowPlayingBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var ivCollapseNowPlaying: ImageView
    private lateinit var btnNowPlayingMore: ImageView
    private lateinit var ivNowPlayingCover: ImageView
    private lateinit var tvNowPlayingTitle: TextView
    private lateinit var tvNowPlayingArtist: TextView
    private lateinit var btnNowPlayingLike: ImageView
    private lateinit var sbNowPlayingProgress: SeekBar
    private lateinit var tvNowPlayingPosition: TextView
    private lateinit var tvNowPlayingDuration: TextView
    private lateinit var btnNowPlayingShuffle: ImageView
    private lateinit var btnNowPlayingPrev: ImageView
    private lateinit var layoutNowPlayingPlayPause: View
    private lateinit var btnNowPlayingPlayPause: ImageView
    private lateinit var btnNowPlayingNext: ImageView
    private lateinit var btnNowPlayingRepeat: ImageView
    private lateinit var btnNowPlayingRewind15: TextView
    private lateinit var btnNowPlayingForward15: TextView
    private lateinit var tvNowPlayingAutoplayStatus: TextView
    private lateinit var btnNowPlayingQueueAction: View
    private lateinit var btnNowPlayingLyricsAction: View
    private lateinit var btnNowPlayingRadioAction: View
    private lateinit var btnNowPlayingAddPlaylistAction: View

    // MediaController connection
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Search Job & Tracking
    private var searchJob: Job? = null
    private var searchGenerationId: Long = 0L
    private var lastQuery: String = ""
    private var isUserTrackingSeek: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            updatePositionDisplay()
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
        lyricsService = LyricsService(metadataService.httpClient)

        initViews()
        setupAdapters()
        setupBottomNavigation()
        setupNowPlayingSheet()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        connectToPlaybackService()

        refreshHomeData()
        refreshCollectionData()
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
        bottomNavigation = findViewById(R.id.bottomNavigation)
        containerHome = findViewById(R.id.containerHome)
        containerSearch = findViewById(R.id.containerSearch)
        containerCollection = findViewById(R.id.containerCollection)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)

        // Home UI
        cardDailyMix = findViewById(R.id.cardDailyMix)
        btnPlayDailyMix = findViewById(R.id.btnPlayDailyMix)
        cardDiscoveryMix = findViewById(R.id.cardDiscoveryMix)
        btnPlayDiscoveryMix = findViewById(R.id.btnPlayDiscoveryMix)
        btnHomeLikedShortcut = findViewById(R.id.btnHomeLikedShortcut)
        btnHomeQueueShortcut = findViewById(R.id.btnHomeQueueShortcut)
        rvRecentlyPlayedHome = findViewById(R.id.rvRecentlyPlayedHome)

        // Search UI
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnSearch)
        pbSearchLoading = findViewById(R.id.pbSearchLoading)
        tvEmptyResults = findViewById(R.id.tvEmptyResults)
        layoutError = findViewById(R.id.layoutError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetrySearch = findViewById(R.id.btnRetrySearch)
        rvSearchResults = findViewById(R.id.rvSearchResults)

        // My Collection UI
        btnCreatePlaylistAction = findViewById(R.id.btnCreatePlaylistAction)
        cardCollectionLiked = findViewById(R.id.cardCollectionLiked)
        tvCollectionLikedCount = findViewById(R.id.tvCollectionLikedCount)
        cardCollectionHistory = findViewById(R.id.cardCollectionHistory)
        tvCollectionHistoryCount = findViewById(R.id.tvCollectionHistoryCount)
        rvPlaylistsCollection = findViewById(R.id.rvPlaylistsCollection)

        // Mini-Player UI
        layoutMiniPlayer = findViewById(R.id.layoutMiniPlayer)
        pbMiniProgress = findViewById(R.id.pbMiniProgress)
        ivMiniCover = findViewById(R.id.ivMiniCover)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        tvMiniArtist = findViewById(R.id.tvMiniArtist)
        btnMiniLike = findViewById(R.id.btnMiniLike)
        btnMiniPlayPause = findViewById(R.id.btnMiniPlayPause)
        btnMiniNext = findViewById(R.id.btnMiniNext)

        // Now Playing Sheet UI
        val sheetContainer: FrameLayout = findViewById(R.id.bottomSheetNowPlaying)
        nowPlayingBehavior = BottomSheetBehavior.from(sheetContainer)
        ivCollapseNowPlaying = findViewById(R.id.ivCollapseNowPlaying)
        btnNowPlayingMore = findViewById(R.id.btnNowPlayingMore)
        ivNowPlayingCover = findViewById(R.id.ivNowPlayingCover)
        tvNowPlayingTitle = findViewById(R.id.tvNowPlayingTitle)
        tvNowPlayingArtist = findViewById(R.id.tvNowPlayingArtist)
        btnNowPlayingLike = findViewById(R.id.btnNowPlayingLike)
        sbNowPlayingProgress = findViewById(R.id.sbNowPlayingProgress)
        tvNowPlayingPosition = findViewById(R.id.tvNowPlayingPosition)
        tvNowPlayingDuration = findViewById(R.id.tvNowPlayingDuration)
        btnNowPlayingShuffle = findViewById(R.id.btnNowPlayingShuffle)
        btnNowPlayingPrev = findViewById(R.id.btnNowPlayingPrev)
        layoutNowPlayingPlayPause = findViewById(R.id.layoutNowPlayingPlayPause)
        btnNowPlayingPlayPause = findViewById(R.id.btnNowPlayingPlayPause)
        btnNowPlayingNext = findViewById(R.id.btnNowPlayingNext)
        btnNowPlayingRepeat = findViewById(R.id.btnNowPlayingRepeat)
        btnNowPlayingRewind15 = findViewById(R.id.btnNowPlayingRewind15)
        btnNowPlayingForward15 = findViewById(R.id.btnNowPlayingForward15)
        tvNowPlayingAutoplayStatus = findViewById(R.id.tvNowPlayingAutoplayStatus)
        btnNowPlayingQueueAction = findViewById(R.id.btnNowPlayingQueueAction)
        btnNowPlayingLyricsAction = findViewById(R.id.btnNowPlayingLyricsAction)
        btnNowPlayingRadioAction = findViewById(R.id.btnNowPlayingRadioAction)
        btnNowPlayingAddPlaylistAction = findViewById(R.id.btnNowPlayingAddPlaylistAction)
    }

    private fun setupAdapters() {
        searchAdapter = SongAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongSelected = { song -> playSongNow(song) },
            onSongAddToQueue = { song -> addSongToQueue(song) },
            onSongOptions = { song -> showSongOptionDialog(song) },
            onSongLikeToggled = { song -> toggleLikeSong(song) },
            isSongLiked = { songId -> playlistDbHelper.isSongLiked(songId) }
        )
        rvSearchResults.layoutManager = LinearLayoutManager(this)
        rvSearchResults.adapter = searchAdapter

        homeRecentAdapter = SongAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongSelected = { song -> playSongNow(song) },
            onSongAddToQueue = { song -> addSongToQueue(song) },
            onSongOptions = { song -> showSongOptionDialog(song) },
            onSongLikeToggled = { song -> toggleLikeSong(song) },
            isSongLiked = { songId -> playlistDbHelper.isSongLiked(songId) }
        )
        rvRecentlyPlayedHome.layoutManager = LinearLayoutManager(this)
        rvRecentlyPlayedHome.adapter = homeRecentAdapter

        collectionPlaylistAdapter = PlaylistAdapter(
            onPlaylistSelected = { playlist -> showPlaylistDetailDialog(playlist) },
            onPlaylistOptions = { playlist -> showPlaylistDetailDialog(playlist) }
        )
        rvPlaylistsCollection.layoutManager = LinearLayoutManager(this)
        rvPlaylistsCollection.adapter = collectionPlaylistAdapter
    }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchTab(Tab.HOME)
                    refreshHomeData()
                    true
                }
                R.id.nav_search -> {
                    switchTab(Tab.SEARCH)
                    true
                }
                R.id.nav_collection -> {
                    switchTab(Tab.COLLECTION)
                    refreshCollectionData()
                    true
                }
                else -> false
            }
        }
    }

    private enum class Tab { HOME, SEARCH, COLLECTION }

    private fun switchTab(tab: Tab) {
        containerHome.visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        containerSearch.visibility = if (tab == Tab.SEARCH) View.VISIBLE else View.GONE
        containerCollection.visibility = if (tab == Tab.COLLECTION) View.VISIBLE else View.GONE
    }

    private fun setupNowPlayingSheet() {
        nowPlayingBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        nowPlayingBehavior.peekHeight = 0

        // Tap on docked mini player expands full sheet
        layoutMiniPlayer.setOnClickListener {
            nowPlayingBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        // Collapse downward chevron
        ivCollapseNowPlaying.setOnClickListener {
            nowPlayingBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun setupListeners() {
        btnOpenSettings.setOnClickListener { showSettingsDialog() }

        // Home Actions
        cardDailyMix.setOnClickListener { generateAndPlayMix("Daily Mix") }
        btnPlayDailyMix.setOnClickListener { generateAndPlayMix("Daily Mix") }
        cardDiscoveryMix.setOnClickListener { generateAndPlayMix("Discovery Mix") }
        btnPlayDiscoveryMix.setOnClickListener { generateAndPlayMix("Discovery Mix") }
        btnHomeLikedShortcut.setOnClickListener { showLikedSongsDialog() }
        btnHomeQueueShortcut.setOnClickListener { showQueueDialog() }

        // Search Actions
        btnSearch.setOnClickListener { performSearch(etSearchQuery.text.toString()) }
        etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(etSearchQuery.text.toString())
                true
            } else {
                false
            }
        }
        btnRetrySearch.setOnClickListener {
            if (lastQuery.isNotBlank()) performSearch(lastQuery)
        }

        // Collection Actions
        btnCreatePlaylistAction.setOnClickListener { showCreatePlaylistDialog() }
        cardCollectionLiked.setOnClickListener { showLikedSongsDialog() }
        cardCollectionHistory.setOnClickListener { showHistoryDialog() }

        // Mini Player Controls
        btnMiniPlayPause.setOnClickListener {
            mediaController?.let { c ->
                if (c.isPlaying) c.pause() else c.play()
            }
        }
        btnMiniNext.setOnClickListener { mediaController?.seekToNextMediaItem() }
        btnMiniLike.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            toggleLikeSong(song)
        }

        // Now Playing Controls
        layoutNowPlayingPlayPause.setOnClickListener {
            mediaController?.let { c ->
                if (c.isPlaying) c.pause() else c.play()
            }
        }
        btnNowPlayingPrev.setOnClickListener { mediaController?.seekToPreviousMediaItem() }
        btnNowPlayingNext.setOnClickListener { mediaController?.seekToNextMediaItem() }
        btnNowPlayingShuffle.setOnClickListener { toggleShuffle() }
        btnNowPlayingRepeat.setOnClickListener { cycleRepeatMode() }

        btnNowPlayingRewind15.setOnClickListener {
            mediaController?.let { c ->
                val newPos = (c.currentPosition - 15000L).coerceAtLeast(0L)
                c.seekTo(newPos)
            }
        }
        btnNowPlayingForward15.setOnClickListener {
            mediaController?.let { c ->
                val newPos = c.currentPosition + 15000L
                c.seekTo(newPos)
            }
        }

        tvNowPlayingAutoplayStatus.setOnClickListener {
            val newEnabled = !appSettings.isAutoplayEnabled()
            appSettings.setAutoplayEnabled(newEnabled)
            updateAutoplayDisplay()
            val text = if (newEnabled) "Autoplay enabled" else "Autoplay disabled"
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        btnNowPlayingLike.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            toggleLikeSong(song)
        }

        btnNowPlayingMore.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            showSongOptionDialog(song)
        }

        btnNowPlayingQueueAction.setOnClickListener { showQueueDialog() }
        btnNowPlayingLyricsAction.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            showLyricsDialog(song)
        }
        btnNowPlayingRadioAction.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            startRadioForSong(song)
        }
        btnNowPlayingAddPlaylistAction.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            showAddToPlaylistDialog(song)
        }

        // Scrubber
        sbNowPlayingProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = mediaController?.duration ?: 0L
                    if (duration > 0) {
                        val currentMs = (progress.toLong() * duration) / 1000L
                        tvNowPlayingPosition.text = formatTime(currentMs)
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
                syncUiWithPlayer()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncUiWithPlayer()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncUiWithPlayer()
                refreshHomeData()
                refreshCollectionData()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updateRepeatControls(repeatMode)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateShuffleControls(shuffleModeEnabled)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MainActivity, "Stream error: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                syncUiWithPlayer()
            }
        })
    }

    private fun syncUiWithPlayer() {
        val controller = mediaController ?: return
        val currentItem = controller.currentMediaItem

        if (currentItem != null) {
            val song = SongMediaItemMapper.fromMediaItem(currentItem)
            val title = song.title.ifEmpty { "Unknown Title" }
            val artist = song.artists.ifEmpty { "Unknown Artist" }
            val album = song.album

            // Update Mini Player
            layoutMiniPlayer.visibility = View.VISIBLE
            tvMiniTitle.text = title
            tvMiniArtist.text = if (album.isNotBlank()) "$artist • $album" else artist
            loadArtwork(song.imageUrl, ivMiniCover)

            val isLiked = playlistDbHelper.isSongLiked(song.id)
            btnMiniLike.setImageResource(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            btnMiniPlayPause.setImageResource(if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)

            // Update Now Playing Sheet
            tvNowPlayingTitle.text = title
            tvNowPlayingArtist.text = if (album.isNotBlank()) "$artist • $album" else artist
            loadArtwork(song.imageUrl, ivNowPlayingCover)
            btnNowPlayingLike.setImageResource(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            btnNowPlayingPlayPause.setImageResource(if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        } else {
            layoutMiniPlayer.visibility = View.GONE
        }

        updateRepeatControls(controller.repeatMode)
        updateShuffleControls(controller.shuffleModeEnabled)
        updateAutoplayDisplay()
        updatePositionDisplay()
    }

    private fun loadArtwork(url: String?, target: ImageView) {
        if (url.isNullOrBlank()) {
            target.setImageResource(R.drawable.ic_music_note)
            return
        }
        val cached = coverImageCache.get(url)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageResource(R.drawable.ic_music_note)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(url).build()
                    val resp = metadataService.httpClient.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                coverImageCache.put(url, bitmap)
                target.setImageBitmap(bitmap)
            }
        }
    }

    private fun updatePositionDisplay() {
        val controller = mediaController ?: return
        val durationMs = if (controller.duration > 0) controller.duration else 0L
        val currentMs = controller.currentPosition.coerceAtLeast(0L)

        tvNowPlayingPosition.text = formatTime(currentMs)
        tvNowPlayingDuration.text = formatTime(durationMs)

        if (durationMs > 0) {
            val progressInt = ((currentMs * 1000L) / durationMs).toInt()
            pbMiniProgress.progress = progressInt
            if (!isUserTrackingSeek) {
                sbNowPlayingProgress.progress = progressInt
            }
        } else {
            pbMiniProgress.progress = 0
            if (!isUserTrackingSeek) {
                sbNowPlayingProgress.progress = 0
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        val minutes = sec / 60
        val seconds = sec % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun updateRepeatControls(repeatMode: Int) {
        when (repeatMode) {
            Player.REPEAT_MODE_ALL -> {
                btnNowPlayingRepeat.setImageResource(R.drawable.ic_repeat)
                btnNowPlayingRepeat.setColorFilter(ContextCompat.getColor(this, R.color.tidal_accent))
            }
            Player.REPEAT_MODE_ONE -> {
                btnNowPlayingRepeat.setImageResource(R.drawable.ic_repeat_one)
                btnNowPlayingRepeat.setColorFilter(ContextCompat.getColor(this, R.color.tidal_accent))
            }
            else -> {
                btnNowPlayingRepeat.setImageResource(R.drawable.ic_repeat)
                btnNowPlayingRepeat.setColorFilter(ContextCompat.getColor(this, R.color.tidal_text_secondary))
            }
        }
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
        updateRepeatControls(nextMode)
    }

    private fun updateShuffleControls(enabled: Boolean) {
        if (enabled) {
            btnNowPlayingShuffle.setColorFilter(ContextCompat.getColor(this, R.color.tidal_accent))
        } else {
            btnNowPlayingShuffle.setColorFilter(ContextCompat.getColor(this, R.color.tidal_text_secondary))
        }
    }

    private fun toggleShuffle() {
        val controller = mediaController ?: return
        val newShuffle = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = newShuffle
        updateShuffleControls(newShuffle)
    }

    private fun updateAutoplayDisplay() {
        val enabled = appSettings.isAutoplayEnabled()
        tvNowPlayingAutoplayStatus.text = if (enabled) "AUTOPLAY ON" else "AUTOPLAY OFF"
        tvNowPlayingAutoplayStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.tidal_badge_text else R.color.tidal_text_secondary)
        )
    }

    private fun playSongNow(song: Song) {
        val playbackUrl = song.getBestPlaybackUrl()
        if (playbackUrl == null) {
            Toast.makeText(this, "Playback stream unavailable for '${song.title}'", Toast.LENGTH_LONG).show()
            return
        }

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
            Toast.makeText(this, "Cannot queue '${song.title}': No stream URL", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Cannot start radio: No valid stream for '${song.title}'", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this@MainActivity, "Radio: ${queue.size} tracks playing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleLikeSong(song: Song) {
        val isLiked = playlistDbHelper.isSongLiked(song.id)
        if (isLiked) {
            playlistDbHelper.unlikeSong(song.id)
            Toast.makeText(this, "Removed from Liked Songs", Toast.LENGTH_SHORT).show()
        } else {
            playlistDbHelper.likeSong(song)
            Toast.makeText(this, "Added to Liked Songs ♥", Toast.LENGTH_SHORT).show()
        }
        syncUiWithPlayer()
        searchAdapter.notifyDataSetChanged()
        homeRecentAdapter.notifyDataSetChanged()
        refreshCollectionData()
    }

    private fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        lastQuery = trimmed
        showSearchState(SearchState.LOADING)
        searchJob?.cancel()
        val localGenId = ++searchGenerationId
        val baseUrl = appSettings.getBaseUrl()

        searchJob = lifecycleScope.launch {
            val result = metadataService.searchSongs(baseUrl, trimmed)
            if (localGenId != searchGenerationId) return@launch

            when (result) {
                is SearchResult.Success -> {
                    searchAdapter.submitList(result.songs)
                    showSearchState(SearchState.RESULTS)
                }
                is SearchResult.Empty -> showSearchState(SearchState.EMPTY)
                is SearchResult.Error -> {
                    tvErrorMessage.text = result.message
                    showSearchState(SearchState.ERROR)
                }
            }
        }
    }

    private enum class SearchState { LOADING, EMPTY, ERROR, RESULTS }

    private fun showSearchState(state: SearchState) {
        pbSearchLoading.visibility = if (state == SearchState.LOADING) View.VISIBLE else View.GONE
        tvEmptyResults.visibility = if (state == SearchState.EMPTY) View.VISIBLE else View.GONE
        layoutError.visibility = if (state == SearchState.ERROR) View.VISIBLE else View.GONE
        rvSearchResults.visibility = if (state == SearchState.RESULTS) View.VISIBLE else View.GONE
    }

    private fun refreshHomeData() {
        val recent = playlistDbHelper.getRecentlyPlayedSongs(10)
        homeRecentAdapter.submitList(recent)
    }

    private fun refreshCollectionData() {
        val likedCount = playlistDbHelper.getAllLikedSongs().size
        tvCollectionLikedCount.text = "$likedCount tracks"

        val historyCount = playlistDbHelper.getRecentlyPlayedSongs(100).size
        tvCollectionHistoryCount.text = "$historyCount recent plays"

        val playlists = playlistDbHelper.getAllPlaylists()
        collectionPlaylistAdapter.submitList(playlists)
    }

    private fun generateAndPlayMix(mixType: String) {
        val baseUrl = appSettings.getBaseUrl()
        Toast.makeText(this, "Generating $mixType...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val mix: PersonalizedMix = try {
                if (mixType == "Daily Mix") mixGenerator.generateDailyMix(baseUrl) else mixGenerator.generateDiscoveryMix(baseUrl)
            } catch (e: Exception) {
                PersonalizedMix(mixType.lowercase(), mixType, "Error generating mix: ${e.message}", emptyList())
            }

            if (mix.songs.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(mix.title)
                    .setMessage("Could not generate ${mix.title} right now. Listen to more music or perform a search first!")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            val mediaItems = mix.songs.map { SongMediaItemMapper.toMediaItem(it) }
            mediaController?.let { c ->
                c.stop()
                c.setMediaItems(mediaItems)
                c.prepare()
                c.play()
                Toast.makeText(this@MainActivity, "Playing ${mix.title} (${mix.songs.size} tracks)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLikedSongsDialog() {
        val liked = playlistDbHelper.getAllLikedSongs()
        if (liked.isEmpty()) {
            Toast.makeText(this, "No liked songs yet. Tap the heart icon on any track to save it here.", Toast.LENGTH_LONG).show()
            return
        }
        val items = liked.map { "${it.title} - ${it.artists}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Liked Songs (${liked.size})")
            .setItems(items) { _, which -> showSongOptionDialog(liked[which]) }
            .setPositiveButton("▶ Play All") { _, _ ->
                val mediaItems = liked.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.prepare()
                    c.play()
                }
            }
            .setNeutralButton("🔀 Shuffle All") { _, _ ->
                val mediaItems = liked.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.shuffleModeEnabled = true
                    c.prepare()
                    c.play()
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
                .setMessage("Queue is currently empty.")
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
                val item = controller.getMediaItemAt(which)
                val song = SongMediaItemMapper.fromMediaItem(item)
                showSongOptionDialog(song)
            }
            .setNegativeButton("Clear Queue") { _, _ ->
                controller.clearMediaItems()
                Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showHistoryDialog() {
        val recent = playlistDbHelper.getRecentlyPlayedSongs(50)
        if (recent.isEmpty()) {
            Toast.makeText(this, "No listening history recorded yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val items = recent.map { "${it.title} - ${it.artists}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Listening History (${recent.size})")
            .setItems(items) { _, which -> showSongOptionDialog(recent[which]) }
            .setPositiveButton("▶ Play All") { _, _ ->
                val mediaItems = recent.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.prepare()
                    c.play()
                }
            }
            .setNeutralButton("Clear History") { _, _ ->
                playlistDbHelper.clearHistory()
                refreshCollectionData()
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this).apply {
            hint = "Playlist Name"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tidal_text_primary))
        }
        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val id = playlistDbHelper.createPlaylist(name)
                    if (id != -1L) {
                        refreshCollectionData()
                        Toast.makeText(this, "Playlist '$name' created", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Playlist name may already exist", Toast.LENGTH_SHORT).show()
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
            "📋 View / Manage Tracks",
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
                            mediaController?.let { c ->
                                c.stop()
                                c.setMediaItems(mediaItems)
                                c.prepare()
                                c.play()
                            }
                        }
                    }
                    1 -> showPlaylistSongsDialog(playlist, songs)
                    2 -> {
                        playlistDbHelper.deletePlaylist(playlist.id)
                        refreshCollectionData()
                        Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setPositiveButton("Close", null)
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
                                refreshCollectionData()
                                Toast.makeText(this, "Removed from playlist", Toast.LENGTH_SHORT).show()
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
        val likeText = if (isLiked) "Unlike Track" else "Like Track ♥"
        val options = arrayOf("Play Now", "Add to Queue", "Start Radio", "View Lyrics", likeText, "Add to Playlist...")
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playSongNow(song)
                    1 -> addSongToQueue(song)
                    2 -> startRadioForSong(song)
                    3 -> showLyricsDialog(song)
                    4 -> toggleLikeSong(song)
                    5 -> showAddToPlaylistDialog(song)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLyricsDialog(song: Song) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_lyrics, null)
        dialog.setContentView(view)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        val ivClose: ImageView = view.findViewById(R.id.ivCloseLyrics)
        val tvSongTitle: TextView = view.findViewById(R.id.tvLyricsSongTitle)
        val tvSongArtist: TextView = view.findViewById(R.id.tvLyricsSongArtist)
        val pbLoading: ProgressBar = view.findViewById(R.id.pbLyricsLoading)
        val layoutEmpty: LinearLayout = view.findViewById(R.id.layoutLyricsEmpty)
        val tvEmptyMsg: TextView = view.findViewById(R.id.tvLyricsEmptyMessage)
        val btnRetry: Button = view.findViewById(R.id.btnRetryLyrics)
        val nsvLyrics: NestedScrollView = view.findViewById(R.id.nsvLyrics)
        val tvBody: TextView = view.findViewById(R.id.tvLyricsBody)
        val tvCopyright: TextView = view.findViewById(R.id.tvLyricsCopyright)

        tvSongTitle.text = song.title
        tvSongArtist.text = song.artists

        ivClose.setOnClickListener { dialog.dismiss() }

        fun loadLyrics() {
            pbLoading.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            nsvLyrics.visibility = View.GONE

            val baseUrl = appSettings.getBaseUrl()
            lifecycleScope.launch {
                when (val result = lyricsService.fetchLyrics(baseUrl, song.id)) {
                    is LyricsResult.Success -> {
                        pbLoading.visibility = View.GONE
                        layoutEmpty.visibility = View.GONE
                        nsvLyrics.visibility = View.VISIBLE
                        tvBody.text = result.lyrics
                        if (!result.copyright.isNullOrBlank()) {
                            tvCopyright.visibility = View.VISIBLE
                            tvCopyright.text = result.copyright
                        } else {
                            tvCopyright.visibility = View.GONE
                        }
                    }
                    is LyricsResult.Unavailable -> {
                        pbLoading.visibility = View.GONE
                        nsvLyrics.visibility = View.GONE
                        layoutEmpty.visibility = View.VISIBLE
                        tvEmptyMsg.text = result.message
                        btnRetry.visibility = View.GONE
                    }
                    is LyricsResult.Error -> {
                        pbLoading.visibility = View.GONE
                        nsvLyrics.visibility = View.GONE
                        layoutEmpty.visibility = View.VISIBLE
                        tvEmptyMsg.text = result.message
                        btnRetry.visibility = View.VISIBLE
                    }
                }
            }
        }

        btnRetry.setOnClickListener { loadLyrics() }

        loadLyrics()
        dialog.show()
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
                    refreshCollectionData()
                    Toast.makeText(this, "Added to '${playlist.name}'", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Track already in playlist", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_backend_settings, null)
        dialog.setContentView(view)

        val etBase: EditText = view.findViewById(R.id.etBaseUrl)
        val btnSave: Button = view.findViewById(R.id.btnSaveBaseUrl)
        val btnReset: Button = view.findViewById(R.id.btnResetBaseUrl)
        val tvTelem: TextView = view.findViewById(R.id.tvTelemetry)

        etBase.setText(appSettings.getBaseUrl())
        val stats = filterListManager.getStatisticsSnapshot()
        tvTelem.text = "Queries: ${stats.totalRequestsEvaluated} | Allowed: ${stats.allowedRequests} | Blocked: ${stats.blockedRequests} | Audio CDN Bypass: ${stats.audioStreamBypasses}"

        btnSave.setOnClickListener {
            val saved = appSettings.setBaseUrl(etBase.text.toString())
            etBase.setText(saved)
            Toast.makeText(this, "Saved endpoint: $saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnReset.setOnClickListener {
            val defaultUrl = appSettings.resetToDefault()
            etBase.setText(defaultUrl)
            Toast.makeText(this, "Reset to default: $defaultUrl", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(positionUpdateRunnable)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(positionUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
    }
}
