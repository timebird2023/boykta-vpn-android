---
name: Boykta VPN UI overhaul
description: Full redesign + engine fixes applied in the v3 pass — lifecycle fix, real pings, test server, locked/unlocked system.
---

# Boykta VPN UI/Engine Overhaul (v3)

## Key decisions

**XrayManager forceStop():** Always call `XrayManager.forceStop()` before any `start()` call to prevent "xray is already running" crashes. The `forceStop()` method sends the stop command unconditionally regardless of the internal `xrayRunning` flag.

**TunnelPingChecker — SOCKS5 routing:** App traffic is excluded from the VPN TUN interface via `addDisallowedApplication(packageName)`. To validate the tunnel, `TunnelPingChecker` must connect explicitly through the SOCKS5 proxy (`127.0.0.1:10808`) using `url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))`. This guarantees the ping actually travels through Xray-core. Plain `url.openConnection()` from the app process bypasses the tunnel.

**Why `Collections.list()`:** `NetworkInterface.getNetworkInterfaces()` returns `Enumeration<NetworkInterface>`. Using `.toList()` causes Kotlin overload resolution ambiguity — must use `Collections.list(enumeration)` with explicit type annotation `List<NetworkInterface>`.

**Built-in test server:** Gated behind `BuildConfig.DEBUG` only — never ships in release APK. Parameters are provided by the operator at development time. Connection details must NOT be stored in memory — see secrets policy.

**Locked/Unlocked config system:**
- `LocalServer` entity bumped to version 2 with `isLocked: Boolean = true` and `configJson: String = ""`.
- `Server` model adds `isLocked` + `configJson` fields.
- `BoykConfigManager.importWithLockInfo()` returns `Pair<BoykConfig, Boolean>` to carry lock status.
- `MainActivity.updateUnlockedPanel()` shows `cardUnlockedConfig` for servers where `isLocked=false`.
- Locked local servers show "كونفيغ مغلق" protocol badge. Unlocked show "UNLOCKED".

**AES-256-GCM removal from UI:** All user-visible strings removed from `strings.xml`, `dialog_config_export.xml`, privacy policy text. Technical term replaced with "تشفير متقدم".

**VLESS parser fix:** `buildStreamSettings` now uses `hostHeader` param (from `params["host"]`) separately from `sni`. Previously the `host` param was used for both WS headers and SNI which could conflict.

## Build notes
- Database version bumped 1→2; uses `fallbackToDestructiveMigration()` — existing local DB is wiped on upgrade (expected).
- `fallbackToDestructiveMigration()` deprecation warning is safe to ignore in this project (no user migration path needed for dev builds).
