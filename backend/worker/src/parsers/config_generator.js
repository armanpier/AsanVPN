/**
 * ConfigGenerator: Generates Xray JSON and Sing-box JSON configs with built-in
 * Iranian Split-Tunneling (Iran IPs, .ir domains, and banking apps set to direct).
 */

export function generateXrayConfig(node, allNodes = []) {
  if (!node) return null;

  // Build outbound for the selected node
  const proxyOutbound = {
    tag: "proxy",
    protocol: node.protocol,
    settings: {},
    streamSettings: {
      network: node.type || "tcp",
      security: node.security || "none"
    }
  };

  if (node.protocol === "vless") {
    proxyOutbound.settings = {
      vnext: [
        {
          address: node.server,
          port: node.port,
          users: [
            {
              id: node.uuid,
              encryption: "none",
              flow: node.flow || undefined
            }
          ]
        }
      ]
    };
  } else if (node.protocol === "vmess") {
    proxyOutbound.settings = {
      vnext: [
        {
          address: node.server,
          port: node.port,
          users: [
            {
              id: node.uuid,
              alterId: node.alterId || 0,
              security: node.cipher || "auto"
            }
          ]
        }
      ]
    };
  } else if (node.protocol === "trojan") {
    proxyOutbound.settings = {
      servers: [
        {
          address: node.server,
          port: node.port,
          password: node.password
        }
      ]
    };
  } else if (node.protocol === "shadowsocks") {
    proxyOutbound.settings = {
      servers: [
        {
          address: node.server,
          port: node.port,
          method: node.method,
          password: node.password
        }
      ]
    };
  }

  // Handle streamSettings security (TLS / Reality)
  if (node.security === "tls") {
    proxyOutbound.streamSettings.tlsSettings = {
      serverName: node.sni || node.server,
      allowInsecure: false,
      alpn: node.alpn || ["http/1.1"]
    };
  } else if (node.security === "reality") {
    proxyOutbound.streamSettings.realitySettings = {
      serverName: node.sni || node.server,
      fingerprint: node.fingerprint || "chrome",
      publicKey: node.pbk,
      shortId: node.sid || "",
      spiderX: node.spx || ""
    };
  }

  // Handle network transport (ws / grpc / http)
  if (node.type === "ws") {
    proxyOutbound.streamSettings.wsSettings = {
      path: node.path || "/",
      headers: {
        Host: node.hostHeader || node.sni || node.server
      }
    };
  } else if (node.type === "grpc") {
    proxyOutbound.streamSettings.grpcSettings = {
      serviceName: node.serviceName || node.path || "",
      multiMode: true
    };
  }

  return {
    log: {
      loglevel: "warning"
    },
    inbounds: [
      {
        tag: "socks-in",
        port: 10808,
        listen: "127.0.0.1",
        protocol: "socks",
        settings: {
          auth: "noauth",
          udp: true
        },
        sniffing: {
          enabled: true,
          destOverride: ["http", "tls"]
        }
      },
      {
        tag: "http-in",
        port: 10809,
        listen: "127.0.0.1",
        protocol: "http",
        settings: {
          auth: "noauth"
        }
      }
    ],
    outbounds: [
      proxyOutbound,
      {
        tag: "direct",
        protocol: "freedom",
        settings: {
          domainStrategy: "UseIP"
        }
      },
      {
        tag: "block",
        protocol: "blackhole",
        settings: {
          response: {
            type: "http"
          }
        }
      }
    ],
    dns: {
      servers: [
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
        {
          address: "10.10.34.35",
          port: 53,
          domains: ["domain:ir", "geosite:ir"]
        },
        "localhost"
      ]
    },
    routing: {
      domainStrategy: "IPIfNonMatch",
      rules: [
        // Iranian websites and domains direct
        {
          type: "field",
          outboundTag: "direct",
          domain: [
            "domain:ir",
            "geosite:ir",
            "domain:shaparak.ir",
            "domain:blubank.sb24.ir",
            "domain:divar.ir",
            "domain:snapp.ir",
            "domain:torob.com",
            "domain:digikala.com",
            "domain:aparat.com"
          ]
        },
        // Iranian IP addresses direct
        {
          type: "field",
          outboundTag: "direct",
          ip: [
            "geoip:ir",
            "geoip:private"
          ]
        },
        // Ads and tracking block
        {
          type: "field",
          outboundTag: "block",
          domain: ["geosite:category-ads-all"]
        },
        // All other international traffic proxy
        {
          type: "field",
          outboundTag: "proxy",
          network: "tcp,udp"
        }
      ]
    }
  };
}

export function generateSingBoxConfig(node) {
  if (!node) return null;

  return {
    log: {
      level: "warn",
      timestamp: true
    },
    inbounds: [
      {
        type: "tun",
        tag: "tun-in",
        interface_name: "tun0",
        inet4_address: "172.19.0.1/30",
        auto_route: true,
        strict_route: true,
        stack: "system",
        sniff: true
      }
    ],
    outbounds: [
      {
        type: node.protocol,
        tag: "proxy",
        server: node.server,
        server_port: node.port,
        uuid: node.uuid || undefined,
        password: node.password || undefined,
        security: node.cipher || undefined,
        tls: node.security === "tls" ? {
          enabled: true,
          server_name: node.sni || node.server,
          insecure: false
        } : (node.security === "reality" ? {
          enabled: true,
          server_name: node.sni || node.server,
          reality: {
            enabled: true,
            public_key: node.pbk,
            short_id: node.sid || ""
          }
        } : undefined),
        transport: node.type === "ws" ? {
          type: "ws",
          path: node.path || "/",
          headers: {
            Host: node.hostHeader || node.sni || node.server
          }
        } : (node.type === "grpc" ? {
          type: "grpc",
          service_name: node.serviceName || ""
        } : undefined)
      },
      {
        type: "direct",
        tag: "direct"
      },
      {
        type: "block",
        tag: "block"
      }
    ],
    route: {
      rules: [
        {
          geoip: "ir",
          outbound: "direct"
        },
        {
          geosite: "ir",
          outbound: "direct"
        },
        {
          ip_is_private: true,
          outbound: "direct"
        }
      ],
      auto_detect_interface: true
    }
  };
}
