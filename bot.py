#!/usr/bin/env python3
"""
Boykta VPN — Telegram Bot Control Panel
Single-file admin system for remote app management.

Features:
  • Developer-only command panel
  • Broadcast announcements to users
  • Server list management (add/remove/list)
  • Generate encrypted .boykta config files
  • Subscription management
  • System status checks (Cloudflare tunnel health)
  • User stats & activity logs
  • Remote log push to the app backend

Configuration (via environment variables):
  BOT_TOKEN        — Telegram bot token
  DEVELOPER_ID     — Telegram user ID of the developer
  BACKEND_URL      — Backend API base URL
  BACKEND_PORT     — Backend port
  CF_TOKEN         — Cloudflare tunnel token
"""

import asyncio
import base64
import hashlib
import json
import logging
import os
import secrets
import time
from datetime import datetime, timedelta
from typing import Optional

import httpx
from telegram import (
    Update,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    BotCommand,
)
from telegram.ext import (
    Application,
    CommandHandler,
    CallbackQueryHandler,
    MessageHandler,
    ConversationHandler,
    filters,
    ContextTypes,
)

# ── Configuration (read from environment variables) ───────────────────────────

BOT_TOKEN    = os.environ.get("BOT_TOKEN", "")
DEVELOPER_ID = int(os.environ.get("DEVELOPER_ID", "0"))
BACKEND_URL  = os.environ.get("BACKEND_URL", "https://boykta.boykta.dpdns.org")
BACKEND_PORT = int(os.environ.get("BACKEND_PORT", "25477"))
CF_TOKEN     = os.environ.get("CF_TOKEN", "")

if not BOT_TOKEN:
    raise RuntimeError(
        "BOT_TOKEN environment variable is not set. "
        "Set it before running the bot."
    )
if DEVELOPER_ID == 0:
    raise RuntimeError(
        "DEVELOPER_ID environment variable is not set. "
        "Set it to your Telegram numeric user ID."
    )

# ── AES-256-GCM key derivation ─────────────────────────────────────────────────
# IMPORTANT: this MUST match the key used in the Android app's CryptoHelper.kt.
# Android: MessageDigest.getInstance("SHA-256").digest("boykta_2nlkkh53DaYBmllnvb2026")
# Any change here makes all existing .boykta files unreadable by the app.
CRYPTO_KEY_RAW = "boykta_2nlkkh53DaYBmllnvb2026"
CRYPTO_KEY     = hashlib.sha256(CRYPTO_KEY_RAW.encode()).digest()

