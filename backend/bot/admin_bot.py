#!/usr/bin/env python3
"""
AsanVPN Admin Orchestrator (Telegram Bot & CLI)
Allows sellers to manage 4-digit PINs, reset hardware locks, and bulk-migrate panels.
"""

import os
import sys
import json
import logging
from datetime import datetime
import requests
from dotenv import load_dotenv

load_dotenv()

# Configuration
WORKER_URL = os.getenv("WORKER_URL", "http://127.0.0.1:8787").rstrip("/")
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "CHANGEME_SECRET_ADMIN_TOKEN_9921")
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
ADMIN_CHAT_IDS = [int(x.strip()) for x in os.getenv("ADMIN_CHAT_IDS", "").split(",") if x.strip().isdigit()]

logging.basicConfig(format="%(asctime)s - %(name)s - %(levelname)s - %(message)s", level=logging.INFO)
logger = logging.getLogger("AsanVpnAdmin")

# Worker API Client
class WorkerClient:
    def __init__(self, base_url: str, token: str):
        self.base_url = base_url
        self.headers = {
            "Content-Type": "application/json",
            "X-Admin-Token": token
        }

    def create_user(self, name: str, sub_url: str, days: int = 30, pin: str = None, panel_type: str = "raw"):
        url = f"{self.base_url}/api/v1/admin/create"
        payload = {
            "name": name,
            "upstream_sub_url": sub_url,
            "days": days,
            "panel_type": panel_type
        }
        if pin:
            payload["pin"] = pin
        res = requests.post(url, json=payload, headers=self.headers, timeout=10)
        return res.json()

    def list_users(self):
        url = f"{self.base_url}/api/v1/admin/users"
        res = requests.get(url, headers=self.headers, timeout=10)
        return res.json()

    def reset_device(self, pin: str):
        url = f"{self.base_url}/api/v1/admin/reset_device"
        res = requests.post(url, json={"pin": pin}, headers=self.headers, timeout=10)
        return res.json()

    def extend_user(self, pin: str, days: int = 30):
        url = f"{self.base_url}/api/v1/admin/extend"
        res = requests.post(url, json={"pin": pin, "days": days}, headers=self.headers, timeout=10)
        return res.json()

    def delete_user(self, pin: str):
        url = f"{self.base_url}/api/v1/admin/delete"
        res = requests.post(url, json={"pin": pin}, headers=self.headers, timeout=10)
        return res.json()

    def migrate_domain(self, old_domain: str, new_domain: str, new_panel_type: str = None):
        url = f"{self.base_url}/api/v1/admin/migrate"
        payload = {
            "old_domain": old_domain,
            "new_domain": new_domain
        }
        if new_panel_type:
            payload["new_panel_type"] = new_panel_type
        res = requests.post(url, json=payload, headers=self.headers, timeout=15)
        return res.json()

client = WorkerClient(WORKER_URL, ADMIN_TOKEN)

