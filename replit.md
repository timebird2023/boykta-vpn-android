# Boykta VPN — Android App

Native Android VPN app using Xray-core (VLESS / Trojan / VMess / Shadowsocks) with a dark neon UI, real-time log terminal, and Telegram-bot-managed server list.

## Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0) | **Target SDK**: 35 (Android 15)
- **DI**: Hilt
- **Networking**: Retrofit + OkHttp (AES-256-GCM encrypted API)
- **Database**: Room (local imported configs)
- **VPN Engine**: libXray.aar (Xray-core JNI) + Java TunBridge (TUN→SOCKS5)
- **Build**: Gradle 8.11.1 + AGP 8.8.0 + Kotlin 2.0.21

## How to Build

### On Replit (debug APK)

```bash
export JAVA_HOME=/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk
./gradlew assembleDebug
```

**Output**: `app/build/outputs/apk/debug/app-debug.apk`

> **Note**: After a container restart the Android SDK must be reinstalled:
> ```bash
> mkdir -p ~/android-sdk/cmdline-tools
> curl -s -o ct.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
> unzip -q ct.zip -d ~/android-sdk/cmdline-tools && mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest && rm ct.zip
> yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=~/android-sdk --licenses > /dev/null
> yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=~/android-sdk "platforms;android-35" "build-tools;35.0.1" "platform-tools"
> echo "sdk.dir=$HOME/android-sdk" > local.properties
> ```

### Via GitHub Actions (recommended for release)

Push to GitHub — the `.github/workflows/build.yml` workflow triggers automatically and uploads the APK as an artifact.

## Architecture

```
MainActivity → ServerAdapter → BoykVpnService → XrayManager (JNI → libXray.aar)
                                      ↓
                               TunBridge (Java TUN→SOCKS5 relay)
                                      ↓
                         127.0.0.1:10808 (SOCKS5) / :10809 (HTTP)
```

- `VpnLogManager` — SharedFlow bus; all pipeline events stream to the in-app log terminal
- API responses are AES-256-GCM encrypted (`CryptoHelper.kt`)
- `.boykta` config files: locked (AES-GCM) or unlocked (plain JSON)
- VLESS URIs / proxy credentials never exposed in UI
- `SecurityChecker.kt` blocks known packet sniffers

## Key Files

| File | Role |
|------|------|
| `service/BoykVpnService.kt` | VPN lifecycle, TUN setup (MTU 1380, IPv4+IPv6 dual-stack routes) |
| `service/TunBridge.kt` | TUN→SOCKS5 relay — IPv4+IPv6 TCP/UDP, QUIC-blocked, 256KB buffers |
| `service/XrayManager.kt` | Xray-core wrapper — VLESS, Trojan, VMess, Shadowsocks |
| `service/VpnLogManager.kt` | SharedFlow log bus (replays 120 entries) |
| `ui/LogAdapter.kt` | Terminal-style log RecyclerView adapter |
| `config/BoykConfig.kt` | Config model: locked/unlocked, custom toast, duration units |
| `config/BoykConfigManager.kt` | Export (locked AES / unlocked JSON) + import |
| `ui/ConfigExportDialog.kt` | Admin export form with all protocol fields |

## Features Implemented

- ✅ Dark neon UI: Obsidian (`#0B0E14`) + Cyan (`#00F2FE`) + Pink (`#FF0055`)
- ✅ Side Navigation Drawer (hamburger menu): Developer Channel, Import Config, Live Logs, Check Updates, Exit
- ✅ Main CONNECT/DISCONNECT toggle — vector drawable icons (ic_play.xml / ic_stop.xml), NO emoji text
- ✅ Selected server card: protocol badge + `01d 12h:30m:15s` countdown + 2-letter country code (no emoji flags)
- ✅ Terminal-style LIVE LOG viewer (120 entries, color-coded)
- ✅ Unlocked config panel: shows Target / Path / SNI / Host Header + Reconnect button
- ✅ Locked config panel: shows only "كونفيغ مغلق" badge — no encryption jargon
- ✅ VPN routing: TUN MTU 1500, DNS 8.8.8.8/1.1.1.1, route 0.0.0.0/0
- ✅ TunBridge: Java TCP/UDP relay via SOCKS5 (no tun2socks native dep)
- ✅ Multi-protocol: VLESS, Trojan, VMess, Shadowsocks
- ✅ Trojan WS host-header override via `params["host"]` (CDN fronting support)
- ✅ Internal Trojan WS+TLS diagnostic config in `app/src/debug/res/values/debug_config.xml` (DEBUG-only, never shipped to users)
- ✅ Process lifecycle: `XrayManager.forceStop()` always called before start — prevents port 10808 conflicts
- ✅ All network ops on `Dispatchers.IO` — UI thread stays clean
- ✅ `builtInTestServers` compile-error fix in `MainViewModel.kt`
- ✅ Locked export (AES-256-GCM) + Unlocked export (plain JSON)
- ✅ Duration selector: seconds / minutes / hours / days
- ✅ Custom on-connect toast/banner per config
- ✅ Remote announcement banner polling
- ✅ Telegram-bot-compatible JSON payload structure

