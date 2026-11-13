// ==========================================
// AsanVPN Gateway on ArvanCloud Edge Computing
// Dual-Cloud Primary (Cloudflare Worker) & VPS Fallback
// OPSEC Hardened: Stealth Protected Admin Dashboard & Client Bootstrap
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
      pop: (request.headers.get && request.headers.get('X-Country-Code')) || 'IR',
      time: new Date().toISOString()
    }), {
      headers: { 'content-type': 'application/json; charset=utf-8' }
    });
  }

  // Read cookies and admin key for stealth authorization
  let reqCookie = '';
  try {
    if (request.headers) {
      if (typeof request.headers.get === 'function') {
        reqCookie = request.headers.get('cookie') || request.headers.get('Cookie') || '';
      } else {
        reqCookie = request.headers['cookie'] || request.headers['Cookie'] || '';
      }
    }
  } catch (_) {}

  const queryKey = url.searchParams.get('key') || url.searchParams.get('token') || url.searchParams.get('auth') || '';
  const isAuthorizedAdmin = (queryKey === 'Arman4237340') || reqCookie.includes('asan_edge_auth=Arman4237340');

  // Handle logout
  if (path === '/logout') {
    const respHeaders = new Headers();
    respHeaders.set('Set-Cookie', 'asan_edge_auth=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly');
    respHeaders.set('Location', '/');
    return new Response(null, { status: 302, headers: respHeaders });
  }

  // 2. Admin Dashboard with Stealth Protection
  // If the admin provides the secret key or holds the authorized session cookie, serve the full Dashboard!
  // If unauthenticated, return the neutral decoy response so domestic crawlers cannot detect the VPN dashboard.
  if (path === '/' || path === '/admin' || path === '/dashboard' || path === '/panel' || path === '/portal') {
    if (isAuthorizedAdmin) {
      try {
        const cfDashboardUrl = 'https://asanvpn-worker.hi-deploy.workers.dev/';
        const cfHtmlResp = await fetch(cfDashboardUrl, {
          headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AsanVPN-Edge/1.0',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
          }
        });
        if (cfHtmlResp && cfHtmlResp.status === 200) {
          const respHeaders = new Headers(cfHtmlResp.headers);
          respHeaders.set('Content-Type', 'text/html; charset=utf-8');
          respHeaders.set('Set-Cookie', 'asan_edge_auth=Arman4237340; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000');
          respHeaders.set('X-Served-By', 'ArvanCloud-Edge');
          return new Response(cfHtmlResp.body, {
            status: 200,
            headers: respHeaders
          });
        }
      } catch (dashErr) {
        console.error('Failed to proxy dashboard:', dashErr);
      }
    }

    // Decoy Response for unauthenticated visitors / scanners / public
    return new Response(JSON.stringify({
      status: 'active',
      service: 'AsanVPN Edge Routing Gateway',
      pop: (request.headers.get && request.headers.get('X-Country-Code')) || 'IR',
      time: new Date().toISOString()
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
      // Allow if request has valid edge secret, or valid admin token, or authorized admin cookie!
      const edgeSecretHeader = (request.headers.get && (request.headers.get('x-edge-secret') || request.headers.get('X-Edge-Secret'))) || '';
      
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

      if (path.startsWith('/api/v1/admin/')) {
        const hasAuthCookie = reqCookie.includes('asan_edge_auth=Arman4237340');
        const hasValidToken = (adminToken === 'Arman4237340');
        const hasEdgeSecret = (edgeSecretHeader === 'asanvpn-edge-internal-secret-2026');

        if (!hasAuthCookie && !hasValidToken && !hasEdgeSecret) {
          return new Response(JSON.stringify({
            error: 'Forbidden',
            message: 'Administrative endpoints disabled on domestic edge gateway.'
          }), {
            status: 403,
            headers: { 'Content-Type': 'application/json; charset=utf-8' }
          });
        }
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
      } else if (reqCookie.includes('asan_edge_auth=Arman4237340')) {
        outgoingHeaders['X-Admin-Token'] = 'Arman4237340';
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

      const isFromWorker = request.headers.get && (request.headers.get('x-from-worker') === '1' || request.headers.get('X-From-Worker') === '1');

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
