import os
from dotenv import load_dotenv

load_dotenv()

# Support Bot Configuration
BOT_TOKEN = os.getenv("SUPPORT_BOT_TOKEN", "9118235680:AAGEhSdMtiU8y2wR5g_hrgjCsLLMB0G_HZM").strip()
BOT_USERNAME = os.getenv("SUPPORT_BOT_USERNAME", "Asanvpnsupport_bot").strip().lstrip("@")

# Admin IDs who receive user queries and can reply to them
# Default owner ID: 93795023
raw_admin_ids = os.getenv("ADMIN_IDS", "93795023").strip()
ADMIN_IDS = [int(x.strip()) for x in raw_admin_ids.split(",") if x.strip().isdigit()]

# Database path
DB_PATH = os.getenv("DB_PATH", "support_bot.db")
