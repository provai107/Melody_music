package com.braveplayer.music.discovery

import com.braveplayer.music.model.Song
import com.braveplayer.music.playlist.PlaylistDatabaseHelper
import com.braveplayer.music.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tracks the musical identity, dominant genres, and artist presence of the current playback session.
 */
data class SessionContext(
    val currentSong: Song? = null,
    val sessionArtists: List<String> = emptyList(),
    val sessionSongIds: Set<String> = emptySet(),
    val dominantGenres: Map<String, Int> = emptyMap(),
    val isAlbumContext: Boolean = false,
    val albumTitle: String? = null
) {
    fun withNextSong(song: Song): SessionContext {
        val primaryArtist = MusicSimilarityService.extractPrimaryArtist(song.artists).lowercase()
        val genres = MusicSimilarityService.inferGenres(song)
        val updatedGenres = dominantGenres.toMutableMap()
        for (g in genres) {
            updatedGenres[g] = (updatedGenres[g] ?: 0) + 1
        }
        val isSameAlbum = isAlbumContext && !albumTitle.isNullOrBlank() &&
            song.album.trim().equals(albumTitle.trim(), ignoreCase = true)

        return copy(
            currentSong = song,
            sessionArtists = (sessionArtists + primaryArtist).takeLast(20),
            sessionSongIds = sessionSongIds + song.id,
            dominantGenres = updatedGenres,
            isAlbumContext = isSameAlbum,
            albumTitle = if (isSameAlbum) albumTitle else song.album.takeIf { it.isNotBlank() }
        )
    }
}

/**
 * Dynamic Next-Song Prediction Engine evaluating session context, artist cooldowns,
 * multi-tier candidate pools, and 7-factor weighted scoring.
 */
