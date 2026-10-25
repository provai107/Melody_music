package com.braveplayer.music.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.braveplayer.music.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists and restores playback queue, current track index, position,
 * repeat mode, and shuffle mode without auto-playing on restoration.
 */
class QueuePersistenceManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "brave_queue_prefs"
        private const val KEY_QUEUE_JSON = "queue_json"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_SHUFFLE_MODE = "shuffle_mode"
    }

    data class PersistedQueueState(
        val songs: List<Song>,
        val currentIndex: Int,
        val positionMs: Long,
        val repeatMode: Int,
        val shuffleMode: Boolean
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveQueueState(
        songs: List<Song>,
        currentIndex: Int,
        positionMs: Long,
        repeatMode: Int,
        shuffleMode: Boolean
    ) {
        val jsonArray = JSONArray()
        for (song in songs) {
            val obj = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artists", song.artists)
                put("album", song.album)
                put("imageUrl", song.imageUrl ?: JSONObject.NULL)
                put("durationSec", song.durationSec)
                put("mediaUrl", song.mediaUrl ?: JSONObject.NULL)
                put("mediaUrlDefault", song.mediaUrlDefault ?: JSONObject.NULL)
                put("previewUrl", song.previewUrl ?: JSONObject.NULL)
            }
            jsonArray.put(obj)
        }

        prefs.edit()
            .putString(KEY_QUEUE_JSON, jsonArray.toString())
            .putInt(KEY_CURRENT_INDEX, currentIndex)
            .putLong(KEY_POSITION_MS, positionMs)
            .putInt(KEY_REPEAT_MODE, repeatMode)
            .putBoolean(KEY_SHUFFLE_MODE, shuffleMode)
            .apply()
    }

    fun restoreQueueState(): PersistedQueueState {
        val jsonStr = prefs.getString(KEY_QUEUE_JSON, null) ?: return PersistedQueueState(
            songs = emptyList(),
            currentIndex = 0,
            positionMs = 0L,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleMode = false
        )

        val songs = mutableListOf<Song>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                songs.add(
                    Song(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artists = obj.optString("artists", "Unknown Artist"),
                        album = obj.optString("album", "Unknown Album"),
                        imageUrl = if (obj.isNull("imageUrl")) null else obj.optString("imageUrl"),
                        durationSec = obj.optInt("durationSec", 0),
                        mediaUrl = if (obj.isNull("mediaUrl")) null else obj.optString("mediaUrl"),
                        mediaUrlDefault = if (obj.isNull("mediaUrlDefault")) null else obj.optString("mediaUrlDefault"),
                        previewUrl = if (obj.isNull("previewUrl")) null else obj.optString("previewUrl")
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore corrupted JSON and return empty
        }

        val currentIndex = prefs.getInt(KEY_CURRENT_INDEX, 0)
        val positionMs = prefs.getLong(KEY_POSITION_MS, 0L)
        val repeatMode = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        val shuffleMode = prefs.getBoolean(KEY_SHUFFLE_MODE, false)

        val validatedIndex = if (songs.isNotEmpty()) currentIndex.coerceIn(0, songs.size - 1) else 0

        return PersistedQueueState(
            songs = songs,
            currentIndex = validatedIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            repeatMode = repeatMode,
            shuffleMode = shuffleMode
        )
    }

    fun clearQueueState() {
        prefs.edit().clear().apply()
    }
}