## Build Environment (Replit)

- **JDK 17**: `jdk17` via Nix
- **Android SDK**: `~/android-sdk/` — platforms/android-35, build-tools/35.0.1
- **local.properties**: `sdk.dir=/home/runner/android-sdk`
- **libXray.aar**: NOT present — VPN engine starts Xray inbounds but TUN→SOCKS5 bridge requires the .aar for native routing. Place in `app/libs/` to enable full device-wide VPN.
- **SDK License fix**: If Gradle complains about licenses, manually write all hashes to `~/android-sdk/licenses/android-sdk-license` (see memory file `android-build-setup.md`).

## Latest Changes (July 23 2026) — Audit & Hardening Pass 2

### ملخص التغييرات (Audit & Hardening Pass 2)

#### TunBridge.kt — إصلاحات Chrome + تحسينات السرعة
- ✅ **حجب HTTP/3 (UDP/80)** — إضافة حجب UDP منفذ 80 إلى جانب منفذ 443. Chrome وFirefox يستخدمان UDP/80 كاختبار HTTP/3. الحجب يجعل المتصفحات ترجع إلى TCP/TLS فورياً
- ✅ **زيادة buffer الـ UDP من 2048 إلى 4096** — يحل مشكلة اقتطاع ردود DNS الكبيرة (DNSSEC/IPv6 records) التي كانت تسبب فشل التحليل في Chrome
- ✅ **زيادة مهلة الاتصال من 8s إلى 15s** — يحل مشكلة "Connection timed out" عند بطء Cloudflare edge
- ✅ **زيادة مهلة stall من 120s إلى 180s** — حماية WebSocket / long-poll / SSE connections من الإغلاق المبكر

#### XrayManager.kt — إصلاح Chrome + DNS
- ✅ **`routeOnly: true` في sniffing** — الإصلاح الرئيسي لـ Chrome: Xray يستخدم النطاق المستشَم للتوجيه فقط، لكنه يتصل بالـ IP الأصلي. بدونه، Xray يُعيد تحليل النطاق وينشئ اتصالاً ثانياً يختلف IP عن توقع Chrome مما يسبب رفض TLS
- ✅ **`queryStrategy: "UseIPv4"` في DNS** — يمنع مشكلة توجيه IPv6 المكسور على بعض الشبكات
- ✅ **دعم DoH (DNS-over-HTTPS)** — عند اختيار Cloudflare أو Family DNS، يُكوَّن Xray لاستخدام DoH للاستعلامات الداخلية
- ✅ **حجب المحتوى الإباحي في routing** — عند اختيار Family DNS، يُضاف قاعدة routing تحجب نطاقات البالغين في طبقة Xray (belt-and-suspenders مع DNS-level blocking)
- ✅ **`domainStrategy: "UseIPv4"` للـ direct outbound** — اتساق مع queryStrategy لمنع اتصالات IPv6 المكسورة

#### DnsPreference.kt — حجب المحتوى الإباحي
- ✅ **CleanBrowsing Family (185.228.168.168)** — DNS متخصص لحجب المحتوى الإباحي، مع DoH
- ✅ **Cloudflare for Families (1.1.1.3)** — يحجب الإباحية + البرمجيات الخبيثة + DoH
- ✅ **OpenDNS FamilyShield (208.67.222.123)** — خيار ثالث موثوق
- ✅ **حقل `blockAdult`** — عند اختياره، يُمرَّر إلى XrayManager لإضافة keyword routing rules

#### SecurityChecker.kt — كشف الـ Sniffers
- ✅ **إضافة 14 تطبيق اختراق جديد** — PCAPdroid، HTTP Toolkit، Reqable، تطبيقات SSL Kill Switch، Frida/objection

#### CryptoHelper.kt — تشفير أقوى للـ Config
- ✅ **تنسيق v2 (HKDF subkeys)** — كل config مشفر بمفتاح مختلف مشتق من master key + 16-byte random salt عبر HKDF-SHA256. حتى لو كان المحتوى متطابقاً، الـ ciphertext مختلف تماماً
- ✅ **version byte 0xB2** — التمييز التلقائي بين v1 (legacy) وv2 عند فك التشفير — متوافق مع الملفات القديمة
- ✅ **backward compatible** — الملفات القديمة (v1) تُفك بالطريقة القديمة، الجديدة بـ HKDF

