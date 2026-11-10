import { connect } from 'cloudflare:sockets';
import { parseSubscriptionText } from '../parsers/sub_parser.js';

/**
 * Universal Socket & HTTP Fetcher
 * Handles direct IPs, custom Cloudflare ports (2096), and self-signed certificates
 */
export async function fetchViaSocket(urlStr, options = {}) {
  const parsed = new URL(urlStr);
  const hostname = parsed.hostname;
  const port = parseInt(parsed.port || (parsed.protocol === 'https:' ? '443' : '80'), 10);
  const isTls = parsed.protocol === 'https:';

  const hostHeader = (port === 80 || port === 443) ? hostname : `${hostname}:${port}`;

  const socket = connect(
    { hostname, port },
    { secureTransport: isTls ? 'on' : 'off', allowHalfOpen: true }
  );

  const writer = socket.writable.getWriter();
  const path = parsed.pathname + (parsed.search || '');
  const method = (options.method || 'GET').toUpperCase();
  const headers = options.headers || {};

  let requestText = `${method} ${path} HTTP/1.1\r\n`;
  requestText += `Host: ${hostHeader}\r\n`;
  requestText += `User-Agent: v2rayNG/1.8.12\r\n`;
  requestText += `Accept: text/plain, application/json, */*\r\n`;
  requestText += `Connection: close\r\n`;

  for (const [key, val] of Object.entries(headers)) {
    if (val !== undefined && val !== null) {
      requestText += `${key}: ${val}\r\n`;
    }
  }

  if (options.body) {
    const bodyText = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
    requestText += `Content-Length: ${new TextEncoder().encode(bodyText).length}\r\n\r\n`;
    requestText += bodyText;
  } else {
    requestText += `\r\n`;
  }

  await writer.write(new TextEncoder().encode(requestText));
  writer.releaseLock();

  const reader = socket.readable.getReader();
  const chunks = [];
  let totalLen = 0;
  let headersParsed = false;
  let expectedContentLength = null;
  let isChunked = false;
  let headerEndOffset = -1;

  const readWithTimeout = (timeoutMs = 6000) => {
    return Promise.race([
      reader.read(),
      new Promise((_, reject) => setTimeout(() => reject(new Error('Socket read timeout')), timeoutMs))
    ]);
  };

  try {
    while (true) {
      const { done, value } = await readWithTimeout(6000);
      if (done) break;
      if (value) {
        chunks.push(value);
        totalLen += value.length;

        // Parse headers as soon as available to detect end of response early
        if (!headersParsed) {
          const currentBytes = new Uint8Array(totalLen);
          let off = 0;
          for (const c of chunks) { currentBytes.set(c, off); off += c.length; }
          const textPreview = new TextDecoder().decode(currentBytes);
          const splitIdx = textPreview.indexOf('\r\n\r\n');
          if (splitIdx !== -1) {
            headersParsed = true;
            headerEndOffset = new TextEncoder().encode(textPreview.slice(0, splitIdx + 4)).length;
            const headersStr = textPreview.slice(0, splitIdx).toLowerCase();
            const clMatch = headersStr.match(/content-length:\s*(\d+)/);
            if (clMatch) {
              expectedContentLength = parseInt(clMatch[1], 10);
            }
            if (headersStr.includes('transfer-encoding: chunked')) {
              isChunked = true;
            }
          }
        }

        if (headersParsed) {
          const bodyBytesLen = totalLen - headerEndOffset;
          if (expectedContentLength !== null && bodyBytesLen >= expectedContentLength) {
            break;
          }
          if (isChunked) {
            const tailBytes = chunks[chunks.length - 1];
            const tailText = new TextDecoder().decode(tailBytes);
            if (tailText.endsWith('0\r\n\r\n') || tailText.includes('0\r\n\r\n')) {
              break;
            }
          }
        }
      }
    }
  } catch (err) {
    if (chunks.length === 0) throw err;
  } finally {
    try { reader.releaseLock(); } catch (_) {}
    try { socket.close(); } catch (_) {}
  }

  const finalTotalLen = chunks.reduce((acc, c) => acc + c.length, 0);
  const fullBytes = new Uint8Array(finalTotalLen);
  let offset = 0;
  for (const c of chunks) {
    fullBytes.set(c, offset);
    offset += c.length;
  }

  const fullText = new TextDecoder().decode(fullBytes);
  const splitIdx = fullText.indexOf('\r\n\r\n');
  if (splitIdx === -1) {
    return fullText;
  }

  const headersPart = fullText.slice(0, splitIdx);
  const bodyPart = fullText.slice(splitIdx + 4);
  const statusLine = headersPart.split('\r\n')[0] || '';

  const statusCodeMatch = statusLine.match(/HTTP\/1\.[01]\s+(\d{3})/);
  const statusCode = statusCodeMatch ? parseInt(statusCodeMatch[1], 10) : 0;
  if (statusCode < 200 || statusCode >= 300) {
    throw new Error(`Socket upstream returned ${statusLine}: ${bodyPart.slice(0, 200)}`);
  }

  const responseHeaders = {};
  const headerLines = headersPart.split('\r\n');
  for (let i = 1; i < headerLines.length; i++) {
    const colonIdx = headerLines[i].indexOf(':');
    if (colonIdx !== -1) {
      const k = headerLines[i].slice(0, colonIdx).trim().toLowerCase();
      const v = headerLines[i].slice(colonIdx + 1).trim();
      responseHeaders[k] = v;
    }
  }

  let finalBody = bodyPart.trim();
  // Handle chunked transfer encoding
  if (headersPart.toLowerCase().includes('transfer-encoding: chunked')) {
    let unchunked = '';
    let remaining = bodyPart;
    while (remaining.length > 0) {
      const lineEnd = remaining.indexOf('\r\n');
      if (lineEnd === -1) break;
      const hexLen = remaining.slice(0, lineEnd).trim();
      const chunkSize = parseInt(hexLen, 16);
      if (isNaN(chunkSize) || chunkSize === 0) break;
      const chunkData = remaining.slice(lineEnd + 2, lineEnd + 2 + chunkSize);
      unchunked += chunkData;
      remaining = remaining.slice(lineEnd + 2 + chunkSize + 2);
    }
    finalBody = unchunked.trim();
  }

  if (options.returnFull) {
    return {
      text: finalBody,
      headers: responseHeaders,
      statusCode: statusCode
    };
  }

  return finalBody;
}

export async function universalFetch(urlStr, options = {}) {
  const parsed = new URL(urlStr);
  const isDirectIp = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(parsed.hostname);

  // If not direct IP, attempt native fetch first
  if (!isDirectIp) {
    try {
      const res = await fetch(urlStr, {
        method: options.method || 'GET',
        headers: options.headers || {},
        body: options.body
      });
      if (res.ok) {
        if (options.returnFull) {
          const respHeaders = {};
          res.headers.forEach((v, k) => { respHeaders[k.toLowerCase()] = v; });
          return {
            text: await res.text(),
            headers: respHeaders,
            statusCode: res.status
          };
        }
        return await res.text();
      }
    } catch (e) {
      // Fallback to socket
    }
  }

  return await fetchViaSocket(urlStr, options);
}

