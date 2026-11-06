/**
 * Marzban Panel Adapter
 */
export class MarzbanAdapter {
  constructor(baseUrl, adminToken) {
    this.baseUrl = baseUrl ? baseUrl.replace(/\/+$/, '') : '';
    this.adminToken = adminToken;
  }

  async fetchSub(subUrl) {
    const res = await fetch(subUrl, {
      headers: {
        'User-Agent': 'v2rayNG/1.8.12',
        'Accept': 'text/plain, application/json'
      }
    });
    if (!res.ok) throw new Error(`Marzban sub fetch failed: HTTP ${res.status}`);
    return await res.text();
  }

  async getUser(username) {
    if (!this.adminToken) return null;
    try {
      const res = await fetch(`${this.baseUrl}/api/user/${username}`, {
        headers: {
          'Authorization': `Bearer ${this.adminToken}`
        }
      });
      if (res.ok) return await res.json();
    } catch (e) {
      console.error('Marzban getUser error:', e);
    }
    return null;
  }
}
