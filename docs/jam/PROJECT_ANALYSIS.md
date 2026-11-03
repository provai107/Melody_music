# MELODY JAM — COMPREHENSIVE PROJECT ANALYSIS & SYSTEM ARCHITECTURE

**Document Version:** 1.0.0  
**Date:** 2026-11-04  
**Target Application:** Melody (Package: `com.braveplayer.music`)  
**Feature Name:** Melody Jam (Group Listening & Real-Time Playback Synchronization)

---

## 1. Executive Summary & Codebase Structure

Melody is an intelligent, high-performance Android music streaming application written in Kotlin, built on top of modern Android components (API 24 to 34) and AndroidX Media3 (ExoPlayer 1.3.1).

### Repository Overview
```text
Melody_music/
├── app/                              # Android Application Module
│   ├── build.gradle.kts             # AGP 8.5.2, Kotlin 1.9.24, Java 17/21, Media3 1.3.1
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   # MediaSessionService, Foreground Service Media Playback
│       │   ├── kotlin/com/braveplayer/music/
│       │   │   ├── api/             # MusicMetadataService (JioSaavn API integration via OkHttp)
│       │   │   ├── discovery/       # MixGenerator, Catalog, RecommendationEngine, GenreEngine
│       │   │   ├── download/        # SongDownloadHelper
│       │   │   ├── lyrics/          # LyricsService, SyncedLyricsAdapter
│       │   │   ├── media/           # BraveMedia3HttpDataSource, SongMediaItemMapper
│       │   │   ├── model/           # Song, SearchResponseParser, MovieSoundtrack, Deduplicator
│       │   │   ├── player/          # CentralizedPlaybackController, PlaybackModels
│       │   │   ├── playlist/        # PlaylistDatabaseHelper (SQLite for Playlists & History)
│       │   │   ├── service/         # PlaybackService (MediaSessionService, ExoPlayer)
│       │   │   ├── settings/        # AppSettings (SharedPreferences), QueuePersistenceManager
│       │   │   ├── ui/              # Adapters for Home, Queue, Search, Artists, Releases
│       │   │   └── update/          # In-app GitHub update pipeline
│       │   └── res/                 # Layouts, Drawables, Themes, Burgundy/Black Color Palette
│       └── test/                    # 23 Unit test suites (Robolectric, JUnit 4, AssertJ)
├── filter-core/                      # Kotlin/JVM library for rule filtering & CDN protection
├── MUSIC_ENGINE_TRANSFER/            # Reference transfer package with JioSaavn FastAPI backend
├── docs/                             # Documentation
└── release.keystore                  # Production release keystore
```

---

## 2. Technical Stack & Versions

| Component | Specification |
|---|---|
| **Programming Language** | Kotlin 1.9.24 |
| **Android SDK** | compileSdk: 34, targetSdk: 34, minSdk: 24 |
| **Build System** | Gradle 8.9 (via Gradle Wrapper) + AGP 8.5.2 |
| **Java Target** | Java 17 / 21 Compatible |
| **Audio Playback Engine** | AndroidX Media3 1.3.1 (`media3-exoplayer`, `media3-session`, `media3-ui`) |
| **HTTP & Networking** | Square OkHttp 4.12.0 + Kotlin Coroutines Android 1.8.0 |
| **Local Database** | Native Android SQLite (`PlaylistDatabaseHelper`) |
| **Preferences** | Android `SharedPreferences` (`AppSettings`) |
| **UI Framework** | Android Views, Material Design 3 (`com.google.android.material:material:1.12.0`), BottomNavigationView, BottomSheetDialog, ConstraintLayout |
| **Test Framework** | JUnit 4.13.2, AssertJ Core 3.25.3, Robolectric 4.12.2 |

---

## 3. Architecture & Existing Music Playback Engine

### Single Source of Truth: `CentralizedPlaybackController`
- Melody implements a centralized singleton controller: `CentralizedPlaybackController.getInstance(context)`.
- It maintains the definitive playback state machine:
  - `currentItem: QueueItem?`
  - `playNextQueue: MutableList<QueueItem>` (priority ordered)
  - `manualQueue: MutableList<QueueItem>` (user added tracks)
  - `activeContextTracks: MutableList<QueueItem>` (album, playlist, search radio)
  - `autoplayQueue: MutableList<QueueItem>` (algorithmically recommended)
  - `playbackHistory: MutableList<QueueItem>` (recently finished tracks)
- Emits real-time state changes via `StateFlow<QueueSnapshot>` and `CentralizedPlaybackController.Listener`:
  - `onSongChanged(song: Song?, queueItem: QueueItem?)`
  - `onPlaybackStateChanged(state: PlaybackState)`
  - `onQueueUpdated(snapshot: QueueSnapshot)`
  - `onPositionDiscontinuity(positionMs: Long)`

