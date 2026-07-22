---
name: Boykta VPN core stability fixes (July 2026)
description: All root-cause fixes applied: TCP stall, UI sync, bad-base64, latency routing, auto-reconnect, network monitor, keep-alive.
---

# Boykta VPN — Core Stability Fixes

## TCP Data Stalling (TunBridge)
**Rule:** `TcpSession.onData()` MUST send standalone ACK before forwarding to proxy.
**Why:** Device TCP send-window fills (65KB) without ACKs — relay logs show active but no traffic.
**How:** Send `FLAG_ACK` packet synchronously in `onData()` before `toProxy.trySend`.

## Socket Keep-Alive (TunBridge)
**Rule:** Always set `socket.keepAlive = true` on SOCKS5 sockets in TcpSession.connect().
**Why:** ISP/NAT silently kills idle VPN connections. OS-level TCP probes keep them alive.

## Socket Buffers
**Rule:** `receiveBufferSize = sendBufferSize = 131072` (128 KB), read buffer 64 KB.
**Why:** Small buffers serialize downloads into many small packets, hurting throughput.

## UI State Sync after Service Rebind
**Rule:** In `ServiceConnection.onServiceConnected`, check `BoykVpnService.isRunning` → call `updateConnectUi(true)`.
**Why:** `onConnected()` fires before `addListener` on activity recreate → "غير متصل" with active VPN.

## bad base-64 Toast
**Rule:** Gate decryption on `server.isLocked`, NOT `CryptoHelper.isEncrypted()`.
**Why:** `isEncrypted("vless://...")` returns true (doesn't start with `{`) → decrypt() throws.

## CryptoHelper.isEncrypted() URI Schemes
**Rule:** Return false for vless://, trojan://, vmess://, ss://, http://, https://.

## LatencyChecker VPN Routing
**Rule:** When `BoykVpnService.isRunning`, route ping through `Proxy(SOCKS, 127.0.0.1:10808)`.
**Why:** `addDisallowedApplication` exempts own app from TUN — direct ping bypasses tunnel.

## Ping Timeout
**Rule:** `HTTPS_TIMEOUT_MS = 12_000` (was 8000). High-latency VPN servers need more time.
**Why:** 4108ms pings observed in logs — 8s is sometimes not enough for TLS round-trip.

## Auto-Reconnect
**Rule:** Keep-alive loop (every 15s): TCP probe first (`isProxyAlive`), then HTTPS ping. After 3 consecutive HTTPS failures → `triggerAutoReconnect()`.
**Why:** Previously just called `stopVpn()` with no retry.
**How:** `triggerAutoReconnect(reason)` uses `AtomicBoolean isReconnecting` guard + exponential backoff (3s→6s→12s→24s, cap 30s). Resets `reconnectCount` on clean connect.

## NetworkMonitor
**Rule:** Register `NetworkMonitor` in `startVpn()`, unregister in `stopVpn()`.
**Why:** No way to detect Wi-Fi ↔ mobile data switch without `ConnectivityManager.NetworkCallback`.
**How:** On `onNetworkAvailable()`: wait 2s for network to stabilize, then `triggerAutoReconnect("network change")`.

## Clean Terminal Logs (no raw stack traces)
**Rule:** `TunnelPingChecker.doHttpPing` catch block: log only `e.javaClass.simpleName + e.message.take(80)`. Never log stack frames to VpnLogManager.
**Why:** Screenshots showed raw ConscrpytEngineSocket frames flooding the terminal log.

## TunnelPingChecker.pingAndLog Return Value
**Rule:** `pingAndLog()` returns `Boolean` (true = success).
**Why:** Keep-alive loop needs to count consecutive failures.

## isProxyAlive() TCP Probe
**Rule:** Call `TunnelPingChecker.isProxyAlive(port)` before HTTPS ping in keep-alive loop.
**Why:** TCP connect to 127.0.0.1:10808 completes in <200ms — quickly confirms Xray is alive before spending 12s on HTTPS.

## SDK Install Path on Replit
**Rule:** `sdk.dir=/home/runner/workspace/~/android-sdk` (tilde not expanded when run from workspace dir).
**Why:** sdkmanager installs to workspace-relative path, not $HOME.
