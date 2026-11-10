import os

admin_ui_path = os.path.abspath('backend/worker/src/admin_ui.js')
with open(admin_ui_path, 'r', encoding='utf-8') as f:
    admin_ui_content = f.read()

# Change branding text in header from "سامانه ابری کلودفلر" to "سامانه ابری آروان (ArvanCloud Edge)"
admin_ui_content = admin_ui_content.replace('سامانه ابری کلودفلر', 'سامانه ابری آروان (ArvanCloud Edge)')
admin_ui_content = admin_ui_content.replace('export function getAdminDashboardHtml() {', 'function getAdminDashboardHtml() {')

bundle_code = f"""
// ==========================================
// AsanVPN Dashboard on ArvanCloud Edge Computing
// Dual-Cloud Primary (Cloudflare KV) & VPS Fallback
// ==========================================

{admin_ui_content}

addEventListener('fetch', event => {{
  event.respondWith(handleRequest(event.request));
}});

async function handleRequest(request) {{
  const url = new URL(request.url);
  const path = url.pathname;

  // 1. Health check endpoint
  if (path === '/health' || path === '/ping') {{
    return new Response(JSON.stringify({{
      status: 'ok',
      service: 'AsanVPN ArvanCloud Edge Gateway',
      pop: request.headers.get('X-Country-Code') || 'IR',
      time: new Date().toISOString()
    }}), {{
      headers: {{ 'content-type': 'application/json; charset=utf-8' }}
    }});
  }}

  // 2. Serve Admin Dashboard UI
  if (path === '/' || path === '/admin' || path === '/dashboard' || path === '/panel') {{
    return new Response(getAdminDashboardHtml(), {{
      headers: {{
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'X-Powered-By': 'ArvanCloud Edge Computing'
      }}
    }});
  }}

  // 3. Serve High-Speed APK Direct Download (domestic Iranian edge)
  if (path === '/download' || path === '/AsanVPN.apk' || path === '/apk') {{
    try {{
      const vpsApk = await fetch('http://95.182.87.115/download', {{
        headers: {{ 'User-Agent': 'ArvanEdge-Download/1.0' }}
      }});
      if (vpsApk && vpsApk.status === 200) {{
        const respHeaders = new Headers(vpsApk.headers);
        respHeaders.set('Access-Control-Allow-Origin', '*');
        respHeaders.set('Cache-Control', 'public, max-age=1800');
        respHeaders.set('X-Served-By', 'ArvanCloud-Edge');
        return new Response(vpsApk.body, {{
          status: 200,
          headers: respHeaders
        }});
      }}
    }} catch (e) {{}}
    return Response.redirect('https://github.com/armanpier/AsanVPN/releases/latest', 302);
  }}

  // 4. Proxy API requests with Cloudflare Primary & VPS Fallback
  if (path.startsWith('/api/')) {{
    try {{
      let adminToken = '';
      try {{
        if (request.headers) {{
          if (typeof request.headers.get === 'function') {{
            adminToken = request.headers.get('x-admin-token') || request.headers.get('X-Admin-Token') || '';
          }}
          if (!adminToken && request.headers['x-admin-token']) {{
            adminToken = request.headers['x-admin-token'];
          }}
          if (!adminToken && request.headers['X-Admin-Token']) {{
            adminToken = request.headers['X-Admin-Token'];
          }}
        }}
      }} catch (e) {{}}

      if (!adminToken) {{
        adminToken = url.searchParams.get('token') || url.searchParams.get('admin_token') || '';
      }}

      const contentType = (request.headers.get && request.headers.get('content-type')) || 'application/json';

      const outgoingHeaders = {{
        'Accept': 'application/json',
        'Content-Type': contentType,
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AsanVPN-Edge/1.0'
      }};
      if (adminToken) {{
        outgoingHeaders['X-Admin-Token'] = adminToken;
      }}

      // Clone or read body safely
      let reqBody = undefined;
      if (!['GET', 'HEAD'].includes(request.method)) {{
        reqBody = await request.arrayBuffer();
      }}

      const cfUrl = new URL(url.pathname + url.search, 'https://asanvpn-worker.hi-deploy.workers.dev');
      const vpsUrl = new URL(url.pathname + url.search, 'http://95.182.87.115');

      let response = null;
      let lastErr = null;

      // 1. Primary: Cloudflare Cloud KV Storage (Serverless & independent of any VPS filtering/changes)
      try {{
        const cfResp = await fetch(cfUrl.toString(), {{
          method: request.method,
          headers: outgoingHeaders,
          body: reqBody
        }});
        if (cfResp && (cfResp.status === 200 || cfResp.status === 204)) {{
          response = cfResp;
        }} else {{
          lastErr = new Error('Cloudflare returned status ' + (cfResp ? cfResp.status : 'null'));
        }}
      }} catch (e) {{
        lastErr = e;
      }}

      // 2. Secondary Fallback: VPS Standalone SQLite (if Cloudflare KV is rate-limited or unavailable)
      if (!response) {{
        try {{
          response = await fetch(vpsUrl.toString(), {{
            method: request.method,
            headers: outgoingHeaders,
            body: reqBody
          }});
        }} catch (e) {{
          throw new Error('Both Cloudflare and VPS failed: ' + (lastErr ? lastErr.message : '') + ' | ' + e.message);
        }}
      }}

      const respHeaders = new Headers(response.headers);
      respHeaders.set('Access-Control-Allow-Origin', '*');
      respHeaders.set('Access-Control-Allow-Headers', 'Content-Type, X-Admin-Token');
      respHeaders.set('X-Served-By', 'ArvanCloud-Edge');

      return new Response(response.body, {{
        status: response.status,
        statusText: response.statusText,
        headers: respHeaders
      }});
    }} catch (err) {{
      return new Response(JSON.stringify({{
        success: false,
        error: 'Proxy to backend failed: ' + err.message
      }}), {{
        status: 502,
        headers: {{ 'content-type': 'application/json; charset=utf-8' }}
      }});
    }}
  }}

  return new Response('Not Found', {{ status: 404 }});
}}
"""

out_dir = 'backend/arvan-edge'
os.makedirs(out_dir, exist_ok=True)
bundle_path = os.path.join(out_dir, 'bundle.js')
with open(bundle_path, 'w', encoding='utf-8') as f:
    f.write(bundle_code)

print(f"Generated updated Arvan bundle at {bundle_path}")
