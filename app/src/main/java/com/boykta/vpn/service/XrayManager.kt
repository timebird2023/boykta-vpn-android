package com.boykta.vpn.service

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages Xray-core lifecycle.
 *
 * libXray.aar is downloaded during GitHub Actions build from:
 * https://github.com/2dust/v2rayNG/releases (libXray.aar includes tun2socks)
 *
 * The native library exposes:
 *   XrayCore.startXray(configJson: String): String  (returns error or "")
 *   XrayCore.stopXray()
 *   Tun2socksStartV2Ray(fd: Int, socks5Port: Int, dnsTTL: Int, enableIPv6: Boolean)
 *   Tun2socksStopV2Ray()
 */
object XrayManager {

    private const val TAG = "XrayManager"

    // Loaded from libXray.aar native library
    private external fun nativeStartXray(configJson: String): String
    private external fun nativeStopXray()
    private external fun nativeTun2SocksStart(tunFd: Int, socksPort: Int)
    private external fun nativeTun2SocksStop()

    private var xrayRunning = false
    private var tun2SocksRunning = false

    init {
        try {
            System.loadLibrary("xray")       // from libXray.aar
            System.loadLibrary("tun2socks")  // from libXray.aar
            Log.i(TAG, "Native libraries loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native libraries: ${e.message}")
        }
    }

    /**
     * Start Xray-core with VLESS configuration.
     * @param vlessUri Full VLESS URI (decrypted from API, never shown to user)
     * @param socksPort Local SOCKS5 port to listen on
     * @param httpPort  Local HTTP proxy port
     * @return true if started successfully
     */
    fun start(vlessUri: String, socksPort: Int, httpPort: Int): Boolean {
        return try {
            val config = buildXrayConfig(vlessUri, socksPort, httpPort)
            val error = nativeStartXray(config)
            if (error.isNotEmpty()) {
                Log.e(TAG, "Xray start error: $error")
                false
            } else {
                xrayRunning = true
                Log.i(TAG, "Xray-core started on socks:$socksPort")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting Xray", e)
            false
        }
    }

    /**
     * Start tun2socks: bridges TUN interface → SOCKS5 proxy
     * @param tunFd File descriptor of the TUN interface
     * @param socksPort SOCKS5 port where Xray is listening
     */
    fun startTun2Socks(tunFd: Int, socksPort: Int) {
        try {
            nativeTun2SocksStart(tunFd, socksPort)
            tun2SocksRunning = true
            Log.i(TAG, "tun2socks started: tunFd=$tunFd → socks:$socksPort")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting tun2socks", e)
        }
    }

    fun stop() {
        try {
            if (tun2SocksRunning) {
                nativeTun2SocksStop()
                tun2SocksRunning = false
            }
            if (xrayRunning) {
                nativeStopXray()
                xrayRunning = false
            }
            Log.i(TAG, "Xray-core stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray", e)
        }
    }

    /**
     * Build Xray-core JSON config from a VLESS URI.
     * VLESS URI format: vless://uuid@host:port?type=ws&security=tls&...#name
     */
    private fun buildXrayConfig(vlessUri: String, socksPort: Int, httpPort: Int): String {
        // Parse VLESS URI
        val vless = parseVlessUri(vlessUri)

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "warning")
            })
            put("inbounds", JSONArray().apply {
                // SOCKS5 inbound (used by tun2socks)
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
                        put("destOverride", JSONArray().apply {
                            put("http"); put("tls")
                        })
                    })
                })
                // HTTP inbound
                put(JSONObject().apply {
                    put("port", httpPort)
                    put("protocol", "http")
                    put("listen", "127.0.0.1")
                })
            })
            put("outbounds", JSONArray().apply {
                // VLESS WS outbound
                put(JSONObject().apply {
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", vless.host)
                                put("port", vless.port)
                                put("users", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("id", vless.uuid)
                                        put("encryption", "none")
                                        put("flow", vless.flow)
                                    })
                                })
                            })
                        })
                    })
                    put("streamSettings", JSONObject().apply {
                        put("network", vless.type) // "ws"
                        put("security", vless.security) // "tls"
                        put("tlsSettings", JSONObject().apply {
                            put("serverName", vless.sni)
                            put("allowInsecure", false)
                        })
                        put("wsSettings", JSONObject().apply {
                            put("path", vless.path)
                            put("headers", JSONObject().apply {
                                put("Host", vless.host)
                            })
                        })
                    })
                    put("mux", JSONObject().apply {
                        put("enabled", false)
                    })
                })
                // Direct (bypass local traffic)
                put(JSONObject().apply {
                    put("protocol", "freedom")
                    put("tag", "direct")
                })
                // Block
                put(JSONObject().apply {
                    put("protocol", "blackhole")
                    put("tag", "block")
                })
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray().apply {
                    // Route local addresses directly
                    put(JSONObject().apply {
                        put("type", "field")
                        put("ip", JSONArray().apply {
                            put("geoip:private")
                        })
                        put("outboundTag", "direct")
                    })
                })
            })
            put("policy", JSONObject().apply {
                put("levels", JSONObject().apply {
                    put("0", JSONObject().apply {
                        put("handshake", 4)
                        put("connIdle", 300)
                        put("uplinkOnly", 1)
                        put("downlinkOnly", 1)
                        put("statsUserUplink", false)
                        put("statsUserDownlink", false)
                    })
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
        val hostPort = if (qIdx != -1) afterAt.substring(0, qIdx) else afterAt.substring(0, hashIdx.takeIf { it != -1 } ?: afterAt.length)
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
            uuid = uuid,
            host = host,
            port = port,
            type = params["type"] ?: "ws",
            security = params["security"] ?: "tls",
            path = params["path"] ?: "/",
            sni = params["sni"] ?: host,
            flow = params["flow"] ?: "",
        )
    }
}