# Telegram Bot Handler
def run_telegram_bot():
    from telegram import Update
    from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes

    async def check_auth(update: Update) -> bool:
        user_id = update.effective_user.id
        if ADMIN_CHAT_IDS and user_id not in ADMIN_CHAT_IDS:
            await update.message.reply_text("⛔️ دسترسی غیرمجاز. شناسه کاربری شما ثبت نشده است.")
            return False
        return True

    async def start_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
        if not await check_auth(update): return
        msg = (
            "🤖 **ربات مدیریت آسان وی‌پی‌ان (AsanVPN)**\n\n"
            "دستورات موجود:\n"
            "🔹 `/new <نام> <لینک_ساب> [تعداد_روز]` - ایجاد پین ۴ رقمی جدید\n"
            "🔹 `/users` - مشاهده لیست کاربران و پین‌ها\n"
            "🔹 `/info <پین>` - مشاهده وضعیت یک کاربر\n"
            "🔹 `/reset <پین>` - ریست قفل دستگاه کاربر (جهت تغییر گوشی)\n"
            "🔹 `/extend <پین> [روز]` - تمدید اشتراک\n"
            "🔹 `/del <پین>` - حذف کاربر\n"
            "🔹 `/switch_domain <دامنه_قدیم> <دامنه_جدید>` - انتقال دسته جمعی لینک‌ها به سرور یا پنل جدید\n"
        )
        await update.message.reply_text(msg, parse_mode="Markdown")

    async def new_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
        if not await check_auth(update): return
        args = context.args
        if len(args) < 2:
            await update.message.reply_text("فرمت صحیح:\n`/new <نام> <لینک_ساب> [تعداد_روز]`", parse_mode="Markdown")
            return

        name = args[0]
        sub_url = args[1]
        days = int(args[2]) if len(args) > 2 and args[2].isdigit() else 30

        try:
            res = client.create_user(name=name, sub_url=sub_url, days=days)
            if res.get("status") == "created":
                user = res["user"]
                pin = user["pin"]
                response_fa = (
                    f"✅ **اشتراک جدید با موفقیت ایجاد شد!**\n\n"
                    f"👤 **نام:** {name}\n"
                    f"🔢 **کد پین فعال‌سازی:** `{pin}`\n"
                    f"⏳ **مدت اعتبار:** {days} روز\n\n"
                    f"📋 **متن آماده جهت فوروارد برای مشتری:**\n"
                    f"➖➖➖➖➖➖➖➖➖➖\n"
                    f"سلام {name} عزیز،\n"
                    f"برنامه آسان وی‌پی‌ان را باز کنید و کد زیر را وارد نمایید:\n\n"
                    f"👈 کد ورود: `{pin}`\n\n"
                    f"سپس دکمه گرد روشن را لمس کنید تا متصل شوید. نیازی به کپی کردن هیچ لینکی نیست!"
                )
                await update.message.reply_text(response_fa, parse_mode="Markdown")
            else:
                await update.message.reply_text(f"❌ خطا در ایجاد کاربر: {res}")
        except Exception as e:
            await update.message.reply_text(f"❌ خطای شبکه: {str(e)}")

    async def users_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
        if not await check_auth(update): return
        try:
            res = client.list_users()
            users = res.get("users", [])
            if not users:
                await update.message.reply_text("هیچ کاربری یافت نشد.")
                return

            now = datetime.now().timestamp()
            lines = [f"👥 **لیست کاربران ({len(users)} نفر):**\n"]
            for u in users:
                pin = u.get("pin")
                name = u.get("name", "نامشخص")
                exp = u.get("expires_at", 0)
                days_left = max(0, int((exp - now) // 86400))
                device = "🔒 قفل شده" if u.get("device_id") else "🔓 آزاد"
                lines.append(f"• `{pin}` | **{name}** | {days_left} روز مانده | {device}")

            await update.message.reply_text("\n".join(lines), parse_mode="Markdown")
        except Exception as e:
            await update.message.reply_text(f"❌ خطا: {str(e)}")

    async def reset_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
        if not await check_auth(update): return
        if not context.args:
            await update.message.reply_text("فرمت: `/reset <پین>`", parse_mode="Markdown")
            return
        pin = context.args[0]
        try:
            res = client.reset_device(pin)
            if res.get("status") == "success":
                await update.message.reply_text(f"✅ قفل دستگاه برای پین `{pin}` ریست شد. مشتری اکنون می‌تواند با گوشی جدید وصل شود.", parse_mode="Markdown")
            else:
                await update.message.reply_text(f"❌ خطا: {res.get('error', 'کاربر یافت نشد')}")
        except Exception as e:
            await update.message.reply_text(f"❌ خطا: {str(e)}")

    async def extend_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
        if not await check_auth(update): return
        if not context.args:
            await update.message.reply_text("فرمت: `/extend <پین> [تعداد_روز]`", parse_mode="Markdown")
            return
        pin = context.args[0]
        days = int(context.args[1]) if len(context.args) > 1 and context.args[1].isdigit() else 30
        try:
            res = client.extend_user(pin, days)
            if res.get("status") == "success":
                await update.message.reply_text(f"✅ اشتراک پین `{pin}` به مدت {days} روز تمدید شد.", parse_mode="Markdown")
            else:
                await update.message.reply_text(f"❌ خطا: {res.get('error', 'کاربر یافت نشد')}")
        except Exception as e:
            await update.message.reply_text(f"❌ خطا: {str(e)}")

    async def switch_domain_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
        if not await check_auth(update): return
        if len(context.args) < 2:
            await update.message.reply_text("فرمت: `/switch_domain <دامنه_قدیم> <دامنه_جدید> [نوع_پنل_جدید]`", parse_mode="Markdown")
            return
        old_dom = context.args[0]
        new_dom = context.args[1]
        new_type = context.args[2] if len(context.args) > 2 else None
        try:
            res = client.migrate_domain(old_dom, new_dom, new_type)
            count = res.get("migrated_count", 0)
            await update.message.reply_text(
                f"🎉 **انتقال موفقیت‌آمیز بود!**\n"
                f"تعداد `{count}` اشتراک از دامنه `{old_dom}` به `{new_dom}` منتقل شدند.\n"
                f"کاربران در اولین اتصال به طور کاملاً خودکار سرورهای پنل جدید را دریافت می‌کنند.",
                parse_mode="Markdown"
            )
        except Exception as e:
            await update.message.reply_text(f"❌ خطا: {str(e)}")

    app = ApplicationBuilder().token(TELEGRAM_BOT_TOKEN).build()
    app.add_handler(CommandHandler("start", start_command))
    app.add_handler(CommandHandler("help", start_command))
    app.add_handler(CommandHandler("new", new_command))
    app.add_handler(CommandHandler("users", users_command))
    app.add_handler(CommandHandler("reset", reset_command))
    app.add_handler(CommandHandler("extend", extend_command))
    app.add_handler(CommandHandler("switch_domain", switch_domain_command))

    logger.info("Starting Telegram Bot polling...")
    app.run_polling()

# Interactive Terminal CLI Mode
def run_cli():
    print("=" * 60)
    print(" AsanVPN Admin CLI - Senior VPN Orchestrator")
    print(f" Connected Worker: {WORKER_URL}")
    print("=" * 60)

    while True:
        print("\nOptions:")
        print("1. Create New User PIN")
        print("2. List All Users")
        print("3. Reset Device Hardware Lock")
        print("4. Extend User Expiration")
        print("5. Delete User PIN")
        print("6. Bulk Migrate Panel Domain (3X-UI -> PasarGuard/Marzban)")
        print("7. Exit")
        choice = input("\nEnter choice (1-7): ").strip()

        if choice == "1":
            name = input("Customer Name (e.g. Haj Reza): ").strip()
            sub = input("Upstream Subscription URL: ").strip()
            days = input("Days of access [30]: ").strip() or "30"
            panel = input("Panel type (3xui/marzban/pasarguard/raw) [raw]: ").strip() or "raw"
            custom_pin = input("Custom PIN (leave blank for auto 4-digit): ").strip() or None
            res = client.create_user(name, sub, int(days), custom_pin, panel)
            print("\nResult:", json.dumps(res, indent=2, ensure_ascii=False))

        elif choice == "2":
            res = client.list_users()
            users = res.get("users", [])
            print(f"\n--- Total Users: {len(users)} ---")
            for u in users:
                print(f"PIN: {u.get('pin')} | Name: {u.get('name')} | Device: {u.get('device_id') or 'None'} | Sub: {u.get('upstream_sub_url')[:35]}...")

        elif choice == "3":
            pin = input("Enter 4-digit PIN to reset: ").strip()
            res = client.reset_device(pin)
            print("\nResult:", res)

        elif choice == "4":
            pin = input("Enter 4-digit PIN: ").strip()
            days = input("Days to add [30]: ").strip() or "30"
            res = client.extend_user(pin, int(days))
            print("\nResult:", res)

        elif choice == "5":
            pin = input("Enter 4-digit PIN to delete: ").strip()
            confirm = input(f"Are you sure you want to delete PIN {pin}? (y/n): ")
            if confirm.lower() == 'y':
                res = client.delete_user(pin)
                print("\nResult:", res)

        elif choice == "6":
            old_dom = input("Enter OLD domain/IP (e.g. old-xui.com): ").strip()
            new_dom = input("Enter NEW domain/IP (e.g. new-pasarguard.com): ").strip()
            new_panel = input("New panel type (optional): ").strip() or None
            res = client.migrate_domain(old_dom, new_dom, new_panel)
            print("\nMigration complete! Result:", res)

        elif choice == "7":
            print("Exiting.")
            sys.exit(0)

if __name__ == "__main__":
    if TELEGRAM_BOT_TOKEN:
        run_telegram_bot()
    else:
        print("Notice: TELEGRAM_BOT_TOKEN not provided in .env. Starting in Interactive CLI mode.")
        run_cli()
