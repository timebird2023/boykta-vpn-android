---
name: Android build setup on Replit
description: How the Boykta VPN Android project is built on Replit — env vars, SDK paths, known quirks, and what was overhauled in the v2 UI/engine pass.
---

# Android build setup on Replit

## Environment
- JDK 17 installed via Nix — path: `/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk`
- Android SDK installs fresh each container restart (ephemeral). Install steps:
  ```bash
  mkdir -p ~/android-sdk/cmdline-tools
  curl -s -o ct.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q ct.zip -d ~/android-sdk/cmdline-tools && mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest && rm ct.zip
  yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=~/android-sdk --licenses > /dev/null
  yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=~/android-sdk "platforms;android-35" "build-tools;35.0.1" "platform-tools"
  echo "sdk.dir=$HOME/android-sdk" > local.properties
  ```
- Build command (no env vars needed after local.properties is written):
  ```bash
  export JAVA_HOME=/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk
  ./gradlew assembleDebug
  ```

## libXray.aar
Not present in repo. JNI `external` functions in `XrayManager.kt` fail at runtime only (not compile time). APK builds cleanly without it. Download and place in `app/libs/` to enable real VPN.

**Why:** External functions in Kotlin/JVM resolve via `System.loadLibrary()` at runtime — they don't cause linker errors at build time.

## Architecture after v2 overhaul
- `VpnLogManager` — SharedFlow(replay=120) log bus; all pipeline events emit here; MainActivity subscribes
- `TunBridge` — pure-Java TUN→SOCKS5 relay using `ParcelFileDescriptor.fileDescriptor`; replaces tun2socks dep
- `XrayManager` — now supports VLESS, Trojan, VMess, Shadowsocks outbounds
- `BoykConfig` — `expiresHours` renamed to `expiresSeconds`; added `locked`, `customToast`, `ssMethod` fields
- `Server` — added `protocol` field with default `"vless"`; `formattedRemaining()` now returns `01d 12h:30m:15s` format
- `ConfigExportDialog` — locked/unlocked toggle, duration unit spinner (sec/min/hr/day), custom toast field, SS cipher

## Issues fixed during setup (v1)
- `allprojects { repositories {} }` in root `build.gradle` conflicts with `FAIL_ON_PROJECT_REPOS` — removed allprojects block
- `org.gradle.configuration-cache=true` conflicts with kapt — set to false
- Missing drawables: `ic_clock.xml`, `bg_ad_link_button.xml`
- Missing colors: `accent_dark`, `dialog_background`, `surface_variant`
- `android:gap` in LinearLayouts is API 35+ only — replaced with marginEnd/Bottom on children
- `Theme.MaterialComponents.DayNight.Dialog.FullScreen` doesn't exist in Material 1.12.0 — use `Theme.MaterialComponents.DayNight.Dialog`
- `colorBackground` (without android: prefix) causes aapt2 namespace confusion — removed from theme
- Missing mipmap launcher icons — generated programmatically with Python
- `statusDot` vs `dotStatus` ID mismatch — fixed constraint reference
- `isExpired` extension not imported in MainActivity — added import
- `ConfigExportDialog.kt` nullable chain — fixed `.trim()?.ifBlank ?: "/"`

## Issues fixed during v2 overhaul
- `LocalServer.toServer()` missing `protocol` field (added with default "local")
- `LatencyChecker.measureMs()` returns `Long`; ViewModel must use `MutableStateFlow<Long?>` not `Int?`
- `ImportResultDialog` referenced `config.expiresHours` (renamed to `expiresSeconds`)
- `TunBridge` must use `ParcelFileDescriptor` not raw Int fd (Android blocks reflection on `FileDescriptor.fd`)
- `BoykConfig.toVlessUri()` renamed to `toProxyUri()` to reflect multi-protocol support; extension alias kept