### Background Playback: `PlaybackService`
- Extends `androidx.media3.session.MediaSessionService`.
- Owns the single `ExoPlayer` instance configured with `BraveMedia3HttpDataSource`.
- Manages audio focus (`C.AUDIO_CONTENT_TYPE_MUSIC`, `C.USAGE_MEDIA`), automatic pause on headphone disconnect (`setHandleAudioBecomingNoisy(true)`), and Android 14 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- Attaches the `ExoPlayer` directly to `CentralizedPlaybackController.attachPlayer(exoPlayer)`.
- Provides lock-screen media notifications and system media transport controls via MediaSession.

---

## 4. Track Identification & Streaming Architecture

### The `Song` Model
Every track in Melody is represented by `com.braveplayer.music.model.Song`:
- `id`: Unique JioSaavn track identifier (e.g. `"s_h8V2_g"`, `"15514682"`).
- `title`: Song title.
- `artists`: Formatted primary and featured artists string.
- `album`: Album or movie name.
- `imageUrl`: High-resolution artwork URL.
- `durationSec`: Duration in seconds.
- `mediaUrlDefault`: High-bitrate CDN audio stream URL (AAC/MP4).
- `mediaUrl`: Secondary direct CDN stream URL.
- `previewUrl`: Preview/sample stream URL.
- `releaseDate`, `year`, `playCount`: Additional metadata.

### Audio Streaming Policy (CRITICAL FOR MELODY JAM)
- **Zero Audio Relaying**: Audio is **never** streamed through the Jam server. The Jam server acts purely as a signaling and state synchronization coordinator.
- **Independent Audio Streams**: Each participating Android client fetches audio directly from authorized CDN endpoints (`*.saavncdn.com`) using its local `BraveMedia3HttpDataSource`.
- **Track Resolution**: When a host changes tracks or a guest joins, the server distributes track identifiers (`trackId`) and essential metadata (`SongSummary`). The guest resolves the track locally via `MusicMetadataService.fetchSongById(trackId)` or uses cached metadata. If a track cannot be resolved or is regionally unavailable, the UI shows a graceful unavailable badge without breaking room connectivity.

---

## 5. Existing Backend vs. Melody Jam Backend

### Current State
- The client currently connects directly to JioSaavn public API endpoints (`https://www.jiosaavn.com/api.php`) for metadata discovery.
- `MUSIC_ENGINE_TRANSFER/backend` contains a reference FastAPI scraper, but there is currently **no existing real-time room or session backend** running for Melody.

### Melody Jam Real-Time Backend Decision
- We will implement a dedicated, ultra-lightweight Node.js (TypeScript/JavaScript) Jam Backend Server (`melody-jam-server`).
- Built using **Node.js + WebSockets / Socket.IO** with native JSON protocol.
- On the Android client, we leverage **OkHttp's native WebSocket (`okhttp3.WebSocket`)**, which is already part of Melody's core dependencies (`com.squareup.okhttp3:okhttp:4.12.0`). This guarantees zero dependency bloat, maximum memory efficiency, rock-solid thread safety, and seamless coroutine integration.

---

## 6. Guest Identity & Security Architecture

### Zero Mandatory Accounts
- Melody currently requires no user accounts. Melody Jam will strictly adhere to this philosophy: **no username/password registration or OAuth required**.

### Android Keystore-Backed Guest Sessions
1. **First Launch**: A cryptographic UUID is generated and stored locally as the client installation identifier.
2. **Display Name**: Defaults to `Melody User #XXXX` (or user-customized nickname).
3. **Session Authentication**:
   - Client requests `POST /api/v1/jam/guest/session` with `{ installationId, displayName }`.
   - Server mints a cryptographically signed HMAC-SHA256 **Guest Access Token** containing `guestId`, `role`, and expiration timestamp.
   - Client stores token securely in Android SharedPreferences encrypted via **Android Keystore (AES-256-GCM)** (`JamKeyStoreManager`).
4. **Token Validation**:
   - Every WebSocket handshake and REST request validates the guest token.
   - Client-supplied roles or host IDs are **never** trusted; all authorizations are derived server-side from room state.
5. **Future Account Compatibility**:
   - The `guestId` schema is designed with an optional `userId` foreign key for future account linking without breaking room protocols.

---

## 7. Jam Room Management & Lifecycle

### Room Structure
- `roomId`: UUID string.
- `roomCode`: 6-character alphanumeric uppercase code (e.g. `JAM-7842` or `MELODY-X`).
- `hostGuestId`: The authoritative host's guest ID.
- `createdAt`: UTC timestamp.
- `expiresAt`: UTC timestamp (auto-pruned after 2 hours of inactivity).
- `visibility`: `PRIVATE` or `UNLISTED`.
- `participants`: Map of active participants (Guest ID, Display Name, Avatar Index, Role: HOST/GUEST, ConnectionStatus, JoinedAt).
- `playbackState`: Authoritative playback state snapshot.
- `queue`: Collaborative queue ordered list.

### Host Disconnection & Grace Period
- If the host drops connection (e.g. network handover, incoming call):
  - The room enters `HOST_RECONNECTING` state with a **45-second grace period**.
  - Playback continues for existing guests or pauses based on room setting.
  - If the host reconnects within 45 seconds, host authority is immediately restored.
  - If grace period expires, room can automatically promote the longest-standing active guest to host, or gracefully end the session with notification.