# ── Logging ───────────────────────────────────────────────────────────────────

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    level=logging.INFO,
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("boykta_bot.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("BoyktaBot")

# ── In-memory state (replace with DB for production) ─────────────────────────

_servers: list[dict] = []          # {"id", "name", "uri", "expires_at", "active"}
_subscribers: set[int] = set()     # Telegram user IDs who subscribed
_broadcast_log: list[str] = []     # History of broadcasts
_user_log: list[dict] = []         # {"user_id", "action", "ts"}

# ConversationHandler states
(
    AWAIT_BROADCAST_TEXT,
    AWAIT_SERVER_NAME,
    AWAIT_SERVER_URI,
    AWAIT_SERVER_EXPIRY,
    AWAIT_CONFIG_PROTOCOL,
    AWAIT_CONFIG_HOST,
    AWAIT_CONFIG_UUID,
    AWAIT_CONFIG_PORT,
    AWAIT_CONFIG_PATH,
    AWAIT_CONFIG_SNI,
    AWAIT_CONFIG_EXPIRY,
    AWAIT_CONFIG_LOCKED,
    AWAIT_AD_MEDIA,
) = range(13)

# ── Announcement queue ────────────────────────────────────────────────────────
# Each entry: {"id", "type": "image"|"video", "file_ids": [...], "caption": str, "created_at": str}
_announcement_queue: list[dict] = []
_ad_id_counter = 0

def _next_ad_id() -> int:
    global _ad_id_counter
    _ad_id_counter += 1
    return _ad_id_counter

# ── Auth decorator ─────────────────────────────────────────────────────────────

def dev_only(func):
    """Restrict command to DEVELOPER_ID only."""
    async def wrapper(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
        if update.effective_user.id != DEVELOPER_ID:
            await update.message.reply_text("🚫 هذا الأمر للمطور فقط.")
            return ConversationHandler.END
        return await func(update, ctx)
    wrapper.__name__ = func.__name__
    return wrapper

# ── Helpers ────────────────────────────────────────────────────────────────────

def now_ts() -> str:
    return datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%S.000Z")

def expires_ts(days: int) -> str:
    dt = datetime.utcnow() + timedelta(days=days)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")

def encrypt_boykta(payload_json: str) -> str:
    """AES-256-GCM encryption matching Android CryptoHelper."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        iv = secrets.token_bytes(12)
        aesgcm = AESGCM(CRYPTO_KEY)
        ciphertext = aesgcm.encrypt(iv, payload_json.encode(), None)
        # Wire format: Base64(IV[12] || ciphertext+tag)
        return base64.b64encode(iv + ciphertext).decode()
    except ImportError:
        log.warning("cryptography package not installed — returning plain JSON")
        return base64.b64encode(payload_json.encode()).decode()

def build_boykta_config(
    protocol: str, name: str, uuid: str, host: str,
    port: int, path: str = "/", sni: str = "",
    host_header: str = "", network: str = "ws",
    security: str = "tls", expires_days: int = 30,
    locked: bool = True, custom_toast: str = "",
) -> dict:
    """Build a BoykConfig dict matching the Android model."""
    return {
        "v": 2,
        "p": protocol,
        "n": name,
        "id": uuid,
        "h": host,
        "sni": sni or host,
        "hh": host_header or host,
        "port": port,
        "path": path,
        "net": network,
        "sec": security,
        "exp": expires_days * 86400,
        "locked": locked,
        "toast": custom_toast,
        "method": "aes-256-gcm",
    }

def decrypt_boykta(data: bytes) -> dict | None:
    """
    Try to decrypt (AES-256-GCM) or parse (plain JSON) a .boykta file.
    Returns the parsed dict on success, None on failure.
    """
    # 1. Try AES-GCM decrypt first
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        raw = base64.b64decode(data)
        if len(raw) > 12:
            iv = raw[:12]
            ct = raw[12:]
            plaintext = AESGCM(CRYPTO_KEY).decrypt(iv, ct, None)
            return json.loads(plaintext.decode())
    except Exception:
        pass

    # 2. Try plain JSON
    try:
        return json.loads(data.decode())
    except Exception:
        pass

    # 3. Try base64-encoded plain JSON (unlocked via bot genconfig)
    try:
        return json.loads(base64.b64decode(data).decode())
    except Exception:
        pass

    return None


def boykta_cfg_to_uri(cfg: dict) -> str:
    """Convert a BoykConfig dict back to a proxy URI for storage."""
    protocol = cfg.get("p", "vless")
    host     = cfg.get("h", "")
    port     = cfg.get("port", 443)
    uuid     = cfg.get("id", "")
    path     = cfg.get("path", "/")
    sni      = cfg.get("sni", host)
    net      = cfg.get("net", "ws")
    sec      = cfg.get("sec", "tls")
    name     = cfg.get("n", "Server")
    hh       = cfg.get("hh", host)

    if protocol in ("vless",):
        return (
            f"vless://{uuid}@{host}:{port}"
            f"?type={net}&security={sec}&path={path}&sni={sni}&host={hh}"
            f"#{name}"
        )
    elif protocol == "trojan":
        return (
            f"trojan://{uuid}@{host}:{port}"
            f"?type={net}&security={sec}&path={path}&sni={sni}&host={hh}"
            f"#{name}"
        )
    elif protocol == "vmess":
        import json as _json
        vmess_obj = {
            "v": "2", "ps": name, "add": host, "port": str(port),
            "id": uuid, "aid": "0", "net": net, "type": "none",
            "host": hh, "path": path, "tls": sec if sec != "none" else "",
        }
        return "vmess://" + base64.b64encode(_json.dumps(vmess_obj).encode()).decode()
    elif protocol == "ss":
        # Shadowsocks: method:password@host:port
        method   = cfg.get("method", "aes-256-gcm")
        userinfo = base64.b64encode(f"{method}:{uuid}".encode()).decode()
        return f"ss://{userinfo}@{host}:{port}#{name}"
    else:
        return f"{protocol}://{uuid}@{host}:{port}#{name}"


async def check_backend_health() -> dict:
    """Check Cloudflare tunnel + backend health."""
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(f"{BACKEND_URL}/health")
            return {"status": r.status_code, "ok": r.status_code < 400, "latency_ms": int(r.elapsed.total_seconds() * 1000)}
    except Exception as e:
        return {"status": 0, "ok": False, "error": str(e)}

async def push_broadcast_to_backend(
    title: str,
    message: str,
    media_urls: list[str] | None = None,
    telegram_file_ids: list[str] | None = None,
    media_type: str = "image",
) -> bool:
    """
    Push a broadcast announcement to the app backend.

    Use `telegram_file_ids` for media uploaded via the bot.
    NEVER pass token-bearing Telegram download URLs in `media_urls` —
    those embed the bot token and would expose it to every app client.
    The backend must resolve file_ids server-side using its own bot-token copy.
    """
    try:
        async with httpx.AsyncClient(timeout=15, headers={"Authorization": f"Bearer {CF_TOKEN}"}) as client:
            payload: dict = {
                "title": title,
                "message": message,
                "media_type": media_type,
                "created_at": now_ts(),
            }
            if telegram_file_ids and isinstance(telegram_file_ids, list) and len(telegram_file_ids) > 0:
                # Preferred: opaque file_ids — token stays server-side
                payload["telegram_file_ids"] = [str(f) for f in telegram_file_ids]
            elif media_urls and isinstance(media_urls, list) and len(media_urls) > 0:
                # Only for pre-existing public CDN URLs (no bot token embedded)
                payload["media_urls"] = [str(u) for u in media_urls]
            r = await client.post(f"{BACKEND_URL}/api/announcements", json=payload)
            return r.status_code < 400
    except Exception as e:
        log.error(f"Backend push failed: {e}")
        return False


async def _get_file_size(bot, file_id: str) -> int:
    """Return the byte size of a Telegram file (0 on error). Used for validation only."""
    try:
        tg_file = await bot.get_file(file_id)
        return tg_file.file_size or 0
    except Exception:
        return 0

# ── /start ─────────────────────────────────────────────────────────────────────

async def cmd_start(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    uid = update.effective_user.id
    _subscribers.add(uid)
    _user_log.append({"user_id": uid, "action": "start", "ts": now_ts()})

    is_dev = uid == DEVELOPER_ID
    greeting = (
        "مرحباً بك في *Boykta VPN* 🛡\n\n"
        "للاتصال بـ Boykta VPN، حمّل التطبيق وشغّله.\n\n"
        "_قناتنا: @boyktavpn_"
    )
    if is_dev:
        greeting = "مرحباً *مطوّر Boykta VPN* 👾\n\n/panel — لوحة تحكم المطور"

    await update.message.reply_markdown(greeting)

# ── /panel — Developer control panel ─────────────────────────────────────────

@dev_only
async def cmd_panel(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    keyboard = [
        [InlineKeyboardButton("📣 بث إعلان", callback_data="panel_broadcast"),
         InlineKeyboardButton("🖥 إدارة السيرفرات", callback_data="panel_servers")],
        [InlineKeyboardButton("🔑 توليد كونفيغ", callback_data="panel_genconfig"),
         InlineKeyboardButton("📊 إحصائيات", callback_data="panel_stats")],
        [InlineKeyboardButton("📢 إدارة الإعلانات", callback_data="panel_ads"),
         InlineKeyboardButton("💓 فحص الخادم", callback_data="panel_health")],
        [InlineKeyboardButton("📋 سجل المستخدمين", callback_data="panel_userlog")],
    ]
    await update.message.reply_markdown(
        "🎛 *لوحة تحكم Boykta VPN — مطور*",
        reply_markup=InlineKeyboardMarkup(keyboard)
    )

# ── Inline button router ──────────────────────────────────────────────────────

async def cb_panel(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    q = update.callback_query
    await q.answer()
    data = q.data

    if update.effective_user.id != DEVELOPER_ID:
        await q.message.reply_text("🚫 للمطور فقط.")
        return

    if data == "panel_health":
        health = await check_backend_health()
        emoji = "✅" if health["ok"] else "❌"
        latency = health.get("latency_ms", "—")
        error = health.get("error", "")
        text = (
            f"{emoji} *حالة الخادم*\n"
            f"URL: `{BACKEND_URL}`\n"
            f"Status: `{health['status']}`\n"
            f"Latency: `{latency}ms`\n"
            + (f"Error: `{error}`" if error else "")
        )
        await q.message.reply_markdown(text)

    elif data == "panel_stats":
        text = (
            f"📊 *إحصائيات Boykta VPN*\n\n"
            f"المشتركون: `{len(_subscribers)}`\n"
            f"السيرفرات: `{len(_servers)}`\n"
            f"البثوث المرسلة: `{len(_broadcast_log)}`\n"
            f"سجلات الاستخدام: `{len(_user_log)}`\n"
        )
        await q.message.reply_markdown(text)

    elif data == "panel_userlog":
        if not _user_log:
            await q.message.reply_text("لا توجد سجلات بعد.")
            return
        lines = [f"`{e['user_id']}` — {e['action']} @ {e['ts']}" for e in _user_log[-20:]]
        await q.message.reply_markdown("📋 *آخر 20 حدث:*\n\n" + "\n".join(lines))

    elif data == "panel_servers":
        if not _servers:
            await q.message.reply_text("لا توجد سيرفرات. استخدم /addserver أو أرسل ملف .boykta مباشرة.")
            return
        buttons = []
        for s in _servers:
            active = "✅" if s.get("active") else "❌"
            buttons.append([InlineKeyboardButton(
                f"{active} [{s['id']}] {s['name']} — {s.get('expires_at','?')[:10]}",
                callback_data=f"del_server_{s['id']}"
            )])
        buttons.append([InlineKeyboardButton("🔙 رجوع", callback_data="panel_back")])
        await q.message.reply_markdown(
            "🖥 *السيرفرات:* (اضغط على سيرفر لحذفه)\n\n"
            f"المجموع: {len(_servers)} سيرفر",
            reply_markup=InlineKeyboardMarkup(buttons)
        )

    elif data.startswith("del_server_"):
        if data == "del_server_cancel":
            await q.message.edit_text("❌ تم الإلغاء.")
            return
        try:
            sid = int(data.replace("del_server_", ""))
        except ValueError:
            return
        # Find server name before deletion
        server_name = next((s["name"] for s in _servers if s["id"] == sid), f"ID {sid}")
        found = _do_delete_server(sid)
        if found:
            await q.message.edit_text(f"🗑 تم حذف السيرفر: *{server_name}* (ID {sid})", parse_mode="Markdown")
        else:
            await q.message.reply_text(f"❌ لم يُوجَد سيرفر بالـ ID {sid}.")
        return

    elif data == "panel_back":
        # Re-show panel
        keyboard = [
            [InlineKeyboardButton("📣 بث إعلان", callback_data="panel_broadcast"),
             InlineKeyboardButton("🖥 إدارة السيرفرات", callback_data="panel_servers")],
            [InlineKeyboardButton("🔑 توليد كونفيغ", callback_data="panel_genconfig"),
             InlineKeyboardButton("📊 إحصائيات", callback_data="panel_stats")],
            [InlineKeyboardButton("📢 إدارة الإعلانات", callback_data="panel_ads"),
             InlineKeyboardButton("💓 فحص الخادم", callback_data="panel_health")],
            [InlineKeyboardButton("📋 سجل المستخدمين", callback_data="panel_userlog")],
        ]
        await q.message.edit_text("🎛 لوحة تحكم Boykta VPN — مطور", reply_markup=InlineKeyboardMarkup(keyboard))

    elif data == "panel_broadcast":
        await q.message.reply_text(
            "📣 أرسل نص الإعلان الآن (أو /cancel للإلغاء):"
        )
        ctx.user_data["awaiting"] = "broadcast"

    elif data == "panel_ads":
        if not _announcement_queue:
            keyboard = [[InlineKeyboardButton("➕ إضافة إعلان جديد", callback_data="panel_setad"),
                         InlineKeyboardButton("🔙 رجوع", callback_data="panel_back")]]
            await q.message.reply_markdown(
                "📢 *قائمة الإعلانات* — لا توجد إعلانات حالياً.",
                reply_markup=InlineKeyboardMarkup(keyboard)
            )
        else:
            lines = []
            buttons = []
            for ad in _announcement_queue:
                media_label = "📹 فيديو" if ad["type"] == "video" else f"🖼 صور ({len(ad['file_ids'])})"
                lines.append(f"[{ad['id']}] {media_label} — {ad['created_at'][:10]}")
                buttons.append([InlineKeyboardButton(
                    f"🗑 [{ad['id']}] {media_label}", callback_data=f"del_ad_{ad['id']}"
                )])
            buttons.append([
                InlineKeyboardButton("➕ إضافة إعلان", callback_data="panel_setad"),
                InlineKeyboardButton("🔙 رجوع", callback_data="panel_back"),
            ])
            await q.message.reply_markdown(
                "📢 *قائمة الإعلانات:* (اضغط لحذف)\n\n" + "\n".join(lines),
                reply_markup=InlineKeyboardMarkup(buttons)
            )

    elif data.startswith("del_ad_"):
        try:
            aid = int(data.replace("del_ad_", ""))
            before = len(_announcement_queue)
            _announcement_queue[:] = [a for a in _announcement_queue if a["id"] != aid]
            if len(_announcement_queue) < before:
                await q.message.edit_text(f"🗑 تم حذف الإعلان ID {aid}.")
            else:
                await q.message.reply_text(f"❌ لا يوجد إعلان بالـ ID {aid}.")
        except (ValueError, Exception):
            pass

    elif data == "panel_setad":
        await q.message.reply_text(
            "📢 *إضافة إعلان جديد*\n\n"
            "أرسل صورة واحدة أو أكثر (ستظهر كعرض شرائح) أو فيديو (حتى 15 ثانية).\n\n"
            "بعد إرسال جميع الصور اكتب /done — أو أرسل الفيديو مباشرة.\n"
            "/cancel للإلغاء.",
            parse_mode="Markdown"
        )
        ctx.user_data["awaiting"] = "ad_media"
        ctx.user_data["pending_ad"] = {"file_ids": [], "type": "image"}

    elif data == "panel_genconfig":
        await q.message.reply_text(
            "🔑 أرسل بروتوكول الكونفيغ (vless / trojan / vmess / ss):"
        )
        ctx.user_data["awaiting"] = "config_protocol"
        ctx.user_data["config"] = {}

# ── /broadcast ─────────────────────────────────────────────────────────────────

@dev_only
async def cmd_broadcast(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    if not ctx.args:
        await update.message.reply_text("الاستخدام: /broadcast <النص>\n\nمثال: /broadcast تحديث جديد متاح!")
        return
    text = " ".join(ctx.args)
    await _do_broadcast(update, ctx, "إعلان Boykta VPN", text)

async def _do_broadcast(
    update: Update, ctx: ContextTypes.DEFAULT_TYPE,
    title: str, text: str,
    media_urls: list[str] | None = None,
    media_type: str = "image",
):
    sent = 0
    failed = 0
    for uid in list(_subscribers):
        try:
            await ctx.bot.send_message(
                uid, f"📣 *{title}*\n\n{text}", parse_mode="Markdown"
            )
            sent += 1
        except Exception:
            failed += 1
    _broadcast_log.append(f"{now_ts()} | {title}: {text[:60]}")
    # Push announcement to app backend so it shows as in-app ad after connection
    ok = await push_broadcast_to_backend(title, text, media_urls=media_urls, media_type=media_type)
    backend_tag = "✅ Backend (إعلان داخل التطبيق)" if ok else "❌ Backend failed"
    await update.message.reply_text(
        f"📣 تم البث\n\n"
        f"أُرسل إلى: {sent} مستخدم\n"
        f"فشل: {failed}\n"
        f"{backend_tag}"
    )

# ── /setad — Add a media announcement (photos carousel or video) ───────────────

@dev_only
async def cmd_setad(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """
    Start an ad-creation flow.
    Developer can send multiple photos (= carousel) or a single video (≤15 s).
    Type /done after photos to finalize. Video finalizes automatically.
    """
    await update.message.reply_text(
        "📢 *إضافة إعلان جديد*\n\n"
        "أرسل صورة واحدة أو أكثر (ستظهر كعرض شرائح تلقائي) أو فيديو (حتى 15 ثانية).\n\n"
        "• بعد إرسال جميع الصور اكتب /done\n"
        "• الفيديو يُضاف تلقائياً دون /done\n"
        "• /cancel للإلغاء",
        parse_mode="Markdown"
    )
    ctx.user_data["awaiting"] = "ad_media"
    ctx.user_data["pending_ad"] = {"file_ids": [], "type": "image"}


@dev_only
async def cmd_listads(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """List all queued announcements."""
    if not _announcement_queue:
        await update.message.reply_text("📢 لا توجد إعلانات في القائمة.")
        return
    lines = []
    for ad in _announcement_queue:
        t = "📹 فيديو" if ad["type"] == "video" else f"🖼 صور ({len(ad['file_ids'])})"
        lines.append(f"[{ad['id']}] {t} — {ad['created_at'][:10]}")
    await update.message.reply_markdown(
        "📢 *قائمة الإعلانات المجدولة:*\n\n" + "\n".join(lines) +
        f"\n\nالمجموع: {len(_announcement_queue)}"
    )


@dev_only
async def cmd_clearads(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """Clear all queued announcements."""
    count = len(_announcement_queue)
    _announcement_queue.clear()
    await update.message.reply_text(f"🗑 تم حذف {count} إعلان.")


async def cmd_donead(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """Finalize a photo-based ad after the developer is done sending photos."""
    if update.effective_user.id != DEVELOPER_ID:
        return
    pending = ctx.user_data.get("pending_ad")
    if ctx.user_data.get("awaiting") != "ad_media" or not pending:
        await update.message.reply_text("لا يوجد إعلان جارٍ إنشاؤه.")
        return
    if not pending["file_ids"]:
        await update.message.reply_text("❌ لم تُرسل أي صور بعد.")
        return
    await _finalize_ad(update, ctx, pending)


async def _finalize_ad(update, ctx, pending: dict):
    """
    Save announcement to queue and push file_ids (NOT token-bearing URLs) to backend.

    Security note: Telegram download URLs embed the bot token
    (https://api.telegram.org/file/bot{TOKEN}/...). We NEVER build or
    transmit those URLs. Instead we send opaque Telegram file_ids to the
    backend, which must resolve them server-side using its own copy of the
    bot token and serve the resulting media through its own CDN/proxy
    endpoint — keeping the bot token out of client-facing payloads entirely.
    """
    ctx.user_data.pop("awaiting", None)
    ctx.user_data.pop("pending_ad", None)

    file_ids = pending.get("file_ids", [])
    if not file_ids:
        await update.message.reply_text("❌ لا توجد ملفات مرفقة.")
        return

    await update.message.reply_text("⏳ جارٍ حفظ الإعلان…")

    ad_id = _next_ad_id()
    ad = {
        "id": ad_id,
        "type": pending["type"],
        "file_ids": file_ids,          # opaque Telegram identifiers — not secrets
        "created_at": now_ts(),
    }
    _announcement_queue.append(ad)

    # Push to backend using opaque file_ids, NOT token-bearing download URLs.
    # The backend resolves file_ids server-side via its own bot-token copy.
    ok = await push_broadcast_to_backend(
        title="إعلان Boykta VPN",
        message="",
        telegram_file_ids=file_ids,
        media_type=pending["type"],
    )
    backend_tag = "✅ مُرسل للتطبيق" if ok else "⚠️ Backend failed — الإعلان محفوظ محلياً"

    media_label = "📹 فيديو" if pending["type"] == "video" else f"🖼 {len(file_ids)} صورة"
    await update.message.reply_markdown(
        f"✅ *تم إضافة الإعلان*\n\n"
        f"🆔 ID: `{ad_id}`\n"
        f"النوع: {media_label}\n"
        f"Backend: {backend_tag}\n\n"
        f"سيظهر هذا الإعلان في التطبيق عند الاتصال الناجح لمدة 15 ثانية."
    )


# ── /addserver ────────────────────────────────────────────────────────────────

@dev_only
async def cmd_addserver(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """Add a server to the managed list. Usage: /addserver <name> <uri> [days=30]"""
    if len(ctx.args) < 2:
        await update.message.reply_text(
            "الاستخدام: /addserver <الاسم> <vless_uri> [أيام_الصلاحية=30]\n\n"
            "مثال:\n/addserver Germany-01 vless://uuid@host:port?... 30"
        )
        return
    name     = ctx.args[0]
    uri      = ctx.args[1]
    days     = int(ctx.args[2]) if len(ctx.args) > 2 else 30
    server_id = len(_servers) + 1

    _servers.append({
        "id": server_id,
        "name": name,
        "uri": uri,
        "expires_at": expires_ts(days),
        "active": True,
        "created_at": now_ts(),
    })
    await update.message.reply_markdown(
        f"✅ *تمت إضافة السيرفر*\n\n"
        f"ID: `{server_id}`\n"
        f"الاسم: *{name}*\n"
        f"الصلاحية: {days} يوم\n"
        f"ينتهي: `{expires_ts(days)[:10]}`"
    )

# ── /removeserver ─────────────────────────────────────────────────────────────

@dev_only
async def cmd_removeserver(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    if not ctx.args:
        await update.message.reply_text("الاستخدام: /removeserver <id>")
        return
    try:
        sid = int(ctx.args[0])
    except ValueError:
        await update.message.reply_text("ID يجب أن يكون رقماً.")
        return
    before = len(_servers)
    _servers[:] = [s for s in _servers if s["id"] != sid]
    if len(_servers) < before:
        await update.message.reply_text(f"✅ تم حذف السيرفر {sid}.")
    else:
        await update.message.reply_text(f"❌ لم يُوجَد سيرفر بالـ ID {sid}.")

# ── /listservers ──────────────────────────────────────────────────────────────

@dev_only
async def cmd_listservers(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    if not _servers:
        await update.message.reply_text("لا توجد سيرفرات مضافة.")
        return
    lines = []
    for s in _servers:
        status = "✅" if s.get("active") else "❌"
        lines.append(f"{status} [{s['id']}] *{s['name']}* — {s.get('expires_at','?')[:10]}")
    await update.message.reply_markdown("🖥 *قائمة السيرفرات:*\n\n" + "\n".join(lines))

# ── /genconfig — Generate .boykta config file ─────────────────────────────────

@dev_only
async def cmd_genconfig(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """
    Generate a .boykta encrypted config file.
    Usage: /genconfig <protocol> <name> <host> <port> <uuid> [path=/] [sni=] [days=30] [locked=true]
    """
    args = ctx.args
    if len(args) < 5:
        await update.message.reply_text(
            "الاستخدام:\n"
            "/genconfig <proto> <name> <host> <port> <uuid> [path=/] [sni=] [days=30] [locked=true]\n\n"
            "مثال:\n"
            "/genconfig vless Germany-01 example.com 443 uuid-here /ws example.com 30 true"
        )
        return

    protocol = args[0].lower()
    name     = args[1]
    host     = args[2]
    port     = int(args[3])
    uuid     = args[4]
    path     = args[5] if len(args) > 5 else "/"
    sni      = args[6] if len(args) > 6 else host
    days     = int(args[7]) if len(args) > 7 else 30
    locked   = (args[8].lower() != "false") if len(args) > 8 else True

    config = build_boykta_config(
        protocol=protocol, name=name, uuid=uuid, host=host,
        port=port, path=path, sni=sni, host_header=host,
        expires_days=days, locked=locked,
    )
    config_json = json.dumps(config, ensure_ascii=False)

    if locked:
        payload = encrypt_boykta(config_json)
    else:
        payload = config_json

    filename = f"{name.replace(' ', '_')}.boykta"

    # Send as document
    import io
    file_obj = io.BytesIO(payload.encode())
    file_obj.name = filename

    await update.message.reply_document(
        document=file_obj,
        filename=filename,
        caption=(
            f"🔑 *كونفيغ Boykta VPN*\n\n"
            f"الاسم: *{name}*\n"
            f"البروتوكول: `{protocol.upper()}`\n"
            f"الصالحية: {days} يوم\n"
            f"مشفّر: {'نعم' if locked else 'لا'}"
        ),
        parse_mode="Markdown"
    )

# ── /status — System status ───────────────────────────────────────────────────

@dev_only
async def cmd_status(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    health = await check_backend_health()
    emoji  = "✅" if health["ok"] else "❌"
    text = (
        f"*نظام Boykta VPN — حالة الخادم*\n\n"
        f"{emoji} Backend: `{BACKEND_URL}`\n"
        f"HTTP Status: `{health.get('status', '—')}`\n"
        f"Latency: `{health.get('latency_ms', '—')}ms`\n"
        f"Port: `{BACKEND_PORT}`\n"
        f"CF Tunnel: {'متصل' if health['ok'] else 'غير متصل'}\n\n"
        f"السيرفرات: `{len(_servers)}`\n"
        f"المشتركون: `{len(_subscribers)}`\n"
        f"الوقت: `{datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S')} UTC`"
    )
    await update.message.reply_markdown(text)

# ── /help ─────────────────────────────────────────────────────────────────────

async def cmd_help(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    uid = update.effective_user.id
    is_dev = uid == DEVELOPER_ID
    base = (
        "*Boykta VPN Bot — المساعدة*\n\n"
        "/start — بدء الاستخدام\n"
        "/status — حالة الخادم\n"
        "/help — هذه الرسالة\n"
    )
    dev_cmds = (
        "\n*أوامر المطور:*\n"
        "/panel — لوحة التحكم الكاملة\n"
        "/broadcast <نص> — بث إعلان نصي\n"
        "/setad — إضافة إعلان بصور أو فيديو (يظهر بعد الاتصال)\n"
        "/listads — قائمة الإعلانات المجدولة\n"
        "/clearads — حذف جميع الإعلانات\n"
        "/addserver <اسم> <uri> [أيام] — إضافة سيرفر يدوياً\n"
        "/removeserver <id> — حذف سيرفر بالـ ID\n"
        "/deleteserver [id] — حذف سيرفر بقائمة تفاعلية\n"
        "/listservers — قائمة السيرفرات\n"
        "/genconfig — توليد كونفيغ مشفر\n"
        "/status — فحص الخادم\n\n"
        "📎 *أرسل ملف .boykta مباشرة لرفعه كسيرفر تلقائياً*\n"
        "   (يدعم الملفات المشفرة والمفتوحة)\n\n"
        "🖼 *لإضافة إعلان بصور:* اكتب /setad ثم أرسل الصور وأنهِ بـ /done\n"
        "📹 *لإضافة إعلان فيديو:* اكتب /setad ثم أرسل الفيديو مباشرة"
    )
    await update.message.reply_markdown(base + (dev_cmds if is_dev else ""))

# ── Photo/Video handler for ad creation ──────────────────────────────────────

async def handle_media_message(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """Handle photo and video messages sent during ad creation flow."""
    if update.effective_user.id != DEVELOPER_ID:
        return
    if ctx.user_data.get("awaiting") != "ad_media":
        return

    msg = update.message
    pending = ctx.user_data.setdefault("pending_ad", {"file_ids": [], "type": "image"})

    if msg.photo:
        # Take the highest-resolution version
        file_id = msg.photo[-1].file_id
        pending["file_ids"].append(file_id)
        pending["type"] = "image"
        count = len(pending["file_ids"])
        await msg.reply_text(
            f"✅ صورة {count} مضافة. أرسل المزيد أو اكتب /done للحفظ."
        )
    elif msg.video:
        # Video → finalize immediately
        pending["file_ids"] = [msg.video.file_id]
        pending["type"] = "video"
        await _finalize_ad(update, ctx, pending)
    elif msg.document and msg.document.mime_type and msg.document.mime_type.startswith("video"):
        pending["file_ids"] = [msg.document.file_id]
        pending["type"] = "video"
        await _finalize_ad(update, ctx, pending)
    else:
        await msg.reply_text("أرسل صورة أو فيديو. أو اكتب /cancel للإلغاء.")


# ── Message handler for conversation states ───────────────────────────────────

async def handle_message(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    awaiting = ctx.user_data.get("awaiting")
    if not awaiting:
        return

    text = update.message.text.strip()

    if awaiting == "broadcast":
        ctx.user_data.pop("awaiting", None)
        await _do_broadcast(update, ctx, "إعلان Boykta VPN", text)

    elif awaiting == "config_protocol":
        ctx.user_data["config"]["protocol"] = text.lower()
        ctx.user_data["awaiting"] = "config_host"
        await update.message.reply_text("أرسل عنوان السيرفر (host):")

    elif awaiting == "config_host":
        ctx.user_data["config"]["host"] = text
        ctx.user_data["awaiting"] = "config_uuid"
        await update.message.reply_text("أرسل UUID أو Password:")

    elif awaiting == "config_uuid":
        ctx.user_data["config"]["uuid"] = text
        ctx.user_data["awaiting"] = "config_port"
        await update.message.reply_text("أرسل رقم البورت:")

    elif awaiting == "config_port":
        try:
            ctx.user_data["config"]["port"] = int(text)
        except ValueError:
            await update.message.reply_text("البورت يجب أن يكون رقماً.")
            return
        ctx.user_data["awaiting"] = "config_done"
        cfg = ctx.user_data["config"]
        config = build_boykta_config(
            protocol=cfg.get("protocol", "vless"),
            name=f"Config-{int(time.time())}",
            uuid=cfg["uuid"], host=cfg["host"], port=cfg["port"],
        )
        config_json = json.dumps(config, ensure_ascii=False)
        payload = encrypt_boykta(config_json)
        import io
        file_obj = io.BytesIO(payload.encode())
        name_safe = f"boykta_config_{int(time.time())}.boykta"
        file_obj.name = name_safe
        ctx.user_data.pop("awaiting", None)
        ctx.user_data.pop("config", None)
        await update.message.reply_document(
            document=file_obj, filename=name_safe,
            caption="✅ تم توليد الكونفيغ المشفّر"
        )

# ── .boykta file upload handler ──────────────────────────────────────────────

async def handle_boykta_document(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """
    Developer sends a .boykta file directly to the bot.
    Bot decrypts/parses it and registers it as a server automatically.
    Also supports any document if the developer is the sender.
    """
    msg = update.message
    if not msg or not msg.document:
        return

    # Only developer can upload servers
    if update.effective_user.id != DEVELOPER_ID:
        await msg.reply_text("🚫 رفع السيرفرات للمطور فقط.")
        return

    doc = msg.document
    filename = doc.file_name or ""

    # Accept .boykta files or any file the developer sends
    if not filename.endswith(".boykta") and "boykta" not in filename.lower():
        # Not a .boykta file — ignore silently (could be other uploads)
        return

    await msg.reply_text("⏳ جارٍ تحليل الملف...")

    try:
        tg_file = await ctx.bot.get_file(doc.file_id)
        raw_bytes = await tg_file.download_as_bytearray()
    except Exception as e:
        await msg.reply_text(f"❌ فشل تحميل الملف: {e}")
        return

    cfg = decrypt_boykta(bytes(raw_bytes))
    if cfg is None:
        await msg.reply_text(
            "❌ تعذّر قراءة الملف.\n\n"
            "تأكد أن الملف:\n"
            "• مشفّر بـ AES-256-GCM (ملف مغلق)\n"
            "• أو JSON صالح (ملف مفتوح)"
        )
        return

    # Extract fields
    name     = cfg.get("n") or cfg.get("name") or f"Server-{int(time.time())}"
    protocol = cfg.get("p") or cfg.get("protocol") or "vless"
    host     = cfg.get("h") or cfg.get("host") or ""
    port     = cfg.get("port") or 443
    exp_secs = cfg.get("exp") or 0
    locked   = cfg.get("locked", True)

    # Build a URI for storage
    uri = boykta_cfg_to_uri(cfg)

    # Calculate expiry
    if exp_secs and exp_secs > 0:
        from datetime import datetime, timedelta
        exp_dt = datetime.utcnow() + timedelta(seconds=int(exp_secs))
        exp_str = exp_dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    else:
        exp_str = expires_ts(30)  # default 30 days

    server_id = (max(s["id"] for s in _servers) + 1) if _servers else 1
    _servers.append({
        "id":         server_id,
        "name":       name,
        "uri":        uri,
        "config":     cfg,
        "expires_at": exp_str,
        "active":     True,
        "created_at": now_ts(),
        "locked":     locked,
    })

    # Inline button to delete this server immediately
    keyboard = InlineKeyboardMarkup([[
        InlineKeyboardButton(f"🗑 حذف هذا السيرفر (ID {server_id})", callback_data=f"del_server_{server_id}"),
    ]])

    lock_tag = "🔒 مغلق" if locked else "🔓 مفتوح"
    await msg.reply_markdown(
        f"✅ *تم رفع السيرفر إلى التطبيق*\n\n"
        f"🆔 ID: `{server_id}`\n"
        f"📛 الاسم: *{name}*\n"
        f"🌐 البروتوكول: `{protocol.upper()}`\n"
        f"🖥 الخادم: `{host}:{port}`\n"
        f"🔐 النوع: {lock_tag}\n"
        f"📅 ينتهي: `{exp_str[:10]}`",
        reply_markup=keyboard
    )


# ── /deleteserver — Delete server by ID with optional inline confirmation ──────

@dev_only
async def cmd_deleteserver(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    """Delete a server by ID. Shows interactive list if no ID given."""
    if ctx.args:
        try:
            sid = int(ctx.args[0])
        except ValueError:
            await update.message.reply_text("ID يجب أن يكون رقماً. مثال: /deleteserver 3")
            return
        _do_delete_server(sid)
        await update.message.reply_text(f"✅ تم حذف السيرفر ID {sid}." if not any(s["id"] == sid for s in _servers) else f"❌ لا يوجد سيرفر بالـ ID {sid}.")
        return

    # No ID given — show interactive list
    if not _servers:
        await update.message.reply_text("لا توجد سيرفرات.")
        return

    buttons = []
    for s in _servers:
        buttons.append([InlineKeyboardButton(
            f"🗑 [{s['id']}] {s['name']}", callback_data=f"del_server_{s['id']}"
        )])
    buttons.append([InlineKeyboardButton("❌ إلغاء", callback_data="del_server_cancel")])
    await update.message.reply_markdown(
        "🗑 *اختر السيرفر الذي تريد حذفه:*",
        reply_markup=InlineKeyboardMarkup(buttons)
    )


def _do_delete_server(sid: int) -> bool:
    """Remove server by id from _servers. Returns True if found."""
    before = len(_servers)
    _servers[:] = [s for s in _servers if s["id"] != sid]
    return len(_servers) < before


# ── /cancel ────────────────────────────────────────────────────────────────────

async def cmd_cancel(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    ctx.user_data.clear()
    await update.message.reply_text("✅ تم الإلغاء.")

# ── /subscribe / /unsubscribe ─────────────────────────────────────────────────

async def cmd_subscribe(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    uid = update.effective_user.id
    _subscribers.add(uid)
    await update.message.reply_text("✅ تم اشتراكك في إعلانات Boykta VPN.")

async def cmd_unsubscribe(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    uid = update.effective_user.id
    _subscribers.discard(uid)
    await update.message.reply_text("✅ تم إلغاء اشتراكك.")

# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    log.info("Starting Boykta VPN Telegram Bot...")
    app = Application.builder().token(BOT_TOKEN).build()

    # Commands
    app.add_handler(CommandHandler("start",         cmd_start))
    app.add_handler(CommandHandler("help",          cmd_help))
    app.add_handler(CommandHandler("panel",         cmd_panel))
    app.add_handler(CommandHandler("broadcast",     cmd_broadcast))
    app.add_handler(CommandHandler("setad",         cmd_setad))
    app.add_handler(CommandHandler("listads",       cmd_listads))
    app.add_handler(CommandHandler("clearads",      cmd_clearads))
    app.add_handler(CommandHandler("done",          cmd_donead))
    app.add_handler(CommandHandler("addserver",     cmd_addserver))
    app.add_handler(CommandHandler("removeserver",  cmd_removeserver))
    app.add_handler(CommandHandler("deleteserver",  cmd_deleteserver))
    app.add_handler(CommandHandler("listservers",   cmd_listservers))
    app.add_handler(CommandHandler("genconfig",     cmd_genconfig))
    app.add_handler(CommandHandler("status",        cmd_status))
    app.add_handler(CommandHandler("subscribe",     cmd_subscribe))
    app.add_handler(CommandHandler("unsubscribe",   cmd_unsubscribe))
    app.add_handler(CommandHandler("cancel",        cmd_cancel))

    # Inline button callbacks
    app.add_handler(CallbackQueryHandler(cb_panel))

    # Photo / video / video-document handler for ad creation (must be before document handler)
    app.add_handler(MessageHandler(
        filters.PHOTO | filters.VIDEO | filters.Document.VIDEO,
        handle_media_message,
    ))

    # .boykta document upload handler (must come before text handler)
    app.add_handler(MessageHandler(filters.Document.ALL, handle_boykta_document))

    # Free-form message handler (for conversation steps)
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))

    # Set bot commands list
    async def post_init(application):
        await application.bot.set_my_commands([
            BotCommand("start",       "بدء الاستخدام"),
            BotCommand("help",        "قائمة الأوامر"),
            BotCommand("subscribe",   "اشتراك في الإعلانات"),
            BotCommand("unsubscribe", "إلغاء الاشتراك"),
            BotCommand("status",      "حالة الخادم"),
        ])
    app.post_init = post_init

    log.info(f"Bot running — Developer ID: {DEVELOPER_ID}")
    app.run_polling(drop_pending_updates=True)


if __name__ == "__main__":
    main()
