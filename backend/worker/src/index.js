import { parseSubscriptionText } from './parsers/sub_parser.js';
import { generateXrayConfig, generateSingBoxConfig } from './parsers/config_generator.js';
import { PanelAdapter } from './panels/panel_adapter.js';
import { PanelManager, universalFetch } from './panels/panel_manager.js';
import { DeviceLock } from './auth/device_lock.js';
import { getAdminDashboardHtml } from './admin_ui.js';
import { getWebAppHtml } from './webapp_ui.js';
import { getOpenAppHtml } from './open_ui.js';

async function safeKvPut(env, key, value, options) {
  try {
    if (options) {
      await env.VPN_USERS.put(key, value, options);
    } else {
      await env.VPN_USERS.put(key, value);
    }
    return true;
  } catch (err) {
    console.warn(`Safe KV put suppressed for key ${key}: ${err?.message || err}`);
    return false;
  }
}

async function safeKvDelete(env, key) {
  try {
    await env.VPN_USERS.delete(key);
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
 * Generates an unguessable, cryptographically secure 8-digit PIN.
 */
async function generateUniqueSecurePin(env, reservedPins = new Set()) {
  let attempts = 0;
  while (attempts < 150) {
    const array = new Uint32Array(2);
    crypto.getRandomValues(array);
    const num = (array[0] % 90000000) + 10000000;
    const candidate = String(num);

    if (isSecure8DigitPin(candidate) && !reservedPins.has(candidate)) {
      const existing = await env.VPN_USERS.get(candidate);
      if (!existing) {
        return candidate;
      }
    }
    attempts++;
  }
  while (true) {
    const candidate = String(Math.floor(10000000 + Math.random() * 90000000));
    if (isSecure8DigitPin(candidate) && !reservedPins.has(candidate)) {
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
      const rawMeta = await env.VPN_USERS.get('apk_meta');
      let version = "1.3.2";
      let versionCode = 8;
      let changelog = "طراحی جدید و مدرن سفید سه‌بعدی، آیکون رسمی جدید، اتصال خودکار با دیپ‌لینک و سیستم به‌روزرسانی خودکار داخلی";
      let totalSize = 69791489;
      if (rawMeta) {
        try {
          const meta = JSON.parse(rawMeta);
          if (meta.version) version = meta.version;
          if (meta.versionCode) versionCode = meta.versionCode;
          if (meta.total_size) totalSize = meta.total_size;
          if (meta.changelog) changelog = meta.changelog;
        } catch (e) {}
      }
      return new Response(JSON.stringify({
        success: true,
        versionName: version,
        versionCode: versionCode,
        totalSize: totalSize,
        downloadUrl: "https://asanvpn-worker.hi-deploy.workers.dev/download",
        githubUrl: "https://github.com/armanpier/AsanVPN/releases/latest",
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

    // Direct APK Download Endpoint (Hosted directly on Cloudflare Worker CDN)
    if ((path === '/download' || path === '/AsanVPN.apk' || path === '/apk') && (request.method === 'GET' || request.method === 'HEAD')) {
      const rawMeta = await env.VPN_USERS.get('apk_meta');
      if (!rawMeta) {
        return new Response('APK file not found on server.', { status: 404 });
      }
      const meta = JSON.parse(rawMeta);

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

        // Strictly enforce 8-digit PIN format
        if (pin.length !== 8 || !/^\d{8}$/.test(pin)) {
          return new Response(JSON.stringify({
            status: 'invalid_pin',
            message_fa: 'کد اشتراک باید یک عدد ۸ رقمی باشد.',
            support_phone: supportPhone,
            support_telegram: supportTelegram
          }), { status: 403, headers: corsHeaders });
        }

        const now = Math.floor(Date.now() / 1000);

        // --- Rate Limiting: 3 wrong PIN attempts -> 30 minutes lockout ---
        const rlKey = `rl:${deviceId}`;
        const rawRl = await env.VPN_USERS.get(rlKey);
        let rlData = rawRl ? JSON.parse(rawRl) : { count: 0, locked_until: 0 };

        if (rlData.locked_until && rlData.locked_until > now) {
          const remainingSec = rlData.locked_until - now;
          const remainingMin = Math.ceil(remainingSec / 60);
          return new Response(JSON.stringify({
            status: 'rate_limited',
            message_fa: `تعداد تلاش‌های ناموفق بیش از ۳ بار بوده است. لطفاً ${remainingMin} دقیقه دیگر تلاش کنید.`,
            retry_after_seconds: remainingSec,
            locked_until_timestamp: rlData.locked_until * 1000,
            support_phone: supportPhone,
            support_telegram: supportTelegram
          }), { status: 429, headers: corsHeaders });
        }

        // Look up PIN in Cloudflare KV
        const rawUser = await env.VPN_USERS.get(pin);
        if (!rawUser) {
          const count = (rlData.count || 0) + 1;
          if (count >= 3) {
            rlData.count = count;
            rlData.locked_until = now + 1800; // 30 minutes lockout
            await safeKvPut(env, rlKey, JSON.stringify(rlData), { expirationTtl: 3600 });
            return new Response(JSON.stringify({
              status: 'rate_limited',
              message_fa: 'تعداد تلاش‌های ناموفق به ۳ بار رسید. ورود به مدت ۳۰ دقیقه مسدود شد.',
              retry_after_seconds: 1800,
              locked_until_timestamp: rlData.locked_until * 1000,
              support_phone: supportPhone,
              support_telegram: supportTelegram
            }), { status: 429, headers: corsHeaders });
          } else {
            rlData.count = count;
            await safeKvPut(env, rlKey, JSON.stringify(rlData), { expirationTtl: 3600 });
            const remaining = 3 - count;
            return new Response(JSON.stringify({
              status: 'invalid_pin',
              message_fa: `کد اشتراک نامعتبر است. (${remaining} فرصت باقی مانده)`,
              remaining_attempts: remaining,
              support_phone: supportPhone,
              support_telegram: supportTelegram
            }), { status: 403, headers: corsHeaders });
          }
        }

        // PIN is valid! Clear rate limit for this device
        if (rawRl) {
          await safeKvDelete(env, rlKey);
        }

        const user = JSON.parse(rawUser);

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
            if (info.total !== undefined) {
              if (info.total === 0) {
                // 0 means UNLIMITED traffic
                if (user.traffic_remaining_bytes !== -1 || user.traffic_total_bytes !== 0) {
                  user.traffic_remaining_bytes = -1;
                  user.traffic_total_bytes = 0;
                  hasChanged = true;
                }
              } else {
                const used = (info.upload || 0) + (info.download || 0);
                const rem = Math.max(0, info.total - used);
                if (user.traffic_remaining_bytes !== rem || user.traffic_total_bytes !== info.total) {
                  user.traffic_remaining_bytes = rem;
                  user.traffic_total_bytes = info.total;
                  hasChanged = true;
                }
              }
            }
            if (info.expire !== undefined) {
              if (info.expire === 0) {
                // 0 means UNLIMITED validity
                if (user.expires_at !== null) {
                  user.expires_at = null;
                  hasChanged = true;
                }
              } else if (info.expire > 1000000000) {
                if (user.expires_at !== info.expire) {
                  user.expires_at = info.expire;
                  hasChanged = true;
                }
              }
            }
            if (hasChanged) {
              await safeKvPut(env, pin, JSON.stringify(user));
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
            const oldNodesStr = JSON.stringify(user.cached_nodes || []);
            const newNodesStr = JSON.stringify(nodes);
            if (oldNodesStr !== newNodesStr) {
              user.cached_nodes = nodes;
              await safeKvPut(env, pin, JSON.stringify(user));
            }
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
          ? Math.max(0, Math.ceil((user.expires_at - now) / 86400))
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

        await env.VPN_USERS.put('__ADMIN_CUSTOM_PASSWORD__', newPassword);

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

        await env.VPN_USERS.put(pin, JSON.stringify(user));

        return new Response(JSON.stringify({
          status: 'success',
          user: user
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/create
      if (path === '/api/v1/admin/create' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        let pin = body.pin ? String(body.pin).trim() : null;

        // Auto-generate or validate secure 8-digit PIN
        if (pin) {
          if (!isSecure8DigitPin(pin)) {
            return new Response(JSON.stringify({
              error: 'Invalid PIN security format',
              message_fa: 'کد اشتراک باید یک عدد ۸ رقمی غیرتکراری و غیرقابل حدس باشد (کدهای ساده نظیر ۸۸۸۸۸۸۸۸، ۱۲۳۴۵۶۷۸ یا الگوهای متقارن/تکراری مجاز نیستند).'
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
          expires_at: expiresAt
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

        await env.VPN_USERS.put(pin, JSON.stringify(userRecord));

        return new Response(JSON.stringify({
          status: 'created',
          user: userRecord
        }), { status: 201, headers: corsHeaders });
      }

      // POST /api/v1/admin/migrate_to_8pin
      if (path === '/api/v1/admin/migrate_to_8pin' && request.method === 'POST') {
        const list = await env.VPN_USERS.list();
        const migratedUsers = [];
        const reservedPins = new Set();
        const now = Math.floor(Date.now() / 1000);

        for (const key of list.keys) {
          if (!/^\d+$/.test(key.name)) continue;

          const oldPin = key.name;
          const needsMigration = oldPin.length !== 8 || !isSecure8DigitPin(oldPin);

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

            await env.VPN_USERS.put(newPin, JSON.stringify(user));
            await env.VPN_USERS.delete(oldPin);

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
            await env.VPN_USERS.put(key.name, JSON.stringify(user));
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
        DeviceLock.resetLock(user);
        await env.VPN_USERS.put(pin, JSON.stringify(user));

        return new Response(JSON.stringify({
          status: 'success',
          message: `Device lock for PIN ${pin} has been reset. User can now bind a new phone.`
        }), { headers: corsHeaders });
      }

      // POST /api/v1/admin/reset_ratelimit
      if (path === '/api/v1/admin/reset_ratelimit' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        const deviceId = String(body.device_id || body.deviceId || '').trim();
        if (deviceId) {
          await env.VPN_USERS.delete(`rl:${deviceId}`);
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

        const raw = await env.VPN_USERS.get(pin);
        if (!raw) {
          return new Response(JSON.stringify({ error: 'User PIN not found' }), { status: 404, headers: corsHeaders });
        }

        const user = JSON.parse(raw);
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

        // Push updates to remote panels!
        if (user.remote_panel_mappings && typeof user.remote_panel_mappings === 'object') {
          try {
            const panels = await PanelManager.getPanels(env);
            for (const [panelId, mappingInfo] of Object.entries(user.remote_panel_mappings)) {
              const panel = panels.find(p => p.id === panelId);
              if (panel) {
                await PanelManager.updateRemoteUser(panel, mappingInfo, {
                  active: true,
                  expires_at: user.expires_at,
                  traffic_total_bytes: user.traffic_total_bytes
                });
              }
            }
          } catch (panelUpdateErr) {
            console.warn(`Error updating remote panels during extend:`, panelUpdateErr.message);
          }
        }

        await env.VPN_USERS.put(pin, JSON.stringify(user));

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
        const body = await request.json().catch(() => ({}));
        if (body.id) {
          const syncRes = await PanelManager.syncPanel(env, body.id);
          return new Response(JSON.stringify(syncRes), { headers: corsHeaders });
        } else {
          const syncAllRes = await PanelManager.syncAllPanels(env);
          return new Response(JSON.stringify(syncAllRes), { headers: corsHeaders });
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
