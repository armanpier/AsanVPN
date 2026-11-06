/**
 * PasarGuard Panel Adapter
 */
export class PasarGuardAdapter {
  constructor(baseUrl, apiSecret) {
    this.baseUrl = baseUrl ? baseUrl.replace(/\/+$/, '') : '';
    this.apiSecret = apiSecret;
  }

  async fetchSub(subUrl) {
    const res = await fetch(subUrl, {
      headers: {
        'User-Agent': 'v2rayNG/1.8.12'
      }
    });
    if (!res.ok) throw new Error(`PasarGuard sub fetch failed: HTTP ${res.status}`);
    return await res.text();
  }
}
