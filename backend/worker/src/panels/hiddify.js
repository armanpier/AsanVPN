/**
 * Hiddify Panel Adapter
 */
export class HiddifyAdapter {
  constructor(baseUrl, adminSecret) {
    this.baseUrl = baseUrl ? baseUrl.replace(/\/+$/, '') : '';
    this.adminSecret = adminSecret;
  }

  async fetchSub(subUrl) {
    const res = await fetch(subUrl, {
      headers: {
        'User-Agent': 'v2rayNG/1.8.12'
      }
    });
    if (!res.ok) throw new Error(`Hiddify sub fetch failed: HTTP ${res.status}`);
    return await res.text();
  }
}
