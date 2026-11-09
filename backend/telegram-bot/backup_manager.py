import os
import shutil
import sqlite3
import json
import logging
import time
from datetime import datetime
import aiosqlite
from config import DB_PATH

logger = logging.getLogger("BackupManager")

class BackupManager:
    def __init__(self, db_path: str = DB_PATH):
        self.db_path = db_path

    async def create_backup(self) -> str:
        """Creates a timestamped sqlite backup copy and returns its file path."""
        timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        backup_dir = "/tmp/asanvpn_backups"
        os.makedirs(backup_dir, exist_ok=True)
        backup_file = os.path.join(backup_dir, f"asanvpn_db_{timestamp}.sqlite")

        # Safely copy using sqlite online backup API
        try:
            src = sqlite3.connect(self.db_path)
            dst = sqlite3.connect(backup_file)
            with dst:
                src.backup(dst)
            dst.close()
            src.close()
            logger.info(f"Safe database snapshot created at {backup_file}")
            return backup_file
        except Exception as e:
            logger.error(f"Error creating sqlite backup: {e}")
            # Fallback simple copy
            shutil.copy2(self.db_path, backup_file)
            return backup_file

    async def create_worker_backup(self, worker) -> str:
        """Fetches all users and panels from Cloudflare Worker and dumps to JSON."""
        if not worker:
            return None
        timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        backup_dir = "/tmp/asanvpn_backups"
        os.makedirs(backup_dir, exist_ok=True)
        backup_file = os.path.join(backup_dir, f"asanvpn_worker_users_{timestamp}.json")

        try:
            import httpx
            async with httpx.AsyncClient(timeout=25.0) as client:
                res_u = await client.get(f"{worker.base_url}/api/v1/admin/users", headers=worker.headers)
                users = res_u.json().get("users", []) if res_u.status_code == 200 else []
                res_p = await client.get(f"{worker.base_url}/api/v1/admin/panels", headers=worker.headers)
                panels = res_p.json().get("panels", []) if res_p.status_code == 200 else []

            data = {
                "timestamp": timestamp,
                "exported_at": datetime.now().isoformat(),
                "total_users": len(users),
                "total_panels": len(panels),
                "users": users,
                "panels": panels
            }
            with open(backup_file, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)

            logger.info(f"Worker JSON snapshot created at {backup_file} with {len(users)} users")
            return backup_file
        except Exception as e:
            logger.error(f"Error creating worker JSON backup: {e}")
            return None

    async def perform_backup_and_send(self, bot, target_chat_id: str, db=None, worker=None) -> bool:
        """Creates SQLite + Worker JSON backups and sends documents to target channel/user."""
        try:
            backup_path = await self.create_backup()
            filename = os.path.basename(backup_path)

            stats_str = ""
            if db:
                try:
                    stats = await db.get_stats()
                    stats_str = (
                        f"\n👥 <b>تعداد کاربران:</b> {stats['total_users']}\n"
                        f"💳 <b>سفارشات موفق:</b> {stats['paid_orders']} از {stats['total_orders']}\n"
                        f"💰 <b>مجموع فروش:</b> {stats['total_revenue']:,} تومان"
                    )
                except Exception as e:
                    logger.error(f"Error getting stats for backup caption: {e}")

            caption = (
                f"📦 <b>نسخه پشتیبان پایگاه داده AsanVPN</b>\n"
                f"📅 <b>تاریخ:</b> {datetime.now().strftime('%Y/%m/%d - %H:%M:%S')}\n"
                f"📁 <b>فایل پایگاه داده:</b> <code>{filename}</code>"
                f"{stats_str}\n\n"
                f"#نسخه_پشتیبان #Database #AsanVPN"
            )

            with open(backup_path, 'rb') as f:
                await bot.send_document(
                    chat_id=target_chat_id,
                    document=f,
                    filename=filename,
                    caption=caption,
                    parse_mode="HTML"
                )

            # Also export and send Cloudflare Worker JSON backup
            if worker:
                worker_file = await self.create_worker_backup(worker)
                if worker_file and os.path.exists(worker_file):
                    wf_name = os.path.basename(worker_file)
                    w_caption = (
                        f"☁️ <b>نسخه پشتیبان کاربران و پنل‌های Cloudflare Worker</b>\n"
                        f"📅 <b>تاریخ:</b> {datetime.now().strftime('%Y/%m/%d - %H:%M:%S')}\n"
                        f"📁 <b>فایل:</b> <code>{wf_name}</code>\n"
                        f"🔒 شامل تمامی کدهای اشتراک، تنظیمات پروکسی و اتصال‌ها\n\n"
                        f"#WorkerBackup #Cloudflare #AsanVPN"
                    )
                    with open(worker_file, 'rb') as wf:
                        await bot.send_document(
                            chat_id=target_chat_id,
                            document=wf,
                            filename=wf_name,
                            caption=w_caption,
                            parse_mode="HTML"
                        )

            if db:
                await db.set_setting("last_backup_time", str(int(time.time())))

            logger.info(f"Successfully uploaded backups to {target_chat_id}")
            return True
        except Exception as e:
            logger.exception(f"Failed to send backup to {target_chat_id}: {e}")
            return False
