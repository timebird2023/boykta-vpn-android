# Boykta VPN — Android App

Native Android VPN app using Xray-core (VLESS WS) with a Telegram-bot-managed server list.

## Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0) | **Target SDK**: 35 (Android 15)
- **DI**: Hilt
- **Networking**: Retrofit + OkHttp (AES-256-GCM encrypted API)
- **Database**: Room (local imported configs)
- **VPN Engine**: libXray.aar (Xray-core + tun2socks) — JNI native libs
- **Build**: Gradle 8.11.1 + AGP 8.8.0 + Kotlin 2.0.21

## How to Build

### On Replit (debug APK)

```bash
export ANDROID_HOME=~/android-sdk
export ANDROID_SDK_ROOT=~/android-sdk
export JAVA_HOME=/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

./gradlew assembleDebug -Pandroid.sdk.root=$ANDROID_HOME
```

**Output**: `app/build/outputs/apk/debug/app-debug.apk`

### Via GitHub Actions (recommended for release)

Push to GitHub — the `.github/workflows/build.yml` workflow triggers automatically and uploads the APK as an artifact. See README.md for full steps.

## Build Environment (Replit)

- **JDK 17**: installed via Nix (`jdk17`)
- **Android SDK**: `~/android-sdk/` — platforms/android-35, build-tools/35.0.1
- **Gradle wrapper JAR**: `gradle/wrapper/gradle-wrapper.jar` (downloaded from Gradle releases — not committed in original repo)
- **libXray.aar**: NOT present — JNI symbols resolve at runtime. VPN engine will fail at runtime without the native `.so` files. Download from v2rayNG releases and place in `app/libs/` to enable actual VPN functionality.

## Key Architecture

```
MainActivity → ServerAdapter → BoykVpnService → XrayManager (JNI)
                                                       ↓
                                               libXray.aar (.so files)
                                               xray-core + tun2socks
```

- API responses are AES-256-GCM encrypted (`CryptoHelper.kt`)
- `.boykta` config files: encrypted at rest in Room DB (`LocalServer.kt`)
- VLESS URIs never exposed in UI — only server names
- `SecurityChecker.kt` blocks known packet sniffers

## Features Implemented

- ✅ Server list with live countdown timers
- ✅ LatencyChecker (`util/LatencyChecker.kt`) — measures ping to `https://dns.google`
- ✅ `.boykta` config file import/export (AES-256-GCM encrypted)
- ✅ Ad dialog with 15-second countdown before connect
- ✅ Sniffer/proxy detection
- ✅ Room DB for locally imported servers

## User Preferences

- Do not edit `.github/workflows/` files
