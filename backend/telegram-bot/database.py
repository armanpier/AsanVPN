import aiosqlite
import logging
import time
from config import DB_PATH, ADMIN_IDS

logger = logging.getLogger("Database")

# Initial Default Plans as requested:
# 10 GB   70T
# 20 GB   130T
# 30 GB   190T
# 40 GB   260T
DEFAULT_PLANS = [
    {
        "id": "plan_10g",
        "title": "۱۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 10,
        "max_devices": 2,
        "price_toman": 70000,
        "is_active": 1,
        "sort_order": 1
    },
    {
        "id": "plan_20g",
        "title": "۲۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 20,
        "max_devices": 2,
        "price_toman": 130000,
        "is_active": 1,
        "sort_order": 2
    },
    {
        "id": "plan_30g",
        "title": "۳۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 30,
        "max_devices": 2,
        "price_toman": 190000,
        "is_active": 1,
        "sort_order": 3
    },
    {
        "id": "plan_40g",
        "title": "۴۰ گیگابایت (۱ ماهه)",
        "days": 30,
        "traffic_gb": 40,
        "max_devices": 2,
        "price_toman": 260000,
        "is_active": 1,
        "sort_order": 4
    }
]

DEFAULT_SETTINGS = {
    "welcome_message": (
        "سلام <b>{name}</b> عزیز، به ربات رسمی <b>آسان وی‌پی‌ان (AsanVPN)</b> خوش آمدید! 🛡️⚡\n\n"
        "🚀 <b>ویژگی‌های آسان وی‌پی‌ان:</b>\n"
        "• اتصال فوق‌سریع با فناوری هوشمند بدون افت سرعت\n"
        "• دور زدن تمام محدودیت‌ها و اختلالات اینترنت\n"
        "• اتصال با کد اختصاصی ۸ رقمی بدون نیاز به تنظیمات دستی\n"
        "• پشتیبانی ۲۴ ساعته و سرورهای اختصاصی پایدار\n\n"
        "لطفاً یکی از گزینه‌های زیر را انتخاب کنید:"
    ),
    "support_username": "@Sib_Support_P",
    "app_download_url": "https://github.com/armanpier/AsanVPN/releases/download/v1.2.0/AsanVPN-v1.2.0-universal.apk",
    "backup_channel_id": "-1004389472709",
    "auto_backup_enabled": "1",
    "auto_backup_interval_hours": "24",
    "last_backup_time": "0",
    "notify_admin_on_sale": "1",
    "notify_admin_on_renew": "1",
    "notify_user_on_sale": "1",
    "maintenance_mode": "0",
    "admin_bypass_payment": "1"
}

