package com.braveplayer.music.jam.service

import android.content.Context
import androidx.media3.common.Player
import com.braveplayer.music.api.MusicMetadataService
import com.braveplayer.music.jam.model.*
import com.braveplayer.music.jam.network.JamApiService
import com.braveplayer.music.jam.network.JamSocketManager
import com.braveplayer.music.jam.security.JamKeyStoreManager
import com.braveplayer.music.jam.sync.JamSyncManager
import com.braveplayer.music.model.Song
import com.braveplayer.music.player.CentralizedPlaybackController
import com.braveplayer.music.settings.FilterEngineProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class JamDriftInfo(
    val driftMs: Long = 0L,
    val isCorrecting: Boolean = false,
    val rttMs: Long = 0L
)

/**
 * Main coordinator for Melody Jam session lifecycle, real-time events,
 * and playback synchronization.
 */
class JamSessionManager(
    private val context: Context,
    private val keyStoreManager: JamKeyStoreManager = JamKeyStoreManager(context),
    private val apiService: JamApiService = JamApiService(),
    private val socketManager: JamSocketManager = JamSocketManager(),
    private val syncManager: JamSyncManager = JamSyncManager(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : JamSocketManager.Listener {

    companion object {
        @Volatile
        private var INSTANCE: JamSessionManager? = null

        fun getInstance(context: Context): JamSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JamSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun setInstanceForTesting(manager: JamSessionManager?) {
            INSTANCE = manager
        }
    }

    private val playbackController = CentralizedPlaybackController.getInstance(context)
    private val metadataService = MusicMetadataService(FilterEngineProvider.filterListManager)

    private val _sessionState = MutableStateFlow(JamSessionState.DISCONNECTED)
    val sessionState: StateFlow<JamSessionState> = _sessionState.asStateFlow()

    private val _currentRoom = MutableStateFlow<JamRoom?>(null)
    val currentRoom: StateFlow<JamRoom?> = _currentRoom.asStateFlow()

    private val _myRole = MutableStateFlow<JamRole?>(null)
    val myRole: StateFlow<JamRole?> = _myRole.asStateFlow()

    private val _reactionsFlow = MutableSharedFlow<JamReaction>(extraBufferCapacity = 16)
    val reactionsFlow: SharedFlow<JamReaction> = _reactionsFlow.asSharedFlow()

    private val _driftInfo = MutableStateFlow(JamDriftInfo())
    val driftInfo: StateFlow<JamDriftInfo> = _driftInfo.asStateFlow()

    private val appSettings = com.braveplayer.music.settings.AppSettings(context)
    private var hostServerIp: String = appSettings.getJamServerHost()
    private var hostServerPort: Int = appSettings.getJamServerPort()

    // Track state to avoid feedback loops
    private var isApplyingRemoteUpdate = false
    private var attachedPlayer: Player? = null
    private var lastSentSongId: String? = null

    init {
        socketManager.addListener(this)

        syncManager.setOnSyncRequestListener {
            socketManager.sendRequestSync()
        }

        syncManager.setOnDriftReported { drift, isCorrecting ->
            _driftInfo.value = JamDriftInfo(
                driftMs = drift,
                isCorrecting = isCorrecting,
                rttMs = syncManager.getEstimatedRtt()
            )
        }

        playbackController.addListener(object : CentralizedPlaybackController.Listener {
            override fun onSongChanged(song: Song?, queueItem: com.braveplayer.music.player.QueueItem?) {
                if (isHost() && song != null && !isApplyingRemoteUpdate) {
                    if (song.id != lastSentSongId) {
                        lastSentSongId = song.id
                        val pos = attachedPlayer?.currentPosition ?: 0L
                        val isPlaying = attachedPlayer?.isPlaying == true && attachedPlayer?.playWhenReady == true
                        sendTrackChanged(song, pos, isPlaying)
                    }
                }
            }

            override fun onPlaybackStateChanged(state: com.braveplayer.music.player.PlaybackState) {
                if (isHost() && !isApplyingRemoteUpdate) {
                    val pos = attachedPlayer?.currentPosition ?: 0L
                    when (state) {
                        com.braveplayer.music.player.PlaybackState.PLAYING -> sendPlay(pos)
                        com.braveplayer.music.player.PlaybackState.PAUSED -> sendPause(pos)
                        com.braveplayer.music.player.PlaybackState.ENDED -> {
                            val nextQueued = _currentRoom.value?.queue?.firstOrNull()
                            if (nextQueued != null) {
                                playTrackFromQueue(nextQueued)
                            }
                        }
                        else -> {}
                    }
                }
            }

            override fun onPositionDiscontinuity(positionMs: Long) {
                if (isHost() && !isApplyingRemoteUpdate) {
                    sendSeek(positionMs)
                }
            }
        })
    }

    fun setServerEndpoint(host: String, port: Int) {
        this.hostServerIp = host
        this.hostServerPort = port
        appSettings.setJamServerHost(host)
        appSettings.setJamServerPort(port)
    }

    fun getDisplayName(): String = keyStoreManager.getDisplayName()

    fun getMyGuestId(): String? = keyStoreManager.getGuestId()

    fun updateDisplayName(name: String) {
        keyStoreManager.setDisplayName(name)
    }

    fun isHost(): Boolean = _myRole.value == JamRole.HOST
    fun isInJam(): Boolean = _currentRoom.value != null

    fun attachPlayer(player: Player) {
        this.attachedPlayer = player
        syncManager.attachPlayer(player, isHost = isHost())
    }

    fun detachPlayer() {
        this.attachedPlayer = null
        syncManager.detachPlayer()
    }

    /**
     * Authenticates guest session if token is missing or expired.
     */
    private suspend fun ensureAuthenticatedSession(): Result<String> {
        val existingToken = keyStoreManager.getGuestToken()
        if (!existingToken.isNullOrBlank()) {
            return Result.success(existingToken)
        }

        val installId = keyStoreManager.getOrCreateInstallationId()
        val displayName = keyStoreManager.getDisplayName()

        val result = apiService.createOrRenewGuestSession(installId, displayName, hostServerIp, hostServerPort)
        return result.map { session ->
            keyStoreManager.saveSession(session.guestId, session.token, session.expiresAt)
            session.token
        }
    }

    // =========================================================================
    // Jam Room Actions
    // =========================================================================

    fun createJam(initialSong: Song? = null, onResult: ((Result<JamRoom>) -> Unit)? = null) {
        coroutineScope.launch {
            _sessionState.value = JamSessionState.CONNECTING
            val tokenResult = ensureAuthenticatedSession()

            if (tokenResult.isFailure) {
                _sessionState.value = JamSessionState.ERROR
                onResult?.invoke(Result.failure(tokenResult.exceptionOrNull() ?: Exception("Authentication failed")))
                return@launch
            }

            val token = tokenResult.getOrThrow()
            val wsUrl = apiService.getBaseWsUrl(hostServerIp, hostServerPort)

            var isCompleted = false
            val listener = object : JamSocketManager.Listener {
                override fun onJamCreated(room: JamRoom, role: JamRole) {
                    if (isCompleted) return
                    isCompleted = true
                    socketManager.removeListener(this)
                    onResult?.invoke(Result.success(room))
                }

                override fun onError(code: String, message: String) {
                    if (isCompleted) return
                    isCompleted = true
                    socketManager.removeListener(this)
                    onResult?.invoke(Result.failure(Exception("$code: $message")))
                }

                override fun onDisconnected(reason: String) {
                    if (reason.contains("401") || reason.contains("Unauthorized", ignoreCase = true)) {
                        keyStoreManager.clearSession()
                    }
                    if (!isCompleted && _currentRoom.value == null) {
                        isCompleted = true
                        socketManager.removeListener(this)
                        onResult?.invoke(Result.failure(Exception("Connection failed: $reason")))
                    }
                }
            }
            socketManager.addListener(listener)

            // Watchdog timeout after 45 seconds for cloud cold-start tolerance
            coroutineScope.launch {
                delay(45_000L)
                if (!isCompleted) {
                    isCompleted = true
                    socketManager.removeListener(listener)
                    onResult?.invoke(Result.failure(Exception("Connection timed out. The cloud server is waking up; please retry in a few seconds.")))
                }
            }

            socketManager.connect(wsUrl, token)

            // Song summary
            val summary = initialSong?.let { JamSongSummary.fromSong(it) }
                ?: playbackController.getCurrentSong()?.let { JamSongSummary.fromSong(it) }

            socketManager.sendCreateJam(summary)
        }
    }

    fun joinJam(roomCodeOrId: String, onResult: ((Result<JamRoom>) -> Unit)? = null) {
        coroutineScope.launch {
            _sessionState.value = JamSessionState.CONNECTING
            val tokenResult = ensureAuthenticatedSession()

            if (tokenResult.isFailure) {
                _sessionState.value = JamSessionState.ERROR
                onResult?.invoke(Result.failure(tokenResult.exceptionOrNull() ?: Exception("Authentication failed")))
                return@launch
            }

            val rawTrimmed = roomCodeOrId.trim().uppercase()
            val cleanCode = if (rawTrimmed.length == 4 && !rawTrimmed.startsWith("JAM-")) "JAM-$rawTrimmed" else rawTrimmed

            val token = tokenResult.getOrThrow()
            val wsUrl = apiService.getBaseWsUrl(hostServerIp, hostServerPort)

            var isCompleted = false
            val listener = object : JamSocketManager.Listener {
                override fun onJamJoined(room: JamRoom, role: JamRole, isReconnecting: Boolean) {
                    if (isCompleted) return
                    isCompleted = true
                    socketManager.removeListener(this)
                    onResult?.invoke(Result.success(room))
                }

                override fun onError(code: String, message: String) {
                    if (isCompleted) return
                    isCompleted = true
                    socketManager.removeListener(this)
                    onResult?.invoke(Result.failure(Exception("$code: $message")))
                }

                override fun onDisconnected(reason: String) {
                    if (reason.contains("401") || reason.contains("Unauthorized", ignoreCase = true)) {
                        keyStoreManager.clearSession()
                    }
                    if (!isCompleted && _currentRoom.value == null) {
                        isCompleted = true
                        socketManager.removeListener(this)
                        onResult?.invoke(Result.failure(Exception("Connection failed: $reason")))
                    }
                }
            }
            socketManager.addListener(listener)

            coroutineScope.launch {
                delay(45_000L)
                if (!isCompleted) {
                    isCompleted = true
                    socketManager.removeListener(listener)
                    onResult?.invoke(Result.failure(Exception("Connection timed out. Check room code or server status.")))
                }
            }

            socketManager.connect(wsUrl, token)
            socketManager.sendJoinJam(cleanCode)
        }
    }

    fun playTrackFromQueue(queueItem: JamQueueItem) {
        if (!isInJam() || !isHost()) return
        val song = queueItem.songSummary.toSong()
        removeFromQueue(queueItem.queueItemId)
        playbackController.playSingleSong(song, playImmediately = true)
    }

    fun leaveJam() {
        socketManager.sendLeaveJam()
        socketManager.disconnect()
        _currentRoom.value = null
        _myRole.value = null
        _sessionState.value = JamSessionState.DISCONNECTED
        syncManager.detachPlayer()
    }

    // =========================================================================
    // Playback Synchronization Actions
    // =========================================================================

    fun sendPlay(positionMs: Long) {
        if (!isInJam()) return
        if (isHost() || _currentRoom.value?.settings?.allowGuestControl == true) {
            _currentRoom.value = _currentRoom.value?.let { room ->
                room.copy(playbackState = room.playbackState.copy(isPlaying = true, positionMs = positionMs))
            }
            socketManager.sendPlay(positionMs)
        }
    }

    fun sendPause(positionMs: Long) {
        if (!isInJam()) return
        if (isHost() || _currentRoom.value?.settings?.allowGuestControl == true) {
            _currentRoom.value = _currentRoom.value?.let { room ->
                room.copy(playbackState = room.playbackState.copy(isPlaying = false, positionMs = positionMs))
            }
            socketManager.sendPause(positionMs)
        }
    }

    fun sendSeek(positionMs: Long) {
        if (!isInJam()) return
        if (isHost() || _currentRoom.value?.settings?.allowGuestControl == true) {
            _currentRoom.value = _currentRoom.value?.let { room ->
                room.copy(playbackState = room.playbackState.copy(positionMs = positionMs))
            }
            socketManager.sendSeek(positionMs)
        }
    }

    fun sendTrackChanged(song: Song, positionMs: Long = 0L, isPlaying: Boolean = true) {
        if (!isInJam()) return
        if (isApplyingRemoteUpdate) return
        if (isHost() || _currentRoom.value?.settings?.allowGuestControl == true) {
            lastSentSongId = song.id
            val summary = JamSongSummary.fromSong(song)
            socketManager.sendTrackChanged(song.id, summary, positionMs, isPlaying)
        }
    }

    fun updateRoomSettings(allowGuestQueueAdd: Boolean? = null, allowGuestControl: Boolean? = null) {
        val room = _currentRoom.value ?: return
        if (!isHost()) return

        val current = room.settings
        val updated = current.copy(
            allowGuestQueueAdd = allowGuestQueueAdd ?: current.allowGuestQueueAdd,
            allowGuestControl = allowGuestControl ?: current.allowGuestControl
        )
        _currentRoom.value = room.copy(settings = updated)
        socketManager.sendUpdateRoomSettings(updated)
    }

    fun addToQueue(song: Song) {
        if (!isInJam()) return
        val summary = JamSongSummary.fromSong(song)
        socketManager.sendAddToQueue(summary)
    }

    fun removeFromQueue(queueItemId: String) {
        if (!isInJam()) return
        socketManager.sendRemoveFromQueue(queueItemId)
    }

    fun reorderQueue(fromIdx: Int, toIdx: Int) {
        if (!isInJam()) return
        if (isHost()) {
            socketManager.sendReorderQueue(fromIdx, toIdx)
        }
    }

    fun sendReaction(emoji: String) {
        if (!isInJam()) return
        socketManager.sendReaction(emoji)
    }

    // =========================================================================
    // JamSocketManager.Listener Implementations
    // =========================================================================

    override fun onConnected() {
        if (_sessionState.value == JamSessionState.CONNECTING || _sessionState.value == JamSessionState.RECONNECTING) {
            _sessionState.value = JamSessionState.CONNECTED
        }
    }

    override fun onDisconnected(reason: String) {
        if (_currentRoom.value != null) {
            _sessionState.value = JamSessionState.RECONNECTING
        } else {
            _sessionState.value = JamSessionState.DISCONNECTED
        }
    }

    override fun onJamCreated(room: JamRoom, role: JamRole) {
        _currentRoom.value = room
        _myRole.value = role
        _sessionState.value = JamSessionState.IN_ROOM
        syncManager.setIsHost(true)
    }

    override fun onJamJoined(room: JamRoom, role: JamRole, isReconnecting: Boolean) {
        _currentRoom.value = room
        _myRole.value = role
        _sessionState.value = JamSessionState.IN_ROOM
        syncManager.setIsHost(role == JamRole.HOST)

        // If guest joined or reconnected, sync with room's active track immediately
        if (role == JamRole.GUEST) {
            synchronizeGuestToRoomPlayback(room.playbackState)
        }
    }

    override fun onPlaybackUpdated(playbackState: JamPlaybackState, action: String, triggeredByGuestId: String) {
        val room = _currentRoom.value ?: return
        _currentRoom.value = room.copy(playbackState = playbackState)

        if (!isHost()) {
            syncManager.onPlaybackStateUpdated(playbackState)

            // If track changed remotely to a different track
            if (action == "TRACK_CHANGED" && playbackState.trackId != null && playbackState.trackId != lastSentSongId) {
                lastSentSongId = playbackState.trackId
                synchronizeGuestToRoomPlayback(playbackState)
            }
        }
    }

    override fun onRoomSettingsUpdated(settings: JamRoomSettings) {
        val room = _currentRoom.value ?: return
        _currentRoom.value = room.copy(settings = settings)
    }

    override fun onQueueUpdated(queue: List<JamQueueItem>) {
        val room = _currentRoom.value ?: return
        _currentRoom.value = room.copy(queue = queue)
    }

    override fun onParticipantJoined(participant: JamParticipant, totalCount: Int) {
        val room = _currentRoom.value ?: return
        val updated = room.participants.filter { it.guestId != participant.guestId } + participant
        _currentRoom.value = room.copy(participants = updated)
    }

    override fun onParticipantLeft(guestId: String, name: String, isHost: Boolean, totalCount: Int) {
        val room = _currentRoom.value ?: return
        val updated = room.participants.filter { it.guestId != guestId }
        _currentRoom.value = room.copy(participants = updated)
    }

    override fun onReactionReceived(reaction: JamReaction) {
        coroutineScope.launch {
            _reactionsFlow.emit(reaction)
        }
    }

    override fun onHostChanged(newHostGuestId: String, newHostName: String) {
        val room = _currentRoom.value ?: return
        val myGuestId = keyStoreManager.getGuestId()
        val isMeNewHost = (myGuestId == newHostGuestId)

        val updatedParticipants = room.participants.map { p ->
            if (p.guestId == newHostGuestId) p.copy(role = JamRole.HOST) else p.copy(role = JamRole.GUEST)
        }

        _myRole.value = if (isMeNewHost) JamRole.HOST else JamRole.GUEST
        syncManager.setIsHost(isMeNewHost)
        _currentRoom.value = room.copy(
            hostGuestId = newHostGuestId,
            participants = updatedParticipants
        )
    }

    override fun onJamEnded(reason: String) {
        leaveJam()
    }

    override fun onSyncResponse(response: JamSyncResponse) {
        syncManager.processSyncResponse(response)
    }

    override fun onError(code: String, message: String) {
        // Logged or handled by caller
    }

    /**
     * Resolves and synchronizes the guest's local player with the Jam room's track and position.
     */
    private fun synchronizeGuestToRoomPlayback(playbackState: JamPlaybackState) {
        val trackId = playbackState.trackId ?: return
        val currentSong = playbackController.getCurrentSong()

        coroutineScope.launch(Dispatchers.Main) {
            val candidateSong = playbackState.songSummary?.toSong()
            val songToPlay: Song? = if (currentSong?.id == trackId && currentSong.getBestPlaybackUrl() != null) {
                currentSong
            } else if (candidateSong?.getBestPlaybackUrl() != null) {
                candidateSong
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        metadataService.fetchSongById(trackId)
                    } catch (_: Exception) {
                        null
                    }
                } ?: candidateSong
            }

            if (songToPlay != null) {
                isApplyingRemoteUpdate = true

                if (playbackController.getCurrentSong()?.id != songToPlay.id) {
                    playbackController.playSingleSong(songToPlay, playImmediately = playbackState.isPlaying)
                }

                delay(150)
                syncManager.evaluateDriftAndCorrect()
                isApplyingRemoteUpdate = false
            }
        }
    }
}