export const DEFAULT_INITIAL_PANELS = [
  {
    id: 'panel_3xui_default',
    name: 'سرور ۳X-UI اصلی',
    type: '3xui',
    url: 'https://panel.example.com:2087/secret_path',
    apiKey: 'your_panel_api_key_or_session_cookie',
    subBaseUrl: 'https://sub.example.com:2016/sub_prefix/{subId}',
    priority: 1,
    autoSync: true,
    lastSync: null,
    lastSyncStatus: null,
    lastSyncMessage: null,
    userCount: 0,
    createdAt: 1792620000
  },
  {
    id: 'panel_pasarguard_default',
    name: 'سرور پاسارگاد (PasarGuard)',
    type: 'pasarguard',
    url: 'https://pasarguard.example.com:8000',
    apiKey: 'pg_key_your_pasarguard_token_here',
    subBaseUrl: 'https://sub.example.com:9090',
    priority: 2,
    autoSync: true,
    lastSync: null,
    lastSyncStatus: null,
    lastSyncMessage: null,
    userCount: 0,
    createdAt: 1792620000
  },
  {
    id: 'panel_goguard_default',
    name: 'سرور گوگارد (GoGuard Core)',
    type: 'goguard',
    url: 'https://goguard.example.com',
    apiKey: 'your_goguard_api_key_here',
    subBaseUrl: '',
    priority: 3,
    autoSync: true,
    lastSync: null,
    lastSyncStatus: null,
    lastSyncMessage: null,
    userCount: 0,
    createdAt: 1792621000
  }
];

export class PanelManager {
  static async getPanels(env) {
    const raw = await env.VPN_USERS.get('__CONFIG_PANELS__');
    let panels = DEFAULT_INITIAL_PANELS;
    let needsSave = false;
    if (raw) {
      try {
        panels = JSON.parse(raw);
      } catch (e) {
        panels = DEFAULT_INITIAL_PANELS;
      }
    }

    // Auto-migrate any stored old VPS IP references to the new VPS IP (95.182.87.115) and port 2016
    for (const p of panels) {
      if (p.url && (p.url.includes('13.140.94.235') || p.url.includes('13.140.84.27'))) {
        p.url = p.url.replace(/13\.140\.(94\.235|84\.27)/g, '95.182.87.115');
        needsSave = true;
      }
      if (p.subBaseUrl && (p.subBaseUrl.includes('13.140.94.235') || p.subBaseUrl.includes('13.140.84.27') || p.subBaseUrl.includes(':2096'))) {
        p.subBaseUrl = p.subBaseUrl.replace(/13\.140\.(94\.235|84\.27)/g, '95.182.87.115').replace(':2096', ':2016');
        needsSave = true;
      }
    }

    // Ensure GoGuard is automatically registered if not already present
    const hasGoGuard = panels.some(p => p.url && p.url.includes('core.erfjab.com'));
    if (!hasGoGuard) {
      panels.push({
        id: 'panel_goguard_default',
        name: 'سرور گوگارد (GoGuard Core)',
        type: 'goguard',
        url: 'https://core.erfjab.com',
        apiKey: '7c704f070ae553721bfa91b4bcfeb84e2eaa8109d09e56d00b471e9001ed5206',
        subBaseUrl: '',
        priority: 3,
        autoSync: true,
        lastSync: null,
        lastSyncStatus: null,
        lastSyncMessage: null,
        userCount: 7,
        createdAt: 1792621000
      });
      needsSave = true;
    }

    // Ensure all panels have valid integer priority
    panels.forEach((p, idx) => {
      if (typeof p.priority !== 'number' || isNaN(p.priority) || p.priority < 1) {
        p.priority = idx + 1;
        needsSave = true;
      }
    });

    // Sort panels ascending by priority (1 = highest priority)
    panels.sort((a, b) => (a.priority || 99) - (b.priority || 99));

    if (needsSave) {
      await env.VPN_USERS.put('__CONFIG_PANELS__', JSON.stringify(panels));
    }

    return panels;
  }

  static async savePanels(env, panels) {
    if (Array.isArray(panels)) {
      panels.sort((a, b) => (a.priority || 99) - (b.priority || 99));
    }
    await env.VPN_USERS.put('__CONFIG_PANELS__', JSON.stringify(panels));
  }

  static async addOrUpdatePanel(env, panelData) {
    const panels = await this.getPanels(env);
    const id = panelData.id || `panel_${Date.now()}`;
    const cleanUrl = (panelData.url || '').trim().replace(/\/+$/, '');
    const existingIndex = panels.findIndex(p => p.id === id);

    let parsedPriority = 1;
    if (panelData.priority !== undefined && panelData.priority !== null && panelData.priority !== '') {
      parsedPriority = parseInt(panelData.priority, 10);
      if (isNaN(parsedPriority) || parsedPriority < 1) parsedPriority = 1;
    } else if (existingIndex >= 0 && panels[existingIndex].priority) {
      parsedPriority = panels[existingIndex].priority;
    } else {
      parsedPriority = panels.length + 1;
    }

    const newPanel = {
      id: id,
      name: panelData.name || 'پنل بدون نام',
      type: (panelData.type || 'pasarguard').toLowerCase(),
      url: cleanUrl,
      apiKey: (panelData.apiKey || panelData.api_key || '').trim(),
      subBaseUrl: (panelData.subBaseUrl || panelData.sub_base_url || '').trim(),
      priority: parsedPriority,
      autoSync: panelData.autoSync !== false && panelData.auto_sync !== false,
      lastSync: panelData.lastSync || null,
      lastSyncStatus: panelData.lastSyncStatus || null,
      lastSyncMessage: panelData.lastSyncMessage || null,
      userCount: panelData.userCount || 0,
      createdAt: panelData.createdAt || Math.floor(Date.now() / 1000)
    };

    if (existingIndex >= 0) {
      panels[existingIndex] = { ...panels[existingIndex], ...newPanel };
    } else {
      panels.push(newPanel);
    }

    panels.sort((a, b) => (a.priority || 99) - (b.priority || 99));
    await this.savePanels(env, panels);
    return newPanel;
  }

  static async deletePanel(env, panelId) {
    let panels = await this.getPanels(env);
    panels = panels.filter(p => p.id !== panelId);
    await this.savePanels(env, panels);
    return { success: true };
  }

