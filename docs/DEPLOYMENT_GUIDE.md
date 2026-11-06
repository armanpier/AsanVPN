# 🚀 AsanVPN Deployment Guide

This guide covers setting up:
1. **Cloudflare Worker Middleware** with Cloudflare KV
2. **Telegram Admin Bot & CLI**
3. **Android Client Application**

---

## 1. Cloudflare Worker Deployment

### Prerequisites:
- A free or paid [Cloudflare Account](https://dash.cloudflare.com)
- Node.js (v18 or newer installed)

### Steps:
```bash
cd backend/worker

# Install Wrangler CLI
npm install

# Authenticate with Cloudflare
npx wrangler login

# Create the KV Namespace for user PIN storage
npx wrangler kv:namespace create VPN_USERS
```

Copy the returned `id` (e.g., `4a1f...`) and paste it into `wrangler.toml`:
```toml
[[kv_namespaces]]
binding = "VPN_USERS"
id = "YOUR_ACTUAL_KV_NAMESPACE_ID"
```

Configure your secrets in `wrangler.toml` or via command line:
```bash
npx wrangler secret put ADMIN_TOKEN
# Type your secure admin secret token (e.g. MySuperSecretToken123!)
```

Deploy the worker:
```bash
npx wrangler deploy
```

Your Worker will now be live at:
`https://asanvpn-worker.<your-subdomain>.workers.dev`

*(Tip: You can also bind a custom domain in your Cloudflare dashboard under Worker -> Custom Domains, e.g. `api.yourdomain.com`).*

---

## 2. Telegram Admin Bot & CLI Setup

### Prerequisites:
- Python 3.10+
- Telegram Bot Token from [@BotFather](https://t.me/BotFather)

### Configuration:
```bash
cd backend/bot
pip install -r requirements.txt
cp .env.example .env
```

Edit `.env`:
```env
TELEGRAM_BOT_TOKEN="123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
ADMIN_CHAT_IDS="YOUR_TELEGRAM_USER_ID"
WORKER_URL="https://asanvpn-worker.yoursubdomain.workers.dev"
ADMIN_TOKEN="MySuperSecretToken123!"
```

### Running the Bot:
```bash
python admin_bot.py
```
If `TELEGRAM_BOT_TOKEN` is set, the bot will start polling and respond to `/start`, `/new`, `/users`, `/reset`, and `/switch_domain`.
If run without a bot token, it automatically runs as an interactive terminal CLI menu.

---

## 3. Android Client Setup & Build

The Android client is built with **Kotlin + Jetpack Compose** and targets Android 7.0+ (API 24 to 34).

### Project Configuration:
1. Open the `android/` directory in Android Studio (Giraffe, Hedgehog, or newer).
2. Open `android/app/src/main/java/com/asanvpn/app/data/VpnRepository.kt`.
3. In `workerEndpoints`, enter your deployed Cloudflare Worker URL and backup domains:
   ```kotlin
   private val workerEndpoints = listOf(
       "https://api.yourdomain.com",
       "https://asanvpn-worker.yoursubdomain.workers.dev",
       "https://backup-api.yourdomain.com"
   )
   ```
4. Build the APK:
   - In Android Studio: **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**
   - Or via CLI:
     ```bash
     cd android
     ./gradlew assembleRelease
     ```

### Testing the User Flow:
1. Create a PIN in Telegram or CLI:
   `/new HajReza https://your-panel.com/sub/token123 30`
2. You will receive a 4-digit code (e.g. `5812`).
3. Send the APK to the user.
4. On first launch, the user types `5812` on the giant keypad.
5. The app locks to their phone and presents the giant **روشن / خاموش** button!