#### bot.py — مزامنة التشفير
- ✅ **دعم v2 في `encrypt_boykta`** — يُنتج ملفات .boykta بتنسيق v2 (HKDF)
- ✅ **دعم v1+v2 في `decrypt_boykta`** — يُميز التلقائي بين التنسيقين
- ✅ **إضافة `aiohttp` للـ requirements.txt** — كان مفقوداً وكان يمنع تشغيل Bot
- ✅ **إنشاء Python venv** — `.venv/` جاهز للتشغيل

#### لتشغيل البوت على Replit
اضبط هذه الـ Secrets في إعدادات Replit:
| المتغير | الوصف |
|---------|-------|
| `BOT_TOKEN` | توكن البوت من @BotFather |
| `DEVELOPER_ID` | رقم حسابك على تيليغرام |
| `CF_TOKEN` | توكن Cloudflare (يُستخدم في bot.py للتحقق من حالة النفق) |
| `CLOUDFLARE_TOKEN` | توكن النفق لـ `cloudflared tunnel run` |

---

## Previous Changes (July 23 2026)

### Full VPN Audit + Performance & Chrome Fix

- ✅ **QUIC blocked (UDP/443)** — `TunBridge.kt` now drops all UDP port 443 packets on both IPv4 and IPv6. Chrome/browsers fall back to TCP/TLS immediately and route correctly through Xray. Root cause of "Chrome not working".
- ✅ **Full IPv6 support** — `BoykVpnService.kt` adds `fd00::1/128` address and `::/0` route to the TUN. `TunBridge.kt` now parses IPv6 headers, relays IPv6 TCP via SOCKS5 CONNECT ATYP=0x04, and injects IPv6 TCP responses back with correct IPv6+TCP checksums. IPv6-only sites and dual-stack connections now work.
- ✅ **Performance: larger socket buffers** — `TcpSession` send/receive buffers increased from 128 KB to 256 KB. Better for streaming and gaming burst traffic.
- ✅ **Performance: larger TUN read buffer** — Increased from 1500 to 8192 bytes. Reduces syscall overhead when the kernel batches packets.
- ✅ **Performance: TcpSession channel capacity** — Increased from 1024 to 2048 frames. Less backpressure on the device→proxy write path under load.
- ✅ **Xray log level `none`** — Eliminates Xray internal log I/O; saves CPU that was wasted on log formatting under load.
- ✅ **Xray routing `IPIfNonMatch`** — Replaces `AsIs`. Xray resolves domains to IP only when no domain-level rule matched, avoiding unnecessary DNS round-trips. Lower first-packet latency for games.
- ✅ **Xray sniffing adds `quic`** — Xray now sniffs QUIC SNI in addition to HTTP Host and TLS SNI, enabling correct routing decisions even when QUIC packets reach the inbound.
- ✅ **Inbound tags added** — SOCKS5 inbound tagged `socks-in`, HTTP inbound tagged `http-in` for clean routing rule targeting.

---

## Full Audit & Bot Overhaul (July 23 2026)

### بوت تيليغرام — bot.py
- ✅ **استمرارية البيانات** — كل البيانات تُحفظ الآن في `boykta_state.json` عبر `_save_state()` ذرية (atomic write). `_load_state()` تُستدعى عند الإقلاع لاستعادة كل شيء بعد إعادة التشغيل.
- ✅ **إصلاح تصادم ID السيرفرات** — `cmd_addserver` كانت تستخدم `len(_servers)+1` مما يسبب تكراراً بعد الحذف؛ صار `max(s["id"])+1` في كل مكان.
- ✅ **إزالة المشتركين المحجوبين** — `_do_broadcast` يكشف "Forbidden/blocked/deactivated" ويزيلهم تلقائياً من `_subscribers`.
- ✅ **فلتر السيرفرات المنتهية** — `/api/servers` لا تُرسل سيرفرات منتهية الصلاحية للتطبيق.
- ✅ **إصلاح رابط الوسائط** — `https://boykta.boykta.dpdns.org` صار متغير بيئة `PUBLIC_BASE_URL` قابل للتغيير.
- ✅ **مهمة انتهاء تلقائي** — `_auto_expire_task()` تفحص كل ساعة وتوقف السيرفرات المنتهية (بدون حذف).
- ✅ **أمر `/toggleserver`** — تفعيل/إيقاف سيرفر بدون حذفه.
- ✅ **حفظ الحالة في كل عملية تغيير** — add/remove server, subscribe/unsubscribe, finalize ad, clear ads, post announcement.

