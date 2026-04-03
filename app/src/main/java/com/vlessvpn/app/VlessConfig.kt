package com.vlessvpn.app

import android.content.Context
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
    val allowInsecure: Boolean
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
                allowInsecure = prefs.getBoolean("allow_insecure", false)
            )
        }
    }

    /**
     * Generate Xray-compatible JSON configuration for VLESS
     */
    fun toXrayJson(): String {
        val root = JsonObject()

        // Inbounds - local SOCKS5 proxy
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
                })
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
            addProperty("domainStrategy", "IPIfNonMatch")
            add("rules", JsonArray().apply {
                // Block private IPs from going through proxy
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    add("ip", JsonArray().apply {
                        add("geoip:private")
                    })
                })
            })
        })

        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }
}
