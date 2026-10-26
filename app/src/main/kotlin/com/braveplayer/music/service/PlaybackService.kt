package com.braveplayer.music.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Media3 MediaSessionService managing background playback, ExoPlayer ownership,
 * audio focus, headphone disconnect handling, lock-screen playback notification,
 * listening history tracking, and autoplay queue extension.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val CHANNEL_ID = "brave_playback_channel"
        const val NOTIFICATION_ID = 1001
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var queuePersistenceManager: QueuePersistenceManager
    private lateinit var appSettings: AppSettings
    private lateinit var playlistDbHelper: PlaylistDatabaseHelper
    private lateinit var recommendationEngine: MusicRecommendationEngine

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // History tracking
    private var currentPlayingSong: Song? = null
    private var currentTrackStartTimeMs: Long = 0L

    // Autoplay & resilience tracking
    private var hasEverStartedPlayback: Boolean = false
    private var isExtendingQueue: Boolean = false
    private val failedTrackIds = mutableSetOf<String>()
    private val extendedTrackIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(this)
        playlistDbHelper = PlaylistDatabaseHelper(this)
        queuePersistenceManager = QueuePersistenceManager(this)

        val filterManager = FilterEngineProvider.filterListManager
        val metadataService = com.braveplayer.music.api.MusicMetadataService(filterManager)
        recommendationEngine = MusicRecommendationEngine(metadataService, playlistDbHelper)

        createNotificationChannel()

        val upstreamFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)

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
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerPlaybackError(exoPlayer, error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                persistCurrentQueueState(exoPlayer)

                if (playbackState == Player.STATE_ENDED) {
                    recordCurrentTrackHistory(isPlaybackEnded = true)
                    checkAndExtendQueueIfNeeded(exoPlayer)
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

        // Restore persisted queue state without auto-playing loudly
        restorePersistedQueue(exoPlayer)
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

    private fun checkAndExtendQueueIfNeeded(exoPlayer: ExoPlayer) {
        if (!appSettings.isAutoplayEnabled()) return
        if (!hasEverStartedPlayback) return // Never autoplay just on app launch / boot
        if (exoPlayer.mediaItemCount == 0) return
        if (exoPlayer.repeatMode != Player.REPEAT_MODE_OFF) return // Do not extend if user requested loop
        if (isExtendingQueue) return

        val currentIndex = exoPlayer.currentMediaItemIndex
        val totalCount = exoPlayer.mediaItemCount
        val remainingTracks = (totalCount - 1 - currentIndex).coerceAtLeast(0)
        val isNearEnd = remainingTracks < 4 || exoPlayer.playbackState == Player.STATE_ENDED

        if (!isNearEnd) return

        isExtendingQueue = true
        serviceScope.launch {
            try {
                // Determine seed song: use last item in queue or current song
                val seedIndex = (totalCount - 1).coerceAtLeast(0)
                val seedItem = exoPlayer.getMediaItemAt(seedIndex)
                val seedSong = SongMediaItemMapper.fromMediaItem(seedItem)

                val currentQueueIds = (0 until totalCount).map { exoPlayer.getMediaItemAt(it).mediaId }.toSet()
                val excludeIds = (currentQueueIds + failedTrackIds + extendedTrackIds).toMutableSet()
                val baseUrl = appSettings.getBaseUrl()

                var recommendations = try {
                    recommendationEngine.getRecommendations(
                        baseUrl = baseUrl,
                        seedSong = seedSong,
                        excludeSongIds = excludeIds,
                        limit = 8
                    )
                } catch (_: Exception) {
                    emptyList()
                }

                // If seed-based recommendations are thin (< 4), enrich with dynamic trending seeds
                if (recommendations.size < 4) {
                    val fallbackQueries = listOf("Top Hits", "Trending Hits", "Global Pop", "Dance Hits", "Acoustic Hits")
                    val query = fallbackQueries.random()
                    val onlineCandidates = try {
                        recommendationEngine.fetchCandidateSongs(baseUrl, query)
                            .filter { it.id !in currentQueueIds && it.id !in failedTrackIds }
                            .take(8)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    recommendations = (recommendations + onlineCandidates).distinctBy { it.id }
                }

                // Resilient local fallback: If online recommendations fail or offline, pull from liked tracks or history
                if (recommendations.isEmpty()) {
                    var localCandidates = (playlistDbHelper.getAllLikedSongs() + playlistDbHelper.getRecentlyPlayedSongs(50))
                        .filter { it.id !in currentQueueIds && it.id !in failedTrackIds }
                        .distinctBy { it.id }
                        .shuffled()
                        .take(8)

                    // If all local songs were already queued, recycle them so infinite playback never halts
                    if (localCandidates.isEmpty()) {
                        extendedTrackIds.clear()
                        localCandidates = (playlistDbHelper.getAllLikedSongs() + playlistDbHelper.getRecentlyPlayedSongs(50))
                            .distinctBy { it.id }
                            .shuffled()
                            .take(8)
                    }
                    recommendations = localCandidates
                }

                if (recommendations.isNotEmpty()) {
                    extendedTrackIds.addAll(recommendations.map { it.id })
                    val newMediaItems = recommendations.map { SongMediaItemMapper.toMediaItem(it) }

                    val previousCount = exoPlayer.mediaItemCount
                    exoPlayer.addMediaItems(newMediaItems)

                    // If playback was ended at queue end, resume seamlessly into new recommendations
                    if (exoPlayer.playbackState == Player.STATE_ENDED) {
                        val targetIndex = previousCount.coerceAtMost(exoPlayer.mediaItemCount - 1)
                        if (targetIndex >= 0) {
                            exoPlayer.seekToDefaultPosition(targetIndex)
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                    }
                }
            } catch (_: Exception) {
                // Fail gracefully without interrupting existing playback
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
            checkAndExtendQueueIfNeeded(exoPlayer)
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

    override fun onDestroy() {
        recordCurrentTrackHistory(isPlaybackEnded = false)
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

    private class MediaSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            return Futures.immediateFuture(mediaItems)
        }
    }
}
