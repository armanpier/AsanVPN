// ==========================================
// AsanVPN Gateway on ArvanCloud Edge Computing
// Dual-Cloud Primary (Cloudflare Worker) & VPS Fallback
// OPSEC Hardened: Client Bootstrap Only (No Domestic Admin UI)
// ==========================================

addEventListener('fetch', event => {
  event.respondWith(handleRequest(event.request));
});

async function handleRequest(request) {
  const url = new URL(request.url);
  const path = url.pathname;

  // 1. Health check endpoint
  if (path === '/health' || path === '/ping') {
    return new Response(JSON.stringify({
      status: 'ok',
      service: 'AsanVPN ArvanCloud Edge Gateway',
      pop: request.headers.get('X-Country-Code') || 'IR',
      time: new Date().toISOString()
    }), {
      headers: { 'content-type': 'application/json; charset=utf-8' }
    });
  }

  // 2. OPSEC: Block Admin Dashboard UI on domestic Iranian edge
  if (path === '/' || path === '/admin' || path === '/dashboard' || path === '/panel') {
    return new Response(JSON.stringify({
      status: 'active',
      service: 'AsanVPN Edge Routing Gateway',
      message: 'Admin Dashboard is disabled on domestic edge for OPSEC security. Access exclusively via Cloudflare Zero Trust or internal SSH tunnel.'
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'X-Powered-By': 'AsanVPN-Edge-Security'
      }
    });
  }

  // 3. Serve High-Speed APK Direct Download (domestic Iranian edge)
  if (path === '/download' || path === '/AsanVPN.apk' || path === '/apk') {
    try {
      const vpsApk = await fetch('http://95.182.87.115/download', {
        headers: { 'User-Agent': 'ArvanEdge-Download/1.0', 'X-Edge-Secret': 'asanvpn-edge-internal-secret-2026' }
      });
      if (vpsApk && vpsApk.status === 200) {
        const respHeaders = new Headers(vpsApk.headers);
        respHeaders.set('Access-Control-Allow-Origin', '*');
        respHeaders.set('Cache-Control', 'public, max-age=1800');
        respHeaders.set('X-Served-By', 'ArvanCloud-Edge');
        return new Response(vpsApk.body, {
          status: 200,
          headers: respHeaders
        });
      }
    } catch (e) {}
    return Response.redirect('https://github.com/armanpier/AsanVPN/releases/latest', 302);
  }

  // 4. Proxy API requests with Cloudflare Primary & VPS Fallback
  if (path.startsWith('/api/')) {
    try {
      // OPSEC: Protect Admin API endpoints on domestic edge.
      // Only internal worker sync requests possessing X-Edge-Secret may pass through ArvanEdge to VPS.
      const edgeSecretHeader = (request.headers.get && (request.headers.get('x-edge-secret') || request.headers.get('X-Edge-Secret'))) || '';
      if (path.startsWith('/api/v1/admin/')) {
        if (edgeSecretHeader !== 'asanvpn-edge-internal-secret-2026') {
          return new Response(JSON.stringify({
            error: 'Forbidden',
            message: 'Administrative endpoints disabled on domestic edge gateway.'
          }), {
            status: 403,
            headers: { 'Content-Type': 'application/json; charset=utf-8' }
          });
        }
      }

      let adminToken = '';
      try {
        if (request.headers) {
          if (typeof request.headers.get === 'function') {
            adminToken = request.headers.get('x-admin-token') || request.headers.get('X-Admin-Token') || '';
          }
          if (!adminToken && request.headers['x-admin-token']) {
            adminToken = request.headers['x-admin-token'];
          }
          if (!adminToken && request.headers['X-Admin-Token']) {
            adminToken = request.headers['X-Admin-Token'];
          }
        }
      } catch (e) {}

      if (!adminToken) {
        adminToken = url.searchParams.get('token') || url.searchParams.get('admin_token') || '';
      }

      // Preserve connecting client IP to prevent reverse-proxy wide rate limit lockouts
      const clientIp = (request.headers && (
        (typeof request.headers.get === 'function' ? (request.headers.get('ar-real-ip') || request.headers.get('x-real-ip') || request.headers.get('cf-connecting-ip') || request.headers.get('x-forwarded-for')) : (request.headers['ar-real-ip'] || request.headers['x-real-ip'] || request.headers['cf-connecting-ip'] || request.headers['x-forwarded-for']))
      )) || '';
      const cleanClientIp = clientIp.split(',')[0].trim();

      const contentType = (request.headers.get && request.headers.get('content-type')) || 'application/json';

      const outgoingHeaders = {
        'Accept': 'application/json',
        'Content-Type': contentType,
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AsanVPN-Edge/1.0',
        'X-Edge-Secret': 'asanvpn-edge-internal-secret-2026'
      };
      if (adminToken) {
        outgoingHeaders['X-Admin-Token'] = adminToken;
      }
      if (cleanClientIp) {
        outgoingHeaders['X-Forwarded-For'] = cleanClientIp;
        outgoingHeaders['CF-Connecting-IP'] = cleanClientIp;
      }

      // Clone or read body safely
      let reqBody = undefined;
      if (!['GET', 'HEAD'].includes(request.method)) {
        reqBody = await request.arrayBuffer();
      }

      const cfUrl = new URL(url.pathname + url.search, 'https://asanvpn-worker.hi-deploy.workers.dev');
      const vpsUrl = new URL(url.pathname + url.search, 'http://95.182.87.115');

      let response = null;
      let lastErr = null;

      const isFromWorker = request.headers.get('x-from-worker') === '1' || request.headers.get('X-From-Worker') === '1';

      if (isFromWorker) {
        // Direct to VPS without looping back to Cloudflare
        try {
          response = await fetch(vpsUrl.toString(), {
            method: request.method,
            headers: outgoingHeaders,
            body: reqBody
          });
        } catch (e) {
          throw new Error('Direct VPS fetch failed: ' + e.message);
        }
      } else {
        // 1. Primary: Cloudflare Cloud KV Storage (Serverless & independent of VPS filtering)
        try {
          const cfResp = await fetch(cfUrl.toString(), {
            method: request.method,
            headers: outgoingHeaders,
            body: reqBody
          });
          if (cfResp && (cfResp.status === 200 || cfResp.status === 204)) {
            response = cfResp;
          } else {
            lastErr = new Error('Cloudflare returned status ' + (cfResp ? cfResp.status : 'null'));
          }
        } catch (e) {
          lastErr = e;
        }

        // 2. Secondary Fallback: VPS Standalone SQLite (if Cloudflare KV is rate-limited or unreachable)
        if (!response) {
          try {
            response = await fetch(vpsUrl.toString(), {
              method: request.method,
              headers: outgoingHeaders,
              body: reqBody
            });
          } catch (e) {
            throw new Error('Both Cloudflare and VPS failed: ' + (lastErr ? lastErr.message : '') + ' | ' + e.message);
          }
        }
      }

      const respHeaders = new Headers(response.headers);
      respHeaders.set('Access-Control-Allow-Origin', '*');
      respHeaders.set('Access-Control-Allow-Headers', 'Content-Type, X-Admin-Token, X-Edge-Secret');
      respHeaders.set('X-Served-By', 'ArvanCloud-Edge');

      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers: respHeaders
      });
    } catch (err) {
      return new Response(JSON.stringify({
        success: false,
        error: 'Proxy to backend failed: ' + err.message
      }), {
        status: 502,
        headers: { 'content-type': 'application/json; charset=utf-8' }
      });
    }
  }

  return new Response('Not Found', { status: 404 });
}
