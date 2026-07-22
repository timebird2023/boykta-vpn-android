---
name: Boykta VPN core stability
description: Key fixes applied to stop SOCKS5 error spam, stale ping loops, and rapid reconnects — build verified clean.
---

# Boykta VPN core stability fixes

## Log format change
VpnLogManager emits structured prefixes now — `[OK]`, `[WARN]`, `[ERR]`, `[SYS]`, `[INFO]`, `[DEV]` instead of emoji.
LogAdapter colors by prefix string, not emoji character.

**Why:** Emoji-based coloring was fragile; structured prefixes are searchable and unambiguous.

**How to apply:** Any new log call uses `VpnLogManager.success/warn/error/sys/info/device()`. No emoji in log messages.

## SOCKS5 error spam during reconnect
Root cause: keep-alive ping coroutine still ran after Xray was stopped during auto-reconnect.

Fix: 
1. `VpnLogManager.isReconnecting: AtomicBoolean` set to `true` BEFORE stopping Xray.
2. All warn/error calls in TunnelPingChecker check this flag and return early.
3. `keepAliveJob` tracked on BoykVpnService — cancelled explicitly before teardown.
4. TunBridge TCP session connect failures also check isReconnecting before logging.

**Why:** Without this, each of 3 ping URLs + TunBridge sessions each logged SocketException during the ~3s reconnect window — flooding the terminal.

## NetworkMonitor debounce
Android fires `onAvailable()` multiple times per network switch (typically 2-4).
Added `AtomicLong lastAvailableMs` with 4-second CAS debounce.

**Why:** Without debounce, each switch triggered multiple concurrent reconnects.

## TunBridge stall detection
Added `socket.soTimeout = 60_000` (STALL_TIMEOUT_MS). If SOCKS5 proxy accepted connection but forwards no data for 60s, SocketTimeoutException is caught → session closed → device RST sent.

**Why:** Trojan/VLESS sometimes "accepts" but stalls mid-stream. 60s timeout surfaces these.

## Xray readiness check
After `XrayManager.start()`, `waitForXrayReady()` polls `isProxyAlive()` every 300ms for up to 8s before establishing TUN. Prevents TunBridge from starting before SOCKS5 is listening.

**Why:** On slow devices Xray takes 1-2s to bind its port. Starting TunBridge before that caused immediate session failures.

## Colors updated
Background: `#050508` (near-pure black), Card: `#0F131D`, connect button: `#FF0055` (neon red matching screenshots).