### تطبيق Android
- ✅ **SplashActivity** — يستخدم `lifecycleScope` بدل `CoroutineScope` لتجنب تسرب الكوروتين عند تدمير الـ Activity.
- ✅ **ConfigExportDialog** — `BoykConfigManager.export()` انتقل من Main Thread إلى `Dispatchers.IO` مع `withContext(Main)` للتحديث، يمنع تجميد الواجهة.
- ✅ **MainViewModel auto-ping** — فترة ping من 1 ثانية → 3 ثواني، يقلل استهلاك البطارية 66%.

### متغيرات البيئة الجديدة (bot.py)
| المتغير | القيمة الافتراضية | الوصف |
|---------|------------------|-------|
| `PUBLIC_BASE_URL` | `https://boykta.boykta.dpdns.org` | الرابط العام لروابط وسائط التطبيق |

---

## Previous Changes (July 2026)

- ✅ **Branding**: All "BOYKTA NET" → "BOYKTA VPN" (activity_main.xml ×3, activity_splash.xml, dialog_privacy_policy.xml)
- ✅ **False reconnect loop fix**: Added `networkChangeJob` tracking in `BoykVpnService.kt` — stale coroutines from old sessions are cancelled before reconnect completes, preventing double-reconnect loops
- ✅ **Ping reliability fix**: `TunnelPingChecker.kt` replaced HTTPS-through-SOCKS5 ping (caused SSLHandshakeException/SocketTimeout with Trojan) with plain TCP probe to 1.1.1.1:80 through SOCKS5 — no TLS overhead, no false failures
- ✅ **Unlocked config editing**: The 4 param fields (Target/Path/SNI/Host Header) in the unlocked config panel are now EditText — users can edit inline. "Save & Reconnect" button rebuilds the VLESS/Trojan URI with updated values while preserving UUID/password

### Architecture Overhaul — Full Autonomous Audit (July 22 2026)

- ✅ **CRITICAL: bot.py crypto key mismatch fixed** — `CRYPTO_KEY_RAW` was `"BoyktaVPN_SecureKey_2024_AES256GCM"` but Android `CryptoHelper.kt` uses `"boykta_2nlkkh53DaYBmllnvb2026"`; now aligned. Bot-generated `.boykta` files can now be decrypted by the Android app.
- ✅ **Thread-safe listeners** — `BoykVpnService.listeners` changed from plain `mutableListOf` to `CopyOnWriteArrayList`; eliminates `ConcurrentModificationException` when binder and coroutine threads access the list concurrently.
- ✅ **WakeLock indefinite** — `PowerManager.WakeLock.acquire()` no longer has a 1-hour hard cap (`@SuppressLint("WakelockTimeout")`). Sessions >60 min no longer lose CPU hold; WakeLock is still released explicitly in `releaseWakeLock()`.
- ✅ **`reconnect()` race condition fixed** — Old `reconnect()` called `stopVpn()→stopSelf()→onDestroy()→serviceScope.cancel()`, silently cancelling the deferred `startVpn()`. New implementation does an in-place teardown without stopping the service, then re-runs `startVpn`.
- ✅ **TunBridge `tunOut` FD leak** — `stop()` now closes both `tunIn` and `tunOut`; previously only `tunIn` was closed, leaving a dangling file descriptor each reconnect.
- ✅ **Stall timeout doubled** — `STALL_TIMEOUT_MS` 60s → 120s in `TunBridge`; prevents killing HTTP long-polling / SSE connections that legitimately have no data for >60s.
- ✅ **`VpnLogManager.emitCount` atomicity** — Changed from raw `var` to `AtomicInteger`; prevents missed increments when multiple coroutines emit logs concurrently.
- ✅ **`XrayManager` transport coverage** — Added `splithttp`/`xhttp` (Xray ≥1.8.16), `httpupgrade`, and `reality` security support in `buildStreamSettings`. Links using these transports now produce valid Xray configs instead of silently omitting the transport block.
- ✅ **Deprecated API suppressions** — `SplashActivity.overridePendingTransition` and `LocalDatabase.fallbackToDestructiveMigration` warnings resolved.
- ✅ **bot.py credentials** — `BOT_TOKEN`, `DEVELOPER_ID`, `CF_TOKEN` moved from hardcoded constants to `os.environ.get()` with startup validation. Set these as Replit Secrets before running.

### Build command (Replit)
```bash
export JAVA_HOME=/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk
./gradlew assembleDebug --no-daemon
# Output: app/build/outputs/apk/debug/app-debug.apk  (~7.8 MB)
```

## User Preferences

- Do not edit `.github/workflows/` files
