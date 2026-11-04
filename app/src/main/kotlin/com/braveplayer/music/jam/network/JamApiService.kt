package com.braveplayer.music.jam.network

import com.braveplayer.music.jam.model.JamGuestSession
import com.braveplayer.music.jam.model.JamRoomPreview
import com.braveplayer.music.jam.model.JamSongSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class JamApiService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val DEFAULT_JAM_HOST = "melody-music-szk2.onrender.com"
        const val DEFAULT_JAM_PORT = 443
    }

    suspend fun wakeUpServer(host: String = DEFAULT_JAM_HOST, port: Int = DEFAULT_JAM_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseHttpUrl(host, port)}/api/v1/jam/health"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    fun getBaseHttpUrl(host: String = DEFAULT_JAM_HOST, port: Int = DEFAULT_JAM_PORT): String {
        val trimmed = host.trim()
        val isExplicitHttps = trimmed.startsWith("https://", ignoreCase = true)
        val isExplicitWss = trimmed.startsWith("wss://", ignoreCase = true)

        val cleanHost = trimmed
            .removePrefix("https://").removePrefix("HTTPS://")
            .removePrefix("http://").removePrefix("HTTP://")
            .removePrefix("wss://").removePrefix("WSS://")
            .removePrefix("ws://").removePrefix("WS://")
            .trimEnd('/')

        val isSecure = isExplicitHttps || isExplicitWss || port == 443 || (!cleanHost.startsWith("127.") && !cleanHost.startsWith("10.") && !cleanHost.startsWith("192.168.") && !cleanHost.equals("localhost", ignoreCase = true) && cleanHost.contains("."))
        val scheme = if (isSecure) "https" else "http"

        return if (cleanHost.contains(":")) {
            "$scheme://$cleanHost"
        } else if ((isSecure && port == 443) || (!isSecure && port == 80)) {
            "$scheme://$cleanHost"
        } else {
            "$scheme://$cleanHost:$port"
        }
    }

    fun getBaseWsUrl(host: String = DEFAULT_JAM_HOST, port: Int = DEFAULT_JAM_PORT): String {
        val trimmed = host.trim()
        val isExplicitHttps = trimmed.startsWith("https://", ignoreCase = true)
        val isExplicitWss = trimmed.startsWith("wss://", ignoreCase = true)

        val cleanHost = trimmed
            .removePrefix("https://").removePrefix("HTTPS://")
            .removePrefix("http://").removePrefix("HTTP://")
            .removePrefix("wss://").removePrefix("WSS://")
            .removePrefix("ws://").removePrefix("WS://")
            .trimEnd('/')

        val isSecure = isExplicitHttps || isExplicitWss || port == 443 || (!cleanHost.startsWith("127.") && !cleanHost.startsWith("10.") && !cleanHost.startsWith("192.168.") && !cleanHost.equals("localhost", ignoreCase = true) && cleanHost.contains("."))
        val scheme = if (isSecure) "wss" else "ws"

        return if (cleanHost.contains(":")) {
            "$scheme://$cleanHost/ws/jam"
        } else if ((isSecure && port == 443) || (!isSecure && port == 80)) {
            "$scheme://$cleanHost/ws/jam"
        } else {
            "$scheme://$cleanHost:$port/ws/jam"
        }
    }

    suspend fun createOrRenewGuestSession(
        installationId: String,
        displayName: String,
        host: String = DEFAULT_JAM_HOST,
        port: Int = DEFAULT_JAM_PORT
    ): Result<JamGuestSession> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseHttpUrl(host, port)}/api/v1/jam/guest/session"
            val jsonPayload = JSONObject().apply {
                put("installationId", installationId)
                put("displayName", displayName)
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Server error: ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                val err = json.optJSONObject("error")?.optString("message") ?: "Session creation failed"
                return@withContext Result.failure(IOException(err))
            }

            val data = json.getJSONObject("data")
            val session = JamGuestSession(
                guestId = data.getString("guestId"),
                displayName = data.getString("displayName"),
                token = data.getString("token"),
                expiresAt = data.getLong("expiresAt")
            )
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRoomPreview(
        roomCodeOrId: String,
        host: String = DEFAULT_JAM_HOST,
        port: Int = DEFAULT_JAM_PORT
    ): Result<JamRoomPreview> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(roomCodeOrId.trim(), "UTF-8")
            val url = "${getBaseHttpUrl(host, port)}/api/v1/jam/rooms/$encoded/info"
            val request = Request.Builder().url(url).get().build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Room not found or expired (${response.code})"))
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                val err = json.optJSONObject("error")?.optString("message") ?: "Room not found"
                return@withContext Result.failure(IOException(err))
            }

            val data = json.getJSONObject("data")
            val trackObj = data.optJSONObject("currentTrack")
            val currentTrack = if (trackObj != null) {
                JamSongSummary(
                    id = trackObj.optString("id", ""),
                    title = trackObj.optString("title", ""),
                    artists = trackObj.optString("artists", ""),
                    album = trackObj.optString("album", ""),
                    imageUrl = trackObj.optString("imageUrl", "").ifBlank { null },
                    durationSec = trackObj.optInt("durationSec", 0),
                    mediaUrlDefault = trackObj.optString("mediaUrlDefault", "").ifBlank { null }
                )
            } else null

            val preview = JamRoomPreview(
                roomId = data.getString("roomId"),
                roomCode = data.getString("roomCode"),
                hostDisplayName = data.getString("hostDisplayName"),
                participantCount = data.getInt("participantCount"),
                currentTrack = currentTrack,
                isPlaying = data.optBoolean("isPlaying", false)
            )
            Result.success(preview)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isServerReachable(host: String = DEFAULT_JAM_HOST, port: Int = DEFAULT_JAM_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseHttpUrl(host, port)}/api/v1/jam/health"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }
}