class NextSongPredictor(
    private val discoveryEngine: DiscoveryEngine,
    private val tasteAnalyzer: UserTasteAnalyzer,
    private val recommendationScorer: RecommendationScorer = RecommendationScorer(),
    private val dbHelper: PlaylistDatabaseHelper
) {

    /**
     * Predicts a single best next track to play after [seedSong].
     */
    suspend fun predictNextSong(
        baseUrl: String,
        seedSong: Song,
        sessionContext: SessionContext = SessionContext(currentSong = seedSong),
        upcomingQueueSongIds: Set<String> = emptySet(),
        upcomingQueueTitles: Set<String> = emptySet(),
        additionalCandidates: List<Song> = emptyList(),
        personalizationEnabled: Boolean = true,
        avoidRecentlyPlayed: Boolean = true,
        diversityMode: String = AppSettings.DIVERSITY_BALANCED,
        recommendationInfluence: String = AppSettings.INFLUENCE_MEDIUM,
        excludeDislikedTracks: Boolean = true
    ): Song? = withContext(Dispatchers.IO) {
        val ranked = predictNextSongs(
            count = 1,
            baseUrl = baseUrl,
            seedSong = seedSong,
            sessionContext = sessionContext,
            upcomingQueueSongIds = upcomingQueueSongIds,
            upcomingQueueTitles = upcomingQueueTitles,
            additionalCandidates = additionalCandidates,
            personalizationEnabled = personalizationEnabled,
            avoidRecentlyPlayed = avoidRecentlyPlayed,
            diversityMode = diversityMode,
            recommendationInfluence = recommendationInfluence,
            excludeDislikedTracks = excludeDislikedTracks
        )
        ranked.firstOrNull()
    }

    /**
     * Predicts a sequence of [count] suitable songs, dynamically updating context
     * to maintain musical flow and introduce gradual diversity.
     */
    suspend fun predictNextSongs(
        count: Int = 6,
        baseUrl: String,
        seedSong: Song,
        sessionContext: SessionContext = SessionContext(currentSong = seedSong),
        upcomingQueueSongIds: Set<String> = emptySet(),
        upcomingQueueTitles: Set<String> = emptySet(),
        additionalCandidates: List<Song> = emptyList(),
        personalizationEnabled: Boolean = true,
        avoidRecentlyPlayed: Boolean = true,
        diversityMode: String = AppSettings.DIVERSITY_BALANCED,
        recommendationInfluence: String = AppSettings.INFLUENCE_MEDIUM,
        excludeDislikedTracks: Boolean = true
    ): List<Song> = withContext(Dispatchers.IO) {
        val tasteProfile = tasteAnalyzer.analyzeProfile(personalizationEnabled)
        val candidatePool = discoveryEngine.generateCandidatePool(
            baseUrl = baseUrl,
            seedSong = seedSong,
            tasteProfile = tasteProfile,
            diversityMode = diversityMode,
            additionalCandidates = additionalCandidates
        )

        val allCandidates = candidatePool.allCandidates()
        if (allCandidates.isEmpty()) return@withContext emptyList()

        val recentSongIds = if (personalizationEnabled && avoidRecentlyPlayed) {
            dbHelper.getRecentSongIds(30)
        } else {
            emptySet()
        }

        val excludedIds = buildSet {
            add(seedSong.id)
            addAll(sessionContext.sessionSongIds)
            addAll(upcomingQueueSongIds)
            addAll(recentSongIds)
            addAll(tasteProfile.hiddenSongIds)
            if (excludeDislikedTracks) {
                addAll(tasteProfile.notInterestedSongIds)
                addAll(tasteProfile.skippedSongIds)
            }
        }

        val excludedTitles = buildSet {
            add(seedSong.title)
            sessionContext.currentSong?.let { add(it.title) }
            addAll(upcomingQueueTitles)
        }

        val recentSearches = if (personalizationEnabled) {
            dbHelper.getRecentSearches(10).map { it.queryText.lowercase().trim() }
        } else {
            emptyList()
        }

        rankCandidates(
            candidates = allCandidates,
            seedSong = seedSong,
            sessionContext = sessionContext,
            excludeSongIds = excludedIds,
            excludeTitles = excludedTitles,
            tasteProfile = tasteProfile,
            limit = count,
            diversityMode = diversityMode,
            recommendationInfluence = recommendationInfluence,
            recentSearchQueries = recentSearches,
            personalizationEnabled = personalizationEnabled
        )
    }

    /**
     * Ranks candidate songs using 7-factor weighted scoring, artist repetition cooldowns,
     * and album context affinity.
     */
    fun rankCandidates(
        candidates: List<Song>,
        seedSong: Song,
        sessionContext: SessionContext,
        excludeSongIds: Set<String>,
        excludeTitles: Set<String>,
        tasteProfile: UserTasteProfile,
        limit: Int = 10,
        diversityMode: String = AppSettings.DIVERSITY_BALANCED,
        recommendationInfluence: String = AppSettings.INFLUENCE_MEDIUM,
        recentSearchQueries: List<String> = emptyList(),
        personalizationEnabled: Boolean = true
    ): List<Song> {
        val seenIds = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()
        val scoredList = mutableListOf<Pair<Song, Double>>()

        val recentArtistWindow = sessionContext.sessionArtists.takeLast(6)
        val seedAlbum = seedSong.album.trim().lowercase()

        for (candidate in candidates) {
            // Guard conditions
            if (candidate.durationSec in 1..30) continue
            if (candidate.getBestPlaybackUrl() == null) continue
            if (candidate.id == seedSong.id) continue
            if (candidate.id in excludeSongIds) continue
            if (!seenIds.add(candidate.id)) continue

            // Title deduplication against seed and queue
            if (MusicSimilarityService.areTitlesTooSimilar(candidate.title, seedSong.title)) continue
            if (excludeTitles.any { MusicSimilarityService.areTitlesTooSimilar(candidate.title, it) }) continue

            // Intra-candidate deduplication
            val normTitle = MusicSimilarityService.normalizeTitle(candidate.title)
            if (normTitle.isNotEmpty() && !seenTitles.add(normTitle)) continue

            // Exclude Not Interested or Hidden tracks & artists
            if (candidate.id in tasteProfile.notInterestedSongIds || candidate.id in tasteProfile.hiddenSongIds) continue
            val candidatePrimaryArtist = MusicSimilarityService.extractPrimaryArtist(candidate.artists).lowercase()
            if (candidatePrimaryArtist in tasteProfile.notInterestedArtists) continue

            // 7-Factor Weighted Scoring
            val scored = recommendationScorer.scoreCandidate(
                candidate = candidate,
                seedSong = seedSong,
                tasteProfile = tasteProfile,
                diversityMode = diversityMode,
                recommendationInfluence = recommendationInfluence,
                recentSessionArtists = recentArtistWindow.toSet(),
                recentSearchQueries = recentSearchQueries,
                isPersonalizationEnabled = personalizationEnabled
            )

            var score = scored.totalScore

            // Album / Soundtrack affinity boost
            val isSameAlbum = seedAlbum.isNotBlank() &&
                seedAlbum != "unknown album" &&
                seedAlbum != "unknown" &&
                seedAlbum != "single" &&
                seedAlbum != "ep" &&
                candidate.album.trim().equals(seedAlbum, ignoreCase = true)
            if (isSameAlbum) {
                score += 25.0
            }

            // Artist repetition cooldown: Max 2 tracks per 6-track window unless same album
            val artistOccurrencesInWindow = recentArtistWindow.count { it.equals(candidatePrimaryArtist, ignoreCase = true) }
            if (!isSameAlbum && artistOccurrencesInWindow >= 2) {
                score -= 35.0 // Strong cooldown fatigue penalty
            }

            scoredList.add(Pair(candidate, score))
        }

        scoredList.sortByDescending { it.second }

        // Diversity-aware artist capping in output batch
        val finalResult = mutableListOf<Song>()
        val artistCount = mutableMapOf<String, Int>()
        val maxPerArtist = when {
            limit > 10 -> if (diversityMode == AppSettings.DIVERSITY_FAMILIAR) 5 else 4
            diversityMode == AppSettings.DIVERSITY_FAMILIAR -> 3
            else -> 2
        }

        for ((song, _) in scoredList) {
            if (finalResult.size >= limit) break
            val artist = MusicSimilarityService.extractPrimaryArtist(song.artists).lowercase()
            val count = artistCount[artist] ?: 0
            val isSameAlbum = seedAlbum.isNotBlank() &&
                seedAlbum != "unknown album" &&
                seedAlbum != "unknown" &&
                seedAlbum != "single" &&
                seedAlbum != "ep" &&
                song.album.trim().equals(seedAlbum, ignoreCase = true)

            if (count < maxPerArtist || isSameAlbum) {
                finalResult.add(song)
                artistCount[artist] = count + 1
            }
        }

        // Fill remaining if artist cap was too strict
        if (finalResult.size < limit) {
            for ((song, _) in scoredList) {
                if (finalResult.size >= limit) break
                if (song !in finalResult) {
                    finalResult.add(song)
                }
            }
        }

        return finalResult
    }
}
