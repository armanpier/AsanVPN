import asyncio
import sys
import httpx
from config import WORKER_URL, ADMIN_TOKEN

sys.stdout.reconfigure(encoding='utf-8')

async def check_panels():
    headers = {
        "Content-Type": "application/json",
        "X-Admin-Token": ADMIN_TOKEN,
        "User-Agent": "AsanVpn-SalesBot/1.0"
    }
    async with httpx.AsyncClient(timeout=15.0) as client:
        res = await client.get(f"{WORKER_URL}/api/v1/admin/panels", headers=headers)
        if res.status_code == 200:
            panels = res.json().get("panels", [])
            print(f"Total panels: {len(panels)}")
            for p in panels:
                print(f"- ID: {p.get('id')}, Name: {p.get('name')}, Type: {p.get('type')}, Priority: {p.get('priority')}, Enabled: {p.get('enabled')}")

if __name__ == "__main__":
    asyncio.run(check_panels())
