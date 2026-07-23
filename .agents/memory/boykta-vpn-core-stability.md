---
name: Boykta VPN core stability
description: Key decisions, bugs found and fixed in BoykVpnService, TunBridge, XrayManager, TrafficCounter
---

## Fast Xray-only restart on network change (WiFi→LTE)

**Rule:** When the physical network changes, call `restartXrayOnly()` — NOT `triggerAutoReconnect()`.  
**Why:** Full reconnect tears down the TUN fd, giving the device a visible disconnect. Fast restart keeps TUN open; Xray rebinds on the new network. User sees a 2-5 s pause, not a disconnect.  
**How to apply:** `BoykVpnService.restartXrayOnly(reason)` handles this. `NetworkMonitor.onNetworkAvailable` calls it. `triggerAutoReconnect` is only for Xray process crashes.

## Dead tunnel detection via consecutive ping failures

**Rule:** After `PING_FAIL_RECONNECT_THRESHOLD` (3) consecutive HTTP-ping failures, call `restartXrayOnly()`.  
**Why:** A tunnel can appear "running" (Xray port open, libXray reports running) but all traffic is silently dropped. Without this counter the user experiences indefinite dead internet.  
**How to apply:** `pingFailCount` AtomicInteger in `BoykVpnService`; reset on success or on `restartXrayOnly` call.

## TCP data ordering — Channel.UNLIMITED

**Rule:** `TcpSession.toProxy` must use `Channel.UNLIMITED`, not a bounded capacity + fallback coroutine.  
**Why:** The fallback-coroutine pattern (`scope.launch { toProxy.send(payload) }`) races with later `trySend` calls from the single `readLoop` coroutine, causing out-of-order TCP segments delivered to the proxy.  
**How to apply:** `Channel.UNLIMITED` + single `trySend` in `onData`. Never add a fallback `launch { send() }` here.

## UDP timeout — SystemClock.elapsedRealtime()

**Rule:** Use `SystemClock.elapsedRealtime()` (not `System.currentTimeMillis()`) for all stall-timeout comparisons in `UdpSession`.  
**Why:** `currentTimeMillis` can jump forward/backward on NTP sync or timezone change, causing sessions to falsely expire or never expire.  
**How to apply:** `lastActivityElapsed` field + `SystemClock.elapsedRealtime()` everywhere in `UdpSession`.

## UDP session cap — MAX_UDP_SESSIONS = 512

**Rule:** Evict the oldest `UdpSession` (via `udpSessions.entries.firstOrNull()`) when the map reaches 512 entries before inserting a new one.  
**Why:** No cap → unbounded memory growth from UDP flood or buggy apps opening thousands of 5-tuples.  
**How to apply:** Check in `forwardUdp()` inside the `synchronized(udpSessions)` block before constructing a new `UdpSession`.

## DNS leak — remove "localhost" from Xray DNS

**Rule:** Never add `servers.put("localhost")` to the Xray DNS config.  
**Why:** "localhost" resolves to the system resolver, which exits the tunnel and leaks DNS queries to the ISP.  
**How to apply:** Use `1.1.1.1` and `8.8.8.8` as plain-UDP fallbacks after DoH and user-chosen servers.

## URI parser safety — try/catch wrappers

**Rule:** Wrap `buildVlessOutbound`, `buildTrojanOutbound`, `buildShadowsocksOutbound` in try/catch returning `buildFallbackOutbound()`.  
**Why:** Malformed URIs (missing `@`, empty host, bad base64) cause `StringIndexOutOfBoundsException` that kills the entire Xray start sequence.  
**How to apply:** Impl functions `buildTrojanOutboundImpl` / `buildShadowsocksOutboundImpl` hold the logic; public functions are thin try/catch wrappers.

## TrafficCounter — dynamic TUN interface detection

**Rule:** Scan `/proc/net/dev` for any interface whose name starts with `"tun"` or `"vpn"` instead of matching a hardcoded list.  
**Why:** Hardcoded list (`tun0`, `tun1`, `tun2`, `vpn0`) misses `tun10`, `tun3`, custom ROMs that use `vpntun`, etc., showing 0 KB/s permanently.  
**How to apply:** `name.startsWith("tun") || name.startsWith("vpn")` after finding `:` in each line of `/proc/net/dev`.

## soTimeout type: Long → Int cast

**Rule:** `socket.soTimeout = STALL_TIMEOUT_MS.toInt()` — `Socket.soTimeout` property is `Int` in the JVM API.  
**Why:** If `STALL_TIMEOUT_MS` is declared as `Long` (e.g. `180_000L`), Kotlin rejects the assignment without explicit cast.  
**How to apply:** Always `.toInt()` when assigning to `socket.soTimeout`.
