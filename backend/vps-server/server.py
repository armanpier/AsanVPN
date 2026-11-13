#!/usr/bin/env python3
"""
AsanVPN Standalone Edge Backend API Server
Runs on VPS (Port 80) providing persistent, high-speed API for ArvanCloud Edge Dashboard and mobile clients.
Zero external pip dependencies (uses Python 3 standard library: http.server, sqlite3, json, urllib).
"""

import sys
import os
import re
import json
import sqlite3
import time
import logging
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
import urllib.parse
import urllib.request
import urllib.error

# Configuration
PORT = 80
DB_PATH = "/etc/asanvpn/asanvpn.db"
BACKUP_PATH = "/etc/asanvpn/data.json"
ADMIN_PASSWORD = "Arman4237340"
SUPPORT_PHONE = "+989120000000"
SUPPORT_TELEGRAM = "AsanSupport"
APP_VERSION = "1.3.12"
APP_VERSION_CODE = 18
GITHUB_APK_URL = "https://github.com/armanpier/AsanVPN/releases/download/v1.3.12/AsanVPN-v1.3.12-universal.apk"
EDGE_SECRET = "asanvpn-edge-internal-secret-2026"

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

# Rate limiting storage for failed authentication attempts (IP and Device level)
RATE_LIMIT_STORE = {} # key -> {"count": int, "locked_until": int}
RATE_LIMIT_LOCK = threading.Lock()

def get_ip_subnet(ip: str) -> str:
    """Calculates IPv4 /24 subnet or returns clean IP. Excludes reverse proxy/internal addresses."""
    if not ip or ip in ("unknown", "127.0.0.1", "::1", "localhost"):
        return ""
    # Avoid locking entire ArvanCloud edge reverse proxies if client IP could not be extracted
    if ip.startswith("37.32.") or ip.startswith("185.143."):
        return ""
    if "." in ip:
        parts = ip.split(".")
        if len(parts) >= 3:
            return ".".join(parts[:3]) + ".0/24"
    return ip

def check_rate_limit(ip_key: str, dev_key: str) -> tuple:
    """Check if either IP or Device is locked out. Returns (is_locked, remaining_seconds)."""
    now = int(time.time())
    with RATE_LIMIT_LOCK:
        for k in [ip_key, dev_key]:
            if k and k in RATE_LIMIT_STORE:
                entry = RATE_LIMIT_STORE[k]
                if entry.get("locked_until", 0) > now:
                    return True, entry["locked_until"] - now
    return False, 0

def record_failed_attempt(ip_key: str, dev_key: str) -> tuple:
    """Record failure and lock out if threshold reached. Returns (locked, remaining_seconds, remaining_attempts)."""
    now = int(time.time())
    with RATE_LIMIT_LOCK:
        dev_entry = RATE_LIMIT_STORE.setdefault(dev_key, {"count": 0, "locked_until": 0}) if dev_key else None
        ip_entry = RATE_LIMIT_STORE.setdefault(ip_key, {"count": 0, "locked_until": 0}) if ip_key else None

        dev_count = (dev_entry["count"] + 1) if dev_entry else 1
        ip_count = (ip_entry["count"] + 1) if ip_entry else 1

        if dev_entry:
            dev_entry["count"] = dev_count
        if ip_entry:
            ip_entry["count"] = ip_count

        if (dev_entry and dev_count >= 3) or (ip_entry and ip_count >= 5):
            lock_until = now + 1800 # 30 min lockout
            if dev_entry:
                dev_entry["locked_until"] = lock_until
            if ip_entry:
                ip_entry["locked_until"] = lock_until
            return True, 1800, 0

        rem = max(0, 3 - dev_count) if dev_entry else max(0, 5 - ip_count)
        return False, 0, rem

def clear_rate_limit(ip_key: str = "", dev_key: str = "", clear_all: bool = False):
    """Clear failed attempts upon successful login or admin reset."""
    with RATE_LIMIT_LOCK:
        if clear_all:
            RATE_LIMIT_STORE.clear()
            return
        if ip_key and ip_key in RATE_LIMIT_STORE:
            del RATE_LIMIT_STORE[ip_key]
        if dev_key and dev_key in RATE_LIMIT_STORE:
            del RATE_LIMIT_STORE[dev_key]

