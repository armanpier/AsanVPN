import logging
import httpx
from config import WORKER_URL, ADMIN_TOKEN

logger = logging.getLogger("WorkerClient")

class WorkerClient:
    def __init__(self, base_url: str = None, token: str = None):
        self.base_url = (base_url or WORKER_URL).rstrip("/")
        self.fallback_url = "http://127.0.0.1:80"
        self.token = token or ADMIN_TOKEN
        self.headers = {
            "Content-Type": "application/json",
            "X-Admin-Token": self.token,
            "User-Agent": "AsanVpn-SalesBot/1.0"
        }

    async def _post_with_fallback(self, path: str, json_data: dict, timeout: float = 20.0):
        primary_url = f"{self.base_url}{path}"
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                res = await client.post(primary_url, json=json_data, headers=self.headers)
                if res.status_code in (200, 201):
                    data = res.json()
                    # If this created/updated a user, mirror the resulting user WITH its PIN to VPS
                    if data and isinstance(data, dict):
                        user_obj = data.get("user")
                        if user_obj and user_obj.get("pin"):
                            try:
                                async with httpx.AsyncClient(timeout=3.0) as fb_client:
                                    await fb_client.post(f"{self.fallback_url}/api/v1/admin/user", json=user_obj, headers=self.headers)
                            except Exception:
                                pass
                        elif path != "/api/v1/admin/create":
                            try:
                                async with httpx.AsyncClient(timeout=3.0) as fb_client:
                                    await fb_client.post(f"{self.fallback_url}{path}", json=json_data, headers=self.headers)
                            except Exception:
                                pass
                    return data
                else:
                    logger.warning(f"Primary {primary_url} returned {res.status_code}: {res.text}. Triggering fallback...")
        except Exception as e:
            logger.warning(f"Primary {primary_url} failed: {e}. Triggering fallback...")

        # Secondary Fallback (Local VPS SQLite Engine)
        fallback_url = f"{self.fallback_url}{path}"
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                res = await client.post(fallback_url, json=json_data, headers=self.headers)
                if res.status_code in (200, 201):
                    logger.info(f"Fallback {fallback_url} succeeded!")
                    return res.json()
                else:
                    logger.error(f"Fallback {fallback_url} failed with {res.status_code}: {res.text}")
        except Exception as e:
            logger.error(f"Fallback {fallback_url} exception: {e}")

        return None

    async def _get_with_fallback(self, path: str, timeout: float = 15.0):
        primary_url = f"{self.base_url}{path}"
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                res = await client.get(primary_url, headers=self.headers)
                if res.status_code == 200:
                    return res.json()
        except Exception as e:
            logger.warning(f"Primary GET {primary_url} failed: {e}. Triggering fallback...")

        fallback_url = f"{self.fallback_url}{path}"
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                res = await client.get(fallback_url, headers=self.headers)
                if res.status_code == 200:
                    return res.json()
        except Exception as e:
            logger.error(f"Fallback GET {fallback_url} failed: {e}")

        return None

    async def get_panels(self) -> list:
        """Fetch list of all configured upstream proxy panels sorted by priority"""
        data = await self._get_with_fallback("/api/v1/admin/panels", timeout=10.0)
        if data and "panels" in data:
            panels = data.get("panels", [])
            panels.sort(key=lambda p: (p.get("priority") or 99))
            return panels
        return []

    async def save_panel(self, panel_data: dict) -> dict:
        """Add, update, or change priority of a panel"""
        data = await self._post_with_fallback("/api/v1/admin/panels/save", panel_data, timeout=15.0)
        return data.get("panel", {}) if data else None

    async def test_panel(self, panel_data: dict) -> dict:
        """Test live latency and connectivity to a proxy panel"""
        data = await self._post_with_fallback("/api/v1/admin/panels/test", panel_data, timeout=15.0)
        return data if data else {"ok": False, "error": "All endpoints failed"}

    async def create_user(self, name: str, days: int = 30, traffic_gb: float = 0, max_devices: int = 1, panel_ids: list = None, telegram_id: int = None, is_test: bool = False) -> dict:
        """
        Create a new user with an 8-digit PIN and provision them on remote proxy panels with exact traffic and days.
        """
        if panel_ids is None:
            panels = await self.get_panels()
            panel_ids = [p["id"] for p in panels if p.get("enabled", True) is not False]
            if not panel_ids and panels:
                panel_ids = [p["id"] for p in panels]

        payload = {
            "name": name,
            "days": int(days),
            "traffic_gb": float(traffic_gb),
            "max_devices": int(max_devices),
            "panel_ids": panel_ids
        }
        if telegram_id:
            payload["telegram_id"] = int(telegram_id)
        if is_test:
            payload["is_test"] = True

        data = await self._post_with_fallback("/api/v1/admin/create", payload, timeout=25.0)
        if data and data.get("user"):
            logger.info(f"User created: pin={data.get('user', {}).get('pin')} traffic={traffic_gb}GB days={days}")
            return data.get("user", {})
        return None

    async def extend_user(self, pin: str, days: int = 30, traffic_gb: float = 0) -> dict:
        """
        Extend an existing user subscription by adding days and gigabytes.
        """
        payload = {
            "pin": str(pin).strip(),
            "days": int(days),
            "traffic_gb": float(traffic_gb)
        }
        data = await self._post_with_fallback("/api/v1/admin/extend", payload, timeout=20.0)
        if data and data.get("status") == "success":
            logger.info(f"User PIN {pin} extended by {days} days and {traffic_gb} GB")
            return data
        return None

    async def get_user_by_pin(self, pin: str) -> dict:
        """
        Fetch a user's details and live status from the worker or fallback VPS.
        """
        clean_pin = str(pin).strip()
        data_single = await self._get_with_fallback(f"/api/v1/admin/user?pin={clean_pin}", timeout=10.0)
        if data_single and data_single.get("user"):
            return data_single.get("user")

        data_all = await self._get_with_fallback("/api/v1/admin/users", timeout=15.0)
        if data_all and data_all.get("users"):
            for u in data_all.get("users", []):
                if str(u.get("pin", "")).strip() == clean_pin:
                    return u
        return None

    async def find_users_by_telegram_id(self, telegram_id: int) -> list:
        """
        Find all users created for a specific telegram_id.
        """
        matched = []
        data = await self._get_with_fallback("/api/v1/admin/users", timeout=15.0)
        if data and data.get("users"):
            str_tid = str(telegram_id).strip()
            for u in data.get("users", []):
                u_tid = str(u.get("telegram_id", "")).strip()
                name = str(u.get("name", ""))
                email = str(u.get("email", ""))
                if not u.get("pin"):
                    continue
                if u_tid and u_tid == str_tid:
                    matched.append(u)
                elif f"@{str_tid}" in name or f"TG {str_tid}" in name or f"telegram{str_tid}" in name.lower() or f"@{str_tid}" in email:
                    matched.append(u)
        return matched

    async def delete_user(self, pin: str) -> dict:
        """
        Delete a user by PIN from Worker and fallback panels.
        """
        payload = {"pin": str(pin).strip()}
        data = await self._post_with_fallback("/api/v1/admin/delete", payload, timeout=25.0)
        return data
