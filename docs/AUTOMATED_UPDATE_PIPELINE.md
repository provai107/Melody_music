# Melody — Automated Android Update Pipeline (₹0 Budget)

Melody features a completely automated, production-grade, zero-budget app update pipeline powered by **GitHub Actions** and **GitHub Releases**. 

Whenever a new version of Melody is released, installed copies of Melody automatically detect the update, verify its cryptographic integrity, and guide the user through Android's package installer without requiring manual searching on GitHub.

---

## 1. Pipeline Architecture

```
+-------------------------------------------------------------------------------+
|  1. Developer Updates Version & Releases Tag                                  |
|     - Increment `versionCode` & `versionName` in `app/build.gradle.kts`       |
|     - Publish a Release on GitHub (e.g., tag `v1.0.1`)                        |
+---------------------------------------+---------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
|  2. GitHub Actions Automation (`release-android.yml`)                         |
|     - Run complete unit test suite (halts build if any test fails)            |
|     - Decode signing certificate from GitHub Secrets                          |
|     - Assemble release APK: `Melody.apk`                                      |
|     - Calculate SHA-256 checksum                                              |
|     - Generate & validate `latest.json` release metadata                      |
|     - Upload `Melody.apk` and `latest.json` to the GitHub Release             |
+---------------------------------------+---------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
|  3. Client-Side Update Detection (`AppUpdateManager.kt`)                      |
|     - Fetch `latest.json` via direct HTTPS release download                   |
|     - Compare `latest.json.versionCode` > `installed.versionCode`             |
|     - Background startup check (1-hour throttle, respects user dismissal)     |
|     - Manual check: **Settings -> About Melody -> Check for updates**         |
+---------------------------------------+---------------------------------------+
                                        |
                                        v
+-------------------------------------------------------------------------------+
|  4. Secure Download, Verification & Installation (`AppUpdateDialogHelper.kt`) |
|     - Stream APK download into app cache with real-time percentage & bytes    |
|     - Verify downloaded file SHA-256 equals `latest.json.sha256`              |
|     - Inspect APK archive: verify package name is `com.braveplayer.music`     |
|     - Verify APK signing certificate matches installed app's certificate      |
|     - Launch Android PackageInstaller via `FileProvider`                      |
|     - Android updates Melody in-place, preserving all playlists and data!      |
+-------------------------------------------------------------------------------+
```

---

## 2. GitHub Actions Secrets Configuration

To enable GitHub Actions to automatically sign the production APK with your permanent certificate, configure the following secrets under **Your GitHub Repository -> Settings -> Secrets and variables -> Actions**:

| Secret Name | Description | Example / Current Value |
|---|---|---|
| `KEYSTORE_BASE64` | Base64-encoded string of `release.keystore` | *(Generated using the command below)* |
| `KEYSTORE_PASSWORD` | Password for the keystore file | `braveplayer` |
| `KEY_ALIAS` | Alias name of the signing key | `braveplayer` |
| `KEY_PASSWORD` | Password for the key alias | `braveplayer` |

### How to generate the `KEYSTORE_BASE64` string:
Run the following command in your terminal from the repository root:
```bash
base64 -w 0 release.keystore
```
Copy the full single-line output and paste it into the `KEYSTORE_BASE64` secret.

> [!IMPORTANT]
> **Permanent Certificate Preservation**: Android strictly requires all application updates to be signed with the exact same certificate as the currently installed version. If the signing key is ever lost or regenerated, Android will reject updates with an "App not installed: Signature mismatch" error. Always keep a secure, encrypted backup of `release.keystore`.

---

## 3. How to Release a New Version of Melody

