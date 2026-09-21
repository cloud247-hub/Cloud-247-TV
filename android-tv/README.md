# Cloud247 TV for Android TV v1.0.0

Native Android TV IPTV player built for the user's own M3U/M3U8 playlists.

## v1 features

- Direct M3U/M3U8 URL import from the Android TV device
- Local M3U file import
- No browser CORS dependency and no Cloudflare video proxy
- HTTP and HTTPS stream support
- Media3 / ExoPlayer playback
- HLS and progressive/MPEG-TS playback through Media3
- Groups from `group-title` / `EXTGRP`
- Smart groups for Fotball, Tennis and Golf when matching channels exist
- Channel search
- Favorites stored locally on the TV
- Channel logos loaded directly from `tvg-logo`
- XMLTV/EPG from URL or local XMLTV file
- Now / Next program information
- Fullscreen player
- D-pad friendly lists and controls
- Channel Up / Down remote key support
- Cloud247 TV launcher banner and Cloud247 visual design

## Privacy

M3U/EPG URLs and playlist contents are not uploaded to Cloud247. URL imports and video streams go directly from the Android TV device to the provider. Favorites are the only playlist-related data persisted, and are stored locally as channel identifiers.

## Build

The repository workflow builds a debug-signed APK suitable for sideloading.

Local build with Android SDK 36, JDK 17 and Gradle 9.6:

```bash
cd android-tv
gradle :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install over ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Technical baseline

- Application ID: `no.cloud247.tv`
- minSdk: 23
- targetSdk: 36
- compileSdk: 36
- Media3: 1.11.1
- Android Gradle Plugin: 9.4.0
- Kotlin: 2.2.10 via AGP 9.4 built-in Kotlin support

The app is TV-only and declares `CATEGORY_LEANBACK_LAUNCHER`, `android.software.leanback`, no touchscreen requirement, and a 320x180 TV launcher banner.
