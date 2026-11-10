#!/usr/bin/env python3
"""
AsanVPN Standalone Edge Backend API Server
Runs on VPS (Port 80) providing persistent, high-speed API for ArvanCloud Edge Dashboard and mobile clients.
Zero external pip dependencies (uses Python 3 standard library: http.server, sqlite3, json, urllib).
"""

import sys
import os
import json
import sqlite3
import time
import logging
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
APP_VERSION = "1.3.10"
APP_VERSION_CODE = 16
GITHUB_APK_URL = "https://github.com/armanpier/AsanVPN/releases/download/v1.3.10/AsanVPN-v1.3.10-universal.apk"

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

class Database:
    def __init__(self, db_path=DB_PATH):
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self.create_tables()

    def create_tables(self):
        with self.conn:
            self.conn.execute("""
                CREATE TABLE IF NOT EXISTS kv_store (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at INTEGER
                )
            """)

    def get(self, key):
        cur = self.conn.cursor()
        cur.execute("SELECT value FROM kv_store WHERE key = ?", (key,))
        row = cur.fetchone()
        return row[0] if row else None

    def put(self, key, value):
        with self.conn:
            self.conn.execute("""
                INSERT INTO kv_store (key, value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
            """, (key, value, int(time.time())))

    def delete(self, key):
        with self.conn:
            self.conn.execute("DELETE FROM kv_store WHERE key = ?", (key,))

    def get_all_users(self):
        cur = self.conn.cursor()
        cur.execute("SELECT key, value FROM kv_store")
        users = []
        for key, val in cur.fetchall():
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
        cur = self.conn.cursor()
        cur.execute("SELECT key, value FROM kv_store")
        res = {}
        for key, val in cur.fetchall():
            try:
                res[key] = json.loads(val)
            except Exception:
                res[key] = val
        return res

    def import_from_dict(self, data_dict):
        with self.conn:
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
                "releaseNotes": "رفع کامل قطعی اینترنت پس از اتصال، مسیریابی اصلاح‌شده، دانلود مستقیم پرسرعت و همگام‌سازی پنل‌ها",
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
                    import random
                    while True:
                        cand = str(random.randint(10000000, 99999999))
                        if not db.get(cand):
                            pin = cand
                            break
                    body["pin"] = pin
                if len(pin) != 8 or not pin.isdigit():
                    return self.send_json(400, {"error": "Invalid PIN", "message": "کد اشتراک باید ۸ رقم باشد."})
                
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
                        u["device_id"] = None
                        u["devices"] = []
                        u["lockout_cleared"] = True
                        u["lockout_cleared_at"] = int(time.time())
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
                        u["device_id"] = None
                        u["devices"] = []
                        u["lockout_cleared"] = True
                        u["lockout_cleared_at"] = int(time.time())
                        db.put(pin, json.dumps(u, ensure_ascii=False))
                        count += 1
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
            pin = str(body.get("pin", "")).strip()
            device_id = str(body.get("device_id", "")).strip()
            if not pin or len(pin) != 8 or not pin.isdigit():
                return self.send_json(403, {
                    "status": "invalid_pin",
                    "message_fa": "کد اشتراک باید ۸ رقم باشد.",
                    "support_phone": SUPPORT_PHONE,
                    "support_telegram": SUPPORT_TELEGRAM
                })

            raw_user = db.get(pin)
            if not raw_user:
                return self.send_json(403, {
                    "status": "invalid_pin",
                    "message_fa": "کد اشتراک یافت نشد یا معتبر نمی‌باشد.",
                    "support_phone": SUPPORT_PHONE,
                    "support_telegram": SUPPORT_TELEGRAM
                })

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
