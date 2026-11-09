import os
from dotenv import load_dotenv

load_dotenv()

# Telegram Bot
BOT_TOKEN = os.getenv("BOT_TOKEN", "").strip()
BOT_USERNAME = os.getenv("BOT_USERNAME", "asan_vpn_p_bot").strip().lstrip("@")
ADMIN_IDS = [int(x.strip()) for x in os.getenv("ADMIN_IDS", "").split(",") if x.strip().isdigit()]

# HooshPay Payment Gateway
HOOSHPAY_API_KEY = os.getenv("HOOSHPAY_API_KEY", "").strip()
HOOSHPAY_API_SECRET = os.getenv("HOOSHPAY_API_SECRET", "").strip()
HOOSHPAY_BASE_URL = os.getenv("HOOSHPAY_BASE_URL", "https://pay.hooshnet.com/api/v1").rstrip("/")
HOOSHPAY_RETURN_URL = f"https://t.me/{BOT_USERNAME}"

# Cloudflare Worker Admin
WORKER_URL = os.getenv("WORKER_URL", "https://asanvpn-worker.hi-deploy.workers.dev").rstrip("/")
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "").strip()

# Support & Channels
SUPPORT_USERNAME = os.getenv("SUPPORT_USERNAME", "@Sib_Support_P").strip()
APP_DOWNLOAD_URL = os.getenv("APP_DOWNLOAD_URL", "https://github.com/armanpier/AsanVPN/releases").strip()
CHANNEL_USERNAME = os.getenv("CHANNEL_USERNAME", "").strip()

# Database
DB_PATH = os.getenv("DB_PATH", "bot_data.db").strip()
