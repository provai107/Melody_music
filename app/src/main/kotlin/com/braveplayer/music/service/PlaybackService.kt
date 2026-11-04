package com.braveplayer.music.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.braveplayer.music.MainActivity
import com.braveplayer.music.discovery.MusicRecommendationEngine
import com.braveplayer.music.media.BraveMedia3HttpDataSource
import com.braveplayer.music.media.SongMediaItemMapper
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import com.braveplayer.music.settings.FilterEngineProvider
import com.braveplayer.music.settings.QueuePersistenceManager
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Media3 MediaSessionService managing background playback, ExoPlayer ownership,
 * audio focus, headphone disconnect handling, lock-screen playback notification,
 * listening history tracking, and autoplay queue extension.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val CHANNEL_ID = "brave_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val COMMAND_EXTEND_AUTOPLAY = "com.braveplayer.music.EXTEND_AUTOPLAY"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var queuePersistenceManager: QueuePersistenceManager
    private lateinit var appSettings: AppSettings
    private lateinit var playlistDbHelper: PlaylistDatabaseHelper
    private lateinit var recommendationEngine: MusicRecommendationEngine
    private lateinit var playbackController: com.braveplayer.music.player.CentralizedPlaybackController

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // History tracking
    private var currentPlayingSong: Song? = null
    private var currentTrackStartTimeMs: Long = 0L

    // Autoplay & resilience tracking
    private var hasEverStartedPlayback: Boolean = false
    private var isExtendingQueue: Boolean = false
    private val failedTrackIds = mutableSetOf<String>()
    private val recentExtendedIds = LinkedHashSet<String>()
    private var autoplayWatchdogJob: Job? = null
    private var prefsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(this)
        playlistDbHelper = PlaylistDatabaseHelper(this)
        queuePersistenceManager = QueuePersistenceManager(this)

        val filterManager = FilterEngineProvider.filterListManager
        val metadataService = com.braveplayer.music.api.MusicMetadataService(filterManager)
        recommendationEngine = MusicRecommendationEngine(metadataService, playlistDbHelper)

        createNotificationChannel()

        // Clean any audio streaming cache on startup to prevent disk storage growth
        com.braveplayer.music.util.NetworkUtils.clearAudioStreamingCache(this)

        val upstreamFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
            .setDefaultRequestProperties(
                mapOf(
                    "Cache-Control" to "no-cache, no-store, must-revalidate",
                    "Pragma" to "no-cache",
                    "Expires" to "0"
                )
            )

        val braveDataSourceFactory = BraveMedia3HttpDataSource.Factory(
            upstreamFactory = upstreamFactory,
            filterListManager = filterManager
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(braveDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true) // Automatic audio focus handling
            .setHandleAudioBecomingNoisy(true) // Automatic pause on headphone disconnect
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    hasEverStartedPlayback = true
                    if (currentTrackStartTimeMs == 0L) {
                        currentTrackStartTimeMs = System.currentTimeMillis()
                    }
                    checkAndExtendQueueIfNeeded(exoPlayer)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    hasEverStartedPlayback = true
                    checkAndExtendQueueIfNeeded(exoPlayer)
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (exoPlayer.playWhenReady || exoPlayer.isPlaying) {
                    hasEverStartedPlayback = true
                    checkAndExtendQueueIfNeeded(exoPlayer)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerPlaybackError(exoPlayer, error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                persistCurrentQueueState(exoPlayer)

                if (exoPlayer.playWhenReady || exoPlayer.isPlaying) {
                    hasEverStartedPlayback = true
                }

                if (playbackState == Player.STATE_ENDED) {
                    recordCurrentTrackHistory(isPlaybackEnded = true)
                    checkAndExtendQueueIfNeeded(exoPlayer, force = true)
                } else if (playbackState == Player.STATE_READY && exoPlayer.playWhenReady) {
                    checkAndExtendQueueIfNeeded(exoPlayer)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Record history for previous track
                recordCurrentTrackHistory(isPlaybackEnded = (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))

                // Update current track tracking
                currentPlayingSong = mediaItem?.let { SongMediaItemMapper.fromMediaItem(it) }
                currentTrackStartTimeMs = if (exoPlayer.isPlaying) System.currentTimeMillis() else 0L

                if (exoPlayer.playWhenReady || exoPlayer.isPlaying) {
                    hasEverStartedPlayback = true
                }

                persistCurrentQueueState(exoPlayer)
                checkAndExtendQueueIfNeeded(exoPlayer)
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(MediaSessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .build()
        )

        this.player = exoPlayer
        this.mediaSession = session

        playbackController = com.braveplayer.music.player.CentralizedPlaybackController.getInstance(
            context = this,
            playlistDbHelper = playlistDbHelper,
            appSettings = appSettings,
            recommendationEngine = recommendationEngine,
            queuePersistenceManager = queuePersistenceManager
        )
        playbackController.attachPlayer(exoPlayer)
        com.braveplayer.music.jam.service.JamSessionManager.getInstance(this).attachPlayer(exoPlayer)

        // Restore persisted queue state without auto-playing loudly
        restorePersistedQueue(exoPlayer)

        // Listen for preference changes so toggling Autoplay in UI triggers check immediately
        prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppSettings.KEY_AUTOPLAY && appSettings.isAutoplayEnabled()) {
                player?.let { exo ->
                    checkAndExtendQueueIfNeeded(exo, force = true)
                }
            }
        }
        getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)

        // Start background watchdog to ensure queue never runs out of upcoming tracks
        startAutoplayWatchdog()
    }

    private fun startAutoplayWatchdog() {
        autoplayWatchdogJob?.cancel()
        autoplayWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(12_000L) // Proactive check every 12 seconds
                player?.let { exo ->
                    if ((exo.isPlaying || exo.playWhenReady) && appSettings.isAutoplayEnabled()) {
                        checkAndExtendQueueIfNeeded(exo)
                    }
                }
            }
        }
    }

    private fun recordExtendedIds(ids: Collection<String>) {
        recentExtendedIds.addAll(ids)
        while (recentExtendedIds.size > 30) {
            val first = recentExtendedIds.iterator().next()
            recentExtendedIds.remove(first)
        }
    }

    private fun recordCurrentTrackHistory(isPlaybackEnded: Boolean) {
        val song = currentPlayingSong ?: return
        if (currentTrackStartTimeMs <= 0L) return

        val now = System.currentTimeMillis()
        val playDurationSec = ((now - currentTrackStartTimeMs) / 1000).toInt().coerceAtLeast(0)
        val totalSec = song.durationSec

        // Avoid counting a track as meaningfully played after only a few seconds
        val isCompleted = isPlaybackEnded || (totalSec > 0 && playDurationSec >= (totalSec * 0.85))
        val isMeaningful = playDurationSec >= 30 || (totalSec > 0 && playDurationSec >= (totalSec * 0.30))
        val isSkipped = !isCompleted && playDurationSec < 30

        playlistDbHelper.recordListeningHistory(
            song = song,
            playDurationSec = playDurationSec,
            isCompleted = isCompleted,
            isSkipped = isSkipped,
            isMeaningful = isMeaningful
        )

        currentTrackStartTimeMs = 0L
    }

    private fun checkAndExtendQueueIfNeeded(exoPlayer: ExoPlayer, force: Boolean = false) {
        if (!appSettings.isAutoplayEnabled()) return
        if (exoPlayer.playWhenReady || exoPlayer.isPlaying) {
            hasEverStartedPlayback = true
        }
        if (!hasEverStartedPlayback) return // Never autoplay just on app launch / boot before playback
        if (exoPlayer.mediaItemCount == 0) return
        if (exoPlayer.repeatMode != Player.REPEAT_MODE_OFF) return // Do not extend if user requested loop
        if (isExtendingQueue) return

        val currentIndex = exoPlayer.currentMediaItemIndex
        val totalCount = exoPlayer.mediaItemCount
        val remainingTracks = (totalCount - 1 - currentIndex).coerceAtLeast(0)
        // Proactively extend when 3 or fewer tracks remain, playback ended, or force requested
        val isNearEnd = remainingTracks <= 3 || exoPlayer.playbackState == Player.STATE_ENDED || force

        if (!isNearEnd) return

        isExtendingQueue = true
        serviceScope.launch {
            try {
                // Determine seed song: use last item in queue or current song
                val seedIndex = (totalCount - 1).coerceAtLeast(0)
                val seedItem = exoPlayer.getMediaItemAt(seedIndex)
                val seedSong = SongMediaItemMapper.fromMediaItem(seedItem)

                val hiddenIds = playlistDbHelper.getHiddenSongIds()
                val notInterestedIds = playlistDbHelper.getNotInterestedSongIds()
                val currentQueueIds = (0 until totalCount).map { exoPlayer.getMediaItemAt(it).mediaId }.toSet()
                val currentQueueTitles = (0 until totalCount).mapNotNull {
                    exoPlayer.getMediaItemAt(it).mediaMetadata.title?.toString()
                }.toSet()
                val strictExcludeIds = (currentQueueIds + failedTrackIds + hiddenIds + notInterestedIds).toSet()
                val baseUrl = appSettings.getBaseUrl()

                val candidates = mutableListOf<Song>()

                // Strategy 1: Multi-signal Recommendation Engine using Seed Song
                try {
                    withTimeoutOrNull(5000L) {
                        val recs = recommendationEngine.getRecommendations(
                            baseUrl = baseUrl,
                            seedSong = seedSong,
                            excludeSongIds = strictExcludeIds + recentExtendedIds,
                            excludeTitles = currentQueueTitles,
                            limit = 8,
                            personalizationEnabled = appSettings.isPersonalizationEnabled(),
                            avoidRecentlyPlayed = appSettings.shouldAvoidRecentlyPlayed(),
                            diversityMode = appSettings.getRecommendationDiversity(),
                            recommendationInfluence = appSettings.getRecommendationInfluence(),
                            excludeDislikedTracks = appSettings.shouldExcludeDislikedTracks()
                        )
                        candidates.addAll(recs)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PlaybackService", "Autoplay seed recommendations error: ${e.message}")
                }

                // Strategy 2: Artist & Contextual Discovery Searches
                if (candidates.size < 6) {
                    val searchQueries = mutableListOf<String>()
                    val primaryArtist = seedSong.artists.split(",", "&", "feat.", "ft.", "/").firstOrNull()?.trim() ?: ""
                    if (primaryArtist.isNotBlank() && primaryArtist.lowercase() !in playlistDbHelper.getNotInterestedArtists()) {
                        searchQueries.add("$primaryArtist hits")
                    }

                    val dynamicTokens = listOf(
                        "Trending Songs",
                        "Top Global Hits",
                        "Bollywood Top 50",
                        "Latest Pop Hits",
                        "Dance Pop Hits",
                        "Chill Melodies",
                        "Acoustic Pop",
                        "Viral Songs",
                        "English Top Hits",
                        "Hindi Top Songs"
                    ).shuffled()
                    searchQueries.addAll(dynamicTokens)

                    for (query in searchQueries) {
                        if (candidates.size >= 8) break
                        try {
                            withTimeoutOrNull(3500L) {
                                val songs = recommendationEngine.fetchCandidateSongs(baseUrl, query)
                                val valid = songs.filter {
                                    it.id !in strictExcludeIds &&
                                    it.id !in recentExtendedIds &&
                                    it.id !in candidates.map { c -> c.id } &&
                                    !MusicRecommendationEngine.areTitlesTooSimilar(it.title, seedSong.title) &&
                                    !currentQueueTitles.any { q -> MusicRecommendationEngine.areTitlesTooSimilar(it.title, q) }
                                }
                                candidates.addAll(valid)
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Strategy 3: Relax recent extended filter if online pool was restricted
                if (candidates.size < 6) {
                    for (query in listOf("Trending Songs", "Top Global Hits", "Melody Hits").shuffled()) {
                        if (candidates.size >= 8) break
                        try {
                            withTimeoutOrNull(3500L) {
                                val songs = recommendationEngine.fetchCandidateSongs(baseUrl, query)
                                val valid = songs.filter {
                                    it.id !in strictExcludeIds &&
                                    it.id !in candidates.map { c -> c.id } &&
                                    !MusicRecommendationEngine.areTitlesTooSimilar(it.title, seedSong.title) &&
                                    !currentQueueTitles.any { q -> MusicRecommendationEngine.areTitlesTooSimilar(it.title, q) }
                                }
                                candidates.addAll(valid)
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Strategy 4: Local Storage & User Favorites/History Fallback (offline or API issue)
                if (candidates.size < 6) {
                    val downloaded = playlistDbHelper.getAllDownloadedSongs()
                        .filter { it.id !in strictExcludeIds && it.id !in candidates.map { c -> c.id } }
                        .shuffled()
                    candidates.addAll(downloaded)
                }

                if (candidates.size < 6) {
                    val localCandidates = (playlistDbHelper.getAllLikedSongs() + playlistDbHelper.getRecentlyPlayedSongs(100))
                        .filter { it.id !in strictExcludeIds && it.id !in candidates.map { c -> c.id } }
                        .distinctBy { it.id }
                        .shuffled()
                    candidates.addAll(localCandidates)
                }

                // Strategy 5: Infinite Autoplay Safety Net - Re-queue previous non-current tracks so queue NEVER terminates
                if (candidates.isEmpty()) {
                    val currentPlayingId = exoPlayer.currentMediaItem?.mediaId
                    val recyclable = (0 until totalCount)
                        .map { exoPlayer.getMediaItemAt(it) }
                        .filter { it.mediaId != currentPlayingId && it.mediaId !in hiddenIds && it.mediaId !in failedTrackIds }
                        .map { SongMediaItemMapper.fromMediaItem(it) }
                        .distinctBy { it.id }
                        .shuffled()
                    candidates.addAll(recyclable)
                }

                if (candidates.isNotEmpty()) {
                    val finalBatch = candidates.take(8)
                    recordExtendedIds(finalBatch.map { it.id })

                    val newMediaItems = finalBatch.map {
                        SongMediaItemMapper.toMediaItem(
                            song = it,
                            origin = SongMediaItemMapper.ORIGIN_AUTOPLAY,
                            contextName = "Similar to ${seedSong.title}",
                            isRecommended = true
                        )
                    }

                    val previousCount = exoPlayer.mediaItemCount
                    exoPlayer.addMediaItems(newMediaItems)

                    // If playback was ended at queue end, or stalled without next track, resume immediately into new songs
                    if (exoPlayer.playbackState == Player.STATE_ENDED ||
                        (!exoPlayer.isPlaying && exoPlayer.playWhenReady && currentIndex >= previousCount - 1)
                    ) {
                        val targetIndex = previousCount.coerceAtMost(exoPlayer.mediaItemCount - 1)
                        if (targetIndex >= 0) {
                            exoPlayer.seekToDefaultPosition(targetIndex)
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Error during autoplay queue extension", e)
            } finally {
                isExtendingQueue = false
            }
        }
    }

    private fun handlePlayerPlaybackError(exoPlayer: ExoPlayer, error: PlaybackException) {
        android.util.Log.w("PlaybackService", "Playback error occurred: ${error.errorCodeName}", error)
        val currentItem = exoPlayer.currentMediaItem
        if (currentItem != null) {
            val songId = currentItem.mediaId
            val extras = currentItem.mediaMetadata.extras
            val candidateUrls = extras?.getStringArrayList(SongMediaItemMapper.EXTRA_CANDIDATE_URLS)
            val currentIndex = extras?.getInt(SongMediaItemMapper.EXTRA_CURRENT_URL_INDEX, 0) ?: 0
            val nextCandidateIndex = currentIndex + 1

            if (candidateUrls != null && nextCandidateIndex < candidateUrls.size) {
                val fallbackItem = SongMediaItemMapper.createFallbackMediaItem(currentItem, nextCandidateIndex)
                if (fallbackItem != null) {
                    val mediaIndex = exoPlayer.currentMediaItemIndex
                    exoPlayer.replaceMediaItem(mediaIndex, fallbackItem)
                    exoPlayer.prepare()
                    exoPlayer.play()
                    return
                }
            }

            // All candidates failed: track to prevent repeatedly selecting it
            failedTrackIds.add(songId)
        }

        // Advance queue gracefully if possible
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            checkAndExtendQueueIfNeeded(exoPlayer, force = true)
        }
    }

    private fun restorePersistedQueue(exoPlayer: ExoPlayer) {
        val state = queuePersistenceManager.restoreQueueState()
        if (state.songs.isNotEmpty()) {
            val mediaItems = state.songs.map { SongMediaItemMapper.toMediaItem(it) }
            exoPlayer.setMediaItems(mediaItems, state.currentIndex, state.positionMs)
            exoPlayer.repeatMode = state.repeatMode
            exoPlayer.shuffleModeEnabled = state.shuffleMode
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false // DO NOT auto-play on boot/restart
            currentPlayingSong = state.songs.getOrNull(state.currentIndex)
        }
    }

    private fun persistCurrentQueueState(exoPlayer: ExoPlayer) {
        val count = exoPlayer.mediaItemCount
        if (count == 0) return

        val songs = mutableListOf<Song>()
        for (i in 0 until count) {
            val mediaItem = exoPlayer.getMediaItemAt(i)
            songs.add(SongMediaItemMapper.fromMediaItem(mediaItem))
        }

        queuePersistenceManager.saveQueueState(
            songs = songs,
            currentIndex = exoPlayer.currentMediaItemIndex,
            positionMs = exoPlayer.currentPosition,
            repeatMode = exoPlayer.repeatMode,
            shuffleMode = exoPlayer.shuffleModeEnabled
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls and status notification"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        player?.let { exo ->
            persistCurrentQueueState(exo)
            recordCurrentTrackHistory(isPlaybackEnded = false)
            exo.stop()
            exo.clearMediaItems()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        playbackController.detachPlayer()
        com.braveplayer.music.jam.service.JamSessionManager.getInstance(this).detachPlayer()
        recordCurrentTrackHistory(isPlaybackEnded = false)
        autoplayWatchdogJob?.cancel()
        autoplayWatchdogJob = null

        prefsChangeListener?.let {
            getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }

        serviceScope.cancel()

        player?.let { exoPlayer ->
            persistCurrentQueueState(exoPlayer)
            exoPlayer.release()
        }
        player = null

        mediaSession?.release()
        mediaSession = null

        super.onDestroy()
    }

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_EXTEND_AUTOPLAY, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_EXTEND_AUTOPLAY) {
                player?.let { exo ->
                    checkAndExtendQueueIfNeeded(exo, force = true)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            return Futures.immediateFuture(mediaItems)
        }
    }
}
