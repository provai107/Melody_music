# Integration Prompt for Target Android Project

Copy and paste the prompt below into the chat interface of the AI coding assistant working on the target Android project:

---

```markdown
# Task: Integrate the Brave Music Engine into this Android Project

We need to integrate the complete native music engine from `MUSIC_ENGINE_TRANSFER/` into this project.

The engine provides:
- JioSaavn music search and metadata parsing.
- AndroidX Media3 background playback via `PlaybackService` (`MediaSessionService`).
- Playback controls, seekbar, 15-second skip, next/prev, repeat, and shuffle.
- Liked Songs collection (SQLite persistence).
- Playlists & track management (SQLite persistence).
- On-device listening history with meaningful play tracking (>=30s) and 500-record cap.
- Intelligent Autoplay queue extension when reaching the end of the queue.
- Local recommendation engine (scoring artist similarity, album affinity, likes, history).
- Song Radio (15-track playlist seeded by any track).
- Personalized Mixes (Daily Mix, Discovery Mix with novelty scoring, and saving as SQLite playlists).
- Brave-style network filtering protecting audio CDNs.

## Mandatory Execution Instructions

1. **Inspect Before Changing**:
   - Inspect this target project's Gradle configuration (`settings.gradle.kts`, `build.gradle.kts`), Java/Kotlin versions, `compileSdk`, and existing dependencies.
   - Do NOT overwrite or redesign this app's existing UI. Adapt the engine to this app's existing layout and visual theme.

2. **Integrate Modules**:
   - Check if this project supports multi-module Gradle builds.
   - If yes: Drop in `:filter-core` and `:music-engine` from `MUSIC_ENGINE_TRANSFER/music-engine-source/`, and include them in `settings.gradle.kts` and `app/build.gradle.kts`.
   - If single-module: Merge the source packages (`com.braveplayer.filter` and `com.braveplayer.music`) directly into the app module.

3. **Verify Dependencies**:
   Ensure the following dependencies (or compatible equivalents) are present in the app's `build.gradle.kts`:
   ```kotlin
   implementation("androidx.media3:media3-exoplayer:1.3.1")
   implementation("androidx.media3:media3-session:1.3.1")
   implementation("androidx.media3:media3-datasource:1.3.1")
   implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
   implementation("com.squareup.okhttp3:okhttp:4.12.0")
   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
   ```

4. **Manifest & Service Integration**:
   - Add permissions in `AndroidManifest.xml`:
     ```xml
     <uses-permission android:name="android.permission.INTERNET" />
     <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
     <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
     ```
   - Register `PlaybackService`:
     ```xml
     <service
         android:name="com.braveplayer.music.service.PlaybackService"
         android:exported="true"
         android:foregroundServiceType="mediaPlayback">
         <intent-filter>
             <action android:name="androidx.media3.session.MediaSessionService" />
         </intent-filter>
     </service>
     ```
   - Ensure `android:usesCleartextTraffic="true"` is enabled for local HTTP backend connectivity during development.

5. **Connect UI via MediaController**:
   - Do NOT instantiate `ExoPlayer` in any Activity or Fragment.
   - Connect your UI using `MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()`.
   - Refer to `MUSIC_ENGINE_TRANSFER/music-engine-source/ui-reference/kotlin/MainActivity.kt` for working UI binding examples:
     - Song search (`MusicMetadataService.searchSongs`)
     - Play now & Add to queue (`SongMediaItemMapper.toMediaItem`)
     - Liked songs toggle (`PlaylistDatabaseHelper.likeSong / unlikeSong`)
     - Start Radio (`MusicRecommendationEngine.getRecommendations`)
     - Personalized Mixes (`MixGenerator.generateDailyMix / generateDiscoveryMix`)
     - Seekbar progress tracking and scrubbing

6. **Run Verification & Tests**:
   - Copy the unit tests from `MUSIC_ENGINE_TRANSFER/music-engine-source/music-engine/src/test/` to verify database operations, recommendation ranking, and autoplay logic.
   - Run tests using `./gradlew test` and build the debug APK using `./gradlew assembleDebug`.
   - Fix any compilation or dependency conflicts rather than reporting success prematurely.
```
