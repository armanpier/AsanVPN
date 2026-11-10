import aiosqlite
import logging
from typing import Optional, List, Dict, Any, Tuple
from config import DB_PATH

logger = logging.getLogger(__name__)

class SupportDatabase:
    def __init__(self, db_path: str = DB_PATH):
        self.db_path = db_path

    async def init_db(self):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                CREATE TABLE IF NOT EXISTS support_users (
                    user_id INTEGER PRIMARY KEY,
                    username TEXT,
                    first_name TEXT,
                    last_name TEXT,
                    is_blocked INTEGER DEFAULT 0,
                    block_reason TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    last_active TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
            """)

            await db.execute("""
                CREATE TABLE IF NOT EXISTS message_mappings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    admin_message_id INTEGER NOT NULL,
                    user_id INTEGER NOT NULL,
                    user_message_id INTEGER NOT NULL,
                    direction TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
            """)

            await db.execute("""
                CREATE INDEX IF NOT EXISTS idx_mappings_admin_msg 
                ON message_mappings (admin_message_id);
            """)

            await db.execute("""
                CREATE INDEX IF NOT EXISTS idx_mappings_user_id 
                ON message_mappings (user_id);
            """)

            await db.execute("""
                CREATE TABLE IF NOT EXISTS support_settings (
                    key TEXT PRIMARY KEY,
                    value TEXT
                );
            """)

            await db.commit()
            logger.info("Support Bot SQLite tables initialized successfully.")

    async def add_or_update_user(self, user_id: int, username: Optional[str], first_name: Optional[str], last_name: Optional[str] = None):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO support_users (user_id, username, first_name, last_name, last_active)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                    username = excluded.username,
                    first_name = excluded.first_name,
                    last_name = excluded.last_name,
                    last_active = CURRENT_TIMESTAMP;
            """, (user_id, username, first_name, last_name))
            await db.commit()

    async def is_blocked(self, user_id: int) -> bool:
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute("SELECT is_blocked FROM support_users WHERE user_id = ?", (user_id,)) as cur:
                row = await cur.fetchone()
                return bool(row[0]) if row else False

    async def block_user(self, user_id: int, reason: Optional[str] = None) -> bool:
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO support_users (user_id, is_blocked, block_reason, last_active)
                VALUES (?, 1, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                    is_blocked = 1,
                    block_reason = excluded.block_reason,
                    last_active = CURRENT_TIMESTAMP;
            """, (user_id, reason))
            await db.commit()
            return True

    async def unblock_user(self, user_id: int) -> bool:
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                UPDATE support_users 
                SET is_blocked = 0, block_reason = NULL, last_active = CURRENT_TIMESTAMP 
                WHERE user_id = ?;
            """, (user_id,))
            await db.commit()
            return True

    async def get_blocked_users(self) -> List[Dict[str, Any]]:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT * FROM support_users WHERE is_blocked = 1 ORDER BY last_active DESC") as cur:
                rows = await cur.fetchall()
                return [dict(r) for r in rows]

    async def save_mapping(self, admin_message_id: int, user_id: int, user_message_id: int, direction: str = "inbound"):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                INSERT INTO message_mappings (admin_message_id, user_id, user_message_id, direction)
                VALUES (?, ?, ?, ?);
            """, (admin_message_id, user_id, user_message_id, direction))
            await db.commit()

    async def get_user_by_admin_msg(self, admin_message_id: int) -> Optional[Dict[str, Any]]:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("""
                SELECT user_id, user_message_id, direction, created_at
                FROM message_mappings
                WHERE admin_message_id = ?
                ORDER BY id DESC LIMIT 1;
            """, (admin_message_id,)) as cur:
                row = await cur.fetchone()
                return dict(row) if row else None

    async def get_user_info(self, user_id: int) -> Optional[Dict[str, Any]]:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute("SELECT * FROM support_users WHERE user_id = ?", (user_id,)) as cur:
                row = await cur.fetchone()
                if not row:
                    return None
                user_dict = dict(row)

            async with db.execute("SELECT COUNT(*) FROM message_mappings WHERE user_id = ? AND direction = 'inbound'", (user_id,)) as cur:
                in_count = (await cur.fetchone())[0]

            async with db.execute("SELECT COUNT(*) FROM message_mappings WHERE user_id = ? AND direction = 'outbound'", (user_id,)) as cur:
                out_count = (await cur.fetchone())[0]

            user_dict["inbound_messages"] = in_count
            user_dict["outbound_messages"] = out_count
            return user_dict

    async def get_all_user_ids(self) -> List[int]:
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute("SELECT user_id FROM support_users WHERE is_blocked = 0") as cur:
                rows = await cur.fetchall()
                return [r[0] for r in rows]

    async def get_stats(self) -> Dict[str, Any]:
        async with aiosqlite.connect(self.db_path) as db:
            async with db.execute("SELECT COUNT(*) FROM support_users") as cur:
                total_users = (await cur.fetchone())[0]

            async with db.execute("SELECT COUNT(*) FROM support_users WHERE is_blocked = 1") as cur:
                blocked_users = (await cur.fetchone())[0]

            async with db.execute("SELECT COUNT(*) FROM message_mappings WHERE direction = 'inbound'") as cur:
                inbound_msgs = (await cur.fetchone())[0]

            async with db.execute("SELECT COUNT(*) FROM message_mappings WHERE direction = 'outbound'") as cur:
                outbound_msgs = (await cur.fetchone())[0]

            return {
                "total_users": total_users,
                "blocked_users": blocked_users,
                "inbound_messages": inbound_msgs,
                "outbound_messages": outbound_msgs,
                "total_messages": inbound_msgs + outbound_msgs
            }

db = SupportDatabase()
