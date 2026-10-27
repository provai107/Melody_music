# Portable Android Music Engine Transfer Package

This package contains the complete, battle-tested music playback, discovery, and personalization engine extracted from the **Brave Music Player** Android project. It is structured for direct transfer and integration into another native Android Kotlin/Java application.

---

## Package Directory Structure

```text
MUSIC_ENGINE_TRANSFER/
├── README.md                  # This overview and transfer procedure
├── MUSIC_ENGINE.md            # In-depth architectural & implementation reference
├── SETUP.md                   # Environment prerequisites, dependencies, and configuration
├── TESTING.md                 # Verification checklist, commands, and test suites
├── INTEGRATION_PROMPT.md      # Ready-to-use prompt for an AI agent in the target repository
│
├── music-engine-source/       # Source code and unit tests ready to drop in
│   ├── filter-core/           # Brave-style rule filtering engine & audio CDN protection (Kotlin/JVM)
│   ├── music-engine/          # Core music engine library module (Models, Service, Discovery, SQLite)
│   └── ui-reference/          # Reference UI implementation (MainActivity, SongAdapter, layouts, XML)
│
└── backend/                   # Unofficial JioSaavn FastAPI backend
    ├── app.py                 # FastAPI application endpoints (/search, /song, /album, /playlist)
    ├── saavn_nn.py            # JioSaavn API scraper and metadata parser
    ├── file_downloader.py     # Background task helper
    ├── requirements.txt       # Python dependencies (fastapi, uvicorn, requests, pycryptodome)
    ├── .env.example           # Example environment file (PORT=5000, HOST=0.0.0.0)
    └── README_POP_OS.md       # Linux setup instructions
```

---

## Core Capabilities Included

1. **JioSaavn Search & Metadata**:
   - Query parser decoding HTML entities and normalizing artist/album fields.
   - Candidate audio stream extraction (`media_url_default`, `media_url`, `preview_url`).
2. **AndroidX Media3 Background Playback**:
   - Single-player ownership inside `PlaybackService` extending `MediaSessionService`.
   - Audio focus management (`USAGE_MEDIA`, `CONTENT_TYPE_MUSIC`) and `handleAudioBecomingNoisy`.
   - Android 14 (API 34) `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and lock-screen notification transport controls.
3. **Queue & Autoplay Management**:
   - Add to queue, play now, clear queue, reorder tracks.
   - Service-driven autoplay queue extension when the current playlist nears completion (suppressed during repeat mode and app launch).
   - Candidate URL fallback (e.g. automatic failover from 404 stream to `_96.mp4` or preview).
4. **Liked Songs & Playlists (SQLite)**:
   - Full CRUD for custom playlists and tracks.
   - Liked songs collection with instant toggle, play all, shuffle all, and duplicate prevention.
5. **Listening History & Analytics**:
   - Records play duration, completions (>= 85%), skips (< 30s), and meaningful plays (>= 30s or >= 30% duration).
   - Auto-prunes older history to enforce a strict 500-row cap.
   - Provides "Recently Played" and "Most Played" collections.
6. **Local Recommendation Engine & Personalized Mixes**:
   - Pure on-device scoring algorithm combining artist similarity, album affinity, liked status, and history.
   - In-memory candidate caching (10-minute TTL) with bounded network requests.
   - Song Radio: Generates 15-track playlist seeded by any song.
   - Daily Mix: Blends user favorites with familiar artist recommendations.
   - Discovery Mix: Actively rewards novel artists outside the user's frequent rotation (+50 novelty bonus).
7. **Brave-Style Network Filtering**:
   - Isolates metadata/analytics requests (`RequestType.METADATA_API`) from audio CDN streams (`RequestType.AUDIO_PLAYBACK`).
   - Guarantees legitimate audio CDNs (`saavncdn.com`, `jio.com`, `soundhelix.com`) and provider-required ad segments are never blocked.

---

## Quick Transfer Procedure

1. **Copy the Source**:
   - Copy `music-engine-source/filter-core` and `music-engine-source/music-engine` into the target Android project root.
   - In target `settings.gradle.kts`, add:
     ```kotlin
     include(":filter-core")
     include(":music-engine")
     ```
2. **Add Dependencies**:
   - In the target app's `build.gradle.kts`:
     ```kotlin
     implementation(project(":filter-core"))
     implementation(project(":music-engine"))
     ```
3. **Start the Backend**:
   - In `backend/`:
     ```bash
     python3 -m venv .venv
     source .venv/bin/activate
     pip install -r requirements.txt
     python app.py
     ```
4. **Connect UI via MediaController**:
   - Follow [`MUSIC_ENGINE.md`](./MUSIC_ENGINE.md) and [`ui-reference/`](./music-engine-source/ui-reference/) to connect your activity/fragment to `PlaybackService` via `MediaController`.