---

## 8. Playback Synchronization & Drift Compensation Engine

### Principles of Server-Authoritative Sync
1. **Event Timestamps**: All play/pause/seek events carry `serverTimestamp` and `clientTimestamp`.
2. **Clock Offset Estimation (NTP-style)**:
   - On connection and periodically via `REQUEST_SYNC`, client measures Round-Trip Time (RTT):
     $$RTT = T_{receive} - T_{send}$$
     $$\text{Clock Offset} = T_{server} - (T_{send} + \frac{RTT}{2})$$
3. **Target Playback Position Calculation**:
   - When playing:
     $$\text{Target Position} = \text{State Position} + (T_{\text{now}} - T_{\text{server}} - \text{Latency Compensation})$$
4. **Drift Detection & Progressive Correction**:
   - Client compares local `exoPlayer.currentPosition` with `Target Position`:
     $$\Delta = |\text{localPos} - \text{targetPos}|$$
   - **$\Delta < 150\text{ ms}$ (Imperceptible)**: No action taken. Prevents audio artifacts.
   - **$150\text{ ms} \le \Delta \le 1200\text{ ms}$ (Minor Drift)**: Apply smooth playback speed adjustment ($0.97\times$ or $1.03\times$) for a few seconds to gently pull into sync without audible pitch shift or clicks.
   - **$\Delta > 1200\text{ ms}$ (Major Drift or Seek Event)**: Explicit `player.seekTo(targetPos)`.

---

## 9. Collaborative Queue Specification

- **Shared Queue**: Backed by server-authoritative `JamQueueItem` list.
- **Permissions**:
  - Any participant can add songs to the collaborative queue.
  - Guests can delete items they personally added.
  - The Host can delete any item or reorder tracks.
- **Track Transition**: When the currently playing track finishes on the host's player:
  - Host advances to next track and broadcasts `TRACK_CHANGED`.
  - All guests transition smoothly to the same track.
  - If host is buffering, guests buffer and synchronize.

---

## 10. UI/UX Design System Integration

Melody's design language features a **pure dark burgundy/black aesthetic**:
- Primary Background: `#100707`
- Elevated Surface: `#241010`
- Card Surface: `#1B1919`
- Accent Crimson: `#B8203A`
- Text Primary: `#F5F1F1`
- Text Secondary: `#A49A9A`
- Border Subtle: `#422020`

### Integrated UI Surfaces
1. **Entry Point**:
   - Cast/Jam icon in Top App Bar (`btnTopCast`) and Now Playing Sheet (`btnNowPlayingCast`).
   - "Melody Jam" card in Library / Home tab.
2. **Create / Join Jam Sheet**:
   - Clean BottomSheetDialog: "Start a Jam" or "Join with Code".
   - Input field with auto-capitalization and room code validation.
3. **Jam Room Active Sheet**:
   - Host badge, participant count pill, room code with 1-tap copy & Android Share Sheet (`Intent.ACTION_SEND`).
   - Real-time animated sync indicator (Connected, In Sync, Buffering).
   - Live collaborative queue tab.
   - Emoji reactions bubble overlay (Floating ❤️, 🔥, 🎵, 👏, 🚀).
   - Host controls (kick participant, transfer host, end room).
   - Guest controls (leave room, add track).

---

## 11. Security, Resilience & Edge Cases

| Edge Case | Mitigation Strategy |
|---|---|
| **Network Loss / Tunnel Switching** | Exponential backoff reconnection (1s, 2s, 4s... max 15s). On reconnection, client sends `REQUEST_SYNC` and updates state without restarting audio. |
| **Guest Unauthorized Action** | Server discards unauthorized commands (e.g. non-host play/pause/seek) and emits error acknowledgement. |
| **Invalid Room Code** | Instant validation response `ROOM_NOT_FOUND` with user-friendly retry prompt. |
| **Song Unavailable on Guest Device** | If stream fails after candidate retries, show "Track Unavailable in your region" overlay while keeping guest in room. When host changes track, guest resumes normal sync. |
| **Application Backgrounded** | `PlaybackService` continues audio playback and maintains WebSocket heartbeat in foreground service. |
| **Leaving Jam** | Restores standalone player state; disconnects socket; clears Jam queue overlay. |

---

## 12. Safe Integration Plan

1. **Phase 01**: Foundation, codebase analysis, documentation (Completed).
2. **Phase 02**: Backend & Real-time infrastructure (`melody-jam-server`).
3. **Phase 03**: Jam room lifecycle & guest session management.
4. **Phase 04**: Real-time playback event pipeline.
5. **Phase 05**: Playback synchronization engine & drift compensation.
6. **Phase 06**: Collaborative queue manager.
7. **Phase 07**: Participants, host controls & live emoji reactions.
8. **Phase 08**: Android UI integration (Sheets, dialogs, badges, share intent).
9. **Phase 09**: Reliability, security, reconnection & edge-case hardening.
10. **Phase 10**: End-to-end verification, regression testing & release readiness.
