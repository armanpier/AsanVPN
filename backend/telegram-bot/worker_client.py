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
        """Fetch list of all configured upstream proxy panels"""
        url = f"{self.base_url}/api/v1/admin/panels"
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                res = await client.get(url, headers=self.headers)
                if res.status_code == 200:
                    data = res.json()
                    return data.get("panels", [])
        except Exception as e:
            logger.error(f"Error fetching panels: {e}")
        return []

    async def create_user(self, name: str, days: int = 30, traffic_gb: float = 0, max_devices: int = 1, panel_ids: list = None) -> dict:
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
        Matches user names containing the telegram_id.
        """
        url = f"{self.base_url}/api/v1/admin/users"
        matched = []
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, headers=self.headers)
                if res.status_code == 200:
                    data = res.json()
                    users = data.get("users", [])
                    tag = str(telegram_id)
                    for u in users:
                        name = str(u.get("name", ""))
                        if tag in name and u.get("pin"):
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

