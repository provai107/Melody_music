# Verification, Testing & Troubleshooting Guide

This document outlines the testing procedures, build commands, automated test suites, and troubleshooting steps for the music engine.

---

## 1. Automated Test Suites & Commands

### Running All Unit Tests
Run the complete unit test suite across both filter and music engine modules:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 gradle :filter-core:test :app:testDebugUnitTest --rerun-tasks
```

### Building the Debug APK
```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 gradle :app:assembleDebug
```
Output artifact location: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 2. Test Coverage & Verification Matrix

The engine includes **52 automated unit tests** across two modules:

### A. Music Engine Module Tests (`:app` / `:music-engine`) — 34 Tests
1. **`LikedSongsDatabaseTest` (3 Tests)**:
   - `testLikeAndUnlikeSong`: Validates insert, retrieval, and removal from `liked_songs` table.
   - `testDuplicateLikeDoesNotCreateDuplicateRows`: Verifies `CONFLICT_REPLACE` deduplication.
   - `testLikedSongsSortedByTimestampDescending`: Verifies most recent likes appear first.
2. **`ListeningHistoryDatabaseTest` (3 Tests)**:
   - `testRecordMeaningfulPlayVsQuickSkip`: Confirms plays <30s are marked skipped and excluded from most-played list.
   - `testArtistPlayCountsAggregation`: Confirms multi-artist splitting and count accumulation.
   - `testStorageGrowthCappedAtMaxRows`: Inserts 520 records; confirms table count is capped at 500 rows.
3. **`MusicRecommendationEngineTest` (3 Tests)**:
   - `testCandidateRankingScoring`: Verifies artist match boost (+45), album match (+20), and skip penalty (-30).
   - `testExcludedSongsAndInvalidUrlsAreFiltered`: Ensures current song, recent songs, and broken URLs are omitted.
   - `testDeduplicationByTitleAndArtist`: Confirms normalized title/artist deduplication.
4. **`MixGeneratorTest` (2 Tests)**:
   - `testDiscoveryMixPrioritizesUnfamiliarArtists`: Validates +50 novelty bonus for artists not in history.
   - `testSaveMixAsPlaylistPersistsInDatabase`: Tests saving generated mix into SQLite playlist table.
5. **`AutoplayQueueExtensionTest` (4 Tests)**:
   - `testAutoplayPreferenceToggle`: Tests preference persistence.
   - `testRepeatModeSuppressesAutoplayExtension`: Tests repeat-all and repeat-one suppression.
   - `testFailedTracksExcludedFromSubsequentRecommendations`: Validates broken stream IDs are not re-recommended.
   - `testQueuePreservesManualOrderAheadOfRecommendations`: Ensures user queue remains ahead.
6. **`PlaylistDatabaseHelperTest` (4 Tests)**:
   - `testCreateAndGetAllPlaylists`: Verifies playlist creation and listing.
   - `testAddAndGetSongsInPlaylist`: Validates track insertion and retrieval in sort order.
   - `testRemoveSongFromPlaylist`: Tests track deletion.
   - `testDeletePlaylistCascadesToSongs`: Confirms foreign key cascade deletes associated songs.
7. **`QueuePersistenceManagerTest` (3 Tests)**:
   - `testEmptyStateReturnsDefaultValues`: Verifies safe fallback for empty state.
   - `testSaveAndRestoreQueueState`: Validates JSON serialization of queue, index, position, repeat, and shuffle.
   - `testClearQueueState`: Verifies queue clearing.
8. **`SongMediaItemMapperTest` (2 Tests)**:
   - `testToMediaItemAndFromMediaItemBidirectionalMapping`: Validates lossless conversion.
   - `testFallbackCandidateMediaItemGeneration`: Verifies building fallback MediaItem pointing to alternate URLs.
9. **`BraveMedia3HttpDataSourceTest` (4 Tests)**:
   - `testLegitimateAudioCdnPlaybackOpensSuccessfully`: Verifies CDN stream passes policy.
   - `testSeekingRangeHeadersPreserved`: Verifies HTTP Range headers pass through.
   - `testProviderRequiredAdSegmentsPreserved`: Confirms ad tokens are preserved.
   - `testBlockedTrackerThrowsHttpDataSourceException`: Verifies telemetry domain blocking.
10. **`SearchResponseParserTest` (6 Tests)**:
    - Multi-song parsing, empty arrays, backend errors, candidate URL prioritization, HTML entity decoding, and missing optional fields.

### B. Core Filtering Module Tests (`:filter-core`) — 18 Tests
- Exact domain matching, subdomain inheritance, lookalike domain homograph defense, allowlists, atomic filter updates, rollback on corrupt checksums, statistics accuracy, range request seeking, and simulated network retries.

---

## 3. End-to-End Feature Verification Checklist

| Feature | Verification Method | Expected Result |
|---|---|---|
| **Search** | Query e.g. "Arijit" with backend running | Song list populates with title, artists, album, duration, and cover image. |
| **Foreground Playback** | Tap any song row | Starts streaming audio; title/artist and position update; status shows "Playing". |
| **Background Playback** | Press Home button or lock screen | Audio continues streaming; notification shows track title, artist, play/pause, next, and previous buttons. |
| **Headphone Disconnect** | Unplug headphones while playing | Audio immediately pauses without error (`setHandleAudioBecomingNoisy`). |
| **Liked Songs** | Tap heart icon on current song or search row | Icon turns pink `♥`; song appears in Liked Songs dialog; stays saved across app restart. |
| **Song Radio** | Long-press song -> "Start Radio" | Immediately queues seed song followed by 15 related tracks. |
| **Autoplay Extension** | Let last queued track finish with Autoplay On | Automatically fetches 5 recommendations and continues playing without pause. |
| **Daily Mix** | Tap "Mixes" -> "Daily Mix" | Generates personalized playlist blending top played tracks and liked artists. |
| **Discovery Mix** | Tap "Mixes" -> "Discovery Mix" | Generates playlist featuring artists outside user's frequent listening rotation. |
| **Listening History** | Listen to a song for >30s, skip another in <5s | "Recently Played" shows both; "Most Played" only counts the >30s track. |
| **Stream URL Failover** | Stream 320kbps MP3 that returns 404 | Seamlessly fails over to 96kbps MP4 or preview clip without halting playback. |

---

## 4. Known Limitations & Troubleshooting

### 1. JioSaavn 320kbps Stream Expiry / HTTP 404
- **Symptom**: Certain older songs return HTTP 404 when requesting `media_url` (`_320.mp3`).
- **Resolution**: `Song.getBestPlaybackUrl()` automatically prioritizes `media_url_default` (`_96.mp4`), which has much higher availability. If a 404 occurs during playback, `PlaybackService` automatically fails over to the preview URL (`vlink`) or skips to the next track.

### 2. Physical Device Connection Issues (`Failed to connect to /10.0.2.2:5000`)
- **Cause**: `10.0.2.2` only works within the Android Emulator.
- **Resolution**: On physical devices, enter the host PC's Wi-Fi LAN IP (e.g. `http://192.168.1.150:5000`) into the top bar, or use `adb reverse tcp:5000 tcp:5000` with `http://localhost:5000`.

### 3. Cleartext HTTP Rejected
- **Symptom**: `CLEARTEXT communication to 10.0.2.2 not permitted by network security policy`.
- **Resolution**: Ensure `android:usesCleartextTraffic="true"` is declared on `<application>` in `AndroidManifest.xml`.
