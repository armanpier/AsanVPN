package com.asanvpn.app.service.v2ray

import com.asanvpn.app.data.models.VpnNode
import com.google.gson.JsonArray
import com.google.gson.JsonObject

object V2RayConfigBuilder {

    fun buildConfig(node: VpnNode): String {
        val root = JsonObject()

        // 1. Stats & Policy
        val stats = JsonObject()
        root.add("stats", stats)

        val log = JsonObject().apply {
            addProperty("loglevel", "warning")
        }
        root.add("log", log)

        val policy = JsonObject().apply {
            val levels = JsonObject().apply {
                val level8 = JsonObject().apply {
                    addProperty("handshake", 4)
                    addProperty("connIdle", 300)
                    addProperty("uplinkOnly", 1)
                    addProperty("downlinkOnly", 1)
                }
                add("8", level8)
            }
            add("levels", levels)
            val system = JsonObject().apply {
                addProperty("statsOutboundUplink", true)
                addProperty("statsOutboundDownlink", true)
            }
            add("system", system)
        }
        root.add("policy", policy)

        // 2. Inbounds: tun + socks-in + http-in
        val inbounds = JsonArray().apply {
            // TUN Inbound (bound to xray.tun.fd in libv2ray)
            add(JsonObject().apply {
                addProperty("tag", "tun")
                addProperty("protocol", "tun")
                add("settings", JsonObject().apply {
                    addProperty("name", "xray0")
                    addProperty("MTU", 1500)
                    addProperty("userLevel", 8)
                })
                add("sniffing", JsonObject().apply {
                    addProperty("enabled", true)
                    val destOverride = JsonArray().apply {
                        add("http")
                        add("tls")
                        add("quic")
                    }
                    add("destOverride", destOverride)
                })
            })

            // Local Socks5 Inbound for local app/browser fallback
            add(JsonObject().apply {
                addProperty("tag", "socks-in")
                addProperty("port", 10808)
                addProperty("listen", "127.0.0.1")
                addProperty("protocol", "socks")
                add("settings", JsonObject().apply {
                    addProperty("auth", "noauth")
                    addProperty("udp", true)
                    addProperty("userLevel", 8)
                })
                add("sniffing", JsonObject().apply {
                    addProperty("enabled", true)
                    val destOverride = JsonArray().apply {
                        add("http")
                        add("tls")
                        add("quic")
                    }
                    add("destOverride", destOverride)
                })
            })

            // Local HTTP Inbound
            add(JsonObject().apply {
                addProperty("tag", "http-in")
                addProperty("port", 10809)
                addProperty("listen", "127.0.0.1")
                addProperty("protocol", "http")
                add("settings", JsonObject().apply {
                    addProperty("auth", "noauth")
                })
            })
        }
        root.add("inbounds", inbounds)

        // 3. Outbounds: proxy + dns-out + direct + block
        val outbounds = JsonArray()
        val proxyOutbound = buildProxyOutbound(node)
        outbounds.add(proxyOutbound)

        // DNS outbound
        outbounds.add(JsonObject().apply {
            addProperty("tag", "dns-out")
            addProperty("protocol", "dns")
        })

        // Direct outbound
        outbounds.add(JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
            add("settings", JsonObject().apply {
                addProperty("domainStrategy", "UseIP")
            })
            add("streamSettings", JsonObject().apply {
                add("sockopt", JsonObject().apply {
                    addProperty("domainStrategy", "UseIP")
                })
            })
        })

        // Block outbound
        outbounds.add(JsonObject().apply {
            addProperty("tag", "block")
            addProperty("protocol", "blackhole")
            add("settings", JsonObject().apply {
                add("response", JsonObject().apply {
                    addProperty("type", "http")
                })
            })
        })
        root.add("outbounds", outbounds)

