import { XUIAdapter } from './xui.js';
import { MarzbanAdapter } from './marzban.js';
import { PasarGuardAdapter } from './pasarguard.js';
import { HiddifyAdapter } from './hiddify.js';
import { fetchViaSocket, PanelManager, DEFAULT_INITIAL_PANELS } from './panel_manager.js';
import { parseSubscriptionText, parseUserInfoHeader, extractUserInfoFromSubscription } from '../parsers/sub_parser.js';

export class PanelAdapter {
  static extractUrls(userRecord) {
    const urls = [];
    if (Array.isArray(userRecord.upstream_sub_urls)) {
      userRecord.upstream_sub_urls.forEach(u => {
        if (typeof u === 'string') {
          const t = u.trim();
          if (t.startsWith('http') && !urls.includes(t)) urls.push(t);
        }
      });
    }
    if (userRecord.upstream_sub_url && typeof userRecord.upstream_sub_url === 'string') {
      userRecord.upstream_sub_url.split(/[\r\n,]+/).forEach(u => {
        const t = u.trim();
        if (t.startsWith('http') && !urls.includes(t)) urls.push(t);
      });
    }
    return urls;
  }

  static async fetchSingleSubscription(url, panelType = 'raw') {
    let parsed;
    try {
      parsed = new URL(url);
    } catch (e) {
      throw new Error(`Invalid URL: ${url}`);
    }

    const port = parsed.port || (parsed.protocol === 'https:' ? '443' : '80');
    const isDirectIp = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(parsed.hostname);
    const isCustomPort = (port !== '80' && port !== '443');

    const processPayload = (text, headers = {}) => {
      let userInfo = null;
      const userinfoHeader = headers['subscription-userinfo'] || (headers.get ? headers.get('subscription-userinfo') : null);
      if (userinfoHeader) {
        userInfo = parseUserInfoHeader(userinfoHeader);
      }
      const fallback = extractUserInfoFromSubscription(text);
      if (fallback) {
        if (!userInfo) {
          userInfo = fallback;
        } else {
          if (userInfo.traffic_remaining_bytes === undefined && fallback.traffic_remaining_bytes !== undefined) {
            userInfo.traffic_remaining_bytes = fallback.traffic_remaining_bytes;
          }
          if ((!userInfo.expire || userInfo.expire === 0) && fallback.expire) {
            userInfo.expire = fallback.expire;
            userInfo.days_left = fallback.days_left;
          }
        }
      }
      return { text, userInfo };
    };

    // If custom port (e.g. 9090, 2096, 2016, 14393) or direct IP, try fetchViaSocket first
    if (isDirectIp || isCustomPort) {
      try {
        const sockRes = await fetchViaSocket(url, { returnFull: true });
        if (sockRes && (sockRes.text.includes('://') || sockRes.text.length > 20)) {
          return processPayload(sockRes.text, sockRes.headers);
        }
      } catch (socketErr) {
        console.warn(`Initial fetchViaSocket for ${url} failed:`, socketErr.message);
      }
    }

    const headers = {
      'User-Agent': 'v2rayNG/1.8.12',
      'Accept': '*/*'
    };

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);