class BotDatabase:
    def __init__(self, db_path: str = DB_PATH):
        self.db_path = db_path
        self.admin_ids_cache: set[int] = {93795023} | set(ADMIN_IDS)

    async def init_db(self):
        async with aiosqlite.connect(self.db_path) as db:
            # Users table
            await db.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    telegram_id INTEGER PRIMARY KEY,
                    username TEXT,
                    first_name TEXT,
                    created_at INTEGER
                )
            """)

            # Bot Admins table
            await db.execute("""
                CREATE TABLE IF NOT EXISTS bot_admins (
                    telegram_id INTEGER PRIMARY KEY,
                    username TEXT,
                    first_name TEXT,
                    added_by INTEGER,
                    created_at INTEGER
                )
            """)

            # Orders table
            await db.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    order_id TEXT UNIQUE,
                    telegram_id INTEGER,
                    order_type TEXT, -- 'new' or 'renew'
                    target_pin TEXT, -- for renew
                    plan_id TEXT,
                    amount_toman INTEGER,
                    hooshpay_uid TEXT UNIQUE,
                    payment_url TEXT,
                    status TEXT DEFAULT 'pending', -- 'pending', 'paid', 'expired', 'failed'
                    tracking_code TEXT,
                    assigned_pin TEXT,
                    created_at INTEGER,
                    paid_at INTEGER
                )
            """)

            # Plans table (Dynamic)
            await db.execute("""
                CREATE TABLE IF NOT EXISTS plans (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    days INTEGER NOT NULL DEFAULT 30,
                    traffic_gb INTEGER NOT NULL DEFAULT 10,
                    max_devices INTEGER NOT NULL DEFAULT 2,
                    price_toman INTEGER NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    sort_order INTEGER NOT NULL DEFAULT 0
                )
            """)

            # Settings table (Key-Value)
            await db.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """)

            # Free test claims table (1 per Telegram ID)
            await db.execute("""
                CREATE TABLE IF NOT EXISTS test_claims (
                    telegram_id INTEGER PRIMARY KEY,
                    pin TEXT NOT NULL,
                    claimed_at INTEGER NOT NULL
                )
            """)

            # Pinned / Associated accounts for users
            await db.execute("""
                CREATE TABLE IF NOT EXISTS user_pinned_accounts (
                    telegram_id INTEGER NOT NULL,
                    pin TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (telegram_id, pin)
                )
            """)

            await db.commit()

            # Seed default settings if missing
            for k, v in DEFAULT_SETTINGS.items():
                async with db.execute("SELECT value FROM settings WHERE key = ?", (k,)) as cur:
                    if await cur.fetchone() is None:
                        await db.execute("INSERT INTO settings (key, value) VALUES (?, ?)", (k, v))

            # Seed default plans if empty
            async with db.execute("SELECT COUNT(*) FROM plans") as cur:
                count_row = await cur.fetchone()
                if count_row and count_row[0] == 0:
                    for p in DEFAULT_PLANS:
                        await db.execute("""
                            INSERT INTO plans (id, title, days, traffic_gb, max_devices, price_toman, is_active, sort_order)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, (
                            p["id"], p["title"], p["days"], p["traffic_gb"],
                            p["max_devices"], p["price_toman"], p["is_active"], p["sort_order"]
                        ))

            # Seed default bot admins
            now = int(time.time())
            initial_admins = [93795023] + [aid for aid in ADMIN_IDS if aid != 93795023]
            for aid in initial_admins:
                async with db.execute("SELECT username, first_name FROM users WHERE telegram_id = ?", (aid,)) as cur:
                    urow = await cur.fetchone()
                    uname = urow[0] if urow and urow[0] else ""
                    fname = urow[1] if urow and urow[1] else ("مالک اصلی" if aid == 93795023 else "مدیر سیستم")
                await db.execute("""
                    INSERT OR IGNORE INTO bot_admins (telegram_id, username, first_name, added_by, created_at)
                    VALUES (?, ?, ?, 0, ?)
                """, (aid, uname, fname, now))

            await db.commit()

            # Load active admin IDs into memory cache
            async with db.execute("SELECT telegram_id FROM bot_admins") as cur:
                rows = await cur.fetchall()
                self.admin_ids_cache = {93795023} | {r[0] for r in rows}

            logger.info(f"Database initialized successfully. Active bot admins: {self.admin_ids_cache}")

    # ---------------- SETTINGS ----------------
    async def get_setting(self, key: str, default: str = None) -> str:
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute("SELECT value FROM settings WHERE key = ?", (key,)) as cur:
                row = await cur.fetchone()
                return row[0] if row else default

    async def set_setting(self, key: str, value: str):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO settings (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """, (key, str(value)))
            await db.commit()

    async def get_all_settings(self) -> dict:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT key, value FROM settings") as cur:
                rows = await cur.fetchall()
                return {r["key"]: r["value"] for r in rows}

    # ---------------- PLANS ----------------
    async def get_plans(self, active_only: bool = True) -> list:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            query = "SELECT * FROM plans"
            if active_only:
                query += " WHERE is_active = 1"
            query += " ORDER BY sort_order ASC, price_toman ASC"
            async with db.execute(query) as cur:
                rows = await cur.fetchall()
                return [dict(r) for r in rows]

    async def get_plan_by_id(self, plan_id: str) -> dict:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT * FROM plans WHERE id = ?", (plan_id,)) as cur:
                row = await cur.fetchone()
                return dict(row) if row else None

    async def add_or_update_plan(self, plan_id: str, title: str, days: int, traffic_gb: int, max_devices: int, price_toman: int, is_active: int = 1, sort_order: int = 0):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO plans (id, title, days, traffic_gb, max_devices, price_toman, is_active, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title,
                    days = excluded.days,
                    traffic_gb = excluded.traffic_gb,
                    max_devices = excluded.max_devices,
                    price_toman = excluded.price_toman,
                    is_active = excluded.is_active,
                    sort_order = excluded.sort_order
            """, (plan_id, title, days, traffic_gb, max_devices, price_toman, is_active, sort_order))
            await db.commit()

    async def delete_plan(self, plan_id: str):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("DELETE FROM plans WHERE id = ?", (plan_id,))
            await db.commit()

    async def toggle_plan(self, plan_id: str) -> int:
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute("SELECT is_active FROM plans WHERE id = ?", (plan_id,)) as cur:
                row = await cur.fetchone()
                if not row:
                    return 0
                new_status = 0 if row[0] == 1 else 1
            await db.execute("UPDATE plans SET is_active = ? WHERE id = ?", (new_status, plan_id))
            await db.commit()
            return new_status

    # ---------------- USERS & ORDERS ----------------
    async def register_user(self, telegram_id: int, username: str, first_name: str):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO users (telegram_id, username, first_name, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(telegram_id) DO UPDATE SET
                    username = excluded.username,
                    first_name = excluded.first_name
            """, (telegram_id, username, first_name, int(time.time())))
            await db.commit()

    async def get_all_user_ids(self) -> list:
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute("SELECT telegram_id FROM users") as cur:
                rows = await cur.fetchall()
                return [r[0] for r in rows]

    async def create_order(self, order_id: str, telegram_id: int, order_type: str, plan_id: str, amount_toman: int, hooshpay_uid: str, payment_url: str, target_pin: str = None):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO orders (
                    order_id, telegram_id, order_type, target_pin, plan_id,
                    amount_toman, hooshpay_uid, payment_url, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
            """, (
                order_id, telegram_id, order_type, target_pin, plan_id,
                amount_toman, hooshpay_uid, payment_url, int(time.time())
            ))
            await db.commit()

    async def get_order_by_uid(self, hooshpay_uid: str) -> dict:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT * FROM orders WHERE hooshpay_uid = ?", (hooshpay_uid,)) as cursor:
                row = await cursor.fetchone()
                return dict(row) if row else None

    async def get_pending_orders(self, older_than_seconds: int = 1800) -> list:
        cutoff = int(time.time()) - older_than_seconds
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                "SELECT * FROM orders WHERE status = 'pending' AND created_at >= ?", (cutoff,)
            ) as cursor:
                rows = await cursor.fetchall()
                return [dict(r) for r in rows]

    async def mark_order_paid(self, hooshpay_uid: str, tracking_code: str, assigned_pin: str = None) -> bool:
        async with aiosqlite.connect(self.db_path) as db:
            now = int(time.time())
            cursor = await db.execute("""
                UPDATE orders
                SET status = 'paid', tracking_code = ?, assigned_pin = COALESCE(?, assigned_pin), paid_at = ?
                WHERE hooshpay_uid = ? AND status != 'paid'
            """, (tracking_code, assigned_pin, now, hooshpay_uid))
            await db.commit()
            return cursor.rowcount > 0

    async def get_user_orders(self, telegram_id: int) -> list:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                "SELECT * FROM orders WHERE telegram_id = ? ORDER BY id DESC LIMIT 10", (telegram_id,)
            ) as cursor:
                rows = await cursor.fetchall()
                return [dict(r) for r in rows]

    async def get_user_latest_pin(self, telegram_id: int) -> str:
        """Get the latest paid PIN, pinned PIN, or test PIN assigned to this telegram user"""
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            # Check paid orders first (ignoring known non-existent PINs)
            async with db.execute(
                "SELECT assigned_pin FROM orders WHERE telegram_id = ? AND status = 'paid' AND assigned_pin IS NOT NULL AND assigned_pin != '' AND assigned_pin != '26508350' ORDER BY paid_at DESC, id DESC LIMIT 1",
                (telegram_id,)
            ) as cursor:
                row = await cursor.fetchone()
                if row and row["assigned_pin"]:
                    return str(row["assigned_pin"]).strip()

            # Check user_pinned_accounts
            async with db.execute(
                "SELECT pin FROM user_pinned_accounts WHERE telegram_id = ? AND pin != '26508350' ORDER BY created_at DESC LIMIT 1",
                (telegram_id,)
            ) as cursor:
                p_row = await cursor.fetchone()
                if p_row and p_row["pin"]:
                    return str(p_row["pin"]).strip()

            # Fallback to test claim
            async with db.execute(
                "SELECT pin FROM test_claims WHERE telegram_id = ?",
                (telegram_id,)
            ) as cursor:
                t_row = await cursor.fetchone()
                if t_row and t_row["pin"]:
                    return str(t_row["pin"]).strip()

            return None

    async def get_recent_admin_pins(self, limit: int = 5) -> list:
        """Fetch recently issued pins from orders for admin convenience"""
        pins = []
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute(
                "SELECT DISTINCT assigned_pin FROM orders WHERE assigned_pin IS NOT NULL AND assigned_pin != '' AND assigned_pin != '26508350' ORDER BY id DESC LIMIT ?",
                (limit,)
            ) as cursor:
                rows = await cursor.fetchall()
                for r in rows:
                    p = str(r[0]).strip()
                    if p and p not in pins:
                        pins.append(p)
        return pins

    async def get_user_all_pins(self, telegram_id: int) -> list:
        """Get all distinct valid PINs assigned to this telegram user"""
        pins = []
        async with aiosqlite.connect(self.db_path) as db:
            # Paid orders
            async with db.execute(
                "SELECT DISTINCT assigned_pin FROM orders WHERE telegram_id = ? AND status = 'paid' AND assigned_pin IS NOT NULL AND assigned_pin != '' AND assigned_pin != '26508350' ORDER BY id DESC",
                (telegram_id,)
            ) as cursor:
                rows = await cursor.fetchall()
                for r in rows:
                    p = str(r[0]).strip()
                    if p and p not in pins:
                        pins.append(p)

            # Pinned accounts
            async with db.execute(
                "SELECT DISTINCT pin FROM user_pinned_accounts WHERE telegram_id = ? AND pin != '26508350' ORDER BY created_at DESC",
                (telegram_id,)
            ) as cursor:
                rows = await cursor.fetchall()
                for r in rows:
                    p = str(r[0]).strip()
                    if p and p not in pins:
                        pins.append(p)

            # Test claims
            async with db.execute(
                "SELECT pin FROM test_claims WHERE telegram_id = ?",
                (telegram_id,)
            ) as cursor:
                t_row = await cursor.fetchone()
                if t_row and t_row[0]:
                    t_pin = str(t_row[0]).strip()
                    if t_pin not in pins:
                        pins.append(t_pin)

        return pins

    async def link_pin_to_user(self, telegram_id: int, pin: str):
        """Associate a PIN with this telegram user in SQLite"""
        if not pin or len(str(pin).strip()) != 8:
            return
        clean_pin = str(pin).strip()
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO user_pinned_accounts (telegram_id, pin, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT(telegram_id, pin) DO NOTHING
            """, (telegram_id, clean_pin, int(time.time())))
            # Also update paid order if it had an invalid or empty assigned_pin
            await db.execute("""
                UPDATE orders
                SET assigned_pin = ?
                WHERE telegram_id = ? AND status = 'paid' AND (assigned_pin IS NULL OR assigned_pin = '' OR assigned_pin = '26508350')
            """, (clean_pin, telegram_id))
            await db.commit()

    async def unlink_invalid_pin(self, pin: str):
        """Purge non-existent PIN from SQLite"""
        clean_pin = str(pin).strip()
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("DELETE FROM user_pinned_accounts WHERE pin = ?", (clean_pin,))
            await db.execute("UPDATE orders SET assigned_pin = NULL WHERE assigned_pin = ?", (clean_pin,))
            await db.execute("DELETE FROM test_claims WHERE pin = ?", (clean_pin,))
            await db.commit()

    async def get_linked_telegram_ids_by_pin(self, pin: str) -> list:
        """Find all telegram IDs associated with a PIN in pinned accounts and orders"""
        clean_pin = str(pin).strip()
        linked_users = []
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT telegram_id FROM user_pinned_accounts WHERE pin = ?", (clean_pin,)) as cur:
                rows = await cur.fetchall()
                for r in rows:
                    if r["telegram_id"] not in linked_users:
                        linked_users.append(r["telegram_id"])

            async with db.execute("SELECT DISTINCT telegram_id FROM orders WHERE assigned_pin = ?", (clean_pin,)) as cur:
                rows = await cur.fetchall()
                for r in rows:
                    if r["telegram_id"] not in linked_users:
                        linked_users.append(r["telegram_id"])
        return linked_users

    async def remove_subscription_by_pin(self, pin: str) -> dict:
        """
        Purge/revoke a subscription PIN from SQLite and return linked user ids.
        """
        clean_pin = str(pin).strip()
        linked_users = []
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT telegram_id FROM user_pinned_accounts WHERE pin = ?", (clean_pin,)) as cur:
                rows = await cur.fetchall()
                for r in rows:
                    if r["telegram_id"] not in linked_users:
                        linked_users.append(r["telegram_id"])

            async with db.execute("SELECT DISTINCT telegram_id FROM orders WHERE assigned_pin = ?", (clean_pin,)) as cur:
                rows = await cur.fetchall()
                for r in rows:
                    if r["telegram_id"] not in linked_users:
                        linked_users.append(r["telegram_id"])

            await db.execute("DELETE FROM user_pinned_accounts WHERE pin = ?", (clean_pin,))
            await db.execute("UPDATE orders SET assigned_pin = NULL, status = 'revoked' WHERE assigned_pin = ?", (clean_pin,))
            await db.execute("DELETE FROM test_claims WHERE pin = ?", (clean_pin,))
            await db.commit()

        return {
            "pin": clean_pin,
            "linked_users": linked_users
        }

    async def get_user_by_id(self, telegram_id: int):
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT * FROM users WHERE telegram_id = ?", (telegram_id,)) as cur:
                row = await cur.fetchone()
                return dict(row) if row else None

    async def get_user_by_username(self, username: str):
        clean_user = username.strip().lstrip("@").lower()
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT * FROM users WHERE LOWER(username) = ?", (clean_user,)) as cur:
                row = await cur.fetchone()
                return dict(row) if row else None

    async def has_paid_order(self, telegram_id: int) -> bool:
        """Check if user has ever completed a paid order"""
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute(
                "SELECT id FROM orders WHERE telegram_id = ? AND status = 'paid' LIMIT 1",
                (telegram_id,)
            ) as cursor:
                return (await cursor.fetchone()) is not None

    # ---------------- FREE TEST CLAIMS ----------------
    async def has_claimed_test(self, telegram_id: int) -> bool:
        """Check if user has claimed a free test plan"""
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute(
                "SELECT pin FROM test_claims WHERE telegram_id = ?",
                (telegram_id,)
            ) as cursor:
                return (await cursor.fetchone()) is not None

    async def get_test_claim(self, telegram_id: int) -> dict:
        """Get test claim details for user"""
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                "SELECT * FROM test_claims WHERE telegram_id = ?",
                (telegram_id,)
            ) as cursor:
                row = await cursor.fetchone()
                return dict(row) if row else None

    async def record_test_claim(self, telegram_id: int, pin: str):
        """Record a test claim for user"""
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO test_claims (telegram_id, pin, claimed_at)
                VALUES (?, ?, ?)
                ON CONFLICT(telegram_id) DO UPDATE SET pin = excluded.pin, claimed_at = excluded.claimed_at
            """, (telegram_id, pin, int(time.time())))
            await db.commit()

    # ---------------- STATISTICS ----------------
    async def get_stats(self) -> dict:
        async with aiosqlite.connect(self.db_path) as db:
            now = int(time.time())
            today_start = now - (now % 86400)
            month_start = now - (30 * 86400)

            # Total users
            async with db.execute("SELECT COUNT(*) FROM users") as cur:
                total_users = (await cur.fetchone())[0]

            # Total orders
            async with db.execute("SELECT COUNT(*) FROM orders") as cur:
                total_orders = (await cur.fetchone())[0]

            # Paid orders & total revenue
            async with db.execute("SELECT COUNT(*), COALESCE(SUM(amount_toman), 0) FROM orders WHERE status = 'paid'") as cur:
                row = await cur.fetchone()
                paid_orders = row[0]
                total_revenue = row[1]

            # Today revenue
            async with db.execute("SELECT COALESCE(SUM(amount_toman), 0) FROM orders WHERE status = 'paid' AND paid_at >= ?", (today_start,)) as cur:
                today_revenue = (await cur.fetchone())[0]

            # Month revenue
            async with db.execute("SELECT COALESCE(SUM(amount_toman), 0) FROM orders WHERE status = 'paid' AND paid_at >= ?", (month_start,)) as cur:
                month_revenue = (await cur.fetchone())[0]

            # Active plans count
            async with db.execute("SELECT COUNT(*) FROM plans WHERE is_active = 1") as cur:
                active_plans = (await cur.fetchone())[0]

            return {
                "total_users": total_users,
                "total_orders": total_orders,
                "paid_orders": paid_orders,
                "total_revenue": total_revenue,
                "today_revenue": today_revenue,
                "month_revenue": month_revenue,
                "active_plans": active_plans
            }

    # ---------------- ADMIN MANAGEMENT ----------------
    def is_admin(self, telegram_id: int) -> bool:
        if not telegram_id:
            return False
        return (telegram_id == 93795023) or (telegram_id in self.admin_ids_cache)

    def get_admin_ids(self) -> set:
        return set(self.admin_ids_cache) | {93795023}

    async def get_all_admins(self) -> list:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("""
                SELECT * FROM bot_admins
                ORDER BY CASE WHEN telegram_id = 93795023 THEN 0 ELSE 1 END, created_at ASC
            """) as cur:
                rows = await cur.fetchall()
                result = []
                for r in rows:
                    d = dict(r)
                    d["is_owner"] = (d["telegram_id"] == 93795023)
                    result.append(d)

                # Ensure owner is always present
                if not any(a["telegram_id"] == 93795023 for a in result):
                    result.insert(0, {
                        "telegram_id": 93795023,
                        "username": "",
                        "first_name": "Arman",
                        "added_by": 0,
                        "created_at": 0,
                        "is_owner": True
                    })
                return result

    async def add_admin(self, telegram_id: int, username: str = "", first_name: str = "", added_by: int = 0) -> bool:
        async with aiosqlite.connect(self.db_path) as db:
            # Query existing user info if details are blank
            if not username or not first_name:
                async with db.execute("SELECT username, first_name FROM users WHERE telegram_id = ?", (telegram_id,)) as cur:
                    urow = await cur.fetchone()
                    if urow:
                        username = username or urow[0] or ""
                        first_name = first_name or urow[1] or ""
            now = int(time.time())
            await db.execute("""
                INSERT INTO bot_admins (telegram_id, username, first_name, added_by, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(telegram_id) DO UPDATE SET
                    username = COALESCE(NULLIF(excluded.username, ''), bot_admins.username),
                    first_name = COALESCE(NULLIF(excluded.first_name, ''), bot_admins.first_name)
            """, (telegram_id, username or "", first_name or "", added_by, now))
            await db.commit()

        self.admin_ids_cache.add(telegram_id)
        return True

    async def remove_admin(self, telegram_id: int) -> bool:
        if telegram_id == 93795023:
            return False  # Protected super admin / owner
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("DELETE FROM bot_admins WHERE telegram_id = ?", (telegram_id,))
            await db.commit()

        self.admin_ids_cache.discard(telegram_id)
        return True

    async def find_user_by_query(self, query: str):
        query = query.strip().lstrip("@")
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            if query.isdigit():
                async with db.execute("SELECT telegram_id, username, first_name FROM users WHERE telegram_id = ?", (int(query),)) as cur:
                    row = await cur.fetchone()
                    if row:
                        return dict(row)
                    return {"telegram_id": int(query), "username": "", "first_name": ""}
            async with db.execute("SELECT telegram_id, username, first_name FROM users WHERE LOWER(username) = LOWER(?)", (query,)) as cur:
                row = await cur.fetchone()
                if row:
                    return dict(row)
        return None
