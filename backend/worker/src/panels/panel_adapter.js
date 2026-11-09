import { XUIAdapter } from './xui.js';
import { MarzbanAdapter } from './marzban.js';
import { PasarGuardAdapter } from './pasarguard.js';
import { HiddifyAdapter } from './hiddify.js';
import { fetchViaSocket, PanelManager, DEFAULT_INITIAL_PANELS } from './panel_manager.js';
import { parseSubscriptionText } from '../parsers/sub_parser.js';

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

    // If custom port (e.g. 9090, 2096, 14393) or direct IP, try fetchViaSocket first
    if (isDirectIp || isCustomPort) {
      try {
        const sockText = await fetchViaSocket(url);
        if (sockText && (sockText.includes('://') || sockText.length > 20)) {
          return { text: sockText, userInfo: null };
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
          const sockText = await fetchViaSocket(url);
          return { text: sockText, userInfo: null };
        } catch (sockErr) {
          const errBody = await response.text().catch(() => '');
          throw new Error(`Upstream panel (${panelType}) returned HTTP ${response.status}: ${errBody.slice(0, 150)}`);
        }
      }

      const userinfoHeader = response.headers.get('subscription-userinfo');
      let userInfo = null;
      if (userinfoHeader) {
        userInfo = {};
        userinfoHeader.split(';').forEach(p => {
          const [k, v] = p.trim().split('=');
          if (k && v) {
            const num = parseInt(v, 10);
            if (!isNaN(num)) userInfo[k.toLowerCase()] = num;
          }
        });
      }

      const text = await response.text();
      return { text, userInfo };
    } catch (err) {
      clearTimeout(timeout);
      try {
        const sockText = await fetchViaSocket(url);
        return { text: sockText, userInfo: null };
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

    // Calculate mergedUserInfo
    let mergedUserInfo = null;
    for (const s of successful) {
      if (s.userInfo) {
        if (!mergedUserInfo) {
          mergedUserInfo = { ...s.userInfo };
          if (mergedUserInfo.total === 0) {
            mergedUserInfo.traffic_remaining_bytes = -1;
          } else if (typeof mergedUserInfo.total === 'number') {
            const used = (mergedUserInfo.upload || 0) + (mergedUserInfo.download || 0);
            mergedUserInfo.traffic_remaining_bytes = Math.max(0, mergedUserInfo.total - used);
          }
        } else {
          // --- HIGHER REMAINING TRAFFIC RULE ---
          const sIsUnlimited = (s.userInfo.total === 0);
          const mIsUnlimited = (mergedUserInfo.total === 0 || mergedUserInfo.traffic_remaining_bytes === -1);

          if (sIsUnlimited || mIsUnlimited) {
            mergedUserInfo.total = 0;
            mergedUserInfo.traffic_remaining_bytes = -1;
          } else {
            const sUsed = (s.userInfo.upload || 0) + (s.userInfo.download || 0);
            const sRem = Math.max(0, (s.userInfo.total || 0) - sUsed);

            const mRem = mergedUserInfo.traffic_remaining_bytes !== undefined
              ? mergedUserInfo.traffic_remaining_bytes
              : Math.max(0, (mergedUserInfo.total || 0) - ((mergedUserInfo.upload || 0) + (mergedUserInfo.download || 0)));

            if (sRem > mRem) {
              mergedUserInfo.total = s.userInfo.total;
              mergedUserInfo.upload = s.userInfo.upload || 0;
              mergedUserInfo.download = s.userInfo.download || 0;
              mergedUserInfo.traffic_remaining_bytes = sRem;
            } else {
              mergedUserInfo.traffic_remaining_bytes = mRem;
            }
          }

          // --- LATER EXPIRY DATE RULE (HIGHER DAYS REMAINED) ---
          const sExp = s.userInfo.expire;
          const mExp = mergedUserInfo.expire;

          if (sExp === 0 || mExp === 0 || sExp === null || mExp === null) {
            mergedUserInfo.expire = 0; // unlimited
          } else if (sExp && mExp) {
            mergedUserInfo.expire = Math.max(sExp, mExp); // later expiry
          } else if (sExp) {
            mergedUserInfo.expire = sExp;
          }
        }
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
