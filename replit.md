# Boykta VPN — Android App

Native Android VPN app using Xray-core (VLESS / Trojan / VMess / Shadowsocks) with a dark neon UI, real-time log terminal, and Telegram-bot-managed server list.

## Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0) | **Target SDK**: 35 (Android 15)
- **DI**: Hilt
- **Networking**: Retrofit + OkHttp (AES-256-GCM encrypted API)
- **Database**: Room (local imported configs)
- **VPN Engine**: libXray.aar (Xray-core JNI) + Java TunBridge (TUN→SOCKS5)
- **Build**: Gradle 8.11.1 + AGP 8.8.0 + Kotlin 2.0.21

## How to Build

### On Replit (debug APK)

```bash
export JAVA_HOME=/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk
./gradlew assembleDebug
```

**Output**: `app/build/outputs/apk/debug/app-debug.apk`

> **Note**: After a container restart the Android SDK must be reinstalled:
> ```bash
> mkdir -p ~/android-sdk/cmdline-tools
> curl -s -o ct.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
> unzip -q ct.zip -d ~/android-sdk/cmdline-tools && mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest && rm ct.zip
> yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=~/android-sdk --licenses > /dev/null
> yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=~/android-sdk "platforms;android-35" "build-tools;35.0.1" "platform-tools"
> echo "sdk.dir=$HOME/android-sdk" > local.properties
> ```

### Via GitHub Actions (recommended for release)

Push to GitHub — the `.github/workflows/build.yml` workflow triggers automatically and uploads the APK as an artifact.

## Architecture

```
MainActivity → ServerAdapter → BoykVpnService → XrayManager (JNI → libXray.aar)
                                      ↓
                               TunBridge (Java TUN→SOCKS5 relay)
                                      ↓
                         127.0.0.1:10808 (SOCKS5) / :10809 (HTTP)
```

- `VpnLogManager` — SharedFlow bus; all pipeline events stream to the in-app log terminal
- API responses are AES-256-GCM encrypted (`CryptoHelper.kt`)
- `.boykta` config files: locked (AES-GCM) or unlocked (plain JSON)
- VLESS URIs / proxy credentials never exposed in UI
- `SecurityChecker.kt` blocks known packet sniffers

## Key Files

| File | Role |
|------|------|
| `service/BoykVpnService.kt` | VPN lifecycle, TUN setup (MTU 1500, DNS 8.8.8.8/1.1.1.1, 0.0.0.0/0 route) |
| `service/TunBridge.kt` | Pure-Java TUN→SOCKS5 packet relay (TCP + UDP) |
| `service/XrayManager.kt` | Xray-core wrapper — VLESS, Trojan, VMess, Shadowsocks |
| `service/VpnLogManager.kt` | SharedFlow log bus (replays 120 entries) |
| `ui/LogAdapter.kt` | Terminal-style log RecyclerView adapter |
| `config/BoykConfig.kt` | Config model: locked/unlocked, custom toast, duration units |
| `config/BoykConfigManager.kt` | Export (locked AES / unlocked JSON) + import |
| `ui/ConfigExportDialog.kt` | Admin export form with all protocol fields |

## Features Implemented

- ✅ Dark neon UI: Obsidian (`#0B0E14`) + Cyan (`#00F2FE`) + Pink (`#FF0055`)
- ✅ Side Navigation Drawer (hamburger menu): Developer Channel, Import Config, Live Logs, Check Updates, Exit
- ✅ Main CONNECT/DISCONNECT toggle — vector drawable icons (ic_play.xml / ic_stop.xml), NO emoji text
- ✅ Selected server card: protocol badge + `01d 12h:30m:15s` countdown + 2-letter country code (no emoji flags)
- ✅ Terminal-style LIVE LOG viewer (120 entries, color-coded)
- ✅ Unlocked config panel: shows Target / Path / SNI / Host Header + Reconnect button
- ✅ Locked config panel: shows only "كونفيغ مغلق" badge — no encryption jargon
- ✅ VPN routing: TUN MTU 1500, DNS 8.8.8.8/1.1.1.1, route 0.0.0.0/0
- ✅ TunBridge: Java TCP/UDP relay via SOCKS5 (no tun2socks native dep)
- ✅ Multi-protocol: VLESS, Trojan, VMess, Shadowsocks
- ✅ Trojan WS host-header override via `params["host"]` (CDN fronting support)
- ✅ Internal Trojan WS+TLS diagnostic config in `app/src/debug/res/values/debug_config.xml` (DEBUG-only, never shipped to users)
- ✅ Process lifecycle: `XrayManager.forceStop()` always called before start — prevents port 10808 conflicts
- ✅ All network ops on `Dispatchers.IO` — UI thread stays clean
- ✅ `builtInTestServers` compile-error fix in `MainViewModel.kt`
- ✅ Locked export (AES-256-GCM) + Unlocked export (plain JSON)
- ✅ Duration selector: seconds / minutes / hours / days
- ✅ Custom on-connect toast/banner per config
- ✅ Remote announcement banner polling
- ✅ Telegram-bot-compatible JSON payload structure

## Build Environment (Replit)

- **JDK 17**: `jdk17` via Nix
- **Android SDK**: `~/android-sdk/` — platforms/android-35, build-tools/35.0.1
- **local.properties**: `sdk.dir=/home/runner/android-sdk`
- **libXray.aar**: NOT present — VPN engine starts Xray inbounds but TUN→SOCKS5 bridge requires the .aar for native routing. Place in `app/libs/` to enable full device-wide VPN.

## User Preferences

- Do not edit `.github/workflows/` files
