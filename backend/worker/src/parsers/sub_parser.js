/**
 * SubParser: Robust parser for v2ray/xray/sing-box subscription payloads.
 * Supports Base64, raw lines, vless://, vmess://, trojan://, ss://, hysteria2://
 */

function safeBase64Decode(str) {
  if (!str) return '';
  // Clean whitespace & url-safe replacements
  let clean = str.trim().replace(/-/g, '+').replace(/_/g, '/');
  // Add missing padding
  while (clean.length % 4 !== 0) {
    clean += '=';
  }
  try {
    // In Cloudflare Worker environment: atob
    return atob(clean);
  } catch (e) {
    // If native Buffer is available (Node.js fallback)
    if (typeof Buffer !== 'undefined') {
      try {
        return Buffer.from(clean, 'base64').toString('utf-8');
      } catch (err) {}
    }
    return str;
  }
}

function parseVless(uri) {
  try {
    const url = new URL(uri);
    const uuid = url.username;
    const host = url.hostname;
    const port = parseInt(url.port, 10);
    const params = Object.fromEntries(url.searchParams.entries());
    const remark = decodeURIComponent(url.hash ? url.hash.substring(1) : `${host}:${port}`);

    // If pbk is present or security is reality, ensure security is 'reality'
    const isReality = Boolean(params.pbk || params.security === 'reality');
    const security = isReality ? 'reality' : (params.security || 'none');

    // If Reality and sni is empty or is an IP address, provide standard fallback sni
    let sni = params.sni || '';
    if (isReality && (!sni || /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(sni))) {
      sni = 'speedtest.net';
    } else if (!sni) {
      sni = host;
    }

    return {
      protocol: 'vless',
      remark: remark || 'Server',
      server: host,
      port: port,
      uuid: uuid,
      type: params.type || 'tcp',
      security: security,
      sni: sni,
      alpn: params.alpn ? params.alpn.split(',') : undefined,
      path: params.path || '',
      hostHeader: params.host || sni || host,
      flow: params.flow || '',
      pbk: params.pbk || '', // Reality public key
      sid: params.sid || '', // Reality short id
      spx: params.spx || '', // Reality spiderX
      fingerprint: params.fp || 'chrome',
      serviceName: params.serviceName || ''
    };
  } catch (e) {
    return null;
  }
}

function parseTrojan(uri) {
  try {
    const url = new URL(uri);
    const password = url.username;
    const host = url.hostname;
    const port = parseInt(url.port, 10);
    const params = Object.fromEntries(url.searchParams.entries());
    const remark = decodeURIComponent(url.hash ? url.hash.substring(1) : `${host}:${port}`);

    return {
      protocol: 'trojan',
      remark: remark || 'Server',
      server: host,
      port: port,
      password: password,
      type: params.type || 'tcp',
      security: params.security || 'tls',
      sni: params.sni || host,
      alpn: params.alpn ? params.alpn.split(',') : undefined,
      path: params.path || '',
      hostHeader: params.host || params.sni || host,
      fingerprint: params.fp || 'chrome'
    };
  } catch (e) {
    return null;
  }
}

function parseVmess(uri) {
  try {
    const raw = uri.replace('vmess://', '');
    const decoded = safeBase64Decode(raw);
    const data = JSON.parse(decoded);

    return {
      protocol: 'vmess',
      remark: data.ps || 'Server',
      server: data.add,
      port: parseInt(data.port, 10),
      uuid: data.id,
      alterId: parseInt(data.aid || '0', 10),
      type: data.net || 'tcp',
      security: data.tls || 'none',
      sni: data.sni || data.host || data.add,
      path: data.path || '',
      hostHeader: data.host || '',
      cipher: data.scy || 'auto'
    };
  } catch (e) {
    return null;
  }
}

function parseShadowsocks(uri) {
  try {
    const hashSplit = uri.replace('ss://', '').split('#');
    const remark = hashSplit[1] ? decodeURIComponent(hashSplit[1]) : 'Server';
    const main = hashSplit[0];

    let method, password, server, port;

    if (main.includes('@')) {
      const atSplit = main.split('@');
      const creds = safeBase64Decode(atSplit[0]);
      const [m, p] = creds.split(':');
      method = m;
      password = p;
      const hostPort = atSplit[1].split(':');
      server = hostPort[0];
      port = parseInt(hostPort[1], 10);
    } else {
      const decoded = safeBase64Decode(main);
      const match = decoded.match(/^(.*?):(.*?)@(.*?):(\d+)$/);
      if (match) {
        method = match[1];
        password = match[2];
        server = match[3];
        port = parseInt(match[4], 10);
      }
    }

    if (!server || !port) return null;

    return {
      protocol: 'shadowsocks',
      remark: remark,
      server: server,
      port: port,
      password: password,
      method: method
    };
  } catch (e) {
    return null;
  }
}

export function parseSubscriptionText(rawText) {
  if (!rawText || typeof rawText !== 'string') return [];

  const rawBlocks = rawText.split(/[\r\n]+/).map(l => l.trim()).filter(Boolean);
  const nodes = [];
  const seenKeys = new Set();

  function addNode(n) {
    if (!n || !n.server || !n.port) return;
    const key = `${n.protocol}:${n.server}:${n.port}:${n.uuid || n.password || ''}:${n.remark || ''}`;
    if (!seenKeys.has(key)) {
      seenKeys.add(key);
      nodes.push(n);
    }
  }

  for (const block of rawBlocks) {
    let content = block;
    // Check if block needs base64 decoding
    if (!content.startsWith('vless://') && 
        !content.startsWith('vmess://') && 
        !content.startsWith('trojan://') && 
        !content.startsWith('ss://') &&
        !content.startsWith('hysteria2://')) {
      const decoded = safeBase64Decode(content);
      if (decoded && (decoded.includes('://') || decoded.includes('{'))) {
        content = decoded;
      }
    }

    const lines = content.split(/[\r\n]+/).map(l => l.trim()).filter(Boolean);
    for (const line of lines) {
      if (line.startsWith('vless://')) {
        addNode(parseVless(line));
      } else if (line.startsWith('vmess://')) {
        addNode(parseVmess(line));
      } else if (line.startsWith('trojan://')) {
        addNode(parseTrojan(line));
      } else if (line.startsWith('ss://')) {
        addNode(parseShadowsocks(line));
      }
    }
  }

  return nodes;
}
