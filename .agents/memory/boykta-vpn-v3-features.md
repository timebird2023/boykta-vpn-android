---
name: Boykta VPN v3 feature set
description: All commercial features, state machine, and bot added in the v3 pass — what was built, key design decisions, and invariants to preserve.
---

# Boykta VPN v3 Feature Set

## State machine (BoykVpnService.kt)
Sequential steps with exact log messages:
1. `waitForPhysicalNetwork()` → "Waiting internet connection..."
2. `acquireWakeLock()` → "Wakelock acquired"
3. `logLocalIp()` → "Local ip, 10.x.x.x"
4. `XrayManager.start()` → "Connecting to v2ray server..."
5. `waitForXrayReady()` → "Connection established — SOCKS5 127.0.0.1:10808"
6. `TunnelPingChecker.pingAndLog()` → "Checking internet connection..."
7. `listeners.forEach { it.onConnected() }` → CONNECTED state

**Why:** Each step has a verifiable log so debugging is deterministic.

## Critical invariant: Ping failures NEVER trigger auto-reconnect
The keepAliveJob only calls `triggerAutoReconnect()` when:
- `isProxyAlive()` returns false (Xray process died)
- `XrayManager.isRunning()` returns false

Ping (HTTP/TCP) failures are logged as warnings only. The tunnel stays alive.

**Why:** Users complained about disconnects on slow or lossy networks.

## New files added (v3)
- `service/TrafficCounter.kt` — reads /proc/net/dev for tun0/tun1, EMA-smoothed speed
- `util/DnsPreference.kt` — SharedPrefs-backed DNS choice (SYSTEM/CLOUDFLARE/GOOGLE/ADGUARD)
- `util/SplitTunnelManager.kt` — package-level VPN bypass (addDisallowedApplication)
- `ui/SplitTunnelDialog.kt` — ListView-based app picker with checkboxes
- `bot.py` + `requirements.txt` — standalone Python Telegram bot in repo root

## DNS preference
DnsPreference.load(context) → DnsChoice → list of DNS servers → applied in VpnService TUN builder.
Applied at connection time, not live. Changing DNS requires reconnect.

## Split tunneling
SplitTunnelManager.getBypassed(ctx) → Set<packageName> → addDisallowedApplication per pkg.
Applied at connection time in BoykVpnService.startVpn().

## Traffic counter
TrafficCounter.start(scope) called after CONNECTED state. Reads /proc/net/dev every 1s.
TrafficCounter.stop() called in stopVpn() and triggerAutoReconnect().
UI: cardTraffic shown onConnected(), hidden onDisconnected().

## Clipboard auto-detect
MainActivity.checkClipboardForConfig() called in onResume(). Detects vless://, trojan://, vmess://, ss://.
Deduped via SharedPrefs key "last_clip_uri" to avoid repeat prompts.

## Battery optimization
AlertDialog on first onCreate() if not already exempt. Uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.

## Telegram bot (bot.py)
- Token: embedded in file (per user request)
- Developer ID: 7401831506 — checked in @dev_only decorator
- /panel → inline keyboard for broadcast, servers, genconfig, stats, health, userlog
- /genconfig → produces real .boykta file (encrypts with AES-256-GCM matching Android CryptoHelper)
- State stored in-memory (_servers, _subscribers, _broadcast_log, _user_log)
- requirements.txt: python-telegram-bot==21.6, httpx==0.27.2, cryptography==43.0.3

## Colors (v3)
background changed from #050508 → #000000 (pure pitch black per spec)
background_alt: #050508, card: #0A0D12

## Build
- Stub libXray.aar created at build time (9-byte download fails on Replit; compile-time stub works)
- Real libXray.aar must be placed in app/libs/ for actual VPN functionality
- Build succeeded: BUILD SUCCESSFUL in 1m 43s

## UDP relay invariant
The TUN bridge must keep one SOCKS5 UDP ASSOCIATE control/data pair per original UDP flow. Opening a fresh protected socket for every datagram loses replies and breaks games that depend on stable source-port mappings.

**Why:** Game traffic is bidirectional and continuous; one-shot direct forwarding only works for simple request/response DNS-like traffic.

**How to apply:** Preserve the flow key, local source port, relay association, and timeout when changing UDP handling. Do not globally drop UDP/443 or UDP/80 unless an explicit user option requires it.
