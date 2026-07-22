---
name: Boykta VPN core bugs fixed (July 2026)
description: Root-cause fixes for TCP data stalling, UI state mismatch, bad base-64 crash, and latency routing.
---

# Boykta VPN — Critical Bug Fixes (July 2026)

## TCP Data Stalling (TunBridge)

**Rule:** `TcpSession.onData()` MUST send a standalone ACK (FLAG_ACK, no payload) to the device synchronously before forwarding data to the SOCKS5 proxy.

**Why:** Without immediate ACKs the device's TCP send-window (65 KB) fills up and it stops transmitting — the relay logs show active connections but no real traffic flows (Telegram/browsers silent).

**How to apply:** Always send ACK in `onData` BEFORE `toProxy.trySend`. Use `FLAG_PSH or FLAG_ACK` on proxy→device data packets (not just `FLAG_ACK`) for push notification.

## TCP Buffer Sizing

**Rule:** Proxy→device read buffer should be 64 KB (not MTU−40). Socket buffers set to 128 KB. Channel capacity 1024.

**Why:** Small read buffer serializes large downloads into many small packets, hurting throughput.

## UI State Sync after Service Rebind

**Rule:** In `ServiceConnection.onServiceConnected`, after `addListener`, check `BoykVpnService.isRunning` and call `updateConnectUi(true)` if true.

**Why:** When app is relaunched while VPN is running, `onConnected()` fires before `addListener` — the new Activity never gets the callback. This causes the "غير متصل" display even when tunnel is active.

## bad base-64 Toast

**Rule:** In `startVpnConnection`, use `server.isLocked` to decide whether to decrypt — NOT `CryptoHelper.isEncrypted()`.

**Why:** `isEncrypted("vless://...")` returns `true` because the URI doesn't start with `{`. Then `decrypt("vless://...")` throws "bad base-64". The fix is gating on `server.isLocked` AND fixing `isEncrypted` to skip known URI schemes.

## CryptoHelper.isEncrypted() URI Schemes

**Rule:** `isEncrypted` must return `false` for strings starting with `vless://`, `trojan://`, `vmess://`, `ss://`, `http://`, `https://`.

## LatencyChecker VPN Routing

**Rule:** When `BoykVpnService.isRunning == true`, route latency ping through `Proxy(SOCKS, 127.0.0.1:10808)` using `connectivitycheck.gstatic.com/generate_204`. Direct otherwise.

**Why:** Device exempts its own app from TUN routing (`addDisallowedApplication`), so a plain `openConnection()` bypasses the tunnel — ping badge showed direct latency not VPN quality.

## SDK Install Path on Replit

**Why:** `sdkmanager --sdk_root=~/android-sdk` installs to `/home/runner/workspace/~/android-sdk/` (tilde not expanded) when run from workspace dir. `local.properties` must use `sdk.dir=/home/runner/workspace/~/android-sdk`.
