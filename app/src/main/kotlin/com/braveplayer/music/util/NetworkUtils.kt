package com.braveplayer.music.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File

object NetworkUtils {

    /**
     * Checks if the device has an active network connection capable of streaming online music.
     */
    fun isOnline(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }

    /**
     * Purges temporary audio streaming files, response caches, and ExoPlayer cache directories
     * from application cache to ensure streaming tracks never consume persistent device storage.
     */
    fun clearAudioStreamingCache(context: Context) {
        try {
            // Delete system HTTP response cache if active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                try {
                    android.net.http.HttpResponseCache.getInstalled()?.delete()
                } catch (_: Exception) {}
            }

            // Clean application cache directory of cached media chunks and exoplayer files
            val cacheDirectory = context.cacheDir ?: return
            cleanDirectoryOfAudioFiles(cacheDirectory)

            // Also check external cache if present
            context.externalCacheDir?.let { cleanDirectoryOfAudioFiles(it) }
        } catch (_: Exception) {
            // Fail safely without affecting UI or playback
        }
    }

    private fun cleanDirectoryOfAudioFiles(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val dirName = file.name.lowercase()
                if (dirName.contains("exo") || dirName.contains("media") || dirName.contains("audio") || dirName.contains("cache")) {
                    file.deleteRecursively()
                } else {
                    cleanDirectoryOfAudioFiles(file)
                }
            } else {
                val fileName = file.name.lowercase()
                if (fileName.endsWith(".mp3") || fileName.endsWith(".mp4") ||
                    fileName.endsWith(".m4a") || fileName.endsWith(".aac") ||
                    fileName.endsWith(".opus") || fileName.endsWith(".exo") ||
                    fileName.endsWith(".uid") || fileName.contains("media3")) {
                    file.delete()
                }
            }
        }
    }
}
