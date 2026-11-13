import { parseSubscriptionText } from './parsers/sub_parser.js';
import { generateXrayConfig, generateSingBoxConfig } from './parsers/config_generator.js';
import { PanelAdapter } from './panels/panel_adapter.js';
import { PanelManager, universalFetch } from './panels/panel_manager.js';
import { DeviceLock } from './auth/device_lock.js';
import { getAdminDashboardHtml } from './admin_ui.js';
import { getWebAppHtml } from './webapp_ui.js';
import { getOpenAppHtml } from './open_ui.js';

// Module-level in-memory cache for Worker isolates to drastically reduce KV operations
const memoryUserCache = new Map(); // pin -> { data: userObject, exp: timestamp }
const MEMORY_USER_CACHE_TTL_MS = 120 * 1000; // 2 minutes in-memory cache per isolate

async function syncToVps(key, value) {
  try {
    const adminToken = "Arman4237340";
    const edgeSecret = "asanvpn-edge-internal-secret-2026";
    if (/^(\d{8}|\d{10})$/.test(key)) {
      const parsed = typeof value === 'string' ? JSON.parse(value) : value;
      await fetch("https://asanvpn-dashboard.armanpier-oxvrx.arvanedge.ir/api/v1/admin/user", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Admin-Token": adminToken, "X-From-Worker": "1", "X-Edge-Secret": edgeSecret },
        body: JSON.stringify(parsed)
      });
    } else if (key === '__CONFIG_PANELS__') {
      const parsed = typeof value === 'string' ? JSON.parse(value) : value;
      await fetch("https://asanvpn-dashboard.armanpier-oxvrx.arvanedge.ir/api/v1/admin/panels", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Admin-Token": adminToken, "X-From-Worker": "1", "X-Edge-Secret": edgeSecret },
        body: JSON.stringify({ panels: parsed })
      });
    }
  } catch (e) {
    console.warn("VPS sync failed:", e?.message);
  }
}

async function getCachedUserRecord(env, pin) {
  const cached = memoryUserCache.get(pin);
  if (cached && Date.now() < cached.exp) {
    return cached.data;
  }
  try {
    const raw = await env.VPN_USERS.get(pin);
    if (raw) {
      const parsed = JSON.parse(raw);
      memoryUserCache.set(pin, { data: parsed, exp: Date.now() + MEMORY_USER_CACHE_TTL_MS });
      return parsed;
    }
  } catch (err) {
    console.warn(`KV get error for ${pin}:`, err?.message);
  }

  // Fallback to VPS SQLite via Arvan Edge Gateway if not in KV (e.g. created during KV quota exhaustion)
  try {
    const vpsResp = await fetch(`https://asanvpn-dashboard.armanpier-oxvrx.arvanedge.ir/api/v1/admin/user?pin=${pin}`, {
      headers: { 
        "X-Admin-Token": "Arman4237340", 
        "X-From-Worker": "1",
        "X-Edge-Secret": "asanvpn-edge-internal-secret-2026"
      }
    });
    if (vpsResp && vpsResp.status === 200) {
      const vpsData = await vpsResp.json();
      if (vpsData && vpsData.user) {
        memoryUserCache.set(pin, { data: vpsData.user, exp: Date.now() + MEMORY_USER_CACHE_TTL_MS });
        return vpsData.user;
      }
    }
  } catch (_) {}

  return null;
}

function updateCachedUserRecord(pin, user) {
  if (user) {
    memoryUserCache.set(pin, { data: user, exp: Date.now() + MEMORY_USER_CACHE_TTL_MS });
  } else {
    memoryUserCache.delete(pin);
  }
}

async function safeKvPut(env, key, value, options) {
  // 1. Always update in-memory cache for fast lookups
  try {
    if (/^(\d{8}|\d{10})$/.test(key)) {
      const parsed = typeof value === 'string' ? JSON.parse(value) : value;
      updateCachedUserRecord(key, parsed);
    }
  } catch (_) {}

  // 2. Dual-Cloud Mirror: Sync to VPS SQLite
  try {
    syncToVps(key, value).catch(() => {});
  } catch (_) {}

  // 3. Put to Cloudflare KV (gracefully suppress daily quota exhaustion)
  try {
    if (options) {
      await env.VPN_USERS.put(key, value, options);
    } else {
      await env.VPN_USERS.put(key, value);
    }
    return true;
  } catch (err) {
    console.warn(`Safe KV put suppressed for key ${key}: ${err?.message || err}`);
    return true; // Return true so caller does not abort when KV hits daily limit!
  }
}

async function safeKvDelete(env, key) {
  try {
    await env.VPN_USERS.delete(key);
    memoryUserCache.delete(key);
    return true;
  } catch (err) {
    console.warn(`Safe KV delete suppressed for key ${key}: ${err?.message || err}`);
    return false;
  }
}

/**
 * Validates if an 8-digit PIN is cryptographically strong and non-guessable.
 * Strictly rejects:
 * 1. Numbers with fewer than 5 unique digits (e.g. 88888888, 11112222, 12121212)
 * 2. 3 or more consecutive identical digits (e.g. 888, 000, 111)
 * 3. 3 or more ascending or descending sequential digits (e.g. 123, 789, 987, 321)
 * 4. Periodic repeating chunks (e.g. ABCDABCD like 84128412, ABABABAB)
 * 5. Repetitive doubled pairs (e.g. 11223344)
 * 6. Palindromes (e.g. 12344321)
 * 7. Common keypad walks and trivial patterns
 */
function isSecure8DigitPin(pin) {
  if (typeof pin !== 'string' || !/^\d{8}$/.test(pin)) {
    return false;
  }

  // 1. Must contain at least 5 distinct digits
  const uniqueDigits = new Set(pin.split('')).size;
  if (uniqueDigits < 5) return false;

  // 2. No 3 or more consecutive identical digits (e.g. 888, 000, 111)
  if (/(.)\1{2,}/.test(pin)) return false;

  // 3. No 3 or more consecutive sequential digits (e.g. 123, 789, 987, 321)
  for (let i = 0; i < pin.length - 2; i++) {
    const d1 = parseInt(pin[i], 10);
    const d2 = parseInt(pin[i + 1], 10);
    const d3 = parseInt(pin[i + 2], 10);
    if ((d2 === d1 + 1 && d3 === d2 + 1) || (d2 === d1 - 1 && d3 === d2 - 1)) {
      return false;
    }
  }

  // 4. No periodic repeating chunks (e.g. ABABABAB, ABCDABCD like 84128412)
  const half1 = pin.slice(0, 4);
  const half2 = pin.slice(4);
  if (half1 === half2) return false;

  const p2 = pin.slice(0, 2);
  if (p2.repeat(4) === pin) return false;

  // 5. No repetitive doubled pairs (e.g. 11223344)
  if (pin[0] === pin[1] && pin[2] === pin[3] && pin[4] === pin[5] && pin[6] === pin[7]) {
    return false;
  }

  // 6. No palindromes (e.g. 12344321)
  if (pin === pin.split('').reverse().join('')) {
    return false;
  }

  // 7. Common keypad walks & trivial patterns
  const trivial = [
    '14725836', '25802580', '13791379', '97319731', '15935700', '15975300',
    '36925814', '74185296', '96385274', '12457800', '08527410'
  ];
  if (trivial.includes(pin)) return false;

  return true;
}

/**
 * Validates if a 10-digit PIN is cryptographically strong and non-guessable.
 */
function isSecure10DigitPin(pin) {
  if (typeof pin !== 'string' || !/^\d{10}$/.test(pin)) {
    return false;
  }

  // 1. Must contain at least 6 distinct digits
  const uniqueDigits = new Set(pin.split('')).size;
  if (uniqueDigits < 6) return false;

  // 2. No 3 or more consecutive identical digits (e.g. 888, 000, 111)
  if (/(.)\1{2,}/.test(pin)) return false;

  // 3. No 3 or more consecutive sequential digits (e.g. 123, 789, 987, 321)
  for (let i = 0; i < pin.length - 2; i++) {
    const d1 = parseInt(pin[i], 10);
    const d2 = parseInt(pin[i + 1], 10);
    const d3 = parseInt(pin[i + 2], 10);
    if ((d2 === d1 + 1 && d3 === d2 + 1) || (d2 === d1 - 1 && d3 === d2 - 1)) {
      return false;
    }
  }

  // 4. No periodic repeating chunks (e.g. ABCDEABCDE, ABABABABAB)
  const half1 = pin.slice(0, 5);
  const half2 = pin.slice(5);
  if (half1 === half2) return false;

  const p2 = pin.slice(0, 2);
  if (p2.repeat(5) === pin) return false;

  // 5. No repetitive doubled pairs (e.g. 1122334455)
  if (pin[0] === pin[1] && pin[2] === pin[3] && pin[4] === pin[5] && pin[6] === pin[7] && pin[8] === pin[9]) {
    return false;
  }

  // 6. No palindromes
  if (pin === pin.split('').reverse().join('')) {
    return false;
  }

  return true;
}