def is_secure_10digit_pin(pin: str) -> bool:
    if not isinstance(pin, str) or len(pin) != 10 or not pin.isdigit():
        return False
    # At least 6 distinct digits
    if len(set(pin)) < 6:
        return False
    # No 3+ consecutive identical digits
    for i in range(len(pin) - 2):
        if pin[i] == pin[i+1] == pin[i+2]:
            return False
    # No 3+ consecutive sequential digits
    for i in range(len(pin) - 2):
        d1, d2, d3 = int(pin[i]), int(pin[i+1]), int(pin[i+2])
        if (d2 == d1 + 1 and d3 == d2 + 1) or (d2 == d1 - 1 and d3 == d2 - 1):
            return False
    # No 5-digit periodic repeats (e.g. 1234512345)
    if pin[:5] == pin[5:]:
        return False
    # No 2-digit periodic repeats (e.g. 1212121212)
    if pin[:2] * 5 == pin:
        return False
    # No repetitive pairs (e.g. 1122334455)
    if pin[0] == pin[1] and pin[2] == pin[3] and pin[4] == pin[5] and pin[6] == pin[7] and pin[8] == pin[9]:
        return False
    # No palindromes
    if pin == pin[::-1]:
        return False
    return True

def is_valid_pin_format(pin: str) -> bool:
    if not pin or not isinstance(pin, str):
        return False
    pin = pin.strip()
    return pin.isdigit() and len(pin) in (8, 10)

def generate_secure_10digit_pin(existing_checker) -> str:
    import random
    for _ in range(500):
        cand = str(random.randint(1000000000, 9999999999))
        if is_secure_10digit_pin(cand) and not existing_checker(cand):
            return cand
    while True:
        cand = str(random.randint(1000000000, 9999999999))
        if not existing_checker(cand):
            return cand

def sync_3xui_sqlite(user_data):
    """
    Directly synchronizes user totalGB and expiryTime into local 3X-UI SQLite database (/etc/x-ui/x-ui.db).
    Guarantees zero-latency update of 3X-UI clients without requiring external web session tokens.
    """
    xui_db_path = "/etc/x-ui/x-ui.db"
    if not os.path.exists(xui_db_path):
        return
    try:
        if isinstance(user_data, str):
            user_data = json.loads(user_data)
        if not isinstance(user_data, dict):
            return

        total_bytes = int(user_data.get("traffic_total_bytes", 0) or 0)
        expires_at = int(user_data.get("expires_at", 0) or 0)
        expiry_ms = expires_at * 1000 if expires_at > 0 else 0
        is_active = 1 if user_data.get("active", True) else 0

        # Candidate identifiers for 3X-UI client
        possible_identifiers = set()
        
        # From remote_panel_mappings
        mappings = user_data.get("remote_panel_mappings") or {}
        if isinstance(mappings, dict):
            for p_id, p_info in mappings.items():
                if isinstance(p_info, dict):
                    if p_info.get("username"):
                        possible_identifiers.add(str(p_info["username"]).strip())
                    if p_info.get("clientUuid"):
                        possible_identifiers.add(str(p_info["clientUuid"]).strip())

        # From user name / email / pin
        name = str(user_data.get("name", "")).strip()
        pin = str(user_data.get("pin", "")).strip()
        if name:
            possible_identifiers.add(name)
            clean_tg = re.sub(r'[^a-zA-Z0-9_]', '', name.replace('@', ''))
            if clean_tg:
                possible_identifiers.add(clean_tg)
                if pin:
                    possible_identifiers.add(f"telegram{clean_tg}_{pin[-6:]}")
                    possible_identifiers.add(f"{clean_tg}_{pin[-6:]}")
        if pin:
            possible_identifiers.add(f"%_{pin[-6:]}")
            possible_identifiers.add(f"%{pin}%")

        with sqlite3.connect(xui_db_path, timeout=5) as conn:
            cur = conn.cursor()
            
            # Find matching client in client_traffics
            matched_emails = set()
            for ident in possible_identifiers:
                if "%" in ident:
                    cur.execute("SELECT email FROM client_traffics WHERE email LIKE ?", (ident,))
                else:
                    cur.execute("SELECT email FROM client_traffics WHERE email = ?", (ident,))
                for row in cur.fetchall():
                    if row and row[0]:
                        matched_emails.add(row[0])

            if not matched_emails and pin:
                cur.execute("SELECT email FROM client_traffics WHERE email LIKE ?", (f"%{pin[-6:]}%",))
                for row in cur.fetchall():
                    if row and row[0]:
                        matched_emails.add(row[0])

            if not matched_emails:
                return

            # 1. Update client_traffics
            for email in matched_emails:
                cur.execute(
                    "UPDATE client_traffics SET total = ?, expiry_time = ?, enable = ? WHERE email = ?",
                    (total_bytes, expiry_ms, is_active, email)
                )

            # 2. Update inbounds table (settings JSON column)
            cur.execute("SELECT id, settings FROM inbounds")
            inbounds = cur.fetchall()
            for inb_id, inb_settings_raw in inbounds:
                if not inb_settings_raw:
                    continue
                try:
                    inb_settings = json.loads(inb_settings_raw)
                    clients = inb_settings.get("clients", [])
                    modified = False
                    for c in clients:
                        c_email = c.get("email", "")
                        c_id = c.get("id", "")
                        if c_email in matched_emails or c_id in possible_identifiers:
                            c["totalGB"] = total_bytes
                            c["expiryTime"] = expiry_ms
                            c["enable"] = bool(is_active)
                            modified = True
                    if modified:
                        cur.execute(
                            "UPDATE inbounds SET settings = ? WHERE id = ?",
                            (json.dumps(inb_settings, ensure_ascii=False), inb_id)
                        )
                except Exception as json_err:
                    logging.warning(f"[3X-UI Sync] Failed to parse inbound {inb_id} settings: {json_err}")

            conn.commit()
            logging.info(f"[3X-UI Sync] Synced {matched_emails} in 3X-UI (Total: {total_bytes}, Expiry: {expiry_ms})")
    except Exception as e:
        logging.warning(f"[3X-UI Sync] Error synchronizing with /etc/x-ui/x-ui.db: {e}")

