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
 * API: LibXray.invoke(requestJSON) → responseJSON
 *   { "apiVersion": 1, "method": "<camelCaseMethod>", "payload": { ... } }
 */
object XrayManager {

    private const val TAG = "XrayManager"
    private const val API_VERSION = 1

    private const val METHOD_RUN_FROM_JSON = "runXrayFromJson"
    private const val METHOD_STOP          = "stopXray"
    private const val METHOD_GET_STATE     = "getXrayState"
    private const val METHOD_VERSION       = "xrayVersion"

    private var xrayRunning = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Start Xray-core with a proxy URI (VLESS / Trojan / VMess / SS).
     */
    fun start(proxyUri: String, socksPort: Int, httpPort: Int): Boolean {
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
                VpnLogManager.success("Xray-core v$ver started — SOCKS5 127.0.0.1:$socksPort, HTTP :$httpPort")
                Log.i(TAG, "Xray started")
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

    fun stop() {
        try {
            if (xrayRunning) {
                LibXray.invoke(JSONObject().apply {
                    put("apiVersion", API_VERSION)
                    put("method", METHOD_STOP)
                }.toString())
                xrayRunning = false
                VpnLogManager.sys("Xray-core stopped")
                Log.i(TAG, "Xray stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray", e)
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

    private fun buildXrayConfig(proxyUri: String, socksPort: Int, httpPort: Int): String {
        val outbound = when {
            proxyUri.startsWith("vless://")  -> buildVlessOutbound(proxyUri)
            proxyUri.startsWith("trojan://") -> buildTrojanOutbound(proxyUri)
            proxyUri.startsWith("vmess://")  -> buildVmessOutbound(proxyUri)
            proxyUri.startsWith("ss://")     -> buildShadowsocksOutbound(proxyUri)
            else                             -> buildVlessOutbound(proxyUri)
        }

        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("port", socksPort)
                    put("protocol", "socks")
                    put("listen", "127.0.0.1")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().apply { put("http"); put("tls") })
                    })
                })
                put(JSONObject().apply {
                    put("port", httpPort)
                    put("protocol", "http")
                    put("listen", "127.0.0.1")
                })
            })
            put("outbounds", JSONArray().apply {
                put(outbound)
                put(JSONObject().apply {
                    put("protocol", "freedom")
                    put("tag", "direct")
                    put("settings", JSONObject().apply { put("domainStrategy", "UseIPv4") })
                })
                put(JSONObject().apply {
                    put("protocol", "blackhole")
                    put("tag", "block")
                })
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray().apply {
                    // Block ads
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "block")
                        put("domain", JSONArray().apply { put("geosite:category-ads-all") })
                    })
                    // Proxy all other
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "proxy")
                        put("network", "tcp,udp")
                    })
                })
            })
        }.toString()
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
            put("streamSettings", buildStreamSettings(p.type, p.security, p.sni, p.path, p.host))
        }
    }

    // ── Trojan outbound ───────────────────────────────────────────────────────

    private fun buildTrojanOutbound(uri: String): JSONObject {
        // trojan://password@host:port?sni=...
        val withoutScheme = uri.removePrefix("trojan://")
        val atIdx = withoutScheme.indexOf('@')
        val password = withoutScheme.substring(0, atIdx)
        val rest = withoutScheme.substring(atIdx + 1)
        val hashIdx = rest.indexOf('#')
        val qIdx = rest.indexOf('?')
        val hostPart = if (qIdx != -1) rest.substring(0, qIdx)
                       else if (hashIdx != -1) rest.substring(0, hashIdx)
                       else rest
        val lastColon = hostPart.lastIndexOf(':')
        val host = hostPart.substring(0, lastColon)
        val port = hostPart.substring(lastColon + 1).toIntOrNull() ?: 443
        val query = if (qIdx != -1) {
            val end = if (hashIdx != -1 && hashIdx > qIdx) hashIdx else rest.length
            rest.substring(qIdx + 1, end)
        } else ""
        val params = query.split("&").associate {
            val kv = it.split("=")
            kv[0] to if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
        }
        val sni = params["sni"] ?: host
        val network = params["type"] ?: "tcp"

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
            put("streamSettings", buildStreamSettings(network, "tls", sni, "/", host))
        }
    }

    // ── VMess outbound ────────────────────────────────────────────────────────

    private fun buildVmessOutbound(uri: String): JSONObject {
        val encoded = uri.removePrefix("vmess://")
        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        val obj = JSONObject(decoded)
        val host = obj.optString("add")
        val port = obj.optString("port", "443").toIntOrNull() ?: 443
        val uuid = obj.optString("id")
        val network = obj.optString("net", "ws")
        val path = obj.optString("path", "/")
        val hostHeader = obj.optString("host", host)
        val tls = obj.optString("tls", "")
        val sni = obj.optString("sni", host)

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
        // ss://base64(method:password)@host:port#name  (SIP002)
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

    // ── Stream settings helper ────────────────────────────────────────────────

    private fun buildStreamSettings(
        network: String, security: String, sni: String, path: String, host: String
    ): JSONObject = JSONObject().apply {
        put("network", network)
        put("security", security)
        if (security == "tls" || security == "reality") {
            put("tlsSettings", JSONObject().apply {
                put("serverName", sni.ifEmpty { host })
                put("allowInsecure", false)
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
    }

    // ── VLESS URI parser ──────────────────────────────────────────────────────

    private data class VlessParams(
        val uuid: String, val host: String, val port: Int,
        val type: String = "ws", val security: String = "tls",
        val path: String = "/", val sni: String = "", val flow: String = "",
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
        VpnLogManager.info("VLESS → $host:$port (type=${params["type"]}, sni=${params["sni"]})")
        return VlessParams(
            uuid = uuid, host = host, port = port,
            type = params["type"] ?: "ws", security = params["security"] ?: "tls",
            path = params["path"] ?: "/", sni = params["sni"] ?: host,
            flow = params["flow"] ?: "",
        )
    }
}