/**
 * Generates an unguessable, cryptographically secure 10-digit PIN.
 */
async function generateUniqueSecurePin(env, reservedPins = new Set()) {
  let attempts = 0;
  while (attempts < 200) {
    const array = new Uint32Array(2);
    crypto.getRandomValues(array);
    const high = BigInt(array[0]);
    const low = BigInt(array[1]);
    const combined = (high << 32n) | low;
    const num = (combined % 9000000000n) + 1000000000n;
    const candidate = num.toString();

    if (isSecure10DigitPin(candidate) && !reservedPins.has(candidate)) {
      const existing = await env.VPN_USERS.get(candidate);
      if (!existing) {
        return candidate;
      }
    }
    attempts++;
  }
  while (true) {
    const candidate = String(Math.floor(1000000000 + Math.random() * 9000000000));
    if (isSecure10DigitPin(candidate) && !reservedPins.has(candidate)) {
      const existing = await env.VPN_USERS.get(candidate);
      if (!existing) {
        return candidate;
      }
    }
  }
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    // CORS headers for all responses
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, X-Admin-Token',
      'Content-Type': 'application/json; charset=utf-8'
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    if (path === '/api/debug-headers') {
      const h = {};
      for (const [k, v] of request.headers.entries()) {
        h[k] = v;
      }
      return new Response(JSON.stringify(h), { headers: corsHeaders });
    }

    // Web Admin Dashboard UI (Serve HTML to browser)
    if ((path === '/' || path === '/admin' || path === '/dashboard') && (request.method === 'GET' || request.method === 'HEAD')) {
      const acceptHeader = request.headers.get('Accept') || '';
      if (acceptHeader.includes('text/html') || path === '/admin' || path === '/dashboard' || path === '/') {
        return new Response(request.method === 'HEAD' ? null : getAdminDashboardHtml(), {
          headers: {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'no-cache'
          }
        });
      }
    }

    // Telegram Mini App WebApp Menu (Serve Colored Palette WebApp)
    if ((path === '/menu' || path === '/webapp') && (request.method === 'GET' || request.method === 'HEAD')) {
      return new Response(request.method === 'HEAD' ? null : getWebAppHtml(), {
        headers: {
          'Content-Type': 'text/html; charset=utf-8',
          'Cache-Control': 'public, max-age=60'
        }
      });
    }

    // Direct App Open & PIN Activation Link (/open?pin=12345678)
    if ((path === '/open' || path === '/connect') && (request.method === 'GET' || request.method === 'HEAD')) {
      const pinParam = url.searchParams.get('pin') || '';
      return new Response(request.method === 'HEAD' ? null : getOpenAppHtml(pinParam), {
        headers: {
          'Content-Type': 'text/html; charset=utf-8',
          'Cache-Control': 'no-cache, no-store, must-revalidate'
        }
      });
    }

    // Health check (JSON)
    if (path === '/health') {
      return new Response(JSON.stringify({
        service: 'AsanVPN Middleware',
        status: 'online',
        timestamp: Math.floor(Date.now() / 1000)
      }), { headers: corsHeaders });
    }

    // App Version & In-App Update API Endpoint
    if ((path === '/api/v1/app/version' || path === '/api/v1/version' || path === '/version') && (request.method === 'GET' || request.method === 'HEAD')) {
      let rawMeta = null;
      try {
        rawMeta = await env.VPN_USERS.get('apk_meta');
      } catch (kvErr) {}

      let version = "1.3.12";
      let versionCode = 18;
      let changelog = "طراحی سه‌بعدی لوکس جدید با دو تم روشن و تاریک (Porcelain و Obsidian)، گوی مغناطیسی چندلایه، پایداری بالاتر اتصال";
      let totalSize = 60619980;
      let githubUrl = "https://github.com/armanpier/AsanVPN/releases/download/v1.3.12/AsanVPN-v1.3.12-universal.apk";

      if (rawMeta) {
        try {
          const meta = JSON.parse(rawMeta);
          if (meta.versionCode && meta.versionCode > versionCode) {
            version = meta.version;
            versionCode = meta.versionCode;
            if (meta.total_size) totalSize = meta.total_size;
            if (meta.changelog) changelog = meta.changelog;
            if (meta.github_url) githubUrl = meta.github_url;
          }
        } catch (e) {}
      }
      return new Response(JSON.stringify({
        success: true,
        versionName: version,
        versionCode: versionCode,
        totalSize: totalSize,
        downloadUrl: "https://asanvpn-dashboard.armanpier-oxvrx.arvanedge.ir/download",
        fallbackUrl: "http://95.182.87.115/download",
        githubUrl: githubUrl,
        releaseNotes: changelog,
        publishedAt: Math.floor(Date.now() / 1000)
      }), {
        headers: {
          'Content-Type': 'application/json',
          'Access-Control-Allow-Origin': '*',
          'Cache-Control': 'no-cache, no-store, must-revalidate'
        }
      });
    }

    // Direct APK Download Endpoint (Hosted directly on Cloudflare Worker CDN with Fast CDN Redirect)
    if ((path === '/download' || path === '/AsanVPN.apk' || path === '/apk') && (request.method === 'GET' || request.method === 'HEAD')) {
      let rawMeta = null;
      try {
        rawMeta = await env.VPN_USERS.get('apk_meta');
      } catch (kvErr) {}

      let meta = null;
      if (rawMeta) {
        try { meta = JSON.parse(rawMeta); } catch (e) {}
      }

      const urlObj = new URL(request.url);
      const forceKv = urlObj.searchParams.get('source') === 'kv';
      const domesticUrl = "https://asanvpn-dashboard.armanpier-oxvrx.arvanedge.ir/download";
      const targetGithubUrl = (meta && meta.versionCode && meta.versionCode >= 18 && meta.github_url) || "https://github.com/armanpier/AsanVPN/releases/download/v1.3.12/AsanVPN-v1.3.12-universal.apk";

      // If source=github is explicitly requested, redirect to GitHub
      if (urlObj.searchParams.get('source') === 'github') {
        return Response.redirect(targetGithubUrl, 302);
      }

      // If not explicitly forced to KV, redirect to Iranian domestic high-speed ArvanCloud Edge (no VPN required, ping <20ms)
      if (!forceKv) {
        return Response.redirect(domesticUrl, 302);
      }

      if (!meta) {
        return Response.redirect(targetGithubUrl, 302);
      }

      if (request.method === 'HEAD') {
        return new Response(null, {
          headers: {
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Disposition': `attachment; filename="${meta.filename || 'AsanVPN.apk'}"`,
            'Content-Length': meta.total_size.toString(),
            'Cache-Control': 'public, max-age=86400',
            'Access-Control-Allow-Origin': '*'
          }
        });
      }

      // Stream concatenated chunks sequentially
      const { readable, writable } = new TransformStream();
      (async () => {
        const writer = writable.getWriter();
        try {
          for (let i = 0; i < meta.total_chunks; i++) {
            const chunk = await env.VPN_USERS.get(`apk_chunk_${i}`, { type: 'arrayBuffer' });
            if (chunk) {
              await writer.write(new Uint8Array(chunk));
            }
          }
        } catch (streamErr) {
          console.error('Error streaming APK chunk:', streamErr);
        } finally {
          await writer.close();
        }
      })();

      return new Response(readable, {
        headers: {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Disposition': `attachment; filename="${meta.filename || 'AsanVPN.apk'}"`,
          'Content-Length': meta.total_size.toString(),
          'X-Total-Length': meta.total_size.toString(),
          'X-File-Size': meta.total_size.toString(),
          'Cache-Control': 'public, max-age=86400',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }

    const supportPhone = env.DEFAULT_SUPPORT_PHONE || '+989120000000';
    const supportTelegram = env.SUPPORT_TELEGRAM || 'AsanSupport';

    try {
      // -------------------------------------------------------------
      // 0. CLIENT LOCKOUT CHECK ENDPOINT: POST /api/v1/check_lockout
      // -------------------------------------------------------------
      if (path === '/api/v1/check_lockout' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const pin = String(body.pin || '').trim();
        const deviceId = String(body.device_id || body.deviceId || '').trim();
        const now = Math.floor(Date.now() / 1000);

        const clientIp = request.headers.get('cf-connecting-ip') || 
                         request.headers.get('x-real-ip') || 
                         request.headers.get('x-forwarded-for')?.split(',')[0]?.trim() || 
                         'unknown';
        const ipSubnet = clientIp.includes('.') ? clientIp.split('.').slice(0, 3).join('.') + '.0/24' : clientIp;
        const rlIpKey = `rl:ip:${ipSubnet}`;
        const rlDevKey = deviceId ? `rl:dev:${deviceId}` : null;
        const legacyRlKey = deviceId ? `rl:${deviceId}` : null;

        let adminUnlocked = false;
        if (pin) {
          const rawUser = await env.VPN_USERS.get(pin);
          if (rawUser) {
            try {
              const u = JSON.parse(rawUser);
              if (u.lockout_cleared === true || (u.lockout_cleared_at && u.lockout_cleared_at > (now - 86400))) {
                adminUnlocked = true;
                if (deviceId) {
                  memoryUserCache.delete(rlDevKey);
                  memoryUserCache.delete(legacyRlKey);
                  await safeKvDelete(env, rlDevKey);
                  await safeKvDelete(env, legacyRlKey);
                }
              }
            } catch (_) {}
          }
        }

        let isLocked = false;
        let remainingSec = 0;

        if (!adminUnlocked) {
          let devRlData = null;
          let ipRlData = null;

          if (rlDevKey) {
            const cachedDev = memoryUserCache.get(rlDevKey);
            if (cachedDev && cachedDev.data && cachedDev.data.locked_until > now) {
              devRlData = cachedDev.data;
            } else {
              const rawDev = await env.VPN_USERS.get(rlDevKey);
              if (rawDev) {
                try {
                  const parsed = JSON.parse(rawDev);
                  if (parsed.locked_until > now) devRlData = parsed;
                } catch (_) {}
              }
            }
          }

          if (!devRlData && legacyRlKey) {
            const rawLegacy = await env.VPN_USERS.get(legacyRlKey);
            if (rawLegacy) {
              try {
                const parsed = JSON.parse(rawLegacy);
                if (parsed.locked_until > now) devRlData = parsed;
              } catch (_) {}
            }
          }

          if (rlIpKey) {
            const cachedIp = memoryUserCache.get(rlIpKey);
            if (cachedIp && cachedIp.data && cachedIp.data.locked_until > now) {
              ipRlData = cachedIp.data;
            } else {
              const rawIp = await env.VPN_USERS.get(rlIpKey);
              if (rawIp) {
                try {
                  const parsed = JSON.parse(rawIp);
                  if (parsed.locked_until > now) ipRlData = parsed;
                } catch (_) {}
              }
            }
          }

          const lockUntil = Math.max(devRlData?.locked_until || 0, ipRlData?.locked_until || 0);
          if (lockUntil > now) {
            isLocked = true;
            remainingSec = lockUntil - now;
          }
        }

        return new Response(JSON.stringify({
          status: 'success',
          locked: isLocked && !adminUnlocked,
          remaining_seconds: isLocked && !adminUnlocked ? remainingSec : 0,
          admin_unlocked: adminUnlocked,
          message_fa: adminUnlocked ? 'قفل ورود توسط مدیر بازگشایی شد.' : (isLocked ? 'حساب هنوز مسدود است.' : 'حساب فعال است.')
        }), { status: 200, headers: corsHeaders });
      }

      // -------------------------------------------------------------
      // 1. CLIENT CONNECTION ENDPOINT: POST /api/v1/connect
      // -------------------------------------------------------------
      if (path === '/api/v1/connect' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const pin = String(body.pin || '').trim();
        const deviceId = String(body.device_id || body.deviceId || '').trim();

        if (!pin || !deviceId) {
          return new Response(JSON.stringify({
            status: 'error',
            message_fa: 'کد اشتراک و شناسه دستگاه الزامی است.',
            support_phone: supportPhone
          }), { status: 400, headers: corsHeaders });
        }

        // Enforce 10-digit PIN (or 8-digit for backward compatibility)
        if (!/^\d{10}$/.test(pin) && !/^\d{8}$/.test(pin)) {
          return new Response(JSON.stringify({
            status: 'invalid_pin',
            message_fa: 'کد اشتراک باید یک عدد ۱۰ رقمی (یا ۸ رقمی) باشد.',
            support_phone: supportPhone,
            support_telegram: supportTelegram
          }), { status: 403, headers: corsHeaders });
        }

        const now = Math.floor(Date.now() / 1000);

        // Fetch user from in-memory cache or single KV read (eliminates 50% of user KV reads)
        const user = await getCachedUserRecord(env, pin);

        // Check if admin cleared lockout for this user
        let userWasAdminUnlocked = false;
        if (user) {
          if (user.lockout_cleared === true || (user.lockout_cleared_at && user.lockout_cleared_at > (now - 86400))) {
            userWasAdminUnlocked = true;
          }
        }

        // --- Rate Limiting by BOTH Network Identity (Client IP / Subnet) AND Device ID ---
        const clientIp = request.headers.get('cf-connecting-ip') || 
                         request.headers.get('x-real-ip') || 
                         request.headers.get('x-forwarded-for')?.split(',')[0]?.trim() || 
                         'unknown';
        const ipSubnet = clientIp.includes('.') ? clientIp.split('.').slice(0, 3).join('.') + '.0/24' : clientIp;
        const rlIpKey = `rl:ip:${ipSubnet}`;
        const rlDevKey = `rl:dev:${deviceId}`;

        let ipRlData = { count: 0, locked_until: 0 };
        let devRlData = { count: 0, locked_until: 0 };

        if (!userWasAdminUnlocked) {
          // Check IP rate limit in memory & KV
          const cachedIpRl = memoryUserCache.get(rlIpKey);
          if (cachedIpRl && Date.now() < cachedIpRl.exp) {
            ipRlData = cachedIpRl.data;
          } else {
            const rawIpRl = await env.VPN_USERS.get(rlIpKey);
            if (rawIpRl) {
              try {
                ipRlData = JSON.parse(rawIpRl);
                memoryUserCache.set(rlIpKey, { data: ipRlData, exp: Date.now() + 60000 });
              } catch (_) {}
            }
          }

          // Check Device rate limit in memory & KV
          const cachedDevRl = memoryUserCache.get(rlDevKey);
          if (cachedDevRl && Date.now() < cachedDevRl.exp) {
            devRlData = cachedDevRl.data;
          } else {
            const rawDevRl = await env.VPN_USERS.get(rlDevKey);
            if (rawDevRl) {
              try {
                devRlData = JSON.parse(rawDevRl);
                memoryUserCache.set(rlDevKey, { data: devRlData, exp: Date.now() + 60000 });
              } catch (_) {}
            }
          }
        }

        const isIpLocked = !userWasAdminUnlocked && ipRlData.locked_until && ipRlData.locked_until > now;
        const isDevLocked = !userWasAdminUnlocked && devRlData.locked_until && devRlData.locked_until > now;

        if (isIpLocked || isDevLocked) {
          const lockedUntil = Math.max(ipRlData.locked_until || 0, devRlData.locked_until || 0);
          const remainingSec = lockedUntil - now;
          const remainingMin = Math.ceil(remainingSec / 60);
          return new Response(JSON.stringify({
            status: 'rate_limited',
            message_fa: `تعداد تلاش‌های ناموفق بیش از ۳ بار بوده است. لطفاً ${remainingMin} دقیقه دیگر تلاش کنید.`,
            retry_after_seconds: remainingSec,
            locked_until_timestamp: lockedUntil * 1000,
            support_phone: supportPhone,
            support_telegram: supportTelegram
          }), { status: 429, headers: corsHeaders });
        }

        if (!user) {
          const devCount = (devRlData.count || 0) + 1;
          const ipCount = (ipRlData.count || 0) + 1;
          devRlData.count = devCount;
          ipRlData.count = ipCount;

          if (devCount >= 3 || ipCount >= 5) {
            const lockUntil = now + 1800; // 30 minutes lockout
            devRlData.locked_until = lockUntil;
            ipRlData.locked_until = lockUntil;
            memoryUserCache.set(rlDevKey, { data: devRlData, exp: Date.now() + 1800000 });
            memoryUserCache.set(rlIpKey, { data: ipRlData, exp: Date.now() + 1800000 });
            await safeKvPut(env, rlDevKey, JSON.stringify(devRlData), { expirationTtl: 3600 });
            await safeKvPut(env, rlIpKey, JSON.stringify(ipRlData), { expirationTtl: 3600 });
            return new Response(JSON.stringify({
              status: 'rate_limited',
              message_fa: 'تعداد تلاش‌های ناموفق به ۳ بار رسید. ورود به مدت ۳۰ دقیقه مسدود شد.',
              retry_after_seconds: 1800,
              locked_until_timestamp: lockUntil * 1000,
              support_phone: supportPhone,
              support_telegram: supportTelegram
            }), { status: 429, headers: corsHeaders });
          } else {
            memoryUserCache.set(rlDevKey, { data: devRlData, exp: Date.now() + 60000 });
            memoryUserCache.set(rlIpKey, { data: ipRlData, exp: Date.now() + 60000 });
            await safeKvPut(env, rlDevKey, JSON.stringify(devRlData), { expirationTtl: 3600 });
            await safeKvPut(env, rlIpKey, JSON.stringify(ipRlData), { expirationTtl: 3600 });
            const remaining = Math.max(0, 3 - devCount);
            return new Response(JSON.stringify({
              status: 'invalid_pin',
              message_fa: `کد اشتراک نامعتبر است. (${remaining} فرصت باقی مانده)`,
              remaining_attempts: remaining,
              support_phone: supportPhone,
              support_telegram: supportTelegram
            }), { status: 403, headers: corsHeaders });
          }
        }

        // PIN is valid! Clear rate limit only if it actually had failure attempts (avoids useless KV deletes)
        if (devRlData.count > 0 || ipRlData.count > 0 || userWasAdminUnlocked) {
          memoryUserCache.delete(rlDevKey);
          memoryUserCache.delete(rlIpKey);
          await safeKvDelete(env, rlDevKey);
          await safeKvDelete(env, rlIpKey);
        }

        // Check if disabled
        if (user.active === false) {
          return new Response(JSON.stringify({
            status: 'disabled',
            message_fa: 'اشتراک شما توسط مدیر غیرفعال شده است.',
            support_phone: supportPhone,
            support_telegram: supportTelegram
          }), { status: 403, headers: corsHeaders });
        }

        // Check expiration (only if expires_at is set to a valid positive timestamp)
        if (user.expires_at && user.expires_at > 0 && user.expires_at < now) {
          return new Response(JSON.stringify({
            status: 'expired',
            message_fa: 'اشتراک شما به پایان رسیده است. جهت تمدید با پشتیبانی تماس بگیرید.',
            support_phone: supportPhone,
            support_telegram: supportTelegram
          }), { status: 403, headers: corsHeaders });
        }

        // Check traffic exhaustion (Argon optimization to prevent multi-panel leak)
        if (user.traffic_remaining_bytes !== undefined && user.traffic_remaining_bytes !== null && user.traffic_remaining_bytes !== -1 && user.traffic_remaining_bytes <= 0) {
          return new Response(JSON.stringify({
            status: 'expired',
            message_fa: 'حجم اشتراک شما به پایان رسیده است. جهت تمدید یا شارژ مجدد با پشتیبانی تماس بگیرید.',
            support_phone: supportPhone,
            support_telegram: supportTelegram
          }), { status: 403, headers: corsHeaders });
        }

        // Check / Bind Hardware Device Lock
        const lockResult = DeviceLock.verifyOrBind(user, deviceId);
        if (!lockResult.ok) {
          return new Response(JSON.stringify({
            status: 'device_locked',
            message_fa: lockResult.errorFa,
            support_phone: supportPhone,
            support_telegram: supportTelegram
          }), { status: 403, headers: corsHeaders });
        }

        // If newly bound, update KV
        if (lockResult.updatedRecord) {
          await safeKvPut(env, pin, JSON.stringify(lockResult.updatedRecord));
        }

        // Fetch upstream subscription content with KV cache resilience
        let nodes = null;
        try {
          const subResult = await PanelAdapter.fetchSubscription(user, env);
          const subText = typeof subResult === 'string' ? subResult : (subResult?.text || '');
          
          // Real-time update of traffic & expiration from upstream subscription-userinfo header
          if (subResult && subResult.userInfo) {
            const info = subResult.userInfo;
            let hasChanged = false;
            // 1. Traffic sync
            if (info.traffic_remaining_bytes !== undefined) {
              if (info.traffic_remaining_bytes === -1) {
                if (user.traffic_remaining_bytes !== -1 || user.traffic_total_bytes !== 0) {
                  user.traffic_remaining_bytes = -1;
                  user.traffic_total_bytes = 0;
                  hasChanged = true;
                }
              } else {
                const newRem = info.traffic_remaining_bytes;
                const newTotal = Math.max(user.traffic_total_bytes || 0, (info.total !== undefined && info.total > 0) ? info.total : 0);
                if (user.traffic_remaining_bytes !== newRem || user.traffic_total_bytes !== newTotal) {
                  user.traffic_remaining_bytes = newRem;
                  user.traffic_total_bytes = newTotal;
                  hasChanged = true;
                }
              }
            } else if (info.total !== undefined) {
              if (info.total === 0) {
                // 0 means UNLIMITED traffic
                if (user.traffic_remaining_bytes !== -1 || user.traffic_total_bytes !== 0) {
                  user.traffic_remaining_bytes = -1;
                  user.traffic_total_bytes = 0;
                  hasChanged = true;
                }
              } else {
                const used = (info.upload || 0) + (info.download || 0);
                const newTotal = Math.max(user.traffic_total_bytes || 0, info.total || 0);
                const rem = Math.max(0, newTotal - used);
                if (user.traffic_remaining_bytes !== rem || user.traffic_total_bytes !== newTotal) {
                  user.traffic_remaining_bytes = rem;
                  user.traffic_total_bytes = newTotal;
                  hasChanged = true;
                }
              }
            }

            // 2. Expiration sync: NEVER DOWNGRADE expiration if KV has a later expiration from user purchase!
            if (info.expire && info.expire > 1000000000) {
              const bestExpire = Math.max(user.expires_at || 0, info.expire);
              if (user.expires_at !== bestExpire) {
                user.expires_at = bestExpire;
                hasChanged = true;
              }
            } else if (info.days_left !== undefined && info.days_left > 0) {
              const calcExp = now + info.days_left * 86400;
              const bestExpire = Math.max(user.expires_at || 0, calcExp);
              if (user.expires_at !== bestExpire) {
                user.expires_at = bestExpire;
                hasChanged = true;
              }
            } else if (info.expire === 0 || info.days_left === -1) {
              // UNLIMITED validity
              if (user.expires_at !== null) {
                user.expires_at = null;
                hasChanged = true;
              }
            }
            if (hasChanged) {
              // Throttled KV write to strictly protect Cloudflare KV free tier (1,000 writes/day):
              // Only persist to KV if traffic changed by > 50MB, or reached 0, or expiration changed,
              // or more than 4 hours elapsed since last save.
              const lastSave = user.last_kv_save_at || 0;
              const timeSinceSave = now - lastSave;
              const lastSavedTraffic = user.last_saved_traffic_bytes !== undefined ? user.last_saved_traffic_bytes : (user.traffic_remaining_bytes || 0);
              const trafficDelta = Math.abs(lastSavedTraffic - (user.traffic_remaining_bytes || 0));

              const isSignificant = trafficDelta > 50 * 1024 * 1024 ||
                                    user.traffic_remaining_bytes === 0 ||
                                    user.traffic_remaining_bytes === -1 ||
                                    timeSinceSave > 14400; // 4 hours

              updateCachedUserRecord(pin, user);

              if (isSignificant) {
                user.last_kv_save_at = now;
                user.last_saved_traffic_bytes = user.traffic_remaining_bytes;
                updateCachedUserRecord(pin, user);
        await safeKvPut(env, pin, JSON.stringify(user));
                // Sync updated limits to other panels in the background
                if (ctx && typeof ctx.waitUntil === 'function') {
                  ctx.waitUntil(PanelManager.syncUserLimitsToPanels(env, user));
                } else {
                  PanelManager.syncUserLimitsToPanels(env, user).catch(() => {});
                }
              }
            }
          }

          if (subResult && Array.isArray(subResult.nodes) && subResult.nodes.length > 0) {
            nodes = subResult.nodes;
          } else {
            nodes = parseSubscriptionText(subText);
          }

          if (nodes && nodes.length > 0) {
            // Guarantee nodes are sorted by panel priority ascending
            nodes.sort((a, b) => (a.panel_priority || 99) - (b.panel_priority || 99));
            // Only save cached_nodes to KV if missing (avoids rewriting KV on every connect)
            if (!user.cached_nodes || user.cached_nodes.length === 0) {
              user.cached_nodes = nodes;
              updateCachedUserRecord(pin, user);
        await safeKvPut(env, pin, JSON.stringify(user));
            } else {
              user.cached_nodes = nodes;
            }
            updateCachedUserRecord(pin, user);
          }
        } catch (fetchErr) {
          console.error('Failed to fetch from panel:', fetchErr);
          // Resilient fallback: use cached nodes or bootstrap Reality nodes
          if (user.cached_nodes && user.cached_nodes.length > 0) {
            console.log('Serving resilient cached_nodes for PIN:', pin);
            nodes = user.cached_nodes;
          } else {
            console.log('Serving fallback bootstrap nodes for PIN:', pin);
            nodes = [
              {
                protocol: "vless",
                remark: "🇳🇴 Norway (سرور راه‌انداز)",
                server: "netshield.officialvpn.shop",
                port: 20125,
                uuid: "7e1a90d4-1f7c-48be-9b3f-1d8f6d7a4c9e",
                type: "tcp",
                security: "reality",
                sni: "speedtest.net",
                pbk: "1A8VfHw1l9sN0pX8zK3mQ7rT2vY4bC6xZ9jL5wP8dE",
                sid: "6a",
                spx: "/",
                flow: "xtls-rprx-vision",
                panel_priority: 1
              },
              {
                protocol: "vless",
                remark: "🇨🇿 Czechia (سرور راه‌انداز)",
                server: "netshield.officialvpn.shop",
                port: 20014,
                uuid: "7e1a90d4-1f7c-48be-9b3f-1d8f6d7a4c9e",
                type: "tcp",
                security: "reality",
                sni: "speedtest.net",
                pbk: "1A8VfHw1l9sN0pX8zK3mQ7rT2vY4bC6xZ9jL5wP8dE",
                sid: "6a",
                spx: "/",
                flow: "xtls-rprx-vision",
                panel_priority: 1
              }
            ];
          }
        }

        // Parse nodes fallback
        if (!nodes || nodes.length === 0) {
          if (user.cached_nodes && user.cached_nodes.length > 0) {
            nodes = user.cached_nodes;
          } else {
            nodes = [
              {
                protocol: "vless",
                remark: "🇳🇴 Norway (سرور راه‌انداز)",
                server: "netshield.officialvpn.shop",
                port: 20125,
                uuid: "7e1a90d4-1f7c-48be-9b3f-1d8f6d7a4c9e",
                type: "tcp",
                security: "reality",
                sni: "speedtest.net",
                pbk: "1A8VfHw1l9sN0pX8zK3mQ7rT2vY4bC6xZ9jL5wP8dE",
                sid: "6a",
                spx: "/",
                flow: "xtls-rprx-vision",
                panel_priority: 1
              }
            ];
          }
        }

        // Guarantee all nodes have integer panel_priority and are sorted strictly by priority
        if (Array.isArray(nodes)) {
          nodes.forEach(n => {
            if (typeof n.panel_priority !== 'number' || isNaN(n.panel_priority) || n.panel_priority < 1) {
              n.panel_priority = 1;
            }
          });
          nodes.sort((a, b) => (a.panel_priority || 99) - (b.panel_priority || 99));
        }

        // Generate configurations for both Xray and Sing-box
        const primaryNode = nodes[0];
        const xrayConfig = generateXrayConfig(primaryNode, nodes);
        const singboxConfig = generateSingBoxConfig(primaryNode);

        const daysLeft = (user.expires_at && user.expires_at > 0)
          ? Math.max(0, Math.floor((user.expires_at - now) / 86400))
          : null;

        let totalBytes = user.traffic_total_bytes;
        let remainingBytes = user.traffic_remaining_bytes;

        // If remainingBytes is explicitly -1, it represents UNLIMITED traffic
        if (remainingBytes === -1) {
          totalBytes = 0;
        } else {
          if (totalBytes === undefined || totalBytes === null) {
            totalBytes = user.total_gb ? user.total_gb * 1024 * 1024 * 1024 : 30 * 1024 * 1024 * 1024;
          }
          if (remainingBytes === undefined || remainingBytes === null) {
            const used = user.used_bytes || 0;
            remainingBytes = Math.max(0, totalBytes - used);
          }
        }

        return new Response(JSON.stringify({
          status: 'success',
          user_info: {
            name: user.name || 'کاربر گرامی',
            days_left: daysLeft,
            traffic_remaining_bytes: remainingBytes,
            traffic_total_bytes: totalBytes,
            max_devices: (user.max_devices !== undefined && user.max_devices !== null) ? user.max_devices : 1,
            active_devices_count: Array.isArray(user.devices) ? user.devices.length : (user.device_id ? 1 : 0),
            support_phone: supportPhone,
            support_telegram: supportTelegram,
            is_admin: Boolean(user.is_admin || user.role === 'admin' || (user.name && user.name.toLowerCase().includes('admin')) || pin === '22858950' || pin === 'Arman4237340')
          },
          nodes: nodes,
          xray_config: xrayConfig,
          singbox_config: singboxConfig
        }), { status: 200, headers: corsHeaders });
      }

      // -------------------------------------------------------------
      // ADMIN ENDPOINTS (Requires X-Admin-Token header)
      // -------------------------------------------------------------
      const customPassword = await env.VPN_USERS.get('__ADMIN_CUSTOM_PASSWORD__');
      const validTokens = [
        customPassword ? customPassword.trim() : null,
        env.ADMIN_TOKEN ? env.ADMIN_TOKEN.trim() : null
      ].filter(Boolean);

      function checkAdminAuth(token) {
        if (!token) return false;
        const clean = String(token).trim().replace(/[۰-۹]/g, d => '۰۱۲۳۴۵۶۷۸۹'.indexOf(d));
        return validTokens.some(vt => vt === clean || vt.toLowerCase() === clean.toLowerCase());
      }

      // POST /api/v1/admin/login
      if (path === '/api/v1/admin/login' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const inputToken = String(body.token || body.password || request.headers.get('X-Admin-Token') || '').trim();
        if (checkAdminAuth(inputToken)) {
          return new Response(JSON.stringify({
            success: true,
            message: 'ورود با موفقیت انجام شد.',
            token: inputToken
          }), { status: 200, headers: corsHeaders });
        } else {
          return new Response(JSON.stringify({
            success: false,
            error: 'رمز عبور وارد شده نادرست است.'
          }), { status: 401, headers: corsHeaders });
        }
      }

      const adminToken = (request.headers.get('X-Admin-Token') || '').trim();
      if (!checkAdminAuth(adminToken)) {
        return new Response(JSON.stringify({
          error: 'Unauthorized',
          message: 'رمز عبور یا توکن مدیریت نامعتبر است'
        }), { status: 401, headers: corsHeaders });
      }

      // POST /api/v1/admin/change_password
      if (path === '/api/v1/admin/change_password' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const currentPassword = String(body.current_password || body.currentPassword || '').trim();
        const newPassword = String(body.new_password || body.newPassword || '').trim();

        if (!currentPassword || !checkAdminAuth(currentPassword)) {
          return new Response(JSON.stringify({
            error: 'رمز عبور فعلی مدیریت اشتباه است.'
          }), { status: 400, headers: corsHeaders });
        }

        if (!newPassword || newPassword.length < 6) {
          return new Response(JSON.stringify({
            error: 'رمز عبور جدید باید حداقل ۶ کاراکتر باشد.'
          }), { status: 400, headers: corsHeaders });
        }

        await safeKvPut(env, '__ADMIN_CUSTOM_PASSWORD__', newPassword);

        return new Response(JSON.stringify({
          success: true,
          message: 'رمز عبور مدیریت با موفقیت بروزرسانی شد.'
        }), { status: 200, headers: corsHeaders });
      }

      // POST /api/v1/admin/merge_duplicates
      if (path === '/api/v1/admin/merge_duplicates' && request.method === 'POST') {
        const result = await PanelManager.mergeDuplicateUsers(env);
        return new Response(JSON.stringify(result), { status: 200, headers: corsHeaders });
      }

      // POST /api/v1/admin/update
      if (path === '/api/v1/admin/update' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const pin = String(body.pin || '').trim();

        const raw = await env.VPN_USERS.get(pin);
        if (!raw) {
          return new Response(JSON.stringify({ error: 'User PIN not found' }), { status: 404, headers: corsHeaders });
        }

        const user = JSON.parse(raw);
        if (body.name !== undefined) user.name = body.name;

        const manualUrls = (body.upstream_sub_url !== undefined && body.upstream_sub_url.trim())
          ? String(body.upstream_sub_url).split(/[\r\n,]+/).map(u => u.trim()).filter(Boolean)
          : [];

        if (Array.isArray(body.panel_ids)) {
          await PanelManager.assignPanelsToUser(env, user, body.panel_ids, manualUrls);
        } else if (manualUrls.length > 0) {
          user.upstream_sub_urls = manualUrls;
          user.upstream_sub_url = manualUrls.join('\n');
          if (manualUrls.length > 1 && !body.panel_type) {
            user.panel_type = 'multi';
          }
        }

        if (body.panel_type !== undefined) user.panel_type = body.panel_type;
        if (body.active !== undefined) user.active = Boolean(body.active);
        if (body.traffic_remaining_bytes !== undefined) user.traffic_remaining_bytes = Number(body.traffic_remaining_bytes);
        if (body.traffic_total_bytes !== undefined) user.traffic_total_bytes = Number(body.traffic_total_bytes);
        if (body.max_devices !== undefined && body.max_devices !== null && body.max_devices !== '') {
          user.max_devices = parseInt(body.max_devices, 10);
        }
        if (body.reset_devices === true) {
          DeviceLock.resetLock(user);
        }
        if (body.days !== undefined && body.days !== '') {
          const now = Math.floor(Date.now() / 1000);
          user.expires_at = now + (parseInt(body.days, 10) * 86400);
        }

        // Sync updates (status, expiry, limits) to remote proxy panels
        if (user.remote_panel_mappings && typeof user.remote_panel_mappings === 'object') {
          try {
            const panels = await PanelManager.getPanels(env);
            for (const [panelId, mappingInfo] of Object.entries(user.remote_panel_mappings)) {
              const panel = panels.find(p => p.id === panelId);
              if (panel) {
                await PanelManager.updateRemoteUser(panel, mappingInfo, {
                  active: user.active,
                  expires_at: user.expires_at,
                  traffic_total_bytes: user.traffic_total_bytes
                });
              }
            }
          } catch (updateRemoteErr) {
            console.warn(`Could not sync updates to remote panels for PIN ${pin}:`, updateRemoteErr.message);
          }
        }

        // Live calculation of traffic & expiry from panels
        if (Array.isArray(user.upstream_sub_urls) && user.upstream_sub_urls.length > 0) {
          try {
            const subRes = await PanelAdapter.fetchSubscription(user, env);
            if (subRes && subRes.userInfo) {
              const info = subRes.userInfo;
              if (info.total === 0) {
                user.traffic_remaining_bytes = -1;
                user.traffic_total_bytes = 0;
              } else if (info.total > 0) {
                const used = (info.upload || 0) + (info.download || 0);
                user.traffic_remaining_bytes = Math.max(0, info.total - used);
                user.traffic_total_bytes = info.total;
              }
              if (info.expire === 0) {
                user.expires_at = null;
              } else if (info.expire > 1000000000 && !body.days) {
                user.expires_at = info.expire;
              }
            }
          } catch (e) {}
        }

        updateCachedUserRecord(pin, user);
        await safeKvPut(env, pin, JSON.stringify(user));

        return new Response(JSON.stringify({
          status: 'success',
          user: user
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/create
      if (path === '/api/v1/admin/create' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        let pin = body.pin ? String(body.pin).trim() : null;

        // Auto-generate 10-digit PIN or validate secure custom PIN
        if (pin) {
          const isValidCustom = (pin.length === 10 && isSecure10DigitPin(pin)) || (pin.length === 8 && isSecure8DigitPin(pin));
          if (!isValidCustom) {
            return new Response(JSON.stringify({
              error: 'Invalid PIN security format',
              message_fa: 'کد اشتراک باید یک عدد ۱۰ رقمی (یا ۸ رقمی) غیرتکراری و غیرقابل حدس باشد (کدهای ساده نظیر ۸۸۸۸۸۸۸۸، ۱۲۳۴۵۶۷۸ یا الگوهای متقارن/تکراری مجاز نیستند).'
            }), { status: 400, headers: corsHeaders });
          }
          const existing = await env.VPN_USERS.get(pin);
          if (existing) {
            return new Response(JSON.stringify({
              error: 'Duplicate PIN',
              message_fa: 'این کد اشتراک قبلاً ثبت شده است. لطفاً کد دیگری انتخاب کنید.'
            }), { status: 409, headers: corsHeaders });
          }
        } else {
          pin = await generateUniqueSecurePin(env);
        }

        if (!pin) {
          return new Response(JSON.stringify({ error: 'Failed to generate unique PIN' }), { status: 500, headers: corsHeaders });
        }

        // Strict 1-time test subscription check on Cloudflare Worker
        if (body.is_test && body.telegram_id) {
          const list = await env.VPN_USERS.list();
          for (const key of list.keys) {
            if (!/^\d+$/.test(key.name)) continue;
            const existingRaw = await env.VPN_USERS.get(key.name);
            if (existingRaw) {
              try {
                const ex = JSON.parse(existingRaw);
                if (ex.telegram_id && String(ex.telegram_id) === String(body.telegram_id) && ex.is_test) {
                  return new Response(JSON.stringify({
                    error: 'Already claimed',
                    message_fa: 'سهمیه اشتراک تست رایگان قبلاً برای این حساب تلگرام فعال شده است.'
                  }), { status: 400, headers: corsHeaders });
                }
              } catch (_) {}
            }
          }
        }

        const now = Math.floor(Date.now() / 1000);
        const days = parseInt(body.days || '30', 10);
        const expiresAt = now + (days * 86400);
        const rawUrls = String(body.upstream_sub_url || '').split(/[\r\n,]+/).map(u => u.trim()).filter(Boolean);

        const trafficGB = parseFloat(body.traffic_gb || body.trafficLimitGB || 0);
        let trafficBytes = null;
        if (trafficGB > 0) {
          trafficBytes = Math.round(trafficGB * 1073741824);
        } else if (body.traffic_total_bytes) {
          trafficBytes = parseInt(body.traffic_total_bytes, 10);
        }

        let userRecord = {
          pin: pin,
          name: body.name || 'کاربر جدید',
          panel_type: rawUrls.length > 1 ? 'multi' : (body.panel_type || 'raw'),
          upstream_sub_url: rawUrls.join('\n'),
          upstream_sub_urls: rawUrls,
          panel_ids: Array.isArray(body.panel_ids) ? body.panel_ids : [],
          traffic_total_bytes: trafficBytes,
          traffic_remaining_bytes: trafficBytes,
          device_id: null,
          devices: [],
          max_devices: (body.max_devices !== undefined && body.max_devices !== null && body.max_devices !== '')
            ? parseInt(body.max_devices, 10)
            : 1,
          bound_at: null,
          active: true,
          created_at: now,
          expires_at: expiresAt,
          telegram_id: body.telegram_id ? parseInt(body.telegram_id, 10) : null,
          is_test: Boolean(body.is_test)
        };

        const allPanels = await PanelManager.getPanels(env);
        const activePanelIds = allPanels.filter(p => p.autoSync !== false).map(p => p.id);
        const targetPanelIds = (Array.isArray(body.panel_ids) && body.panel_ids.length > 0)
          ? body.panel_ids
          : activePanelIds;

        userRecord = await PanelManager.assignPanelsToUser(env, userRecord, targetPanelIds, rawUrls);

        // Live calculation of traffic & expiry from panels
        if (Array.isArray(userRecord.upstream_sub_urls) && userRecord.upstream_sub_urls.length > 0) {
          try {
            const subRes = await PanelAdapter.fetchSubscription(userRecord, env);
            if (subRes && subRes.userInfo) {
              const info = subRes.userInfo;
              if (info.total === 0 && !trafficBytes) {
                userRecord.traffic_remaining_bytes = -1;
                userRecord.traffic_total_bytes = 0;
              } else if (info.total > 0 && !trafficBytes) {
                const used = (info.upload || 0) + (info.download || 0);
                userRecord.traffic_remaining_bytes = Math.max(0, info.total - used);
                userRecord.traffic_total_bytes = info.total;
              }
              if (info.expire === 0 && !body.days) {
                userRecord.expires_at = null;
              } else if (info.expire > 1000000000 && !body.days) {
                userRecord.expires_at = info.expire;
              }
            }
          } catch (e) {}
        }

        await safeKvPut(env, pin, JSON.stringify(userRecord));

        return new Response(JSON.stringify({
          status: 'created',
          user: userRecord
        }), { status: 201, headers: corsHeaders });
      }

      // POST /api/v1/admin/migrate_to_8pin or /api/v1/admin/migrate_to_10pin
      if ((path === '/api/v1/admin/migrate_to_8pin' || path === '/api/v1/admin/migrate_to_10pin') && request.method === 'POST') {
        const list = await env.VPN_USERS.list();
        const migratedUsers = [];
        const reservedPins = new Set();
        const now = Math.floor(Date.now() / 1000);

        for (const key of list.keys) {
          if (!/^\d+$/.test(key.name)) continue;

          const oldPin = key.name;
          // Keep valid 8-digit and 10-digit PINs; migrate only insecure or non-standard pins
          const isValidCurrent = (oldPin.length === 8 && isSecure8DigitPin(oldPin)) || (oldPin.length === 10 && isSecure10DigitPin(oldPin));
          const needsMigration = !isValidCurrent;

          if (needsMigration) {
            const raw = await env.VPN_USERS.get(oldPin);
            if (!raw) continue;

            let user;
            try {
              user = JSON.parse(raw);
            } catch (_) {
              continue;
            }

            const newPin = await generateUniqueSecurePin(env, reservedPins);
            reservedPins.add(newPin);

            user.pin = newPin;
            user.device_id = null; // Reset hardware lock so user can smoothly bind to their phone
            user.bound_at = null;

            await safeKvPut(env, newPin, JSON.stringify(user));
            await safeKvDelete(env, oldPin);

            const daysLeft = user.expires_at ? Math.max(0, Math.ceil((user.expires_at - now) / 86400)) : 30;

            migratedUsers.push({
              name: user.name || 'کاربر',
              old_pin: oldPin,
              new_pin: newPin,
              panel_type: user.panel_type || 'pasarguard',
              days_left: daysLeft
            });
          }
        }

        return new Response(JSON.stringify({
          status: 'success',
          migrated_count: migratedUsers.length,
          migrated_users: migratedUsers
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/migrate
      if (path === '/api/v1/admin/migrate' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const { old_domain, new_domain, new_panel_type } = body;

        if (!old_domain || !new_domain) {
          return new Response(JSON.stringify({
            error: 'Missing parameters',
            message: 'old_domain and new_domain are required'
          }), { status: 400, headers: corsHeaders });
        }

        const list = await env.VPN_USERS.list();
        let migratedCount = 0;

        for (const key of list.keys) {
          if (!/^\d+$/.test(key.name)) continue;
          const raw = await env.VPN_USERS.get(key.name);
          if (!raw) continue;
          const user = JSON.parse(raw);

          if (user.upstream_sub_url && user.upstream_sub_url.includes(old_domain)) {
            user.upstream_sub_url = user.upstream_sub_url.replace(old_domain, new_domain);
            if (new_panel_type) {
              user.panel_type = new_panel_type;
            }
            await safeKvPut(env, key.name, JSON.stringify(user));
            migratedCount++;
          }
        }

        return new Response(JSON.stringify({
          status: 'success',
          migrated_count: migratedCount
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/reset_device
      if (path === '/api/v1/admin/reset_device' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const pin = String(body.pin || '').trim();

        const raw = await env.VPN_USERS.get(pin);
        if (!raw) {
          return new Response(JSON.stringify({ error: 'User PIN not found' }), { status: 404, headers: corsHeaders });
        }

        const user = JSON.parse(raw);
        const oldDevices = [user.device_id, ...(Array.isArray(user.devices) ? user.devices : [])].filter(Boolean);
        DeviceLock.resetLock(user);
        user.lockout_cleared_at = Math.floor(Date.now() / 1000);
        user.lockout_cleared = true;
        updateCachedUserRecord(pin, user);
        await safeKvPut(env, pin, JSON.stringify(user));

        // Clear rate limits for all associated devices and PIN
        await safeKvDelete(env, `rl:pin:${pin}`);
        for (const dev of oldDevices) {
          memoryUserCache.delete(`rl:dev:${dev}`);
          memoryUserCache.delete(`rl:${dev}`);
          await safeKvDelete(env, `rl:dev:${dev}`);
          await safeKvDelete(env, `rl:${dev}`);
        }

        return new Response(JSON.stringify({
          status: 'success',
          message: `قفل سخت‌افزاری و مسدودی ورود برای پین ${pin} با موفقیت بازنشانی شد.`
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/unlock_rate_limit
      if (path === '/api/v1/admin/unlock_rate_limit' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const pin = String(body.pin || '').trim();
        const deviceId = String(body.device_id || body.deviceId || '').trim();
        const clientIp = String(body.ip || '').trim();
        const now = Math.floor(Date.now() / 1000);

        if (deviceId) {
          memoryUserCache.delete(`rl:dev:${deviceId}`);
          memoryUserCache.delete(`rl:${deviceId}`);
          await safeKvDelete(env, `rl:dev:${deviceId}`);
          await safeKvDelete(env, `rl:${deviceId}`);
        }

        const reqIp = request.headers.get('cf-connecting-ip') || request.headers.get('x-real-ip') || '';
        const targetIps = Array.from(new Set([clientIp, reqIp].filter(Boolean)));
        for (const cip of targetIps) {
          const ipSubnet = cip.includes('.') ? cip.split('.').slice(0, 3).join('.') + '.0/24' : cip;
          memoryUserCache.delete(`rl:ip:${ipSubnet}`);
          memoryUserCache.delete(`rl:ip:${cip}`);
          await safeKvDelete(env, `rl:ip:${ipSubnet}`);
          await safeKvDelete(env, `rl:ip:${cip}`);
        }

        if (pin) {
          await safeKvDelete(env, `rl:pin:${pin}`);
          const raw = await env.VPN_USERS.get(pin);
          if (raw) {
            try {
              const user = JSON.parse(raw);
              user.lockout_cleared_at = now;
              user.lockout_cleared = true;
              updateCachedUserRecord(pin, user);
        await safeKvPut(env, pin, JSON.stringify(user));

              const devs = [user.device_id, ...(Array.isArray(user.devices) ? user.devices : [])].filter(Boolean);
              for (const d of devs) {
                memoryUserCache.delete(`rl:dev:${d}`);
                memoryUserCache.delete(`rl:${d}`);
                await safeKvDelete(env, `rl:dev:${d}`);
                await safeKvDelete(env, `rl:${d}`);
              }
            } catch (_) {}
          }
        }

        return new Response(JSON.stringify({
          status: 'success',
          message: `قفل و محدودیت ورود ۳۰ دقیقه‌ای با موفقیت برطرف شد.`
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/reset_ratelimit
      if (path === '/api/v1/admin/reset_ratelimit' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const deviceId = String(body.device_id || body.deviceId || '').trim();
        if (deviceId) {
          memoryUserCache.delete(`rl:dev:${deviceId}`);
          memoryUserCache.delete(`rl:${deviceId}`);
          await safeKvDelete(env, `rl:dev:${deviceId}`);
          await safeKvDelete(env, `rl:${deviceId}`);
        }
        return new Response(JSON.stringify({
          status: 'success',
          message: `Rate limit for device ${deviceId} has been cleared.`
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/extend
      if (path === '/api/v1/admin/extend' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const pin = String(body.pin || '').trim();
        const days = parseInt(body.days || '0', 10);
        const addTrafficGB = parseFloat(body.traffic_gb || body.add_traffic_gb || 0);

        let user = await getCachedUserRecord(env, pin);
        if (!user) {
          return new Response(JSON.stringify({ error: 'User PIN not found' }), { status: 404, headers: corsHeaders });
        }

        const now = Math.floor(Date.now() / 1000);
        if (days > 0) {
          const base = Math.max(now, user.expires_at || now);
          user.expires_at = base + (days * 86400);
        }
        if (addTrafficGB > 0) {
          const addBytes = Math.round(addTrafficGB * 1073741824);
          user.traffic_total_bytes = (user.traffic_total_bytes || 0) + addBytes;
          if (user.traffic_remaining_bytes !== undefined && user.traffic_remaining_bytes !== null && user.traffic_remaining_bytes !== -1) {
            user.traffic_remaining_bytes = Math.max(0, user.traffic_remaining_bytes + addBytes);
          } else {
            user.traffic_remaining_bytes = user.traffic_total_bytes;
          }
        }
        user.active = true;

        // Push updates to remote panels concurrently in parallel!
        if (user.remote_panel_mappings && typeof user.remote_panel_mappings === 'object') {
          try {
            const panels = await PanelManager.getPanels(env);
            await Promise.allSettled(Object.entries(user.remote_panel_mappings).map(async ([panelId, mappingInfo]) => {
              const panel = panels.find(p => p.id === panelId);
              if (panel) {
                await PanelManager.updateRemoteUser(panel, mappingInfo, {
                  active: true,
                  expires_at: user.expires_at,
                  traffic_total_bytes: user.traffic_total_bytes
                });
              }
            }));
          } catch (panelUpdateErr) {
            console.warn(`Error updating remote panels during extend:`, panelUpdateErr.message);
          }
        }

        updateCachedUserRecord(pin, user);
        await safeKvPut(env, pin, JSON.stringify(user));

        return new Response(JSON.stringify({
          status: 'success',
          pin: pin,
          new_expires_at: user.expires_at,
          traffic_total_bytes: user.traffic_total_bytes,
          traffic_remaining_bytes: user.traffic_remaining_bytes
        }), { headers: corsHeaders });
      }

      // GET /api/v1/admin/user
      if (path === '/api/v1/admin/user' && request.method === 'GET') {
        const pin = String(url.searchParams.get('pin') || '').trim();
        if (!pin) {
          return new Response(JSON.stringify({ error: 'PIN parameter required' }), { status: 400, headers: corsHeaders });
        }
        const raw = await env.VPN_USERS.get(pin);
        if (!raw) {
          return new Response(JSON.stringify({ error: 'User PIN not found' }), { status: 404, headers: corsHeaders });
        }
        const user = JSON.parse(raw);
        return new Response(JSON.stringify({ status: 'success', user }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/delete
      if (path === '/api/v1/admin/delete' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const pin = String(body.pin || '').trim();

        if (pin) {
          const raw = await env.VPN_USERS.get(pin);
          if (raw) {
            try {
              const user = JSON.parse(raw);
              const panels = await PanelManager.getPanels(env);

              // 1. Delete on panels where mapping exists
              if (user.remote_panel_mappings && typeof user.remote_panel_mappings === 'object') {
                for (const [panelId, mappingInfo] of Object.entries(user.remote_panel_mappings)) {
                  const panel = panels.find(p => p.id === panelId);
                  if (panel) {
                    await PanelManager.deleteRemoteUser(panel, mappingInfo);
                  }
                }
              }

              // 2. Also cleanup across all panels by safe username & email
              const safeName = PanelManager.generateSafeUsername(user);
              for (const panel of panels) {
                try {
                  await PanelManager.deleteRemoteUser(panel, {
                    username: safeName,
                    name: user.name,
                    email: safeName
                  });
                } catch (_) {}
              }
            } catch (cleanupErr) {
              console.warn(`Could not cleanup remote user for PIN ${pin}:`, cleanupErr.message);
            }
          }
          await env.VPN_USERS.delete(pin);
          await safeKvDelete(env, `rl:${pin}`);
        }

        return new Response(JSON.stringify({
          status: 'deleted',
          pin: pin
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/sync_all_users_to_panels
      if (path === '/api/v1/admin/sync_all_users_to_panels' && request.method === 'POST') {
        const res = await PanelManager.syncUsersToAllPanels(env);
        return new Response(JSON.stringify(res), { headers: corsHeaders });
      }

      // GET /api/v1/admin/users
      if (path === '/api/v1/admin/users' && request.method === 'GET') {
        const list = await env.VPN_USERS.list();
        const users = [];

        for (const key of list.keys) {
          if (!/^\d+$/.test(key.name)) continue;
          const raw = await env.VPN_USERS.get(key.name);
          if (raw) {
            try {
              const u = JSON.parse(raw);
              if (u.pin) users.push(u);
            } catch (e) {}
          }
        }

        return new Response(JSON.stringify({
          total: users.length,
          users: users
        }), { headers: corsHeaders });
      }

      // -------------------------------------------------------------
      // PROXY PANELS MANAGEMENT ENDPOINTS
      // -------------------------------------------------------------
      
      // GET /api/v1/admin/panels
      if (path === '/api/v1/admin/panels' && request.method === 'GET') {
        const panels = await PanelManager.getPanels(env);
        return new Response(JSON.stringify({ panels }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/panels/save
      if (path === '/api/v1/admin/panels/save' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        if (!body.name || !body.url || !body.type) {
          return new Response(JSON.stringify({ error: 'نام پنل، آدرس و نوع پنل الزامی است' }), { status: 400, headers: corsHeaders });
        }
        const saved = await PanelManager.addOrUpdatePanel(env, body);
        return new Response(JSON.stringify({ status: 'success', panel: saved }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/panels/delete
      if (path === '/api/v1/admin/panels/delete' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        if (!body.id) {
          return new Response(JSON.stringify({ error: 'شناسه پنل الزامی است' }), { status: 400, headers: corsHeaders });
        }
        await PanelManager.deletePanel(env, body.id);
        return new Response(JSON.stringify({ status: 'success' }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/panels/test
      if (path === '/api/v1/admin/panels/test' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const testRes = await PanelManager.testPanel(body);
        return new Response(JSON.stringify(testRes), { headers: corsHeaders });
      }

      // POST /api/v1/admin/panels/sync
      if (path === '/api/v1/admin/panels/sync' && request.method === 'POST') {
        try {
          const body = await request.json().catch(() => ({}));
          if (body.id) {
            const syncRes = await PanelManager.syncPanel(env, body.id);
            return new Response(JSON.stringify(syncRes), { headers: corsHeaders });
          } else {
            const syncAllRes = await PanelManager.syncAllPanels(env);
            return new Response(JSON.stringify(syncAllRes), { headers: corsHeaders });
          }
        } catch (syncErr) {
          console.error('Error in /api/v1/admin/panels/sync:', syncErr);
          return new Response(JSON.stringify({
            success: false,
            error: syncErr.message || 'خطا در همگام‌سازی پنل‌ها'
          }), { status: 500, headers: corsHeaders });
        }
      }

      // GET /api/v1/admin/panels/remote_users
      if (path === '/api/v1/admin/panels/remote_users' && request.method === 'GET') {
        const panelId = url.searchParams.get('panel_id');
        const panels = await PanelManager.getPanels(env);
        const panel = panels.find(p => p.id === panelId);
        if (!panel) {
          return new Response(JSON.stringify({ error: 'پنل یافت نشد' }), { status: 404, headers: corsHeaders });
        }
        const remoteUsers = await PanelManager.fetchRemoteUsers(panel);
        return new Response(JSON.stringify({ users: remoteUsers }), { headers: corsHeaders });
      }

      // GET /api/v1/admin/panels/inspect
      if (path === '/api/v1/admin/panels/inspect' && request.method === 'GET') {
        const panelId = url.searchParams.get('panel_id');
        const customPath = url.searchParams.get('path');
        const panels = await PanelManager.getPanels(env);
        const panel = panels.find(p => p.id === panelId);
        if (!panel) {
          return new Response(JSON.stringify({ error: 'پنل یافت نشد' }), { status: 404, headers: corsHeaders });
        }
        const inspectRes = await PanelManager.inspectPanel(panel, customPath);
        return new Response(JSON.stringify(inspectRes), { headers: corsHeaders });
      }

      // 404
      return new Response(JSON.stringify({ error: 'Endpoint not found' }), { status: 404, headers: corsHeaders });

    } catch (err) {
      console.error('Unhandled worker exception:', err);
      return new Response(JSON.stringify({
        status: 'internal_error',
        message: err.message,
        message_fa: 'خطای غیرمنتظره در سرور رخ داد.',
        support_phone: supportPhone
      }), { status: 500, headers: corsHeaders });
    }
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(PanelManager.syncAllPanels(env));
  }
};