        // 4. DNS: Fast standard UDP resolvers
        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                add("1.1.1.1")
                add("8.8.8.8")
            }
            add("servers", servers)
        }
        root.add("dns", dns)

        // 5. Routing: Iran split tunneling + general proxy
        val routing = JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
            val rules = JsonArray().apply {
                // Rule 0: Hijack port 53 DNS queries to internal DNS engine
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "dns-out")
                    addProperty("port", "53")
                })

                // Rule 1: Direct Iranian domains (Chocolate4Iran geosite rules)
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val domainArr = JsonArray().apply {
                        add("geosite:category-ir")
                        add("geosite:ir")
                        add("domain:ir")
                    }
                    add("domain", domainArr)
                })

                // Rule 2: Direct private LAN & Iran IP ranges (Chocolate4Iran geoip rules)
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val ipArr = JsonArray().apply {
                        add("geoip:private")
                        add("geoip:ir")
                    }
                    add("ip", ipArr)
                })

                // Rule 3: All other TCP & UDP -> proxy
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    addProperty("network", "tcp,udp")
                })
            }
            add("rules", rules)
        }
        root.add("routing", routing)

        return root.toString()
    }

    /**
     * Builds lightweight config without TUN for testing outbound delay with Libv2ray.measureOutboundDelay
     */
    fun buildTestConfig(node: VpnNode): String {
        val root = JsonObject()

        val log = JsonObject().apply {
            addProperty("loglevel", "none")
        }
        root.add("log", log)

        val outbounds = JsonArray()
        outbounds.add(buildProxyOutbound(node))
        outbounds.add(JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
        })
        root.add("outbounds", outbounds)

        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                add("1.1.1.1")
                add("8.8.8.8")
            }
            add("servers", servers)
        }
        root.add("dns", dns)

        return root.toString()
    }

    private fun buildProxyOutbound(node: VpnNode): JsonObject {
        val outbound = JsonObject()
        outbound.addProperty("tag", "proxy")
        outbound.addProperty("protocol", node.protocol.lowercase())

        val settings = JsonObject()
        val streamSettings = JsonObject()
        val sec = if (!node.pbk.isNullOrBlank()) "reality" else (node.security?.lowercase() ?: "none")
        streamSettings.addProperty("network", if (node.type.isNullOrEmpty()) "tcp" else node.type.lowercase())
        streamSettings.addProperty("security", sec)

        when (node.protocol.lowercase()) {
            "vless" -> {
                val vnext = JsonArray()
                val serverObj = JsonObject()
                serverObj.addProperty("address", node.server)
                serverObj.addProperty("port", node.port)

                val users = JsonArray()
                val user = JsonObject()
                user.addProperty("id", node.uuid ?: "")
                user.addProperty("encryption", "none")
                val isVisionCompatible = (sec == "tls" || sec == "reality") && (node.type.isNullOrBlank() || node.type.lowercase() == "tcp")
                if (!node.flow.isNullOrBlank() && isVisionCompatible) {
                    user.addProperty("flow", node.flow)
                }
                users.add(user)
                serverObj.add("users", users)
                vnext.add(serverObj)
                settings.add("vnext", vnext)
            }
            "vmess" -> {
                val vnext = JsonArray()
                val serverObj = JsonObject()
                serverObj.addProperty("address", node.server)
                serverObj.addProperty("port", node.port)

                val users = JsonArray()
                val user = JsonObject()
                user.addProperty("id", node.uuid ?: "")
                user.addProperty("alterId", 0)
                user.addProperty("security", "auto")
                users.add(user)
                serverObj.add("users", users)
                vnext.add(serverObj)
                settings.add("vnext", vnext)
            }
            "trojan" -> {
                val servers = JsonArray()
                val serverObj = JsonObject()
                serverObj.addProperty("address", node.server)
                serverObj.addProperty("port", node.port)
                serverObj.addProperty("password", node.password ?: "")
                servers.add(serverObj)
                settings.add("servers", servers)
            }
            "shadowsocks" -> {
                val servers = JsonArray()
                val serverObj = JsonObject()
                serverObj.addProperty("address", node.server)
                serverObj.addProperty("port", node.port)
                serverObj.addProperty("method", node.cipher ?: "chacha20-ietf-poly1305")
                serverObj.addProperty("password", node.password ?: "")
                servers.add(serverObj)
                settings.add("servers", servers)
            }
        }
        outbound.add("settings", settings)

        // Security: TLS or Reality
        if (sec == "tls") {
            val tls = JsonObject()
            tls.addProperty("serverName", if (!node.sni.isNullOrBlank()) node.sni else node.server)
            tls.addProperty("allowInsecure", false)
            val alpn = JsonArray()
            if (!node.alpn.isNullOrEmpty()) {
                node.alpn.forEach { alpn.add(it) }
            } else {
                alpn.add("http/1.1")
            }
            tls.add("alpn", alpn)
            streamSettings.add("tlsSettings", tls)
        } else if (sec == "reality") {
            val reality = JsonObject()
            val serverName = if (!node.sni.isNullOrBlank() && !node.sni.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                node.sni
            } else if (!node.server.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                node.server
            } else {
                "speedtest.net"
            }
            reality.addProperty("serverName", serverName)
            reality.addProperty("fingerprint", if (!node.fingerprint.isNullOrBlank()) node.fingerprint else "chrome")
            reality.addProperty("publicKey", node.pbk ?: "")
            reality.addProperty("shortId", node.sid ?: "")
            reality.addProperty("spiderX", node.spx ?: "")
            streamSettings.add("realitySettings", reality)
        }

        // Network: ws / grpc / tcp
        val net = node.type?.lowercase() ?: "tcp"
        if (net == "ws") {
            val ws = JsonObject()
            ws.addProperty("path", if (!node.path.isNullOrBlank()) node.path else "/")
            val headers = JsonObject()
            headers.addProperty("Host", if (!node.hostHeader.isNullOrBlank()) node.hostHeader else (node.sni ?: node.server))
            ws.add("headers", headers)
            streamSettings.add("wsSettings", ws)
        } else if (net == "grpc") {
            val grpc = JsonObject()
            grpc.addProperty("serviceName", if (!node.serviceName.isNullOrBlank()) node.serviceName else (node.path ?: ""))
            grpc.addProperty("multiMode", true)
            streamSettings.add("grpcSettings", grpc)
        } else if (net == "xhttp" || net == "splithttp") {
            val xhttp = JsonObject()
            xhttp.addProperty("path", if (!node.path.isNullOrBlank()) node.path else "/")
            xhttp.addProperty("host", if (!node.hostHeader.isNullOrBlank()) node.hostHeader else (node.sni ?: node.server))
            xhttp.addProperty("mode", "auto")
            streamSettings.add("xhttpSettings", xhttp)
        }

        outbound.add("streamSettings", streamSettings)
        return outbound
    }
}
