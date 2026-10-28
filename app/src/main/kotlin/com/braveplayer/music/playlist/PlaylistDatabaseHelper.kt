package com.braveplayer.music.playlist

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.braveplayer.music.model.Song

data class Playlist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val songCount: Int = 0
)

data class PlayHistoryEntry(
    val id: Long,
    val song: Song,
    val playedAt: Long,
    val playDurationSec: Int,
    val isCompleted: Boolean,
    val isSkipped: Boolean,
    val isMeaningfulPlay: Boolean
)

data class SearchHistoryItem(
    val id: Long = 0,
    val queryText: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String? = null,
    val contentType: String = "Song",
    val songId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val mediaUrl: String? = null,
    val mediaUrlDefault: String? = null,
    val durationSec: Int = 0,
    val album: String = "",
    val artists: String = ""
)

/**
 * SQLite persistence layer for user playlists, liked songs, and listening history.
 * Caps listening history growth to protect storage and maintain privacy strictly on-device.
 */
class PlaylistDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "brave_playlists.db"
        private const val DATABASE_VERSION = 5

        // Playlists Table
        private const val TABLE_PLAYLISTS = "playlists"
        private const val COL_P_ID = "id"
        private const val COL_P_NAME = "name"
        private const val COL_P_CREATED_AT = "created_at"

        // Playlist Songs Table
        private const val TABLE_PLAYLIST_SONGS = "playlist_songs"
        private const val COL_PS_ID = "id"
        private const val COL_PS_PLAYLIST_ID = "playlist_id"
        private const val COL_PS_SONG_ID = "song_id"
        private const val COL_PS_TITLE = "title"
        private const val COL_PS_ARTISTS = "artists"
        private const val COL_PS_ALBUM = "album"
        private const val COL_PS_IMAGE_URL = "image_url"
        private const val COL_PS_DURATION = "duration_sec"
        private const val COL_PS_MEDIA_URL = "media_url"
        private const val COL_PS_MEDIA_URL_DEF = "media_url_default"
        private const val COL_PS_PREVIEW_URL = "preview_url"
        private const val COL_PS_SORT_ORDER = "sort_order"

        // Liked Songs Table
        private const val TABLE_LIKED = "liked_songs"
        private const val COL_L_SONG_ID = "song_id"
        private const val COL_L_TITLE = "title"
        private const val COL_L_ARTISTS = "artists"
        private const val COL_L_ALBUM = "album"
        private const val COL_L_IMAGE_URL = "image_url"
        private const val COL_L_DURATION = "duration_sec"
        private const val COL_L_MEDIA_URL = "media_url"
        private const val COL_L_MEDIA_URL_DEF = "media_url_default"
        private const val COL_L_PREVIEW_URL = "preview_url"
        private const val COL_L_LIKED_AT = "liked_at"

        // Listening History Table
        private const val TABLE_HISTORY = "listening_history"
        private const val COL_H_ID = "id"
        private const val COL_H_SONG_ID = "song_id"
        private const val COL_H_TITLE = "title"
        private const val COL_H_ARTISTS = "artists"
        private const val COL_H_ALBUM = "album"
        private const val COL_H_IMAGE_URL = "image_url"
        private const val COL_H_DURATION = "duration_sec"
        private const val COL_H_MEDIA_URL = "media_url"
        private const val COL_H_MEDIA_URL_DEF = "media_url_default"
        private const val COL_H_PREVIEW_URL = "preview_url"
        private const val COL_H_PLAYED_AT = "played_at"
        private const val COL_H_PLAY_DURATION_SEC = "play_duration_sec"
        private const val COL_H_COMPLETED = "completed"
        private const val COL_H_SKIPPED = "skipped"
        private const val COL_H_MEANINGFUL = "meaningful_play"

        // Downloaded Songs Table
        private const val TABLE_DOWNLOADED = "downloaded_songs"
        private const val COL_D_SONG_ID = "song_id"
        private const val COL_D_TITLE = "title"
        private const val COL_D_ARTISTS = "artists"
        private const val COL_D_ALBUM = "album"
        private const val COL_D_IMAGE_URL = "image_url"
        private const val COL_D_DURATION = "duration_sec"
        private const val COL_D_MEDIA_URL = "media_url"
        private const val COL_D_LOCAL_PATH = "local_path"
        private const val COL_D_DOWNLOADED_AT = "downloaded_at"

        // Search History Table
        private const val TABLE_SEARCH_HISTORY = "search_history"
        private const val COL_SH_ID = "id"
        private const val COL_SH_QUERY = "query_text"
        private const val COL_SH_TITLE = "title"
        private const val COL_SH_SUBTITLE = "subtitle"
        private const val COL_SH_IMAGE_URL = "image_url"
        private const val COL_SH_TYPE = "content_type"
        private const val COL_SH_SONG_ID = "song_id"
        private const val COL_SH_TIMESTAMP = "timestamp"
        private const val COL_SH_MEDIA_URL = "media_url"
        private const val COL_SH_MEDIA_URL_DEF = "media_url_default"
        private const val COL_SH_DURATION = "duration_sec"
        private const val COL_SH_ALBUM = "album"
        private const val COL_SH_ARTISTS = "artists"

        // Recommendation Feedback Table (Spotify-style Like / Not Interested / Hide)
        private const val TABLE_RECOMMENDATION_FEEDBACK = "recommendation_feedback"
        private const val COL_RF_ID = "id"
        private const val COL_RF_SONG_ID = "song_id"
        private const val COL_RF_TITLE = "title"
        private const val COL_RF_ARTISTS = "artists"
        private const val COL_RF_ACTION = "action"
        private const val COL_RF_TIMESTAMP = "timestamp"

        const val ACTION_NOT_INTERESTED = "NOT_INTERESTED"
        const val ACTION_HIDE = "HIDE"

        const val MAX_HISTORY_ROWS = 500
        const val MAX_SEARCH_HISTORY_ROWS = 50
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PLAYLISTS (
                $COL_P_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_P_NAME TEXT NOT NULL UNIQUE,
                $COL_P_CREATED_AT INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_PLAYLIST_SONGS (
                $COL_PS_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PS_PLAYLIST_ID INTEGER NOT NULL,
                $COL_PS_SONG_ID TEXT NOT NULL,
                $COL_PS_TITLE TEXT NOT NULL,
                $COL_PS_ARTISTS TEXT NOT NULL,
                $COL_PS_ALBUM TEXT NOT NULL,
                $COL_PS_IMAGE_URL TEXT,
                $COL_PS_DURATION INTEGER NOT NULL,
                $COL_PS_MEDIA_URL TEXT,
                $COL_PS_MEDIA_URL_DEF TEXT,
                $COL_PS_PREVIEW_URL TEXT,
                $COL_PS_SORT_ORDER INTEGER NOT NULL,
                FOREIGN KEY($COL_PS_PLAYLIST_ID) REFERENCES $TABLE_PLAYLISTS($COL_P_ID) ON DELETE CASCADE
            );
            """.trimIndent()
        )

        createLikedSongsTable(db)
        createHistoryTable(db)
        createDownloadedSongsTable(db)
        createSearchHistoryTable(db)
        createRecommendationFeedbackTable(db)
    }

    private fun createRecommendationFeedbackTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RECOMMENDATION_FEEDBACK (
                $COL_RF_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_RF_SONG_ID TEXT NOT NULL,
                $COL_RF_TITLE TEXT,
                $COL_RF_ARTISTS TEXT,
                $COL_RF_ACTION TEXT NOT NULL,
                $COL_RF_TIMESTAMP INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rf_song_id ON $TABLE_RECOMMENDATION_FEEDBACK($COL_RF_SONG_ID);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rf_action ON $TABLE_RECOMMENDATION_FEEDBACK($COL_RF_ACTION);")
    }

    private fun createSearchHistoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SEARCH_HISTORY (
                $COL_SH_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SH_QUERY TEXT NOT NULL,
                $COL_SH_TITLE TEXT NOT NULL,
                $COL_SH_SUBTITLE TEXT NOT NULL,
                $COL_SH_IMAGE_URL TEXT,
                $COL_SH_TYPE TEXT NOT NULL,
                $COL_SH_SONG_ID TEXT,
                $COL_SH_TIMESTAMP INTEGER NOT NULL,
                $COL_SH_MEDIA_URL TEXT,
                $COL_SH_MEDIA_URL_DEF TEXT,
                $COL_SH_DURATION INTEGER DEFAULT 0,
                $COL_SH_ALBUM TEXT DEFAULT '',
                $COL_SH_ARTISTS TEXT DEFAULT ''
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_search_timestamp ON $TABLE_SEARCH_HISTORY($COL_SH_TIMESTAMP DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_search_query ON $TABLE_SEARCH_HISTORY($COL_SH_QUERY);")
    }

    private fun createLikedSongsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LIKED (
                $COL_L_SONG_ID TEXT PRIMARY KEY,
                $COL_L_TITLE TEXT NOT NULL,
                $COL_L_ARTISTS TEXT NOT NULL,
                $COL_L_ALBUM TEXT NOT NULL,
                $COL_L_IMAGE_URL TEXT,
                $COL_L_DURATION INTEGER NOT NULL,
                $COL_L_MEDIA_URL TEXT,
                $COL_L_MEDIA_URL_DEF TEXT,
                $COL_L_PREVIEW_URL TEXT,
                $COL_L_LIKED_AT INTEGER NOT NULL
            );
            """.trimIndent()
        )
    }

    private fun createDownloadedSongsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DOWNLOADED (
                $COL_D_SONG_ID TEXT PRIMARY KEY,
                $COL_D_TITLE TEXT NOT NULL,
                $COL_D_ARTISTS TEXT NOT NULL,
                $COL_D_ALBUM TEXT NOT NULL,
                $COL_D_IMAGE_URL TEXT,
                $COL_D_DURATION INTEGER NOT NULL,
                $COL_D_MEDIA_URL TEXT,
                $COL_D_LOCAL_PATH TEXT NOT NULL,
                $COL_D_DOWNLOADED_AT INTEGER NOT NULL
            );
            """.trimIndent()
        )
    }

    private fun createHistoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_HISTORY (
                $COL_H_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_H_SONG_ID TEXT NOT NULL,
                $COL_H_TITLE TEXT NOT NULL,
                $COL_H_ARTISTS TEXT NOT NULL,
                $COL_H_ALBUM TEXT NOT NULL,
                $COL_H_IMAGE_URL TEXT,
                $COL_H_DURATION INTEGER NOT NULL,
                $COL_H_MEDIA_URL TEXT,
                $COL_H_MEDIA_URL_DEF TEXT,
                $COL_H_PREVIEW_URL TEXT,
                $COL_H_PLAYED_AT INTEGER NOT NULL,
                $COL_H_PLAY_DURATION_SEC INTEGER NOT NULL,
                $COL_H_COMPLETED INTEGER NOT NULL DEFAULT 0,
                $COL_H_SKIPPED INTEGER NOT NULL DEFAULT 0,
                $COL_H_MEANINGFUL INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_played_at ON $TABLE_HISTORY($COL_H_PLAYED_AT DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_song_id ON $TABLE_HISTORY($COL_H_SONG_ID);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createLikedSongsTable(db)
            createHistoryTable(db)
        }
        if (oldVersion < 3) {
            createDownloadedSongsTable(db)
        }
        if (oldVersion < 4) {
            createSearchHistoryTable(db)
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE $TABLE_SEARCH_HISTORY ADD COLUMN $COL_SH_MEDIA_URL TEXT;")
                db.execSQL("ALTER TABLE $TABLE_SEARCH_HISTORY ADD COLUMN $COL_SH_MEDIA_URL_DEF TEXT;")
                db.execSQL("ALTER TABLE $TABLE_SEARCH_HISTORY ADD COLUMN $COL_SH_DURATION INTEGER DEFAULT 0;")
                db.execSQL("ALTER TABLE $TABLE_SEARCH_HISTORY ADD COLUMN $COL_SH_ALBUM TEXT DEFAULT '';")
                db.execSQL("ALTER TABLE $TABLE_SEARCH_HISTORY ADD COLUMN $COL_SH_ARTISTS TEXT DEFAULT '';")
            } catch (_: Exception) {}
            createRecommendationFeedbackTable(db)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    // ==========================================
    // Playlists Operations
    // ==========================================

    fun createPlaylist(name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L

        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_P_NAME, trimmed)
            put(COL_P_CREATED_AT, System.currentTimeMillis())
        }
        return db.insertWithOnConflict(TABLE_PLAYLISTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getAllPlaylists(): List<Playlist> {
        val playlists = mutableListOf<Playlist>()
        val db = readableDatabase
        val query = """
            SELECT p.$COL_P_ID, p.$COL_P_NAME, p.$COL_P_CREATED_AT, COUNT(ps.$COL_PS_ID) AS count
            FROM $TABLE_PLAYLISTS p
            LEFT JOIN $TABLE_PLAYLIST_SONGS ps ON p.$COL_P_ID = ps.$COL_PS_PLAYLIST_ID
            GROUP BY p.$COL_P_ID
            ORDER BY p.$COL_P_CREATED_AT DESC
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1)
                val createdAt = it.getLong(2)
                val count = it.getInt(3)
                playlists.add(Playlist(id, name, createdAt, count))
            }
        }
        return playlists
    }

    fun deletePlaylist(playlistId: Long): Boolean {
        val db = writableDatabase
        val deleted = db.delete(TABLE_PLAYLISTS, "$COL_P_ID = ?", arrayOf(playlistId.toString()))
        return deleted > 0
    }

    fun addSongToPlaylist(playlistId: Long, song: Song): Boolean {
        val db = writableDatabase
        val maxOrderQuery = "SELECT MAX($COL_PS_SORT_ORDER) FROM $TABLE_PLAYLIST_SONGS WHERE $COL_PS_PLAYLIST_ID = ?"
        val cursor = db.rawQuery(maxOrderQuery, arrayOf(playlistId.toString()))
        var nextOrder = 0
        cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) {
                nextOrder = it.getInt(0) + 1
            }
        }

        val values = ContentValues().apply {
            put(COL_PS_PLAYLIST_ID, playlistId)
            put(COL_PS_SONG_ID, song.id)
            put(COL_PS_TITLE, song.title)
            put(COL_PS_ARTISTS, song.artists)
            put(COL_PS_ALBUM, song.album)
            put(COL_PS_IMAGE_URL, song.imageUrl)
            put(COL_PS_DURATION, song.durationSec)
            put(COL_PS_MEDIA_URL, song.mediaUrl)
            put(COL_PS_MEDIA_URL_DEF, song.mediaUrlDefault)
            put(COL_PS_PREVIEW_URL, song.previewUrl)
            put(COL_PS_SORT_ORDER, nextOrder)
        }
        val rowId = db.insert(TABLE_PLAYLIST_SONGS, null, values)
        return rowId != -1L
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: String): Boolean {
        val db = writableDatabase
        val deleted = db.delete(
            TABLE_PLAYLIST_SONGS,
            "$COL_PS_PLAYLIST_ID = ? AND $COL_PS_SONG_ID = ?",
            arrayOf(playlistId.toString(), songId)
        )
        return deleted > 0
    }

    fun getSongsInPlaylist(playlistId: Long): List<Song> {
        val songs = mutableListOf<Song>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PLAYLIST_SONGS,
            null,
            "$COL_PS_PLAYLIST_ID = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "$COL_PS_SORT_ORDER ASC"
        )
        cursor.use {
            val idxId = it.getColumnIndexOrThrow(COL_PS_SONG_ID)
            val idxTitle = it.getColumnIndexOrThrow(COL_PS_TITLE)
            val idxArtists = it.getColumnIndexOrThrow(COL_PS_ARTISTS)
            val idxAlbum = it.getColumnIndexOrThrow(COL_PS_ALBUM)
            val idxImage = it.getColumnIndexOrThrow(COL_PS_IMAGE_URL)
            val idxDuration = it.getColumnIndexOrThrow(COL_PS_DURATION)
            val idxMediaUrl = it.getColumnIndexOrThrow(COL_PS_MEDIA_URL)
            val idxMediaUrlDef = it.getColumnIndexOrThrow(COL_PS_MEDIA_URL_DEF)
            val idxPreviewUrl = it.getColumnIndexOrThrow(COL_PS_PREVIEW_URL)

            while (it.moveToNext()) {
                songs.add(
                    Song(
                        id = it.getString(idxId),
                        title = it.getString(idxTitle),
                        artists = it.getString(idxArtists),
                        album = it.getString(idxAlbum),
                        imageUrl = it.getString(idxImage),
                        durationSec = it.getInt(idxDuration),
                        mediaUrl = it.getString(idxMediaUrl),
                        mediaUrlDefault = it.getString(idxMediaUrlDef),
                        previewUrl = it.getString(idxPreviewUrl)
                    )
                )
            }
        }
        return songs
    }

    // ==========================================
    // Liked Songs Operations (Feature 1)
    // ==========================================

    fun likeSong(song: Song): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_L_SONG_ID, song.id)
            put(COL_L_TITLE, song.title)
            put(COL_L_ARTISTS, song.artists)
            put(COL_L_ALBUM, song.album)
            put(COL_L_IMAGE_URL, song.imageUrl)
            put(COL_L_DURATION, song.durationSec)
            put(COL_L_MEDIA_URL, song.mediaUrl)
            put(COL_L_MEDIA_URL_DEF, song.mediaUrlDefault)
            put(COL_L_PREVIEW_URL, song.previewUrl)
            put(COL_L_LIKED_AT, System.currentTimeMillis())
        }
        val result = db.insertWithOnConflict(TABLE_LIKED, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return result != -1L
    }

    fun unlikeSong(songId: String): Boolean {
        val db = writableDatabase
        val deleted = db.delete(TABLE_LIKED, "$COL_L_SONG_ID = ?", arrayOf(songId))
        return deleted > 0
    }

    fun isSongLiked(songId: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LIKED,
            arrayOf(COL_L_SONG_ID),
            "$COL_L_SONG_ID = ?",
            arrayOf(songId),
            null,
            null,
            null
        )
        cursor.use {
            return it.moveToFirst()
        }
    }

    fun getAllLikedSongs(): List<Song> {
        val songs = mutableListOf<Song>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LIKED,
            null,
            null,
            null,
            null,
            null,
            "$COL_L_LIKED_AT DESC"
        )
        cursor.use {
            val idxId = it.getColumnIndexOrThrow(COL_L_SONG_ID)
            val idxTitle = it.getColumnIndexOrThrow(COL_L_TITLE)
            val idxArtists = it.getColumnIndexOrThrow(COL_L_ARTISTS)
            val idxAlbum = it.getColumnIndexOrThrow(COL_L_ALBUM)
            val idxImage = it.getColumnIndexOrThrow(COL_L_IMAGE_URL)
            val idxDuration = it.getColumnIndexOrThrow(COL_L_DURATION)
            val idxMediaUrl = it.getColumnIndexOrThrow(COL_L_MEDIA_URL)
            val idxMediaUrlDef = it.getColumnIndexOrThrow(COL_L_MEDIA_URL_DEF)
            val idxPreviewUrl = it.getColumnIndexOrThrow(COL_L_PREVIEW_URL)

            while (it.moveToNext()) {
                songs.add(
                    Song(
                        id = it.getString(idxId),
                        title = it.getString(idxTitle),
                        artists = it.getString(idxArtists),
                        album = it.getString(idxAlbum),
                        imageUrl = it.getString(idxImage),
                        durationSec = it.getInt(idxDuration),
                        mediaUrl = it.getString(idxMediaUrl),
                        mediaUrlDefault = it.getString(idxMediaUrlDef),
                        previewUrl = it.getString(idxPreviewUrl)
                    )
                )
            }
        }
        return songs
    }

    fun getLikedSongIds(): Set<String> {
        val ids = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.query(TABLE_LIKED, arrayOf(COL_L_SONG_ID), null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                ids.add(it.getString(0))
            }
        }
        return ids
    }

    // ==========================================
    // Listening History Operations (Feature 5)
    // ==========================================

    fun recordListeningHistory(
        song: Song,
        playDurationSec: Int,
        isCompleted: Boolean,
        isSkipped: Boolean,
        isMeaningful: Boolean
    ): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_H_SONG_ID, song.id)
            put(COL_H_TITLE, song.title)
            put(COL_H_ARTISTS, song.artists)
            put(COL_H_ALBUM, song.album)
            put(COL_H_IMAGE_URL, song.imageUrl)
            put(COL_H_DURATION, song.durationSec)
            put(COL_H_MEDIA_URL, song.mediaUrl)
            put(COL_H_MEDIA_URL_DEF, song.mediaUrlDefault)
            put(COL_H_PREVIEW_URL, song.previewUrl)
            put(COL_H_PLAYED_AT, System.currentTimeMillis())
            put(COL_H_PLAY_DURATION_SEC, playDurationSec)
            put(COL_H_COMPLETED, if (isCompleted) 1 else 0)
            put(COL_H_SKIPPED, if (isSkipped) 1 else 0)
            put(COL_H_MEANINGFUL, if (isMeaningful) 1 else 0)
        }
        val rowId = db.insert(TABLE_HISTORY, null, values)
        if (rowId == -1L) return false

        // Enforce storage cap: keep latest MAX_HISTORY_ROWS
        db.execSQL(
            """
            DELETE FROM $TABLE_HISTORY
            WHERE $COL_H_ID NOT IN (
                SELECT $COL_H_ID FROM $TABLE_HISTORY
                ORDER BY $COL_H_PLAYED_AT DESC
                LIMIT $MAX_HISTORY_ROWS
            );
            """.trimIndent()
        )

        return true
    }

    fun getRecentlyPlayedSongs(limit: Int = 50): List<Song> {
        val songs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "$COL_H_PLAYED_AT DESC",
            (limit * 2).toString()
        )
        cursor.use {
            val idxId = it.getColumnIndexOrThrow(COL_H_SONG_ID)
            val idxTitle = it.getColumnIndexOrThrow(COL_H_TITLE)
            val idxArtists = it.getColumnIndexOrThrow(COL_H_ARTISTS)
            val idxAlbum = it.getColumnIndexOrThrow(COL_H_ALBUM)
            val idxImage = it.getColumnIndexOrThrow(COL_H_IMAGE_URL)
            val idxDuration = it.getColumnIndexOrThrow(COL_H_DURATION)
            val idxMediaUrl = it.getColumnIndexOrThrow(COL_H_MEDIA_URL)
            val idxMediaUrlDef = it.getColumnIndexOrThrow(COL_H_MEDIA_URL_DEF)
            val idxPreviewUrl = it.getColumnIndexOrThrow(COL_H_PREVIEW_URL)

            while (it.moveToNext() && songs.size < limit) {
                val songId = it.getString(idxId)
                if (seenIds.add(songId)) {
                    songs.add(
                        Song(
                            id = songId,
                            title = it.getString(idxTitle),
                            artists = it.getString(idxArtists),
                            album = it.getString(idxAlbum),
                            imageUrl = it.getString(idxImage),
                            durationSec = it.getInt(idxDuration),
                            mediaUrl = it.getString(idxMediaUrl),
                            mediaUrlDefault = it.getString(idxMediaUrlDef),
                            previewUrl = it.getString(idxPreviewUrl)
                        )
                    )
                }
            }
        }
        return songs
    }

    fun getMostPlayedSongs(limit: Int = 50): List<Pair<Song, Int>> {
        val list = mutableListOf<Pair<Song, Int>>()
        val db = readableDatabase
        val query = """
            SELECT $COL_H_SONG_ID, $COL_H_TITLE, $COL_H_ARTISTS, $COL_H_ALBUM, $COL_H_IMAGE_URL,
                   $COL_H_DURATION, $COL_H_MEDIA_URL, $COL_H_MEDIA_URL_DEF, $COL_H_PREVIEW_URL,
                   COUNT($COL_H_ID) AS play_count
            FROM $TABLE_HISTORY
            WHERE $COL_H_MEANINGFUL = 1
            GROUP BY $COL_H_SONG_ID
            ORDER BY play_count DESC
            LIMIT $limit
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                val song = Song(
                    id = it.getString(0),
                    title = it.getString(1),
                    artists = it.getString(2),
                    album = it.getString(3),
                    imageUrl = it.getString(4),
                    durationSec = it.getInt(5),
                    mediaUrl = it.getString(6),
                    mediaUrlDefault = it.getString(7),
                    previewUrl = it.getString(8)
                )
                val count = it.getInt(9)
                list.add(Pair(song, count))
            }
        }
        return list
    }

    fun getArtistPlayCounts(): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val db = readableDatabase
        val query = """
            SELECT $COL_H_ARTISTS, COUNT($COL_H_ID) AS count
            FROM $TABLE_HISTORY
            WHERE $COL_H_MEANINGFUL = 1
            GROUP BY $COL_H_ARTISTS
            ORDER BY count DESC
            LIMIT 50
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                val rawArtists = it.getString(0)
                val count = it.getInt(1)
                // Split multi-artists by comma / &
                val individualArtists = rawArtists.split(",", "&", "/").map { a -> a.trim() }.filter { a -> a.isNotEmpty() }
                for (artist in individualArtists) {
                    map[artist] = map.getOrDefault(artist, 0) + count
                }
            }
        }
        return map
    }

    fun getRecentSongIds(limit: Int = 50): Set<String> {
        val ids = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_HISTORY,
            arrayOf(COL_H_SONG_ID),
            null,
            null,
            null,
            null,
            "$COL_H_PLAYED_AT DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                ids.add(it.getString(0))
            }
        }
        return ids
    }

    fun getSkippedSongIds(limit: Int = 50): Set<String> {
        val ids = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_HISTORY,
            arrayOf(COL_H_SONG_ID),
            "$COL_H_SKIPPED = 1",
            null,
            null,
            null,
            "$COL_H_PLAYED_AT DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                ids.add(it.getString(0))
            }
        }
        return ids
    }

    fun clearHistory() {
        val db = writableDatabase
        db.delete(TABLE_HISTORY, null, null)
    }

    // ==========================================
    // Downloaded Songs Operations
    // ==========================================

    fun saveDownloadedSong(song: Song, localPath: String): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_D_SONG_ID, song.id)
            put(COL_D_TITLE, song.title)
            put(COL_D_ARTISTS, song.artists)
            put(COL_D_ALBUM, song.album)
            put(COL_D_IMAGE_URL, song.imageUrl)
            put(COL_D_DURATION, song.durationSec)
            put(COL_D_MEDIA_URL, song.mediaUrl)
            put(COL_D_LOCAL_PATH, localPath)
            put(COL_D_DOWNLOADED_AT, System.currentTimeMillis())
        }
        return db.insertWithOnConflict(TABLE_DOWNLOADED, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getDownloadedSong(songId: String): Song? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DOWNLOADED,
            null,
            "$COL_D_SONG_ID = ?",
            arrayOf(songId),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val idxId = it.getColumnIndexOrThrow(COL_D_SONG_ID)
                val idxTitle = it.getColumnIndexOrThrow(COL_D_TITLE)
                val idxArtists = it.getColumnIndexOrThrow(COL_D_ARTISTS)
                val idxAlbum = it.getColumnIndexOrThrow(COL_D_ALBUM)
                val idxImage = it.getColumnIndexOrThrow(COL_D_IMAGE_URL)
                val idxDuration = it.getColumnIndexOrThrow(COL_D_DURATION)
                val idxPath = it.getColumnIndexOrThrow(COL_D_LOCAL_PATH)
                val localPath = it.getString(idxPath)
                return Song(
                    id = it.getString(idxId),
                    title = it.getString(idxTitle),
                    artists = it.getString(idxArtists),
                    album = it.getString(idxAlbum),
                    imageUrl = it.getString(idxImage),
                    durationSec = it.getInt(idxDuration),
                    mediaUrl = localPath,
                    mediaUrlDefault = localPath,
                    previewUrl = null
                )
            }
        }
        return null
    }

    fun isSongDownloaded(songId: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DOWNLOADED,
            arrayOf(COL_D_SONG_ID),
            "$COL_D_SONG_ID = ?",
            arrayOf(songId),
            null,
            null,
            null
        )
        cursor.use {
            return it.moveToFirst()
        }
    }

    fun isDownloadedFileAvailableOnDisk(songId: String): Boolean {
        val song = getDownloadedSong(songId) ?: return false
        val path = song.mediaUrl ?: return false
        val file = java.io.File(path)
        return file.exists() && file.length() > 0
    }

    fun getAllDownloadedSongs(): List<Song> {
        val list = mutableListOf<Song>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DOWNLOADED,
            null,
            null,
            null,
            null,
            null,
            "$COL_D_DOWNLOADED_AT DESC"
        )
        cursor.use {
            val idxId = it.getColumnIndexOrThrow(COL_D_SONG_ID)
            val idxTitle = it.getColumnIndexOrThrow(COL_D_TITLE)
            val idxArtists = it.getColumnIndexOrThrow(COL_D_ARTISTS)
            val idxAlbum = it.getColumnIndexOrThrow(COL_D_ALBUM)
            val idxImage = it.getColumnIndexOrThrow(COL_D_IMAGE_URL)
            val idxDuration = it.getColumnIndexOrThrow(COL_D_DURATION)
            val idxPath = it.getColumnIndexOrThrow(COL_D_LOCAL_PATH)

            while (it.moveToNext()) {
                val localPath = it.getString(idxPath)
                list.add(
                    Song(
                        id = it.getString(idxId),
                        title = it.getString(idxTitle),
                        artists = it.getString(idxArtists),
                        album = it.getString(idxAlbum),
                        imageUrl = it.getString(idxImage),
                        durationSec = it.getInt(idxDuration),
                        mediaUrl = localPath,
                        mediaUrlDefault = localPath,
                        previewUrl = null
                    )
                )
            }
        }
        return list
    }

    fun deleteDownloadedSong(songId: String): Boolean {
        val db = writableDatabase
        return db.delete(TABLE_DOWNLOADED, "$COL_D_SONG_ID = ?", arrayOf(songId)) > 0
    }

    // ==========================================
    // Search History Operations
    // ==========================================

    fun recordSearchHistory(item: SearchHistoryItem): Long {
        val trimmedQuery = item.queryText.trim()
        val trimmedTitle = item.title.trim()
        if (trimmedQuery.isEmpty() && trimmedTitle.isEmpty()) return -1L

        val db = writableDatabase
        // Deduplicate: if an entry with the same query or songId exists, remove the previous record
        if (!item.songId.isNullOrBlank()) {
            db.delete(TABLE_SEARCH_HISTORY, "$COL_SH_SONG_ID = ?", arrayOf(item.songId))
        } else {
            db.delete(TABLE_SEARCH_HISTORY, "$COL_SH_QUERY = ? OR $COL_SH_TITLE = ?", arrayOf(trimmedQuery, trimmedTitle))
        }

        val values = ContentValues().apply {
            put(COL_SH_QUERY, trimmedQuery)
            put(COL_SH_TITLE, trimmedTitle)
            put(COL_SH_SUBTITLE, item.subtitle)
            put(COL_SH_IMAGE_URL, item.imageUrl)
            put(COL_SH_TYPE, item.contentType)
            put(COL_SH_SONG_ID, item.songId)
            put(COL_SH_TIMESTAMP, System.currentTimeMillis())
            put(COL_SH_MEDIA_URL, item.mediaUrl)
            put(COL_SH_MEDIA_URL_DEF, item.mediaUrlDefault)
            put(COL_SH_DURATION, item.durationSec)
            put(COL_SH_ALBUM, item.album)
            put(COL_SH_ARTISTS, item.artists)
        }
        val insertedId = db.insert(TABLE_SEARCH_HISTORY, null, values)

        // Prune older history beyond MAX_SEARCH_HISTORY_ROWS
        db.execSQL(
            """
            DELETE FROM $TABLE_SEARCH_HISTORY
            WHERE $COL_SH_ID NOT IN (
                SELECT $COL_SH_ID FROM $TABLE_SEARCH_HISTORY
                ORDER BY $COL_SH_TIMESTAMP DESC
                LIMIT $MAX_SEARCH_HISTORY_ROWS
            );
            """.trimIndent()
        )
        return insertedId
    }

    fun recordSongSearch(song: Song): Long {
        return recordSearchHistory(
            SearchHistoryItem(
                queryText = song.title,
                title = song.title,
                subtitle = "Song • ${song.artists}",
                imageUrl = song.imageUrl,
                contentType = "Song",
                songId = song.id,
                mediaUrl = song.mediaUrl,
                mediaUrlDefault = song.mediaUrlDefault,
                durationSec = song.durationSec,
                album = song.album,
                artists = song.artists
            )
        )
    }

    fun getRecentSearches(limit: Int = 10): List<SearchHistoryItem> {
        val list = mutableListOf<SearchHistoryItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SEARCH_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "$COL_SH_TIMESTAMP DESC",
            limit.toString()
        )
        cursor.use {
            val idxId = it.getColumnIndexOrThrow(COL_SH_ID)
            val idxQuery = it.getColumnIndexOrThrow(COL_SH_QUERY)
            val idxTitle = it.getColumnIndexOrThrow(COL_SH_TITLE)
            val idxSub = it.getColumnIndexOrThrow(COL_SH_SUBTITLE)
            val idxImg = it.getColumnIndexOrThrow(COL_SH_IMAGE_URL)
            val idxType = it.getColumnIndexOrThrow(COL_SH_TYPE)
            val idxSongId = it.getColumnIndexOrThrow(COL_SH_SONG_ID)
            val idxTime = it.getColumnIndexOrThrow(COL_SH_TIMESTAMP)
            val idxMediaUrl = it.getColumnIndex(COL_SH_MEDIA_URL)
            val idxMediaUrlDef = it.getColumnIndex(COL_SH_MEDIA_URL_DEF)
            val idxDuration = it.getColumnIndex(COL_SH_DURATION)
            val idxAlbum = it.getColumnIndex(COL_SH_ALBUM)
            val idxArtists = it.getColumnIndex(COL_SH_ARTISTS)

            while (it.moveToNext()) {
                list.add(
                    SearchHistoryItem(
                        id = it.getLong(idxId),
                        queryText = it.getString(idxQuery),
                        title = it.getString(idxTitle),
                        subtitle = it.getString(idxSub),
                        imageUrl = it.getString(idxImg),
                        contentType = it.getString(idxType),
                        songId = it.getString(idxSongId),
                        timestamp = it.getLong(idxTime),
                        mediaUrl = if (idxMediaUrl >= 0 && !it.isNull(idxMediaUrl)) it.getString(idxMediaUrl) else null,
                        mediaUrlDefault = if (idxMediaUrlDef >= 0 && !it.isNull(idxMediaUrlDef)) it.getString(idxMediaUrlDef) else null,
                        durationSec = if (idxDuration >= 0 && !it.isNull(idxDuration)) it.getInt(idxDuration) else 0,
                        album = if (idxAlbum >= 0 && !it.isNull(idxAlbum)) it.getString(idxAlbum) else "",
                        artists = if (idxArtists >= 0 && !it.isNull(idxArtists)) it.getString(idxArtists) else ""
                    )
                )
            }
        }
        return list
    }

    fun removeSearchHistoryItem(id: Long): Boolean {
        val db = writableDatabase
        return db.delete(TABLE_SEARCH_HISTORY, "$COL_SH_ID = ?", arrayOf(id.toString())) > 0
    }

    fun clearSearchHistory() {
        val db = writableDatabase
        db.delete(TABLE_SEARCH_HISTORY, null, null)
    }

    // ==========================================
    // Recommendation Feedback Operations
    // ==========================================

    fun recordNotInterested(song: Song): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_RF_SONG_ID, song.id)
            put(COL_RF_TITLE, song.title)
            put(COL_RF_ARTISTS, song.artists)
            put(COL_RF_ACTION, ACTION_NOT_INTERESTED)
            put(COL_RF_TIMESTAMP, System.currentTimeMillis())
        }
        return db.insert(TABLE_RECOMMENDATION_FEEDBACK, null, values)
    }

    fun hideSong(song: Song): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_RF_SONG_ID, song.id)
            put(COL_RF_TITLE, song.title)
            put(COL_RF_ARTISTS, song.artists)
            put(COL_RF_ACTION, ACTION_HIDE)
            put(COL_RF_TIMESTAMP, System.currentTimeMillis())
        }
        return db.insert(TABLE_RECOMMENDATION_FEEDBACK, null, values)
    }

    fun unhideSong(songId: String): Boolean {
        val db = writableDatabase
        return db.delete(
            TABLE_RECOMMENDATION_FEEDBACK,
            "$COL_RF_SONG_ID = ? AND $COL_RF_ACTION = ?",
            arrayOf(songId, ACTION_HIDE)
        ) > 0
    }

    fun isSongHidden(songId: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_RECOMMENDATION_FEEDBACK,
            arrayOf(COL_RF_ID),
            "$COL_RF_SONG_ID = ? AND $COL_RF_ACTION = ?",
            arrayOf(songId, ACTION_HIDE),
            null,
            null,
            null
        )
        return cursor.use { it.moveToFirst() }
    }

    fun getHiddenSongIds(): Set<String> {
        val ids = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_RECOMMENDATION_FEEDBACK,
            arrayOf(COL_RF_SONG_ID),
            "$COL_RF_ACTION = ?",
            arrayOf(ACTION_HIDE),
            null,
            null,
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                ids.add(it.getString(0))
            }
        }
        return ids
    }

    fun getNotInterestedSongIds(): Set<String> {
        val ids = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_RECOMMENDATION_FEEDBACK,
            arrayOf(COL_RF_SONG_ID),
            "$COL_RF_ACTION = ?",
            arrayOf(ACTION_NOT_INTERESTED),
            null,
            null,
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                ids.add(it.getString(0))
            }
        }
        return ids
    }

    fun getNotInterestedArtists(): Set<String> {
        val artists = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_RECOMMENDATION_FEEDBACK,
            arrayOf(COL_RF_ARTISTS),
            "$COL_RF_ACTION = ?",
            arrayOf(ACTION_NOT_INTERESTED),
            null,
            null,
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                val raw = it.getString(0) ?: ""
                for (a in raw.split(",", "&", "/")) {
                    val t = a.trim().lowercase()
                    if (t.isNotEmpty()) artists.add(t)
                }
            }
        }
        return artists
    }

    fun resetLearnedPreferences() {
        val db = writableDatabase
        db.delete(TABLE_HISTORY, null, null)
        db.delete(TABLE_RECOMMENDATION_FEEDBACK, null, null)
    }

    // ==========================================
    // Cached Song Lookups for Resilient Playback
    // ==========================================

    fun getLikedSong(songId: String): Song? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LIKED,
            null,
            "$COL_L_SONG_ID = ?",
            arrayOf(songId),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return Song(
                    id = it.getString(it.getColumnIndexOrThrow(COL_L_SONG_ID)),
                    title = it.getString(it.getColumnIndexOrThrow(COL_L_TITLE)),
                    artists = it.getString(it.getColumnIndexOrThrow(COL_L_ARTISTS)),
                    album = it.getString(it.getColumnIndexOrThrow(COL_L_ALBUM)),
                    imageUrl = it.getString(it.getColumnIndexOrThrow(COL_L_IMAGE_URL)),
                    durationSec = it.getInt(it.getColumnIndexOrThrow(COL_L_DURATION)),
                    mediaUrl = it.getString(it.getColumnIndexOrThrow(COL_L_MEDIA_URL)),
                    mediaUrlDefault = it.getString(it.getColumnIndexOrThrow(COL_L_MEDIA_URL_DEF)),
                    previewUrl = it.getString(it.getColumnIndexOrThrow(COL_L_PREVIEW_URL))
                )
            }
        }
        return null
    }

    fun getHistorySong(songId: String): Song? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_HISTORY,
            null,
            "$COL_H_SONG_ID = ?",
            arrayOf(songId),
            null,
            null,
            "$COL_H_PLAYED_AT DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return Song(
                    id = it.getString(it.getColumnIndexOrThrow(COL_H_SONG_ID)),
                    title = it.getString(it.getColumnIndexOrThrow(COL_H_TITLE)),
                    artists = it.getString(it.getColumnIndexOrThrow(COL_H_ARTISTS)),
                    album = it.getString(it.getColumnIndexOrThrow(COL_H_ALBUM)),
                    imageUrl = it.getString(it.getColumnIndexOrThrow(COL_H_IMAGE_URL)),
                    durationSec = it.getInt(it.getColumnIndexOrThrow(COL_H_DURATION)),
                    mediaUrl = it.getString(it.getColumnIndexOrThrow(COL_H_MEDIA_URL)),
                    mediaUrlDefault = it.getString(it.getColumnIndexOrThrow(COL_H_MEDIA_URL_DEF)),
                    previewUrl = it.getString(it.getColumnIndexOrThrow(COL_H_PREVIEW_URL))
                )
            }
        }
        return null
    }

    fun findCachedSong(songId: String): Song? {
        getDownloadedSong(songId)?.let { if (it.getBestPlaybackUrl() != null) return it }
        getLikedSong(songId)?.let { if (it.getBestPlaybackUrl() != null) return it }
        getHistorySong(songId)?.let { if (it.getBestPlaybackUrl() != null) return it }

        // Also check playlist songs
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PLAYLIST_SONGS,
            null,
            "$COL_PS_SONG_ID = ?",
            arrayOf(songId),
            null,
            null,
            "$COL_PS_ID DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return Song(
                    id = it.getString(it.getColumnIndexOrThrow(COL_PS_SONG_ID)),
                    title = it.getString(it.getColumnIndexOrThrow(COL_PS_TITLE)),
                    artists = it.getString(it.getColumnIndexOrThrow(COL_PS_ARTISTS)),
                    album = it.getString(it.getColumnIndexOrThrow(COL_PS_ALBUM)),
                    imageUrl = it.getString(it.getColumnIndexOrThrow(COL_PS_IMAGE_URL)),
                    durationSec = it.getInt(it.getColumnIndexOrThrow(COL_PS_DURATION)),
                    mediaUrl = it.getString(it.getColumnIndexOrThrow(COL_PS_MEDIA_URL)),
                    mediaUrlDefault = it.getString(it.getColumnIndexOrThrow(COL_PS_MEDIA_URL_DEF)),
                    previewUrl = it.getString(it.getColumnIndexOrThrow(COL_PS_PREVIEW_URL))
                )
            }
        }
        return null
    }
}
