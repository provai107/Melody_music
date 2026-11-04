package com.braveplayer.music.jam.network

import com.braveplayer.music.jam.model.*
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JamSocketManager(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    interface Listener {
        fun onConnected() {}
        fun onDisconnected(reason: String) {}
        fun onJamCreated(room: JamRoom, role: JamRole) {}
        fun onJamJoined(room: JamRoom, role: JamRole, isReconnecting: Boolean) {}
        fun onPlaybackUpdated(playbackState: JamPlaybackState, action: String, triggeredByGuestId: String) {}
        fun onQueueUpdated(queue: List<JamQueueItem>) {}
        fun onParticipantJoined(participant: JamParticipant, totalCount: Int) {}
        fun onParticipantLeft(guestId: String, name: String, isHost: Boolean, totalCount: Int) {}
        fun onReactionReceived(reaction: JamReaction) {}
        fun onHostChanged(newHostGuestId: String, newHostName: String) {}
        fun onJamEnded(reason: String) {}
        fun onSyncResponse(response: JamSyncResponse) {}
        fun onError(code: String, message: String) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var webSocket: WebSocket? = null
    private var currentWsUrl: String? = null
    private var currentToken: String? = null
    private val isConnecting = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val isManuallyClosed = AtomicBoolean(false)
    private val pendingQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    fun isConnected(): Boolean = isConnected.get()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun connect(wsUrl: String, token: String) {
        isManuallyClosed.set(false)
        currentWsUrl = wsUrl
        currentToken = token
        establishConnection()
    }

    private fun establishConnection() {
        val url = currentWsUrl ?: return
        val token = currentToken ?: return
        if (isConnected.get()) return
        if (isConnecting.getAndSet(true)) return

        val fullUrl = if (url.contains("?")) "$url&token=$token" else "$url?token=$token"
        val request = Request.Builder().url(fullUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnecting.set(false)
                isConnected.set(true)
                reconnectAttempts = 0
                startHeartbeat()
                flushPendingQueue()
                listeners.forEach { it.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnecting.set(false)
                isConnected.set(false)
                stopHeartbeat()
                listeners.forEach { it.onDisconnected(reason) }
                if (!isManuallyClosed.get()) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnecting.set(false)
                isConnected.set(false)
                stopHeartbeat()
                listeners.forEach { it.onDisconnected(t.message ?: "Connection error") }
                if (!isManuallyClosed.get()) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun flushPendingQueue() {
        val ws = webSocket ?: return
        while (isConnected.get() && !pendingQueue.isEmpty()) {
            val msg = pendingQueue.poll() ?: break
            ws.send(msg)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope.launch {
            reconnectAttempts++
            val backoffMs = (1000L * (1L shl (reconnectAttempts.coerceAtMost(4)))).coerceAtMost(15000L)
            delay(backoffMs)
            if (!isManuallyClosed.get() && !isConnected.get()) {
                establishConnection()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = coroutineScope.launch {
            while (isActive) {
                delay(15_000L)
                sendHeartbeat()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun disconnect() {
        isManuallyClosed.set(true)
        isConnected.set(false)
        pendingQueue.clear()
        reconnectJob?.cancel()
        stopHeartbeat()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnecting.set(false)
    }

    // =========================================================================
    // Outgoing Event Dispatchers
    // =========================================================================

    private fun sendJson(event: String, data: JSONObject = JSONObject()) {
        val payload = JSONObject().apply {
            put("event", event)
            put("data", data)
            put("requestId", "req_" + UUID.randomUUID().toString().take(8))
            put("clientTimestamp", System.currentTimeMillis())
        }
        val text = payload.toString()
        val ws = webSocket
        if (isConnected.get() && ws != null) {
            ws.send(text)
        } else {
            pendingQueue.add(text)
        }
    }

    fun sendHeartbeat() {
        sendJson("HEARTBEAT", JSONObject().apply {
            put("clientTimestamp", System.currentTimeMillis())
        })
    }

    fun sendCreateJam(initialTrack: JamSongSummary? = null) {
        val data = JSONObject()
        if (initialTrack != null) {
            data.put("initialTrack", serializeSongSummary(initialTrack))
        }
        sendJson("CREATE_JAM", data)
    }

    fun sendJoinJam(roomCodeOrId: String) {
        val data = JSONObject().apply {
            put("roomCode", roomCodeOrId.trim().uppercase())
        }
        sendJson("JOIN_JAM", data)
    }

    fun sendLeaveJam() {
        sendJson("LEAVE_JAM", JSONObject())
    }

    fun sendPlay(positionMs: Long) {
        sendJson("PLAY", JSONObject().apply {
            put("positionMs", positionMs)
        })
    }

    fun sendPause(positionMs: Long) {
        sendJson("PAUSE", JSONObject().apply {
            put("positionMs", positionMs)
        })
    }

    fun sendSeek(positionMs: Long) {
        sendJson("SEEK", JSONObject().apply {
            put("positionMs", positionMs)
        })
    }

    fun sendTrackChanged(trackId: String, summary: JamSongSummary?, positionMs: Long = 0L, isPlaying: Boolean = true) {
        sendJson("TRACK_CHANGED", JSONObject().apply {
            put("trackId", trackId)
            if (summary != null) {
                put("songSummary", serializeSongSummary(summary))
            }
            put("positionMs", positionMs)
            put("isPlaying", isPlaying)
        })
    }

    fun sendAddToQueue(songSummary: JamSongSummary) {
        sendJson("ADD_TO_QUEUE", JSONObject().apply {
            put("songSummary", serializeSongSummary(songSummary))
        })
    }

    fun sendRemoveFromQueue(queueItemId: String) {
        sendJson("REMOVE_FROM_QUEUE", JSONObject().apply {
            put("queueItemId", queueItemId)
        })
    }

    fun sendReorderQueue(fromIdx: Int, toIdx: Int) {
        sendJson("REORDER_QUEUE", JSONObject().apply {
            put("fromIndex", fromIdx)
            put("toIndex", toIdx)
        })
    }

    fun sendReaction(emoji: String) {
        sendJson("SEND_REACTION", JSONObject().apply {
            put("emoji", emoji)
        })
    }

    fun sendRequestSync(clientTimestamp: Long = System.currentTimeMillis()) {
        sendJson("REQUEST_SYNC", JSONObject().apply {
            put("clientTimestamp", clientTimestamp)
        })
    }

    // =========================================================================
    // Incoming Message Parser
    // =========================================================================

    private fun handleIncomingMessage(text: String) {
        try {
            val root = JSONObject(text)
            val event = root.optString("event")
            val data = root.optJSONObject("data") ?: JSONObject()

            when (event) {
                "JAM_CREATED" -> {
                    val roomObj = data.getJSONObject("room")
                    val room = parseRoom(roomObj)
                    val role = if (data.optString("myRole") == "HOST") JamRole.HOST else JamRole.GUEST
                    listeners.forEach { it.onJamCreated(room, role) }
                }

                "JAM_JOINED" -> {
                    val roomObj = data.getJSONObject("room")
                    val room = parseRoom(roomObj)
                    val role = if (data.optString("myRole") == "HOST") JamRole.HOST else JamRole.GUEST
                    val isReconnecting = data.optBoolean("isReconnecting", false)
                    listeners.forEach { it.onJamJoined(room, role, isReconnecting) }
                }

                "PLAYBACK_UPDATED" -> {
                    val pbObj = data.getJSONObject("playbackState")
                    val pbState = parsePlaybackState(pbObj)
                    val action = data.optString("action", "")
                    val triggeredBy = data.optString("triggeredByGuestId", "")
                    listeners.forEach { it.onPlaybackUpdated(pbState, action, triggeredBy) }
                }

                "QUEUE_UPDATED" -> {
                    val qArray = data.optJSONArray("queue") ?: JSONArray()
                    val queue = parseQueue(qArray)
                    listeners.forEach { it.onQueueUpdated(queue) }
                }

                "PARTICIPANT_JOINED" -> {
                    val pObj = data.getJSONObject("participant")
                    val participant = parseParticipant(pObj)
                    val count = data.optInt("participantCount", 1)
                    listeners.forEach { it.onParticipantJoined(participant, count) }
                }

                "PARTICIPANT_LEFT" -> {
                    val guestId = data.optString("guestId", "")
                    val name = data.optString("displayName", "")
                    val isHost = data.optBoolean("isHost", false)
                    val count = data.optInt("participantCount", 1)
                    listeners.forEach { it.onParticipantLeft(guestId, name, isHost, count) }
                }

                "REACTION_RECEIVED" -> {
                    val guestId = data.optString("guestId", "")
                    val name = data.optString("displayName", "")
                    val emoji = data.optString("emoji", "❤️")
                    val timestamp = data.optLong("timestamp", System.currentTimeMillis())
                    listeners.forEach { it.onReactionReceived(JamReaction(guestId, name, emoji, timestamp)) }
                }

                "HOST_CHANGED" -> {
                    val newHostId = data.optString("newHostGuestId", "")
                    val newHostName = data.optString("newHostDisplayName", "")
                    listeners.forEach { it.onHostChanged(newHostId, newHostName) }
                }

                "JAM_ENDED" -> {
                    val reason = data.optString("reason", "SESSION_CLOSED")
                    listeners.forEach { it.onJamEnded(reason) }
                }

                "SYNC_RESPONSE" -> {
                    val cTime = data.optLong("clientTimestamp", 0L)
                    val sTime = data.optLong("serverTimestamp", 0L)
                    val pbObj = data.getJSONObject("playbackState")
                    val pbState = parsePlaybackState(pbObj)
                    val calcPos = pbObj.optLong("calculatedPositionMs", pbState.positionMs)
                    val sVer = data.optLong("stateVersion", pbState.stateVersion)
                    val response = JamSyncResponse(cTime, sTime, pbState, calcPos, sVer)
                    listeners.forEach { it.onSyncResponse(response) }
                }

                "ERROR" -> {
                    val code = data.optString("code", "UNKNOWN_ERROR")
                    val msg = data.optString("message", "An error occurred")
                    listeners.forEach { it.onError(code, msg) }
                }
            }
        } catch (_: Exception) {
            // Malformed message handled cleanly
        }
    }

    // =========================================================================
    // JSON Serialization Helpers
    // =========================================================================

    private fun serializeSongSummary(s: JamSongSummary): JSONObject {
        return JSONObject().apply {
            put("id", s.id)
            put("title", s.title)
            put("artists", s.artists)
            put("album", s.album)
            put("imageUrl", s.imageUrl ?: "")
            put("durationSec", s.durationSec)
            put("mediaUrlDefault", s.mediaUrlDefault ?: "")
        }
    }

    private fun parseSongSummary(obj: JSONObject?): JamSongSummary? {
        if (obj == null) return null
        return JamSongSummary(
            id = obj.optString("id", ""),
            title = obj.optString("title", ""),
            artists = obj.optString("artists", ""),
            album = obj.optString("album", ""),
            imageUrl = obj.optString("imageUrl", "").ifBlank { null },
            durationSec = obj.optInt("durationSec", 0),
            mediaUrlDefault = obj.optString("mediaUrlDefault", "").ifBlank { null }
        )
    }

    private fun parsePlaybackState(obj: JSONObject): JamPlaybackState {
        return JamPlaybackState(
            trackId = obj.optString("trackId", "").ifBlank { null },
            songSummary = parseSongSummary(obj.optJSONObject("songSummary")),
            isPlaying = obj.optBoolean("isPlaying", false),
            positionMs = obj.optLong("positionMs", 0L),
            serverTimestamp = obj.optLong("serverTimestamp", 0L),
            stateVersion = obj.optLong("stateVersion", 1L)
        )
    }

    private fun parseParticipant(obj: JSONObject): JamParticipant {
        return JamParticipant(
            participantId = obj.optString("participantId", ""),
            guestId = obj.optString("guestId", ""),
            displayName = obj.optString("displayName", "User"),
            avatarIndex = obj.optInt("avatarIndex", 0),
            role = if (obj.optString("role") == "HOST") JamRole.HOST else JamRole.GUEST,
            connectionStatus = if (obj.optString("connectionStatus") == "DISCONNECTED")
                JamConnectionStatus.DISCONNECTED else JamConnectionStatus.CONNECTED,
            joinedAt = obj.optLong("joinedAt", 0L)
        )
    }

    private fun parseQueue(array: JSONArray): List<JamQueueItem> {
        val list = mutableListOf<JamQueueItem>()
        for (i in 0 until array.length()) {
            val itemObj = array.optJSONObject(i) ?: continue
            val songObj = itemObj.optJSONObject("songSummary") ?: continue
            val songSummary = parseSongSummary(songObj) ?: continue
            list.add(
                JamQueueItem(
                    queueItemId = itemObj.optString("queueItemId", ""),
                    trackId = itemObj.optString("trackId", songSummary.id),
                    songSummary = songSummary,
                    addedByGuestId = itemObj.optString("addedByGuestId", ""),
                    addedByName = itemObj.optString("addedByName", ""),
                    addedAt = itemObj.optLong("addedAt", 0L)
                )
            )
        }
        return list
    }

    private fun parseRoom(obj: JSONObject): JamRoom {
        val pArray = obj.optJSONArray("participants") ?: JSONArray()
        val participants = mutableListOf<JamParticipant>()
        for (i in 0 until pArray.length()) {
            pArray.optJSONObject(i)?.let { participants.add(parseParticipant(it)) }
        }

        val qArray = obj.optJSONArray("queue") ?: JSONArray()
        val queue = parseQueue(qArray)

        val pbObj = obj.optJSONObject("playbackState") ?: JSONObject()
        val pbState = parsePlaybackState(pbObj)

        val settingsObj = obj.optJSONObject("settings")
        val settings = JamRoomSettings(
            allowGuestControl = settingsObj?.optBoolean("allowGuestControl", false) ?: false,
            allowGuestQueueAdd = settingsObj?.optBoolean("allowGuestQueueAdd", true) ?: true,
            allowGuestQueueRemoveOwn = settingsObj?.optBoolean("allowGuestQueueRemoveOwn", true) ?: true
        )

        return JamRoom(
            roomId = obj.optString("roomId", ""),
            roomCode = obj.optString("roomCode", ""),
            hostGuestId = obj.optString("hostGuestId", ""),
            visibility = obj.optString("visibility", "PRIVATE"),
            createdAt = obj.optLong("createdAt", 0L),
            settings = settings,
            playbackState = pbState,
            queue = queue,
            participants = participants,
            serverTimestamp = obj.optLong("serverTimestamp", 0L)
        )
    }
}