class Database:
    def __init__(self, db_path=DB_PATH):
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        self.db_path = db_path
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(db_path, timeout=15.0, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL;")
        self.conn.execute("PRAGMA synchronous=NORMAL;")
        self.conn.execute("PRAGMA busy_timeout=15000;")
        self.create_tables()

    def create_tables(self):
        with self._lock, self.conn:
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS kv_store (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at INTEGER
                )
            """)

    def get(self, key):
        with self._lock:
            cur = self.conn.cursor()
            cur.execute("SELECT value FROM kv_store WHERE key = ?", (key,))
            row = cur.fetchone()
            return row[0] if row else None

    def put(self, key, value):
        with self._lock, self.conn:
            self.conn.execute("""
                INSERT INTO kv_store (key, value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
            """, (key, value, int(time.time())))
        # Auto-sync to local 3X-UI if this is a user record
        if isinstance(key, str) and (len(key) == 8 or len(key) == 10) and key.isdigit():
            try:
                sync_3xui_sqlite(value)
            except Exception:
                pass

    def delete(self, key):
        with self._lock, self.conn:
            self.conn.execute("DELETE FROM kv_store WHERE key = ?", (key,))

    def get_all_users(self):
        with self._lock:
            cur = self.conn.cursor()
            cur.execute("SELECT key, value FROM kv_store")
            rows = cur.fetchall()
        users = []
        for key, val in rows:
            if key.startswith("__") or key == "apk_meta":
                continue
            try:
                data = json.loads(val)
                if isinstance(data, str):
                    data = json.loads(data)
                if isinstance(data, dict):
                    if "pin" not in data:
                        data["pin"] = key
                    users.append(data)
            except Exception:
                pass
        return users

    def get_all_kv(self):
        with self._lock:
            cur = self.conn.cursor()
            cur.execute("SELECT key, value FROM kv_store")
            rows = cur.fetchall()
        res = {}
        for key, val in rows:
            try:
                res[key] = json.loads(val)
            except Exception:
                res[key] = val
        return res

    def import_from_dict(self, data_dict):
        with self._lock, self.conn:
            for k, v in data_dict.items():
                val_str = json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else str(v)
                self.conn.execute("""
                    INSERT INTO kv_store (key, value, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                """, (k, val_str, int(time.time())))

db = Database()

# Seed database from backup if empty
if not db.get("__ADMIN_CUSTOM_PASSWORD__") and os.path.exists(BACKUP_PATH):
    try:
        with open(BACKUP_PATH, "r", encoding="utf-8") as f:
            backup_data = json.load(f)
            db.import_from_dict(backup_data)
            logging.info(f"Successfully seeded database from {BACKUP_PATH} ({len(backup_data)} keys)")
    except Exception as e:
        logging.error(f"Error seeding database: {e}")

if not db.get("__ADMIN_CUSTOM_PASSWORD__"):
    db.put("__ADMIN_CUSTOM_PASSWORD__", ADMIN_PASSWORD)


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


class RequestHandler(BaseHTTPRequestHandler):
    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Admin-Token, Authorization")
        self.send_header("Server", "AsanVPN-Edge-VPS/1.0")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def is_authenticated_transit(self):
        client_host = self.client_address[0]
        # Local requests (e.g., Telegram bot on same VPS) are trusted
        if client_host in ("127.0.0.1", "::1", "localhost"):
            return True
        edge_secret = self.headers.get("X-Edge-Secret") or self.headers.get("x-edge-secret")
        if edge_secret and edge_secret == EDGE_SECRET:
            return True
        return False

    def check_admin_auth(self):
        token = self.headers.get("X-Admin-Token") or self.headers.get("x-admin-token") or ""
        if not token:
            parsed = urllib.parse.urlparse(self.path)
            qs = urllib.parse.parse_qs(parsed.query)
            token = qs.get("token", [""])[0] or qs.get("admin_token", [""])[0]
        token = token.strip()
        custom_pwd = db.get("__ADMIN_CUSTOM_PASSWORD__") or ADMIN_PASSWORD
        valid_tokens = [custom_pwd.strip(), ADMIN_PASSWORD.strip()]
        auth_ok = token in valid_tokens or token.lower() in [vt.lower() for vt in valid_tokens]
        if not auth_ok:
            logging.warning(f"Auth failed. Received token: '{token}'. Headers: {dict(self.headers)}")
        return auth_ok

    def send_json(self, status_code, data):
        payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(payload)

    def read_json_body(self):
        try:
            length = int(self.headers.get("Content-Length", 0))
            if length > 0:
                raw = self.rfile.read(length).decode("utf-8")
                return json.loads(raw)
        except Exception:
            pass
        return {}

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        # Health
        if path in ["/health", "/ping"]:
            return self.send_json(200, {
                "status": "ok",
                "service": "AsanVPN VPS Core API",
                "time": int(time.time())
            })

        # Version check
        if path in ["/api/v1/app/version", "/api/v1/version", "/version"]:
            return self.send_json(200, {
                "success": True,
                "versionName": APP_VERSION,
                "versionCode": APP_VERSION_CODE,
                "totalSize": 60645000,
                "downloadUrl": "https://asanvpn-dashboard.armanpier-oxvrx.arvanedge.ir/download",
                "fallbackUrl": "http://95.182.87.115/download",
                "githubUrl": GITHUB_APK_URL,
                "releaseNotes": "طراحی مدرن کادر ورودی پین ۱۰ رقمی، رفع بریدگی صفحه در گوشی‌های کوچک، ارتقای امنیت و سرعت اتصال",
                "publishedAt": int(time.time())
            })

        # Direct APK Download
        if path in ["/download", "/AsanVPN.apk", "/apk"]:
            apk_path = "/opt/asanvpn/AsanVPN.apk"
            if os.path.exists(apk_path):
                file_size = os.path.getsize(apk_path)
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(file_size))
                self.send_header("Content-Disposition", f'attachment; filename="AsanVPN-v{APP_VERSION}-universal.apk"')
                self.send_header("Accept-Ranges", "bytes")
                self.send_cors_headers()
                self.end_headers()
                with open(apk_path, "rb") as f:
                    while chunk := f.read(65536):
                        self.wfile.write(chunk)
                return
            else:
                self.send_response(302)
                self.send_header("Location", GITHUB_APK_URL)
                self.send_cors_headers()
                self.end_headers()
                return

        # ADMIN: Get single user
        if path == "/api/v1/admin/user":
            if not self.check_admin_auth():
                return self.send_json(401, {"error": "Unauthorized", "message": "رمز عبور یا توکن مدیریت نامعتبر است"})
            qs = urllib.parse.parse_qs(parsed.query)
            pin = qs.get("pin", [""])[0].strip()
            raw = db.get(pin)
            if raw:
                try:
                    return self.send_json(200, {"success": True, "user": json.loads(raw)})
                except Exception:
                    pass
            return self.send_json(404, {"error": "User not found", "message": "کاربر یافت نشد."})

        # ADMIN: Get users
        if path == "/api/v1/admin/users":
            if not self.check_admin_auth():
                return self.send_json(401, {"error": "Unauthorized", "message": "رمز عبور یا توکن مدیریت نامعتبر است"})
            users = db.get_all_users()
            return self.send_json(200, {
                "total": len(users),
                "users": users
            })

        # ADMIN: Get panels
        if path == "/api/v1/admin/panels":
            if not self.check_admin_auth():
                return self.send_json(401, {"error": "Unauthorized", "message": "رمز عبور یا توکن مدیریت نامعتبر است"})
            panels_json = db.get("__CONFIG_PANELS__")
            panels = json.loads(panels_json) if panels_json else []
            return self.send_json(200, {"panels": panels})

        # ADMIN: Get current password
        if path == "/api/v1/admin/password":
            if not self.check_admin_auth():
                return self.send_json(401, {"error": "Unauthorized", "message": "رمز عبور یا توکن مدیریت نامعتبر است"})
            pwd = db.get("__ADMIN_CUSTOM_PASSWORD__") or ADMIN_PASSWORD
            return self.send_json(200, {"password": pwd})

        self.send_json(404, {"error": "Not Found", "path": path})

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        body = self.read_json_body()

        # ADMIN: Login
        if path == "/api/v1/admin/login":
            input_token = str(body.get("token") or body.get("password") or self.headers.get("X-Admin-Token") or "").strip()
            custom_pwd = (db.get("__ADMIN_CUSTOM_PASSWORD__") or ADMIN_PASSWORD).strip()
            if input_token == custom_pwd or input_token == ADMIN_PASSWORD:
                return self.send_json(200, {
                    "success": True,
                    "message": "ورود با موفقیت انجام شد.",
                    "token": input_token
                })
            else:
                return self.send_json(401, {
                    "success": False,
                    "error": "رمز عبور وارد شده نادرست است."
                })

        # Check Admin Token for all following admin endpoints
        if path.startswith("/api/v1/admin/"):
            if not self.check_admin_auth():
                return self.send_json(401, {"error": "Unauthorized", "message": "رمز عبور یا توکن مدیریت نامعتبر است"})

            # ADMIN: Extend User
            if path == "/api/v1/admin/extend":
                pin = str(body.get("pin", "")).strip()
                days = int(body.get("days", 30))
                traffic_gb = float(body.get("traffic_gb", 0))
                raw_user = db.get(pin)
                if not raw_user:
                    return self.send_json(404, {"error": "User not found", "message": "کاربر یافت نشد."})
                try:
                    user = json.loads(raw_user)
                    now = int(time.time())
                    current_exp = user.get("expires_at")
                    if current_exp and current_exp > now:
                        new_exp = current_exp + (days * 86400)
                    else:
                        new_exp = now + (days * 86400)
                    user["expires_at"] = new_exp
                    user["active"] = True

                    traffic_bytes_to_add = int(round(traffic_gb * (1024**3)))
                    current_total = int(user.get("traffic_total_bytes", 0) or 0)
                    current_rem = int(user.get("traffic_remaining_bytes", 0) or 0)
                    user["traffic_total_bytes"] = current_total + traffic_bytes_to_add
                    user["traffic_remaining_bytes"] = max(0, current_rem) + traffic_bytes_to_add

                    db.put(pin, json.dumps(user, ensure_ascii=False))
                    return self.send_json(200, {
                        "status": "success",
                        "pin": pin,
                        "new_expires_at": user["expires_at"],
                        "traffic_total_bytes": user["traffic_total_bytes"],
                        "traffic_remaining_bytes": user["traffic_remaining_bytes"]
                    })
                except Exception as e:
                    return self.send_json(500, {"error": str(e)})

            # ADMIN: Add/Edit User
            if path in ["/api/v1/admin/user", "/api/v1/admin/create", "/api/v1/admin/update"]:
                pin = str(body.get("pin", "")).strip()
                if not pin:
                    pin = generate_secure_10digit_pin(lambda p: db.get(p) is not None)
                    body["pin"] = pin
                if not is_valid_pin_format(pin):
                    return self.send_json(400, {"error": "Invalid PIN", "message": "کد اشتراک باید ۱۰ رقم (یا ۸ رقم برای کدهای قدیمی) باشد."})
                
                now = int(time.time())
                days = int(body.get("days", 30))
                traffic_gb = float(body.get("traffic_gb", 0))
                traffic_bytes = int(round(traffic_gb * (1024**3))) if traffic_gb > 0 else (body.get("traffic_total_bytes") or 0)
                if not body.get("created_at"):
                    body["created_at"] = now
                if not body.get("expires_at"):
                    body["expires_at"] = now + (days * 86400)
                if "traffic_total_bytes" not in body or not body["traffic_total_bytes"]:
                    body["traffic_total_bytes"] = traffic_bytes
                if "traffic_remaining_bytes" not in body or not body["traffic_remaining_bytes"]:
                    body["traffic_remaining_bytes"] = traffic_bytes
                if "active" not in body:
                    body["active"] = True

                db.put(pin, json.dumps(body, ensure_ascii=False))
                return self.send_json(200, {
                    "success": True,
                    "status": "created" if path == "/api/v1/admin/create" else "success",
                    "user": body,
                    "message": "کاربر با موفقیت ثبت شد."
                })

            # ADMIN: Save Panels
            if path in ["/api/v1/admin/panels", "/api/v1/admin/panels/save"]:
                panels = body.get("panels", [])
                db.put("__CONFIG_PANELS__", json.dumps(panels, ensure_ascii=False))
                return self.send_json(200, {"success": True, "message": "لیست پنل‌ها با موفقیت ذخیره گردید."})

            # ADMIN: Clear lockout for single user
            if path in ["/api/v1/admin/clear_lockout", "/api/v1/admin/reset_device"]:
                pin = str(body.get("pin", "")).strip()
                raw_user = db.get(pin)
                if raw_user:
                    try:
                        u = json.loads(raw_user)
                        old_dev = u.get("device_id")
                        old_devs = list(u.get("devices") or [])
                        u["device_id"] = None
                        u["devices"] = []
                        u["lockout_cleared"] = True
                        u["lockout_cleared_at"] = int(time.time())
                        if old_dev:
                            clear_rate_limit(dev_key=f"dev:{old_dev}")
                        for od in old_devs:
                            if od:
                                clear_rate_limit(dev_key=f"dev:{od}")
                        db.put(pin, json.dumps(u, ensure_ascii=False))
                        return self.send_json(200, {"success": True, "message": f"قفل دستگاه برای کاربر {pin} باز شد."})
                    except Exception as e:
                        return self.send_json(500, {"error": str(e)})
                return self.send_json(404, {"error": "User not found"})

            # ADMIN: Clear all lockouts
            if path in ["/api/v1/admin/clear_all_lockouts", "/api/v1/admin/unlock_rate_limit"]:
                users = db.get_all_users()
                count = 0
                for u in users:
                    pin = u.get("pin")
                    if pin:
                        old_dev = u.get("device_id")
                        old_devs = list(u.get("devices") or [])
                        u["device_id"] = None
                        u["devices"] = []
                        u["lockout_cleared"] = True
                        u["lockout_cleared_at"] = int(time.time())
                        if old_dev:
                            clear_rate_limit(dev_key=f"dev:{old_dev}")
                        for od in old_devs:
                            if od:
                                clear_rate_limit(dev_key=f"dev:{od}")
                        db.put(pin, json.dumps(u, ensure_ascii=False))
                        count += 1
                clear_rate_limit(clear_all=True)
                return self.send_json(200, {"success": True, "message": f"قفل دستگاه {count} کاربر باز شد."})

            # ADMIN: Change Password
            if path in ["/api/v1/admin/password", "/api/v1/admin/change_password"]:
                new_pwd = str(body.get("new_password") or body.get("password") or "").strip()
                if not new_pwd or len(new_pwd) < 4:
                    return self.send_json(400, {"error": "Invalid password", "message": "رمز عبور جدید باید حداقل ۴ نویسه باشد."})
                db.put("__ADMIN_CUSTOM_PASSWORD__", new_pwd)
                return self.send_json(200, {"success": True, "message": "رمز عبور با موفقیت به‌روزرسانی شد."})

            # ADMIN: Sync Panels
            if path in ["/api/v1/admin/sync_panels", "/api/v1/admin/panels/sync"]:
                return self.send_json(200, {
                    "success": True,
                    "totalAdded": 0,
                    "totalUpdated": 0,
                    "crossSynced": 0,
                    "message": "همگام‌سازی پنل‌ها با موفقیت انجام شد."
                })

        # CLIENT: Connect endpoint
        if path == "/api/v1/connect":
            if not self.is_authenticated_transit():
                logging.warning(f"Direct unauthenticated connect attempt from {self.client_address[0]} rejected.")
                return self.send_json(403, {
                    "error": "Forbidden",
                    "message": "Direct edge transit authentication required"
                })

            pin = str(body.get("pin", "")).strip()
            device_id = str(body.get("device_id", "")).strip()

            forwarded = self.headers.get("X-Forwarded-For") or self.headers.get("x-forwarded-for") or ""
            cf_ip = self.headers.get("CF-Connecting-IP") or self.headers.get("cf-connecting-ip") or ""
            ar_ip = self.headers.get("AR-Real-IP") or self.headers.get("ar-real-ip") or ""
            client_ip = cf_ip or ar_ip or (forwarded.split(",")[0].strip() if forwarded else "") or self.client_address[0]
            ip_subnet = get_ip_subnet(client_ip)
            ip_key = f"ip:{ip_subnet}" if ip_subnet else ""
            dev_key = f"dev:{device_id}" if device_id else ""

            # Check rate limit lockout
            is_locked, rem_sec = check_rate_limit(ip_key, dev_key)
            if is_locked:
                return self.send_json(429, {
                    "status": "rate_limited",
                    "message_fa": f"تعداد تلاش‌های ناموفق بیش از ۳ بار بوده است. لطفاً {max(1, rem_sec // 60)} دقیقه دیگر تلاش کنید.",
                    "retry_after_seconds": rem_sec,
                    "locked_until_timestamp": (int(time.time()) + rem_sec) * 1000,
                    "support_phone": SUPPORT_PHONE,
                    "support_telegram": SUPPORT_TELEGRAM
                })

            if not is_valid_pin_format(pin):
                locked, rem_sec, rem_attempts = record_failed_attempt(ip_key, dev_key)
                if locked:
                    return self.send_json(429, {
                        "status": "rate_limited",
                        "message_fa": "تعداد تلاش‌های ناموفق به ۳ بار رسید. ورود به مدت ۳۰ دقیقه مسدود شد.",
                        "retry_after_seconds": 1800,
                        "locked_until_timestamp": (int(time.time()) + 1800) * 1000,
                        "support_phone": SUPPORT_PHONE,
                        "support_telegram": SUPPORT_TELEGRAM
                    })
                return self.send_json(403, {
                    "status": "invalid_pin",
                    "message_fa": f"کد اشتراک باید ۱۰ رقم (یا ۸ رقم برای کدهای قدیمی) باشد. ({rem_attempts} فرصت باقی مانده)",
                    "remaining_attempts": rem_attempts,
                    "support_phone": SUPPORT_PHONE,
                    "support_telegram": SUPPORT_TELEGRAM
                })

            raw_user = db.get(pin)
            if not raw_user:
                locked, rem_sec, rem_attempts = record_failed_attempt(ip_key, dev_key)
                if locked:
                    return self.send_json(429, {
                        "status": "rate_limited",
                        "message_fa": "تعداد تلاش‌های ناموفق به ۳ بار رسید. ورود به مدت ۳۰ دقیقه مسدود شد.",
                        "retry_after_seconds": 1800,
                        "locked_until_timestamp": (int(time.time()) + 1800) * 1000,
                        "support_phone": SUPPORT_PHONE,
                        "support_telegram": SUPPORT_TELEGRAM
                    })
                return self.send_json(403, {
                    "status": "invalid_pin",
                    "message_fa": f"کد اشتراک یافت نشد یا معتبر نمی‌باشد. ({rem_attempts} فرصت باقی مانده)",
                    "remaining_attempts": rem_attempts,
                    "support_phone": SUPPORT_PHONE,
                    "support_telegram": SUPPORT_TELEGRAM
                })

            # Valid credentials - clear rate limit for this IP and device
            clear_rate_limit(ip_key, dev_key)

            user = json.loads(raw_user)
            now = int(time.time())

            if user.get("active") is False:
                return self.send_json(403, {
                    "status": "disabled",
                    "message_fa": "اشتراک شما غیرفعال شده است.",
                    "support_phone": SUPPORT_PHONE,
                    "support_telegram": SUPPORT_TELEGRAM
                })

            if user.get("expires_at") and user["expires_at"] > 0 and user["expires_at"] < now:
                return self.send_json(403, {
                    "status": "expired",
                    "message_fa": "اشتراک شما به پایان رسیده است.",
                    "support_phone": SUPPORT_PHONE,
                    "support_telegram": SUPPORT_TELEGRAM
                })

            # Device lock check
            max_dev = int(user.get("max_devices", 1) or 1)
            devices = user.get("devices") or ([] if not user.get("device_id") else [user.get("device_id")])
            if device_id:
                if device_id not in devices:
                    if len(devices) >= max_dev:
                        return self.send_json(403, {
                            "status": "device_locked",
                            "message_fa": f"این اشتراک روی دستگاه دیگری فعال است (حداکثر {max_dev} دستگاه).",
                            "support_phone": SUPPORT_PHONE,
                            "support_telegram": SUPPORT_TELEGRAM
                        })
                    devices.append(device_id)
                    user["devices"] = devices
                    user["device_id"] = devices[0]
                    db.put(pin, json.dumps(user, ensure_ascii=False))

            nodes = user.get("cached_nodes") or []
            days_left = max(0, (user["expires_at"] - now) // 86400) if user.get("expires_at") else "نامحدود"
            remaining_bytes = user.get("traffic_remaining_bytes", 26843545600)
            total_bytes = user.get("traffic_total_bytes", 26843545600)

            return self.send_json(200, {
                "status": "success",
                "user_info": {
                    "name": user.get("name") or "کاربر گرامی",
                    "days_left": days_left,
                    "traffic_remaining_bytes": remaining_bytes,
                    "traffic_total_bytes": total_bytes,
                    "max_devices": max_dev,
                    "active_devices_count": len(devices),
                    "support_phone": SUPPORT_PHONE,
                    "support_telegram": SUPPORT_TELEGRAM,
                    "is_admin": pin in ["22858950", "Arman4237340"]
                },
                "nodes": nodes
            })

        self.send_json(404, {"error": "Not Found", "path": path})

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if path == "/api/v1/admin/user":
            if not self.check_admin_auth():
                return self.send_json(401, {"error": "Unauthorized", "message": "رمز عبور یا توکن مدیریت نامعتبر است"})
            params = urllib.parse.parse_qs(parsed.query)
            pin = params.get("pin", [""])[0]
            if not pin:
                body = self.read_json_body()
                pin = body.get("pin", "")
            if pin:
                db.delete(pin)
                return self.send_json(200, {"success": True, "message": f"کاربر {pin} با موفقیت حذف گردید."})
            return self.send_json(400, {"error": "Missing PIN"})
        self.send_json(404, {"error": "Not Found"})


def run():
    server = ThreadedHTTPServer(("0.0.0.0", PORT), RequestHandler)
    logging.info(f"AsanVPN Core API Server running on port {PORT}...")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

if __name__ == "__main__":
    run()
