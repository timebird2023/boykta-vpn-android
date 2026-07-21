package com.boykta.vpn.service

import android.util.Log
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages Xray-core lifecycle via the XTLS/libXray AAR.
 *
 * Supports VLESS, Trojan, VMess, and Shadowsocks outbound configurations.
 * All events are also forwarded to VpnLogManager for the in-app log terminal.
 *
 * NOTE: All geosite/geoip routing rules have been REMOVED to avoid the
 * "stat /system/bin/geosite.dat: no such file or directory" crash.
 * Traffic routes directly through the proxy with no domain/IP classification.
 *
 * FIX: forceStop() is always called before start() to prevent
 * "xray is already running" errors from stale instances.
 */
object XrayManager {

    private const val TAG = "XrayManager"
    private const val API_VERSION = 1

    private const val METHOD_RUN_FROM_JSON = "runXrayFromJson"
    private const val METHOD_STOP          = "stopXray"
    private const val METHOD_GET_STATE     = "getXrayState"
    private const val METHOD_VERSION       = "xrayVersion"

    @Volatile private var xrayRunning = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Start Xray-core with a proxy URI (VLESS / Trojan / VMess / SS).
     * CRITICAL: Always calls forceStop() first to clean up any stale instance.
     */
    fun start(proxyUri: String, socksPort: Int, httpPort: Int): Boolean {
        // Always stop first — prevents "xray is already running" crash
        forceStop()

        return try {
            val configJson = buildXrayConfig(proxyUri, socksPort, httpPort)
            VpnLogManager.sys("Building Xray config for port $socksPort…")

            val requestJson = JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_RUN_FROM_JSON)
                put("payload", JSONObject().apply { put("configJSON", configJson) })
            }.toString()

            val responseJson = LibXray.invoke(requestJson)
            val response = JSONObject(responseJson)

