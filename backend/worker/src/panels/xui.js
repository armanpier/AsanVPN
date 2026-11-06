/**
 * 3X-UI / X-UI Adapter
 */
export class XUIAdapter {
  constructor(baseUrl, username, password) {
    this.baseUrl = baseUrl ? baseUrl.replace(/\/+$/, '') : '';
    this.username = username;
    this.password = password;
    this.cookie = null;
  }

  async login() {
    if (!this.username || !this.password) return true;
    try {
      const res = await fetch(`${this.baseUrl}/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          username: this.username,
          password: this.password
        })
      });
      const cookie = res.headers.get('set-cookie');
      if (cookie) {
        this.cookie = cookie;
        return true;
      }
      return false;
    } catch (e) {
      console.error('XUI login error:', e);
      return false;
    }
  }

  async fetchSub(subUrl) {
    const res = await fetch(subUrl, {
      headers: {
        'User-Agent': 'v2rayNG/1.8.12'
      }
    });
    if (!res.ok) throw new Error(`3X-UI sub fetch failed: HTTP ${res.status}`);
    return await res.text();
  }
}
