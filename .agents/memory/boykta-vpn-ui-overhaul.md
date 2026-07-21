---
name: Boykta VPN UI overhaul + runtime fixes
description: All major UI and runtime fixes applied to the Boykta VPN Android app
---

## Key decisions & fixes

### Navigation Drawer
- DrawerLayout wraps root ConstraintLayout in activity_main.xml
- Drawer items: Telegram channel, Import config, Live logs, Check updates, Exit
- Bottom bar kept minimal: Update + Logs + Key (🔑) only — Telegram/Exit removed (duplicates in drawer)

### Connect button
- `ivConnectIcon` (ImageView) uses ic_play.xml / ic_stop.xml — no emoji text
- Country code badge: plain 2-letter text (DE, US, etc.) — no emoji flags

### Runtime fixes applied
- `android:usesCleartextTraffic="true"` added to AndroidManifest.xml `<application>` tag
- TunnelPingChecker: HTTP ping URLs changed to HTTPS (connectivitycheck.gstatic.com)
- LatencyChecker: already used HTTPS (dns.google) — no change needed
- Base64 fix in XrayManager: `sanitizeBase64()` helper normalises URL-safe chars (- → +, _ → /) and pads before decoding VMess and SS URIs
- Base64 fix in CryptoHelper: same sanitisation in `decrypt()` before `Base64.NO_WRAP` decode
- Notification toast: emoji 🔔 replaced with "[!]"

### Diagnostic server removal
- `app/src/debug/res/values/debug_config.xml`: URI and name set to empty strings
- `debugTestServerConfig()` returns null when URI is blank → `builtInTestServers` is always empty
- App launches with completely empty server list for the user

### App icon
- `ic_launcher_foreground.xml` redesigned: cyan shield + pink power-button symbol (ring arc + vertical line)
- `ic_launcher_background.xml`: unchanged (#0B0E14 dark)

### Server test result
- boykta2-100891635133.europe-west1.run.app:443 is reachable (TLS handshake succeeds, cert valid)
- WebSocket path /boykta returns HTTP 404 for plain requests — expected Trojan camouflage behaviour
- Server is operational; 404 is intentional for non-Trojan traffic

**Why:** `usesCleartextTraffic=false` (default on API 28+) blocks all plain HTTP; ping targets and any future HTTP calls need this flag or HTTPS. Base64 URL-safe strings from Xray URI share format use `-`/`_` instead of `+`/`/` which causes `bad base-64` crash without sanitisation.