    try {
      const response = await fetch(url, {
        headers,
        signal: controller.signal
      });

      clearTimeout(timeout);

      if (!response.ok) {
        // Fallback to socket fetch on ANY non-2xx status (525, 403, 502, 1003, etc.)
        try {
          const sockRes = await fetchViaSocket(url, { returnFull: true });
          return processPayload(sockRes.text, sockRes.headers);
        } catch (sockErr) {
          const errBody = await response.text().catch(() => '');
          throw new Error(`Upstream panel (${panelType}) returned HTTP ${response.status}: ${errBody.slice(0, 150)}`);
        }
      }

      const headersMap = {};
      response.headers.forEach((v, k) => { headersMap[k.toLowerCase()] = v; });
      const text = await response.text();
      return processPayload(text, headersMap);
    } catch (err) {
      clearTimeout(timeout);
      try {
        const sockRes = await fetchViaSocket(url, { returnFull: true });
        return processPayload(sockRes.text, sockRes.headers);
      } catch (sockErr) {
        throw err;
      }
    }
  }

  static async fetchSubscription(userRecord, env = null) {
    let panels = [];
    if (env) {
      try {
        panels = await PanelManager.getPanels(env);
      } catch (_) {
        panels = DEFAULT_INITIAL_PANELS;
      }
    } else {
      panels = DEFAULT_INITIAL_PANELS;
    }

    // Build targets list with priority
    const targets = [];
    const seenUrls = new Set();

    // 1. If user has remote_panel_mappings, map them to configured panels
    if (userRecord.remote_panel_mappings && typeof userRecord.remote_panel_mappings === 'object') {
      for (const [panelId, mapping] of Object.entries(userRecord.remote_panel_mappings)) {
        if (mapping && mapping.subUrl && typeof mapping.subUrl === 'string') {
          const cleanUrl = mapping.subUrl.trim();
          if (cleanUrl.startsWith('http') && !seenUrls.has(cleanUrl)) {
            seenUrls.add(cleanUrl);
            const panel = panels.find(p => p.id === panelId);
            targets.push({
              url: cleanUrl,
              panelId: panelId,
              panelName: panel ? panel.name : (mapping.username ? `سرور (${mapping.username})` : 'سرور پروکسی'),
              priority: panel ? (panel.priority || 1) : 1,
              panelType: mapping.panelType || (panel ? panel.type : (userRecord.panel_type || 'raw'))
            });
          }
        }
      }
    }

    // 2. Also check any additional URLs in upstream_sub_urls or upstream_sub_url
    const rawUrls = this.extractUrls(userRecord);
    for (const u of rawUrls) {
      if (!seenUrls.has(u)) {
        seenUrls.add(u);
        // Try matching URL hostname to a configured panel
        const matched = panels.find(p => {
          try {
            const pHost = new URL(p.url).hostname;
            const uHost = new URL(u).hostname;
            if (pHost === uHost) return true;
            if (p.subBaseUrl && new URL(p.subBaseUrl).hostname === uHost) return true;
          } catch (_) {}
          return false;
        });

        targets.push({
          url: u,
          panelId: matched ? matched.id : `panel_raw_${targets.length + 1}`,
          panelName: matched ? matched.name : `سرور اختصاصی ${targets.length + 1}`,
          priority: matched ? (matched.priority || (targets.length + 1)) : (targets.length + 1),
          panelType: matched ? matched.type : (userRecord.panel_type || 'raw')
        });
      }
    }

    if (targets.length === 0) {
      throw new Error('User record has no upstream_sub_url');
    }

    // Sort targets by priority ascending (1 = highest priority)
    targets.sort((a, b) => (a.priority || 99) - (b.priority || 99));

    // Fetch all targets concurrently
    const results = await Promise.allSettled(
      targets.map(t => this.fetchSingleSubscription(t.url, t.panelType))
    );

    const successful = [];
    const allParsedNodes = [];
    const combinedTexts = [];

    results.forEach((r, idx) => {
      const target = targets[idx];
      if (r.status === 'fulfilled' && r.value) {
        const val = r.value;
        successful.push(val);

        const text = typeof val === 'string' ? val : (val.text || '');
        if (text) {
          combinedTexts.push(text);
          const nodes = parseSubscriptionText(text);
          if (Array.isArray(nodes) && nodes.length > 0) {
            nodes.forEach(node => {
              node.panel_id = target.panelId;
              node.panel_name = target.panelName;
              node.panel_priority = target.priority;
              allParsedNodes.push(node);
            });

            // Extract live userinfo from parsed nodes remarks if missing or incomplete
            if (!val.userInfo || val.userInfo.traffic_remaining_bytes === undefined || val.userInfo.days_left === undefined) {
              const nodeInfo = extractUserInfoFromSubscription(text, nodes);
              if (nodeInfo) {
                if (!val.userInfo) {
                  val.userInfo = nodeInfo;
                } else {
                  if (val.userInfo.traffic_remaining_bytes === undefined && nodeInfo.traffic_remaining_bytes !== undefined) {
                    val.userInfo.traffic_remaining_bytes = nodeInfo.traffic_remaining_bytes;
                  }
                  if ((!val.userInfo.expire || val.userInfo.expire === 0) && nodeInfo.expire) {
                    val.userInfo.expire = nodeInfo.expire;
                    val.userInfo.days_left = nodeInfo.days_left;
                  }
                  if ((!val.userInfo.days_left || val.userInfo.days_left <= 0) && nodeInfo.days_left) {
                    val.userInfo.days_left = nodeInfo.days_left;
                  }
                }
              }
            }
          }
        }
      } else {
        console.warn(`Upstream panel fetch failed for ${target.panelName} (${target.url}):`, r.reason?.message || r.reason);
      }
    });

    if (successful.length === 0 && allParsedNodes.length === 0) {
      const firstRejected = results.find(r => r.status === 'rejected');
      throw (firstRejected ? firstRejected.reason : new Error('Failed to fetch from all upstream panels'));
    }

    // Sort all parsed nodes by panel priority ascending
    allParsedNodes.sort((a, b) => (a.panel_priority || 99) - (b.panel_priority || 99));

    // Calculate mergedUserInfo across all successful panels
    let mergedUserInfo = null;
    let totalAccountQuota = null;
    let minRemaining = null;
    let sumUsedAcrossPanels = 0;
    let earliestActiveExpiry = null;
    let anyPanelActive = false;

    for (const s of successful) {
      if (s.userInfo) {
        anyPanelActive = true;
        const u = s.userInfo;

        // 1. Quota & Remaining traffic
        if (u.total !== undefined && u.total > 0) {
          if (totalAccountQuota === null) {
            totalAccountQuota = u.total;
          } else {
            totalAccountQuota = Math.max(totalAccountQuota, u.total);
          }
        }

        const used = (u.upload || 0) + (u.download || 0);
        sumUsedAcrossPanels += used;

        let rem = u.traffic_remaining_bytes;
        if (rem === undefined || rem === null) {
          if (u.total && u.total > 0) {
            rem = Math.max(0, u.total - used);
          }
        }
        if (typeof rem === 'number' && rem >= 0) {
          if (minRemaining === null || rem < minRemaining) {
            minRemaining = rem;
          }
        }

        // 2. Expiration (earliest active expiry date)
        if (u.expire && u.expire > 1000000000) {
          if (earliestActiveExpiry === null || u.expire < earliestActiveExpiry) {
            earliestActiveExpiry = u.expire;
          }
        }
      }
    }

    if (anyPanelActive) {
      mergedUserInfo = {};
      if (totalAccountQuota !== null && totalAccountQuota > 0) {
        mergedUserInfo.total = totalAccountQuota;
        const calculatedRem = Math.max(0, totalAccountQuota - sumUsedAcrossPanels);
        mergedUserInfo.traffic_remaining_bytes = (minRemaining !== null)
          ? Math.min(minRemaining, calculatedRem)
          : calculatedRem;
        mergedUserInfo.upload = 0;
        mergedUserInfo.download = Math.max(0, totalAccountQuota - mergedUserInfo.traffic_remaining_bytes);
      } else {
        // Unlimited
        mergedUserInfo.total = 0;
        mergedUserInfo.traffic_remaining_bytes = -1;
        mergedUserInfo.upload = 0;
        mergedUserInfo.download = 0;
      }

      mergedUserInfo.expire = earliestActiveExpiry || 0;
      if (mergedUserInfo.expire > 1000000000) {
        const nowSec = Math.floor(Date.now() / 1000);
        mergedUserInfo.days_left = Math.max(0, Math.ceil((mergedUserInfo.expire - nowSec) / 86400));
      }
    }

    return {
      text: combinedTexts.join('\n'),
      allTexts: combinedTexts,
      userInfo: mergedUserInfo,
      nodes: allParsedNodes
    };
  }
}
