package com.braveplayer.music.discovery

import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates autonomous playback continuity when the queue empties or drops below threshold.
 * Asynchronously generates cohesive song batches maintaining genre flow and session continuity.
 */
class AutoplayManager(
    private val nextSongPredictor: NextSongPredictor,
    private val appSettings: AppSettings,
    private val dbHelper: PlaylistDatabaseHelper
) {
    private val isFetching = AtomicBoolean(false)

    fun isFetchingAutoplay(): Boolean = isFetching.get()

    /**
     * Prefetches an intelligent batch of autoplay recommendations based on the current song and session context.
     * Guaranteed to never block audio playback threads and gracefully falls back to local database favorites.
     */
    suspend fun prefetchAutoplayBatch(
        baseUrl: String,
        seedSong: Song,
        sessionContext: SessionContext,
        currentQueueSongIds: Set<String>,
        currentQueueTitles: Set<String>,
        batchSize: Int = 6,
        force: Boolean = false
    ): List<Song> = withContext(Dispatchers.IO) {
        if (!appSettings.isAutoplayEnabled()) return@withContext emptyList()
        if (!isFetching.compareAndSet(false, true)) {
            if (!force) return@withContext emptyList()
        }

        try {
            val personalizationEnabled = appSettings.isPersonalizationEnabled()
            val avoidRecentlyPlayed = appSettings.shouldAvoidRecentlyPlayed()
            val diversityMode = appSettings.getRecommendationDiversity()
            val influence = appSettings.getRecommendationInfluence()
            val excludeDisliked = appSettings.shouldExcludeDislikedTracks()

            val predictedSongs = nextSongPredictor.predictNextSongs(
                count = batchSize,
                baseUrl = baseUrl,
                seedSong = seedSong,
                sessionContext = sessionContext,
                upcomingQueueSongIds = currentQueueSongIds,
                upcomingQueueTitles = currentQueueTitles,
                personalizationEnabled = personalizationEnabled,
                avoidRecentlyPlayed = avoidRecentlyPlayed,
                diversityMode = diversityMode,
                recommendationInfluence = influence,
                excludeDislikedTracks = excludeDisliked
            )

            if (predictedSongs.isNotEmpty()) {
                return@withContext predictedSongs
            }

            // Fallback: Local database favorites & most played tracks if network returned empty
            val localLiked = dbHelper.getAllLikedSongs()
            val localPlayed = dbHelper.getMostPlayedSongs(20).map { it.first }
            val fallbackCandidates = (localLiked + localPlayed).filter {
                it.id != seedSong.id &&
                    it.id !in currentQueueSongIds &&
                    it.getBestPlaybackUrl() != null &&
                    !MusicSimilarityService.areTitlesTooSimilar(it.title, seedSong.title)
            }.distinctBy { it.id }

            if (fallbackCandidates.isNotEmpty()) {
                val tasteProfile = UserTasteProfile(
                    likedSongIds = dbHelper.getLikedSongIds(),
                    skippedSongIds = dbHelper.getSkippedSongIds(),
                    notInterestedSongIds = dbHelper.getNotInterestedSongIds(),
                    notInterestedArtists = dbHelper.getNotInterestedArtists(),
                    hiddenSongIds = dbHelper.getHiddenSongIds()
                )
                return@withContext nextSongPredictor.rankCandidates(
                    candidates = fallbackCandidates,
                    seedSong = seedSong,
                    sessionContext = sessionContext,
                    excludeSongIds = currentQueueSongIds + seedSong.id,
                    excludeTitles = currentQueueTitles + seedSong.title,
                    tasteProfile = tasteProfile,
                    limit = batchSize,
                    diversityMode = diversityMode,
                    recommendationInfluence = influence,
                    personalizationEnabled = personalizationEnabled
                )
            }

            emptyList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            isFetching.set(false)
        }
    }
}