  /**
   * Test live connectivity to a panel
   */
  static async testPanel(panel) {
    const startTime = Date.now();
    const type = (panel.type || '').toLowerCase();
    const url = (panel.url || '').trim().replace(/\/+$/, '');
    const apiKey = (panel.apiKey || '').trim();

    try {
      if (type === 'pasarguard') {
        const text = await universalFetch(`${url}/api/users`, {
          headers: {
            'X-API-KEY': apiKey,
            'Authorization': `ApiKey ${apiKey}`
          }
        });
        const json = JSON.parse(text);
        const count = json.users ? json.users.length : (Array.isArray(json) ? json.length : 0);
        return {
          ok: true,
          latency: Date.now() - startTime,
          userCount: count,
          message: `ارتباط با پاسارگاد موفق بود (${count} کاربر فعال شناسایی شد)`
        };
      } else if (type === '3xui') {
        // Handle 3X-UI panel path
        const inboundUrl = url.includes('/panel') 
          ? `${url}/api/inbounds/list` 
          : `${url}/panel/api/inbounds/list`;

        const text = await universalFetch(inboundUrl, {
          headers: {
            'Cookie': `session=${apiKey}`,
            'Authorization': `Bearer ${apiKey}`
          }
        });
        const json = JSON.parse(text);
        let clientCount = 0;
        if (json.obj && Array.isArray(json.obj)) {
          for (const ib of json.obj) {
            if (ib.settings?.clients) {
              clientCount += ib.settings.clients.length;
            } else if (ib.clientStats) {
              clientCount += ib.clientStats.length;
            }
          }
        }
        return {
          ok: true,
          latency: Date.now() - startTime,
          userCount: clientCount,
          message: `ارتباط با ۳X-UI موفق بود (${clientCount} کلاینت در اینباندها شناسایی شد)`
        };
      } else if (type === 'marzban') {
        const text = await universalFetch(`${url}/api/users`, {
          headers: {
            'Authorization': `Bearer ${apiKey}`
          }
        });
        const json = JSON.parse(text);
        const count = json.users ? json.users.length : (Array.isArray(json) ? json.length : 0);
        return {
          ok: true,
          latency: Date.now() - startTime,
          userCount: count,
          message: `ارتباط با مرزبان موفق بود (${count} کاربر شناسایی شد)`
        };
      } else if (type === 'hiddify') {
        const text = await universalFetch(`${url}/api/v2/admin/user/`, {
          headers: {
            'Hiddify-API-Key': apiKey
          }
        });
        const json = JSON.parse(text);
        const count = Array.isArray(json) ? json.length : 0;
        return {
          ok: true,
          latency: Date.now() - startTime,
          userCount: count,
          message: `ارتباط با هیدیفای موفق بود (${count} کاربر شناسایی شد)`
        };
      } else if (type === 'goguard' || type === 'guard') {
        const text = await universalFetch(`${url}/api/subscriptions`, {
          headers: {
            'X-API-Key': apiKey
          }
        });
        const json = JSON.parse(text);
        const count = json.items ? json.items.length : (Array.isArray(json) ? json.length : 0);
        return {
          ok: true,
          latency: Date.now() - startTime,
          userCount: count,
          message: `ارتباط با گوگارد (GoGuard) موفق بود (${count} کاربر فعال شناسایی شد)`
        };
      } else if (type === 'container' || type === 'raw' || type === 'xray_container') {
        const text = await universalFetch(url, {
          headers: {
            'User-Agent': 'v2rayNG/1.8.12',
            'Accept': '*/*'
          }
        });
        const nodes = parseSubscriptionText(text);
        if (!nodes || nodes.length === 0) {
          return {
            ok: false,
            latency: Date.now() - startTime,
            error: 'هیچ کانفیگ معتبری در لینک کانتینر شناسایی نشد (پشتیبانی از VLESS, VMess, Trojan, Shadowsocks)'
          };
        }
        return {
          ok: true,
          latency: Date.now() - startTime,
          userCount: nodes.length,
          message: `کانتینر فعال است (${nodes.length} سرور پروکسی Xray استخراج شد)`
        };
      }

      return { ok: false, error: 'نوع پنل پشتیبانی نمی‌شود' };
    } catch (err) {
      return {
        ok: false,
        latency: Date.now() - startTime,
        error: err.message || String(err)
      };
    }
  }

