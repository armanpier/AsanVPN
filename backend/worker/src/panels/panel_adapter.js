import { XUIAdapter } from './xui.js';
import { MarzbanAdapter } from './marzban.js';
import { PasarGuardAdapter } from './pasarguard.js';
import { HiddifyAdapter } from './hiddify.js';
import { fetchViaSocket } from './panel_manager.js';

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

    const isDirectIpWithCfPort = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(parsed.hostname) &&
      ['2052', '2053', '2082', '2083', '2086', '2087', '2095', '2096', '8080', '8443', '8880'].includes(parsed.port);

    if (isDirectIpWithCfPort) {
      try {
        return await fetchViaSocket(url);
      } catch (socketErr) {
        throw new Error(`fetchViaSocket failed: ${socketErr.message || String(socketErr)}`);
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
        const errBody = await response.text().catch(() => '');
        if (errBody.includes('1003') || response.status === 403) {
          try {
            const sockText = await fetchViaSocket(url);
            return { text: sockText, userInfo: null };
          } catch (sockErr) {
            throw new Error(`Upstream panel (${panelType}) returned HTTP ${response.status}: ${errBody.slice(0, 150)}`);
          }
        }
        throw new Error(`Upstream panel (${panelType}) returned HTTP ${response.status}: ${errBody.slice(0, 150)}`);
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

  static async fetchSubscription(userRecord) {
    const urls = this.extractUrls(userRecord);
    if (urls.length === 0) {
      throw new Error('User record has no upstream_sub_url');
    }

    if (urls.length === 1) {
      return await this.fetchSingleSubscription(urls[0], userRecord.panel_type);
    }

    // Multiple URLs: fetch concurrently
    const results = await Promise.allSettled(
      urls.map(u => this.fetchSingleSubscription(u, userRecord.panel_type))
    );

    const successful = [];
    for (const r of results) {
      if (r.status === 'fulfilled' && r.value) {
        successful.push(r.value);
      }
    }

    if (successful.length === 0) {
      const firstRejected = results.find(r => r.status === 'rejected');
      throw (firstRejected ? firstRejected.reason : new Error('Failed to fetch from all upstream panels'));
    }

    const combinedTexts = successful.map(s => typeof s === 'string' ? s : (s.text || '')).filter(Boolean);

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
      userInfo: mergedUserInfo
    };
  }
}
