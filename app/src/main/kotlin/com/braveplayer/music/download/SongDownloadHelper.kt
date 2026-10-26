package com.braveplayer.music.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import java.io.File

class SongDownloadHelper(
    private val context: Context,
    private val playlistDbHelper: PlaylistDatabaseHelper
) {

    fun downloadSong(
        song: Song,
        onSuccess: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val downloadUrl = song.mediaUrl ?: song.getBestPlaybackUrl()
        if (downloadUrl.isNullOrBlank()) {
            val err = "Download stream unavailable for '${song.title}'"
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            onError?.invoke(err)
            return
        }

        try {
            val safeTitle = sanitizeFilename(song.title.ifEmpty { "Track" })
            val safeArtist = sanitizeFilename(song.artists.ifEmpty { "Artist" })
            val fileName = "$safeTitle - $safeArtist.mp3"

            val subPath = "BravePlayer/$fileName"
            val externalMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val fullTargetFile = File(externalMusicDir, subPath)

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("${song.title} - ${song.artists}")
                setDescription("Downloading to Music/BravePlayer")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, subPath)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            // Save record in database for offline playback tracking
            playlistDbHelper.saveDownloadedSong(song, fullTargetFile.absolutePath)

            Toast.makeText(context, "Downloading: ${song.title} ⬇", Toast.LENGTH_SHORT).show()
            onSuccess?.invoke(fullTargetFile.absolutePath)
        } catch (e: Exception) {
            val msg = "Download failed: ${e.message}"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            onError?.invoke(msg)
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(50)
    }
}