  static async inspectPanel(panel, customPath = null) {
    const type = (panel.type || '').toLowerCase();
    const url = (panel.url || '').trim().replace(/\/+$/, '');
    const apiKey = (panel.apiKey || '').trim();
    try {
      if (customPath) {
        const fullUrl = `${url}${customPath.startsWith('/') ? '' : '/'}${customPath}`;
        const text = await universalFetch(fullUrl, {
          headers: {
            'X-API-KEY': apiKey,
            'X-API-Key': apiKey,
            'Authorization': `Bearer ${apiKey}`,
            'Cookie': `session=${apiKey}`
          }
        });
        try {
          return { ok: true, data: JSON.parse(text) };
        } catch (_) {
          return { ok: true, raw: text.slice(0, 1000) };
        }
      }
      if (type === 'pasarguard') {
        const text = await universalFetch(`${url}/api/user/admin`, {
          headers: { 'X-API-KEY': apiKey }
        });
        return { ok: true, data: JSON.parse(text) };
      } else if (type === '3xui') {
        const inboundUrl = url.includes('/panel') 
          ? `${url}/api/inbounds/list` 
          : `${url}/panel/api/inbounds/list`;
        const text = await universalFetch(inboundUrl, {
          headers: {
            'Cookie': `session=${apiKey}`,
            'Authorization': `Bearer ${apiKey}`
          }
        });
        return { ok: true, data: JSON.parse(text) };
      }
      return { ok: false, error: 'Unsupported type' };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  }

  static generateSafeUsername(userRecord) {
    const rawName = String(userRecord.name || '').trim().toLowerCase();
    if (/^[a-z0-9_]{3,30}$/.test(rawName)) {
      return rawName;
    }
    const asciiPart = rawName.replace(/[^a-z0-9]/g, '').slice(0, 24);
    const pinPart = String(userRecord.pin || '').slice(-6);
    if (asciiPart.length >= 3) {
      return `${asciiPart}_${pinPart}`;
    }
    return `user_${userRecord.pin || Math.floor(100000 + Math.random() * 900000)}`;
  }

  static randomHex(len = 16) {
    const chars = 'abcdef0123456789';
    let res = '';
    for (let i = 0; i < len; i++) {
      res += chars[Math.floor(Math.random() * chars.length)];
    }
    return res;
  }

  /**
   * Two-Way Sync: Creates user on the remote proxy panel and returns subUrl
   */
  static async createRemoteUser(panel, userRecord) {
    const type = (panel.type || '').toLowerCase();
    const url = (panel.url || '').trim().replace(/\/+$/, '');
    const apiKey = (panel.apiKey || '').trim();
    const subBase = (panel.subBaseUrl || '').trim();
    const safeUsername = this.generateSafeUsername(userRecord);

    const now = Math.floor(Date.now() / 1000);
    const expireSec = (userRecord.expires_at && userRecord.expires_at > 0) ? userRecord.expires_at : null;
    let dataLimitBytes = null;
    if (userRecord.traffic_total_bytes && userRecord.traffic_total_bytes > 0) {
      dataLimitBytes = userRecord.traffic_total_bytes;
    }

    if (type === 'pasarguard') {
      const payload = {
        username: safeUsername,
        expire: expireSec,
        data_limit: dataLimitBytes,
        data_limit_reset_strategy: "no_reset",
        status: userRecord.active !== false ? "active" : "disabled",
        group_ids: [1, 2, 3, 6],
        proxies: {
          vless: { id: crypto.randomUUID() },
          vmess: { id: crypto.randomUUID() },
          trojan: { password: this.randomHex(24) },
          shadowsocks: { password: this.randomHex(24), method: "chacha20-ietf-poly1305" }
        }
      };

      const resText = await universalFetch(`${url}/api/user`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-API-KEY': apiKey,
          'Authorization': `ApiKey ${apiKey}`
        },
        body: JSON.stringify(payload)
      });

      let created = {};
      try {
        created = JSON.parse(resText);
      } catch (e) {
        throw new Error(`PasarGuard create user parse failed: ${resText.slice(0, 150)}`);
      }

      let subUrl = created.subscription_url || '';
      if (!subUrl && created.username) {
        try {
          const detailText = await universalFetch(`${url}/api/user/${encodeURIComponent(created.username)}`, {
            headers: { 'X-API-KEY': apiKey }
          });
          const detail = JSON.parse(detailText);
          subUrl = detail.subscription_url || '';
        } catch (_) {}
      }

      if (!subUrl && subBase && created.username) {
        subUrl = `${subBase}/sub/${encodeURIComponent(created.username)}`;
      }

      return {
        username: created.username || safeUsername,
        subUrl: subUrl,
        expiresAt: expireSec,
        panelId: panel.id,
        panelType: 'pasarguard'
      };
    } else if (type === '3xui') {
      const inboundUrl = url.includes('/panel') 
        ? `${url}/api/inbounds/list` 
        : `${url}/panel/api/inbounds/list`;

      const listText = await universalFetch(inboundUrl, {
        headers: {
          'Cookie': `session=${apiKey}`,
          'Authorization': `Bearer ${apiKey}`
        }
      });
      const listJson = JSON.parse(listText);
      const inbounds = listJson.obj || (Array.isArray(listJson) ? listJson : []);
      if (inbounds.length === 0) {
        throw new Error('هیچ اینباندی در پنل ۳X-UI یافت نشد');
      }

      const targetInbound = inbounds.find(ib => ib.enable !== false) || inbounds[0];
      const subId = this.randomHex(16);
      const clientUuid = crypto.randomUUID();
      const expiryMs = expireSec ? expireSec * 1000 : 0;

      const addClientUrl = url.includes('/panel')
        ? `${url}/api/clients/add`
        : `${url}/panel/api/clients/add`;

      const clientObj = {
        id: clientUuid,
        flow: targetInbound.protocol === 'vless' ? 'xtls-rprx-vision' : '',
        email: safeUsername,
        limitIp: 0,
        totalGB: dataLimitBytes || 0,
        expiryTime: expiryMs,
        enable: userRecord.active !== false,
        tgId: 0,
        subId: subId,
        reset: 0
      };

      const addPayload = {
        client: clientObj,
        inboundIds: [targetInbound.id]
      };

      const addResText = await universalFetch(addClientUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Cookie': `session=${apiKey}`,
          'Authorization': `Bearer ${apiKey}`
        },
        body: JSON.stringify(addPayload)
      });

      let addRes = {};
      try { addRes = JSON.parse(addResText); } catch (_) {}
      if (addRes && addRes.success === false && !String(addRes.msg || '').includes('already exists')) {
        console.warn(`3X-UI add client warning: ${addRes.msg || addResText}`);
      }

      let subUrl = '';
      if (subBase) {
        subUrl = subBase.replace(':2096', ':2016').replace('{subId}', subId);
      } else {
        const host = new URL(url).hostname;
        subUrl = `https://${host}:2016/xx11fs28c0ea3gr5/${subId}`;
      }

      return {
        username: safeUsername,
        subUrl: subUrl,
        clientUuid: clientUuid,
        inboundId: targetInbound.id,
        subId: subId,
        expiresAt: expireSec,
        panelId: panel.id,
        panelType: '3xui'
      };
    } else if (type === 'marzban') {
      const payload = {
        username: safeUsername,
        expire: expireSec || 0,
        data_limit: dataLimitBytes || 0,
        data_limit_reset_strategy: "no_reset",
        status: userRecord.active !== false ? "active" : "disabled",
        proxies: {
          vless: { id: crypto.randomUUID(), flow: "xtls-rprx-vision" },
          vmess: { id: crypto.randomUUID() },
          trojan: { password: this.randomHex(24) },
          shadowsocks: { password: this.randomHex(24), method: "chacha20-ietf-poly1305" }
        }
      };

      const resText = await universalFetch(`${url}/api/user`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${apiKey}`
        },
        body: JSON.stringify(payload)
      });

      const created = JSON.parse(resText);
      return {
        username: created.username || safeUsername,
        subUrl: created.subscription_url || '',
        expiresAt: expireSec,
        panelId: panel.id,
        panelType: 'marzban'
      };
    } else if (type === 'hiddify') {
      const days = expireSec ? Math.max(1, Math.ceil((expireSec - now) / 86400)) : 30;
      const gb = dataLimitBytes ? Math.ceil(dataLimitBytes / (1024 * 1024 * 1024)) : 50;

      const payload = {
        name: safeUsername,
        package_days: days,
        usage_limit_GB: gb,
        comment: "AsanVPN User"
      };

      const resText = await universalFetch(`${url}/api/v2/admin/user/`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Hiddify-API-Key': apiKey
        },
        body: JSON.stringify(payload)
      });

      const created = JSON.parse(resText);
      const subUrl = created.link || (created.uuid ? `${url}/${created.uuid}/` : '');
      return {
        username: created.name || safeUsername,
        subUrl: subUrl,
        expiresAt: expireSec,
        panelId: panel.id,
        panelType: 'hiddify'
      };
    } else if (type === 'goguard' || type === 'guard') {
      const payload = {
        username: safeUsername,
        limit_expire: expireSec || 0,
        limit_usage: dataLimitBytes || 0,
        services: [1],
        note: `AsanVPN:${userRecord.pin || ''}`
      };

      const resText = await universalFetch(`${url}/api/subscriptions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-API-Key': apiKey
        },
        body: JSON.stringify(payload)
      });

      let created = {};
      try {
        created = JSON.parse(resText);
      } catch (_) {
        throw new Error(`GoGuard create user response: ${resText.slice(0, 150)}`);
      }

      if (Array.isArray(created) && created.length > 0) {
        created = created[0];
      }

      let subUrl = created.subscription_link || created.subscription_url || '';
      if (!subUrl && created.access_key) {
        subUrl = `https://versub.bertly.top/guards/${created.access_key}`;
      }

      return {
        username: created.username || safeUsername,
        subUrl: subUrl,
        id: created.id,
        accessKey: created.access_key,
        expiresAt: expireSec,
        panelId: panel.id,
        panelType: 'goguard'
      };
    }

    throw new Error(`Unsupported panel type for user creation: ${type}`);
  }

  /**
   * Two-Way Sync: Deletes user on the remote proxy panel
   */
  static async deleteRemoteUser(panel, remoteInfo) {
    if (!remoteInfo) return;
    const type = (panel.type || '').toLowerCase();
    const url = (panel.url || '').trim().replace(/\/+$/, '');
    const apiKey = (panel.apiKey || '').trim();

    try {
      if (type === 'pasarguard' || type === 'marzban') {
        const username = remoteInfo.username || remoteInfo.name;
        if (username) {
          await universalFetch(`${url}/api/user/${encodeURIComponent(username)}`, {
            method: 'DELETE',
            headers: {
              'X-API-KEY': apiKey,
              'Authorization': `Bearer ${apiKey}`
            }
          });
        }
      } else if (type === '3xui') {
        const username = remoteInfo.username || remoteInfo.name || remoteInfo.email;
        const clientUuid = remoteInfo.clientUuid || remoteInfo.id;

        // 1. Delete by username/email via Sanaei /panel/api/clients/del/:email
        if (username) {
          const delEmailUrl = url.includes('/panel')
            ? `${url}/api/clients/del/${encodeURIComponent(username)}`
            : `${url}/panel/api/clients/del/${encodeURIComponent(username)}`;
          try {
            await universalFetch(delEmailUrl, {
              method: 'POST',
              headers: {
                'Cookie': `session=${apiKey}`,
                'Authorization': `Bearer ${apiKey}`
              }
            });
          } catch (_) {}
        }

        // 2. Also attempt deletion by client UUID
        if (clientUuid) {
          const delUrl = url.includes('/panel')
            ? `${url}/api/inbounds/delClient/${encodeURIComponent(clientUuid)}`
            : `${url}/panel/api/inbounds/delClient/${encodeURIComponent(clientUuid)}`;

          try {
            await universalFetch(delUrl, {
              method: 'POST',
              headers: {
                'Cookie': `session=${apiKey}`,
                'Authorization': `Bearer ${apiKey}`
              }
            });
          } catch (_) {}
        }
      } else if (type === 'hiddify') {
        const uuid = remoteInfo.uuid || remoteInfo.id;
        if (uuid) {
          await universalFetch(`${url}/api/v2/admin/user/${encodeURIComponent(uuid)}/`, {
            method: 'DELETE',
            headers: { 'Hiddify-API-Key': apiKey }
          });
        }
      } else if (type === 'goguard' || type === 'guard') {
        const username = remoteInfo.username || remoteInfo.name;
        if (username) {
          await universalFetch(`${url}/api/subscriptions`, {
            method: 'DELETE',
            headers: {
              'Content-Type': 'application/json',
              'X-API-Key': apiKey
            },
            body: JSON.stringify({ usernames: [username] })
          });
        }
      }
    } catch (e) {
      console.warn(`Could not delete remote user on panel ${panel.name}:`, e.message);
    }
  }

  /**
   * Two-Way Sync: Updates user state on the remote proxy panel (active/disabled, expiry, limit)
   */
  static async updateRemoteUser(panel, remoteInfo, updates = {}) {
    if (!remoteInfo) return;
    const type = (panel.type || '').toLowerCase();
    const url = (panel.url || '').trim().replace(/\/+$/, '');
    const apiKey = (panel.apiKey || '').trim();

    try {
      if (type === 'pasarguard' || type === 'marzban') {
        const username = remoteInfo.username || remoteInfo.name;
        if (username) {
          const payload = {};
          if (updates.active !== undefined) {
            payload.status = updates.active ? 'active' : 'disabled';
          }
          if (updates.expires_at !== undefined) {
            payload.expire = updates.expires_at;
          }
          if (updates.traffic_total_bytes !== undefined) {
            payload.data_limit = updates.traffic_total_bytes;
          }
          const authHeader = type === 'marzban' ? `Bearer ${apiKey}` : `ApiKey ${apiKey}`;
          await universalFetch(`${url}/api/user/${encodeURIComponent(username)}`, {
            method: 'PUT',
            headers: {
              'Content-Type': 'application/json',
              'X-API-KEY': apiKey,
              'Authorization': authHeader
            },
            body: JSON.stringify(payload)
          });
        }
      } else if (type === '3xui') {
        const clientUuid = remoteInfo.clientUuid;
        const inboundId = remoteInfo.inboundId;
        if (clientUuid && inboundId) {
          const updateUrl = url.includes('/panel')
            ? `${url}/api/inbounds/updateClient/${clientUuid}`
            : `${url}/panel/api/inbounds/updateClient/${clientUuid}`;

          const clientObj = {
            id: clientUuid,
            email: remoteInfo.username || '',
            subId: remoteInfo.subId || '',
            enable: updates.active !== false
          };
          if (updates.expires_at !== undefined) {
            clientObj.expiryTime = updates.expires_at ? updates.expires_at * 1000 : 0;
          }
          if (updates.traffic_total_bytes !== undefined) {
            clientObj.totalGB = updates.traffic_total_bytes || 0;
          }

          await universalFetch(updateUrl, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'Cookie': `session=${apiKey}`,
              'Authorization': `Bearer ${apiKey}`
            },
            body: JSON.stringify({
              id: inboundId,
              settings: JSON.stringify({ clients: [clientObj] })
            })
          });
        }
      } else if (type === 'goguard' || type === 'guard') {
        const username = remoteInfo.username || remoteInfo.name;
        if (username) {
          if (updates.active !== undefined) {
            const ep = updates.active ? '/api/subscriptions/enable' : '/api/subscriptions/disable';
            await universalFetch(`${url}${ep}`, {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'X-API-Key': apiKey
              },
              body: JSON.stringify({ usernames: [username] })
            }).catch(() => {});
          }

          if (updates.traffic_total_bytes !== undefined || updates.expires_at !== undefined) {
            const ggPayload = {};
            if (updates.traffic_total_bytes !== undefined) ggPayload.limit_usage = updates.traffic_total_bytes;
            if (updates.expires_at !== undefined) ggPayload.limit_expire = updates.expires_at;
            await universalFetch(`${url}/api/subscriptions/${encodeURIComponent(username)}`, {
              method: 'PUT',
              headers: {
                'Content-Type': 'application/json',
                'X-API-Key': apiKey
              },
              body: JSON.stringify(ggPayload)
            }).catch(() => {});
          }
        }
      }
    } catch (e) {
      console.warn(`Could not update remote user on panel ${panel.name}:`, e.message);
    }
  }

  /**
   * Pushes synchronized days left (expires_at) and remaining traffic to all mapped panels
   * so all panels stay coordinated with the current active subscription state.
   */
  static async syncUserLimitsToPanels(env, userRecord) {
    if (!userRecord) return;
    try {
      const panels = await this.getPanels(env);
      const updates = {};
      if (userRecord.expires_at !== undefined) {
        updates.expires_at = userRecord.expires_at;
      }
      if (userRecord.traffic_remaining_bytes === -1) {
        updates.traffic_total_bytes = 0;
      } else if (userRecord.traffic_remaining_bytes !== undefined && userRecord.traffic_remaining_bytes >= 0) {
        updates.traffic_total_bytes = userRecord.traffic_remaining_bytes;
      } else if (userRecord.traffic_total_bytes !== undefined) {
        updates.traffic_total_bytes = userRecord.traffic_total_bytes;
      }

      if (Object.keys(updates).length === 0) return;

      const promises = [];
      if (userRecord.remote_panel_mappings && typeof userRecord.remote_panel_mappings === 'object') {
        for (const [panelId, mapping] of Object.entries(userRecord.remote_panel_mappings)) {
          const panel = panels.find(p => p.id === panelId);
          if (panel && mapping) {
            promises.push(this.updateRemoteUser(panel, mapping, updates));
          }
        }
      }
      await Promise.allSettled(promises);
    } catch (e) {
      console.warn('syncUserLimitsToPanels error:', e.message);
    }
  }

  /**
   * Fetch all remote users from a panel
   */
  static async fetchRemoteUsers(panel) {
    const type = (panel.type || '').toLowerCase();
    const url = (panel.url || '').trim().replace(/\/+$/, '');
    const apiKey = (panel.apiKey || '').trim();
    const subBase = (panel.subBaseUrl || '').trim();
    const results = [];

    if (type === 'pasarguard') {
      const text = await universalFetch(`${url}/api/users`, {
        headers: {
          'X-API-KEY': apiKey,
          'Authorization': `ApiKey ${apiKey}`
        }
      });
      const json = JSON.parse(text);
      const userList = json.users || (Array.isArray(json) ? json : []);

      for (const u of userList) {
        let subUrl = u.subscription_url || '';
        // If sub URL is missing in list, try fetching user details
        if (!subUrl && u.username) {
          try {
            const detailText = await universalFetch(`${url}/api/user/${encodeURIComponent(u.username)}`, {
              headers: { 'X-API-KEY': apiKey }
            });
            const detail = JSON.parse(detailText);
            subUrl = detail.subscription_url || '';
          } catch (e) {}
        }

        if (subUrl) {
          let expiresAt = null;
          if (u.expire && u.expire > 0) {
            if (typeof u.expire === 'number') {
              if (u.expire > 1000000000000) {
                expiresAt = Math.floor(u.expire / 1000);
              } else if (u.expire > 1000000000) {
                expiresAt = Math.floor(u.expire);
              }
            } else {
              const expTime = new Date(u.expire).getTime();
              if (!isNaN(expTime) && expTime > 0) expiresAt = Math.floor(expTime / 1000);
            }
          }

          results.push({
            name: u.username || `کاربر ${u.id}`,
            username: u.username,
            subUrl: subUrl,
            status: u.status || 'active',
            expiresAt: expiresAt,
            panelId: panel.id,
            panelType: 'pasarguard'
          });
        }
      }
    } else if (type === '3xui') {
      const inboundUrl = url.includes('/panel') 
        ? `${url}/api/inbounds/list` 
        : `${url}/panel/api/inbounds/list`;

      const text = await universalFetch(inboundUrl, {
        headers: {
          'Cookie': `session=${apiKey}`,
          'Authorization': `Bearer ${apiKey}`
        }
      });
      const json = JSON.parse(text);
      if (json.obj && Array.isArray(json.obj)) {
        const seenClients = new Set();
        for (const ib of json.obj) {
          const clients = ib.settings?.clients || [];
          for (const cl of clients) {
            const clientKey = cl.subId || cl.email || cl.id;
            if (seenClients.has(clientKey)) continue;
            seenClients.add(clientKey);

            let subUrl = '';
            if (subBase && cl.subId) {
              subUrl = subBase.replace('{subId}', cl.subId);
            } else if (cl.subId) {
              const host = new URL(url).hostname;
              subUrl = `https://${host}:2096/xx11fs28c0ea3gr5/${cl.subId}`;
            }

            if (subUrl) {
              let expiresAt = null;
              if (cl.expiryTime && cl.expiryTime > 0) {
                expiresAt = Math.floor(cl.expiryTime / 1000);
              }

              results.push({
                name: cl.email || `کلاینت ${cl.subId || cl.id}`,
                username: cl.email,
                subUrl: subUrl,
                status: cl.enable !== false ? 'active' : 'disabled',
                expiresAt: expiresAt,
                panelId: panel.id,
                panelType: '3xui'
              });
            }
          }
        }
      }
    } else if (type === 'marzban') {
      const text = await universalFetch(`${url}/api/users`, {
        headers: { 'Authorization': `Bearer ${apiKey}` }
      });
      const json = JSON.parse(text);
      const userList = json.users || (Array.isArray(json) ? json : []);
      for (const u of userList) {
        if (u.subscription_url) {
          results.push({
            name: u.username || 'کاربر مرزبان',
            username: u.username,
            subUrl: u.subscription_url,
            status: u.status || 'active',
            expiresAt: u.expire ? Math.floor(u.expire) : null,
            panelId: panel.id,
            panelType: 'marzban'
          });
        }
      }
    } else if (type === 'goguard' || type === 'guard') {
      const text = await universalFetch(`${url}/api/subscriptions`, {
        headers: { 'X-API-Key': apiKey }
      });
      const json = JSON.parse(text);
      const items = json.items || (Array.isArray(json) ? json : []);
      for (const u of items) {
        if (u.subscription_link) {
          results.push({
            name: u.username || `کاربر ${u.id}`,
            username: u.username,
            subUrl: u.subscription_link,
            status: (u.enabled !== false && u.activated !== false) ? 'active' : 'disabled',
            expiresAt: u.limit_expire && u.limit_expire > 0 ? u.limit_expire : null,
            panelId: panel.id,
            panelType: 'goguard'
          });
        }
      }
    } else if (type === 'container' || type === 'raw' || type === 'xray_container') {
      results.push({
        name: panel.name || 'کانتینر لینک‌های Xray',
        username: panel.id,
        subUrl: url,
        status: 'active',
        expiresAt: null,
        panelId: panel.id,
        panelType: 'container'
      });
    }

    return results;
  }

  /**
   * Sync a panel with AsanVPN: fetches all users and updates/creates them with 4-digit PINs
   * Optimized with dirty checking to prevent exceeding KV daily write quotas
   */
  static async syncPanel(env, panelId, preloadedUsers = null) {
    const panels = await this.getPanels(env);
    const panel = panels.find(p => p.id === panelId);
    if (!panel) {
      throw new Error(`پنل با شناسه ${panelId} یافت نشد`);
    }

    const type = (panel.type || '').toLowerCase();
    if (type === 'container' || type === 'raw' || type === 'xray_container') {
      const now = Math.floor(Date.now() / 1000);
      try {
        const text = await universalFetch(panel.url, {
          headers: { 'User-Agent': 'v2rayNG/1.8.12', 'Accept': '*/*' }
        });
        const nodes = parseSubscriptionText(text);
        const nodeCount = nodes ? nodes.length : 0;
        panel.lastSync = now;
        panel.lastSyncStatus = 'success';
        panel.userCount = nodeCount;
        panel.lastSyncMessage = `کانتینر فعال با ${nodeCount} سرور پروکسی Xray`;
        await this.savePanels(env, panels);

        return {
          success: true,
          added: 0,
          updated: 0,
          total: nodeCount,
          message: panel.lastSyncMessage
        };
      } catch (fetchErr) {
        panel.lastSync = now;
        panel.lastSyncStatus = 'error';
        panel.lastSyncMessage = fetchErr.message || 'خطا در ارتباط با کانتینر';
        await this.savePanels(env, panels);
        throw fetchErr;
      }
    }

    let remoteUsers = [];
    try {
      remoteUsers = await this.fetchRemoteUsers(panel);
    } catch (fetchErr) {
      panel.lastSync = Math.floor(Date.now() / 1000);
      panel.lastSyncStatus = 'error';
      panel.lastSyncMessage = fetchErr.message || 'خطا در ارتباط با پنل';
      await this.savePanels(env, panels);
      throw fetchErr;
    }

    // Use preloaded users if provided (e.g. from syncAllPanels) to avoid multiple list() calls
    let existingUsers = preloadedUsers;
    if (!existingUsers) {
      const kvList = await env.VPN_USERS.list();
      existingUsers = [];
      for (const k of kvList.keys) {
        if (!/^\d+$/.test(k.name)) continue;
        const raw = await env.VPN_USERS.get(k.name);
        if (raw) {
          try {
            const u = JSON.parse(raw);
            if (u.pin) existingUsers.push(u);
          } catch (e) {}
        }
      }
    }

    let addedCount = 0;
    let updatedCount = 0;
    const now = Math.floor(Date.now() / 1000);

    for (const ru of remoteUsers) {
      // Find if user already exists by subUrl or username (case-insensitive)
      const ruName = (ru.name || '').trim().toLowerCase();
      const existing = existingUsers.find(eu => {
        const euName = (eu.name || '').trim().toLowerCase();
        if (euName && ruName && euName === ruName) return true;
        if (eu.upstream_sub_url && eu.upstream_sub_url.includes(ru.subUrl)) return true;
        if (Array.isArray(eu.upstream_sub_urls) && eu.upstream_sub_urls.includes(ru.subUrl)) return true;
        return false;
      });

      if (existing) {
        let hasChanged = false;

        // Merge subscription URLs from multiple panels
        const urlSet = new Set(
          (existing.upstream_sub_urls || [])
            .concat(existing.upstream_sub_url ? existing.upstream_sub_url.split(/[\r\n,]+/) : [])
            .map(s => s.trim())
            .filter(u => u.startsWith('http'))
        );

        if (ru.subUrl && ru.subUrl.trim().startsWith('http') && !urlSet.has(ru.subUrl.trim())) {
          urlSet.add(ru.subUrl.trim());
          hasChanged = true;
        }

        const mergedUrls = Array.from(urlSet);
        if (mergedUrls.length > 0) {
          const newUrlStr = mergedUrls.join('\n');
          if (existing.upstream_sub_url !== newUrlStr) {
            existing.upstream_sub_url = newUrlStr;
            existing.upstream_sub_urls = mergedUrls;
            hasChanged = true;
          }
        }

        // Merge panel IDs
        const panelIds = new Set(existing.panel_ids || [existing.panel_id].filter(Boolean));
        if (!panelIds.has(panel.id)) {
          panelIds.add(panel.id);
          existing.panel_ids = Array.from(panelIds);
          hasChanged = true;
        }

        // Multi panel type
        if (existing.panel_ids && existing.panel_ids.length > 1) {
          if (existing.panel_type !== 'multi') {
            existing.panel_type = 'multi';
            hasChanged = true;
          }
        } else if (existing.panel_type !== ru.panelType) {
          existing.panel_type = ru.panelType;
          hasChanged = true;
        }

        // Expiration: keep most generous expiration
        if (ru.expiresAt) {
          if (!existing.expires_at || ru.expiresAt > existing.expires_at) {
            existing.expires_at = ru.expiresAt;
            hasChanged = true;
          }
        }

        // Active state: if active in any panel, keep active
        const newActive = ru.status !== 'disabled';
        if (newActive && existing.active === false) {
          existing.active = true;
          hasChanged = true;
        }

        if (hasChanged) {
          await env.VPN_USERS.put(existing.pin, JSON.stringify(existing));
          updatedCount++;
        }
      } else {
        // Generate a new unique 8-digit PIN
        let pin = null;
        let attempts = 0;
        while (attempts < 30) {
          const cand = String(Math.floor(10000000 + Math.random() * 90000000));
          const hasInMemory = existingUsers.some(u => u.pin === cand);
          if (!hasInMemory) {
            const hasInKv = await env.VPN_USERS.get(cand);
            if (!hasInKv) {
              pin = cand;
              break;
            }
          }
          attempts++;
        }

        if (pin) {
          const newUser = {
            pin: pin,
            name: ru.name || 'کاربر جدید',
            panel_type: ru.panelType,
            upstream_sub_url: ru.subUrl,
            upstream_sub_urls: [ru.subUrl],
            device_id: null,
            bound_at: null,
            active: ru.status !== 'disabled',
            created_at: now,
            expires_at: ru.expiresAt || (now + 30 * 86400),
            panel_id: panel.id,
            panel_ids: [panel.id]
          };
          await env.VPN_USERS.put(pin, JSON.stringify(newUser));
          existingUsers.push(newUser);
          addedCount++;
        }
      }
    }

    // Update panel metadata only if changes occurred or previous state was error
    const prevStatus = panel.lastSyncStatus;
    panel.lastSync = now;
    panel.lastSyncStatus = 'success';
    panel.lastSyncMessage = `همگام‌سازی موفق (${addedCount} کاربر جدید، ${updatedCount} بروزرسانی)`;
    panel.userCount = remoteUsers.length;

    if (addedCount > 0 || updatedCount > 0 || prevStatus !== 'success') {
      await this.savePanels(env, panels);
    }

    return {
      success: true,
      added: addedCount,
      updated: updatedCount,
      total: remoteUsers.length,
      message: panel.lastSyncMessage
    };
  }

  /**
   * Merge duplicate users with identical names across multiple panels
   */
  static async mergeDuplicateUsers(env, preloadedUsers = null) {
    let users = preloadedUsers;
    if (!users) {
      const kvList = await env.VPN_USERS.list();
      users = [];
      for (const k of kvList.keys) {
        if (!/^\d+$/.test(k.name)) continue;
        const raw = await env.VPN_USERS.get(k.name);
        if (raw) {
          try {
            const u = JSON.parse(raw);
            if (u.pin) users.push(u);
          } catch (e) {}
        }
      }
    }

    const groupsByName = new Map();
    for (const u of users) {
      const norm = (u.name || '').trim().toLowerCase();
      if (!norm) continue;
      if (!groupsByName.has(norm)) {
        groupsByName.set(norm, []);
      }
      groupsByName.get(norm).push(u);
    }

    let mergedGroupsCount = 0;
    const removedPins = [];

    for (const [name, group] of groupsByName.entries()) {
      if (group.length <= 1) continue;

      // Prioritize primary record:
      // 1. Bound device
      // 2. 8-digit PIN
      // 3. Oldest created_at
      group.sort((a, b) => {
        const aBound = a.device_id ? 1 : 0;
        const bBound = b.device_id ? 1 : 0;
        if (aBound !== bBound) return bBound - aBound;

        const aIs8 = String(a.pin).length === 8 ? 1 : 0;
        const bIs8 = String(b.pin).length === 8 ? 1 : 0;
        if (aIs8 !== bIs8) return bIs8 - aIs8;

        return (a.created_at || 0) - (b.created_at || 0);
      });

      const primary = group[0];
      const duplicates = group.slice(1);

      // Collect all unique URLs
      const urlSet = new Set();
      for (const u of group) {
        const list = (u.upstream_sub_urls || []).concat(
          u.upstream_sub_url ? u.upstream_sub_url.split(/[\r\n,]+/) : []
        );
        for (const item of list) {
          const t = item.trim();
          if (t.startsWith('http')) urlSet.add(t);
        }
      }

      // Collect all panel IDs
      const panelIdSet = new Set();
      for (const u of group) {
        (u.panel_ids || [u.panel_id].filter(Boolean)).forEach(p => panelIdSet.add(p));
      }

      primary.upstream_sub_urls = Array.from(urlSet);
      primary.upstream_sub_url = Array.from(urlSet).join('\n');
      primary.panel_ids = Array.from(panelIdSet);
      if (primary.panel_ids.length > 1) {
        primary.panel_type = 'multi';
      }

      // Expiry: keep unlimited (null) if any is unlimited, else max
      if (group.some(u => !u.expires_at)) {
        primary.expires_at = null;
      } else {
        primary.expires_at = Math.max(...group.map(u => u.expires_at));
      }

      primary.active = group.some(u => u.active !== false);

      await env.VPN_USERS.put(primary.pin, JSON.stringify(primary));

      for (const dup of duplicates) {
        await env.VPN_USERS.delete(dup.pin);
        removedPins.push(dup.pin);
        const idx = users.findIndex(u => u.pin === dup.pin);
        if (idx !== -1) users.splice(idx, 1);
      }

      mergedGroupsCount++;
    }

    return {
      success: true,
      merged_groups: mergedGroupsCount,
      removed_pins: removedPins
    };
  }

  /**
   * Sync all panels with autoSync !== false
   * Optimized to load users once instead of per panel
   */
  static async syncAllPanels(env) {
    const panels = await this.getPanels(env);
    let totalAdded = 0;
    let totalUpdated = 0;

    // Load existing users once for all panels
    const kvList = await env.VPN_USERS.list();
    const existingUsers = [];
    for (const k of kvList.keys) {
      if (!/^\d+$/.test(k.name)) continue;
      const raw = await env.VPN_USERS.get(k.name);
      if (raw) {
        try {
          const u = JSON.parse(raw);
          if (u.pin) existingUsers.push(u);
        } catch (e) {}
      }
    }

    // Merge any existing duplicate names before syncing panels
    await this.mergeDuplicateUsers(env, existingUsers);

    for (const panel of panels) {
      if (panel.autoSync !== false) {
        try {
          const res = await this.syncPanel(env, panel.id, existingUsers);
          totalAdded += res.added;
          totalUpdated += res.updated;
        } catch (e) {
          console.error(`Failed to auto-sync panel ${panel.name}:`, e);
        }
      }
    }

    // Ensure all users are provisioned across ALL active panels
    let crossSynced = 0;
    try {
      const syncResult = await this.syncUsersToAllPanels(env);
      crossSynced = syncResult.syncedUsers || 0;
    } catch (crossErr) {
      console.warn('Cross-panel user sync error:', crossErr.message);
    }

    return { success: true, totalAdded, totalUpdated, crossSynced };
  }

  /**
   * Provisions all existing users across ALL active panels
   * so that every user has working subscription links on every panel in priority order.
   */
  static async syncUsersToAllPanels(env) {
    const panels = await this.getPanels(env);
    const activePanels = panels.filter(p => p.autoSync !== false);
    const activePanelIds = activePanels.map(p => p.id);

    // Prefetch all active panels' remote users ONCE to prevent N*M redundant HTTP network calls
    const cachedRemoteUsersMap = {};
    for (const panel of activePanels) {
      try {
        cachedRemoteUsersMap[panel.id] = await this.fetchRemoteUsers(panel);
      } catch (e) {
        console.warn(`Failed to prefetch remote users for panel ${panel.name}:`, e.message);
        cachedRemoteUsersMap[panel.id] = [];
      }
    }

    const kvList = await env.VPN_USERS.list();
    let syncedCount = 0;

    for (const key of kvList.keys) {
      if (!/^\d+$/.test(key.name)) continue;
      const raw = await env.VPN_USERS.get(key.name);
      if (!raw) continue;

      let user;
      try {
        user = JSON.parse(raw);
      } catch (_) {
        continue;
      }

      user.remote_panel_mappings = user.remote_panel_mappings || {};
      let needsUpdate = false;

      // Fix any old dead IPs (13.140.94.235, 13.140.84.27) or wrong port 2096
      if (Array.isArray(user.upstream_sub_urls)) {
        user.upstream_sub_urls = user.upstream_sub_urls.map(u => {
          if (u.includes('13.140.94.235') || u.includes('13.140.84.27') || u.includes(':2096')) {
            needsUpdate = true;
            return u.replace(/13\.140\.(94\.235|84\.27)/g, '95.182.87.115').replace(':2096', ':2016');
          }
          return u;
        });
      }

      // Check if user is missing mapping for any active panel
      for (const panel of activePanels) {
        if (!user.remote_panel_mappings[panel.id] || !user.remote_panel_mappings[panel.id].subUrl) {
          needsUpdate = true;
          break;
        }
      }

      if (needsUpdate || !user.panel_ids || user.panel_ids.length < activePanelIds.length) {
        try {
          user = await this.assignPanelsToUser(env, user, activePanelIds, user.upstream_sub_urls || [], cachedRemoteUsersMap, false);
          await env.VPN_USERS.put(key.name, JSON.stringify(user));
          syncedCount++;
        } catch (e) {
          console.error(`Failed to sync user ${key.name} to all panels:`, e.message);
        }
      }
    }

    return { success: true, syncedUsers: syncedCount };
  }

  /**
   * Assigns panels to a user: resolves remote subscription URLs,
   * matches expiration to the later expiry date (higher days remained),
   * and sets panel_ids and multi-panel type.
   */
  static async assignPanelsToUser(env, userRecord, selectedPanelIds, manualSubUrls = [], preloadedRemoteUsersMap = null, allowAutoCreate = true) {
    if (!selectedPanelIds || !Array.isArray(selectedPanelIds)) {
      return userRecord;
    }

    const panels = await this.getPanels(env);
    const chosenPanels = panels.filter(p => selectedPanelIds.includes(p.id));

    const urlSet = new Set();
    const panelIdSet = new Set();
    let resolvedExpiry = (userRecord.expires_at && userRecord.expires_at > 0) ? userRecord.expires_at : null;

    userRecord.remote_panel_mappings = userRecord.remote_panel_mappings || {};
    const normName = (userRecord.name || '').trim().toLowerCase();

    // 1. Clean up remote users for panels that were unassigned
    for (const mappedPanelId of Object.keys(userRecord.remote_panel_mappings)) {
      if (!selectedPanelIds.includes(mappedPanelId)) {
        const unassignedPanel = panels.find(p => p.id === mappedPanelId);
        if (unassignedPanel) {
          try {
            await this.deleteRemoteUser(unassignedPanel, userRecord.remote_panel_mappings[mappedPanelId]);
          } catch (delErr) {
            console.warn(`Could not delete unassigned remote user on panel ${unassignedPanel.name}:`, delErr.message);
          }
        }
        delete userRecord.remote_panel_mappings[mappedPanelId];
      }
    }

    // 2. Process each chosen panel
    for (const panel of chosenPanels) {
      panelIdSet.add(panel.id);
      const pType = (panel.type || '').toLowerCase();

      if (pType === 'container' || pType === 'raw' || pType === 'xray_container') {
        if (panel.url && panel.url.startsWith('http')) {
          urlSet.add(panel.url.trim());
        }
      } else {
        // Standard API panel (PasarGuard, 3X-UI, Marzban, Hiddify, GoGuard)
        let resolvedSubUrl = null;
        const existingMapping = userRecord.remote_panel_mappings[panel.id];

        if (existingMapping && existingMapping.subUrl) {
          resolvedSubUrl = existingMapping.subUrl;
          if (existingMapping.expiresAt && existingMapping.expiresAt > 0) {
            resolvedExpiry = (resolvedExpiry !== null) ? Math.min(resolvedExpiry, existingMapping.expiresAt) : existingMapping.expiresAt;
          }
        } else {
          // Check if matching user already exists on the remote panel by name/username
          try {
            const remoteUsers = (preloadedRemoteUsersMap && preloadedRemoteUsersMap[panel.id])
              ? preloadedRemoteUsersMap[panel.id]
              : await this.fetchRemoteUsers(panel);
            const match = remoteUsers.find(ru => {
              const rName = (ru.name || '').trim().toLowerCase();
              const rUser = (ru.username || '').trim().toLowerCase();
              return (rName && rName === normName) || (rUser && rUser === normName) ||
                     (normName && (rName.includes(normName) || normName.includes(rName)));
            });

            if (match && match.subUrl) {
              resolvedSubUrl = match.subUrl.trim();
              userRecord.remote_panel_mappings[panel.id] = {
                username: match.username || match.name,
                subUrl: resolvedSubUrl,
                panelType: panel.type,
                expiresAt: match.expiresAt
              };
              if (match.expiresAt && match.expiresAt > 0) {
                resolvedExpiry = (resolvedExpiry !== null) ? Math.min(resolvedExpiry, match.expiresAt) : match.expiresAt;
              }
            }
          } catch (fetchErr) {
            console.warn(`Could not fetch remote users for panel ${panel.name}:`, fetchErr.message);
          }

          // If still not found on remote panel, CREATE USER ON REMOTE PANEL (if allowed)
          if (!resolvedSubUrl && allowAutoCreate) {
            try {
              console.log(`Auto-creating remote user on panel ${panel.name} (${panel.type}) for PIN ${userRecord.pin}...`);
              const created = await this.createRemoteUser(panel, userRecord);
              if (created && created.subUrl) {
                resolvedSubUrl = created.subUrl.trim();
                userRecord.remote_panel_mappings[panel.id] = {
                  username: created.username,
                  subUrl: resolvedSubUrl,
                  clientUuid: created.clientUuid,
                  inboundId: created.inboundId,
                  subId: created.subId,
                  id: created.id,
                  accessKey: created.accessKey,
                  panelType: panel.type,
                  expiresAt: created.expiresAt
                };
                if (created.expiresAt && created.expiresAt > 0) {
                  resolvedExpiry = (resolvedExpiry !== null) ? Math.min(resolvedExpiry, created.expiresAt) : created.expiresAt;
                }
              }
            } catch (createErr) {
              console.error(`Failed to auto-create user on panel ${panel.name}:`, createErr.message);
            }
          }
        }

        if (resolvedSubUrl) {
          urlSet.add(resolvedSubUrl);
        }
      }
    }

    // Add manual custom URLs if supplied
    if (Array.isArray(manualSubUrls)) {
      for (const u of manualSubUrls) {
        if (u && typeof u === 'string' && u.trim().startsWith('http')) {
          urlSet.add(u.trim());
        }
      }
    }

    const finalUrls = Array.from(urlSet);
    userRecord.panel_ids = Array.from(panelIdSet);
    userRecord.upstream_sub_urls = finalUrls;
    userRecord.upstream_sub_url = finalUrls.join('\n');
    userRecord.panel_type = panelIdSet.size > 1 ? 'multi' : (chosenPanels[0]?.type || 'raw');
    if (resolvedExpiry !== undefined && resolvedExpiry !== null) {
      userRecord.expires_at = resolvedExpiry;
    }

    return userRecord;
  }
}
