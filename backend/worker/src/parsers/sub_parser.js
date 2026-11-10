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

export function normalizeDigits(str) {
  if (!str || typeof str !== 'string') return '';
  return str
    .replace(/[۰-۹]/g, d => String.fromCharCode(d.charCodeAt(0) - 1728))
    .replace(/[٠-٩]/g, d => String.fromCharCode(d.charCodeAt(0) - 1584));
}

function safeDecodeUri(str) {
  if (!str || typeof str !== 'string') return '';
  try {
    return decodeURIComponent(str);
  } catch (_) {
    return str;
  }
}

export function parseUserInfoHeader(headerStr) {
  if (!headerStr || typeof headerStr !== 'string') return null;
  const normalized = normalizeDigits(headerStr);
  const info = {};
  normalized.split(';').forEach(p => {
    const [k, v] = p.trim().split('=');
    if (k && v) {
      const num = parseInt(v, 10);
      if (!isNaN(num)) info[k.toLowerCase()] = num;
    }
  });

  if (info.total !== undefined) {
    if (info.total === 0) {
      info.traffic_remaining_bytes = -1;
    } else {
      const used = (info.upload || 0) + (info.download || 0);
      info.traffic_remaining_bytes = Math.max(0, info.total - used);
    }
  }

  if (info.expire && info.expire > 1000000000) {
    const nowSec = Math.floor(Date.now() / 1000);
    info.days_left = Math.max(0, Math.ceil((info.expire - nowSec) / 86400));
  }

  return Object.keys(info).length > 0 ? info : null;
}

export function parseTrafficUnitToBytes(valStr, unitStr) {
  const normVal = normalizeDigits(valStr);
  const val = parseFloat(normVal);
  if (isNaN(val)) return 0;
  const u = (unitStr || '').trim().toUpperCase();
  if (u.startsWith('T')) return Math.round(val * 1024 * 1024 * 1024 * 1024);
  if (u.startsWith('G')) return Math.round(val * 1024 * 1024 * 1024);
  if (u.startsWith('M')) return Math.round(val * 1024 * 1024);
  if (u.startsWith('K')) return Math.round(val * 1024);
  return Math.round(val);
}

