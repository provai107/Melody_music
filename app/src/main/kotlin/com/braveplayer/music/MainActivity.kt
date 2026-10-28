package com.braveplayer.music

import android.Manifest
import android.content.ComponentName
import android.content.Intent
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
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.widget.SwitchCompat
import android.app.Dialog
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.api.SearchResult
import com.braveplayer.music.discovery.GenreCategory
import com.braveplayer.music.discovery.MixGenerator
import com.braveplayer.music.discovery.MoodCategory
import com.braveplayer.music.discovery.MusicCatalog
import com.braveplayer.music.discovery.MusicRecommendationEngine
import com.braveplayer.music.discovery.PersonalizedMix
import com.braveplayer.music.download.SongDownloadHelper
import com.braveplayer.music.lyrics.LyricsResult
import com.braveplayer.music.lyrics.LyricsService
import com.braveplayer.music.lyrics.SyncedLyricsAdapter
import com.braveplayer.music.media.SongMediaItemMapper
import com.braveplayer.music.model.Song
import androidx.core.widget.NestedScrollView
import com.braveplayer.music.playlist.Playlist
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.service.PlaybackService
import com.braveplayer.music.settings.AppSettings
import com.braveplayer.music.update.AppUpdateDialogHelper
import com.braveplayer.music.update.AppUpdateManager
import com.braveplayer.music.update.UpdateCheckResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.braveplayer.music.ui.SearchChipAdapter
import com.braveplayer.music.settings.FilterEngineProvider
import com.braveplayer.music.ui.GenreAdapter
import com.braveplayer.music.ui.MoodAdapter
import com.braveplayer.music.ui.PlaylistAdapter
import com.braveplayer.music.ui.QueueAdapter
import com.braveplayer.music.ui.SongAdapter
import com.braveplayer.music.ui.HorizontalSongCardAdapter
import com.braveplayer.music.ui.DailyMixCardAdapter
import com.braveplayer.music.ui.CuratedPlaylistCardAdapter
import com.braveplayer.music.ui.HomeCollectionItem
import java.util.Calendar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.util.concurrent.ListenableFuture
import android.content.Context
import com.braveplayer.music.ui.RecentSearchAdapter
import com.braveplayer.music.ui.TrendingRowAdapter
import com.braveplayer.music.ui.CategoryChannelAdapter
import com.braveplayer.music.playlist.SearchHistoryItem
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.delay
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

        // Verified official direct Melody APK download URL from GitHub Releases
        const val OFFICIAL_APK_DOWNLOAD_URL =
            "https://github.com/hernos5/melody/releases/latest/download/Melody.apk"

        fun buildShareMessage(song: Song?): String {
            return if (song != null && song.title.isNotBlank()) {
                val artistPart = if (song.artists.isNotBlank()) " by ${song.artists}" else ""
                "Listening to \"${song.title}\"$artistPart on Melody 🎵\n\nDownload the latest version here: $OFFICIAL_APK_DOWNLOAD_URL"
            } else {
                "Listen with Melody 🎵 Download the latest version here: $OFFICIAL_APK_DOWNLOAD_URL"
            }
        }
    }

    private lateinit var appSettings: AppSettings
    private lateinit var filterListManager: FilterListManager
    private lateinit var metadataService: MusicMetadataService
    private lateinit var playlistDbHelper: PlaylistDatabaseHelper
    private lateinit var recommendationEngine: MusicRecommendationEngine
    private lateinit var mixGenerator: MixGenerator
    private lateinit var lyricsService: LyricsService
    private lateinit var songDownloadHelper: SongDownloadHelper

    // Adapters
    private lateinit var searchAdapter: SongAdapter
    private lateinit var collectionPlaylistAdapter: PlaylistAdapter

    // Bottom Navigation & Tabs
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var appHeaderBar: View
    private lateinit var containerHome: View
    private lateinit var containerSearch: View
    private lateinit var containerCollection: View
    private lateinit var btnTopSearch: ImageView
    private lateinit var btnHomeQuickSearch: View

    // Home Tab UI & Modern Carousels
    private var cardDailyMix: View? = null
    private var cardDiscoveryMix: View? = null
    private var btnHomeLikedShortcut: View? = null
    private var btnHomeQueueShortcut: View? = null
    private lateinit var rvMoodHome: RecyclerView
    private lateinit var rvGenreHome: RecyclerView
    private lateinit var rvRecentlyPlayedHome: RecyclerView
    private lateinit var tvHomeGreeting: TextView
    private lateinit var tvHomeGreetingSub: TextView
    private var btnQuickDailyMix2: View? = null
    private var btnQuickChillMix: View? = null
    private lateinit var layoutRecentEmpty: View
    private lateinit var btnSeeAllRecent: View
    private lateinit var rvTrendingCarousel: RecyclerView
    private lateinit var pbTrendingLoading: ProgressBar
    private lateinit var rvMadeForYouCarousel: RecyclerView
    private lateinit var pbMadeForYouLoading: ProgressBar
    private lateinit var rvDailyMixesCarousel: RecyclerView
    private lateinit var rvMostPlayedCarousel: RecyclerView
    private lateinit var layoutMostPlayedEmpty: View
    private lateinit var rvOtherGenresCarousel: RecyclerView
    private lateinit var rvRecommendedPlaylistsCarousel: RecyclerView
    private lateinit var cardContinueListening: View
    private lateinit var ivContinueCover: ImageView
    private lateinit var tvContinueTitle: TextView
    private lateinit var tvContinueArtist: TextView
    private lateinit var btnContinuePlay: View

    // Reusable Home Carousel Adapters
    private lateinit var homeRecentCardAdapter: HorizontalSongCardAdapter
    private lateinit var trendingCardAdapter: HorizontalSongCardAdapter
    private lateinit var madeForYouCardAdapter: HorizontalSongCardAdapter
    private lateinit var dailyMixCardAdapter: DailyMixCardAdapter
    private lateinit var mostPlayedCardAdapter: HorizontalSongCardAdapter
    private lateinit var curatedPlaylistCardAdapter: CuratedPlaylistCardAdapter
    private var lastPlayedSong: Song? = null

    // Explore / Search Tab UI
    private lateinit var etSearchQuery: EditText
    private lateinit var btnSearchClear: ImageView
    private lateinit var layoutExploreDiscovery: View
    private lateinit var layoutSearchResultsContainer: View
    private lateinit var pbSearchLoading: ProgressBar
    private lateinit var tvEmptyResults: TextView
    private lateinit var layoutError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetrySearch: Button
    private lateinit var rvSearchResults: RecyclerView

    // Explore: Recent Searches
    private lateinit var layoutRecentSearchesSection: View
    private lateinit var btnClearRecentSearches: TextView
    private lateinit var rvRecentSearches: RecyclerView
    private lateinit var recentSearchAdapter: RecentSearchAdapter

    // Explore: Trending Section
    private lateinit var layoutTrendingSection: View
    private lateinit var btnSeeAllTrendingExplore: TextView
    private lateinit var pbTrendingExploreLoading: ProgressBar
    private lateinit var rvTrendingExplore: RecyclerView
    private lateinit var trendingExploreAdapter: TrendingRowAdapter

    // Explore: Channel Grid
    private lateinit var layoutCategoryChannelsSection: View
    private lateinit var rvCategoryChannels: RecyclerView
    private lateinit var categoryChannelAdapter: CategoryChannelAdapter

    // My Collection Tab UI
    private lateinit var btnCreatePlaylistAction: Button
    private lateinit var cardCollectionLiked: View
    private lateinit var tvCollectionLikedCount: TextView
    private lateinit var cardCollectionHistory: View
    private lateinit var tvCollectionHistoryCount: TextView
    private lateinit var cardCollectionDownloads: View
    private lateinit var tvCollectionDownloadsCount: TextView
    private lateinit var rvPlaylistsCollection: RecyclerView
    private var layoutPlaylistsEmpty: View? = null

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
    private lateinit var btnNowPlayingDownloadAction: View
    private lateinit var btnTopCast: ImageView
    private lateinit var btnNowPlayingCast: ImageView
    private lateinit var btnNowPlayingDislike: ImageView
    private lateinit var btnNowPlayingShareAction: View
    private lateinit var btnNowPlayingLyricsActionBottom: View

    // MediaController connection
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Spotify-style 3-mode Shuffle
    enum class SmartShuffleState {
        NORMAL,
        SHUFFLE,
        SMART_SHUFFLE
    }
    private var smartShuffleState = SmartShuffleState.NORMAL

    // Search Job & Tracking
    private var searchJob: Job? = null
    private var searchGenerationId: Long = 0L
    private var lastQuery: String = ""
    private var isUserTrackingSeek: Boolean = false

    // Automated App Updates
    private lateinit var updateManager: AppUpdateManager
    private lateinit var updateDialogHelper: AppUpdateDialogHelper

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
        songDownloadHelper = SongDownloadHelper(this, playlistDbHelper)
        updateManager = AppUpdateManager(this)
        updateDialogHelper = AppUpdateDialogHelper(this, updateManager, lifecycleScope)
        com.braveplayer.music.util.NetworkUtils.clearAudioStreamingCache(this)

        initViews()
        setupAdapters()
        setupBottomNavigation()
        setupNowPlayingSheet()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        connectToPlaybackService()

        refreshHomeData()
        refreshCollectionData()
        refreshSearchRecommendations()
        checkAppUpdateOnLaunch()
    }

    private fun checkAppUpdateOnLaunch() {
        lifecycleScope.launch {
            delay(2500L) // Allow home UI and player to initialize first without lag
            val result = updateManager.checkForUpdate(isManual = false)
            if (result is UpdateCheckResult.UpdateAvailable) {
                updateDialogHelper.showUpdateDialog(result.metadata)
            }
        }
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
        appHeaderBar = findViewById(R.id.topAppBar)
        containerHome = findViewById(R.id.containerHome)
        containerSearch = findViewById(R.id.containerSearch)
        containerCollection = findViewById(R.id.containerCollection)
        btnTopSearch = findViewById(R.id.btnTopSearch)
        btnHomeQuickSearch = findViewById(R.id.btnHomeQuickSearch)

        // Home UI & Modern Carousels
        rvMoodHome = findViewById(R.id.rvMoodHome)
        rvGenreHome = findViewById(R.id.rvGenreHome)
        rvRecentlyPlayedHome = findViewById(R.id.rvRecentlyPlayedHome)
        tvHomeGreeting = findViewById(R.id.tvHomeGreeting)
        tvHomeGreetingSub = findViewById(R.id.tvHomeGreetingSub)
        layoutRecentEmpty = findViewById(R.id.layoutRecentEmpty)
        btnSeeAllRecent = findViewById(R.id.btnSeeAllRecent)
        rvTrendingCarousel = findViewById(R.id.rvTrendingCarousel)
        pbTrendingLoading = findViewById(R.id.pbTrendingLoading)
        rvMadeForYouCarousel = findViewById(R.id.rvMadeForYouCarousel)
        pbMadeForYouLoading = findViewById(R.id.pbMadeForYouLoading)
        rvDailyMixesCarousel = findViewById(R.id.rvDailyMixesCarousel)
        rvMostPlayedCarousel = findViewById(R.id.rvMostPlayedCarousel)
        layoutMostPlayedEmpty = findViewById(R.id.layoutMostPlayedEmpty)
        rvOtherGenresCarousel = findViewById(R.id.rvOtherGenresCarousel)
        rvRecommendedPlaylistsCarousel = findViewById(R.id.rvRecommendedPlaylistsCarousel)
        cardContinueListening = findViewById(R.id.cardContinueListening)
        ivContinueCover = findViewById(R.id.ivContinueCover)
        tvContinueTitle = findViewById(R.id.tvContinueTitle)
        tvContinueArtist = findViewById(R.id.tvContinueArtist)
        btnContinuePlay = findViewById(R.id.btnContinuePlay)

        // Explore / Search UI
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnSearchClear = findViewById(R.id.btnSearchClear)
        layoutExploreDiscovery = findViewById(R.id.layoutExploreDiscovery)
        layoutSearchResultsContainer = findViewById(R.id.layoutSearchResultsContainer)
        pbSearchLoading = findViewById(R.id.pbSearchLoading)
        tvEmptyResults = findViewById(R.id.tvEmptyResults)
        layoutError = findViewById(R.id.layoutError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetrySearch = findViewById(R.id.btnRetrySearch)
        rvSearchResults = findViewById(R.id.rvSearchResults)

        // Explore: Recent Searches
        layoutRecentSearchesSection = findViewById(R.id.layoutRecentSearchesSection)
        btnClearRecentSearches = findViewById(R.id.btnClearRecentSearches)
        rvRecentSearches = findViewById(R.id.rvRecentSearches)

        // Explore: Trending Section
        layoutTrendingSection = findViewById(R.id.layoutTrendingSection)
        btnSeeAllTrendingExplore = findViewById(R.id.btnSeeAllTrendingExplore)
        pbTrendingExploreLoading = findViewById(R.id.pbTrendingExploreLoading)
        rvTrendingExplore = findViewById(R.id.rvTrendingExplore)

        // Explore: Channel Grid
        layoutCategoryChannelsSection = findViewById(R.id.layoutCategoryChannelsSection)
        rvCategoryChannels = findViewById(R.id.rvCategoryChannels)

        // My Collection UI
        btnCreatePlaylistAction = findViewById(R.id.btnCreatePlaylistAction)
        cardCollectionLiked = findViewById(R.id.cardCollectionLiked)
        tvCollectionLikedCount = findViewById(R.id.tvCollectionLikedCount)
        cardCollectionHistory = findViewById(R.id.cardCollectionHistory)
        tvCollectionHistoryCount = findViewById(R.id.tvCollectionHistoryCount)
        cardCollectionDownloads = findViewById(R.id.cardCollectionDownloads)
        tvCollectionDownloadsCount = findViewById(R.id.tvCollectionDownloadsCount)
        rvPlaylistsCollection = findViewById(R.id.rvPlaylistsCollection)
        layoutPlaylistsEmpty = findViewById(R.id.layoutPlaylistsEmpty)
        findViewById<View?>(R.id.btnEmptyCreatePlaylist)?.setOnClickListener { showCreatePlaylistDialog() }

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
        btnNowPlayingDownloadAction = findViewById(R.id.btnNowPlayingDownloadAction)
        btnTopCast = findViewById(R.id.btnTopCast)
        btnNowPlayingCast = findViewById(R.id.btnNowPlayingCast)
        btnNowPlayingDislike = findViewById(R.id.btnNowPlayingDislike)
        btnNowPlayingShareAction = findViewById(R.id.btnNowPlayingShareAction)
        btnNowPlayingLyricsActionBottom = findViewById(R.id.btnNowPlayingLyricsActionBottom)
    }

    private fun setupAdapters() {
        // Moods Carousel on Home
        rvMoodHome.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvMoodHome.adapter = MoodAdapter(MusicCatalog.MOODS) { mood ->
            openMoodTracks(mood)
        }

        // Genres Carousel on Home
        rvGenreHome.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvGenreHome.adapter = GenreAdapter(MusicCatalog.GENRES) { genre ->
            openGenreTracks(genre)
        }

        // Explore: Recent Searches
        recentSearchAdapter = RecentSearchAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onItemClick = { item ->
                handleHistoricalItemClick(item)
            },
            onRemoveClick = { item ->
                playlistDbHelper.removeSearchHistoryItem(item.id)
                refreshRecentSearchesSection()
            }
        )
        rvRecentSearches.layoutManager = LinearLayoutManager(this)
        rvRecentSearches.adapter = recentSearchAdapter

        // Explore: Trending Rows
        trendingExploreAdapter = TrendingRowAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongClicked = { song ->
                playlistDbHelper.recordSongSearch(song)
                playSongNow(song)
            },
            onPlayClicked = { song ->
                playlistDbHelper.recordSongSearch(song)
                playSongNow(song)
            },
            onOptionClicked = { song -> showSongOptionDialog(song) }
        )
        rvTrendingExplore.layoutManager = LinearLayoutManager(this)
        rvTrendingExplore.adapter = trendingExploreAdapter

        // Explore: 2-Column Category Channel Grid
        categoryChannelAdapter = CategoryChannelAdapter(MusicCatalog.CHANNELS) { category ->
            openCategoryChannel(category)
        }
        rvCategoryChannels.layoutManager = GridLayoutManager(this, 2)
        rvCategoryChannels.adapter = categoryChannelAdapter

        // Live Search Results List
        searchAdapter = SongAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongSelected = { song ->
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etSearchQuery.windowToken, 0)
                playlistDbHelper.recordSongSearch(song)
                playSongNow(song)
            },
            onSongAddToQueue = { song -> addSongToQueue(song) },
            onSongOptions = { song -> showSongOptionDialog(song) },
            onSongLikeToggled = { song -> toggleLikeSong(song) },
            isSongLiked = { songId -> playlistDbHelper.isSongLiked(songId) }
        )
        rvSearchResults.layoutManager = LinearLayoutManager(this)
        rvSearchResults.adapter = searchAdapter
        rvSearchResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(etSearchQuery.windowToken, 0)
                }
            }
        })

        // SECTION 4: Recently Played Horizontal Carousel
        homeRecentCardAdapter = HorizontalSongCardAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongClicked = { song -> playSongNow(song) },
            onPlayClicked = { song -> playSongNow(song) },
            onOptionClicked = { song -> showSongOptionDialog(song) }
        )
        rvRecentlyPlayedHome.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRecentlyPlayedHome.adapter = homeRecentCardAdapter

        // SECTION 5: Trending Songs Horizontal Carousel
        trendingCardAdapter = HorizontalSongCardAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongClicked = { song -> playSongNow(song) },
            onPlayClicked = { song -> playSongNow(song) },
            onOptionClicked = { song -> showSongOptionDialog(song) },
            showRankBadge = true
        )
        rvTrendingCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTrendingCarousel.adapter = trendingCardAdapter

        // SECTION 6: Made for You Recommendations Carousel
        madeForYouCardAdapter = HorizontalSongCardAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongClicked = { song -> playSongNow(song) },
            onPlayClicked = { song -> playSongNow(song) },
            onOptionClicked = { song -> showSongOptionDialog(song) }
        )
        rvMadeForYouCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvMadeForYouCarousel.adapter = madeForYouCardAdapter

        // SECTION 7: Daily Mixes Horizontal Carousel
        dailyMixCardAdapter = DailyMixCardAdapter(
            onMixClicked = { mix -> showMixDetailDialog(mix) },
            onPlayClicked = { mix -> playMixDirectly(mix, shuffle = false) },
            onShuffleClicked = { mix -> playMixDirectly(mix, shuffle = true) }
        )
        rvDailyMixesCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvDailyMixesCarousel.adapter = dailyMixCardAdapter

        // SECTION 8: Most Played Songs Carousel
        mostPlayedCardAdapter = HorizontalSongCardAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongClicked = { song -> playSongNow(song) },
            onPlayClicked = { song -> playSongNow(song) },
            onOptionClicked = { song -> showSongOptionDialog(song) },
            showRankBadge = true
        )
        rvMostPlayedCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvMostPlayedCarousel.adapter = mostPlayedCardAdapter

        // SECTION 9 & 10: Genres Carousels
        rvOtherGenresCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvOtherGenresCarousel.adapter = GenreAdapter(MusicCatalog.GENRES.drop(6)) { genre ->
            openGenreTracks(genre)
        }

        // SECTION 11: Recommended Albums & Playlists Carousel
        curatedPlaylistCardAdapter = CuratedPlaylistCardAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onCollectionClicked = { col -> openCuratedCollection(col) },
            onPlayClicked = { col -> playCuratedCollection(col) }
        )
        rvRecommendedPlaylistsCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRecommendedPlaylistsCarousel.adapter = curatedPlaylistCardAdapter

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
                    refreshSearchRecommendations()
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

        bottomNavigation.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    (containerHome as? androidx.core.widget.NestedScrollView)?.smoothScrollTo(0, 0)
                }
                R.id.nav_search -> {
                    if (layoutSearchResultsContainer.visibility == View.VISIBLE) {
                        rvSearchResults.smoothScrollToPosition(0)
                    } else {
                        (layoutExploreDiscovery as? androidx.core.widget.NestedScrollView)?.smoothScrollTo(0, 0)
                    }
                }
                R.id.nav_collection -> {
                    (containerCollection as? androidx.core.widget.NestedScrollView)?.smoothScrollTo(0, 0)
                }
            }
        }
    }

    private enum class Tab { HOME, SEARCH, COLLECTION }

    private fun switchTab(tab: Tab) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)

        appHeaderBar.visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        containerHome.visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        containerSearch.visibility = if (tab == Tab.SEARCH) View.VISIBLE else View.GONE
        containerCollection.visibility = if (tab == Tab.COLLECTION) View.VISIBLE else View.GONE
    }

    private fun setupNowPlayingSheet() {
        nowPlayingBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        nowPlayingBehavior.peekHeight = 0

        nowPlayingBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(bottomSheet.windowToken, 0)
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED || newState == BottomSheetBehavior.STATE_HIDDEN) {
                    layoutMiniPlayer.alpha = 1f
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (layoutMiniPlayer.visibility == View.VISIBLE || slideOffset > 0f) {
                    layoutMiniPlayer.alpha = (1f - slideOffset).coerceIn(0f, 1f)
                }
            }
        })

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
        btnTopSearch.setOnClickListener { openExploreSearch() }
        btnHomeQuickSearch.setOnClickListener { openExploreSearch() }

        // Home Actions
        cardDailyMix?.setOnClickListener { openMixDetail("Daily Mix 1") }
        btnQuickDailyMix2?.setOnClickListener { openMixDetail("Daily Mix 2") }
        btnHomeLikedShortcut?.setOnClickListener { showLikedSongsDialog() }
        btnQuickChillMix?.setOnClickListener { openMixDetail("Chill & Focus Mix") }
        cardDiscoveryMix?.setOnClickListener { openMixDetail("Discovery Mix") }
        btnHomeQueueShortcut?.setOnClickListener { showQueueDialog() }
        btnSeeAllRecent.setOnClickListener { showHistoryDialog() }
        cardContinueListening.setOnClickListener { resumeLastPlayback() }
        btnContinuePlay.setOnClickListener { resumeLastPlayback() }

        // System Back Navigation & Soft Keyboard Dismissal
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nowPlayingBehavior.state != BottomSheetBehavior.STATE_COLLAPSED &&
                    nowPlayingBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                    nowPlayingBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    return
                }
                if (containerSearch.visibility == View.VISIBLE) {
                    if (!etSearchQuery.text.isNullOrEmpty()) {
                        etSearchQuery.text?.clear()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(etSearchQuery.windowToken, 0)
                        return
                    }
                    if (bottomNavigation.selectedItemId != R.id.nav_home) {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(etSearchQuery.windowToken, 0)
                        bottomNavigation.selectedItemId = R.id.nav_home
                        return
                    }
                }
                if (containerCollection.visibility == View.VISIBLE) {
                    if (bottomNavigation.selectedItemId != R.id.nav_home) {
                        bottomNavigation.selectedItemId = R.id.nav_home
                        return
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        // Explore & Live Search Actions
        btnSearchClear.setOnClickListener {
            etSearchQuery.text?.clear()
        }

        btnClearRecentSearches.setOnClickListener {
            playlistDbHelper.clearSearchHistory()
            refreshRecentSearchesSection()
        }

        (layoutExploreDiscovery as? androidx.core.widget.NestedScrollView)?.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etSearchQuery.windowToken, 0)
            }
        )

        btnSeeAllTrendingExplore.setOnClickListener {
            showFullTrendingDialog()
        }

        btnRetrySearch.setOnClickListener {
            if (lastQuery.isNotBlank()) {
                val genId = ++searchGenerationId
                performLiveSearch(lastQuery, genId)
            }
        }

        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                btnSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

                // Cancel pending debounce and network requests
                liveSearchJob?.cancel()

                val trimmed = query.trim()
                if (trimmed.isEmpty()) {
                    searchGenerationId++
                    showSearchState(SearchState.IDLE)
                    refreshRecentSearchesSection()
                    return
                }

                // Show loading state immediately while preparing debounced request
                showSearchState(SearchState.LOADING)
                val currentGenId = ++searchGenerationId

                // Debounce network request by 300 ms
                liveSearchJob = lifecycleScope.launch {
                    delay(300L)
                    performLiveSearch(trimmed, currentGenId)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val trimmed = etSearchQuery.text.toString().trim()
                if (trimmed.isNotEmpty()) {
                    liveSearchJob?.cancel()
                    val currentGenId = ++searchGenerationId
                    performLiveSearch(trimmed, currentGenId)
                    playlistDbHelper.recordSearchHistory(
                        SearchHistoryItem(
                            queryText = trimmed,
                            title = trimmed,
                            subtitle = "Search query",
                            contentType = "Search"
                        )
                    )
                }
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etSearchQuery.windowToken, 0)
                true
            } else {
                false
            }
        }

        // Collection Actions
        btnCreatePlaylistAction.setOnClickListener { showCreatePlaylistDialog() }
        cardCollectionLiked.setOnClickListener { showLikedSongsDialog() }
        cardCollectionHistory.setOnClickListener { showHistoryDialog() }
        cardCollectionDownloads.setOnClickListener { showDownloadedSongsDialog() }

        // Mini Player Controls
        btnMiniPlayPause.setOnClickListener {
            mediaController?.let { c ->
                if (c.isPlaying) {
                    c.pause()
                } else {
                    val currentItem = c.currentMediaItem
                    if (currentItem != null) {
                        val songId = currentItem.mediaId
                        val isDownloaded = playlistDbHelper.isSongDownloaded(songId)
                        val isOnline = com.braveplayer.music.util.NetworkUtils.isOnline(this)
                        if (!isOnline && !isDownloaded) {
                            Toast.makeText(this, "You're offline. Streamed tracks cannot play offline.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                    }
                    c.play()
                }
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
                if (c.isPlaying) {
                    c.pause()
                } else {
                    val currentItem = c.currentMediaItem
                    if (currentItem != null) {
                        val songId = currentItem.mediaId
                        val isDownloaded = playlistDbHelper.isSongDownloaded(songId)
                        val isOnline = com.braveplayer.music.util.NetworkUtils.isOnline(this)
                        if (!isOnline && !isDownloaded) {
                            Toast.makeText(this, "You're offline. Streamed tracks cannot play offline.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                    }
                    c.play()
                }
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
            if (newEnabled) {
                triggerAutoplayCheck()
            }
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
        findViewById<View?>(R.id.btnNowPlayingQueueTop)?.setOnClickListener { showQueueDialog() }
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
        btnNowPlayingDownloadAction.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            songDownloadHelper.downloadSong(song) {
                refreshCollectionData()
            }
        }

        btnTopCast.setOnClickListener {
            Toast.makeText(this, "Connect to a device (Cast)", Toast.LENGTH_SHORT).show()
        }
        btnNowPlayingCast.setOnClickListener {
            Toast.makeText(this, "Connect to a device (Cast)", Toast.LENGTH_SHORT).show()
        }
        btnNowPlayingDislike.setOnClickListener {
            val item = mediaController?.currentMediaItem
            if (item != null) {
                val song = SongMediaItemMapper.fromMediaItem(item)
                playlistDbHelper.recordNotInterested(song)
                Toast.makeText(this, "Marked as Not Interested. Deprioritizing track.", Toast.LENGTH_SHORT).show()
                mediaController?.seekToNextMediaItem()
            }
        }
        btnNowPlayingShareAction.setOnClickListener {
            val item = mediaController?.currentMediaItem
            val song = if (item != null) SongMediaItemMapper.fromMediaItem(item) else null
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, if (!song?.title.isNullOrBlank()) "Listen to \"${song?.title}\" on Melody" else "Download Melody")
                putExtra(Intent.EXTRA_TEXT, buildShareMessage(song))
            }
            startActivity(Intent.createChooser(shareIntent, "Share Track"))
        }
        btnNowPlayingLyricsActionBottom.setOnClickListener {
            val item = mediaController?.currentMediaItem ?: return@setOnClickListener
            val song = SongMediaItemMapper.fromMediaItem(item)
            showLyricsDialog(song)
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
                if (!shuffleModeEnabled) {
                    smartShuffleState = SmartShuffleState.NORMAL
                    appSettings.setShuffleMode(AppSettings.SHUFFLE_MODE_NORMAL)
                }
                updateShuffleControls()
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
            val primaryColor = ContextCompat.getColor(
                this,
                if (appSettings.isReducedIntensityEnabled()) R.color.accentPressed else R.color.accentPrimary
            )
            val mutedColor = ContextCompat.getColor(this, R.color.textSecondary)

            btnMiniLike.setImageResource(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            btnMiniLike.setColorFilter(if (isLiked) primaryColor else mutedColor)
            btnMiniLike.contentDescription = if (isLiked) "Remove $title from liked songs" else "Add $title to liked songs"

            btnMiniPlayPause.setImageResource(if (controller.isPlaying) R.drawable.ic_ytm_pause else R.drawable.ic_ytm_play)
            btnMiniPlayPause.contentDescription = if (controller.isPlaying) "Pause $title" else "Play $title"

            // Update Now Playing Sheet
            tvNowPlayingTitle.text = title
            tvNowPlayingArtist.text = if (album.isNotBlank()) "$artist • $album" else artist
            loadArtwork(song.imageUrl, ivNowPlayingCover)

            btnNowPlayingLike.setImageResource(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            btnNowPlayingLike.setColorFilter(if (isLiked) primaryColor else mutedColor)
            btnNowPlayingLike.contentDescription = if (isLiked) "Remove $title from liked songs" else "Add $title to liked songs"

            btnNowPlayingPlayPause.setImageResource(if (controller.isPlaying) R.drawable.ic_ytm_pause else R.drawable.ic_ytm_play)
            btnNowPlayingPlayPause.contentDescription = if (controller.isPlaying) "Pause $title" else "Play $title"
            findViewById<View?>(R.id.layoutNowPlayingPlayPause)?.contentDescription = if (controller.isPlaying) "Pause $title" else "Play $title"
        } else {
            layoutMiniPlayer.visibility = View.GONE
        }

        updateRepeatControls(controller.repeatMode)

        val savedMode = appSettings.getShuffleMode()
        if (savedMode == AppSettings.SHUFFLE_MODE_SMART && controller.shuffleModeEnabled) {
            smartShuffleState = SmartShuffleState.SMART_SHUFFLE
        } else if (controller.shuffleModeEnabled) {
            smartShuffleState = SmartShuffleState.SHUFFLE
        } else {
            smartShuffleState = SmartShuffleState.NORMAL
        }
        updateShuffleControls()

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
        val primaryColor = ContextCompat.getColor(
            this,
            if (appSettings.isReducedIntensityEnabled()) R.color.accentPressed else R.color.accentPrimary
        )
        val mutedColor = ContextCompat.getColor(this, R.color.textSecondary)
        when (repeatMode) {
            Player.REPEAT_MODE_ALL -> {
                btnNowPlayingRepeat.setImageResource(R.drawable.ic_ytm_repeat)
                btnNowPlayingRepeat.setColorFilter(primaryColor)
                btnNowPlayingRepeat.contentDescription = "Repeat all enabled"
            }
            Player.REPEAT_MODE_ONE -> {
                btnNowPlayingRepeat.setImageResource(R.drawable.ic_ytm_repeat_one)
                btnNowPlayingRepeat.setColorFilter(primaryColor)
                btnNowPlayingRepeat.contentDescription = "Repeat one enabled"
            }
            else -> {
                btnNowPlayingRepeat.setImageResource(R.drawable.ic_ytm_repeat)
                btnNowPlayingRepeat.setColorFilter(mutedColor)
                btnNowPlayingRepeat.contentDescription = "Repeat disabled"
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

    private fun updateShuffleControls(state: SmartShuffleState = smartShuffleState) {
        val primaryColor = ContextCompat.getColor(
            this,
            if (appSettings.isReducedIntensityEnabled()) R.color.accentPressed else R.color.accentPrimary
        )
        val mutedColor = ContextCompat.getColor(this, R.color.textSecondary)
        when (state) {
            SmartShuffleState.NORMAL -> {
                btnNowPlayingShuffle.setImageResource(R.drawable.ic_ytm_shuffle)
                btnNowPlayingShuffle.setColorFilter(mutedColor)
                btnNowPlayingShuffle.contentDescription = "Shuffle disabled"
            }
            SmartShuffleState.SHUFFLE -> {
                btnNowPlayingShuffle.setImageResource(R.drawable.ic_ytm_shuffle)
                btnNowPlayingShuffle.setColorFilter(primaryColor)
                btnNowPlayingShuffle.contentDescription = "Shuffle enabled"
            }
            SmartShuffleState.SMART_SHUFFLE -> {
                btnNowPlayingShuffle.setImageResource(R.drawable.ic_ytm_smart_shuffle)
                btnNowPlayingShuffle.setColorFilter(primaryColor)
                btnNowPlayingShuffle.contentDescription = "Smart Shuffle enabled with recommendations"
            }
        }
    }

    private fun toggleShuffle() {
        val controller = mediaController ?: return
        when (smartShuffleState) {
            SmartShuffleState.NORMAL -> {
                smartShuffleState = SmartShuffleState.SHUFFLE
                appSettings.setShuffleMode(AppSettings.SHUFFLE_MODE_SHUFFLE)
                controller.shuffleModeEnabled = true
                removeInjectedSmartShuffleRecommendations()
                updateShuffleControls(SmartShuffleState.SHUFFLE)
                Toast.makeText(this, "🔀 Shuffle On", Toast.LENGTH_SHORT).show()
            }
            SmartShuffleState.SHUFFLE -> {
                smartShuffleState = SmartShuffleState.SMART_SHUFFLE
                appSettings.setShuffleMode(AppSettings.SHUFFLE_MODE_SMART)
                controller.shuffleModeEnabled = true
                updateShuffleControls(SmartShuffleState.SMART_SHUFFLE)
                Toast.makeText(this, "✨ Smart Shuffle On • Adding recommendations", Toast.LENGTH_SHORT).show()
                injectSmartShuffleRecommendations()
            }
            SmartShuffleState.SMART_SHUFFLE -> {
                smartShuffleState = SmartShuffleState.NORMAL
                appSettings.setShuffleMode(AppSettings.SHUFFLE_MODE_NORMAL)
                controller.shuffleModeEnabled = false
                removeInjectedSmartShuffleRecommendations()
                updateShuffleControls(SmartShuffleState.NORMAL)
                Toast.makeText(this, "Shuffle Off", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeInjectedSmartShuffleRecommendations() {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        val currentIndex = controller.currentMediaItemIndex
        for (i in (count - 1) downTo (currentIndex + 1)) {
            val item = controller.getMediaItemAt(i)
            val origin = SongMediaItemMapper.getQueueOrigin(item)
            if (origin == SongMediaItemMapper.ORIGIN_RECOMMENDED) {
                controller.removeMediaItem(i)
            }
        }
    }

    private fun injectSmartShuffleRecommendations() {
        val controller = mediaController ?: return
        val currentItem = controller.currentMediaItem ?: return
        val seedSong = SongMediaItemMapper.fromMediaItem(currentItem)
        val currentIndex = controller.currentMediaItemIndex
        val totalCount = controller.mediaItemCount

        lifecycleScope.launch {
            try {
                val currentQueueIds = (0 until totalCount).map { controller.getMediaItemAt(it).mediaId }.toSet()
                val baseUrl = appSettings.getBaseUrl()
                val recommendations = recommendationEngine.getRecommendations(
                    baseUrl = baseUrl,
                    seedSong = seedSong,
                    excludeSongIds = currentQueueIds,
                    limit = 5,
                    personalizationEnabled = appSettings.isPersonalizationEnabled(),
                    avoidRecentlyPlayed = appSettings.shouldAvoidRecentlyPlayed(),
                    diversityMode = appSettings.getRecommendationDiversity()
                )

                if (recommendations.isNotEmpty() && smartShuffleState == SmartShuffleState.SMART_SHUFFLE) {
                    var insertPos = currentIndex + 2
                    for (recSong in recommendations) {
                        val mediaItem = SongMediaItemMapper.toMediaItem(
                            song = recSong,
                            origin = SongMediaItemMapper.ORIGIN_RECOMMENDED,
                            contextName = "✨ For You",
                            isRecommended = true
                        )
                        val safeInsert = insertPos.coerceAtMost(controller.mediaItemCount)
                        controller.addMediaItem(safeInsert, mediaItem)
                        insertPos += 3
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateAutoplayDisplay() {
        val enabled = appSettings.isAutoplayEnabled()
        tvNowPlayingAutoplayStatus.text = if (enabled) "AUTOPLAY ON" else "AUTOPLAY OFF"
        tvNowPlayingAutoplayStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.textPrimary else R.color.textSecondary)
        )
        tvNowPlayingAutoplayStatus.contentDescription = if (enabled) "Autoplay is on, continuous playback active" else "Autoplay is off"
    }

    private fun triggerAutoplayCheck() {
        try {
            val bundle = android.os.Bundle()
            mediaController?.sendCustomCommand(
                androidx.media3.session.SessionCommand(PlaybackService.COMMAND_EXTEND_AUTOPLAY, bundle),
                bundle
            )
        } catch (_: Exception) {}
    }

    private fun playSongNow(song: Song) {
        val isDownloaded = playlistDbHelper.isSongDownloaded(song.id)
        val isOnline = com.braveplayer.music.util.NetworkUtils.isOnline(this)

        if (!isOnline && !isDownloaded) {
            Toast.makeText(
                this,
                "You're offline. Only downloaded tracks can be played without internet.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val effectiveSong = if (isDownloaded) {
            playlistDbHelper.getDownloadedSong(song.id) ?: song
        } else {
            song
        }

        val playbackUrl = effectiveSong.getBestPlaybackUrl()
        if (playbackUrl == null) {
            Toast.makeText(this, "Playback stream unavailable for '${effectiveSong.title}'", Toast.LENGTH_LONG).show()
            return
        }

        val mediaItem = SongMediaItemMapper.toMediaItem(
            song = effectiveSong,
            origin = SongMediaItemMapper.ORIGIN_CONTEXT,
            contextName = effectiveSong.album.ifEmpty { "Single" }
        )
        mediaController?.let { controller ->
            controller.stop()
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
            if (appSettings.isAutoplayEnabled()) {
                triggerAutoplayCheck()
            }
        }
    }

    private fun playNext(song: Song) {
        val isDownloaded = playlistDbHelper.isSongDownloaded(song.id)
        val isOnline = com.braveplayer.music.util.NetworkUtils.isOnline(this)

        if (!isOnline && !isDownloaded) {
            Toast.makeText(this, "You're offline. Only downloaded tracks can be queued offline.", Toast.LENGTH_SHORT).show()
            return
        }

        val effectiveSong = if (isDownloaded) {
            playlistDbHelper.getDownloadedSong(song.id) ?: song
        } else {
            song
        }

        val playbackUrl = effectiveSong.getBestPlaybackUrl()
        if (playbackUrl == null) {
            Toast.makeText(this, "Cannot queue '${effectiveSong.title}': No stream URL", Toast.LENGTH_SHORT).show()
            return
        }
        val mediaItem = SongMediaItemMapper.toMediaItem(
            song = effectiveSong,
            origin = SongMediaItemMapper.ORIGIN_MANUAL,
            contextName = "Queued"
        )
        mediaController?.let { controller ->
            val curr = controller.currentMediaItemIndex
            val insertPos = if (curr >= 0) curr + 1 else 0
            controller.addMediaItem(insertPos, mediaItem)
            if (controller.playbackState == Player.STATE_IDLE || controller.mediaItemCount == 1) {
                controller.prepare()
            }
            Toast.makeText(this, "Playing next: ${effectiveSong.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addSongToQueue(song: Song) {
        val isDownloaded = playlistDbHelper.isSongDownloaded(song.id)
        val isOnline = com.braveplayer.music.util.NetworkUtils.isOnline(this)

        if (!isOnline && !isDownloaded) {
            Toast.makeText(this, "You're offline. Only downloaded tracks can be queued offline.", Toast.LENGTH_SHORT).show()
            return
        }

        val effectiveSong = if (isDownloaded) {
            playlistDbHelper.getDownloadedSong(song.id) ?: song
        } else {
            song
        }

        val playbackUrl = effectiveSong.getBestPlaybackUrl()
        if (playbackUrl == null) {
            Toast.makeText(this, "Cannot queue '${effectiveSong.title}': No stream URL", Toast.LENGTH_SHORT).show()
            return
        }
        val mediaItem = SongMediaItemMapper.toMediaItem(
            song = effectiveSong,
            origin = SongMediaItemMapper.ORIGIN_MANUAL,
            contextName = "Queued"
        )
        mediaController?.let { controller ->
            val insertPos = findInsertPositionForQueue(controller)
            controller.addMediaItem(insertPos, mediaItem)
            if (controller.playbackState == Player.STATE_IDLE || controller.mediaItemCount == 1) {
                controller.prepare()
            }
            Toast.makeText(this, "Added to queue: ${effectiveSong.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findInsertPositionForQueue(controller: MediaController): Int {
        val total = controller.mediaItemCount
        val curr = controller.currentMediaItemIndex
        if (total == 0 || curr < 0) return total
        for (i in (curr + 1) until total) {
            val item = controller.getMediaItemAt(i)
            if (SongMediaItemMapper.getQueueOrigin(item) == SongMediaItemMapper.ORIGIN_AUTOPLAY) {
                return i
            }
        }
        return total
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
            val mediaItems = queue.mapIndexed { idx, s ->
                SongMediaItemMapper.toMediaItem(
                    song = s,
                    origin = if (idx == 0) SongMediaItemMapper.ORIGIN_CONTEXT else SongMediaItemMapper.ORIGIN_AUTOPLAY,
                    contextName = "${song.title} Radio"
                )
            }
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
        homeRecentCardAdapter.notifyDataSetChanged()
        trendingCardAdapter.notifyDataSetChanged()
        madeForYouCardAdapter.notifyDataSetChanged()
        mostPlayedCardAdapter.notifyDataSetChanged()
        refreshCollectionData()
    }

    private var liveSearchJob: Job? = null

    private fun performLiveSearch(query: String, genId: Long) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            showSearchState(SearchState.IDLE)
            return
        }

        lastQuery = trimmed
        showSearchState(SearchState.LOADING)
        val baseUrl = appSettings.getBaseUrl()

        liveSearchJob = lifecycleScope.launch {
            val result = metadataService.searchSongs(baseUrl, trimmed)
            if (genId != searchGenerationId) return@launch

            when (result) {
                is SearchResult.Success -> {
                    searchAdapter.submitList(result.songs)
                    showSearchState(SearchState.RESULTS)
                }
                is SearchResult.Empty -> {
                    tvEmptyResults.text = "No results found for \"$trimmed\""
                    showSearchState(SearchState.EMPTY)
                }
                is SearchResult.Error -> {
                    tvErrorMessage.text = result.message
                    showSearchState(SearchState.ERROR)
                }
            }
        }
    }

    private fun performSearch(query: String) {
        liveSearchJob?.cancel()
        val genId = ++searchGenerationId
        performLiveSearch(query, genId)
    }

    private var historicalPlaybackJob: Job? = null
    private var historicalRequestGenId: Long = 0L

    /**
     * Handles selection of a Search History item.
     * - If pure query: populates input and executes search.
     * - If song item: reconstructs the exact song via its stable ID and plays it,
     *   preventing stale list indices, query substitutions, or racing async requests from replacing the track.
     */
    private fun handleHistoricalItemClick(item: SearchHistoryItem) {
        val songId = item.songId?.trim()
        if (songId.isNullOrEmpty()) {
            // Pure search query entry: populate search box and search
            etSearchQuery.setText(item.queryText)
            etSearchQuery.setSelection(item.queryText.length)
            performSearch(item.queryText)
            return
        }

        // Cancel previous pending live search and previous historical playback resolutions to eliminate race conditions
        liveSearchJob?.cancel()
        historicalPlaybackJob?.cancel()
        val currentGenId = ++historicalRequestGenId

        // 1. Direct reconstruction of the historical song from stored metadata
        val historicalSong = item.toSong()

        // 2. If the historical record already has a valid stream URL, play it immediately
        if (historicalSong != null && historicalSong.getBestPlaybackUrl() != null) {
            playSongNow(historicalSong)
            return
        }

        // 3. Check locally cached tracks (downloaded, liked, history, playlist) by exact stable ID
        val cached = playlistDbHelper.findCachedSong(songId)
        if (cached != null && cached.id == songId && cached.getBestPlaybackUrl() != null) {
            playSongNow(cached)
            return
        }

        // 4. Incomplete metadata/stream: asynchronously resolve using stored stable ID
        Toast.makeText(this@MainActivity, "Loading '${item.title}' stream...", Toast.LENGTH_SHORT).show()
        historicalPlaybackJob = lifecycleScope.launch {
            val baseUrl = appSettings.getBaseUrl()
            val resolved = metadataService.fetchSongDetails(baseUrl, songId, item.title)

            // Guard against stale async responses replacing the currently selected song
            if (currentGenId != historicalRequestGenId) return@launch

            if (resolved != null && resolved.id == songId && resolved.getBestPlaybackUrl() != null) {
                // Ensure audio source, title, artist, and artwork strictly correspond to the same song
                playlistDbHelper.recordSongSearch(resolved)
                refreshRecentSearchesSection()
                playSongNow(resolved)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Playback stream unavailable for '${item.title}'",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private enum class SearchState { IDLE, LOADING, EMPTY, ERROR, RESULTS }

    private fun showSearchState(state: SearchState) {
        if (state == SearchState.IDLE) {
            layoutExploreDiscovery.visibility = View.VISIBLE
            layoutSearchResultsContainer.visibility = View.GONE
            pbSearchLoading.visibility = View.GONE
            tvEmptyResults.visibility = View.GONE
            layoutError.visibility = View.GONE
            rvSearchResults.visibility = View.GONE
        } else {
            layoutExploreDiscovery.visibility = View.GONE
            layoutSearchResultsContainer.visibility = View.VISIBLE
            pbSearchLoading.visibility = if (state == SearchState.LOADING) View.VISIBLE else View.GONE
            tvEmptyResults.visibility = if (state == SearchState.EMPTY) View.VISIBLE else View.GONE
            layoutError.visibility = if (state == SearchState.ERROR) View.VISIBLE else View.GONE
            rvSearchResults.visibility = if (state == SearchState.RESULTS) View.VISIBLE else View.GONE
        }
    }

    private fun refreshHomeData() {
        // 1. Personalized Greeting
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        tvHomeGreeting.text = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        tvHomeGreetingSub.text = "Discover and stream tailored to your day"

        // 2. Section 4: Recently Played Carousel
        val recent = playlistDbHelper.getRecentlyPlayedSongs(15)
        if (recent.isNotEmpty()) {
            layoutRecentEmpty.visibility = View.GONE
            rvRecentlyPlayedHome.visibility = View.VISIBLE
            homeRecentCardAdapter.submitData(recent)
            lastPlayedSong = recent.first()
            updateContinueListeningCard(lastPlayedSong)
        } else {
            layoutRecentEmpty.visibility = View.VISIBLE
            rvRecentlyPlayedHome.visibility = View.GONE
            updateContinueListeningCard(null)
        }

        // 3. Section 8: Most Played Carousel
        val mostPlayedPairs = playlistDbHelper.getMostPlayedSongs(15)
        if (mostPlayedPairs.isNotEmpty()) {
            layoutMostPlayedEmpty.visibility = View.GONE
            rvMostPlayedCarousel.visibility = View.VISIBLE
            val mostPlayedSongs = mostPlayedPairs.map { it.first }
            val counts = mostPlayedPairs.associate { it.first.id to it.second }
            mostPlayedCardAdapter.submitData(mostPlayedSongs, counts)
        } else {
            layoutMostPlayedEmpty.visibility = View.VISIBLE
            rvMostPlayedCarousel.visibility = View.GONE
        }

        // 4. Section 7: Daily Mixes Carousel
        val mixPreviews = listOf(
            PersonalizedMix("daily_mix_1", "Daily Mix 1", "Curated from your top listened tracks and favorite artists.", emptyList(), category = "DAILY MIX 1"),
            PersonalizedMix("daily_mix_2", "Daily Mix 2", "High-energy rhythm cluster with complementary artist selections.", emptyList(), category = "DAILY MIX 2"),
            PersonalizedMix("chill_mix", "Chill & Focus Mix", "Calm acoustic, downtempo, and flow state soundscapes.", emptyList(), category = "CHILL & FOCUS"),
            PersonalizedMix("discovery_mix", "Discovery Mix", "Novel artists and underground discoveries outside your rotation.", emptyList(), category = "DISCOVERY")
        )
        dailyMixCardAdapter.submitMixes(mixPreviews)

        // 5. Section 11: Recommended Albums & Playlists
        val userPlaylists = playlistDbHelper.getAllPlaylists()
        val playlistItems = mutableListOf<HomeCollectionItem>()
        for (p in userPlaylists) {
            playlistItems.add(
                HomeCollectionItem(
                    id = "user_${p.id}",
                    title = p.name,
                    subtitle = "Playlist • ${p.songCount} tracks"
                )
            )
        }
        playlistItems.add(HomeCollectionItem("curated_1", "Today's Top Hits", "The hottest global streaming tracks", "https://c.saavncdn.com/editorial/Today_sTopHits_English_20240101.jpg"))
        playlistItems.add(HomeCollectionItem("curated_2", "Acoustic Morning", "Unplugged sessions and gentle mornings", "https://c.saavncdn.com/editorial/AcousticMorning_20230501.jpg"))
        playlistItems.add(HomeCollectionItem("curated_3", "Deep Focus & Study", "Ambient lo-fi and electronic flow", "https://c.saavncdn.com/editorial/DeepFocus_20230501.jpg"))
        playlistItems.add(HomeCollectionItem("curated_4", "Party Dance Hits", "Uplifting festival and club bangers", "https://c.saavncdn.com/editorial/PartyDance_20230501.jpg"))
        curatedPlaylistCardAdapter.submitItems(playlistItems)

        // 6. Section 5: Trending Songs (Real Backend Data)
        val baseUrl = appSettings.getBaseUrl()
        pbTrendingLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val trendingSongs = try {
                recommendationEngine.fetchCandidateSongs(baseUrl, "Top Trending Hits")
                    .ifEmpty { recommendationEngine.fetchCandidateSongs(baseUrl, "Trending") }
            } catch (_: Exception) {
                emptyList()
            }
            pbTrendingLoading.visibility = View.GONE
            if (trendingSongs.isNotEmpty()) {
                trendingCardAdapter.submitData(trendingSongs)
            }
        }

        // 7. Section 6: Made for You (Personalized Recommendations)
        pbMadeForYouLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val seed = recent.firstOrNull() ?: playlistDbHelper.getAllLikedSongs().firstOrNull()
            val recommendations = if (seed != null) {
                try {
                    recommendationEngine.getRecommendations(baseUrl, seed, limit = 15)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                try {
                    recommendationEngine.fetchCandidateSongs(baseUrl, "Popular Hits")
                } catch (_: Exception) {
                    emptyList()
                }
            }
            pbMadeForYouLoading.visibility = View.GONE
            if (recommendations.isNotEmpty()) {
                madeForYouCardAdapter.submitData(recommendations)
            }
        }
    }

    private fun updateContinueListeningCard(song: Song?) {
        if (song != null) {
            tvContinueTitle.text = song.title
            tvContinueArtist.text = if (song.artists.isNotBlank()) song.artists else song.album
            loadArtwork(song.imageUrl, ivContinueCover)
        } else {
            tvContinueTitle.text = "Ready to listen"
            tvContinueArtist.text = "Discover mixes, trending hits, and genres in Melody"
            ivContinueCover.setImageResource(R.drawable.ic_music_note)
        }
    }

    private fun resumeLastPlayback() {
        val song = lastPlayedSong ?: playlistDbHelper.getRecentlyPlayedSongs(1).firstOrNull()
        if (song != null) {
            playSongNow(song)
        } else {
            generateAndPlayMix("Daily Mix 1")
        }
    }

    private fun playMixDirectly(mix: PersonalizedMix, shuffle: Boolean = false) {
        val baseUrl = appSettings.getBaseUrl()
        Toast.makeText(this, "Loading ${mix.title}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val fullMix = try {
                when (mix.title) {
                    "Daily Mix 2" -> mixGenerator.generateDailyMix2(baseUrl)
                    "Chill & Focus Mix" -> mixGenerator.generateChillMix(baseUrl)
                    "Discovery Mix" -> mixGenerator.generateDiscoveryMix(baseUrl)
                    else -> mixGenerator.generateDailyMix(baseUrl)
                }
            } catch (e: Exception) {
                mix
            }

            if (fullMix.songs.isEmpty()) {
                Toast.makeText(this@MainActivity, "Could not load ${mix.title}. Check network.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val songsToPlay = if (shuffle) fullMix.songs.shuffled() else fullMix.songs
            val mediaItems = songsToPlay.map {
                SongMediaItemMapper.toMediaItem(
                    song = it,
                    origin = SongMediaItemMapper.ORIGIN_CONTEXT,
                    contextName = fullMix.title
                )
            }
            mediaController?.let { c ->
                c.stop()
                c.setMediaItems(mediaItems)
                c.prepare()
                c.play()
                Toast.makeText(this@MainActivity, "Playing ${fullMix.title} (${fullMix.songs.size} tracks)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openCuratedCollection(item: HomeCollectionItem) {
        if (item.id.startsWith("user_")) {
            val playlistId = item.id.removePrefix("user_").toLongOrNull()
            val playlist = playlistDbHelper.getAllPlaylists().firstOrNull { it.id == playlistId }
            if (playlist != null) {
                showPlaylistDetailDialog(playlist)
                return
            }
        }
        openCategorySheet(
            title = item.title,
            subtitle = item.subtitle,
            query = item.title
        )
    }

    private fun playCuratedCollection(item: HomeCollectionItem) {
        val baseUrl = appSettings.getBaseUrl()
        Toast.makeText(this, "Loading ${item.title}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val songs = if (item.id.startsWith("user_")) {
                val playlistId = item.id.removePrefix("user_").toLongOrNull() ?: 0L
                playlistDbHelper.getSongsInPlaylist(playlistId)
            } else {
                recommendationEngine.fetchCandidateSongs(baseUrl, item.title)
            }

            if (songs.isEmpty()) {
                Toast.makeText(this@MainActivity, "No tracks available in ${item.title}", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val mediaItems = songs.map {
                SongMediaItemMapper.toMediaItem(
                    song = it,
                    origin = SongMediaItemMapper.ORIGIN_CONTEXT,
                    contextName = item.title
                )
            }
            mediaController?.let { c ->
                c.stop()
                c.setMediaItems(mediaItems)
                c.prepare()
                c.play()
                Toast.makeText(this@MainActivity, "Playing ${item.title}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshCollectionData() {
        val likedCount = playlistDbHelper.getAllLikedSongs().size
        tvCollectionLikedCount.text = "$likedCount tracks"
        cardCollectionLiked.contentDescription = "Liked songs collection, $likedCount tracks"

        val historyCount = playlistDbHelper.getRecentlyPlayedSongs(100).size
        tvCollectionHistoryCount.text = "$historyCount recent plays"
        cardCollectionHistory.contentDescription = "Listening history, $historyCount recent plays"

        val downloadedCount = playlistDbHelper.getAllDownloadedSongs().size
        tvCollectionDownloadsCount.text = "$downloadedCount offline tracks"
        cardCollectionDownloads.contentDescription = "Downloaded offline music, $downloadedCount tracks"

        val playlists = playlistDbHelper.getAllPlaylists()
        collectionPlaylistAdapter.submitList(playlists)
        layoutPlaylistsEmpty?.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        rvPlaylistsCollection.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openMixDetail(mixType: String) {
        val baseUrl = appSettings.getBaseUrl()
        Toast.makeText(this, "Loading $mixType...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val mix = try {
                when (mixType) {
                    "Daily Mix 2" -> mixGenerator.generateDailyMix2(baseUrl)
                    "Chill & Focus Mix" -> mixGenerator.generateChillMix(baseUrl)
                    "Discovery Mix" -> mixGenerator.generateDiscoveryMix(baseUrl)
                    else -> mixGenerator.generateDailyMix(baseUrl)
                }
            } catch (e: Exception) {
                PersonalizedMix(mixType.lowercase(), mixType, "Error loading mix: ${e.message}", emptyList())
            }

            if (mix.songs.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(mix.title)
                    .setMessage("Could not generate ${mix.title} right now. Listen to more music or perform a search first!")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }
            showMixDetailDialog(mix)
        }
    }

    private fun showMixDetailDialog(mix: PersonalizedMix) {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_mix_details, null)
        dialog.setContentView(sheetView)

        val ivClose: ImageView = sheetView.findViewById(R.id.ivCloseMixDetails)
        val tvBadge: TextView = sheetView.findViewById(R.id.tvMixBadgeHeader)
        val tvTitle: TextView = sheetView.findViewById(R.id.tvMixDetailTitle)
        val tvSubtitle: TextView = sheetView.findViewById(R.id.tvMixDetailSubtitle)
        val tvDesc: TextView = sheetView.findViewById(R.id.tvMixDetailDescription)
        val tvCount: TextView = sheetView.findViewById(R.id.tvMixDetailTrackCount)
        val btnPlay: Button = sheetView.findViewById(R.id.btnPlayMixAction)
        val btnShuffle: Button = sheetView.findViewById(R.id.btnShuffleMixAction)
        val btnSave: Button = sheetView.findViewById(R.id.btnSaveMixAction)
        val rvTracks: RecyclerView = sheetView.findViewById(R.id.rvMixTracksList)

        tvBadge.text = mix.category.ifEmpty { "PERSONALIZED MIX" }
        tvTitle.text = mix.title
        tvSubtitle.text = mix.subtitle.ifEmpty { "Curated for you" }
        tvDesc.text = mix.description
        tvCount.text = "${mix.songs.size} tracks"

        ivClose.setOnClickListener { dialog.dismiss() }

        fun startPlayback(songs: List<Song>, startIndex: Int = 0) {
            val mediaItems = songs.map {
                SongMediaItemMapper.toMediaItem(
                    song = it,
                    origin = SongMediaItemMapper.ORIGIN_CONTEXT,
                    contextName = mix.title
                )
            }
            mediaController?.let { c ->
                c.stop()
                c.setMediaItems(mediaItems, startIndex, 0L)
                c.prepare()
                c.play()
                Toast.makeText(this@MainActivity, "Playing ${mix.title}", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        btnPlay.setOnClickListener {
            startPlayback(mix.songs, 0)
        }

        btnShuffle.setOnClickListener {
            val shuffled = mix.songs.shuffled()
            startPlayback(shuffled, 0)
        }

        btnSave.setOnClickListener {
            val id = mixGenerator.saveMixAsPlaylist(mix)
            if (id != -1L) {
                refreshCollectionData()
                Toast.makeText(this@MainActivity, "Saved '${mix.title}' to your playlists!", Toast.LENGTH_SHORT).show()
                btnSave.text = "✓ Saved"
                btnSave.isEnabled = false
            } else {
                Toast.makeText(this@MainActivity, "Could not save playlist", Toast.LENGTH_SHORT).show()
            }
        }

        val mixSongAdapter = SongAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongSelected = { song ->
                val idx = mix.songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                startPlayback(mix.songs, idx)
            },
            onSongAddToQueue = { song -> addSongToQueue(song) },
            onSongOptions = { song -> showSongOptionDialog(song) },
            onSongLikeToggled = { song -> toggleLikeSong(song) },
            isSongLiked = { songId -> playlistDbHelper.isSongLiked(songId) }
        )
        rvTracks.layoutManager = LinearLayoutManager(this)
        rvTracks.adapter = mixSongAdapter
        mixSongAdapter.submitList(mix.songs)

        dialog.show()
    }

    private fun generateAndPlayMix(mixType: String) {
        val baseUrl = appSettings.getBaseUrl()
        Toast.makeText(this, "Generating $mixType...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val mix: PersonalizedMix = try {
                when (mixType) {
                    "Daily Mix 2" -> mixGenerator.generateDailyMix2(baseUrl)
                    "Chill & Focus Mix" -> mixGenerator.generateChillMix(baseUrl)
                    "Discovery Mix" -> mixGenerator.generateDiscoveryMix(baseUrl)
                    else -> mixGenerator.generateDailyMix(baseUrl)
                }
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

            val mediaItems = mix.songs.map {
                SongMediaItemMapper.toMediaItem(
                    song = it,
                    origin = SongMediaItemMapper.ORIGIN_CONTEXT,
                    contextName = mix.title
                )
            }
            mediaController?.let { c ->
                c.stop()
                c.setMediaItems(mediaItems)
                c.prepare()
                c.play()
                Toast.makeText(this@MainActivity, "Playing ${mix.title} (${mix.songs.size} tracks)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshSearchRecommendations() {
        refreshExploreData()
    }

    private fun refreshExploreData() {
        refreshRecentSearchesSection()
        loadTrendingExplore()
    }

    private fun refreshRecentSearchesSection() {
        val recentSearches = playlistDbHelper.getRecentSearches(10)
        if (recentSearches.isNotEmpty()) {
            layoutRecentSearchesSection.visibility = View.VISIBLE
            recentSearchAdapter.submitList(recentSearches)
        } else {
            layoutRecentSearchesSection.visibility = View.GONE
        }
    }

    private var cachedTrendingSongs: List<Song> = emptyList()

    private fun loadTrendingExplore() {
        if (cachedTrendingSongs.isNotEmpty()) {
            trendingExploreAdapter.submitData(cachedTrendingSongs.take(8))
            pbTrendingExploreLoading.visibility = View.GONE
            return
        }

        pbTrendingExploreLoading.visibility = View.VISIBLE
        val baseUrl = appSettings.getBaseUrl()
        lifecycleScope.launch {
            val songs = try {
                recommendationEngine.fetchCandidateSongs(baseUrl, "Top Trending Hits")
                    .ifEmpty { recommendationEngine.fetchCandidateSongs(baseUrl, "Trending") }
            } catch (_: Exception) {
                emptyList()
            }
            pbTrendingExploreLoading.visibility = View.GONE
            if (songs.isNotEmpty()) {
                cachedTrendingSongs = songs
                trendingExploreAdapter.submitData(songs.take(8))
            }
        }
    }

    private fun showFullTrendingDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_queue, null)
        dialog.setContentView(view)

        val tvTrackCount = view.findViewById<TextView>(R.id.tvQueueTrackCount)
        val btnClear = view.findViewById<Button>(R.id.btnClearQueue)
        val rv = view.findViewById<RecyclerView>(R.id.rvQueueList)
        val ivClose = view.findViewById<ImageView>(R.id.ivCloseQueue)
        val layoutNowPlaying = view.findViewById<View>(R.id.layoutQueueNowPlayingCard)

        layoutNowPlaying?.visibility = View.GONE
        tvTrackCount?.text = "${cachedTrendingSongs.size} tracks • Trending Charts"
        btnClear?.visibility = View.GONE
        ivClose?.setOnClickListener { dialog.dismiss() }

        val adapter = TrendingRowAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongClicked = { song ->
                playlistDbHelper.recordSongSearch(song)
                playSongNow(song)
                dialog.dismiss()
            },
            onPlayClicked = { song ->
                playlistDbHelper.recordSongSearch(song)
                playSongNow(song)
                dialog.dismiss()
            },
            onOptionClicked = { song -> showSongOptionDialog(song) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        adapter.submitData(cachedTrendingSongs)

        dialog.show()
    }

    private fun openCategoryChannel(category: GenreCategory) {
        etSearchQuery.setText(category.query)
        etSearchQuery.setSelection(category.query.length)
        Toast.makeText(this, "Opening ${category.name} channel", Toast.LENGTH_SHORT).show()
    }

    private fun showSongCollectionSheet(
        title: String,
        subtitle: String,
        songs: List<Song>,
        isDownloaded: Boolean = false,
        isHistory: Boolean = false,
        playlist: Playlist? = null,
        onClearAction: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_song_collection, null)
        dialog.setContentView(view)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val ivClose: ImageView = view.findViewById(R.id.ivCloseCollectionSheet)
        val tvTopTitle: TextView? = view.findViewById(R.id.tvCollectionSheetTopTitle)
        val tvTitle: TextView = view.findViewById(R.id.tvCollectionSheetTitle)
        val tvSub: TextView = view.findViewById(R.id.tvCollectionSheetSubtitle)
        val ivHeroArt: ImageView? = view.findViewById(R.id.ivCollectionHeroArt)
        val ivRename: ImageView? = view.findViewById(R.id.ivCollectionSheetRename)
        val ivAction: ImageView = view.findViewById(R.id.ivCollectionSheetAction)
        val btnPlayAll: Button = view.findViewById(R.id.btnCollectionPlayAll)
        val btnShuffle: Button = view.findViewById(R.id.btnCollectionShuffle)
        val btnAddTracks: Button? = view.findViewById(R.id.btnCollectionAddTracks)
        val rvSongs: RecyclerView = view.findViewById(R.id.rvCollectionSongs)
        val layoutEmpty: View = view.findViewById(R.id.layoutCollectionEmpty)
        val tvEmptyTitle: TextView = view.findViewById(R.id.tvCollectionEmptyTitle)
        val tvEmptyMsg: TextView = view.findViewById(R.id.tvCollectionEmptyMsg)
        val btnEmptyAction: Button? = view.findViewById(R.id.btnCollectionEmptyAction)

        ivClose.setOnClickListener { dialog.dismiss() }
        tvTitle.text = title
        tvTopTitle?.text = title
        tvSub.text = if (songs.isNotEmpty()) "${songs.size} tracks • $subtitle" else subtitle

        // Hero Artwork Setup
        if (ivHeroArt != null) {
            when {
                isHistory -> {
                    ivHeroArt.setImageResource(R.drawable.ic_queue_music)
                    ivHeroArt.setColorFilter(ContextCompat.getColor(this, R.color.accentBlue))
                }
                isDownloaded -> {
                    ivHeroArt.setImageResource(R.drawable.ic_download)
                    ivHeroArt.setColorFilter(ContextCompat.getColor(this, R.color.accentViolet))
                }
                playlist != null -> {
                    if (songs.isNotEmpty() && !songs[0].imageUrl.isNullOrBlank()) {
                        ivHeroArt.clearColorFilter()
                        ivHeroArt.setPadding(0, 0, 0, 0)
                        loadArtwork(songs[0].imageUrl, ivHeroArt)
                    } else {
                        ivHeroArt.setImageResource(R.drawable.ic_library_music)
                        ivHeroArt.setColorFilter(ContextCompat.getColor(this, R.color.accentPrimary))
                    }
                }
                else -> {
                    // Liked Songs
                    ivHeroArt.setImageResource(R.drawable.ic_heart_filled)
                    ivHeroArt.setColorFilter(ContextCompat.getColor(this, R.color.accentPrimary))
                }
            }
        }

        // Rename Action
        if (playlist != null && ivRename != null) {
            ivRename.visibility = View.VISIBLE
            ivRename.setOnClickListener {
                showRenamePlaylistDialog(playlist) { newName ->
                    tvTitle.text = newName
                    tvTopTitle?.text = newName
                }
            }
        } else {
            ivRename?.visibility = View.GONE
        }

        // Delete / Clear Action
        if (onClearAction != null) {
            ivAction.visibility = View.VISIBLE
            ivAction.setImageResource(R.drawable.ic_delete)
            ivAction.contentDescription = "Clear History"
            ivAction.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear History")
                    .setMessage("Are you sure you want to clear your listening history?")
                    .setPositiveButton("Clear") { _, _ ->
                        onClearAction.invoke()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else if (playlist != null) {
            ivAction.visibility = View.VISIBLE
            ivAction.setImageResource(R.drawable.ic_delete)
            ivAction.contentDescription = "Delete Playlist"
            ivAction.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete Playlist")
                    .setMessage("Are you sure you want to delete '${playlist.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        playlistDbHelper.deletePlaylist(playlist.id)
                        refreshCollectionData()
                        dialog.dismiss()
                        Toast.makeText(this, "Playlist '${playlist.name}' deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            ivAction.visibility = View.GONE
        }

        // Add Tracks Action
        if (playlist != null && btnAddTracks != null) {
            btnAddTracks.visibility = View.VISIBLE
            btnAddTracks.setOnClickListener {
                dialog.dismiss()
                switchTab(Tab.SEARCH)
                bottomNavigation.selectedItemId = R.id.nav_search
                Toast.makeText(this, "Search songs and tap ⋮ to add to '${playlist.name}'", Toast.LENGTH_SHORT).show()
            }
        } else {
            btnAddTracks?.visibility = View.GONE
        }

        btnPlayAll.setOnClickListener {
            if (songs.isNotEmpty()) {
                val isOnline = com.braveplayer.music.util.NetworkUtils.isOnline(this)
                val playableSongs = if (!isOnline && !isDownloaded) {
                    songs.mapNotNull { playlistDbHelper.getDownloadedSong(it.id) }
                } else {
                    songs.map { s -> if (playlistDbHelper.isSongDownloaded(s.id)) playlistDbHelper.getDownloadedSong(s.id) ?: s else s }
                }

                if (playableSongs.isEmpty()) {
                    Toast.makeText(this, "You're offline. Only downloaded tracks can play offline.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val mediaItems = playableSongs.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.shuffleModeEnabled = false
                    c.prepare()
                    c.play()
                }
                dialog.dismiss()
            }
        }

        btnShuffle.setOnClickListener {
            if (songs.isNotEmpty()) {
                val isOnline = com.braveplayer.music.util.NetworkUtils.isOnline(this)
                val playableSongs = if (!isOnline && !isDownloaded) {
                    songs.mapNotNull { playlistDbHelper.getDownloadedSong(it.id) }
                } else {
                    songs.map { s -> if (playlistDbHelper.isSongDownloaded(s.id)) playlistDbHelper.getDownloadedSong(s.id) ?: s else s }
                }

                if (playableSongs.isEmpty()) {
                    Toast.makeText(this, "You're offline. Only downloaded tracks can play offline.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val mediaItems = playableSongs.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.shuffleModeEnabled = true
                    c.prepare()
                    c.play()
                }
                dialog.dismiss()
            }
        }

        if (songs.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvSongs.visibility = View.GONE
            btnPlayAll.alpha = 0.5f
            btnPlayAll.isEnabled = false
            btnShuffle.alpha = 0.5f
            btnShuffle.isEnabled = false
            when {
                isHistory -> {
                    tvEmptyTitle.text = "No history yet"
                    tvEmptyMsg.text = "Songs you stream will appear here."
                }
                isDownloaded -> {
                    tvEmptyTitle.text = "No downloaded tracks"
                    tvEmptyMsg.text = "Tap the download button on any track to listen offline."
                }
                playlist != null -> {
                    tvEmptyTitle.text = "Playlist is empty"
                    tvEmptyMsg.text = "Add tracks to '${playlist.name}' from any song's options menu."
                }
                else -> {
                    tvEmptyTitle.text = "No liked tracks"
                    tvEmptyMsg.text = "Tap the heart icon on any song to save it here."
                }
            }
            if (btnEmptyAction != null) {
                btnEmptyAction.visibility = View.VISIBLE
                btnEmptyAction.text = if (playlist != null) "+ Add Songs" else "Explore Music"
                btnEmptyAction.setOnClickListener {
                    dialog.dismiss()
                    switchTab(Tab.SEARCH)
                    bottomNavigation.selectedItemId = R.id.nav_search
                }
            }
        } else {
            layoutEmpty.visibility = View.GONE
            rvSongs.visibility = View.VISIBLE
            rvSongs.layoutManager = LinearLayoutManager(this)

            val adapter = SongAdapter(
                scope = lifecycleScope,
                httpClient = metadataService.httpClient,
                onSongSelected = { song ->
                    val isOnline = com.braveplayer.music.util.NetworkUtils.isOnline(this@MainActivity)
                    val playableSongs = if (!isOnline && !isDownloaded) {
                        songs.mapNotNull { playlistDbHelper.getDownloadedSong(it.id) }
                    } else {
                        songs.map { s -> if (playlistDbHelper.isSongDownloaded(s.id)) playlistDbHelper.getDownloadedSong(s.id) ?: s else s }
                    }
                    val startIndex = playableSongs.indexOfFirst { it.id == song.id }
                    if (startIndex >= 0) {
                        val mediaItems = playableSongs.map { SongMediaItemMapper.toMediaItem(it) }
                        mediaController?.let { c ->
                            c.stop()
                            c.setMediaItems(mediaItems, startIndex, 0L)
                            c.prepare()
                            c.play()
                        }
                    } else {
                        playSongNow(song)
                    }
                },
                onSongAddToQueue = { song ->
                    addSongToQueue(song)
                },
                onSongOptions = { song ->
                    showSongOptionDialog(song, fromPlaylist = playlist)
                },
                onSongLikeToggled = { song ->
                    toggleLikeSong(song)
                },
                isSongLiked = { id ->
                    playlistDbHelper.isSongLiked(id)
                }
            )
            rvSongs.adapter = adapter
            adapter.submitList(songs)
        }

        dialog.show()
    }

    private fun showRenamePlaylistDialog(playlist: Playlist, onRenamed: (String) -> Unit) {
        val dialog = Dialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_create_playlist, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvTitle = view.findViewById<TextView>(R.id.tvCreatePlaylistTitle)
        val tvSub = view.findViewById<TextView>(R.id.tvCreatePlaylistSubtitle)
        val etName = view.findViewById<EditText>(R.id.etPlaylistNameInput)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelCreatePlaylist)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmCreatePlaylist)

        tvTitle?.text = "Rename Playlist"
        tvSub?.text = "Enter a new name for '${playlist.name}'"
        etName.setText(playlist.name)
        etName.setSelection(playlist.name.length)
        btnConfirm.text = "Save"

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                playlistDbHelper.renamePlaylist(playlist.id, newName)
                refreshCollectionData()
                onRenamed(newName)
                dialog.dismiss()
                Toast.makeText(this, "Playlist renamed to '$newName'", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showLikedSongsDialog() {
        val liked = playlistDbHelper.getAllLikedSongs()
        showSongCollectionSheet(
            title = "Liked Songs",
            subtitle = "Saved to your library",
            songs = liked
        )
    }

    private fun showQueueDialog() {
        val controller = mediaController ?: run {
            Toast.makeText(this, "Player not connected", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_queue, null)
        dialog.setContentView(view)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val ivClose: ImageView = view.findViewById(R.id.ivCloseQueue)
        val tvQueueCount: TextView = view.findViewById(R.id.tvQueueTrackCount)
        val btnClear: Button = view.findViewById(R.id.btnClearQueue)
        val btnQueueSettings: ImageView? = view.findViewById(R.id.btnQueueSettings)
        val cardNowPlaying: LinearLayout = view.findViewById(R.id.layoutQueueNowPlayingCard)
        val ivNowPlayingThumb: ImageView = view.findViewById(R.id.ivNowPlayingQueueThumb)
        val tvNowPlayingTitle: TextView = view.findViewById(R.id.tvNowPlayingQueueTitle)
        val tvNowPlayingArtist: TextView = view.findViewById(R.id.tvNowPlayingQueueArtist)
        val btnShuffle: Button = view.findViewById(R.id.btnShuffleQueue)
        val rvQueue: RecyclerView = view.findViewById(R.id.rvQueueList)
        val layoutEmpty: LinearLayout = view.findViewById(R.id.layoutQueueEmpty)

        ivClose.setOnClickListener { dialog.dismiss() }
        btnQueueSettings?.setOnClickListener { showPlaybackSettingsDialog() }
        tvQueueCount.setOnClickListener { showPlaybackSettingsDialog() }

        var isDragging = false
        var dragFromPosition = -1
        var dragToPosition = -1
        var itemTouchHelper: ItemTouchHelper? = null

        val queueAdapter = QueueAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onItemClicked = { index ->
                if (index in 0 until controller.mediaItemCount) {
                    controller.seekToDefaultPosition(index)
                    controller.play()
                }
            },
            onItemRemoved = { index ->
                if (index in 0 until controller.mediaItemCount) {
                    controller.removeMediaItem(index)
                }
            },
            onItemMoved = { _, _ -> },
            onStartDrag = { viewHolder ->
                itemTouchHelper?.startDrag(viewHolder)
            }
        )

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val pos = viewHolder.bindingAdapterPosition
                if (!queueAdapter.isTrackPosition(pos)) {
                    return makeMovementFlags(0, 0)
                }
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION &&
                    queueAdapter.isTrackPosition(fromPos) && queueAdapter.isTrackPosition(toPos)) {
                    if (dragFromPosition == -1) {
                        dragFromPosition = fromPos
                    }
                    dragToPosition = toPos
                    return queueAdapter.onItemMove(fromPos, toPos)
                }
                return false
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragging = true
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                isDragging = false
                val from = dragFromPosition
                val to = dragToPosition
                dragFromPosition = -1
                dragToPosition = -1
                if (from != -1 && to != -1 && from != to) {
                    if (from in 0 until controller.mediaItemCount && to in 0 until controller.mediaItemCount) {
                        controller.moveMediaItem(from, to)
                    }
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true
        })

        rvQueue.layoutManager = LinearLayoutManager(this)
        rvQueue.adapter = queueAdapter
        itemTouchHelper.attachToRecyclerView(rvQueue)

        fun updateQueueData() {
            val totalCount = controller.mediaItemCount
            val currentIndex = controller.currentMediaItemIndex

            val autoplayText = if (appSettings.isAutoplayEnabled()) "Autoplay On" else "Autoplay Off"
            tvQueueCount.text = "$totalCount tracks • $autoplayText"

            if (totalCount == 0) {
                layoutEmpty.visibility = View.VISIBLE
                rvQueue.visibility = View.GONE
                cardNowPlaying.visibility = View.GONE
            } else {
                layoutEmpty.visibility = View.GONE
                rvQueue.visibility = View.VISIBLE
                cardNowPlaying.visibility = View.VISIBLE

                val currentItem = controller.currentMediaItem
                if (currentItem != null) {
                    tvNowPlayingTitle.text = currentItem.mediaMetadata.title?.toString() ?: "Unknown Track"
                    tvNowPlayingArtist.text = currentItem.mediaMetadata.artist?.toString() ?: "Unknown Artist"
                    val imageUrl = currentItem.mediaMetadata.extras?.getString(SongMediaItemMapper.EXTRA_IMAGE_URL)
                        ?: currentItem.mediaMetadata.artworkUri?.toString()
                    loadArtwork(imageUrl, ivNowPlayingThumb)
                } else {
                    cardNowPlaying.visibility = View.GONE
                }

                val allItems = List(totalCount) { i -> controller.getMediaItemAt(i) }
                queueAdapter.submitQueue(allItems, currentIndex)
            }

            val shuffleText = when (smartShuffleState) {
                SmartShuffleState.SMART_SHUFFLE -> "✨ Smart Shuffle"
                SmartShuffleState.SHUFFLE -> "🔀 Shuffle ON"
                SmartShuffleState.NORMAL -> "🔀 Shuffle"
            }
            val shuffleColor = when (smartShuffleState) {
                SmartShuffleState.SMART_SHUFFLE -> R.color.ytm_red
                SmartShuffleState.SHUFFLE -> R.color.ytm_text_primary
                SmartShuffleState.NORMAL -> R.color.ytm_text_secondary
            }
            btnShuffle.text = shuffleText
            btnShuffle.setTextColor(ContextCompat.getColor(this, shuffleColor))
        }

        btnShuffle.setOnClickListener {
            toggleShuffle()
            updateQueueData()
        }

        cardNowPlaying.setOnClickListener {
            val curr = controller.currentMediaItemIndex
            if (curr in 0 until controller.mediaItemCount) {
                rvQueue.smoothScrollToPosition(curr)
            }
        }

        btnClear.setOnClickListener {
            val total = controller.mediaItemCount
            if (total == 0) return@setOnClickListener
            val currentIdx = controller.currentMediaItemIndex

            if (currentIdx in 0 until total - 1) {
                AlertDialog.Builder(this)
                    .setTitle("Clear Queue")
                    .setMessage("Would you like to clear upcoming tracks or the entire queue?")
                    .setPositiveButton("Clear Upcoming") { _, _ ->
                        controller.removeMediaItems(currentIdx + 1, total)
                        updateQueueData()
                        Toast.makeText(this, "Upcoming queue cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Clear All") { _, _ ->
                        controller.clearMediaItems()
                        updateQueueData()
                        Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                controller.clearMediaItems()
                updateQueueData()
                Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
            }
        }

        val queueListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isDragging) {
                    updateQueueData()
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (!isDragging) {
                    updateQueueData()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateQueueData()
            }
        }

        controller.addListener(queueListener)
        dialog.setOnDismissListener {
            controller.removeListener(queueListener)
        }

        updateQueueData()
        dialog.show()
    }

    private fun showHistoryDialog() {
        val recent = playlistDbHelper.getRecentlyPlayedSongs(50)
        showSongCollectionSheet(
            title = "Listening History",
            subtitle = "Recently played",
            songs = recent,
            isHistory = true,
            onClearAction = {
                playlistDbHelper.clearHistory()
                refreshCollectionData()
                Toast.makeText(this@MainActivity, "Listening history cleared", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showCreatePlaylistDialog(songToAdd: Song? = null) {
        val dialog = Dialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_create_playlist, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etName = view.findViewById<EditText>(R.id.etPlaylistNameInput)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelCreatePlaylist)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmCreatePlaylist)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isNotEmpty()) {
                val id = playlistDbHelper.createPlaylist(name)
                if (id != -1L) {
                    if (songToAdd != null) {
                        playlistDbHelper.addSongToPlaylist(id, songToAdd)
                        Toast.makeText(this, "Created '$name' and added track", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Playlist '$name' created", Toast.LENGTH_SHORT).show()
                    }
                    refreshCollectionData()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Playlist name may already exist", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Please enter a playlist title", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showPlaylistDetailDialog(playlist: Playlist) {
        val songs = playlistDbHelper.getSongsInPlaylist(playlist.id)
        showSongCollectionSheet(
            title = playlist.name,
            subtitle = "Your Playlist",
            songs = songs,
            playlist = playlist
        )
    }

    private fun showSongOptionDialog(song: Song, fromPlaylist: Playlist? = null) {
        val isLiked = playlistDbHelper.isSongLiked(song.id)
        val likeText = if (isLiked) "Unlike Track" else "Like Track ♥"
        val isDownloaded = playlistDbHelper.isSongDownloaded(song.id)
        val downloadText = if (isDownloaded) "Downloaded ✓" else "⬇ Download Track"
        val isHidden = playlistDbHelper.isSongHidden(song.id)
        val hideText = if (isHidden) "Unhide Track" else "🚫 Hide This Song"

        val options = mutableListOf<Pair<String, () -> Unit>>()
        options.add("▶ Play Now" to { playSongNow(song) })
        options.add("⏭ Play Next" to { playNext(song) })
        options.add("➕ Add to Queue" to { addSongToQueue(song) })
        options.add("✨ More Like This (Radio)" to { startRadioForSong(song) })
        options.add("📜 View Lyrics" to { showLyricsDialog(song) })
        options.add(downloadText to {
            songDownloadHelper.downloadSong(song) {
                refreshCollectionData()
            }
        })
        options.add(likeText to { toggleLikeSong(song) })
        options.add("👎 Not Interested in this track" to {
            playlistDbHelper.recordNotInterested(song)
            Toast.makeText(this, "Deprioritized. We won't recommend this track often.", Toast.LENGTH_SHORT).show()
            val controller = mediaController
            if (controller?.currentMediaItem?.mediaId == song.id) {
                controller.seekToNextMediaItem()
            }
        })
        options.add(hideText to {
            if (isHidden) {
                playlistDbHelper.unhideSong(song.id)
                Toast.makeText(this, "Track unhidden", Toast.LENGTH_SHORT).show()
            } else {
                playlistDbHelper.hideSong(song)
                Toast.makeText(this, "Track hidden from autoplay & recommendations", Toast.LENGTH_SHORT).show()
                val controller = mediaController
                if (controller?.currentMediaItem?.mediaId == song.id) {
                    controller.seekToNextMediaItem()
                }
            }
        })
        options.add("📁 Add to Playlist..." to { showAddToPlaylistDialog(song) })
        if (fromPlaylist != null) {
            options.add("❌ Remove from '${fromPlaylist.name}'" to {
                playlistDbHelper.removeSongFromPlaylist(fromPlaylist.id, song.id)
                refreshCollectionData()
                Toast.makeText(this, "Removed from '${fromPlaylist.name}'", Toast.LENGTH_SHORT).show()
                showPlaylistDetailDialog(fromPlaylist)
            })
        }
        options.add("⚙ Playback & Recommendations Settings" to { showPlaybackSettingsDialog() })

        val labels = options.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(labels) { _, which ->
                if (which in options.indices) {
                    options[which].second.invoke()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPlaybackSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_playback_settings, null)
        dialog.setContentView(view)

        val ivClose: ImageView = view.findViewById(R.id.ivClosePlaybackSettings)
        val switchAutoplay: SwitchCompat = view.findViewById(R.id.switchAutoplay)
        val switchSmartShuffle: SwitchCompat = view.findViewById(R.id.switchSmartShuffle)
        val switchPersonalization: SwitchCompat = view.findViewById(R.id.switchPersonalization)
        val switchAvoidRecent: SwitchCompat = view.findViewById(R.id.switchAvoidRecent)
        val switchReducedIntensity: SwitchCompat? = view.findViewById(R.id.switchReducedIntensity)
        val rgDiversity: RadioGroup = view.findViewById(R.id.rgDiversity)
        val rbBalanced: RadioButton = view.findViewById(R.id.rbBalanced)
        val rbFamiliar: RadioButton = view.findViewById(R.id.rbFamiliar)
        val rbDiscovery: RadioButton = view.findViewById(R.id.rbDiscovery)
        val btnResetPreferences: Button = view.findViewById(R.id.btnResetPreferences)

        ivClose.setOnClickListener { dialog.dismiss() }

        // Initialize state from AppSettings
        switchAutoplay.isChecked = appSettings.isAutoplayEnabled()
        switchSmartShuffle.isChecked = (smartShuffleState == SmartShuffleState.SMART_SHUFFLE || appSettings.isSmartShuffleEnabled())
        switchPersonalization.isChecked = appSettings.isPersonalizationEnabled()
        switchAvoidRecent.isChecked = appSettings.shouldAvoidRecentlyPlayed()
        switchReducedIntensity?.isChecked = appSettings.isReducedIntensityEnabled()
        switchReducedIntensity?.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setReducedIntensityEnabled(isChecked)
            syncUiWithPlayer()
        }

        when (appSettings.getRecommendationDiversity()) {
            AppSettings.DIVERSITY_FAMILIAR -> rbFamiliar.isChecked = true
            AppSettings.DIVERSITY_DISCOVERY -> rbDiscovery.isChecked = true
            else -> rbBalanced.isChecked = true
        }

        // Event listeners
        switchAutoplay.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setAutoplayEnabled(isChecked)
            updateAutoplayDisplay()
            if (isChecked) {
                triggerAutoplayCheck()
            }
        }

        switchSmartShuffle.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setSmartShuffleEnabled(isChecked)
            if (isChecked && smartShuffleState != SmartShuffleState.SMART_SHUFFLE) {
                smartShuffleState = SmartShuffleState.SMART_SHUFFLE
                appSettings.setShuffleMode(AppSettings.SHUFFLE_MODE_SMART)
                mediaController?.shuffleModeEnabled = true
                updateShuffleControls(SmartShuffleState.SMART_SHUFFLE)
                injectSmartShuffleRecommendations()
            } else if (!isChecked && smartShuffleState == SmartShuffleState.SMART_SHUFFLE) {
                smartShuffleState = SmartShuffleState.SHUFFLE
                appSettings.setShuffleMode(AppSettings.SHUFFLE_MODE_SHUFFLE)
                removeInjectedSmartShuffleRecommendations()
                updateShuffleControls(SmartShuffleState.SHUFFLE)
            }
        }

        switchPersonalization.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setPersonalizationEnabled(isChecked)
            recommendationEngine.clearCache()
        }

        switchAvoidRecent.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setAvoidRecentlyPlayed(isChecked)
            recommendationEngine.clearCache()
        }

        rgDiversity.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbFamiliar -> AppSettings.DIVERSITY_FAMILIAR
                R.id.rbDiscovery -> AppSettings.DIVERSITY_DISCOVERY
                else -> AppSettings.DIVERSITY_BALANCED
            }
            appSettings.setRecommendationDiversity(mode)
            recommendationEngine.clearCache()
        }

        btnResetPreferences.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Learned Preferences?")
                .setMessage("This will clear your listening history, skipped track penalties, and recommendation feedback. Recommendations will reset to fresh defaults.")
                .setPositiveButton("Reset") { _, _ ->
                    playlistDbHelper.resetLearnedPreferences()
                    recommendationEngine.clearCache()
                    refreshHomeData()
                    Toast.makeText(this, "Preferences and history reset", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // About Melody & Manual Check for updates
        val tvAboutAppVersion: TextView? = view.findViewById(R.id.tvAboutAppVersion)
        val btnCheckForUpdates: Button? = view.findViewById(R.id.btnCheckForUpdates)
        val tvCheckUpdateStatus: TextView? = view.findViewById(R.id.tvCheckUpdateStatus)

        tvAboutAppVersion?.text = "Version ${updateManager.getCurrentVersionName()} (Build ${updateManager.getCurrentVersionCode()})"

        btnCheckForUpdates?.setOnClickListener {
            btnCheckForUpdates.isEnabled = false
            btnCheckForUpdates.text = "Checking for updates..."
            tvCheckUpdateStatus?.visibility = View.VISIBLE
            tvCheckUpdateStatus?.text = "Connecting to GitHub..."

            lifecycleScope.launch {
                val result = updateManager.checkForUpdate(isManual = true)
                btnCheckForUpdates.isEnabled = true
                btnCheckForUpdates.text = "Check for updates"

                when (result) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        tvCheckUpdateStatus?.text = "Update available: v${result.metadata.versionName}"
                        updateDialogHelper.showUpdateDialog(result.metadata)
                    }
                    is UpdateCheckResult.UpToDate -> {
                        val msg = "Melody is up to date (v${result.currentVersionName})"
                        tvCheckUpdateStatus?.text = msg
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                    is UpdateCheckResult.Error -> {
                        tvCheckUpdateStatus?.text = result.message
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showDownloadedSongsDialog() {
        val downloaded = playlistDbHelper.getAllDownloadedSongs()
        showSongCollectionSheet(
            title = "Downloaded Music",
            subtitle = "Offline tracks",
            songs = downloaded,
            isDownloaded = true
        )
    }

    private fun openGenreTracks(genre: GenreCategory) {
        openCategorySheet(
            title = "${genre.emoji} ${genre.name}",
            subtitle = genre.subtitle,
            query = genre.query
        )
    }

    private fun openMoodTracks(mood: MoodCategory) {
        openCategorySheet(
            title = "${mood.emoji} ${mood.name}",
            subtitle = mood.subtitle,
            query = mood.query
        )
    }

    private fun openCategorySheet(title: String, subtitle: String, query: String) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_genre_detail, null)
        dialog.setContentView(view)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val ivClose: ImageView = view.findViewById(R.id.ivCloseGenre)
        val tvTitle: TextView = view.findViewById(R.id.tvGenreHeaderTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvGenreHeaderSubtitle)
        val btnPlayAll: Button = view.findViewById(R.id.btnGenrePlayAll)
        val btnShuffleAll: Button = view.findViewById(R.id.btnGenreShuffleAll)
        val btnQueueAll: Button = view.findViewById(R.id.btnGenreQueueAll)
        val pbLoading: ProgressBar = view.findViewById(R.id.pbGenreLoading)
        val rvSongs: RecyclerView = view.findViewById(R.id.rvGenreSongs)
        val tvEmpty: TextView = view.findViewById(R.id.tvGenreEmpty)

        tvTitle.text = title
        tvSubtitle.text = subtitle

        ivClose.setOnClickListener { dialog.dismiss() }

        var loadedSongs = emptyList<Song>()
        val categoryAdapter = SongAdapter(
            scope = lifecycleScope,
            httpClient = metadataService.httpClient,
            onSongSelected = { song -> playSongNow(song) },
            onSongAddToQueue = { song -> addSongToQueue(song) },
            onSongOptions = { song -> showSongOptionDialog(song) },
            onSongLikeToggled = { song -> toggleLikeSong(song) },
            isSongLiked = { songId -> playlistDbHelper.isSongLiked(songId) }
        )
        rvSongs.layoutManager = LinearLayoutManager(this)
        rvSongs.adapter = categoryAdapter

        btnPlayAll.setOnClickListener {
            if (loadedSongs.isNotEmpty()) {
                val mediaItems = loadedSongs.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.prepare()
                    c.play()
                }
                Toast.makeText(this, "Playing all $title (${loadedSongs.size} tracks)", Toast.LENGTH_SHORT).show()
            }
        }

        btnShuffleAll.setOnClickListener {
            if (loadedSongs.isNotEmpty()) {
                val mediaItems = loadedSongs.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.stop()
                    c.setMediaItems(mediaItems)
                    c.shuffleModeEnabled = true
                    c.prepare()
                    c.play()
                }
                Toast.makeText(this, "Shuffling $title", Toast.LENGTH_SHORT).show()
            }
        }

        btnQueueAll.setOnClickListener {
            if (loadedSongs.isNotEmpty()) {
                val mediaItems = loadedSongs.map { SongMediaItemMapper.toMediaItem(it) }
                mediaController?.let { c ->
                    c.addMediaItems(mediaItems)
                    if (c.playbackState == Player.STATE_IDLE || c.mediaItemCount == mediaItems.size) {
                        c.prepare()
                    }
                }
                Toast.makeText(this, "Added ${loadedSongs.size} tracks to queue", Toast.LENGTH_SHORT).show()
            }
        }

        pbLoading.visibility = View.VISIBLE
        rvSongs.visibility = View.GONE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val result = metadataService.searchSongs(appSettings.getBaseUrl(), query)
            pbLoading.visibility = View.GONE
            when (result) {
                is SearchResult.Success -> {
                    loadedSongs = result.songs
                    categoryAdapter.submitList(loadedSongs)
                    tvSubtitle.text = "${loadedSongs.size} tracks • $subtitle"
                    rvSongs.visibility = View.VISIBLE
                }
                is SearchResult.Empty -> {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "No tracks found for $title"
                }
                is SearchResult.Error -> {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = result.message
                }
            }
        }

        dialog.show()
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
        val layoutContent: LinearLayout = view.findViewById(R.id.layoutLyricsContent)
        val rvLyrics: RecyclerView = view.findViewById(R.id.rvLyrics)
        val tvCopyright: TextView = view.findViewById(R.id.tvLyricsCopyright)

        tvSongTitle.text = song.title
        tvSongArtist.text = song.artists

        ivClose.setOnClickListener { dialog.dismiss() }

        val lyricsAdapter = SyncedLyricsAdapter(
            onLineClicked = { timeMs ->
                mediaController?.seekTo(timeMs)
            }
        )
        val layoutManager = LinearLayoutManager(this)
        rvLyrics.layoutManager = layoutManager
        rvLyrics.adapter = lyricsAdapter

        var isUserTouching = false
        var lastUserInteractionTime = 0L

        rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isUserTouching = true
                    lastUserInteractionTime = System.currentTimeMillis()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    isUserTouching = false
                    lastUserInteractionTime = System.currentTimeMillis()
                }
            }
        })

        var currentActiveIndex = -1
        val lyricsTicker = object : Runnable {
            override fun run() {
                val controller = mediaController
                if (dialog.isShowing && controller != null && lyricsAdapter.isSyncedLyrics()) {
                    val currentPos = controller.currentPosition
                    val lines = lyricsAdapter.getLines()
                    val newIndex = lyricsService.getActiveLineIndex(lines, currentPos)

                    if (newIndex != currentActiveIndex) {
                        currentActiveIndex = newIndex
                        lyricsAdapter.setActiveIndex(newIndex)

                        val now = System.currentTimeMillis()
                        if (!isUserTouching && (now - lastUserInteractionTime > 2500L)) {
                            if (newIndex in lines.indices) {
                                val offset = (rvLyrics.height / 3).coerceAtLeast(0)
                                layoutManager.scrollToPositionWithOffset(newIndex, offset)
                            }
                        }
                    }
                }
                if (dialog.isShowing) {
                    mainHandler.postDelayed(this, 200)
                }
            }
        }

        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(lyricsTicker)
        }

        fun loadLyrics() {
            pbLoading.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            layoutContent.visibility = View.GONE
            mainHandler.removeCallbacks(lyricsTicker)
            currentActiveIndex = -1

            val baseUrl = appSettings.getBaseUrl()
            lifecycleScope.launch {
                when (val result = lyricsService.fetchLyrics(baseUrl, song.id, song.title, song.artists)) {
                    is LyricsResult.Success -> {
                        pbLoading.visibility = View.GONE
                        layoutEmpty.visibility = View.GONE
                        layoutContent.visibility = View.VISIBLE
                        lyricsAdapter.submitLines(result.lines, result.isSynced)
                        if (!result.copyright.isNullOrBlank()) {
                            tvCopyright.visibility = View.VISIBLE
                            tvCopyright.text = result.copyright
                        } else {
                            tvCopyright.visibility = View.GONE
                        }
                        if (result.isSynced) {
                            mainHandler.post(lyricsTicker)
                        }
                    }
                    is LyricsResult.Unavailable -> {
                        pbLoading.visibility = View.GONE
                        layoutContent.visibility = View.GONE
                        layoutEmpty.visibility = View.VISIBLE
                        tvEmptyMsg.text = result.message
                        btnRetry.visibility = View.GONE
                    }
                    is LyricsResult.Error -> {
                        pbLoading.visibility = View.GONE
                        layoutContent.visibility = View.GONE
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
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_to_playlist, null)
        dialog.setContentView(view)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val ivClose: ImageView = view.findViewById(R.id.ivCloseAddToPlaylist)
        val tvTarget: TextView = view.findViewById(R.id.tvAddToPlaylistSongTarget)
        val btnNewPlaylist: View = view.findViewById(R.id.btnCreateNewPlaylistInSheet)
        val rvPlaylists: RecyclerView = view.findViewById(R.id.rvExistingPlaylists)
        val tvEmptyNote: TextView = view.findViewById(R.id.tvNoPlaylistsNote)
        val tvHeader: TextView = view.findViewById(R.id.tvYourPlaylistsHeader)

        tvTarget.text = "${song.title} • ${song.artists}"
        ivClose.setOnClickListener { dialog.dismiss() }

        btnNewPlaylist.setOnClickListener {
            dialog.dismiss()
            showCreatePlaylistDialog(songToAdd = song)
        }

        val playlists = playlistDbHelper.getAllPlaylists()
        if (playlists.isEmpty()) {
            tvEmptyNote.visibility = View.VISIBLE
            tvHeader.visibility = View.GONE
            rvPlaylists.visibility = View.GONE
        } else {
            tvEmptyNote.visibility = View.GONE
            tvHeader.visibility = View.VISIBLE
            rvPlaylists.visibility = View.VISIBLE
            rvPlaylists.layoutManager = LinearLayoutManager(this)

            val adapter = PlaylistAdapter(
                onPlaylistSelected = { pl ->
                    val added = playlistDbHelper.addSongToPlaylist(pl.id, song)
                    if (added) {
                        refreshCollectionData()
                        Toast.makeText(this, "Added to '${pl.name}'", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this, "Track is already in '${pl.name}'", Toast.LENGTH_SHORT).show()
                    }
                },
                onPlaylistOptions = { pl ->
                    showPlaylistDetailDialog(pl)
                }
            )
            rvPlaylists.adapter = adapter
            adapter.submitList(playlists)
        }

        dialog.show()
    }

    private fun openExploreSearch() {
        bottomNavigation.selectedItemId = R.id.nav_search
        containerSearch.post {
            etSearchQuery.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }
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
