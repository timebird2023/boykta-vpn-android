package com.boykta.vpn.service

import android.util.Log
import libXray.LibXray
import org.json.JSONObject

/**
 * Manages Xray-core lifecycle via the XTLS/libXray AAR (v26.7.x+).
 *
 * The AAR ships a single native library (libgojni.so) compiled with Go mobile.
 * It is loaded automatically by the Go mobile runtime — no System.loadLibrary()
 * call is needed.
 *
 * API: LibXray.invoke(requestJSON) → responseJSON
 *
 * Request format:
 *   { "apiVersion": 1, "method": "<camelCaseMethod>", "payload": { ... } }
 *
 * Response format:
 *   { "success": true/false, "data": { ... }, "error": "..." }
 *
 * Method strings are defined in XTLS/libXray invoke_model.go:
 *   runXrayFromJson, stopXray, getXrayState, xrayVersion, ping, …
 *
 * Note: tun2socks is NOT bundled in the XTLS libXray.aar.
 * The TUN → SOCKS5 packet bridge must be provided by a separate library
 * (e.g. hiddify/tun2socks or xjasonlyu/tun2socks).
 * Until one is integrated, startTun2Socks() is a no-op — the SOCKS5/HTTP
 * inbound ports still work for apps that support a proxy setting directly.
 */
object XrayManager {

    private const val TAG = "XrayManager"
    private const val API_VERSION = 1

    // Method name constants — must match invoke_model.go exactly
    private const val METHOD_RUN_FROM_JSON = "runXrayFromJson"
    private const val METHOD_STOP          = "stopXray"
    private const val METHOD_GET_STATE     = "getXrayState"
    private const val METHOD_VERSION       = "xrayVersion"

    private var xrayRunning = false

    // ── Xray-core lifecycle ──────────────────────────────────────────────────

    /**
     * Start Xray-core with VLESS configuration.
     * @param vlessUri Full VLESS URI (decrypted from API, never shown to user)
     * @param socksPort Local SOCKS5 port to listen on
     * @param httpPort  Local HTTP proxy port
     * @return true if started successfully
     */
    fun start(vlessUri: String, socksPort: Int, httpPort: Int): Boolean {
        return try {
            val configJson = buildXrayConfig(vlessUri, socksPort, httpPort)

            val requestJson = JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_RUN_FROM_JSON)
                put("payload", JSONObject().apply {
                    put("configJSON", configJson)
                })
            }.toString()

            val responseJson = LibXray.invoke(requestJson)
            val response = JSONObject(responseJson)

            if (response.optBoolean("success", false)) {
                xrayRunning = true
                Log.i(TAG, "Xray-core started on socks:$socksPort http:$httpPort")
                true
            } else {
                val error = response.optString("error", "unknown error")
                Log.e(TAG, "Xray start error: $error")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting Xray", e)
            false
        }
    }

    /**
     * Start tun2socks bridging: routes TUN interface traffic → SOCKS5 proxy.
     *
     * tun2socks is NOT bundled in libXray.aar. This is a no-op stub.
     * To enable full VPN traffic routing, add a standalone tun2socks AAR
     * (e.g. from hiddify/tun2socks) and replace this stub with the real call.
     *
     * @param tunFd    File descriptor of the TUN interface (from VpnService.Builder.establish())
     * @param socksPort SOCKS5 port where Xray is listening
     */
    fun startTun2Socks(tunFd: Int, socksPort: Int) {
        Log.w(
            TAG,
            "tun2socks not bundled in libXray.aar — TUN→SOCKS routing is inactive. " +
            "Add a tun2socks AAR (e.g. hiddify/tun2socks) and wire it here to enable " +
            "full device-wide traffic routing."
        )
    }

    fun stop() {
        try {
            if (xrayRunning) {
                val requestJson = JSONObject().apply {
                    put("apiVersion", API_VERSION)
                    put("method", METHOD_STOP)
                }.toString()
                LibXray.invoke(requestJson)
                xrayRunning = false
                Log.i(TAG, "Xray-core stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray", e)
        }
    }

    /** @return true if Xray-core reports itself as running */
    fun isRunning(): Boolean {
        return try {
            val requestJson = JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_GET_STATE)
            }.toString()
            val responseJson = LibXray.invoke(requestJson)
            val response = JSONObject(responseJson)
            response.optBoolean("success") &&
                response.optJSONObject("data")?.optBoolean("running", false) == true
        } catch (e: Exception) {
            xrayRunning
        }
    }

    /** @return Xray-core version string, or null on error */
    fun version(): String? {
        return try {
            val requestJson = JSONObject().apply {
                put("apiVersion", API_VERSION)
                put("method", METHOD_VERSION)
            }.toString()
            val responseJson = LibXray.invoke(requestJson)
            val response = JSONObject(responseJson)
            response.optJSONObject("data")?.optString("version")
        } catch (e: Exception) {
            null
        }
    }

    // ── Config builder ───────────────────────────────────────────────────────

    /**
     * Build an Xray-core JSON config from a VLESS URI.
     * VLESS URI format: vless://uuid@host:port?type=ws&security=tls&...#name
     */
    private fun buildXrayConfig(vlessUri: String, socksPort: Int, httpPort: Int): String {
        val vless = parseVlessUri(vlessUri)

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "warning")
            })
            put("inbounds", org.json.JSONArray().apply {
                // SOCKS5 inbound (used by tun2socks or proxy-aware apps)
                put(JSONObject().apply {
                    put("port", socksPort)
                    put("protocol", "socks")
                    put("listen", "127.0.0.1")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                })
                // HTTP inbound
                put(JSONObject().apply {
                    put("port", httpPort)
                    put("protocol", "http")
                    put("listen", "127.0.0.1")
                })
            })
            put("outbounds", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        put("vnext", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", vless.host)
                                put("port", vless.port)
                                put("users", org.json.JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("id", vless.uuid)
                                        put("encryption", "none")
                                        if (vless.flow.isNotEmpty()) put("flow", vless.flow)
                                    })
                                })
                            })
                        })
                    })
                    put("streamSettings", JSONObject().apply {
                        put("network", vless.type)
                        put("security", vless.security)
                        if (vless.security == "tls" || vless.security == "reality") {
                            put("tlsSettings", JSONObject().apply {
                                put("serverName", vless.sni)
                                put("allowInsecure", false)
                            })
                        }
                        if (vless.type == "ws") {
                            put("wsSettings", JSONObject().apply {
                                put("path", vless.path)
                                put("headers", JSONObject().apply {
                                    put("Host", vless.sni.ifEmpty { vless.host })
                                })
                            })
                        }
                    })
                    put("tag", "proxy")
                })
                // Direct outbound (for bypass rules)
                put(JSONObject().apply {
                    put("protocol", "freedom")
                    put("tag", "direct")
                })
            })
        }.toString()
    }

    private data class VlessParams(
        val uuid: String,
        val host: String,
        val port: Int,
        val type: String = "ws",
        val security: String = "tls",
        val path: String = "/",
        val sni: String = "",
        val flow: String = "",
    )

    private fun parseVlessUri(uri: String): VlessParams {
        // vless://uuid@host:port?type=ws&security=tls&path=%2F&sni=host&...#name
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
            kv[0] to (if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else "")
        }

        return VlessParams(
            uuid     = uuid,
            host     = host,
            port     = port,
            type     = params["type"]     ?: "ws",
            security = params["security"] ?: "tls",
            path     = params["path"]     ?: "/",
            sni      = params["sni"]      ?: host,
            flow     = params["flow"]     ?: "",
        )
    }
}