            if (response.optBoolean("success", false)) {
                xrayRunning = true
                val ver = version() ?: "unknown"
                VpnLogManager.success("Xray-core v$ver started — SOCKS5 127.0.0.1:$socksPort | HTTP :$httpPort")
                Log.i(TAG, "Xray started successfully")
                true
            } else {
                val err = response.optString("error", "unknown error")
                VpnLogManager.error("Xray start failed: $err")
                Log.e(TAG, "Xray start error: $err")
                false
            }
        } catch (e: Exception) {
            VpnLogManager.error("Xray exception: ${e.message}")
            Log.e(TAG, "Exception starting Xray", e)
            false
        }
    }

    /**
     * Graceful stop — respects internal running flag.
     */
    fun stop() {
        if (xrayRunning) {
            forceStop()
        }
    }

    /**
     * Force-stop regardless of internal state flag.
     * Sends the stop command to libXray unconditionally.
     * Used before every start() to prevent stale-instance crashes.
     */
    fun forceStop() {
        try {
            LibXray.invoke(JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_STOP)
            }.toString())
            VpnLogManager.sys("Xray-core stopped")
            Log.i(TAG, "Xray force-stopped")
        } catch (e: Exception) {
            Log.w(TAG, "forceStop exception (safe to ignore): ${e.message}")
        } finally {
            xrayRunning = false
        }
    }

    fun isRunning(): Boolean {
        return try {
            val resp = JSONObject(LibXray.invoke(JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_GET_STATE)
            }.toString()))
            resp.optBoolean("success") &&
                resp.optJSONObject("data")?.optBoolean("running", false) == true
        } catch (e: Exception) { xrayRunning }
    }

    fun version(): String? {
        return try {
            val resp = JSONObject(LibXray.invoke(JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_VERSION)
            }.toString()))
            resp.optJSONObject("data")?.optString("version")
        } catch (e: Exception) { null }
    }

    // ── Config builder ─────────────────────────────────────────────────────────

    /**
     * Builds a lightweight Xray JSON config WITHOUT any geosite/geoip rules.
     * All traffic routes through the proxy — no external .dat files required.
     * TUN: MTU 1500, DNS 8.8.8.8 / 1.1.1.1, route 0.0.0.0/0.
     */
    private fun buildXrayConfig(proxyUri: String, socksPort: Int, httpPort: Int): String {
        val outbound = when {
            proxyUri.startsWith("vless://")  -> buildVlessOutbound(proxyUri)
            proxyUri.startsWith("trojan://") -> buildTrojanOutbound(proxyUri)
            proxyUri.startsWith("vmess://")  -> buildVmessOutbound(proxyUri)
            proxyUri.startsWith("ss://")     -> buildShadowsocksOutbound(proxyUri)
            else                             -> buildVlessOutbound(proxyUri)
        }

        return JSONObject().apply {
            // Log level: warning (no debug noise)
            put("log", JSONObject().apply { put("loglevel", "warning") })

            // DNS — simple and reliable, no geosite lookup
            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put("8.8.8.8")
                    put("1.1.1.1")
                    put("localhost")
                })
            })

            // Inbounds: SOCKS5 + HTTP proxy on localhost
            put("inbounds", JSONArray().apply {
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
                put(JSONObject().apply {
                    put("port", httpPort)
                    put("protocol", "http")
                    put("listen", "127.0.0.1")
                    put("settings", JSONObject().apply { put("allowTransparent", false) })
                })
            })

            // Outbounds: proxy + direct (NO freedom-geoip, NO geosite rules)
            put("outbounds", JSONArray().apply {
                put(outbound)                    // index 0 = proxy (all traffic)
                put(JSONObject().apply {         // index 1 = direct (for local bypass)
                    put("tag", "direct")
                    put("protocol", "freedom")
                    put("settings", JSONObject())
                })
                put(JSONObject().apply {         // index 2 = block
                    put("tag", "block")
                    put("protocol", "blackhole")
                    put("settings", JSONObject())
                })
            })

            // Routing: simple rules — NO geosite, NO geoip, NO .dat files
            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")   // "AsIs" avoids any DNS-based lookups
                put("rules", JSONArray().apply {
                    // Block local/private addresses from going through proxy
                    put(JSONObject().apply {
                        put("type", "field")
                        put("ip", JSONArray().apply {
                            put("10.0.0.0/8")
                            put("172.16.0.0/12")
                            put("192.168.0.0/16")
                            put("127.0.0.0/8")
                            put("::1/128")
                            put("fc00::/7")
                            put("fe80::/10")
                        })
                        put("outboundTag", "direct")
                    })
                    // Everything else goes through proxy
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
        val withoutScheme = uri.removePrefix("trojan://")
        val atIdx = withoutScheme.indexOf('@')
        val password = withoutScheme.substring(0, atIdx)
        val rest = withoutScheme.substring(atIdx + 1)
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
        val params = queryStr.split("&").associate {
            val kv = it.split("=")
            kv[0] to if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
        }
        val sni = params["sni"] ?: params["peer"] ?: host

        VpnLogManager.info("Trojan → $host:$port (sni=$sni)")
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
            // Support explicit host-header override via params["host"] (important for WS CDN fronting)
        val wsHostHeader = params["host"]?.takeIf { it.isNotBlank() } ?: host
        put("streamSettings", buildStreamSettings(
                params["type"] ?: "tcp",
                if (params["security"] == "none") "none" else "tls",
                sni,
                params["path"] ?: "/",
                wsHostHeader
            ))
        }
    }

    // ── VMess outbound ────────────────────────────────────────────────────────

    private fun buildVmessOutbound(uri: String): JSONObject {
        val base64 = uri.removePrefix("vmess://")
        val json = try {
            val decoded = String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
            JSONObject(decoded)
        } catch (e: Exception) {
            VpnLogManager.error("VMess parse error: ${e.message}")
            return buildFallbackOutbound()
        }
        val host = json.optString("add", "")
        val port = json.optInt("port", 443)
        val uuid = json.optString("id", "")
        val network = json.optString("net", "ws")
        val tls = json.optString("tls", "none")
        val sni = json.optString("sni", host)
        val path = json.optString("path", "/")
        val hostHeader = json.optString("host", host)

        VpnLogManager.info("VMess → $host:$port (net=$network)")
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", host)
                        put("port", port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", uuid)
                                put("alterId", 0)
                                put("security", "auto")
                            })
                        })
                    })
                })
            })
            put("streamSettings", buildStreamSettings(network, if (tls == "tls") "tls" else "none", sni, path, hostHeader))
        }
    }

    // ── Shadowsocks outbound ──────────────────────────────────────────────────

    private fun buildShadowsocksOutbound(uri: String): JSONObject {
        val withoutScheme = uri.removePrefix("ss://")
        val hashIdx = withoutScheme.indexOf('#')
        val main = if (hashIdx != -1) withoutScheme.substring(0, hashIdx) else withoutScheme
        val atIdx = main.lastIndexOf('@')
        val userInfoB64 = main.substring(0, atIdx)
        val hostPort = main.substring(atIdx + 1)
        val userInfo = String(android.util.Base64.decode(userInfoB64, android.util.Base64.DEFAULT))
        val colonIdx = userInfo.indexOf(':')
        val method = userInfo.substring(0, colonIdx)
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
                        put("address", host)
                        put("port", port)
                        put("method", method)
                        put("password", password)
                    })
                })
            })
        }
    }

    // ── Fallback outbound (should never happen) ───────────────────────────────

    private fun buildFallbackOutbound(): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", "freedom")
        put("settings", JSONObject())
    }

    // ── Stream settings builder ───────────────────────────────────────────────

    private fun buildStreamSettings(
        network: String,
        security: String,
        sni: String,
        path: String,
        host: String
    ): JSONObject = JSONObject().apply {
        put("network", network)
        put("security", security)
        if (security == "tls") {
            put("tlsSettings", JSONObject().apply {
                put("serverName", sni.ifEmpty { host })
                put("allowInsecure", false)
                put("fingerprint", "chrome")
            })
        }
        if (network == "ws") {
            put("wsSettings", JSONObject().apply {
                put("path", path.ifEmpty { "/" })
                put("headers", JSONObject().apply { put("Host", host) })
            })
        }
        if (network == "grpc") {
            put("grpcSettings", JSONObject().apply { put("serviceName", path.ifEmpty { "" }) })
        }
        if (network == "h2") {
            put("httpSettings", JSONObject().apply {
                put("path", path.ifEmpty { "/" })
                put("host", JSONArray().apply { put(host) })
            })
        }
    }

    // ── VLESS URI parser ──────────────────────────────────────────────────────

    private data class VlessParams(
        val uuid: String, val host: String, val port: Int,
        val type: String = "ws", val security: String = "tls",
        val path: String = "/", val sni: String = "", val flow: String = "",
        val hostHeader: String = "",
    )

    private fun parseVlessUri(uri: String): VlessParams {
        val withoutScheme = uri.removePrefix("vless://")
        val atIdx = withoutScheme.indexOf('@')
        val uuid = withoutScheme.substring(0, atIdx)
        val afterAt = withoutScheme.substring(atIdx + 1)
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
        val params = queryStr.split("&").associate {
            val kv = it.split("=")
            kv[0] to if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
        }
        val resolvedHost = params["host"]?.ifBlank { host } ?: host
        VpnLogManager.info("VLESS → $host:$port (type=${params["type"]}, sni=${params["sni"]}, host=$resolvedHost)")
        return VlessParams(
            uuid = uuid, host = host, port = port,
            type = params["type"] ?: "ws", security = params["security"] ?: "tls",
            path = params["path"] ?: "/", sni = params["sni"] ?: host,
            flow = params["flow"] ?: "",
            hostHeader = resolvedHost,
        )
    }
}
