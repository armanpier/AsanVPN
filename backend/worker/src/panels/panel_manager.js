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

  const socket = connect(
    { hostname, port },
    { secureTransport: isTls ? 'on' : 'off', allowHalfOpen: false }
  );

  const writer = socket.writable.getWriter();
  const path = parsed.pathname + (parsed.search || '');
  const method = (options.method || 'GET').toUpperCase();
  const headers = options.headers || {};

  let requestText = `${method} ${path} HTTP/1.1\r\n`;
  requestText += `Host: ${hostname}:${port}\r\n`;
  requestText += `User-Agent: AsanVPN-Manager/2.0\r\n`;
  requestText += `Accept: application/json, text/plain, */*\r\n`;
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
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
  }

  const totalLen = chunks.reduce((acc, c) => acc + c.length, 0);
  const fullBytes = new Uint8Array(totalLen);
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

  if (!statusLine.includes(' 200 ') && !statusLine.includes(' 200 OK')) {
    throw new Error(`Socket upstream returned: ${statusLine} - ${bodyPart.slice(0, 150)}`);
  }

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
    return unchunked.trim();
  }

  return bodyPart.trim();
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
    id: 'panel_pasarguard_default',
    name: 'سرور پاسارگاد (PasarGuard)',
    type: 'pasarguard',
    url: 'https://irtun.darklink.ir:8000',
    apiKey: 'pg_key_f85f5abb-13cc-4dc5-b3fc-8a77c2026e94',
    subBaseUrl: 'https://irsub.trafficfast.ir:9090',
    autoSync: true,
    lastSync: null,
    lastSyncStatus: null,
    lastSyncMessage: null,
    userCount: 2,
    createdAt: 1792620000
  },
  {
    id: 'panel_3xui_default',
    name: 'سرور ۳X-UI اصلی',
    type: '3xui',
    url: 'https://13.140.84.27:14393/eX6Vvc2csV2GKtzmPF',
    apiKey: 'Wg6BrJKRiJzcNr4NKnjlxjZSAxMUIYkh7lzvWUDUQn3gxmW9',
    subBaseUrl: 'https://13.140.84.27:2096/xx11fs28c0ea3gr5/{subId}',
    autoSync: true,
    lastSync: null,
    lastSyncStatus: null,
    lastSyncMessage: null,
    userCount: 2,
    createdAt: 1792620000
  },
  {
    id: 'panel_goguard_default',
    name: 'سرور گوگارد (GoGuard Core)',
    type: 'goguard',
    url: 'https://core.erfjab.com',
    apiKey: '7c704f070ae553721bfa91b4bcfeb84e2eaa8109d09e56d00b471e9001ed5206',
    subBaseUrl: '',
    autoSync: true,
    lastSync: null,
    lastSyncStatus: null,
    lastSyncMessage: null,
    userCount: 7,
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

    // Auto-migrate any stored old VPS IP references to the new VPS IP
    for (const p of panels) {
      if (p.url && p.url.includes('13.140.94.235')) {
        p.url = p.url.replace(/13\.140\.94\.235/g, '13.140.84.27');
        needsSave = true;
      }
      if (p.subBaseUrl && p.subBaseUrl.includes('13.140.94.235')) {
        p.subBaseUrl = p.subBaseUrl.replace(/13\.140\.94\.235/g, '13.140.84.27');
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
        autoSync: true,
        lastSync: null,
        lastSyncStatus: null,
        lastSyncMessage: null,
        userCount: 7,
        createdAt: 1792621000
      });
      needsSave = true;
    }

    if (needsSave) {
      await env.VPN_USERS.put('__CONFIG_PANELS__', JSON.stringify(panels));
    }

    return panels;
  }

  static async savePanels(env, panels) {
    await env.VPN_USERS.put('__CONFIG_PANELS__', JSON.stringify(panels));
  }

  static async addOrUpdatePanel(env, panelData) {
    const panels = await this.getPanels(env);
    const id = panelData.id || `panel_${Date.now()}`;
    const cleanUrl = (panelData.url || '').trim().replace(/\/+$/, '');

    const newPanel = {
      id: id,
      name: panelData.name || 'پنل بدون نام',
      type: (panelData.type || 'pasarguard').toLowerCase(),
      url: cleanUrl,
      apiKey: (panelData.apiKey || panelData.api_key || '').trim(),
      subBaseUrl: (panelData.subBaseUrl || panelData.sub_base_url || '').trim(),
      autoSync: panelData.autoSync !== false && panelData.auto_sync !== false,
      lastSync: panelData.lastSync || null,
      lastSyncStatus: panelData.lastSyncStatus || null,
      lastSyncMessage: panelData.lastSyncMessage || null,
      userCount: panelData.userCount || 0,
      createdAt: panelData.createdAt || Math.floor(Date.now() / 1000)
    };

    const existingIndex = panels.findIndex(p => p.id === id);
    if (existingIndex >= 0) {
      panels[existingIndex] = { ...panels[existingIndex], ...newPanel };
    } else {
      panels.push(newPanel);
    }

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

    return { success: true, totalAdded, totalUpdated };
  }

  /**
   * Assigns panels to a user: resolves remote subscription URLs,
   * matches expiration to the later expiry date (higher days remained),
   * and sets panel_ids and multi-panel type.
   */
  static async assignPanelsToUser(env, userRecord, selectedPanelIds, manualSubUrls = []) {
    if (!selectedPanelIds || !Array.isArray(selectedPanelIds)) {
      return userRecord;
    }

    const panels = await this.getPanels(env);
    const chosenPanels = panels.filter(p => selectedPanelIds.includes(p.id));

    const urlSet = new Set();
    const panelIdSet = new Set();
    let maxExpiry = userRecord.expires_at || null;

    const normName = (userRecord.name || '').trim().toLowerCase();

    for (const panel of chosenPanels) {
      panelIdSet.add(panel.id);
      const pType = (panel.type || '').toLowerCase();

      if (pType === 'container' || pType === 'raw' || pType === 'xray_container') {
        if (panel.url && panel.url.startsWith('http')) {
          urlSet.add(panel.url.trim());
        }
      } else {
        // Standard API panel
        try {
          const remoteUsers = await this.fetchRemoteUsers(panel);
          const match = remoteUsers.find(ru => {
            const rName = (ru.name || '').trim().toLowerCase();
            const rUser = (ru.username || '').trim().toLowerCase();
            return (rName && rName === normName) || (rUser && rUser === normName) ||
                   (normName && (rName.includes(normName) || normName.includes(rName)));
          });
          if (match && match.subUrl) {
            urlSet.add(match.subUrl.trim());
            // Match to later expiry date (higher days remained)
            if (match.expiresAt === 0 || match.expiresAt === null) {
              maxExpiry = null; // unlimited
            } else if (match.expiresAt && maxExpiry !== null) {
              maxExpiry = Math.max(maxExpiry, match.expiresAt);
            }
          }
        } catch (e) {
          console.warn(`Could not fetch remote users for panel ${panel.name}:`, e.message);
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
    if (maxExpiry !== undefined) {
      userRecord.expires_at = maxExpiry;
    }

    return userRecord;
  }
}
