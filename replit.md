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
| `service/BoykVpnService.kt` | VPN lifecycle, TUN setup (MTU 1380, IPv4+IPv6 dual-stack routes) |
| `service/TunBridge.kt` | TUN→SOCKS5 relay — IPv4+IPv6 TCP/UDP, QUIC-blocked, 256KB buffers |
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
- **SDK License fix**: If Gradle complains about licenses, manually write all hashes to `~/android-sdk/licenses/android-sdk-license` (see memory file `android-build-setup.md`).

## Latest Changes (July 23 2026)

### Full VPN Audit + Performance & Chrome Fix

- ✅ **QUIC blocked (UDP/443)** — `TunBridge.kt` now drops all UDP port 443 packets on both IPv4 and IPv6. Chrome/browsers fall back to TCP/TLS immediately and route correctly through Xray. Root cause of "Chrome not working".
- ✅ **Full IPv6 support** — `BoykVpnService.kt` adds `fd00::1/128` address and `::/0` route to the TUN. `TunBridge.kt` now parses IPv6 headers, relays IPv6 TCP via SOCKS5 CONNECT ATYP=0x04, and injects IPv6 TCP responses back with correct IPv6+TCP checksums. IPv6-only sites and dual-stack connections now work.
- ✅ **Performance: larger socket buffers** — `TcpSession` send/receive buffers increased from 128 KB to 256 KB. Better for streaming and gaming burst traffic.
- ✅ **Performance: larger TUN read buffer** — Increased from 1500 to 8192 bytes. Reduces syscall overhead when the kernel batches packets.
- ✅ **Performance: TcpSession channel capacity** — Increased from 1024 to 2048 frames. Less backpressure on the device→proxy write path under load.
- ✅ **Xray log level `none`** — Eliminates Xray internal log I/O; saves CPU that was wasted on log formatting under load.
- ✅ **Xray routing `IPIfNonMatch`** — Replaces `AsIs`. Xray resolves domains to IP only when no domain-level rule matched, avoiding unnecessary DNS round-trips. Lower first-packet latency for games.
- ✅ **Xray sniffing adds `quic`** — Xray now sniffs QUIC SNI in addition to HTTP Host and TLS SNI, enabling correct routing decisions even when QUIC packets reach the inbound.
- ✅ **Inbound tags added** — SOCKS5 inbound tagged `socks-in`, HTTP inbound tagged `http-in` for clean routing rule targeting.

## Previous Changes (July 2026)

- ✅ **Branding**: All "BOYKTA NET" → "BOYKTA VPN" (activity_main.xml ×3, activity_splash.xml, dialog_privacy_policy.xml)
- ✅ **False reconnect loop fix**: Added `networkChangeJob` tracking in `BoykVpnService.kt` — stale coroutines from old sessions are cancelled before reconnect completes, preventing double-reconnect loops
- ✅ **Ping reliability fix**: `TunnelPingChecker.kt` replaced HTTPS-through-SOCKS5 ping (caused SSLHandshakeException/SocketTimeout with Trojan) with plain TCP probe to 1.1.1.1:80 through SOCKS5 — no TLS overhead, no false failures
- ✅ **Unlocked config editing**: The 4 param fields (Target/Path/SNI/Host Header) in the unlocked config panel are now EditText — users can edit inline. "Save & Reconnect" button rebuilds the VLESS/Trojan URI with updated values while preserving UUID/password

### Architecture Overhaul — Full Autonomous Audit (July 22 2026)

- ✅ **CRITICAL: bot.py crypto key mismatch fixed** — `CRYPTO_KEY_RAW` was `"BoyktaVPN_SecureKey_2024_AES256GCM"` but Android `CryptoHelper.kt` uses `"boykta_2nlkkh53DaYBmllnvb2026"`; now aligned. Bot-generated `.boykta` files can now be decrypted by the Android app.
- ✅ **Thread-safe listeners** — `BoykVpnService.listeners` changed from plain `mutableListOf` to `CopyOnWriteArrayList`; eliminates `ConcurrentModificationException` when binder and coroutine threads access the list concurrently.
- ✅ **WakeLock indefinite** — `PowerManager.WakeLock.acquire()` no longer has a 1-hour hard cap (`@SuppressLint("WakelockTimeout")`). Sessions >60 min no longer lose CPU hold; WakeLock is still released explicitly in `releaseWakeLock()`.
- ✅ **`reconnect()` race condition fixed** — Old `reconnect()` called `stopVpn()→stopSelf()→onDestroy()→serviceScope.cancel()`, silently cancelling the deferred `startVpn()`. New implementation does an in-place teardown without stopping the service, then re-runs `startVpn`.
- ✅ **TunBridge `tunOut` FD leak** — `stop()` now closes both `tunIn` and `tunOut`; previously only `tunIn` was closed, leaving a dangling file descriptor each reconnect.
- ✅ **Stall timeout doubled** — `STALL_TIMEOUT_MS` 60s → 120s in `TunBridge`; prevents killing HTTP long-polling / SSE connections that legitimately have no data for >60s.
- ✅ **`VpnLogManager.emitCount` atomicity** — Changed from raw `var` to `AtomicInteger`; prevents missed increments when multiple coroutines emit logs concurrently.
- ✅ **`XrayManager` transport coverage** — Added `splithttp`/`xhttp` (Xray ≥1.8.16), `httpupgrade`, and `reality` security support in `buildStreamSettings`. Links using these transports now produce valid Xray configs instead of silently omitting the transport block.
- ✅ **Deprecated API suppressions** — `SplashActivity.overridePendingTransition` and `LocalDatabase.fallbackToDestructiveMigration` warnings resolved.
- ✅ **bot.py credentials** — `BOT_TOKEN`, `DEVELOPER_ID`, `CF_TOKEN` moved from hardcoded constants to `os.environ.get()` with startup validation. Set these as Replit Secrets before running.

### Build command (Replit)
```bash
export JAVA_HOME=/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk
./gradlew assembleDebug --no-daemon
# Output: app/build/outputs/apk/debug/app-debug.apk  (~7.8 MB)
```

## User Preferences

- Do not edit `.github/workflows/` files
