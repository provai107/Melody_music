# Melody — Smart Music Player for Android

Melody is an intelligent, high-performance music streaming application for Android featuring Spotify-inspired recommendations, continuous autoplay, 3-mode Smart Shuffle, synced lyrics, and an automated zero-budget update pipeline.

---

## Features

- **Continuous Autoplay**: Proactive infinite queue extension ensuring playback never ends.
- **3-Mode Smart Shuffle**: Seamlessly cycle between `Normal`, `Shuffle`, and `Smart Shuffle (✨)` with personalized track interleaving.
- **Synced Lyrics**: Dynamic, timed lyrics display that scrolls with playback.
- **Smart Discovery**: Personalized daily mixes, artist radios, and mood/genre filters.
- **Automated In-App Updates**: Zero-budget automated update pipeline via GitHub Actions and GitHub Releases.

---

## Automated In-App Update Pipeline (₹0 Budget)

Melody includes a fully automated release and update pipeline using **GitHub Actions** and **GitHub Releases**:

1. **Automatic Discovery**: Installed apps check for updates asynchronously on startup and via **Settings -> About Melody -> Check for updates**.
2. **Cryptographic Verification**: Every downloaded update APK is checked against an official SHA-256 hash and verified against the installed app's signing certificate before installation.
3. **Seamless Installation**: Uses Android's `FileProvider` and PackageInstaller without data loss—preserving all user playlists, history, and downloads.

For full setup instructions, GitHub Actions secret configuration, and release guide, see:
👉 **[docs/AUTOMATED_UPDATE_PIPELINE.md](docs/AUTOMATED_UPDATE_PIPELINE.md)**

---

## Build & Test Instructions

### Requirements
- OpenJDK 21
- Android SDK 34 (Build Tools 34.0.0)

### Running Unit Tests
```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 gradle testDebugUnitTest
```

### Assembling Production Release APK
```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 gradle assembleRelease
```
The signed release APK will be generated at:
`app/build/outputs/apk/release/app-release.apk`
