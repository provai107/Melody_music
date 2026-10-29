package com.braveplayer.music.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.braveplayer.music.discovery.MusicRecommendationEngine
import com.braveplayer.music.media.SongMediaItemMapper
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import com.braveplayer.music.settings.QueuePersistenceManager
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Centralized Playback Controller and Single Source of Truth for Melody.
 *
 * Coordinates playback state, ordered manual queues (Play Next + Add to Queue),
 * context-based track sequences (albums, playlists, search results, history),
 * intelligent next-song selection, shuffle, repeat, and autoplay recommendations.
 */
class CentralizedPlaybackController(
    private val context: Context,
    private val playlistDbHelper: PlaylistDatabaseHelper,
    private val appSettings: AppSettings,
    private val recommendationEngine: MusicRecommendationEngine? = null,
    private val queuePersistenceManager: QueuePersistenceManager? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {

    companion object {
        @Volatile
        private var INSTANCE: CentralizedPlaybackController? = null

        fun getInstance(
            context: Context,
            playlistDbHelper: PlaylistDatabaseHelper = PlaylistDatabaseHelper(context),
            appSettings: AppSettings = AppSettings(context),
            recommendationEngine: MusicRecommendationEngine? = null,
            queuePersistenceManager: QueuePersistenceManager? = null
        ): CentralizedPlaybackController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CentralizedPlaybackController(
                    context = context.applicationContext,
                    playlistDbHelper = playlistDbHelper,
                    appSettings = appSettings,
                    recommendationEngine = recommendationEngine,
                    queuePersistenceManager = queuePersistenceManager
                ).also { INSTANCE = it }
            }
        }

        fun setInstanceForTesting(controller: CentralizedPlaybackController?) {
            INSTANCE = controller
        }
    }

    interface Listener {
        fun onSongChanged(song: Song?, queueItem: QueueItem?) {}
        fun onPlaybackStateChanged(state: PlaybackState) {}
        fun onQueueUpdated(snapshot: QueueSnapshot) {}
        fun onPositionDiscontinuity(positionMs: Long) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    // Core Queue State (single source of truth)
    private var currentItem: QueueItem? = null
    private val playNextQueue = mutableListOf<QueueItem>()
    private val manualQueue = mutableListOf<QueueItem>()
    private val originalContextTracks = mutableListOf<QueueItem>()
    private val activeContextTracks = mutableListOf<QueueItem>()
    private val autoplayQueue = mutableListOf<QueueItem>()
    private val playbackHistory = mutableListOf<QueueItem>()

    // Playback Settings & Modes
    private var activeContext: PlaybackContext? = null
    private var isShuffleEnabled: Boolean = false
    private var currentRepeatMode: RepeatMode = RepeatMode.OFF
    private var isAutoplayEnabled: Boolean = appSettings.isAutoplayEnabled()
    private var currentPlaybackState: PlaybackState = PlaybackState.IDLE

    // Resilience & Error Recovery
    private var consecutiveErrorCount: Int = 0
    private val failedSongIdsInSession = mutableSetOf<String>()
    private val recentRecommendedSongIds = LinkedHashSet<String>()
    private var isFetchingAutoplay: Boolean = false

    // Player attachment
    private var attachedPlayer: Player? = null
    private var isInternalPlayerUpdate: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _queueSnapshot = MutableStateFlow(buildSnapshot())
    val queueSnapshot: StateFlow<QueueSnapshot> = _queueSnapshot.asStateFlow()

    init {
        isAutoplayEnabled = appSettings.isAutoplayEnabled()
        val savedShuffle = appSettings.getShuffleMode()
        isShuffleEnabled = (savedShuffle != AppSettings.SHUFFLE_MODE_NORMAL)
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onQueueUpdated(buildSnapshot())
            listener.onPlaybackStateChanged(currentPlaybackState)
            if (currentItem != null) {
                listener.onSongChanged(currentItem?.song, currentItem)
            }
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Binds the underlying ExoPlayer or MediaController to this centralized controller.
     */
    fun attachPlayer(player: Player) {
        if (attachedPlayer == player) return
        if (attachedPlayer != null && attachedPlayer !is MediaController && player is MediaController) {
            return
        }
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = player
        player.addListener(playerListener)
        syncSettingsToPlayer(player)
        emitState()
    }

    fun detachPlayer() {
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null
    }

    private fun syncSettingsToPlayer(player: Player) {
        player.repeatMode = when (currentRepeatMode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // =========================================================================
    // Playback Entry Points (Search, History, Playlists, Albums, Favorites)
    // =========================================================================

    /**
     * Initiates playback of a specific song within a given playback context.
     * Guaranteed to play the exact track with its stable ID.
     */
    fun playTrack(
        song: Song,
        context: PlaybackContext? = null,
        contextSongs: List<Song>? = null,
        playImmediately: Boolean = true
    ) {
        val resolvedContext = context ?: PlaybackContext.single(song)
        val allSongs = contextSongs ?: resolvedContext.songs.ifEmpty { listOf(song) }

        // Find the exact selected song by ID (not stale index)
        val startIndex = allSongs.indexOfFirst { it.id == song.id }.let { if (it >= 0) it else 0 }
        val effectiveSelectedSong = allSongs.getOrNull(startIndex) ?: song

        activeContext = resolvedContext
        originalContextTracks.clear()
        activeContextTracks.clear()
        autoplayQueue.clear()
        consecutiveErrorCount = 0

        // Populate history with songs prior to selected index
        if (startIndex > 0) {
            for (i in 0 until startIndex) {
                playbackHistory.add(
                    QueueItem(
                        song = allSongs[i],
                        origin = QueueOrigin.CONTEXT,
                        contextTitle = resolvedContext.title
                    )
                )
            }
        }

        // Set current item
        currentItem = QueueItem(
            song = effectiveSelectedSong,
            origin = QueueOrigin.CONTEXT,
            contextTitle = resolvedContext.title
        )

        // Store complete original context for Repeat All and Shuffle restore
        for (s in allSongs) {
            originalContextTracks.add(
                QueueItem(
                    song = s,
                    origin = QueueOrigin.CONTEXT,
                    contextTitle = resolvedContext.title
                )
            )
        }

        // Populate upcoming context tracks
        for (i in (startIndex + 1) until allSongs.size) {
            val item = QueueItem(
                song = allSongs[i],
                origin = QueueOrigin.CONTEXT,
                contextTitle = resolvedContext.title
            )
            activeContextTracks.add(item)
        }

        if (isShuffleEnabled) {
            activeContextTracks.shuffle()
        }

        // Synchronize with player timeline
        syncTimelineToPlayer(startPlaying = playImmediately)
        emitState()

        // Trigger autoplay prefetch if near end
        checkAndPrefetchAutoplayIfNeeded()
    }

    /**
     * Plays a single track immediately.
     */
    fun playSingleSong(song: Song, playImmediately: Boolean = true) {
        playTrack(
            song = song,
            context = PlaybackContext.single(song),
            contextSongs = listOf(song),
            playImmediately = playImmediately
        )
    }

    // =========================================================================
    // Play Next & Add to Queue (Deterministic Queue Semantics)
    // =========================================================================

    /**
     * Inserts a track immediately after the currently playing song.
     * Explicit Play Next items have priority over ordinary queued tracks.
     * When multiple songs are added via Play Next, their insertion order is preserved.
     */
    fun playNext(song: Song) {
        val item = QueueItem(
            song = song,
            origin = QueueOrigin.PLAY_NEXT,
            contextTitle = "Play Next"
        )

        // Append to playNextQueue so insertion order is preserved
        playNextQueue.add(item)

        // Update player timeline immediately without interrupting current song
        attachedPlayer?.let { player ->
            val currIndex = player.currentMediaItemIndex
            if (currIndex >= 0) {
                // Insert after current item and all prior Play Next items
                val insertPos = currIndex + playNextQueue.size
                val mediaItem = toMediaItem(item)
                val safePos = insertPos.coerceIn(0, player.mediaItemCount)
                isInternalPlayerUpdate = true
                player.addMediaItem(safePos, mediaItem)
                isInternalPlayerUpdate = false
            } else {
                syncTimelineToPlayer(startPlaying = false)
            }
        }

        emitState()
    }

    /**
     * Appends a track to the end of the manual queue.
     * Preserves existing queue items, allows intentional duplicates.
     */
    fun addToQueue(song: Song) {
        val item = QueueItem(
            song = song,
            origin = QueueOrigin.ADD_TO_QUEUE,
            contextTitle = "Queued"
        )

        // Append to manual queue
        manualQueue.add(item)

        // Update player timeline immediately
        attachedPlayer?.let { player ->
            val currIndex = player.currentMediaItemIndex
            if (currIndex >= 0) {
                // Insert after current item, all Play Next items, and previous manual queue items
                val insertPos = currIndex + playNextQueue.size + manualQueue.size
                val mediaItem = toMediaItem(item)
                val safePos = insertPos.coerceIn(0, player.mediaItemCount)
                isInternalPlayerUpdate = true
                player.addMediaItem(safePos, mediaItem)
                isInternalPlayerUpdate = false
            } else {
                syncTimelineToPlayer(startPlaying = false)
            }
        }

        emitState()
    }

    // =========================================================================
    // Intelligent Next-Song & Previous-Song Selection
    // =========================================================================

    /**
     * Centralized next track decision function applying strict priority:
     * 1. Respect Repeat One when enabled (unless explicit user skip)
     * 2. Next eligible manual queue item (Play Next first, then Add to Queue)
     * 3. Continue through active playlist, album, or playback context
     * 4. Repeat All loop back when context ends
     * 5. Autoplay recommendations if context ended and Autoplay enabled
     * 6. Graceful termination if queue exhausted
     */
    fun getNextTrack(isManualSkip: Boolean = false): QueueItem? {
        // Priority 1: Repeat One (respected on natural completion, bypassed on manual skip)
        if (currentRepeatMode == RepeatMode.ONE && !isManualSkip && currentItem != null) {
            return currentItem
        }

        // Priority 2: Next eligible manual queue item (Play Next first, then Add to Queue)
        if (playNextQueue.isNotEmpty()) {
            return playNextQueue.first()
        }
        if (manualQueue.isNotEmpty()) {
            return manualQueue.first()
        }

        // Priority 3: Active Context Tracks
        if (activeContextTracks.isNotEmpty()) {
            return activeContextTracks.first()
        }

        // Priority 4: Repeat All (restart context)
        if (currentRepeatMode == RepeatMode.ALL && originalContextTracks.isNotEmpty()) {
            return originalContextTracks.first()
        }

        // Priority 5: Autoplay Recommendations
        if (isAutoplayEnabled && autoplayQueue.isNotEmpty()) {
            return autoplayQueue.first()
        }

        // Priority 6: End playback gracefully
        return null
    }

    /**
     * Executes skip to next track using the centralized decision logic.
     */
    fun skipToNext() {
        val player = attachedPlayer
        val next = getNextTrack(isManualSkip = true)

        if (next != null) {
            advanceToTrack(next, isNaturalCompletion = false)
        } else {
            // No next track available: end playback cleanly
            player?.stop()
            currentItem?.let { playbackHistory.add(it) }
            currentItem = null
            currentPlaybackState = PlaybackState.ENDED
            emitState()
        }
    }

    /**
     * Executes skip to previous track:
     * If current position > 3 seconds, seeks to beginning of current track.
     * Otherwise pops previous song from playback history.
     */
    fun skipToPrevious() {
        val player = attachedPlayer
        val pos = player?.currentPosition ?: 0L

        if (pos > 3000L) {
            player?.seekTo(0L)
            listeners.forEach { it.onPositionDiscontinuity(0L) }
            return
        }

        if (playbackHistory.isNotEmpty()) {
            val prev = playbackHistory.removeAt(playbackHistory.size - 1)
            // Push current item back to front of Play Next queue
            currentItem?.let { curr ->
                playNextQueue.add(0, curr)
            }
            currentItem = prev
            syncTimelineToPlayer(startPlaying = true)
            emitState()
        } else {
            player?.seekTo(0L)
            listeners.forEach { it.onPositionDiscontinuity(0L) }
        }
    }

    /**
     * Plays a specific queue item immediately.
     */
    fun playQueueItem(queueItem: QueueItem) {
        if (currentItem?.queueEntryId == queueItem.queueEntryId) return
        advanceToTrack(queueItem, isNaturalCompletion = false)
    }

    private fun advanceToTrack(nextItem: QueueItem, isNaturalCompletion: Boolean) {
        val previous = currentItem
        if (previous != null) {
            playbackHistory.add(previous)
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    playlistDbHelper.recordListeningHistory(
                        song = previous.song,
                        playDurationSec = previous.song.durationSec,
                        isCompleted = isNaturalCompletion,
                        isSkipped = !isNaturalCompletion,
                        isMeaningful = isNaturalCompletion || previous.song.durationSec >= 30
                    )
                } catch (_: Exception) {}
            }
        }

        // Remove the chosen item from its respective queue
        when (nextItem.origin) {
            QueueOrigin.PLAY_NEXT -> playNextQueue.removeAll { it.queueEntryId == nextItem.queueEntryId }
            QueueOrigin.ADD_TO_QUEUE -> manualQueue.removeAll { it.queueEntryId == nextItem.queueEntryId }
            QueueOrigin.CONTEXT -> {
                val wasEmpty = activeContextTracks.isEmpty()
                activeContextTracks.removeAll { it.queueEntryId == nextItem.queueEntryId }
                // If restarting context in Repeat All (wasEmpty before selection)
                if (wasEmpty && currentRepeatMode == RepeatMode.ALL) {
                    activeContextTracks.addAll(originalContextTracks)
                    if (isShuffleEnabled) activeContextTracks.shuffle()
                    activeContextTracks.removeAll { it.queueEntryId == nextItem.queueEntryId }
                }
            }
            QueueOrigin.AUTOPLAY -> autoplayQueue.removeAll { it.queueEntryId == nextItem.queueEntryId }
        }

        currentItem = nextItem
        consecutiveErrorCount = 0

        syncTimelineToPlayer(startPlaying = true)
        emitState()

        checkAndPrefetchAutoplayIfNeeded()
    }

    // =========================================================================
    // Shuffle & Repeat Management
    // =========================================================================

    /**
     * Toggles shuffle mode.
     * Shuffles remaining context tracks without interrupting current song or manual queue.
     * Toggling off restores original context order.
     */
    fun toggleShuffle(): Boolean {
        isShuffleEnabled = !isShuffleEnabled
        appSettings.setShuffleMode(if (isShuffleEnabled) AppSettings.SHUFFLE_MODE_SHUFFLE else AppSettings.SHUFFLE_MODE_NORMAL)

        if (isShuffleEnabled) {
            activeContextTracks.shuffle()
        } else {
            // Restore remaining context tracks in original context order
            val remainingIds = activeContextTracks.map { it.song.id }.toSet()
            activeContextTracks.clear()
            for (item in originalContextTracks) {
                if (item.song.id in remainingIds) {
                    activeContextTracks.add(item)
                }
            }
        }

        syncUpcomingToPlayer()
        emitState()
        return isShuffleEnabled
    }

    /**
     * Cycles repeat mode: OFF -> ALL -> ONE -> OFF.
     */
    fun cycleRepeatMode(): RepeatMode {
        currentRepeatMode = when (currentRepeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }

        attachedPlayer?.let { syncSettingsToPlayer(it) }
        emitState()
        return currentRepeatMode
    }

    fun isShuffleEnabled(): Boolean = isShuffleEnabled

    fun getRepeatMode(): RepeatMode = currentRepeatMode

    fun setRepeatMode(mode: RepeatMode) {
        currentRepeatMode = mode
        attachedPlayer?.let { syncSettingsToPlayer(it) }
        emitState()
    }

    // =========================================================================
    // Autoplay Controls & Recommendations
    // =========================================================================

    fun setAutoplayEnabled(enabled: Boolean) {
        isAutoplayEnabled = enabled
        appSettings.setAutoplayEnabled(enabled)

        if (!enabled) {
            autoplayQueue.clear()
            syncUpcomingToPlayer()
        } else {
            checkAndPrefetchAutoplayIfNeeded(force = true)
        }

        emitState()
    }

    fun isAutoplayEnabled(): Boolean = isAutoplayEnabled

    private fun checkAndPrefetchAutoplayIfNeeded(force: Boolean = false) {
        if (!isAutoplayEnabled || isFetchingAutoplay) return
        val remainingUpcoming = playNextQueue.size + manualQueue.size + activeContextTracks.size + autoplayQueue.size
        if (remainingUpcoming > 3 && !force) return

        val seed = currentItem?.song ?: return
        isFetchingAutoplay = true

        coroutineScope.launch {
            try {
                val baseUrl = appSettings.getBaseUrl()
                val currentQueueSongIds = buildSet {
                    currentItem?.let { add(it.song.id) }
                    playNextQueue.forEach { add(it.song.id) }
                    manualQueue.forEach { add(it.song.id) }
                    activeContextTracks.forEach { add(it.song.id) }
                    autoplayQueue.forEach { add(it.song.id) }
                    addAll(failedSongIdsInSession)
                }

                val recs = withContext(Dispatchers.IO) {
                    recommendationEngine?.getRecommendations(
                        baseUrl = baseUrl,
                        seedSong = seed,
                        excludeSongIds = currentQueueSongIds + recentRecommendedSongIds,
                        limit = 6,
                        personalizationEnabled = appSettings.isPersonalizationEnabled(),
                        avoidRecentlyPlayed = appSettings.shouldAvoidRecentlyPlayed(),
                        diversityMode = appSettings.getRecommendationDiversity()
                    ) ?: emptyList()
                }

                if (recs.isNotEmpty()) {
                    val newItems = recs.map { recSong ->
                        recentRecommendedSongIds.add(recSong.id)
                        while (recentRecommendedSongIds.size > 40) {
                            val first = recentRecommendedSongIds.iterator().next()
                            recentRecommendedSongIds.remove(first)
                        }
                        QueueItem(
                            song = recSong,
                            origin = QueueOrigin.AUTOPLAY,
                            contextTitle = "Similar to ${seed.title}"
                        )
                    }

                    autoplayQueue.addAll(newItems)
                    syncUpcomingToPlayer()
                    emitState()
                }
            } catch (_: Exception) {
                // Silently fallback if network unreachable
            } finally {
                isFetchingAutoplay = false
            }
        }
    }

    // =========================================================================
    // Queue Reordering & Item Manipulation
    // =========================================================================

    /**
     * Reorders an item within the upcoming queue.
     * fromUpcomingIndex and toUpcomingIndex are 0-based indices within allUpcoming().
     */
    fun reorderQueue(fromUpcomingIndex: Int, toUpcomingIndex: Int): Boolean {
        val upcoming = buildAllUpcoming().toMutableList()
        if (fromUpcomingIndex !in upcoming.indices || toUpcomingIndex !in upcoming.indices) return false

        val item = upcoming.removeAt(fromUpcomingIndex)
        upcoming.add(toUpcomingIndex, item)

        // Partition back to queue categories
        reconstructQueuesFromUpcoming(upcoming)
        syncUpcomingToPlayer()
        emitState()
        return true
    }

    /**
     * Removes an item from the upcoming queue by its unique queueEntryId.
     */
    fun removeQueueItem(queueEntryId: String): Boolean {
        var removed = playNextQueue.removeAll { it.queueEntryId == queueEntryId }
        if (!removed) removed = manualQueue.removeAll { it.queueEntryId == queueEntryId }
        if (!removed) removed = activeContextTracks.removeAll { it.queueEntryId == queueEntryId }
        if (!removed) removed = autoplayQueue.removeAll { it.queueEntryId == queueEntryId }

        if (removed) {
            syncUpcomingToPlayer()
            emitState()
        }
        return removed
    }

    /**
     * Moves any upcoming track to the top of the Play Next queue.
     */
    fun moveToPlayNext(queueEntryId: String): Boolean {
        val item = findUpcomingItem(queueEntryId) ?: return false
        removeQueueItem(queueEntryId)

        val updatedItem = item.copy(origin = QueueOrigin.PLAY_NEXT, contextTitle = "Play Next")
        playNextQueue.add(0, updatedItem)

        syncUpcomingToPlayer()
        emitState()
        return true
    }

    /**
     * Clears upcoming tracks.
     * If includeContext is true, also clears remaining context tracks.
     * Currently playing track is never interrupted.
     */
    fun clearUpcoming(includeContext: Boolean = false) {
        playNextQueue.clear()
        manualQueue.clear()
        autoplayQueue.clear()

        if (includeContext) {
            activeContextTracks.clear()
        }

        syncUpcomingToPlayer()
        emitState()
    }

    fun findUpcomingItem(queueEntryId: String): QueueItem? {
        return playNextQueue.find { it.queueEntryId == queueEntryId }
            ?: manualQueue.find { it.queueEntryId == queueEntryId }
            ?: activeContextTracks.find { it.queueEntryId == queueEntryId }
            ?: autoplayQueue.find { it.queueEntryId == queueEntryId }
    }

    // =========================================================================
    // Error Handling & Stream URL Resilience
    // =========================================================================

    /**
     * Handles playback errors gracefully with candidate fallback and bounded retry.
     */
    fun handlePlayerError(error: PlaybackException) {
        consecutiveErrorCount++
        val failedSong = currentItem?.song

        if (failedSong != null) {
            failedSongIdsInSession.add(failedSong.id)
            android.util.Log.w("CentralizedPlaybackController", "Stream error for '${failedSong.title}': ${error.errorCodeName}")
        }

        if (consecutiveErrorCount >= 3) {
            android.util.Log.e("CentralizedPlaybackController", "Bounded retry limit (3) exceeded. Halting playback.")
            attachedPlayer?.stop()
            currentItem?.let { playbackHistory.add(it) }
            currentItem = null
            currentPlaybackState = PlaybackState.ERROR
            emitState()
            return
        }

        // Advance to next available track
        skipToNext()
    }

    // =========================================================================
    // Internal Timeline Synchronization
    // =========================================================================

    private fun syncTimelineToPlayer(startPlaying: Boolean) {
        val player = attachedPlayer ?: return
        val current = currentItem ?: return

        val fullList = mutableListOf<QueueItem>()
        fullList.add(current)
        fullList.addAll(buildAllUpcoming())

        val mediaItems = fullList.map { toMediaItem(it) }

        isInternalPlayerUpdate = true
        player.stop()
        player.setMediaItems(mediaItems, 0, 0L)
        player.prepare()
        if (startPlaying) {
            player.play()
        }
        isInternalPlayerUpdate = false
    }

    private fun syncUpcomingToPlayer() {
        val player = attachedPlayer ?: return
        val currIndex = player.currentMediaItemIndex
        if (currIndex < 0) return

        val upcomingItems = buildAllUpcoming().map { toMediaItem(it) }

        isInternalPlayerUpdate = true
        // Remove all items after currIndex
        val totalCount = player.mediaItemCount
        if (totalCount > currIndex + 1) {
            player.removeMediaItems(currIndex + 1, totalCount)
        }
        // Add updated upcoming items
        if (upcomingItems.isNotEmpty()) {
            player.addMediaItems(currIndex + 1, upcomingItems)
        }
        isInternalPlayerUpdate = false
    }

    private fun buildAllUpcoming(): List<QueueItem> {
        val list = mutableListOf<QueueItem>()
        list.addAll(playNextQueue)
        list.addAll(manualQueue)
        list.addAll(activeContextTracks)
        if (isAutoplayEnabled && currentRepeatMode != RepeatMode.ALL) {
            list.addAll(autoplayQueue)
        }
        return list
    }

    private fun reconstructQueuesFromUpcoming(upcoming: List<QueueItem>) {
        playNextQueue.clear()
        manualQueue.clear()
        activeContextTracks.clear()
        autoplayQueue.clear()

        for (item in upcoming) {
            when (item.origin) {
                QueueOrigin.PLAY_NEXT -> playNextQueue.add(item)
                QueueOrigin.ADD_TO_QUEUE -> manualQueue.add(item)
                QueueOrigin.CONTEXT -> activeContextTracks.add(item)
                QueueOrigin.AUTOPLAY -> autoplayQueue.add(item)
            }
        }
    }

    private fun toMediaItem(queueItem: QueueItem): MediaItem {
        val originStr = when (queueItem.origin) {
            QueueOrigin.PLAY_NEXT, QueueOrigin.ADD_TO_QUEUE -> SongMediaItemMapper.ORIGIN_MANUAL
            QueueOrigin.CONTEXT -> SongMediaItemMapper.ORIGIN_CONTEXT
            QueueOrigin.AUTOPLAY -> SongMediaItemMapper.ORIGIN_AUTOPLAY
        }

        return SongMediaItemMapper.toMediaItem(
            song = queueItem.song,
            origin = originStr,
            contextName = queueItem.contextTitle,
            isRecommended = (queueItem.origin == QueueOrigin.AUTOPLAY)
        )
    }

    private fun buildSnapshot(): QueueSnapshot {
        return QueueSnapshot(
            current = currentItem,
            playNext = playNextQueue.toList(),
            manualQueue = manualQueue.toList(),
            contextTracks = activeContextTracks.toList(),
            autoplayQueue = autoplayQueue.toList(),
            history = playbackHistory.toList(),
            isShuffle = isShuffleEnabled,
            repeatMode = currentRepeatMode,
            autoplayEnabled = isAutoplayEnabled,
            playbackContext = activeContext
        )
    }

    private fun emitState() {
        val snapshot = buildSnapshot()
        _queueSnapshot.value = snapshot
        val notifyBlock = Runnable {
            listeners.forEach { listener ->
                listener.onQueueUpdated(snapshot)
                listener.onSongChanged(currentItem?.song, currentItem)
                listener.onPlaybackStateChanged(currentPlaybackState)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyBlock.run()
        } else {
            mainHandler.post(notifyBlock)
        }
    }

    // =========================================================================
    // Player.Listener integration
    // =========================================================================

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            currentPlaybackState = if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
            emitState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> currentPlaybackState = PlaybackState.BUFFERING
                Player.STATE_READY -> currentPlaybackState = if (attachedPlayer?.isPlaying == true) PlaybackState.PLAYING else PlaybackState.PAUSED
                Player.STATE_ENDED -> {
                    currentPlaybackState = PlaybackState.ENDED
                    // Natural track completion: advance per priority
                    val next = getNextTrack(isManualSkip = false)
                    if (next != null) {
                        advanceToTrack(next, isNaturalCompletion = true)
                    }
                }
                Player.STATE_IDLE -> currentPlaybackState = PlaybackState.IDLE
            }
            emitState()
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isInternalPlayerUpdate) return

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // Natural track advance in ExoPlayer
                val next = getNextTrack(isManualSkip = false)
                if (next != null) {
                    advanceToTrack(next, isNaturalCompletion = true)
                }
            } else if (mediaItem != null) {
                val song = SongMediaItemMapper.fromMediaItem(mediaItem)
                if (currentItem?.song?.id != song.id) {
                    val matching = findUpcomingItem(mediaItem.mediaId)
                    if (matching != null) {
                        advanceToTrack(matching, isNaturalCompletion = false)
                    }
                }
            }
        }
    }
}
