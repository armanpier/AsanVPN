import logging
import httpx
from config import WORKER_URL, ADMIN_TOKEN

logger = logging.getLogger("WorkerClient")

class WorkerClient:
    def __init__(self, base_url: str = None, token: str = None):
        self.base_url = (base_url or WORKER_URL).rstrip("/")
        self.token = token or ADMIN_TOKEN
        self.headers = {
            "Content-Type": "application/json",
            "X-Admin-Token": self.token,
            "User-Agent": "AsanVpn-SalesBot/1.0"
        }

    async def get_panels(self) -> list:
        """Fetch list of all configured upstream proxy panels sorted by priority"""
        url = f"{self.base_url}/api/v1/admin/panels"
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                res = await client.get(url, headers=self.headers)
                if res.status_code == 200:
                    data = res.json()
                    panels = data.get("panels", [])
                    panels.sort(key=lambda p: (p.get("priority") or 99))
                    return panels
        except Exception as e:
            logger.error(f"Error fetching panels: {e}")
        return []

    async def save_panel(self, panel_data: dict) -> dict:
        """Add, update, or change priority of a panel"""
        url = f"{self.base_url}/api/v1/admin/panels/save"
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.post(url, json=panel_data, headers=self.headers)
                if res.status_code in (200, 201):
                    return res.json().get("panel", {})
        except Exception as e:
            logger.error(f"Error saving panel: {e}")
        return None

    async def test_panel(self, panel_data: dict) -> dict:
        """Test live latency and connectivity to a proxy panel"""
        url = f"{self.base_url}/api/v1/admin/panels/test"
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.post(url, json=panel_data, headers=self.headers)
                return res.json()
        except Exception as e:
            logger.error(f"Error testing panel: {e}")
            return {"ok": False, "error": str(e)}

    async def create_user(self, name: str, days: int = 30, traffic_gb: float = 0, max_devices: int = 1, panel_ids: list = None, telegram_id: int = None, is_test: bool = False) -> dict:
        """
        Create a new user with an 8-digit PIN and provision them on remote proxy panels with exact traffic and days.
        """
        url = f"{self.base_url}/api/v1/admin/create"

        # If no panels specified, fetch all active panels
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

        try:
            async with httpx.AsyncClient(timeout=25.0) as client:
                res = await client.post(url, json=payload, headers=self.headers)
                data = res.json()
                if res.status_code in (200, 201):
                    logger.info(f"User created in Cloudflare Worker: pin={data.get('user', {}).get('pin')} traffic={traffic_gb}GB days={days}")
                    return data.get("user", {})
                else:
                    logger.error(f"Worker create user failed: status={res.status_code} body={res.text}")
                    return None
        except Exception as e:
            logger.exception(f"Exception creating user in worker: {e}")
            return None

    async def extend_user(self, pin: str, days: int = 30, traffic_gb: float = 0) -> dict:
        """
        Extend an existing user subscription by adding days and gigabytes.
        """
        url = f"{self.base_url}/api/v1/admin/extend"
        payload = {
            "pin": str(pin).strip(),
            "days": int(days),
            "traffic_gb": float(traffic_gb)
        }

        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.post(url, json=payload, headers=self.headers)
                data = res.json()
                if res.status_code == 200 and data.get("status") == "success":
                    logger.info(f"User PIN {pin} extended by {days} days and {traffic_gb} GB")
                    return data
                else:
                    logger.error(f"Worker extend failed: status={res.status_code} body={res.text}")
                    return None
        except Exception as e:
            logger.exception(f"Exception extending user in worker: {e}")
            return None

    async def get_user_by_pin(self, pin: str) -> dict:
        """
        Fetch a user's details and live status from the worker.
        """
        clean_pin = str(pin).strip()
        url_single = f"{self.base_url}/api/v1/admin/user?pin={clean_pin}"
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                res = await client.get(url_single, headers=self.headers)
                if res.status_code == 200:
                    data = res.json()
                    if data.get("user"):
                        return data.get("user")
        except Exception:
            pass

        url = f"{self.base_url}/api/v1/admin/users"
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, headers=self.headers)
                if res.status_code == 200:
                    data = res.json()
                    users = data.get("users", [])
                    for u in users:
                        if str(u.get("pin", "")).strip() == clean_pin:
                            return u
        except Exception as e:
            logger.error(f"Error fetching user by PIN {pin}: {e}")
        return None

    async def find_users_by_telegram_id(self, telegram_id: int) -> list:
        """
        Find all users on Cloudflare Worker created for a specific telegram_id.
        Matches user records strictly by telegram_id, or explicit Telegram tag in name/email.
        """
        url = f"{self.base_url}/api/v1/admin/users"
        matched = []
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, headers=self.headers)
                if res.status_code == 200:
                    data = res.json()
                    users = data.get("users", [])
                    str_tid = str(telegram_id).strip()
                    for u in users:
                        u_tid = str(u.get("telegram_id", "")).strip()
                        name = str(u.get("name", ""))
                        email = str(u.get("email", ""))
                        if not u.get("pin"):
                            continue
                        if u_tid and u_tid == str_tid:
                            matched.append(u)
                        elif f"@{str_tid}" in name or f"TG {str_tid}" in name or f"telegram{str_tid}" in name.lower() or f"@{str_tid}" in email:
                            matched.append(u)
        except Exception as e:
            logger.error(f"Error finding users by telegram_id {telegram_id}: {e}")
        return matched

    async def delete_user(self, pin: str) -> dict:
        """
        Delete a user by PIN from Cloudflare Worker and remove them from all remote proxy panels.
        """
        url = f"{self.base_url}/api/v1/admin/delete"
        payload = {"pin": str(pin).strip()}
        try:
            async with httpx.AsyncClient(timeout=25.0) as client:
                res = await client.post(url, json=payload, headers=self.headers)
                data = res.json()
                if res.status_code == 200:
                    logger.info(f"User PIN {pin} successfully deleted from worker and remote panels: {data}")
                    return data
                else:
                    logger.error(f"Worker delete failed: status={res.status_code} body={res.text}")
                    return None
        except Exception as e:
            logger.exception(f"Exception deleting user in worker: {e}")
            return None