### Step 1: Increment Version in `app/build.gradle.kts`
Open [app/build.gradle.kts](file:///home/prodiptadutta/Desktop/tpm/app/build.gradle.kts) and bump `versionCode` and `versionName`:
```kotlin
defaultConfig {
    applicationId = "com.braveplayer.music"
    minSdk = 24
    targetSdk = 34
    versionCode = 2          // Increment by 1 for each new release
    versionName = "1.0.1"    // Semantic version string
    ...
}
```

### Step 2: Commit and Push Changes
```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to v1.0.1 (code 2)"
git push origin main
```

### Step 3: Publish a GitHub Release
1. Navigate to **https://github.com/hernos5/melody/releases/new**.
2. Set **Choose a tag**: `v1.0.1` (create new tag on publish).
3. Set **Release title**: `Melody v1.0.1`.
4. Enter your release notes describing what changed.
5. Click **Publish release**.

*(Alternatively, you can manually trigger the workflow via the **Actions** tab -> **Build & Release Melody Android** -> **Run workflow**)*.

### Step 4: Automated Build & Publishing
GitHub Actions will automatically:
1. Validate that `versionCode` is greater than previous releases.
2. Run all unit tests.
3. Build and sign `Melody.apk`.
4. Compute the SHA-256 checksum.
5. Generate `latest.json`.
6. Attach `Melody.apk` and `latest.json` directly to the release.

---

## 4. Release Metadata Format (`latest.json`)

The metadata generated by the CI workflow adheres to the following specification:

```json
{
  "versionName": "1.0.1",
  "versionCode": 2,
  "releaseNotes": "Enhanced music recommendations and instant search.",
  "apkUrl": "https://github.com/hernos5/melody/releases/download/v1.0.1/Melody.apk",
  "sha256": "511b4e728ee0f2ef90db07ad4a7b8fa9d854c9d12af8813e6963ffd9a8cc5f56",
  "mandatory": false
}
```

### Verification & Validation Safeguards
The app treats `latest.json` as untrusted input and enforces:
- `apkUrl` must use `https://`.
- Host must be an approved GitHub release asset domain (`github.com` or `objects.githubusercontent.com`).
- Path must target `.apk`.
- `sha256` must be exactly 64 hexadecimal characters.
- Downloaded file bytes are validated against `sha256` prior to any install attempt.
- Package name must match `com.braveplayer.music`.
- Signing certificate must match the certificate of the currently executing application.

---

## 5. User Experience & In-App Features

### Automatic Startup Check
- Runs in a background coroutine after app initialization.
- Never blocks playback, the splash screen, or navigation.
- Cached for 1 hour to prevent unnecessary network requests.
- If an optional update is dismissed ("Later"), the dismissal is saved in `SharedPreferences` so the prompt does not nag the user on every launch.

### Manual Check for Updates
- Accessible anytime via:
  **Now Playing / Queue Header -> ⚙ Playback Settings -> ABOUT MELODY -> Check for updates**
- Displays current version: `Version 1.0.0 (Build 1)`.
- Always performs a fresh check against GitHub Releases and provides instant feedback (`Melody is up to date` or opens the Update Sheet).

### Update Bottom Sheet (`dialog_app_update.xml`)
- Designed in Melody's dark theme with brand colors and typography.
- Shows version number, release notes, progress bar, downloaded megabytes / total megabytes, and percentage.
- Supports **Later** and **Update Now** buttons.
- Supports mandatory updates (hides "Later" and close buttons).

---

## 6. GitHub Usage Limits & Cost Analysis (₹0 Budget)

| Resource | GitHub Policy / Free Limit | Melody Usage |
|---|---|---|
| **Release Asset Hosting** | Up to 2 GB per file | `Melody.apk` is ~8.5 MB (< 0.5% of limit) |
| **Release Bandwidth** | Unlimited for open-source / public repos | Direct download from GitHub CDN |
| **GitHub Actions Minutes** | Unlimited for public repos / 2,000 mins/mo for private | ~25 seconds per build (~0.4 minutes) |
| **API Rate Limits** | 60 req/hr for unauthenticated API | **Bypassed completely**: `latest.json` is fetched via direct release asset download URL (`github.com/.../releases/latest/download/latest.json`), which is not subject to the 60 req/hr API quota |

---

## 7. Troubleshooting

### Problem: "App not installed: The package appears to be corrupt" or Signature Mismatch
- **Cause**: The update APK was signed with a different key than the installed app.
- **Solution**: Ensure `KEYSTORE_BASE64` in GitHub Secrets matches `release.keystore`. In-app signature validation automatically catches this and alerts the user before handing off to Android.

### Problem: "Unknown sources permission required"
- **Cause**: Android 8.0+ requires users to grant "Install unknown apps" permission to Melody.
- **Solution**: When tapped, Melody automatically opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Melody so the user can toggle permission with one tap.

### Problem: Offline or Slow Network
- Melody handles connection dropouts gracefully: displays a clear "Download interrupted. Tap retry to continue" error banner without crashing or interrupting music.
