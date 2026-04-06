package com.vlessvpn.app

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray

data class VlessConfig(
    val serverAddress: String,
    val serverPort: Int,
    val uuid: String,
    val encryption: String,
    val flow: String,
    val network: String,
    val security: String,
    val sni: String,
    val fingerprint: String,
    val allowInsecure: Boolean,
    val publicKey: String,
    val shortId: String,
    val spiderX: String
) {
    companion object {
        fun load(context: Context): VlessConfig {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return VlessConfig(
                serverAddress = prefs.getString("server_address", "") ?: "",
                serverPort = prefs.getString("server_port", "443")?.toIntOrNull() ?: 443,
                uuid = prefs.getString("uuid", "") ?: "",
                encryption = prefs.getString("encryption", "none") ?: "none",
                flow = prefs.getString("flow", "") ?: "",
                network = prefs.getString("network", "tcp") ?: "tcp",
                security = prefs.getString("security", "tls") ?: "tls",
                sni = prefs.getString("sni", "") ?: "",
                fingerprint = prefs.getString("fingerprint", "chrome") ?: "chrome",
                allowInsecure = prefs.getBoolean("allow_insecure", false),
                publicKey = prefs.getString("public_key", "") ?: "",
                shortId = prefs.getString("short_id", "") ?: "",
                spiderX = prefs.getString("spider_x", "") ?: ""
            )
        }

        fun parseVlessLink(link: String): Map<String, String>? {
            if (!link.startsWith("vless://")) return null
            try {
                // vless://UUID@SERVER:PORT?params#name
                val withoutScheme = link.removePrefix("vless://")
                val uuid = withoutScheme.substringBefore("@")
                val afterAt = withoutScheme.substringAfter("@")
                val hostPort = afterAt.substringBefore("?").substringBefore("#")
                val server = hostPort.substringBefore(":")
                val port = hostPort.substringAfter(":").substringBefore("/").substringBefore("?").substringBefore("#")

                val queryString = if (afterAt.contains("?")) {
                    afterAt.substringAfter("?").substringBefore("#")
                } else ""

                val params = mutableMapOf<String, String>()
                params["uuid"] = uuid
                params["server_address"] = server
                params["server_port"] = port

                for (param in queryString.split("&")) {
                    if (param.isBlank()) continue
                    val key = param.substringBefore("=")
                    val value = Uri.decode(param.substringAfter("="))
                    when (key) {
                        "type" -> params["network"] = value
                        "encryption" -> params["encryption"] = value
                        "security" -> params["security"] = value
                        "pbk" -> params["public_key"] = value
                        "fp" -> params["fingerprint"] = value
                        "sni" -> params["sni"] = value
                        "sid" -> params["short_id"] = value
                        "spx" -> params["spider_x"] = value
                        "flow" -> params["flow"] = value
                    }
                }
                return params
            } catch (e: Exception) {
                return null
            }
        }

        fun saveFromLink(context: Context, link: String): Boolean {
            val params = parseVlessLink(link) ?: return false
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().apply {
                params.forEach { (key, value) -> putString(key, value) }
                apply()
            }
            return true
        }
    }

    /**
     * Generate Xray-compatible JSON configuration for VLESS
     */
    fun toXrayJson(): String {
        val root = JsonObject()

        // Log
        root.add("log", JsonObject().apply {
            addProperty("loglevel", "warning")
        })

        // DNS
        root.add("dns", JsonObject().apply {
            add("servers", JsonArray().apply {
                add("1.1.1.1")
                add("8.8.8.8")
            })
            addProperty("queryStrategy", "UseIP")
        })

        // Inbounds - SOCKS5 proxy
        val inbounds = JsonArray()
        val socksInbound = JsonObject().apply {
            addProperty("tag", "socks")
            addProperty("port", 10808)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
            })
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply {
                    add("http")
                    add("tls")
                    add("quic")
                })
                addProperty("routeOnly", true)
            })
        }
        inbounds.add(socksInbound)
        root.add("inbounds", inbounds)

        // Outbounds - VLESS
        val outbounds = JsonArray()
        val vlessOutbound = JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "vless")
            add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", serverAddress)
                        addProperty("port", serverPort)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", uuid)
                                addProperty("encryption", encryption)
                                if (flow.isNotBlank()) {
                                    addProperty("flow", flow)
                                }
                            })
                        })
                    })
                })
            })
            add("streamSettings", JsonObject().apply {
                addProperty("network", network)
                addProperty("security", security)
                if (security == "tls") {
                    add("tlsSettings", JsonObject().apply {
                        addProperty("allowInsecure", allowInsecure)
                        if (sni.isNotBlank()) {
                            addProperty("serverName", sni)
                        }
                        if (fingerprint.isNotBlank()) {
                            addProperty("fingerprint", fingerprint)
                        }
                    })
                } else if (security == "reality") {
                    add("realitySettings", JsonObject().apply {
                        if (sni.isNotBlank()) {
                            addProperty("serverName", sni)
                        }
                        if (fingerprint.isNotBlank()) {
                            addProperty("fingerprint", fingerprint)
                        }
                        if (publicKey.isNotBlank()) {
                            addProperty("publicKey", publicKey)
                        }
                        if (shortId.isNotBlank()) {
                            addProperty("shortId", shortId)
                        }
                        if (spiderX.isNotBlank()) {
                            addProperty("spiderX", spiderX)
                        }
                    })
                }
            })
        }
        outbounds.add(vlessOutbound)

        // Direct outbound
        outbounds.add(JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
        })

        // Block outbound
        outbounds.add(JsonObject().apply {
            addProperty("tag", "block")
            addProperty("protocol", "blackhole")
        })

        root.add("outbounds", outbounds)

        // Routing
        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", JsonArray().apply {
                // DNS queries go direct
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    addProperty("port", "53")
                })
                // Direct for private IPs
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    add("ip", JsonArray().apply {
                        add("geoip:private")
                    })
                })
                // Everything else through proxy
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    addProperty("port", "0-65535")
                })
            })
        })

        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }
}
