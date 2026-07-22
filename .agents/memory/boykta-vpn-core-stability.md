---
name: Boykta VPN core stability
description: Key fixes applied to stop SOCKS5 error spam, stale ping loops, rapid reconnects, and VPN self-disconnect loop — build verified clean.
---

# Boykta VPN core stability fixes

## CRITICAL: NetworkCallback MUST be observe-only while tunnel is active
**Root cause (confirmed July 2026 via screenshot evidence):** Even with NET_CAPABILITY_NOT_VPN filtering and the 10s grace delay, a real physical network switch (e.g. cell→WiFi) fires onAvailable() and previously triggered `triggerAutoReconnect("network change")` — killing a perfectly healthy tunnel (5ms ping right before disconnect).

**Fix:** The `onNetworkAvailable` callback in `BoykVpnService.startVpn()` now ONLY logs the event. It NEVER calls `triggerAutoReconnect`. Auto-reconnect is triggered exclusively by Xray crash detection in the keep-alive loop (proxy not responding / XrayManager.isRunning() == false).

**Rule:** A running VPN session must never be killed by network callback events. NetworkMonitor is observe-only while connected.

## THE BIG FIX: VPN self-disconnect loop
**Root cause:** When the TUN interface comes up, Android fires `onAvailable()` for the VPN network itself. Without filtering, NetworkMonitor treated this as a "real network change" → triggered reconnect → infinite loop.

**Fix (two layers):**
1. `NetworkRequest` now includes `NET_CAPABILITY_NOT_VPN` — VPN networks are excluded from callbacks.
2. Double-check inside `onAvailable()`: verify `caps.hasCapability(NET_CAPABILITY_NOT_VPN)` before forwarding.
3. Post-connect grace period: NetworkMonitor start is delayed 10 s after TUN establishes (via `serviceScope.launch { delay(10_000) }`).
4. Debounce raised from 4 s → 8 s.

**Why:** Android's `ConnectivityManager` fires onAvailable for every network including the VPN tun0. Without `NET_CAPABILITY_NOT_VPN` in the request, every connection created an instant reconnect loop.

## WAKE_LOCK permission
`android.permission.WAKE_LOCK` must be declared in AndroidManifest.xml. Without it, `PowerManager.newWakeLock().acquire()` throws a SecurityException on production devices (seen as `[WARN] WakeLock failed: Neither user ... nor current process has android.permission.WAKE_LOCK`).

## Log format change
VpnLogManager emits structured prefixes — `[OK]`, `[WARN]`, `[ERR]`, `[SYS]`, `[INFO]`, `[DEV]` instead of emoji.
LogAdapter colors by prefix string. Sentinel `__CLEAR__` triggers `clearAll()` in LogAdapter.

## clearLogs() added to VpnLogManager
Clears throttle map and emits `__CLEAR__` sentinel which LogAdapter intercepts → `clearAll()` wipes all lines and inserts a visual separator.

## SOCKS5 error spam during reconnect
`VpnLogManager.isReconnecting: AtomicBoolean` set before teardown → suppresses SOCKS5 errors.
`TunnelPingChecker` checks `isReconnecting.get()` at every log site.

## NetworkMonitor debounce
AtomicLong CAS debounce now 8 s. Only fires for non-VPN networks.

## TunBridge stall detection
`socket.soTimeout = 60_000` → stalled sessions self-close.

## Xray readiness check
`waitForXrayReady()` polls `isProxyAlive()` for up to 8 s before TUN build.

## Colors
- Disconnected button: `#FF0055` (red) — `bg_connect_button.xml`
- Connected button: `#00C8E0` with `#00F2FE` border — `bg_connect_button_disconnect.xml`
- Shield icon in topbar tints: cyan (connected), amber (connecting), red (disconnected)
- Ring alpha: 1.0 (connected), 0.5 (connecting), 0.35 (disconnected)

## UI additions (second pass)
- Removed fake "0 KB/s" traffic counters from top bar — replaced with tinting shield icon
- Added Clear Logs (trash icon) button in terminal header
- Added Share App drawer item (ic_share.xml)
- Added Privacy Policy drawer item (ic_privacy.xml)
- New launcher icon: dark `#050508` background + cyan shield with "B" letter
- SplashActivity with fade+scale animation (2.2 s, then → MainActivity)
