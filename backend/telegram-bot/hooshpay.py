import hmac
import hashlib
import json
import logging
import httpx
from config import HOOSHPAY_API_KEY, HOOSHPAY_API_SECRET, HOOSHPAY_BASE_URL, HOOSHPAY_RETURN_URL

logger = logging.getLogger("HooshPay")

class HooshPayClient:
    def __init__(self, api_key: str = None, api_secret: str = None, base_url: str = None):
        self.api_key = api_key or HOOSHPAY_API_KEY
        self.api_secret = api_secret or HOOSHPAY_API_SECRET
        self.base_url = (base_url or HOOSHPAY_BASE_URL).rstrip("/")
        self.headers = {
            "Content-Type": "application/json",
            "X-API-KEY": self.api_key,
            "User-Agent": "AsanVpn-TelegramBot/1.0"
        }

    async def create_invoice(self, amount_toman: int, order_id: str, description: str, return_url: str = None, callback_url: str = None) -> dict:
        """
        Create a new invoice on HooshPay.
        amount_toman: Amount in Iranian Tomans.
        """
        url = f"{self.base_url}/invoices"
        payload = {
            "amount": int(amount_toman),
            "fee_mode": "buyer",
            "description": description,
            "order_id": order_id,
            "return_url": return_url or HOOSHPAY_RETURN_URL
        }
        if callback_url:
            payload["callback_url"] = callback_url

        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.post(url, json=payload, headers=self.headers)
                data = res.json()
                if res.status_code == 200 and data.get("success"):
                    logger.info(f"HooshPay invoice created: uid={data.get('data', {}).get('uid')} order={order_id}")
                    return data.get("data", {})
                else:
                    logger.error(f"Failed to create HooshPay invoice: status={res.status_code} body={res.text}")
                    return None
        except Exception as e:
            logger.exception(f"Exception creating HooshPay invoice: {e}")
            return None

    async def verify_invoice(self, invoice_uid: str) -> dict:
        """
        Verify payment of an invoice with HooshPay.
        Returns: { 'success': True/False, 'paid': True/False, 'status': 'paid'|'pending', 'data': {...} }
        """
        url = f"{self.base_url}/invoices/{invoice_uid}/verify"
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.post(url, json={}, headers=self.headers)
                data = res.json()
                return data
        except Exception as e:
            logger.exception(f"Exception verifying HooshPay invoice {invoice_uid}: {e}")
            return {"success": False, "paid": False, "error": str(e)}

    async def get_invoice(self, invoice_uid: str) -> dict:
        """
        Get latest details of an invoice.
        """
        url = f"{self.base_url}/invoices/{invoice_uid}"
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, headers=self.headers)
                return res.json()
        except Exception as e:
            logger.exception(f"Exception getting HooshPay invoice {invoice_uid}: {e}")
            return {"success": False, "error": str(e)}

    @staticmethod
    def verify_webhook_signature(raw_body: str, received_signature: str, secret: str = None) -> bool:
        """
        Verify HMAC-SHA256 signature sent in X-HooshPay-Signature header.
        Keys in JSON body must be recursively sorted.
        """
        sec = secret or HOOSHPAY_API_SECRET
        if not raw_body or not received_signature or not sec:
            return False

        try:
            parsed = json.loads(raw_body)

            def sort_json(obj):
                if isinstance(obj, dict):
                    return {k: sort_json(v) for k, v in sorted(obj.items())}
                if isinstance(obj, list):
                    return [sort_json(x) for x in obj]
                return obj

            sorted_str = json.dumps(sort_json(parsed), separators=(',', ':'), ensure_ascii=False)
            computed = hmac.new(sec.strip().encode('utf-8'), sorted_str.encode('utf-8'), hashlib.sha256).hexdigest().lower()
            return hmac.compare_digest(computed, received_signature.strip().lower())
        except Exception as e:
            logger.error(f"Signature verification error: {e}")
            return False
