package com.boykta.vpn.service

import android.util.Log
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages Xray-core lifecycle via the XTLS/libXray AAR.
 *
 * Supports VLESS, Trojan, VMess, and Shadowsocks outbound configurations
 * with transport options: WebSocket (WS), gRPC, HTTP/2, TCP.
 *
 * Key design decisions:
 *  • forceStop() ALWAYS called before start() — eliminates "xray is already running" crashes.
 *  • NO geosite/geoip rules — avoids "stat /system/bin/geosite.dat" runtime crashes.
 *  • Base64 sanitization (URL-safe chars → standard) in VMess/SS parsers.
 *  • Path strings preserved as-is — no forced leading-slash injection.
 */
object XrayManager {

    private const val TAG = "XrayManager"
    private const val API_VERSION        = 1
    private const val METHOD_RUN         = "runXrayFromJson"
    private const val METHOD_STOP        = "stopXray"
    private const val METHOD_STATE       = "getXrayState"
    private const val METHOD_VERSION     = "xrayVersion"

    @Volatile private var xrayRunning = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Start Xray-core with the given proxy URI.
     * Always calls forceStop() first to prevent stale-instance port conflicts.
     */
    fun start(proxyUri: String, socksPort: Int, httpPort: Int): Boolean {
        forceStop()   // belt-and-suspenders — BoykVpnService also waits for port free

        return try {
            VpnLogManager.sys("Building Xray config…  protocol=${proxyUri.substringBefore("://")}")
            val cfg = buildXrayConfig(proxyUri, socksPort, httpPort)

            val req = JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_RUN)
                put("payload", JSONObject().apply { put("configJSON", cfg) })
            }.toString()

            val resp = JSONObject(LibXray.invoke(req))

            if (resp.optBoolean("success", false)) {
                xrayRunning = true
                val ver = version() ?: "?"
                VpnLogManager.success("Xray-core v$ver started — SOCKS5 127.0.0.1:$socksPort | HTTP :$httpPort")
                Log.i(TAG, "Xray started (v$ver)")
                true
            } else {
                val err = resp.optString("error", "unknown")
                VpnLogManager.error("Xray start failed: $err")
                Log.e(TAG, "Xray start error: $err")
                false
            }
        } catch (e: Exception) {
            VpnLogManager.error("Xray exception: ${e.javaClass.simpleName} — ${e.message?.take(120)}")
            Log.e(TAG, "Exception starting Xray", e)
            false
        }
    }

    fun stop() {
        if (xrayRunning) forceStop()
    }

    /**
     * Force-stop Xray regardless of internal state.
     * Called before every start() and on service destroy.
     */
    fun forceStop() {
        try {
            LibXray.invoke(JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_STOP)
            }.toString())
            VpnLogManager.sys("Xray-core stop command sent")
            Log.i(TAG, "Xray force-stopped")
        } catch (e: Exception) {
            Log.w(TAG, "forceStop exception (safe): ${e.message}")
        } finally {
            xrayRunning = false
        }
    }

    fun isRunning(): Boolean = try {
        val resp = JSONObject(LibXray.invoke(JSONObject().apply {
            put("apiVersion", API_VERSION)
            put("method", METHOD_STATE)
        }.toString()))
        resp.optBoolean("success") &&
            resp.optJSONObject("data")?.optBoolean("running", false) == true
    } catch (_: Exception) { xrayRunning }

    fun version(): String? = try {
        val resp = JSONObject(LibXray.invoke(JSONObject().apply {
            put("apiVersion", API_VERSION)
            put("method", METHOD_VERSION)
        }.toString()))
        resp.optJSONObject("data")?.optString("version")
    } catch (_: Exception) { null }

    // ── Config builder ─────────────────────────────────────────────────────────

    /**
     * Builds a minimal Xray JSON config.
     * No geosite/geoip files required — all traffic routes directly to proxy.
     */
    private fun buildXrayConfig(proxyUri: String, socksPort: Int, httpPort: Int): String {
        val outbound = when {
            proxyUri.startsWith("vless://")  -> buildVlessOutbound(proxyUri)
            proxyUri.startsWith("trojan://") -> buildTrojanOutbound(proxyUri)
            proxyUri.startsWith("vmess://")  -> buildVmessOutbound(proxyUri)
            proxyUri.startsWith("ss://")     -> buildShadowsocksOutbound(proxyUri)
            else -> buildVlessOutbound(proxyUri)
        }

        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })

            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put("8.8.8.8"); put("1.1.1.1"); put("localhost")
                })
            })

            put("inbounds", JSONArray().apply {
                // SOCKS5 inbound with TLS/HTTP sniffing for domain detection
                put(JSONObject().apply {
                    put("port", socksPort)
                    put("protocol", "socks")
                    put("listen", "127.0.0.1")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                        put("ip", "127.0.0.1")
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().apply { put("http"); put("tls") })
                        put("routeOnly", false)
                    })
                })
                // HTTP proxy inbound
                put(JSONObject().apply {
                    put("port", httpPort)
                    put("protocol", "http")
                    put("listen", "127.0.0.1")
                    put("settings", JSONObject().apply { put("allowTransparent", false) })
                })
            })

            put("outbounds", JSONArray().apply {
                put(outbound)
                put(JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    put("settings", JSONObject())
                })
                put(JSONObject().apply {
                    put("tag", "block")
                    put("protocol", "blackhole")
                    put("settings", JSONObject())
                })
            })

            // Simple routing — no .dat files, no geosite, no geoip
            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("rules", JSONArray().apply {
                    // Local/private IP ranges → direct (no proxy)
                    put(JSONObject().apply {
                        put("type", "field")
                        put("ip", JSONArray().apply {
                            put("10.0.0.0/8"); put("172.16.0.0/12")
                            put("192.168.0.0/16"); put("127.0.0.0/8")
                            put("::1/128"); put("fc00::/7"); put("fe80::/10")
                        })
                        put("outboundTag", "direct")
                    })
                    // Everything else → proxy
                    put(JSONObject().apply {
                        put("type", "field")
                        put("network", "tcp,udp")
                        put("outboundTag", "proxy")
                    })
                })
            })
        }.toString(2)
    }

    // ── VLESS outbound ────────────────────────────────────────────────────────

    private fun buildVlessOutbound(uri: String): JSONObject {
        val p = parseVlessUri(uri)
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", p.host)
                        put("port", p.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", p.uuid)
                                put("encryption", "none")
                                if (p.flow.isNotEmpty()) put("flow", p.flow)
                            })
                        })
                    })
                })
            })
            put("streamSettings", buildStreamSettings(p.type, p.security, p.sni, p.path, p.hostHeader))
        }
    }

    // ── Trojan outbound ───────────────────────────────────────────────────────

    private fun buildTrojanOutbound(uri: String): JSONObject {
        val raw = uri.removePrefix("trojan://")
        val atIdx = raw.indexOf('@')
        val password = raw.substring(0, atIdx)
        val rest = raw.substring(atIdx + 1)
        val qIdx = rest.indexOf('?')
        val hashIdx = rest.indexOf('#')
        val hostPort = if (qIdx != -1) rest.substring(0, qIdx)
                       else rest.substring(0, if (hashIdx != -1) hashIdx else rest.length)
        val lastColon = hostPort.lastIndexOf(':')
        val host = hostPort.substring(0, lastColon)
        val port = hostPort.substring(lastColon + 1).toIntOrNull() ?: 443

        val queryStr = if (qIdx != -1) {
            val end = if (hashIdx != -1 && hashIdx > qIdx) hashIdx else rest.length
            rest.substring(qIdx + 1, end)
        } else ""

        val params = parseQueryString(queryStr)
        val sni = params["sni"] ?: params["peer"] ?: host
        val wsHostHeader = params["host"]?.takeIf { it.isNotBlank() } ?: host
        // Preserve path exactly as given — do NOT force a leading slash
        val path = params["path"] ?: "/"

        VpnLogManager.info("Trojan → $host:$port (sni=$sni, path=$path, host-hdr=$wsHostHeader)")

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", host)
                        put("port", port)
                        put("password", password)
                    })
                })
            })
            put("streamSettings", buildStreamSettings(
                params["type"] ?: "tcp",
                if ((params["security"] ?: "tls").lowercase() == "none") "none" else "tls",
                sni, path, wsHostHeader
            ))
        }
    }

    // ── VMess outbound ────────────────────────────────────────────────────────

    private fun buildVmessOutbound(uri: String): JSONObject {
        val b64 = uri.removePrefix("vmess://")
        val json = try {
            val decoded = String(android.util.Base64.decode(sanitizeBase64(b64), android.util.Base64.NO_WRAP))
            JSONObject(decoded)
        } catch (e: Exception) {
            VpnLogManager.error("VMess parse error: ${e.message}")
            return buildFallbackOutbound()
        }
        val host    = json.optString("add", "")
        val port    = json.optInt("port", 443)
        val uuid    = json.optString("id", "")
        val net     = json.optString("net", "ws")
        val tls     = json.optString("tls", "none")
        val sni     = json.optString("sni", host)
        val path    = json.optString("path", "/")
        val hdr     = json.optString("host", host)

        VpnLogManager.info("VMess → $host:$port (net=$net, tls=$tls)")
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", host); put("port", port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", uuid); put("alterId", 0); put("security", "auto")
                            })
                        })
                    })
                })
            })
            put("streamSettings", buildStreamSettings(net, if (tls == "tls") "tls" else "none", sni, path, hdr))
        }
    }

    // ── Shadowsocks outbound ──────────────────────────────────────────────────

    private fun buildShadowsocksOutbound(uri: String): JSONObject {
        val raw = uri.removePrefix("ss://")
        val hashIdx = raw.indexOf('#')
        val main = if (hashIdx != -1) raw.substring(0, hashIdx) else raw
        val atIdx = main.lastIndexOf('@')
        val userInfoB64 = main.substring(0, atIdx)
        val hostPort = main.substring(atIdx + 1)

        val userInfo = String(android.util.Base64.decode(sanitizeBase64(userInfoB64), android.util.Base64.NO_WRAP))
        val colonIdx = userInfo.indexOf(':')
        val method   = userInfo.substring(0, colonIdx)
        val password = userInfo.substring(colonIdx + 1)
        val lastColon = hostPort.lastIndexOf(':')
        val host = hostPort.substring(0, lastColon)
        val port = hostPort.substring(lastColon + 1).toIntOrNull() ?: 8388

        VpnLogManager.info("Shadowsocks → $host:$port (method=$method)")
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", host); put("port", port)
                        put("method", method); put("password", password)
                    })
                })
            })
        }
    }

    // ── Stream settings builder ───────────────────────────────────────────────

    private fun buildStreamSettings(
        network: String, security: String,
        sni: String, path: String, host: String
    ): JSONObject = JSONObject().apply {
        put("network", network)
        put("security", security)

        when (security.lowercase()) {
            "tls" -> put("tlsSettings", JSONObject().apply {
                put("serverName", sni.ifEmpty { host })
                put("allowInsecure", false)
                put("fingerprint", "chrome")
            })
            "reality" -> put("realitySettings", JSONObject().apply {
                // publicKey and shortId must be embedded in the URI fragment or path
                // For REALITY links: path="pbk=<key>&sid=<shortId>&spx=<spiderX>"
                val realityParams = parseQueryString(path.removePrefix("?"))
                put("fingerprint", "chrome")
                put("publicKey", realityParams["pbk"] ?: "")
                put("shortId",   realityParams["sid"] ?: "")
                put("spiderX",   realityParams["spx"] ?: "/")
                put("serverName", sni.ifEmpty { host })
            })
        }

        when (network.lowercase()) {
            "ws" -> put("wsSettings", JSONObject().apply {
                put("path", path.ifEmpty { "/" })
                put("headers", JSONObject().apply { put("Host", host) })
            })
            "grpc" -> put("grpcSettings", JSONObject().apply {
                put("serviceName", path.ifEmpty { "" })
                put("multiMode", false)
            })
            "h2", "http" -> put("httpSettings", JSONObject().apply {
                put("path", path.ifEmpty { "/" })
                put("host", JSONArray().apply { put(host) })
            })
            // splithttp (xhttp) — Xray ≥ 1.8.16
            "splithttp", "xhttp" -> put("splithttpSettings", JSONObject().apply {
                put("path", path.ifEmpty { "/" })
                put("host", host)
            })
            "httpupgrade" -> put("httpupgradeSettings", JSONObject().apply {
                put("path", path.ifEmpty { "/" })
                put("host", host)
            })
        }
    }

    // ── Fallback outbound ─────────────────────────────────────────────────────

    private fun buildFallbackOutbound(): JSONObject = JSONObject().apply {
        put("tag", "proxy"); put("protocol", "freedom"); put("settings", JSONObject())
    }

    // ── VLESS URI parser ──────────────────────────────────────────────────────

    private data class VlessParams(
        val uuid: String, val host: String, val port: Int,
        val type: String = "ws", val security: String = "tls",
        val path: String = "/", val sni: String = "",
        val flow: String = "", val hostHeader: String = ""
    )

    private fun parseVlessUri(uri: String): VlessParams {
        val raw = uri.removePrefix("vless://")
        val atIdx = raw.indexOf('@')
        val uuid = raw.substring(0, atIdx)
        val afterAt = raw.substring(atIdx + 1)
        val qIdx = afterAt.indexOf('?')
        val hashIdx = afterAt.indexOf('#')
        val hostPort = if (qIdx != -1) afterAt.substring(0, qIdx)
                       else afterAt.substring(0, hashIdx.takeIf { it != -1 } ?: afterAt.length)
        val lastColon = hostPort.lastIndexOf(':')
        val host = hostPort.substring(0, lastColon)
        val port = hostPort.substring(lastColon + 1).toIntOrNull() ?: 443
        val queryStr = if (qIdx != -1) {
            val end = if (hashIdx != -1 && hashIdx > qIdx) hashIdx else afterAt.length
            afterAt.substring(qIdx + 1, end)
        } else ""
        val params = parseQueryString(queryStr)
        val resolvedHost = params["host"]?.ifBlank { host } ?: host

        VpnLogManager.info("VLESS → $host:$port (type=${params["type"]}, sni=${params["sni"]}, host=$resolvedHost)")
        return VlessParams(
            uuid = uuid, host = host, port = port,
            type = params["type"] ?: "ws",
            security = params["security"] ?: "tls",
            // Preserve path as-is
            path = params["path"] ?: "/",
            sni = params["sni"] ?: host,
            flow = params["flow"] ?: "",
            hostHeader = resolvedHost
        )
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /** Parse URL query string → key/value map with URL decoding. */
    private fun parseQueryString(query: String): Map<String, String> =
        if (query.isBlank()) emptyMap()
        else query.split("&").associate {
            val kv = it.split("=", limit = 2)
            kv[0] to if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
        }

    /**
     * Normalize URL-safe Base64 → standard Base64 and add padding.
     * Fixes "bad base-64" crashes from VLESS/VMess/SS URIs shared via apps.
     */
    private fun sanitizeBase64(s: String): String {
        val clean = s.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - clean.length % 4) % 4
        return if (pad > 0) clean + "=".repeat(pad) else clean
    }
}
