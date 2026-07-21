---
name: Boykta VPN UI overhaul
description: Key decisions from the full UI/UX + routing overhaul of Boykta VPN app.
---

# Boykta VPN UI Overhaul

## What was changed

- activity_main.xml: Complete redesign — single server card + glowing circular connect button + speed counters + bottom action bar (5 buttons: Update, Logs, Key, Telegram, Exit)
- MainActivity.kt: Full rewrite for new single-card UI; no RecyclerView for servers anymore
- MainViewModel.kt: Added `startAutoPing()` / `stopAutoPing()` — 1-second coroutine loop using LatencyChecker targeting dns.google
- XrayManager.kt: Removed ALL geosite/geoip routing rules (were causing "stat /system/bin/geosite.dat: no such file" crash). Routing is now pure IP-based with simple rules only. domainStrategy = "AsIs".
- ServerSelectSheet.kt: New BottomSheetDialogFragment with search bar + radio checkmarks
- ConfigExportDialog.kt: Added "CONNECT DIRECTLY" button next to Export; locked mode shows [ENCRYPTED & LOCKED] hint on sensitive fields
- Icons: Generated via pure-Python PNG writer (no PIL needed); also added adaptive icon XMLs in mipmap-anydpi-v26/

## Critical decisions

**Why removed geosite/geoip:** The libXray.aar in this project doesn't bundle the .dat files. Any routing rule that references geosite: or geoip: causes a fatal crash at startup. The fix is to use only IP-range rules in routing.

**Why single server card instead of RecyclerView:** Spec requirement — only one card visible at a time; tapping opens BottomSheetDialog for selection.

**Why 1-second ping:** Spec requirement for "live ping badge". LatencyChecker makes a HEAD request to https://dns.google; result updates tvPingBadge on main card.

**How to apply:** If routing crashes return, check XrayManager.buildXrayConfig() — ensure no `geosite:` or `geoip:` strings appear in the JSONObject routing rules.

## Build command

```bash
export JAVA_HOME=/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk` (~79 MB)