export function extractUserInfoFromSubscription(rawText, parsedNodes = []) {
  if (!rawText && (!parsedNodes || parsedNodes.length === 0)) return null;

  // 1. Check for commented #subscription-userinfo in rawText
  if (typeof rawText === 'string') {
    const headerMatch = rawText.match(/(?:#|\/\/)\s*subscription-userinfo:\s*([^\r\n]+)/i);
    if (headerMatch) {
      const parsed = parseUserInfoHeader(headerMatch[1]);
      if (parsed) return parsed;
    }
  }

  // 2. Decode rawText if it is base64 encoded
  const candidateTexts = [];
  if (typeof rawText === 'string' && rawText.trim().length > 0) {
    candidateTexts.push(rawText);
    const decoded = safeBase64Decode(rawText);
    if (decoded && decoded !== rawText) {
      candidateTexts.push(decoded);
      const decHeaderMatch = decoded.match(/(?:#|\/\/)\s*subscription-userinfo:\s*([^\r\n]+)/i);
      if (decHeaderMatch) {
        const parsed = parseUserInfoHeader(decHeaderMatch[1]);
        if (parsed) return parsed;
      }
    }
  }

  // 3. Scan strings (nodes remarks + raw lines)
  const candidateStrings = [];
  if (Array.isArray(parsedNodes)) {
    for (const n of parsedNodes) {
      if (n.remark) candidateStrings.push(n.remark);
    }
  }

  for (const txt of candidateTexts) {
    const lines = txt.split(/[\r\n]+/).map(l => l.trim()).filter(Boolean);
    for (const l of lines) {
      if (l.startsWith('#') || l.includes('📊') || l.includes('⏳') || l.includes('⌛') || l.includes('🔋') || l.includes('%F0%9F%93%8A') || l.includes('%E2%8F%B3')) {
        candidateStrings.push(l);
      }
    }
  }

  let extractedRemaining = null;
  let extractedTotal = null;
  let extractedUsed = null;
  let extractedDays = null;
  let isUnlimitedTraffic = false;
  let isUnlimitedDays = false;

  for (let rawStr of candidateStrings) {
    // Decode URI component (e.g. %F0%9F%93%8A -> 📊, %E2%8F%B3 -> ⏳)
    let str = safeDecodeUri(rawStr);
    // Normalize Persian/Arabic digits
    str = normalizeDigits(str);

    // 3X-UI: |📊8.79GB| or 📊 8.79 GB
    if (extractedRemaining === null) {
      const remMatch = str.match(/📊\s*([0-9.]+)\s*([A-Za-z]+)/);
      if (remMatch) {
        extractedRemaining = parseTrafficUnitToBytes(remMatch[1], remMatch[2]);
      }
    }

    // Dummy node format: 10.0 GB🔋0 B or ∞🔋39.74 GB
    if (extractedTotal === null || extractedUsed === null) {
      const batMatch = str.match(/([0-9.]+|∞)\s*([A-Za-z]*)\s*🔋\s*([0-9.]+)\s*([A-Za-z]+)/);
      if (batMatch) {
        if (batMatch[1] === '∞') {
          isUnlimitedTraffic = true;
          extractedTotal = 0;
          extractedUsed = parseTrafficUnitToBytes(batMatch[3], batMatch[4]);
          extractedRemaining = -1;
        } else {
          extractedTotal = parseTrafficUnitToBytes(batMatch[1], batMatch[2]);
          extractedUsed = parseTrafficUnitToBytes(batMatch[3], batMatch[4]);
          if (extractedRemaining === null) {
            extractedRemaining = Math.max(0, extractedTotal - extractedUsed);
          }
        }
      }
    }

    // Days pattern: ⏳29D or ⌛ 30 or ⏳ 29 روز or ⌛ ∞
    if (extractedDays === null && !isUnlimitedDays) {
      if (str.includes('⌛ ∞') || str.includes('⏳ ∞') || str.includes('⌛∞') || str.includes('⏳∞')) {
        isUnlimitedDays = true;
        extractedDays = -1;
      } else {
        const daysMatch = str.match(/[⏳⌛]\s*(\d+)/);
        if (daysMatch) {
          extractedDays = parseInt(daysMatch[1], 10);
        } else {
          const altDaysMatch = str.match(/(\d+)\s*(?:[Dd]|Days?|روز)(?:\s*(?:left|باقیمانده|باقی|مانده))?/i);
          if (altDaysMatch) {
            extractedDays = parseInt(altDaysMatch[1], 10);
          }
        }
      }
    }

    if ((extractedRemaining !== null || isUnlimitedTraffic) && (extractedDays !== null || isUnlimitedDays)) {
      break;
    }
  }

  if (extractedRemaining === null && extractedDays === null && extractedTotal === null && !isUnlimitedTraffic && !isUnlimitedDays) {
    return null;
  }

  const nowSec = Math.floor(Date.now() / 1000);
  const result = {};

  if (isUnlimitedTraffic) {
    result.total = 0;
    result.traffic_remaining_bytes = -1;
    result.upload = 0;
    result.download = extractedUsed || 0;
  } else if (extractedRemaining !== null) {
    result.traffic_remaining_bytes = extractedRemaining;
    result.total = extractedTotal !== null ? extractedTotal : Math.max(extractedRemaining, 10 * 1024 * 1024 * 1024);
    result.upload = 0;
    result.download = Math.max(0, result.total - extractedRemaining);
  } else if (extractedTotal !== null) {
    result.total = extractedTotal;
    result.upload = 0;
    result.download = extractedUsed || 0;
    result.traffic_remaining_bytes = Math.max(0, extractedTotal - (extractedUsed || 0));
  }

  if (isUnlimitedDays) {
    result.days_left = -1;
    result.expire = 0;
  } else if (extractedDays !== null) {
    result.days_left = extractedDays;
    result.expire = nowSec + (extractedDays * 86400);
  }

  return Object.keys(result).length > 0 ? result : null;
}
