# Music Engine Setup & Environment Configuration

This guide details the exact prerequisites, toolchain versions, backend setup, and connection configurations required to build and integrate the music engine into another Android project.

---

## 1. Verified Toolchain & Dependency Versions

### Build Environment
- **Java Development Kit**: OpenJDK 17 or OpenJDK 21 (verified with `java-21-openjdk-amd64`).
- **Kotlin**: `1.9.24` (Bytecode compatibility: `JavaVersion.VERSION_17`).
- **Android Gradle Plugin (AGP)**: `8.5.2` (requires Gradle `8.7+` or `8.10.2`).
- **Android SDK Requirements**:
  - `compileSdk = 34`
  - `targetSdk = 34`
  - `minSdk = 24` (Android 7.0 Nougat)
  - `buildToolsVersion = "34.0.0"`

### Verified Dependency Manifest
```kotlin
// AndroidX Media3 (Unified Media & Playback)
implementation("androidx.media3:media3-exoplayer:1.3.1")
implementation("androidx.media3:media3-session:1.3.1")
implementation("androidx.media3:media3-datasource:1.3.1")
implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
implementation("androidx.media3:media3-ui:1.3.1")

// Networking & Concurrency
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

// Android Core & Material UI
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("com.google.android.material:material:1.12.0")

// Unit Testing & Shadow Framework
testImplementation("junit:junit:4.13.2")
testImplementation("org.assertj:assertj-core:3.25.3")
testImplementation("org.robolectric:robolectric:4.12.2")
```

---

## 2. Backend Setup (`backend/`)

The music engine uses an unofficial open-source FastAPI backend that scrapes JioSaavn's public endpoints.

### Linux / macOS Setup
```bash
cd backend/
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
python app.py
```

The service runs on `http://0.0.0.0:5000` by default.

### Environment Configuration (`.env`)
Create or edit `.env` in `backend/`:
```properties
PORT=5000
HOST=0.0.0.0
```

### Health Check
Verify the backend is operational:
```bash
curl "http://localhost:5000/search?query=arijit"
```
Should return HTTP 200 with JSON search results.

---

## 3. Physical Device & Emulator Connectivity

Android applications cannot use `localhost` or `127.0.0.1` to access services running on the host development machine. Configure `AppSettings` accordingly:

### Android Emulator
- **URL**: `http://10.0.2.2:5000`
- `10.0.2.2` is the special Android Emulator alias mapping to the development host loopback.
- This is the default in `AppSettings.DEFAULT_EMULATOR_BASE_URL`.

### Physical Device via Local Wi-Fi / LAN
1. Connect both development PC and Android device to the same Wi-Fi network.
2. Find host LAN IP (`ip -4 addr show` on Linux or `ipconfig` on Windows), e.g. `192.168.1.150`.
3. Set base URL in `AppSettings` or via UI to `http://192.168.1.150:5000`.

### Physical Device via USB Reverse Tethering
If Wi-Fi isolation prevents direct LAN access, run:
```bash
adb reverse tcp:5000 tcp:5000
```
Then configure the base URL on the phone as `http://localhost:5000`.

### Cleartext HTTP Manifest Requirement
Because local development servers use unencrypted HTTP (`http://`), ensure the host app's `AndroidManifest.xml` enables cleartext traffic:
```xml
<application
    android:usesCleartextTraffic="true"
    ...>
```

---

## 4. Integration Steps for Another Android Project

### Option A: Integrate as Gradle Submodules (Recommended)
1. Copy `music-engine-source/filter-core` and `music-engine-source/music-engine` to target project root.
2. In target `settings.gradle.kts`:
   ```kotlin
   include(":filter-core")
   include(":music-engine")
   ```
3. In target `app/build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation(project(":filter-core"))
       implementation(project(":music-engine"))
   }
   ```
4. In target `app/src/main/AndroidManifest.xml`:
   Ensure permissions and service are present:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

   <application ...>
       <service
           android:name="com.braveplayer.music.service.PlaybackService"
           android:exported="true"
           android:foregroundServiceType="mediaPlayback">
           <intent-filter>
               <action android:name="androidx.media3.session.MediaSessionService" />
           </intent-filter>
       </service>
   </application>
   ```

### Option B: Merge Source Directly into Existing App Module
1. Copy `music-engine-source/filter-core/src/main/kotlin/com/braveplayer/filter` to your source directory.
2. Copy `music-engine-source/music-engine/src/main/kotlin/com/braveplayer/music` to your source directory.
3. Add the dependencies listed in Section 1 to your module's `build.gradle.kts`.
