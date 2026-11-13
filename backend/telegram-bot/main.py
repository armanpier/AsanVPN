import os
import re
import io
import random
import asyncio
import logging
import time
import aiosqlite
import qrcode
from datetime import datetime
from telegram import (
    Update,
    InlineKeyboardMarkup,
    InlineKeyboardButton,
    ReplyKeyboardMarkup,
    KeyboardButton,
    BotCommand,
    BotCommandScopeDefault,
    BotCommandScopeChat,
    MenuButtonCommands
)
from telegram.constants import ParseMode
from telegram.ext import (
    Application,
    CommandHandler,
    MessageHandler,
    CallbackQueryHandler,
    ContextTypes,
    filters
)

from config import (
    BOT_TOKEN,
    ADMIN_IDS,
    BOT_USERNAME
)
from database import BotDatabase
from hooshpay import HooshPayClient
from worker_client import WorkerClient
from backup_manager import BackupManager

# Logging
logging.basicConfig(
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    level=logging.INFO
)
logger = logging.getLogger("AsanVpnBot")

db = BotDatabase()
hooshpay = HooshPayClient()
worker = WorkerClient()
backup_mgr = BackupManager()

CHANNEL_BACKUP_ID = "-1004389472709"

# In-memory states
USER_STATES = {}
ADMIN_STATES = {}

def is_admin(user_id: int) -> bool:
    return db.is_admin(user_id)

def get_main_keyboard(user_id: int):
    # Palette: Orange Pink (#FF6F52 -> danger), Glacial Depth (#004E64 -> primary)
    keyboard = [
        [
            KeyboardButton("🛒 خرید اشتراک جدید", style="danger"),
            KeyboardButton("🔄 تمدید و شارژ اشتراک", style="danger")
        ],
        [
            KeyboardButton("📊 وضعیت اشتراک من", style="primary"),
            KeyboardButton("📥 دانلود اپلیکیشن", style="primary")
        ],
        [
            KeyboardButton("💬 ارتباط با پشتیبانی", style="primary"),
            KeyboardButton("🚀 شروع مجدد (منوی اصلی)", style="primary")
        ],
        [
            KeyboardButton("🎁 دریافت اشتراک تست رایگان", style="danger")
        ]
    ]
    if is_admin(user_id):
        keyboard.append([KeyboardButton("⚙️ پنل مدیریت هوشمند ربات", style="danger")])
    return ReplyKeyboardMarkup(keyboard, resize_keyboard=True, is_persistent=True)

# ----------------- USER FLOW: /start & WELCOME -----------------

WELCOME_VIDEO_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "welcome_ffd3c6.mp4")

DEFAULT_WELCOME_TEXT = (
    "✨ <b>به ربات هوشمند آسان وی‌پی‌ان (AsanVPN) خوش آمدید!</b> 🛡️⚡\n\n"
    "<blockquote>🚀 <b>اینترنت کاملاً آزاد، پرسرعت و پایدار بدون قطعی</b></blockquote>\n\n"
    "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
    "🤖 <b>قابلیت‌های هوشمند ربات آسان وی‌پی‌ان:</b>\n\n"
    "💎 <b>خرید آنی و خودکار:</b> انتخاب پلن و پرداخت آنلاین مستقیم با کلیه کارت‌های شتاب.\n"
    "🔑 <b>تحویل فوری کد ۱۰ رقمی:</b> صدور خودکار کد اشتراک بلافاصله پس از پرداخت بدون معطلی.\n"
    "🔄 <b>شارژ تجمعی بدون سوخت حجم:</b> با خرید قبل از پایان اشتراک، حجم و روزها به اکانت فعلی شما افزوده می‌شود.\n"
    "📊 <b>استعلام لحظه‌ای:</b> مشاهده مانده حجم و روزهای باقیمانده در هر ساعت شبانه‌روز.\n"
    "📱 <b>اتصال با اپلیکیشن AsanVPN:</b> اتصال تک‌کلیکه با پروتکل‌های نوین ضد فیلتر (VLESS, Reality).\n"
    "💬 <b>پشتیبانی ۲۴ ساعته:</b> راهنمایی سریع و پاسخگویی به تمام سوالات شما.\n"
    "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
    "👇 <i>جهت شروع، یکی از گزینه‌های زیر را انتخاب نمایید:</i>"
)

async def start_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    await db.register_user(user.id, user.username or "", user.first_name or "")
    USER_STATES.pop(user.id, None)
    ADMIN_STATES.pop(user.id, None)

    # Ensure menu button is activated for this specific chat
    try:
        await context.bot.set_chat_menu_button(chat_id=user.id, menu_button=MenuButtonCommands())
    except Exception:
        pass

    welcome_template = await db.get_setting("welcome_message")
    if not welcome_template:
        welcome_template = DEFAULT_WELCOME_TEXT

    welcome_text = welcome_template.replace("{name}", user.first_name or "کاربر")
    app_download_url = await db.get_setting("app_download_url", "https://github.com/armanpier/AsanVPN/releases/download/v1.2.0/AsanVPN-v1.2.0-universal.apk")
    support_username = await db.get_setting("support_username", "@Asanvpnsupport_bot")

    inline_kb = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("🔙 شروع مجدد (منو)", callback_data="back_to_home", style="primary")
        ]
    ])

    video_file_id = await db.get_setting("welcome_video_file_id")
    video_sent = False
    chat_id = user.id

    # 1. Try sending cached video_file_id for instantaneous delivery
    if video_file_id:
        try:
            await context.bot.send_video(
                chat_id=chat_id,
                video=video_file_id,
                caption=welcome_text,
                parse_mode=ParseMode.HTML,
                reply_markup=inline_kb
            )
            video_sent = True
        except Exception as e:
            logger.warning(f"Cached video_file_id failed: {e}. Falling back to local file.")
            await db.set_setting("welcome_video_file_id", "")

    # 2. Try sending local video file on server if not sent yet
    if not video_sent and os.path.exists(WELCOME_VIDEO_PATH):
        try:
            with open(WELCOME_VIDEO_PATH, "rb") as vf:
                sent_msg = await context.bot.send_video(
                    chat_id=chat_id,
                    video=vf,
                    caption=welcome_text,
                    parse_mode=ParseMode.HTML,
                    reply_markup=inline_kb
                )
            video_sent = True
            if sent_msg.video:
                await db.set_setting("welcome_video_file_id", sent_msg.video.file_id)
                logger.info(f"Welcome video cached on Telegram CDN: {sent_msg.video.file_id}")
        except Exception as e:
            logger.error(f"Failed to send local welcome video: {e}")

    # 3. Fallback to text message if video sending failed
    if not video_sent:
        if update.message:
            await update.message.reply_text(
                welcome_text,
                reply_markup=inline_kb,
                parse_mode=ParseMode.HTML
            )
        elif update.callback_query:
            await update.callback_query.message.reply_text(
                welcome_text,
                reply_markup=inline_kb,
                parse_mode=ParseMode.HTML
            )

    # Always refresh bottom persistent reply keyboard
    try:
        await context.bot.send_message(
            chat_id=chat_id,
            text="👇 برای دسترسی سریع، از منوی زیر یا دکمه <b>[ ☰ منو ]</b> استفاده فرمایید:",
            reply_markup=get_main_keyboard(user.id),
            parse_mode=ParseMode.HTML
        )
    except Exception as e:
        logger.error(f"Failed to send main keyboard prompt: {e}")

# ----------------- USER FLOW: BUY NEW PLAN -----------------

async def show_plans_menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    # Check maintenance mode
    m_mode = await db.get_setting("maintenance_mode", "0")
    user_id = update.effective_user.id
    if m_mode == "1" and not is_admin(user_id):
        msg = "⚠️ درگاه فروش به دلیل ارتقا و بهینه‌سازی شبکه موقتاً غیرفعال است. لطفاً ساعاتی دیگر مراجعه فرمایید."
        if update.message:
            await update.message.reply_text(msg)
        elif update.callback_query:
            await update.callback_query.edit_message_text(msg)
        return

    plans = await db.get_plans(active_only=True)
    back_kb = InlineKeyboardMarkup([[InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home")]])
    if not plans:
        msg = "❌ در حال حاضر هیچ پلنی فعال نیست. لطفاً بعداً بررسی نمایید."
        if update.message:
            await update.message.reply_text(msg, reply_markup=back_kb)
        elif update.callback_query:
            await update.callback_query.edit_message_text(msg, reply_markup=back_kb)
        return

    text = (
        "🛍️ <b>لیست پلن‌های اشتراک آسان وی‌پی‌ان:</b>\n\n"
        "تمامی پلن‌ها شامل سرورهای اختصاصی VIP، پینگ پایین و ضمانت پایداری هستند. "
        "پلن مورد نظر خود را انتخاب فرمایید:"
    )

    user = update.effective_user or (update.callback_query and update.callback_query.from_user)
    user_id = user.id if user else 0
    has_claimed = await db.has_claimed_test(user_id) if user_id else False
    has_paid = await db.has_paid_order(user_id) if user_id else False

    keyboard = []
    if not has_claimed and not has_paid:
        keyboard.append([
            InlineKeyboardButton("🎁 دریافت ۱ بار اشتراک تست رایگان (۱ گیگ)", callback_data="claim_free_test", style="danger")
        ])

    styles = ["danger", "primary"]
    for idx, plan in enumerate(plans):
        st = styles[idx % len(styles)]
        price_fa = f"{plan['price_toman']:,}"
        btn_text = f"⚡ {plan['title']} — {price_fa} تومان (۲ کاربر)"
        keyboard.append([InlineKeyboardButton(btn_text, callback_data=f"buy_plan:{plan['id']}", style=st)])
    keyboard.append([InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")])

    reply_markup = InlineKeyboardMarkup(keyboard)
    if update.message:
        await update.message.reply_text(text, reply_markup=reply_markup, parse_mode=ParseMode.HTML)
    elif update.callback_query:
        await update.callback_query.edit_message_text(text, reply_markup=reply_markup, parse_mode=ParseMode.HTML)

async def create_and_send_checkout_invoice(context: ContextTypes.DEFAULT_TYPE, query, plan_id: str, order_type: str = "new", target_pin: str = None):
    plan = await db.get_plan_by_id(plan_id)
    if not plan:
        await query.edit_message_text("❌ پلن انتخابی نامعتبر است.")
        return

    price_fa = f"{plan['price_toman']:,}"
    user = query.from_user
    prefix = "RENEW" if order_type == "renew" else "BUY"

    # Admin test purchase bypass: allows admin to test robot & worker provisioning without bank payment
    admin_bypass = await db.get_setting("admin_bypass_payment", "1")
    if is_admin(user.id) and admin_bypass == "1":
        await query.edit_message_text(
            f"👑 <b>[حالت تست مدیر]</b>\n\n"
            f"شما به عنوان ادمین ربات وارد شدید.\n"
            f"پلن <b>«{plan['title']}»</b> بدون نیاز به پرداخت در درگاه بانکی در حال صدور و فعال‌سازی آنی در سیستم است...\n"
            f"لطفاً چند لحظه شکیبا باشید ⏳",
            parse_mode=ParseMode.HTML
        )
        fake_uid = f"ADMIN_FREE_{int(time.time())}_{random.randint(1000, 9999)}"
        order_id = f"ADMIN_{prefix}_{user.id}_{int(time.time())}"

        await db.create_order(
            order_id=order_id,
            telegram_id=user.id,
            order_type=order_type,
            plan_id=plan_id,
            amount_toman=0,
            hooshpay_uid=fake_uid,
            payment_url="ADMIN_BYPASS_TEST",
            target_pin=target_pin
        )

        order = {
            "order_id": order_id,
            "hooshpay_uid": fake_uid,
            "telegram_id": user.id,
            "order_type": order_type,
            "plan_id": plan_id,
            "amount_toman": 0,
            "target_pin": target_pin
        }

        success = await fulfill_paid_order(context, order, tracking_code=f"ADMIN-FREE-{int(time.time())}")
        if not success:
            await query.message.reply_text("❌ خطا در فعال‌سازی آنی در ورکر کلودفلر یا پنل‌ها.")
        return

    loading_msg = await query.edit_message_text(
        "⏳ در حال صدور فاکتور پرداخت هوش‌پی...\nلطفاً چند لحظه شکیبا باشید.",
        parse_mode=ParseMode.HTML
    )

    prefix = "RENEW" if order_type == "renew" else "BUY"
    order_id = f"{prefix}_{user.id}_{int(time.time())}"
    desc = f"تمدید اشتراک {target_pin} آسان وی‌پی‌ان ({plan['title']})" if order_type == "renew" else f"خرید {plan['title']} آسان وی‌پی‌ان"

    invoice = await hooshpay.create_invoice(
        amount_toman=plan["price_toman"],
        order_id=order_id,
        description=desc
    )

    if not invoice or not invoice.get("payment_url"):
        await loading_msg.edit_text(
            "⚠️ خطا در ارتباط با درگاه پرداخت هوش‌پی.\n"
            "لطفاً مجدداً تلاش نمایید یا با پشتیبانی تماس حاصل فرمایید.",
            reply_markup=InlineKeyboardMarkup([
                [InlineKeyboardButton("🔙 بازگشت به لیست پلن‌ها", callback_data="back_to_plans")]
            ])
        )
        return

    uid = invoice["uid"]
    payment_url = invoice["payment_url"]

    # Save order to DB
    await db.create_order(
        order_id=order_id,
        telegram_id=user.id,
        order_type=order_type,
        plan_id=plan_id,
        amount_toman=plan["price_toman"],
        hooshpay_uid=uid,
        payment_url=payment_url,
        target_pin=target_pin
    )

    traffic_display = "نامحدود" if plan["traffic_gb"] == 0 else f"{plan['traffic_gb']} گیگابایت"
    type_title = "تمدید و شارژ اشتراک" if order_type == "renew" else "خرید اشتراک جدید"
    target_info = f"\n🔑 <b>کد اشتراک هدف:</b> <code>{target_pin}</code> (شارژ تجمعی)" if target_pin else ""

    msg_text = (
        f"🧾 <b>پیش‌فاکتور {type_title}</b>\n\n"
        f"📦 <b>پلن:</b> {plan['title']}\n"
        f"⏳ <b>مدت اعتبار:</b> {plan['days']} روز\n"
        f"📊 <b>ترافیک:</b> {traffic_display}\n"
        f"📱 <b>دستگاه‌های مجاز:</b> {plan['max_devices']} گوشی (۲ دستگاه همزمان){target_info}\n"
        f"💰 <b>مبلغ قابل پرداخت:</b> {price_fa} تومان\n\n"
        "💳 برای تکمیل خرید، دکمه <b>«پرداخت آنلاین»</b> را لمس کنید و پس از پرداخت به ربات بازگشته و دکمه <b>«بررسی پرداخت»</b> را بزنید:"
    )

    pay_keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("💳 پرداخت آنلاین (هوش‌پی)", url=payment_url)],
        [InlineKeyboardButton("🔄 بررسی وضعیت و اعمال اشتراک", callback_data=f"verify_order:{uid}")],
        [InlineKeyboardButton("🔙 بازگشت به پلن‌ها", callback_data="back_to_plans"), InlineKeyboardButton("🔄 منوی اصلی", callback_data="back_to_home")]
    ])

    await loading_msg.edit_text(msg_text, reply_markup=pay_keyboard, parse_mode=ParseMode.HTML)

async def plan_selected_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()

    plan_id = query.data.split(":")[1]
    plan = await db.get_plan_by_id(plan_id)
    if not plan:
        await query.edit_message_text("❌ پلن انتخابی نامعتبر است.")
        return

    user_id = query.from_user.id

    # Check if this user already has an active, unexpired subscription
    latest_pin = await db.get_user_latest_pin(user_id)
    has_active_plan = False
    active_user_data = None

    if latest_pin:
        active_user_data = await worker.get_user_by_pin(latest_pin)
        now = int(time.time())
        exp = (active_user_data.get("expires_at") or 0) if active_user_data else 0
        if active_user_data and exp > now:
            has_active_plan = True

    # If user has an active plan, ASK them whether to extend or buy a new one!
    if has_active_plan and active_user_data:
        now = int(time.time())
        exp = active_user_data.get("expires_at", now)
        rem_days = max(1, (exp - now) // 86400)
        exp_date_str = time.strftime('%Y-%m-%d', time.localtime(exp))
        price_fa = f"{plan['price_toman']:,}"

        ask_text = (
            "🔔 <b>اشتراک فعال شناسایی شد!</b>\n\n"
            f"شما در حال حاضر یک اشتراک فعال در سیستم دارید:\n"
            f"🔑 <b>کد اشتراک فعلی شما:</b> <code>{latest_pin}</code>\n"
            f"⏳ <b>اعتبار باقیمانده:</b> حدود {rem_days} روز (تا تاریخ {exp_date_str})\n"
            f"📦 <b>پلن انتخابی جدید:</b> {plan['title']} ({price_fa} تومان)\n\n"
            "لطفاً مشخص فرمایید که مایلید این پلن چگونه اعمال گردد؟\n\n"
            "🔄 <b>تمدید و شارژ اشتراک فعلی:</b>\n"
            f"حجم ({plan['traffic_gb']}GB) و روزها ({plan['days']} روز) به همین کد <code>{latest_pin}</code> اضافه می‌شود (حجم فعلی نمی‌سوزد و نیازی به تغییر کد در برنامه نیست).\n\n"
            "➕ <b>دریافت اشتراک کاملاً جدید:</b>\n"
            "یک کد اختصاصی ۱۰ رقمی مجزا و مستقل برای شما صادر خواهد شد."
        )

        ask_keyboard = InlineKeyboardMarkup([
            [InlineKeyboardButton("🟠 تمدید و شارژ اشتراک فعلی", callback_data=f"choose_plan_action:renew:{plan_id}:{latest_pin}")],
            [InlineKeyboardButton("🟡 دریافت اشتراک جدید (۲ دستگاه مجزا)", callback_data=f"choose_plan_action:new:{plan_id}")],
            [InlineKeyboardButton("🔙 بازگشت به پلن‌ها", callback_data="back_to_plans"), InlineKeyboardButton("🔄 منوی اصلی", callback_data="back_to_home")]
        ])

        await query.edit_message_text(ask_text, reply_markup=ask_keyboard, parse_mode=ParseMode.HTML)
        return

    # If user does NOT have an active plan, proceed directly to new checkout
    await create_and_send_checkout_invoice(context, query, plan_id, order_type="new", target_pin=None)

async def choose_plan_action_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()

    parts = query.data.split(":")
    action = parts[1]  # "renew" or "new"
    plan_id = parts[2]
    target_pin = parts[3] if len(parts) > 3 else None

    if action == "renew" and target_pin:
        await create_and_send_checkout_invoice(context, query, plan_id, order_type="renew", target_pin=target_pin)
    else:
        await create_and_send_checkout_invoice(context, query, plan_id, order_type="new", target_pin=None)

# ----------------- USER FLOW: RENEWAL -----------------

async def ask_renew_pin(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    user_id = user.id
    latest_pin = await db.get_user_latest_pin(user_id)

    if latest_pin:
        text = (
            "🔄 <b>تمدید و افزایش حجم اشتراک:</b>\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
            "🔑 <b>کد اشتراک فعال شما:</b>\n\n"
            f"👉  <code>{latest_pin}</code>  👈\n\n"
            "👆 <b>(کافیست یک‌بار روی کد بالا ضربه بزنید تا کپی شود)</b>\n\n"
            f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={latest_pin}\"><b>کلیک کنید تا برنامه آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
            "مایلید همین اشتراک را تمدید فرمایید یا کد اشتراک دیگری را شارژ کنید؟"
        )
        keyboard = InlineKeyboardMarkup([
            [InlineKeyboardButton("🚀 اتصال و فعال‌سازی مستقیم در برنامه", url=f"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={latest_pin}")],
            [InlineKeyboardButton(f"🟠 تمدید همین اشتراک ({latest_pin})", callback_data=f"renew_pin_direct:{latest_pin}")],
            [InlineKeyboardButton("✏️ شارژ کد دستی دیگر", callback_data="renew_manual_input")],
            [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home")]
        ])
        if update.message:
            await update.message.reply_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
        elif update.callback_query:
            await update.callback_query.edit_message_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
        return

    USER_STATES[user_id] = "waiting_for_renew_pin"
    text = (
        "🔄 <b>تمدید اشتراک آسان وی‌پی‌ان:</b>\n\n"
        "لطفاً <b>کد اختصاصی ۱۰ رقمی (یا ۸ رقمی)</b> اشتراک فعلی خود را ارسال فرمایید:"
    )
    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home")]
    ])
    if update.message:
        await update.message.reply_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
    elif update.callback_query:
        await update.callback_query.edit_message_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)

async def renew_manual_input_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    user_id = query.from_user.id
    USER_STATES[user_id] = "waiting_for_renew_pin"
    text = (
        "🔄 <b>تمدید اشتراک با کد دستی:</b>\n\n"
        "لطفاً <b>کد اختصاصی ۱۰ رقمی (یا ۸ رقمی)</b> اشتراک فعلی خود را ارسال فرمایید:"
    )
    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home")]
    ])
    await query.edit_message_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)

async def handle_renew_pin_input(update_or_query, context: ContextTypes.DEFAULT_TYPE, pin: str):
    is_query = hasattr(update_or_query, "message") and hasattr(update_or_query, "data")
    user = update_or_query.from_user if is_query else update_or_query.effective_user
    USER_STATES.pop(user.id, None)

    if is_query:
        msg = await update_or_query.edit_message_text("🔍 در حال استعلام وضعیت کد اشتراک از سرور...")
    else:
        msg = await update_or_query.message.reply_text("🔍 در حال استعلام وضعیت کد اشتراک از سرور...")

    user_record = await worker.get_user_by_pin(pin)

    if not user_record:
        await msg.edit_text(
            f"❌ کد اشتراک <code>{pin}</code> در سیستم یافت نشد.\n\n"
            "لطفاً از صحت کد ۱۰ رقمی (یا ۸ رقمی) اطمینان حاصل کرده یا یک اشتراک جدید خریداری فرمایید.",
            parse_mode=ParseMode.HTML,
            reply_markup=InlineKeyboardMarkup([
                [InlineKeyboardButton("🟡 خرید اشتراک جدید", callback_data="buy_new_plan")],
                [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home")]
            ])
        )
        return

    expires_at = user_record.get("expires_at")
    now = int(time.time())
    if expires_at and expires_at > now:
        days_left = max(0, (expires_at - now) // 86400)
        status_str = f"🟢 فعال ({days_left} روز باقی‌مانده)"
    else:
        status_str = "🔴 منقضی شده"

    text = (
        "👤 <b>اطلاعات اشتراک شما جهت تمدید:</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "🔑 <b>کد اشتراک:</b>\n\n"
        f"👉  <code>{pin}</code>  👈\n\n"
        "👆 <b>(کافیست یک‌بار روی کد بالا ضربه بزنید تا کپی شود)</b>\n\n"
        f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}\"><b>کلیک کنید تا برنامه آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
        f"🏷️ <b>نام اکانت:</b> {user_record.get('name', 'کاربر آسان وی‌پی‌ان')}\n"
        f"⏱️ <b>وضعیت کنونی:</b> {status_str}\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "بسته تمدید مورد نظر خود را انتخاب فرمایید:"
    )

    plans = await db.get_plans(active_only=True)
    keyboard = [
        [InlineKeyboardButton("🚀 اتصال و فعال‌سازی مستقیم در برنامه", url=f"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}")]
    ]
    palette_dots = ["🟡", "🟠", "🔵", "🟣"]
    for idx, p in enumerate(plans):
        dot = palette_dots[idx % len(palette_dots)]
        price_fa = f"{p['price_toman']:,}"
        btn_text = f"{dot} تمدید {p['title']} — {price_fa} تومان"
        keyboard.append([InlineKeyboardButton(btn_text, callback_data=f"renew_opt:{pin}:{p['id']}")])

    keyboard.append([InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home")])

    await msg.edit_text(text, reply_markup=InlineKeyboardMarkup(keyboard), parse_mode=ParseMode.HTML)

async def renew_option_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()

    parts = query.data.split(":")
    pin = parts[1]
    plan_id = parts[2]
    await create_and_send_checkout_invoice(context, query, plan_id, order_type="renew", target_pin=pin)

# ----------------- PAYMENT FULFILLMENT & NOTIFICATIONS -----------------

async def notify_admins(context: ContextTypes.DEFAULT_TYPE, message_html: str, exclude_id: int = None):
    notify_sale = await db.get_setting("notify_admin_on_sale", "1")
    if notify_sale != "1":
        return

    admin_targets = db.get_admin_ids()
    for aid in admin_targets:
        if exclude_id and aid == exclude_id:
            continue
        try:
            await context.bot.send_message(chat_id=aid, text=message_html, parse_mode=ParseMode.HTML)
        except Exception as e:
            logger.error(f"Failed to notify admin {aid}: {e}")

async def fulfill_paid_order(context: ContextTypes.DEFAULT_TYPE, order: dict, tracking_code: str) -> bool:
    uid = order["hooshpay_uid"]
    telegram_id = order["telegram_id"]
    order_type = order.get("order_type", "new")
    app_download_url = await db.get_setting("app_download_url", "https://github.com/armanpier/AsanVPN/releases/download/v1.2.0/AsanVPN-v1.2.0-universal.apk")
    support_username = await db.get_setting("support_username", "@Asanvpnsupport_bot")
    is_admin_test = str(tracking_code).startswith("ADMIN-")

    plan = await db.get_plan_by_id(order.get("plan_id"))
    days = plan["days"] if plan else 30
    traffic_gb = plan["traffic_gb"] if plan else 10
    max_devices = 2  # All bot-created subscriptions are strictly locked to 2 devices
    plan_title = plan["title"] if plan else f"{traffic_gb} گیگابایت - {days} روز"

    target_pin = order.get("target_pin")
    is_cumulative = False
    is_unexpired_upgrade = False

    if (order_type == "renew" or target_pin) and target_pin:
        is_cumulative = True
        is_unexpired_upgrade = True

    if is_cumulative and target_pin:
        # Extend existing active PIN cumulatively on Worker and remote panels
        res = await worker.extend_user(pin=target_pin, days=days, traffic_gb=traffic_gb)
        if not res or res.get("status") != "success":
            logger.error(f"Failed to extend PIN {target_pin} for order {uid}")
            return False

        await db.mark_order_paid(uid, tracking_code, assigned_pin=target_pin)
        await db.link_pin_to_user(telegram_id, target_pin)

        new_exp = res.get("new_expires_at")
        exp_str = time.strftime('%Y-%m-%d %H:%M', time.localtime(new_exp)) if new_exp else f"{days} روز اضافه شد"
        total_traffic_bytes = res.get("traffic_total_bytes", 0)
        total_traffic_gb = round(total_traffic_bytes / (1024**3), 1) if total_traffic_bytes else traffic_gb

        if is_admin_test:
            success_text = (
                "👑 <b>[تست مدیر] شارژ و ارتقای تجمعی اشتراک شما!</b>\n\n"
                f"اشتراک فعال شما با کد <code>{target_pin}</code> به صورت آزمایشی (بدون نیاز به پرداخت درگاه) شارژ و تمدید گردید:\n\n"
                f"🔑 <b>کد اشتراک شما:</b>\n"
                f"👉  <code>{target_pin}</code>  👈\n"
                "<i>(برای کپی، کافیست روی کد بالا ضربه بزنید)</i>\n\n"
                f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={target_pin}\"><b>کلیک کنید تا برنامه آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
                f"➕ <b>حجم اضافه شده:</b> {traffic_gb} گیگابایت\n"
                f"➕ <b>اعتبار زمانی اضافه شده:</b> {days} روز\n"
                f"📊 <b>مجموع کل حجم اشتراک:</b> {total_traffic_gb} گیگابایت\n"
                f"⏳ <b>تاریخ انقضای جدید:</b> {exp_str}\n\n"
                "📱 <b>راهنمای اتصال:</b>\n"
                "حجم و زمان تستی بلافاصله در سیستم ثبت شد و در برنامه بدون نیاز به تغییر کد فعال است!\n\n"
                f"🧾 <b>شناسه سفارش تستی:</b> <code>{tracking_code}</code>"
            )
        elif is_unexpired_upgrade:
            success_text = (
                "🎉 <b>شارژ و ارتقای تجمعی اشتراک شما!</b>\n\n"
                f"اشتراک فعال شما با کد <code>{target_pin}</code> هنوز منقضی نشده بود؛ بنابراین حجم و روزهای پلن جدید به صورت <b>تجمعی</b> به اشتراک فعلی شما افزوده شد:\n\n"
                f"🔑 <b>کد اشتراک شما:</b>\n"
                f"👉 <code>{target_pin}</code> 👈\n"
                "<i>(برای کپی، کافیست روی کد بالا ضربه بزنید)</i>\n\n"
                f"➕ <b>حجم اضافه شده:</b> {traffic_gb} گیگابایت\n"
                f"➕ <b>اعتبار زمانی اضافه شده:</b> {days} روز\n"
                f"📊 <b>مجموع کل حجم اشتراک:</b> {total_traffic_gb} گیگابایت\n"
                f"⏳ <b>تاریخ انقضای جدید:</b> {exp_str}\n\n"
                "📱 <b>راهنمای اتصال:</b>\n"
                "نیازی به تغییر هیچ کدی در نرم‌افزار آسان وی‌پی‌ان نیست؛ حجم و زمان جدید بلافاصله اعمال گردید و می‌توانید به اتصال خود ادامه دهید!\n\n"
                f"🧾 <b>کد رهگیری تراکنش هوش‌پی:</b> <code>{tracking_code}</code>\n"
                "🙏 از همراهی و اعتماد شما سپاسگزاریم."
            )
        else:
            success_text = (
                "🎉 <b>اشتراک شما با موفقیت تمدید شد!</b>\n\n"
                f"🔑 <b>کد اشتراک:</b> <code>{target_pin}</code>\n"
                f"➕ <b>حجم اضافه شده:</b> {traffic_gb} گیگابایت\n"
                f"⏳ <b>اعتبار زمانی اضافه شده:</b> {days} روز\n"
                f"📊 <b>مجموع کل حجم:</b> {total_traffic_gb} گیگابایت\n"
                f"⏳ <b>تاریخ انقضای جدید:</b> {exp_str}\n\n"
                f"🧾 <b>کد رهگیری هوش‌پی:</b> <code>{tracking_code}</code>\n\n"
                "🚀 بدون نیاز به تغییر در اپلیکیشن، اتصال شما هم‌اکنون با حداکثر سرعت فعال است."
            )

        try:
            await context.bot.send_message(
                chat_id=telegram_id,
                text=success_text,
                parse_mode=ParseMode.HTML,
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton("🟣 دانلود اپلیکیشن اندروید", url=app_download_url)],
                    [InlineKeyboardButton("🟤 پشتیبانی تلگرام", url=f"https://t.me/{support_username.lstrip('@')}")],
                    [InlineKeyboardButton("🔄 شروع مجدد / منوی اصلی 🚀", callback_data="back_to_home")]
                ])
            )
            await context.bot.send_message(
                chat_id=telegram_id,
                text="👇 منوی دسترسی سریع ربات آسان وی‌پی‌ان (همواره در پایین صفحه در دسترس شماست):",
                reply_markup=get_main_keyboard(telegram_id)
            )
        except Exception as e:
            logger.error(f"Failed to send success msg to {telegram_id}: {e}")

        # Notify Admin (ONLY for ordinary customer purchases, NEVER for admin's own purchase or admin test)
        if not is_admin_test and not db.is_admin(telegram_id):
            admin_alert = (
                "🔔 <b>#تمدید_تجمعی_اشتراک</b>\n\n"
                f"👤 <b>کاربر:</b> <code>{telegram_id}</code>\n"
                f"📦 <b>پلن:</b> {plan_title} ({traffic_gb}GB, {days} روز)\n"
                f"💰 <b>مبلغ:</b> {order['amount_toman']:,} تومان\n"
                f"🔑 <b>کد اشتراک شارژ شده:</b> <code>{target_pin}</code>\n"
                f"📊 <b>مجموع حجم جدید:</b> {total_traffic_gb} گیگابایت\n"
                f"🧾 <b>شناسه پیگیری:</b> <code>{tracking_code}</code>"
            )
            await notify_admins(context, admin_alert, exclude_id=telegram_id)
        return True

    else:
        # Create user via Cloudflare Worker & remote panels with exact traffic_gb and days
        user_name = f"Telegram @{telegram_id}"
        created_user = await worker.create_user(
            name=user_name,
            days=days,
            traffic_gb=traffic_gb,
            max_devices=max_devices
        )

        if not created_user or not created_user.get("pin"):
            logger.error(f"Failed to auto-provision user on worker for order {uid}")
            return False

        pin = created_user["pin"]
        await db.mark_order_paid(uid, tracking_code, assigned_pin=pin)
        await db.link_pin_to_user(telegram_id, pin)

        # Notify buyer
        if is_admin_test:
            success_text = (
                "👑 <b>[تست مدیر] اشتراک آزمایشی شما بدون نیاز به پرداخت فعال شد!</b>\n\n"
                "🔑 <b>کد اختصاصی اشتراک شما:</b>\n\n"
                f"👉  <code>{pin}</code>  👈\n\n"
                "👆 <i>(برای کپی کردن، کافیست روی کد بالا ضربه بزنید)</i>\n\n"
                f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}\"><b>کلیک کنید تا اپلیکیشن آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
                f"📊 <b>حجم ترافیک:</b> {traffic_gb} گیگابایت\n"
                f"⏳ <b>مدت اعتبار:</b> {days} روز\n\n"
                "📱 <b>راهنمای اتصال سریع:</b>\n"
                "۱. دکمه «اتصال و فعال‌سازی مستقیم در برنامه» زیر را لمس نمایید تا برنامه خودکار باز شود.\n"
                f"۲. یا می‌توانید نرم‌افزار <b>آسان وی‌پی‌ان</b> را باز کرده و کد اختصاصی <code>{pin}</code> را وارد فرمایید.\n"
                "۳. دکمه اتصال را لمس کنید تا به بهترین سرور متصل شوید!\n\n"
                "🔒 <b>امنیت و قفل دستگاه:</b> اشتراک تستی برای ۲ دستگاه همزمان فعال بوده و بر روی دو دستگاه اول متصل‌شده قفل می‌گردد.\n\n"
                f"🧾 <b>شناسه سفارش تستی:</b> <code>{tracking_code}</code>"
            )
        else:
            success_text = (
                "🎉 <b>پرداخت شما با موفقیت تایید شد!</b>\n\n"
                "🔑 <b>کد اختصاصی اشتراک شما:</b>\n\n"
                f"👉  <code>{pin}</code>  👈\n\n"
                "👆 <i>(برای کپی کردن، کافیست روی کد بالا ضربه بزنید)</i>\n\n"
                f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}\"><b>کلیک کنید تا اپلیکیشن آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
                f"📊 <b>حجم ترافیک:</b> {traffic_gb} گیگابایت\n"
                f"⏳ <b>مدت اعتبار:</b> {days} روز\n\n"
                "📱 <b>راهنمای اتصال سریع:</b>\n"
                "۱. دکمه «اتصال و فعال‌سازی مستقیم در برنامه» زیر را لمس نمایید تا برنامه خودکار باز شود.\n"
                f"۲. یا می‌توانید نرم‌افزار <b>آسان وی‌پی‌ان</b> را باز کرده و کد اختصاصی <code>{pin}</code> را وارد فرمایید.\n"
                "۳. دکمه اتصال را لمس کنید تا به بهترین سرور متصل شوید!\n\n"
                "🔒 <b>امنیت و قفل دستگاه:</b> این اشتراک برای ۲ دستگاه (۲ گوشی همزمان) فعال بوده و بر روی دو دستگاه اول متصل‌شده قفل می‌گردد.\n\n"
                f"🧾 <b>کد رهگیری تراکنش هوش‌پی:</b> <code>{tracking_code}</code>\n"
                "🙏 از خرید و اعتماد شما سپاسگزاریم."
            )

        try:
            await context.bot.send_message(
                chat_id=telegram_id,
                text=success_text,
                parse_mode=ParseMode.HTML,
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton("🚀 اتصال و فعال‌سازی مستقیم در برنامه", url=f"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}")],
                    [InlineKeyboardButton("🟣 دانلود اپلیکیشن اندروید", url=app_download_url)],
                    [InlineKeyboardButton("🟤 پشتیبانی تلگرام", url=f"https://t.me/{support_username.lstrip('@')}")],
                    [InlineKeyboardButton("🔄 شروع مجدد / منوی اصلی 🚀", callback_data="back_to_home")]
                ])
            )
            # Restore bottom reply keyboard
            await context.bot.send_message(
                chat_id=telegram_id,
                text="👇 منوی دسترسی سریع ربات آسان وی‌پی‌ان (همواره در پایین صفحه در دسترس شماست):",
                reply_markup=get_main_keyboard(telegram_id)
            )
        except Exception as e:
            logger.error(f"Failed to send success msg to {telegram_id}: {e}")

        # Notify Admin (ONLY for ordinary customer purchases, NEVER for admin's own purchase or admin test)
        if not is_admin_test and not db.is_admin(telegram_id):
            admin_alert = (
                "🔔 <b>#خرید_جدید</b>\n\n"
                f"👤 <b>کاربر:</b> <code>{telegram_id}</code>\n"
                f"📦 <b>پلن:</b> {plan_title}\n"
                f"💰 <b>مبلغ:</b> {order['amount_toman']:,} تومان\n"
                f"🔑 <b>کد اختصاصی صادر شده:</b> <code>{pin}</code>\n"
                f"📊 <b>حجم اختصاص داده شده:</b> {traffic_gb} گیگابایت\n"
                f"🧾 <b>شناسه پیگیری:</b> <code>{tracking_code}</code>"
            )
            await notify_admins(context, admin_alert, exclude_id=telegram_id)
        return True

    return False

async def verify_order_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()

    uid = query.data.split(":")[1]
    order = await db.get_order_by_uid(uid)

    if not order:
        await query.answer("❌ سفارش یافت نشد.", show_alert=True)
        return

    if order.get("status") == "paid":
        pin = order.get("assigned_pin")
        await query.edit_message_text(
            f"✅ این فاکتور قبلاً پرداخت و تایید شده است!\n\n🔑 کد اشتراک: <code>{pin}</code>",
            parse_mode=ParseMode.HTML
        )
        return

    verify_res = await hooshpay.verify_invoice(uid)
    paid = verify_res.get("paid", False)

    if paid:
        tracking_code = (
            verify_res.get("data", {}).get("tracking_code")
            or verify_res.get("tracking_code")
            or "HP-" + uid[-6:]
        )
        await query.edit_message_text("⏳ پرداخت تایید شد! در حال صدور کد اشتراک...")
        success = await fulfill_paid_order(context, order, tracking_code)
        if not success:
            await query.edit_message_text(
                "⚠️ پرداخت شما تایید شده است اما در ثبت نهایی سیستم وقفه‌ای رخ داد.\n"
                f"کد رهگیری: <code>{tracking_code}</code>\n"
                "لطفاً شناسه پرداخت را برای پشتیبانی ارسال فرمایید تا اشتراک شما دستی فعال گردد.",
                parse_mode=ParseMode.HTML
            )
    else:
        await query.answer(
            "⏳ پرداخت هنوز انجام نشده یا توسط درگاه تایید نشده است. پس از واریز، مجدداً دکمه را لمس کنید.",
            show_alert=True
        )

# ----------------- ADMIN DASHBOARD & SETTINGS -----------------

async def show_admin_dashboard(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return

    ADMIN_STATES.pop(user_id, None)
    USER_STATES.pop(user_id, None)

    stats = await db.get_stats()
    text = (
        "⚙️ <b>پنل مدیریت هوشمند آسان وی‌پی‌ان (AsanVPN Admin)</b>\n\n"
        f"👥 <b>تعداد کاربران:</b> {stats['total_users']:,} نفر\n"
        f"📦 <b>کل سفارشات:</b> {stats['total_orders']:,} عدد\n"
        f"✅ <b>سفارشات موفق:</b> {stats['paid_orders']:,} عدد\n"
        f"💰 <b>مجموع فروش کل:</b> {stats['total_revenue']:,} تومان\n"
        f"📅 <b>درآمد امروز:</b> {stats['today_revenue']:,} تومان\n"
        f"⚡ <b>پلن‌های فعال:</b> {stats['active_plans']} عدد\n\n"
        "بخش مورد نظر را انتخاب فرمایید:"
    )

    keyboard = [
        [
            InlineKeyboardButton("📦 مدیریت پلن‌ها", callback_data="admin_plans"),
            InlineKeyboardButton("📊 گزارشات مالی کامل", callback_data="admin_stats_full")
        ],
        [
            InlineKeyboardButton("🌐 اولویت‌بندی و وضعیت پنل‌ها", callback_data="admin_panels_menu"),
            InlineKeyboardButton("🗑 حذف و ابطال اشتراک کاربر", callback_data="admin_remove_sub")
        ],
        [
            InlineKeyboardButton("👥 مدیریت مدیران ربات", callback_data="admin_manage_admins"),
            InlineKeyboardButton("📢 ارسال پیام همگانی", callback_data="admin_broadcast")
        ],
        [
            InlineKeyboardButton("💾 تنظیمات و مدیریت بکاپ", callback_data="admin_backup_menu"),
            InlineKeyboardButton("📝 تنظیمات متون", callback_data="admin_texts")
        ],
        [
            InlineKeyboardButton("🔔 تنظیمات اعلانات", callback_data="admin_notifications"),
            InlineKeyboardButton("🔙 بازگشت به منوی کاربری", callback_data="admin_exit")
        ]
    ]

    markup = InlineKeyboardMarkup(keyboard)
    if update.message:
        await update.message.reply_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)
    elif update.callback_query:
        await update.callback_query.edit_message_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)

# ----------------- ADMIN PANEL PRIORITY & HEALTH -----------------

async def admin_panels_menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return

    query = update.callback_query
    if query:
        await query.answer()

    panels = await worker.get_panels()
    if not panels:
        text = "❌ <b>ارتباط با ورکر برقرار نشد یا هیچ پنلی تعریف نشده است.</b>"
        kb = InlineKeyboardMarkup([[InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")]])
        if query:
            await query.edit_message_text(text, reply_markup=kb, parse_mode=ParseMode.HTML)
        else:
            await update.effective_message.reply_text(text, reply_markup=kb, parse_mode=ParseMode.HTML)
        return

    # Sort ascending by priority
    panels.sort(key=lambda p: (p.get("priority") or 99))

    text = (
        "🌐 <b>مدیریت، سلامت و اولویت‌بندی پنل‌های پروکسی</b> 🛡️⚡\n\n"
        "<blockquote>💡 <b>سیستم آبشاری خودکار (Priority Waterfall):</b>\n"
        "اپلیکیشن کاربر ابتدا کانفیگ‌های پنل با اولویت ۱ را با تاخیر واقعی (Real Delay) تست می‌کند و در صورت پاسخ‌گویی متصل می‌شود. "
        "فقط در صورت قطعی کامل پنل اول، به ترتیب سراغ پنل‌های بعدی می‌رود.</blockquote>\n\n"
        "📋 <b>لیست پنل‌ها به ترتیب اولویت اتصال:</b>\n\n"
    )

    keyboard = []
    priority_emojis = {1: "🥇", 2: "🥈", 3: "🥉"}

    for p in panels:
        p_id = p.get("id", "")
        p_name = p.get("name", "نامشخص")
        p_prio = p.get("priority", 99)
        p_type = p.get("type", "نامشخص")
        p_enabled = p.get("enabled", True)

        badge = priority_emojis.get(p_prio, f"#{p_prio}")
        status_str = "🟢 فعال" if p_enabled else "🔴 غیرفعال"

        text += (
            f"━━━━━━━━━━━━━━━━━━━\n"
            f"{badge} <b>{p_name}</b> ({p_type})\n"
            f"🔹 <b>اولویت اتصال:</b> {p_prio}\n"
            f"🔹 <b>وضعیت:</b> {status_str}\n"
            f"🔹 <b>آدرس سرور:</b> <code>{p.get('url', '')}</code>\n"
        )

        row = []
        if p_prio > 1:
            row.append(InlineKeyboardButton(f"⬆️ ارتقا ({p_name})", callback_data=f"prio_up:{p_id}"))
        row.append(InlineKeyboardButton(f"⬇️ تنزل ({p_name})", callback_data=f"prio_down:{p_id}"))
        row.append(InlineKeyboardButton(f"⚡ تست پینگ ({p_name})", callback_data=f"test_pnl:{p_id}"))
        keyboard.append(row)

    keyboard.append([
        InlineKeyboardButton("🔄 بروزرسانی و استعلام مجدد", callback_data="admin_panels_menu"),
        InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")
    ])

    markup = InlineKeyboardMarkup(keyboard)
    if query:
        await query.edit_message_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)
    else:
        await update.effective_message.reply_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)

async def admin_change_panel_priority(update: Update, context: ContextTypes.DEFAULT_TYPE, panel_id: str, direction: str):
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return

    query = update.callback_query
    panels = await worker.get_panels()
    target = next((p for p in panels if p.get("id") == panel_id), None)
    if not target:
        if query:
            await query.answer("پنل مورد نظر یافت نشد!", show_alert=True)
        return

    curr_prio = target.get("priority", 99)
    new_prio = max(1, curr_prio - 1) if direction == "up" else curr_prio + 1

    if new_prio == curr_prio:
        if query:
            await query.answer("اولویت تغییری نکرد.", show_alert=False)
        return

    # Check if another panel holds new_prio, swap them
    other = next((p for p in panels if p.get("priority") == new_prio and p.get("id") != panel_id), None)
    if other:
        other["priority"] = curr_prio
        await worker.save_panel(other)

    target["priority"] = new_prio
    await worker.save_panel(target)

    if query:
        await query.answer(f"✅ اولویت {target.get('name')} به {new_prio} تنظیم شد.", show_alert=False)
    await admin_panels_menu(update, context)

async def admin_test_panel_handler(update: Update, context: ContextTypes.DEFAULT_TYPE, panel_id: str):
    query = update.callback_query
    if query:
        await query.answer("⏳ در حال سنجش پینگ و تاخیر پنل...", show_alert=False)

    panels = await worker.get_panels()
    target = next((p for p in panels if p.get("id") == panel_id), None)
    if not target:
        if query:
            await query.answer("پنل یافت نشد!", show_alert=True)
        return

    res = await worker.test_panel(target)
    if res.get("ok"):
        lat = res.get("latency_ms", "نامشخص")
        code = res.get("status", 200)
        msg = f"✅ اتصال به «{target.get('name')}» با موفقیت برقرار شد!\n⚡ زمان پاسخ (Ping): {lat} میلی‌ثانیه\nکد وضعیت: {code}"
    else:
        err = res.get("error", "عدم برقراری ارتباط")
        msg = f"❌ خطای اتصال به «{target.get('name')}»:\n{err}"

    if query:
        await query.answer(msg, show_alert=True)

# Admin: Remove / Revoke Subscription Flow
async def admin_remove_sub_menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return

    ADMIN_STATES[user_id] = {"action": "await_sub_to_remove"}

    text = (
        "🗑 <b>حذف و ابطال اشتراک کاربر (Revoke Subscription)</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "برای جستجو و لغو اشتراک، لطفاً یکی از موارد زیر را ارسال فرمایید:\n\n"
        "1️⃣ <b>کد ۸ رقمی اشتراک (PIN):</b> مانند <code>88605949</code>\n"
        "2️⃣ <b>شناسه عددی کاربر (Telegram ID):</b> مانند <code>93795023</code>\n"
        "3️⃣ <b>نام کاربری تلگرام:</b> مانند <code>@username</code>\n\n"
        "⚡️ <i>می‌توانید مستقیماً از دستور سریع زیر نیز استفاده فرمایید:</i>\n"
        "<code>/delpin 88605949</code>\n\n"
        "⛔️ <b>توجه مهم:</b> با حذف اشتراک، دسترسی کاربر بلافاصله در ورکر کلودفلر، سرورها و تمامی پنل‌های متصل مسدود شده و کد از دیتابیس پاک خواهد شد.\n\n"
        "جهت انصراف عبارت <code>/cancel</code> را بفرستید."
    )

    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")]
    ])

    if update.callback_query:
        await update.callback_query.edit_message_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
    elif update.message:
        await update.message.reply_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)

async def show_admin_del_pin_card(update: Update, context: ContextTypes.DEFAULT_TYPE, pin: str, target_tid: int = None):
    clean_pin = str(pin).strip()
    is_query = hasattr(update, "callback_query") and update.callback_query is not None
    query = update.callback_query if is_query else None

    loading_text = f"⏳ در حال بررسی مشخصات اشتراک <code>{clean_pin}</code> از ورکر و پنل‌ها..."
    if query:
        msg = await query.edit_message_text(loading_text, parse_mode=ParseMode.HTML)
    else:
        msg = await update.message.reply_text(loading_text, parse_mode=ParseMode.HTML)

    try:
        user_record = await worker.get_user_by_pin(clean_pin)

        linked_tids = []
        if target_tid:
            linked_tids.append(target_tid)

        db_tids = await db.get_linked_telegram_ids_by_pin(clean_pin)
        for tid in db_tids:
            if tid not in linked_tids:
                linked_tids.append(tid)

        linked_tids_str = ", ".join([f"<code>{tid}</code>" for tid in linked_tids]) if linked_tids else "ثبت نشده"

        if not user_record:
            text = (
                f"⚠️ <b>اشتراک در ورکر کلودفلر یافت نشد!</b>\n\n"
                f"کد <code>{clean_pin}</code> در سرورهای ابری کلودفلر وجود ندارد یا قبلاً حذف شده است.\n"
                f"👤 شناسه‌های مرتبط در دیتابیس ربات: {linked_tids_str}\n\n"
                "آیا مایلید این کد از دیتابیس ربات و سوابق پاکسازی شود؟"
            )
            keyboard = InlineKeyboardMarkup([
                [InlineKeyboardButton("🗑 پاکسازی کامل از دیتابیس ربات", callback_data=f"admin_del_confirm:{clean_pin}")],
                [InlineKeyboardButton("🔙 انصراف و بازگشت", callback_data="admin_remove_sub")],
                [InlineKeyboardButton("⚙️ پنل مدیریت", callback_data="admin_dashboard")]
            ])
            await msg.edit_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
            return

        name = user_record.get("name") or "کاربر آسان وی‌پی‌ان"
        now = int(time.time())
        expires_at = user_record.get("expires_at")
        if expires_at and expires_at > 0:
            days_left = max(0, (expires_at - now) // 86400)
            import datetime
            dt = datetime.datetime.fromtimestamp(expires_at, datetime.timezone.utc)
            exp_date_str = dt.strftime("%Y-%m-%d")
            exp_str = f"<b>{days_left} روز باقی‌مانده</b> ({exp_date_str})"
        else:
            exp_str = "<b>نامحدود / بدون انقضا</b>"

        traffic_total = user_record.get("traffic_total_bytes", 0)
        traffic_rem = user_record.get("traffic_remaining_bytes", -1)
        if traffic_rem == -1 or traffic_total == 0:
            traffic_str = "<b>نامحدود</b>"
        else:
            rem_gb = round(traffic_rem / (1024**3), 2)
            tot_gb = round(traffic_total / (1024**3), 2)
            used_gb = round(max(0, traffic_total - traffic_rem) / (1024**3), 2)
            traffic_str = f"<b>{used_gb} GB مصرفی</b> از {tot_gb} GB ({rem_gb} GB مانده)"

        devices = user_record.get("devices", [])
        max_dev = user_record.get("max_devices", 2)
        dev_str = f"<b>{len(devices)} از {max_dev} دستگاه فعال</b>"

        panel_mappings = user_record.get("remote_panel_mappings", {})
        if isinstance(panel_mappings, dict) and panel_mappings:
            panels_str = f"متصل به {len(panel_mappings)} پنل سرور ({', '.join(panel_mappings.keys())})"
        else:
            panels_str = "بدون سرور متصل"

        active = user_record.get("active", True)
        status_icon = "🟢 فعال" if active else "🔴 غیرفعال / منقضی"

        text = (
            "⚠️ <b>بررسی و تأییدیه حذف اشتراک:</b>\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
            f"🔑 <b>کد اختصاصی اشتراک (PIN):</b> <code>{clean_pin}</code>\n"
            f"👤 <b>نام کاربر / برچسب:</b> {name}\n"
            f"🆔 <b>آیدی تلگرام مرتبط:</b> {linked_tids_str}\n"
            f"⚡️ <b>وضعیت اتصال:</b> {status_icon}\n"
            f"📊 <b>حجم ترافیک:</b> {traffic_str}\n"
            f"⏳ <b>اعتبار زمانی:</b> {exp_str}\n"
            f"📱 <b>دستگاه‌های همزمان:</b> {dev_str}\n"
            f"🌐 <b>پنل‌های شبکه:</b> {panels_str}\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
            "⛔️ <b>هشدار مهم:</b> با تأیید، این اشتراک <b>بلافاصله و به طور دائمی</b> از ورکر کلودفلر، سرورها و پنل‌های بالادستی حذف شده، اتصال کاربر قطع و کد از دیتابیس ربات پاک خواهد شد!\n\n"
            "آیا از حذف کامل این اشتراک اطمینان دارید؟"
        )

        keyboard = InlineKeyboardMarkup([
            [InlineKeyboardButton("🗑 بله، اشتراک حذف و مسدود شود", callback_data=f"admin_del_confirm:{clean_pin}")],
            [InlineKeyboardButton("🔙 انصراف و بازگشت", callback_data="admin_remove_sub")],
            [InlineKeyboardButton("⚙️ پنل مدیریت", callback_data="admin_dashboard")]
        ])

        await msg.edit_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
    except Exception as e:
        logger.exception(f"Error inspecting PIN {clean_pin}: {e}")
        err_text = f"❌ بروز خطا در بررسی اشتراک {clean_pin}:\n<code>{e}</code>"
        err_kb = InlineKeyboardMarkup([
            [InlineKeyboardButton("🔄 تلاش مجدد", callback_data=f"admin_del_pin_view:{clean_pin}")],
            [InlineKeyboardButton("⚙️ پنل مدیریت", callback_data="admin_dashboard")]
        ])
        await msg.edit_text(err_text, reply_markup=err_kb, parse_mode=ParseMode.HTML)

async def process_admin_sub_lookup(update: Update, context: ContextTypes.DEFAULT_TYPE, query_str: str):
    user_id = update.effective_user.id
    raw = query_str.strip()

    if raw.lower() in ("/cancel", "cancel", "لغو"):
        ADMIN_STATES.pop(user_id, None)
        await update.message.reply_text("عملیات حذف اشتراک لغو شد.", reply_markup=get_main_keyboard(user_id))
        return

    # Check username
    if raw.startswith("@") or (not raw.isdigit() and len(raw) >= 3 and not re.fullmatch(r'\d{8}|\d{10}', raw)):
        uname = raw.lstrip("@")
        u_info = await db.get_user_by_username(uname)
        if u_info:
            target_tid = u_info["telegram_id"]
            raw = str(target_tid)
        else:
            await update.message.reply_text(
                f"❌ کاربری با نام کاربری <code>@{uname}</code> در سیستم یافت نشد.\nلطفاً کد ۱۰ رقمی (یا ۸ رقمی) PIN یا آیدی عددی تلگرام را وارد کنید:",
                parse_mode=ParseMode.HTML
            )
            return

    # Check 10-digit or 8-digit PIN
    if re.fullmatch(r'\d{8}|\d{10}', raw):
        ADMIN_STATES.pop(user_id, None)
        await show_admin_del_pin_card(update, context, pin=raw)
        return

    # Check numeric Telegram ID
    if raw.isdigit():
        target_tid = int(raw)
        pins = await db.get_user_all_pins(target_tid)
        cf_users = await worker.find_users_by_telegram_id(target_tid)
        for u in cf_users:
            p = str(u.get("pin", "")).strip()
            if p and p not in pins:
                pins.append(p)

        if not pins:
            keyboard = InlineKeyboardMarkup([
                [InlineKeyboardButton("🔄 جستجوی مجدد", callback_data="admin_remove_sub")],
                [InlineKeyboardButton("⚙️ پنل مدیریت", callback_data="admin_dashboard")]
            ])
            await update.message.reply_text(
                f"❌ هیچ اشتراک فعالی برای شناسه تلگرام <code>{target_tid}</code> یافت نشد.\n\n"
                "می‌توانید با وارد کردن مستقیم کد ۸ رقمی (PIN) آن را پیدا کنید.",
                reply_markup=keyboard,
                parse_mode=ParseMode.HTML
            )
            return

        ADMIN_STATES.pop(user_id, None)
        if len(pins) == 1:
            await show_admin_del_pin_card(update, context, pin=pins[0], target_tid=target_tid)
            return

        text = (
            f"📋 <b>اشتراک‌های یافت‌شده برای کاربر <code>{target_tid}</code>:</b>\n\n"
            f"تعداد {len(pins)} اشتراک برای این کاربر پیدا شد. لطفاً اشتراکی که می‌خواهید حذف کنید را انتخاب نمایید:"
        )
        buttons = []
        for p in pins:
            buttons.append([InlineKeyboardButton(f"🗑 حذف کد {p}", callback_data=f"admin_del_pin_view:{p}")])
        buttons.append([InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")])

        await update.message.reply_text(text, reply_markup=InlineKeyboardMarkup(buttons), parse_mode=ParseMode.HTML)
        return

    await update.message.reply_text(
        "⚠️ لطفاً یک <b>کد ۸ رقمی (PIN)</b> یا <b>آیدی عددی تلگرام</b> معتبر وارد کنید:\n(برای انصراف <code>/cancel</code>)",
        parse_mode=ParseMode.HTML
    )

async def admin_delpin_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return

    if context.args and len(context.args) > 0:
        query_str = context.args[0].strip()
        await process_admin_sub_lookup(update, context, query_str)
    else:
        await admin_remove_sub_menu(update, context)

# ----------------- ADMINS MANAGEMENT (DYNAMIC ADD / REMOVE) -----------------

async def admin_manage_admins(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return

    ADMIN_STATES.pop(user_id, None)
    admins = await db.get_all_admins()

    text = (
        "👥 <b>مدیریت مدیران ربات آسان وی‌پی‌ان (Admins Management)</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "در این بخش می‌توانید لیست مدیران دارای دسترسی به پنل مدیریت را مشاهده، مدیر جدید اضافه کرده و یا دسترسی مدیران را لغو نمایید.\n\n"
        f"👑 <b>لیست مدیران فعال ({len(admins)} نفر):</b>\n\n"
    )

    for idx, a in enumerate(admins, 1):
        tid = a["telegram_id"]
        fname = a.get("first_name") or "مدیر"
        uname = f"@{a['username']}" if a.get("username") else "بدون یوزرنیم"
        if a.get("is_owner") or tid == 93795023:
            role_badge = "👑 <b>مالک اصلی (Super Admin)</b>"
        else:
            role_badge = "🛡️ <b>مدیر سیستم (Admin)</b>"
        text += f"{idx}. <b>{fname}</b> ({uname} | <code>{tid}</code>)\n   🏷 سمت: {role_badge}\n\n"

    text += (
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        "💡 <i>نکته امنیتی: مالک اصلی ربات غیرقابل حذف بوده و همواره دسترسی کامل دارد.</i>"
    )

    keyboard = [
        [
            InlineKeyboardButton("➕ افزودن مدیر جدید", callback_data="admin_add_admin_prompt"),
            InlineKeyboardButton("➖ حذف مدیر", callback_data="admin_remove_admin_list")
        ],
        [
            InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")
        ]
    ]

    markup = InlineKeyboardMarkup(keyboard)
    if update.callback_query:
        await update.callback_query.edit_message_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)
    elif update.message:
        await update.message.reply_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)


async def admin_add_admin_prompt(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return

    ADMIN_STATES[user_id] = {"action": "await_new_admin"}
    text = (
        "➕ <b>افزودن مدیر جدید به ربات:</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "جهت اعطای دسترسی مدیریت، یکی از روش‌های زیر را انجام دهید:\n\n"
        "1️⃣ <b>ارسال شناسه عددی تلگرام (Telegram ID):</b>\n"
        "مثال: <code>7293867371</code>\n\n"
        "2️⃣ <b>ارسال آیدی یا نام کاربری تلگرام:</b>\n"
        "مثال: <code>@username</code>\n\n"
        "3️⃣ <b>فوروارد پیام از کاربر:</b>\n"
        "یک پیام از کاربر مورد نظر را به این چت فوروارد (Forward) کنید.\n\n"
        "❌ جهت انصراف، دستور <code>/cancel</code> را ارسال فرمایید."
    )
    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("🔙 انصراف و بازگشت", callback_data="admin_manage_admins")]
    ])
    if update.callback_query:
        await update.callback_query.edit_message_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
    elif update.message:
        await update.message.reply_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)


async def admin_remove_admin_list(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return

    ADMIN_STATES.pop(user_id, None)
    admins = await db.get_all_admins()
    removable = [a for a in admins if not a.get("is_owner") and a["telegram_id"] != 93795023]

    if not removable:
        text = (
            "ℹ️ <b>حذف مدیر:</b>\n\n"
            "در حال حاضر هیچ مدیر فرعی دیگری جهت حذف وجود ندارد.\n"
            "<i>(مالک اصلی ربات تحت هیچ شرایطی قابل حذف نیست.)</i>"
        )
        kb = InlineKeyboardMarkup([
            [InlineKeyboardButton("🔙 بازگشت به مدیریت مدیران", callback_data="admin_manage_admins")]
        ])
        if update.callback_query:
            await update.callback_query.edit_message_text(text, reply_markup=kb, parse_mode=ParseMode.HTML)
        elif update.message:
            await update.message.reply_text(text, reply_markup=kb, parse_mode=ParseMode.HTML)
        return

    text = (
        "➖ <b>حذف دسترسی مدیر:</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "لطفاً مدیری را که مایل به لغو دسترسی ایشان هستید انتخاب نمایید:"
    )
    keyboard = []
    for a in removable:
        tid = a["telegram_id"]
        fname = a.get("first_name") or "مدیر"
        uname = f"@{a['username']}" if a.get("username") else str(tid)
        btn_text = f"❌ حذف: {fname} ({uname})"
        keyboard.append([InlineKeyboardButton(btn_text, callback_data=f"admin_del_admin_ask:{tid}")])

    keyboard.append([InlineKeyboardButton("🔙 انصراف و بازگشت", callback_data="admin_manage_admins")])

    markup = InlineKeyboardMarkup(keyboard)
    if update.callback_query:
        await update.callback_query.edit_message_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)
    elif update.message:
        await update.message.reply_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)


async def admin_del_admin_ask(update: Update, context: ContextTypes.DEFAULT_TYPE, target_id: int):
    query = update.callback_query
    await query.answer()

    if target_id == 93795023:
        await query.answer("مالک اصلی ربات غیرقابل حذف است!", show_alert=True)
        return

    admins = await db.get_all_admins()
    target_info = next((a for a in admins if a["telegram_id"] == target_id), None)
    target_name = target_info.get("first_name", "مدیر") if target_info else str(target_id)
    target_user = f"@{target_info['username']}" if target_info and target_info.get("username") else str(target_id)

    text = (
        f"⚠️ <b>تأییدیه لغو دسترسی مدیریت: {target_name} ({target_user})</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        f"آیا از لغو دسترسی مدیریت کاربر <code>{target_id}</code> اطمینان دارید؟\n\n"
        "🚫 <b>پیامدها:</b>\n"
        "• دکمه «⚙️ پنل مدیریت هوشمند ربات» فوراً از منوی ایشان حذف می‌شود.\n"
        "• کلیه دستورات و دسترسی‌های مدیریتی برای ایشان مسدود می‌گردد.\n"
        "• منوی کاربری ایشان به حالت کاربر عادی بازنشانی می‌شود."
    )

    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("🗑️ بله، دسترسی مدیریت حذف شود", callback_data=f"admin_del_admin_confirm:{target_id}")],
        [InlineKeyboardButton("❌ انصراف", callback_data="admin_manage_admins")]
    ])

    await query.edit_message_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)


async def admin_del_admin_confirm(update: Update, context: ContextTypes.DEFAULT_TYPE, target_id: int):
    query = update.callback_query
    user_id = query.from_user.id
    await query.answer()

    if target_id == 93795023:
        await query.answer("مالک اصلی ربات تحت هیچ شرایطی قابل حذف نیست!", show_alert=True)
        return

    admins = await db.get_all_admins()
    target_info = next((a for a in admins if a["telegram_id"] == target_id), None)
    target_name = target_info.get("first_name", "کاربر") if target_info else str(target_id)

    # 1. Remove from database and cache
    await db.remove_admin(target_id)

    # 2. Reset bot commands for the demoted user
    try:
        await context.bot.delete_my_commands(scope=BotCommandScopeChat(chat_id=target_id))
    except Exception as e:
        logger.warning(f"Failed to reset commands for {target_id}: {e}")

    # 3. Push plain user keyboard to the demoted user to remove the admin button immediately
    try:
        await context.bot.send_message(
            chat_id=target_id,
            text="ℹ️ دسترسی مدیریت شما در ربات آسان وی‌پی‌ان غیرفعال گردید.",
            reply_markup=get_main_keyboard(target_id)
        )
    except Exception as e:
        logger.info(f"Could not reach user {target_id} to refresh keyboard: {e}")

    # 4. Notify all remaining admins
    actor_name = query.from_user.first_name or str(user_id)
    alert_msg = (
        "🔔 <b>#لغو_دسترسی_مدیر</b>\n\n"
        f"👤 <b>اقدام‌کننده:</b> {actor_name} (<code>{user_id}</code>)\n"
        f"➖ <b>مدیر برکنارشده:</b> {target_name} (<code>{target_id}</code>)"
    )
    await notify_admins(context, alert_msg)

    # 5. Success screen for executor
    success_text = (
        f"✅ <b>دسترسی مدیریت کاربر {target_name} (<code>{target_id}</code>) با موفقیت لغو شد.</b>\n\n"
        "دکمه پنل مدیریت از حساب ایشان برداشته شد و منوی عادی فعال گردید."
    )
    kb = InlineKeyboardMarkup([
        [InlineKeyboardButton("👥 بازگشت به لیست مدیران", callback_data="admin_manage_admins")],
        [InlineKeyboardButton("⚙️ پنل مدیریت", callback_data="admin_dashboard")]
    ])
    await query.edit_message_text(success_text, reply_markup=kb, parse_mode=ParseMode.HTML)

# Admin: Plans Management
async def admin_plans_menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()

    plans = await db.get_plans(active_only=False)
    text = (
        "📦 <b>مدیریت پلن‌های فروش (Plans Management)</b>\n\n"
        "برای ویرایش یا فعال/غیرفعال کردن، روی هر پلن کلیک کنید:"
    )

    keyboard = []
    for p in plans:
        status_icon = "🟢" if p["is_active"] == 1 else "🔴"
        traffic_str = "نامحدود" if p["traffic_gb"] == 0 else f"{p['traffic_gb']}GB"
        btn_text = f"{status_icon} {p['title']} | {traffic_str} | {p['price_toman']:,} ت"
        keyboard.append([InlineKeyboardButton(btn_text, callback_data=f"admin_plan_view:{p['id']}")])

    keyboard.append([InlineKeyboardButton("➕ ایجاد پلن جدید", callback_data="admin_plan_create")])
    keyboard.append([InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")])

    await query.edit_message_text(text, reply_markup=InlineKeyboardMarkup(keyboard), parse_mode=ParseMode.HTML)

async def admin_plan_view(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()

    plan_id = query.data.split(":")[1]
    plan = await db.get_plan_by_id(plan_id)
    if not plan:
        await query.answer("پلن یافت نشد.", show_alert=True)
        return

    status_str = "🟢 فعال (قابل خرید توسط کاربران)" if plan["is_active"] == 1 else "🔴 غیرفعال (پنهان از فروشگاه)"
    text = (
        f"📦 <b>جزئیات پلن:</b> {plan['title']}\n\n"
        f"🔑 <b>شناسه یکتا:</b> <code>{plan['id']}</code>\n"
        f"📊 <b>حجم ترافیک:</b> {plan['traffic_gb']} گیگابایت\n"
        f"⏳ <b>مدت اعتبار:</b> {plan['days']} روز\n"
        f"📱 <b>تعداد دستگاه مجاز:</b> {plan['max_devices']} گوشی (۲ کاربر / دستگاه همزمان)\n"
        f"💰 <b>قیمت:</b> {plan['price_toman']:,} تومان\n"
        f"⚡ <b>وضعیت:</b> {status_str}\n"
    )

    toggle_btn_text = "🔴 غیرفعال‌سازی" if plan["is_active"] == 1 else "🟢 فعال‌سازی"
    keyboard = [
        [
            InlineKeyboardButton("💰 تغییر قیمت", callback_data=f"admin_plan_edit_price:{plan_id}"),
            InlineKeyboardButton(toggle_btn_text, callback_data=f"admin_plan_toggle:{plan_id}")
        ],
        [
            InlineKeyboardButton("🗑️ حذف پلن", callback_data=f"admin_plan_del_ask:{plan_id}"),
            InlineKeyboardButton("🔙 بازگشت به لیست", callback_data="admin_plans")
        ]
    ]

    await query.edit_message_text(text, reply_markup=InlineKeyboardMarkup(keyboard), parse_mode=ParseMode.HTML)

# Admin: Text Settings
async def admin_texts_menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()

    welcome = await db.get_setting("welcome_message")
    supp = await db.get_setting("support_username", "@Asanvpnsupport_bot")
    app_url = await db.get_setting("app_download_url", "https://github.com/armanpier/AsanVPN/releases")

    text = (
        "📝 <b>تنظیمات پیام‌ها و متون ربات:</b>\n\n"
        f"📞 <b>آیدی پشتیبانی:</b> {supp}\n"
        f"📲 <b>لینک دانلود اپلیکیشن:</b>\n{app_url}\n\n"
        f"👋 <b>متن خوش‌آمدگویی فعلی:</b>\n<i>{welcome[:150]}...</i>\n\n"
        "گزینه مورد نظر برای ویرایش را انتخاب فرمایید:"
    )

    keyboard = [
        [InlineKeyboardButton("✏️ ویرایش متن خوش‌آمدگویی", callback_data="admin_edit_welcome")],
        [InlineKeyboardButton("✏️ ویرایش آیدی پشتیبانی", callback_data="admin_edit_support")],
        [InlineKeyboardButton("✏️ ویرایش لینک دانلود برنامه", callback_data="admin_edit_applink")],
        [InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")]
    ]

    await query.edit_message_text(text, reply_markup=InlineKeyboardMarkup(keyboard), parse_mode=ParseMode.HTML)

# Admin: Notifications & Switches
async def admin_notifications_menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()

    ns = await db.get_setting("notify_admin_on_sale", "1")
    nr = await db.get_setting("notify_admin_on_renew", "1")
    mm = await db.get_setting("maintenance_mode", "0")
    abp = await db.get_setting("admin_bypass_payment", "1")

    icon_ns = "✅ روشن" if ns == "1" else "❌ خاموش"
    icon_nr = "✅ روشن" if nr == "1" else "❌ خاموش"
    icon_mm = "🔴 فعال (خرید بسته)" if mm == "1" else "🟢 غیرفعال (عادی)"
    icon_abp = "👑 فعال (خرید فوری بدون درگاه)" if abp == "1" else "💳 غیرفعال (نیاز به پرداخت)"

    text = (
        "🔔 <b>تنظیمات اعلانات و حالت‌های ربات:</b>\n\n"
        "با لمس هر گزینه، وضعیت آن فوراً تغییر خواهد کرد:"
    )

    keyboard = [
        [InlineKeyboardButton(f"تست خرید ادمین بدون پرداخت: {icon_abp}", callback_data="toggle_admin_bypass")],
        [InlineKeyboardButton(f"اعلان خرید جدید به ادمین: {icon_ns}", callback_data="toggle_notif_sale")],
        [InlineKeyboardButton(f"اعلان تمدید به ادمین: {icon_nr}", callback_data="toggle_notif_renew")],
        [InlineKeyboardButton(f"حالت تعمیرات: {icon_mm}", callback_data="toggle_maint_mode")],
        [InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")]
    ]

    await query.edit_message_text(text, reply_markup=InlineKeyboardMarkup(keyboard), parse_mode=ParseMode.HTML)

# Admin: Backup Settings & Management
async def admin_backup_menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if query:
        await query.answer()

    channel_id = await db.get_setting("backup_channel_id", "-1004389472709")
    auto_backup = await db.get_setting("auto_backup_enabled", "1")
    interval_hours = await db.get_setting("auto_backup_interval_hours", "24")
    last_backup_ts = int(await db.get_setting("last_backup_time", "0"))

    if last_backup_ts > 0:
        import datetime
        dt = datetime.datetime.fromtimestamp(last_backup_ts, datetime.timezone.utc)
        last_str = dt.strftime("%Y-%m-%d %H:%M:%S UTC")
    else:
        last_str = "ثبت نشده (یا به‌تازگی راه‌اندازی شده)"

    auto_status_icon = "🟢 فعال" if auto_backup == "1" else "🔴 غیرفعال"

    text = (
        "💾 <b>مدیریت و تنظیمات نسخه پشتیبان (Backup Settings)</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        f"📢 <b>کانال تلگرام مقصد بکاپ:</b>\n"
        f"👉 <code>{channel_id}</code>\n\n"
        f"🔄 <b>وضعیت بکاپ‌گیری خودکار:</b> {auto_status_icon}\n"
        f"⏱️ <b>دوره زمانی بکاپ خودکار:</b> هر <b>{interval_hours}</b> ساعت یک‌بار\n"
        f"📅 <b>آخرین ارسال موفق:</b> {last_str}\n\n"
        "📦 <b>محتوای بسته پشتیبان:</b>\n"
        "• فایل دیتابیس کامل ربات (SQLite)\n"
        "• فایل JSON کامل کاربران و پنل‌های کلودفلر (Cloudflare Worker)\n\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        "برای تغییر هر یک از موارد یا ارسال فوری، از دکمه‌های زیر استفاده نمایید:"
    )

    keyboard = [
        [
            InlineKeyboardButton(f"🔄 بکاپ خودکار: {auto_status_icon}", callback_data="toggle_auto_backup"),
            InlineKeyboardButton(f"⏱️ دوره: هر {interval_hours} ساعت", callback_data="admin_backup_interval_menu")
        ],
        [
            InlineKeyboardButton("✏️ تغییر کانال مقصد بکاپ", callback_data="admin_edit_backup_channel")
        ],
        [
            InlineKeyboardButton("🚀 ارسال فوری بکاپ به کانال", callback_data="admin_instant_backup_now"),
            InlineKeyboardButton("📥 دریافت فایل‌ها در همین چت", callback_data="admin_send_backup_direct")
        ],
        [
            InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")
        ]
    ]

    markup = InlineKeyboardMarkup(keyboard)
    if query:
        await query.edit_message_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)
    elif update.message:
        await update.message.reply_text(text, reply_markup=markup, parse_mode=ParseMode.HTML)

async def admin_backup_interval_menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if query:
        await query.answer()

    current_interval = await db.get_setting("auto_backup_interval_hours", "24")
    text = (
        "⏱️ <b>تنظیم بازه زمانی بکاپ‌گیری خودکار:</b>\n\n"
        f"دوره فعلی: <b>هر {current_interval} ساعت یک‌بار</b>\n\n"
        "لطفاً بازه زمانی مورد نظر خود را انتخاب نمایید:"
    )
    keyboard = [
        [
            InlineKeyboardButton("⏱️ هر ۶ ساعت (۴ بار در روز)", callback_data="set_backup_interval:6"),
            InlineKeyboardButton("⏱️ هر ۱۲ ساعت (۲ بار در روز)", callback_data="set_backup_interval:12")
        ],
        [
            InlineKeyboardButton("⏱️ هر ۲۴ ساعت (روزانه)", callback_data="set_backup_interval:24"),
            InlineKeyboardButton("⏱️ هر ۴۸ ساعت (یک روز درمیان)", callback_data="set_backup_interval:48")
        ],
        [
            InlineKeyboardButton("🔙 بازگشت به تنظیمات بکاپ", callback_data="admin_backup_menu")
        ]
    ]
    await query.edit_message_text(text, reply_markup=InlineKeyboardMarkup(keyboard), parse_mode=ParseMode.HTML)

# ----------------- ADMIN CALLBACK ROUTER -----------------

async def admin_callback_router(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    user_id = query.from_user.id
    data = query.data

    if not is_admin(user_id):
        await query.answer("دسترسی غیرمجاز است.", show_alert=True)
        return

    if data == "admin_dashboard":
        await show_admin_dashboard(update, context)
        return

    if data == "admin_panels_menu":
        await admin_panels_menu(update, context)
        return

    if data.startswith("prio_up:"):
        panel_id = data.split(":")[1]
        await admin_change_panel_priority(update, context, panel_id, "up")
        return

    if data.startswith("prio_down:"):
        panel_id = data.split(":")[1]
        await admin_change_panel_priority(update, context, panel_id, "down")
        return

    if data.startswith("test_pnl:"):
        panel_id = data.split(":")[1]
        await admin_test_panel_handler(update, context, panel_id)
        return

    # Admins Management callbacks
    if data == "admin_manage_admins":
        await admin_manage_admins(update, context)
        return

    if data == "admin_add_admin_prompt":
        await admin_add_admin_prompt(update, context)
        return

    if data == "admin_remove_admin_list":
        await admin_remove_admin_list(update, context)
        return

    if data.startswith("admin_del_admin_ask:"):
        target_id = int(data.split(":")[1])
        await admin_del_admin_ask(update, context, target_id)
        return

    if data.startswith("admin_del_admin_confirm:"):
        target_id = int(data.split(":")[1])
        await admin_del_admin_confirm(update, context, target_id)
        return

    if data == "admin_remove_sub":
        await admin_remove_sub_menu(update, context)
        return

    if data.startswith("admin_del_pin_view:"):
        pin = data.split(":")[1]
        await show_admin_del_pin_card(update, context, pin)
        return

    if data.startswith("admin_del_confirm:"):
        pin = data.split(":")[1]
        ADMIN_STATES.pop(user_id, None)
        await query.edit_message_text(
            f"⏳ در حال ابطال و حذف اشتراک <code>{pin}</code> از ورکر کلودفلر، سرورها و دیتابیس...",
            parse_mode=ParseMode.HTML
        )

        worker_res = await worker.delete_user(pin)
        db_res = await db.remove_subscription_by_pin(pin)

        linked_users = db_res.get("linked_users", [])
        linked_str = ", ".join([f"<code>{u}</code>" for u in linked_users]) if linked_users else "ثبت نشده"

        success_text = (
            "✅ <b>اشتراک با موفقیت ابطال و حذف گردید!</b>\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
            f"🔑 <b>کد اشتراک:</b> <code>{pin}</code>\n"
            f"👤 <b>کاربران تلگرام مرتبط:</b> {linked_str}\n"
            "🗑 <b>ورکر کلودفلر:</b> کاربر و دسترسی حذف گردید\n"
            "🌐 <b>پنل‌ها و سرورها:</b> اکانت مسدود و از سرورها پاک شد\n"
            "🗄 <b>دیتابیس ربات:</b> وضعیت سفارش به باطل‌شده (revoked) تغییر یافت\n\n"
            "💡 این کد دیگر تحت هیچ شرایطی امکان اتصال به شبکه آسان وی‌پی‌ان را ندارد."
        )

        keyboard = InlineKeyboardMarkup([
            [InlineKeyboardButton("🗑 حذف یک اشتراک دیگر", callback_data="admin_remove_sub")],
            [InlineKeyboardButton("⚙️ بازگشت به پنل مدیریت", callback_data="admin_dashboard")]
        ])

        await query.edit_message_text(success_text, reply_markup=keyboard, parse_mode=ParseMode.HTML)

        alert_msg = (
            "🔔 <b>#ابطال_اشتراک_توسط_مدیر</b>\n\n"
            f"👤 <b>مدیر اقدام‌کننده:</b> <code>{user_id}</code>\n"
            f"🔑 <b>کد باطل‌شده:</b> <code>{pin}</code>\n"
            f"🆔 <b>کاربر مرتبط:</b> {linked_str}"
        )
        await notify_admins(context, alert_msg)
        return

    if data == "admin_exit":
        await start_handler(update, context)
        return

    if data == "admin_plans":
        await admin_plans_menu(update, context)
        return

    if data.startswith("admin_plan_view:"):
        await admin_plan_view(update, context)
        return

    if data.startswith("admin_plan_toggle:"):
        plan_id = data.split(":")[1]
        await db.toggle_plan(plan_id)
        await admin_plan_view(update, context)
        return

    if data.startswith("admin_plan_del_ask:"):
        plan_id = data.split(":")[1]
        plan = await db.get_plan_by_id(plan_id)
        if not plan:
            await query.answer("پلن یافت نشد.", show_alert=True)
            return
        confirm_text = (
            f"⚠️ <b>تأییدیه حذف پلن:</b> {plan['title']}\n\n"
            f"آیا از حذف دائمی این پلن از ربات اطمینان دارید؟\n"
            f"📊 حجم: {plan['traffic_gb']} گیگابایت | ⏳ مدت: {plan['days']} روز | 💰 قیمت: {plan['price_toman']:,} تومان\n\n"
            "<i>(توجه: حذف این پلن به کاربران قبلی که آن را خریداری کرده‌اند آسیبی نمی‌رساند، اما پلن دیگر در فروشگاه نمایش داده نخواهد شد.)</i>"
        )
        confirm_keyboard = [
            [
                InlineKeyboardButton("🗑️ بله، پلن حذف شود", callback_data=f"admin_plan_del_confirm:{plan_id}"),
                InlineKeyboardButton("❌ انصراف", callback_data=f"admin_plan_view:{plan_id}")
            ]
        ]
        await query.edit_message_text(confirm_text, reply_markup=InlineKeyboardMarkup(confirm_keyboard), parse_mode=ParseMode.HTML)
        return

    if data.startswith("admin_plan_del_confirm:") or data.startswith("admin_plan_delete:"):
        plan_id = data.split(":")[1]
        await db.delete_plan(plan_id)
        await query.answer("✅ پلن با موفقیت حذف گردید.", show_alert=True)
        await admin_plans_menu(update, context)
        return

    if data.startswith("admin_plan_edit_price:"):
        plan_id = data.split(":")[1]
        ADMIN_STATES[user_id] = {"action": "edit_plan_price", "plan_id": plan_id}
        await query.edit_message_text(
            f"💰 لطفاً قیمت جدید را برای پلن <code>{plan_id}</code> به <b>تومان</b> ارسال کنید:\n(مثال: 180000)",
            parse_mode=ParseMode.HTML
        )
        return

    if data == "admin_plan_create":
        ADMIN_STATES[user_id] = {"action": "create_plan_step1"}
        await query.edit_message_text(
            "➕ <b>ایجاد پلن جدید - مرحله ۱ از ۵:</b>\n\n"
            "لطفاً یک <b>شناسه انگلیسی یکتا</b> برای پلن وارد کنید (بدون فاصله، مثال: <code>plan_50g</code>):",
            parse_mode=ParseMode.HTML
        )
        return

    if data == "admin_texts":
        await admin_texts_menu(update, context)
        return

    if data == "admin_edit_welcome":
        ADMIN_STATES[user_id] = {"action": "edit_welcome"}
        await query.edit_message_text(
            "📝 لطفاً متن کامل خوش‌آمدگویی جدید را ارسال نمایید.\n(می‌توانید از تگ <code>{name}</code> برای نام کاربر استفاده کنید):",
            parse_mode=ParseMode.HTML
        )
        return

    if data == "admin_edit_support":
        ADMIN_STATES[user_id] = {"action": "edit_support"}
        await query.edit_message_text("📞 لطفاً آیدی جدید پشتیبانی تلگرام را ارسال کنید (مثال: <code>@Asanvpnsupport_bot</code>):", parse_mode=ParseMode.HTML)
        return

    if data == "admin_edit_applink":
        ADMIN_STATES[user_id] = {"action": "edit_applink"}
        await query.edit_message_text("📲 لطفاً آدرس اینترنتی جدید دانلود اپلیکیشن اندروید را ارسال نمایید:", parse_mode=ParseMode.HTML)
        return

    if data == "admin_notifications":
        await admin_notifications_menu(update, context)
        return

    if data == "toggle_notif_sale":
        val = await db.get_setting("notify_admin_on_sale", "1")
        await db.set_setting("notify_admin_on_sale", "0" if val == "1" else "1")
        await admin_notifications_menu(update, context)
        return

    if data == "toggle_notif_renew":
        val = await db.get_setting("notify_admin_on_renew", "1")
        await db.set_setting("notify_admin_on_renew", "0" if val == "1" else "1")
        await admin_notifications_menu(update, context)
        return

    if data == "toggle_maint_mode":
        val = await db.get_setting("maintenance_mode", "0")
        await db.set_setting("maintenance_mode", "0" if val == "1" else "1")
        await admin_notifications_menu(update, context)
        return

    if data == "toggle_admin_bypass":
        val = await db.get_setting("admin_bypass_payment", "1")
        await db.set_setting("admin_bypass_payment", "0" if val == "1" else "1")
        await admin_notifications_menu(update, context)
        return

    if data == "admin_broadcast":
        ADMIN_STATES[user_id] = {"action": "broadcast_message"}
        await query.edit_message_text(
            "📢 <b>ارسال پیام همگانی به کاربران:</b>\n\n"
            "لطفاً متن پیام خود را ارسال فرمایید تا برای تمامی کاربران ثبت‌نامی در ربات ارسال گردد (برای انصراف /cancel را بفرستید):",
            parse_mode=ParseMode.HTML
        )
        return

    # Backup settings & actions
    if data == "admin_backup_menu":
        await admin_backup_menu(update, context)
        return

    if data == "toggle_auto_backup":
        val = await db.get_setting("auto_backup_enabled", "1")
        new_val = "0" if val == "1" else "1"
        await db.set_setting("auto_backup_enabled", new_val)
        await admin_backup_menu(update, context)
        return

    if data == "admin_backup_interval_menu":
        await admin_backup_interval_menu(update, context)
        return

    if data.startswith("set_backup_interval:"):
        hours = data.split(":")[1]
        await db.set_setting("auto_backup_interval_hours", hours)
        await query.answer(f"بازه زمانی بکاپ خودکار به هر {hours} ساعت تغییر یافت.", show_alert=True)
        await admin_backup_menu(update, context)
        return

    if data == "admin_edit_backup_channel":
        ADMIN_STATES[user_id] = {"action": "edit_backup_channel"}
        current_ch = await db.get_setting("backup_channel_id", "-1004389472709")
        await query.edit_message_text(
            "✏️ <b>تنظیم کانال تلگرام مقصد برای دریافت فایل‌های بکاپ:</b>\n\n"
            f"کانال فعلی: <code>{current_ch}</code>\n\n"
            "لطفاً <b>شناسه عددی کانال</b> (مثال: <code>-1004389472709</code>) یا <b>آیدی عمومی کانال</b> (مثال: <code>@my_channel</code>) را ارسال فرمایید.\n\n"
            "⚠️ <i>نکته: ربات حتماً باید در کانال عضو و دارای دسترسی ادمین جهت ارسال پیام و فایل باشد.</i>\n\n"
            "برای انصراف /cancel را ارسال نمایید.",
            parse_mode=ParseMode.HTML
        )
        return

    if data in ["admin_instant_backup_now", "admin_instant_backup"]:
        channel_id = await db.get_setting("backup_channel_id", "-1004389472709")
        await query.edit_message_text(f"⏳ در حال ایجاد فایل‌های پایگاه داده و JSON کاربران و ارسال مستقیم به کانال <code>{channel_id}</code>...", parse_mode=ParseMode.HTML)
        success = await backup_mgr.perform_backup_and_send(context.bot, channel_id, db=db, worker=worker)
        if success:
            await query.edit_message_text(
                f"✅ <b>بسته پشتیبان کامل با موفقیت به کانال ارسال شد!</b>\n\n"
                f"📢 <b>کانال مقصد:</b> <code>{channel_id}</code>\n"
                f"📦 شامل دیتابیس کامل SQLite و فایل JSON کلیه کاربران و سرورها.",
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton("💾 بازگشت به تنظیمات بکاپ", callback_data="admin_backup_menu")],
                    [InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")]
                ]),
                parse_mode=ParseMode.HTML
            )
        else:
            await query.edit_message_text(
                "❌ خطا در ارسال بکاپ به کانال.\n"
                "لطفاً اطمینان حاصل کنید ربات در کانال ادمین است و دسترسی ارسال فایل دارد.",
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton("💾 بازگشت به تنظیمات بکاپ", callback_data="admin_backup_menu")],
                    [InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")]
                ]),
                parse_mode=ParseMode.HTML
            )
        return

    if data == "admin_send_backup_direct":
        await query.edit_message_text("⏳ در حال آماده‌سازی و ارسال مستقیم فایل‌های بکاپ برای شما...")
        success = await backup_mgr.perform_backup_and_send(context.bot, target_chat_id=user_id, db=db, worker=worker)
        if success:
            await query.message.reply_text(
                "✅ <b>فایل‌های پشتیبان (SQLite + JSON) با موفقیت در این چت برای شما ارسال شدند.</b>",
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton("💾 بازگشت به تنظیمات بکاپ", callback_data="admin_backup_menu")],
                    [InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")]
                ]),
                parse_mode=ParseMode.HTML
            )
        else:
            await query.edit_message_text(
                "❌ خطا در ایجاد یا ارسال مستقیم فایل‌های بکاپ.",
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton("💾 بازگشت به تنظیمات بکاپ", callback_data="admin_backup_menu")]
                ])
            )
        return

    if data == "admin_stats_full":
        stats = await db.get_stats()
        text = (
            "📊 <b>گزارش کامل مالی و عملکردی سیستم:</b>\n\n"
            f"👥 <b>تعداد کل کاربران ربات:</b> {stats['total_users']:,} نفر\n"
            f"📑 <b>تعداد فاکتورهای صادر شده:</b> {stats['total_orders']:,} عدد\n"
            f"✅ <b>تعداد فاکتورهای پرداخت‌شده:</b> {stats['paid_orders']:,} عدد\n"
            f"💰 <b>درآمد کل حاصله:</b> {stats['total_revenue']:,} تومان\n"
            f"📅 <b>درآمد امروز:</b> {stats['today_revenue']:,} تومان\n"
            f"📆 <b>درآمد ۳۰ روز گذشته:</b> {stats['month_revenue']:,} تومان\n"
            f"⚡ <b>تعداد پلن‌های آماده فروش:</b> {stats['active_plans']} پلن\n"
        )
        await query.edit_message_text(
            text,
            reply_markup=InlineKeyboardMarkup([
                [InlineKeyboardButton("🔙 بازگشت به پنل مدیریت", callback_data="admin_dashboard")]
            ]),
            parse_mode=ParseMode.HTML
        )
        return

# ----------------- ADMIN INPUT HANDLER (WIZARD) -----------------

async def handle_admin_text_inputs(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    user_id = update.effective_user.id
    if not is_admin(user_id):
        return False

    state = ADMIN_STATES.get(user_id)
    if not state:
        return False

    text = (update.message.text or "").strip()
    action = state.get("action")

    if text == "/cancel":
        ADMIN_STATES.pop(user_id, None)
        await update.message.reply_text("عملیات لغو شد.", reply_markup=get_main_keyboard(user_id))
        return True

    # Remove subscription lookup
    if action == "await_sub_to_remove":
        await process_admin_sub_lookup(update, context, text)
        return True

    # Add new admin wizard
    if action == "await_new_admin":
        if text.lower() in ("/cancel", "cancel", "لغو"):
            ADMIN_STATES.pop(user_id, None)
            await update.message.reply_text("عملیات افزودن مدیر لغو شد.", reply_markup=get_main_keyboard(user_id))
            return True

        f_user = update.message.forward_from
        target_id = None
        target_username = ""
        target_name = ""

        if f_user:
            target_id = f_user.id
            target_username = f_user.username or ""
            target_name = f_user.first_name or ""
        elif update.message.forward_sender_name and not f_user:
            await update.message.reply_text(
                "⚠️ حساب کاربری این فرد فوروارد خصوصی (حفظ حریم خصوصی) دارد و آیدی عددی ایشان از طریق فوروارد قابل دریافت نیست.\n\n"
                "💡 لطفاً <b>شناسه عددی تلگرام (Telegram ID)</b> ایشان را مستقیماً به صورت عدد ارسال فرمایید.\n"
                "(برای انصراف: <code>/cancel</code>)",
                parse_mode=ParseMode.HTML
            )
            return True
        else:
            raw_target = text.strip()
            if raw_target.isdigit():
                target_id = int(raw_target)
                u_info = await db.find_user_by_query(str(target_id))
                if u_info:
                    target_username = u_info.get("username") or ""
                    target_name = u_info.get("first_name") or ""
            elif raw_target.startswith("@") or len(raw_target) >= 3:
                u_info = await db.find_user_by_query(raw_target)
                if u_info:
                    target_id = u_info.get("telegram_id")
                    target_username = u_info.get("username") or ""
                    target_name = u_info.get("first_name") or ""
                else:
                    await update.message.reply_text(
                        f"❌ کاربری با مشخصات <code>{raw_target}</code> در دیتابیس ربات یافت نشد.\n\n"
                        "💡 لطفاً <b>شناسه عددی تلگرام (Telegram ID)</b> کاربر را وارد فرمایید.\n"
                        "(برای انصراف: <code>/cancel</code>)",
                        parse_mode=ParseMode.HTML
                    )
                    return True

        if not target_id:
            await update.message.reply_text(
                "⚠️ لطفاً یک شناسه عددی تلگرام معتبر ارسال کنید یا پیامی از کاربر فوروارد فرمایید.\n"
                "(برای انصراف: <code>/cancel</code>)"
            )
            return True

        # Check if already admin
        if db.is_admin(target_id):
            ADMIN_STATES.pop(user_id, None)
            await update.message.reply_text(
                f"ℹ️ کاربر <b>{target_name or 'مدیر'}</b> (<code>{target_id}</code>) در حال حاضر مدیر فعال ربات می‌باشد!",
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton("👥 بازگشت به لیست مدیران", callback_data="admin_manage_admins")],
                    [InlineKeyboardButton("⚙️ پنل مدیریت", callback_data="admin_dashboard")]
                ]),
                parse_mode=ParseMode.HTML
            )
            return True

        # Add admin
        await db.add_admin(target_id, target_username, target_name, added_by=user_id)
        ADMIN_STATES.pop(user_id, None)

        # Set admin commands for newly promoted admin
        admin_commands = [
            BotCommand("start", "🚀 منوی اصلی و شروع مجدد (Restart)"),
            BotCommand("admin", "⚙️ پنل مدیریت هوشمند ربات"),
            BotCommand("delpin", "🗑 حذف و ابطال اشتراک کاربر"),
            BotCommand("plans", "🛒 خرید اشتراک جدید"),
            BotCommand("test", "🎁 دریافت اشتراک تست رایگان (۱ روزه)"),
            BotCommand("renew", "🔄 تمدید و شارژ اشتراک"),
            BotCommand("status", "📊 استعلام وضعیت اشتراک من"),
            BotCommand("download", "📥 دانلود نرم‌افزار اندروید"),
            BotCommand("support", "💬 پشتیبانی ۲۴ ساعته")
        ]
        try:
            await context.bot.set_my_commands(admin_commands, scope=BotCommandScopeChat(chat_id=target_id))
            await context.bot.set_my_commands(admin_commands, scope=BotCommandScopeChat(chat_id=target_id), language_code="fa")
        except Exception as e:
            logger.warning(f"Failed to set admin commands for {target_id}: {e}")

        # Send notification to new admin and upgrade their reply keyboard
        try:
            await context.bot.send_message(
                chat_id=target_id,
                text=(
                    "🎉 <b>تبریک! دسترسی مدیریت ربات آسان وی‌پی‌ان به شما اعطا گردید.</b> 🛡️⚡\n\n"
                    "از هم‌اکنون دکمه «⚙️ پنل مدیریت هوشمند ربات» در منوی اصلی شما فعال شد و می‌توانید با ارسال دستور <code>/admin</code> نیز به بخش مدیریت دسترسی داشته باشید."
                ),
                reply_markup=get_main_keyboard(target_id),
                parse_mode=ParseMode.HTML
            )
        except Exception as e:
            logger.info(f"Could not reach user {target_id} to send promo notification: {e}")

        # Notify other admins
        actor_name = update.effective_user.first_name or str(user_id)
        alert_msg = (
            "🔔 <b>#افزودن_مدیر_جدید</b>\n\n"
            f"👤 <b>مدیر اقدام‌کننده:</b> {actor_name} (<code>{user_id}</code>)\n"
            f"➕ <b>مدیر جدید:</b> {target_name or 'بدون نام'} (@{target_username or 'ندارد'} | <code>{target_id}</code>)"
        )
        await notify_admins(context, alert_msg)

        # Confirm to current admin
        kb = InlineKeyboardMarkup([
            [InlineKeyboardButton("👥 بازگشت به لیست مدیران", callback_data="admin_manage_admins")],
            [InlineKeyboardButton("⚙️ پنل مدیریت", callback_data="admin_dashboard")]
        ])
        await update.message.reply_text(
            f"✅ <b>کاربر {target_name or 'جدید'} با موفقیت به مدیران ربات اضافه شد!</b>\n\n"
            f"🆔 <b>شناسه تلگرام:</b> <code>{target_id}</code>\n"
            f"👤 <b>یوزرنیم:</b> @{target_username or 'ندارد'}\n\n"
            "دکمه پنل مدیریت و دسترسی‌های کامل ادمین برای ایشان فعال گردید.",
            reply_markup=kb,
            parse_mode=ParseMode.HTML
        )
        return True

    # 1. Edit Plan Price
    if action == "edit_plan_price":
        if not text.isdigit():
            await update.message.reply_text("⚠️ لطفاً فقط عدد (به تومان) وارد کنید:")
            return True
        pid = state["plan_id"]
        new_price = int(text)
        plan = await db.get_plan_by_id(pid)
        if plan:
            await db.add_or_update_plan(
                plan_id=pid,
                title=plan["title"],
                days=plan["days"],
                traffic_gb=plan["traffic_gb"],
                max_devices=plan["max_devices"],
                price_toman=new_price,
                is_active=plan["is_active"],
                sort_order=plan["sort_order"]
            )
            ADMIN_STATES.pop(user_id, None)
            await update.message.reply_text(
                f"✅ قیمت پلن <b>{plan['title']}</b> به <b>{new_price:,} تومان</b> بروزرسانی شد.",
                parse_mode=ParseMode.HTML,
                reply_markup=get_main_keyboard(user_id)
            )
        return True

    # 2. Create Plan Wizard (5 steps)
    if action == "create_plan_step1":
        clean_id = text.lower().replace(" ", "_")
        state["plan_id"] = clean_id
        state["action"] = "create_plan_step2"
        await update.message.reply_text(
            f"✅ شناسه: <code>{clean_id}</code>\n\n"
            "<b>مرحله ۲ از ۵:</b> لطفاً <b>عنوان پلن</b> را به فارسی وارد کنید (مثال: <code>۵۰ گیگابایت (۱ ماهه)</code>):",
            parse_mode=ParseMode.HTML
        )
        return True

    if action == "create_plan_step2":
        state["title"] = text
        state["action"] = "create_plan_step3"
        await update.message.reply_text(
            f"✅ عنوان: <b>{text}</b>\n\n"
            "<b>مرحله ۳ از ۵:</b> لطفاً <b>حجم ترافیک (گیگابایت)</b> را فقط به صورت عدد وارد کنید (برای نامحدود 0):",
            parse_mode=ParseMode.HTML
        )
        return True

    if action == "create_plan_step3":
        if not text.isdigit():
            await update.message.reply_text("⚠️ لطفاً یک عدد معتبر ارسال کنید:")
            return True
        state["traffic_gb"] = int(text)
        state["action"] = "create_plan_step4"
        await update.message.reply_text(
            "<b>مرحله ۴ از ۵:</b> لطفاً <b>مدت اعتبار (تعداد روز)</b> را وارد کنید (مثال: 30):",
            parse_mode=ParseMode.HTML
        )
        return True

    if action == "create_plan_step4":
        if not text.isdigit():
            await update.message.reply_text("⚠️ لطفاً یک عدد معتبر وارد کنید:")
            return True
        state["days"] = int(text)
        state["action"] = "create_plan_step5"
        await update.message.reply_text(
            "<b>مرحله ۵ از ۵:</b> لطفاً <b>قیمت پلن (به تومان)</b> را وارد کنید (مثال: 150000):",
            parse_mode=ParseMode.HTML
        )
        return True

    if action == "create_plan_step5":
        if not text.isdigit():
            await update.message.reply_text("⚠️ لطفاً فقط عدد وارد کنید:")
            return True
        state["price_toman"] = int(text)

        await db.add_or_update_plan(
            plan_id=state["plan_id"],
            title=state["title"],
            days=state["days"],
            traffic_gb=state["traffic_gb"],
            max_devices=2,
            price_toman=state["price_toman"],
            is_active=1,
            sort_order=5
        )
        ADMIN_STATES.pop(user_id, None)
        await update.message.reply_text(
            f"🎉 پلن جدید <b>{state['title']}</b> با قیمت <b>{state['price_toman']:,} تومان</b> با موفقیت ایجاد و فعال شد!",
            parse_mode=ParseMode.HTML,
            reply_markup=get_main_keyboard(user_id)
        )
        return True

    # 3. Edit Settings
    if action == "edit_welcome":
        await db.set_setting("welcome_message", text)
        ADMIN_STATES.pop(user_id, None)
        await update.message.reply_text("✅ متن خوش‌آمدگویی ذخیره و اعمال شد.", reply_markup=get_main_keyboard(user_id))
        return True

    if action == "edit_support":
        supp = text if text.startswith("@") else f"@{text}"
        await db.set_setting("support_username", supp)
        ADMIN_STATES.pop(user_id, None)
        await update.message.reply_text(f"✅ آیدی پشتیبانی به <b>{supp}</b> تغییر یافت.", parse_mode=ParseMode.HTML, reply_markup=get_main_keyboard(user_id))
        return True

    if action == "edit_applink":
        await db.set_setting("app_download_url", text)
        ADMIN_STATES.pop(user_id, None)
        await update.message.reply_text("✅ لینک دانلود اپلیکیشن بروزرسانی شد.", reply_markup=get_main_keyboard(user_id))
        return True

    if action == "edit_backup_channel":
        ADMIN_STATES.pop(user_id, None)
        clean_ch = text.strip()
        await db.set_setting("backup_channel_id", clean_ch)
        kb = InlineKeyboardMarkup([
            [InlineKeyboardButton("💾 بازگشت به تنظیمات بکاپ", callback_data="admin_backup_menu")],
            [InlineKeyboardButton("⚙️ پنل مدیریت", callback_data="admin_dashboard")]
        ])
        await update.message.reply_text(
            f"✅ <b>کانال مقصد بکاپ با موفقیت ذخیره شد:</b>\n<code>{clean_ch}</code>",
            parse_mode=ParseMode.HTML,
            reply_markup=kb
        )
        return True

    # 4. Broadcast
    if action == "broadcast_message":
        ADMIN_STATES.pop(user_id, None)
        msg_wait = await update.message.reply_text("⏳ در حال ارسال پیام به تمامی کاربران...")
        uids = await db.get_all_user_ids()
        success = 0
        failed = 0
        for uid in uids:
            try:
                await context.bot.send_message(chat_id=uid, text=text, parse_mode=ParseMode.HTML)
                success += 1
                await asyncio.sleep(0.04)
            except Exception:
                failed += 1

        await msg_wait.edit_text(
            f"📢 ارسال پیام همگانی پایان یافت:\n\n"
            f"✅ ارسال موفق: {success} کاربر\n"
            f"❌ ناموفق: {failed} کاربر"
        )
        return True

    return False

# ----------------- STATUS & SUPPORT & DOWNLOAD & TEST -----------------

async def show_user_status_flow(update: Update, context: ContextTypes.DEFAULT_TYPE, target_pin: str = None):
    is_query = hasattr(update, "callback_query") and update.callback_query is not None
    query = update.callback_query if is_query else None
    user = query.from_user if is_query else update.effective_user
    user_id = user.id
    USER_STATES.pop(user_id, None)

    if query:
        await query.answer()

    app_download_url = await db.get_setting("app_download_url", "https://asanvpn-worker.hi-deploy.workers.dev/download")
    support_username = await db.get_setting("support_username", "@Asanvpnsupport_bot")

    # 1. Determine which PIN to show
    pin = target_pin
    all_pins = await db.get_user_all_pins(user_id)

    if not pin:
        if all_pins:
            pin = all_pins[0]
        else:
            pin = await db.get_user_latest_pin(user_id)

    # 1.5. If still no PIN, auto-discover from Cloudflare Worker by Telegram ID
    if not pin:
        cf_users = await worker.find_users_by_telegram_id(user_id)
        if cf_users:
            for u in cf_users:
                p = str(u.get("pin", "")).strip()
                if p:
                    await db.link_pin_to_user(user_id, p)
                    if not pin:
                        pin = p
            all_pins = await db.get_user_all_pins(user_id)

    # 2. If no PIN is registered for this telegram user in database or worker
    if not pin:
        text = (
            "📊 <b>استعلام وضعیت و مشخصات اشتراک:</b>\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
            f"👤 <b>کاربر گرامی {user.first_name or ''}:</b>\n\n"
            "❌ <b>شما در حال حاضر هیچ اشتراکی در سیستم ربات آسان وی‌پی‌ان ندارید.</b>\n\n"
            "🎁 <b>هدیه ویژه عضویت:</b> شما می‌توانید همین حالا <b>اشتراک تست رایگان ۱ روزه (۱ گیگابایت)</b> دریافت فرمایید تا کیفیت و سرعت شبکه را بررسی کنید.\n\n"
            "یا برای خرید پلن‌های اقتصادی ۲ کاربره (با سرورهای VIP و پایداری بالا) از گزینه‌های زیر استفاده نمایید:"
        )
        keyboard = InlineKeyboardMarkup([
            [InlineKeyboardButton("🎁 دریافت اشتراک تست رایگان (۱ روزه)", callback_data="claim_free_test", style="success")],
            [InlineKeyboardButton("🛒 خرید اشتراک پرسرعت جدید", callback_data="buy_new_plan", style="danger")],
            [InlineKeyboardButton("🔍 استعلام با وارد کردن کد دستی (۱۰ یا ۸ رقمی)", callback_data="status_manual_input", style="primary")],
            [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")]
        ])
        if query:
            await query.edit_message_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
        elif update.message:
            await update.message.reply_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)
        return

    # 3. Query Cloudflare Worker for realtime subscription status
    loading_text = "⏳ در حال استعلام لحظه‌ای از شبکه سرورها..."
    if query:
        msg = await query.edit_message_text(loading_text)
    else:
        msg = await update.message.reply_text(loading_text)

    user_record = await worker.get_user_by_pin(pin)

    # Auto-recovery: If PIN not found on worker, check if user has other valid pins or worker accounts
    if not user_record:
        cf_users = await worker.find_users_by_telegram_id(user_id)
        recovered = False
        for u in cf_users:
            cand_pin = str(u.get("pin", "")).strip()
            if cand_pin and cand_pin != pin:
                logger.info(f"Auto-recovered user PIN {cand_pin} for telegram user {user_id}")
                user_record = u
                pin = cand_pin
                await db.link_pin_to_user(user_id, cand_pin)
                all_pins = await db.get_user_all_pins(user_id)
                recovered = True
                break

        if not recovered:
            not_found_text = (
                f"⚠️ <b>عدم دریافت اطلاعات اشتراک {pin}</b>\n\n"
                f"در حال حاضر امکان دریافت مشخصات کد <code>{pin}</code> از سرورهای ابری میسر نشد.\n"
                "اشتراک شما در دیتابیس محفوظ است. می‌توانید مجدداً استعلام بگیرید یا از منوی زیر استفاده نمایید:"
            )
            not_found_kb = InlineKeyboardMarkup([
                [InlineKeyboardButton("🔄 استعلام مجدد", callback_data=f"status_view_pin:{pin}")],
                [InlineKeyboardButton("🔍 استعلام با کد دستی", callback_data="status_manual_input")],
                [InlineKeyboardButton("🛒 خرید اشتراک جدید", callback_data="buy_new_plan")],
                [InlineKeyboardButton("💬 پشتیبانی تلگرام", url=f"https://t.me/{support_username.lstrip('@')}")],
                [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home")]
            ])
            await msg.edit_text(not_found_text, reply_markup=not_found_kb, parse_mode=ParseMode.HTML)
            return

    now = int(time.time())
    expires_at = user_record.get("expires_at")
    if expires_at and expires_at > 0:
        days_left = max(0, (expires_at - now) // 86400)
        import datetime
        dt = datetime.datetime.fromtimestamp(expires_at, datetime.timezone.utc)
        exp_date_str = dt.strftime("%Y-%m-%d")
        exp_str = f"<b>{days_left} روز باقی‌مانده</b> (تا {exp_date_str})"
    else:
        exp_str = "<b>نامحدود / بدون تاریخ انقضا</b>"

    traffic_total = user_record.get("traffic_total_bytes", 0)
    traffic_rem = user_record.get("traffic_remaining_bytes", -1)

    if traffic_rem == -1 or traffic_total == 0:
        traffic_str = "<b>نامحدود</b>"
    else:
        rem_gb = round(traffic_rem / (1024**3), 2)
        tot_gb = round(traffic_total / (1024**3), 2)
        pct = int((traffic_rem / traffic_total) * 100) if traffic_total > 0 else 0
        traffic_str = f"<b>{rem_gb} گیگابایت</b> از {tot_gb} گیگابایت (مانده: {pct}%)"

    devices = user_record.get("devices", [])
    max_dev = user_record.get("max_devices", 2)
    dev_str = "نامحدود" if max_dev <= 0 else f"<b>{len(devices)} از {max_dev} دستگاه</b> (۲ گوشی همزمان)"

    active = user_record.get("active", True)
    status_emoji = "🟢 <b>فعال و پرسرعت</b>" if active else "🔴 <b>غیرفعال (منقضی)</b>"

    res_text = (
        "📊 <b>مشخصات و وضعیت لحظه‌ای اشتراک شما:</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "🔑 <b>کد اختصاصی اشتراک شما:</b>\n\n"
        f"👉  <code>{pin}</code>  👈\n\n"
        "👆 <b>برای کپی کردن، کافیست یک‌بار روی کد بالا ضربه بزنید.</b>\n\n"
        f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}\"><b>کلیک کنید تا اپلیکیشن آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        f"⚡ <b>وضعیت اتصال:</b> {status_emoji}\n"
        f"⏳ <b>اعتبار زمانی:</b> {exp_str}\n"
        f"📈 <b>حجم باقیمانده:</b> {traffic_str}\n"
        f"📱 <b>دستگاه‌های متصل:</b> {dev_str}\n"
        "🔒 <b>امنیت سخت‌افزاری:</b> قفل اختصاصی روی ۲ دستگاه همزمان\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        "💡 <i>کد اختصاصی بالا را کپی کرده و در کادر ورودی نرم‌افزار <b>آسان وی‌پی‌ان (AsanVPN)</b> وارد فرمایید.</i>"
    )

    kb = [
        [InlineKeyboardButton("🚀 اتصال و فعال‌سازی مستقیم در برنامه", url=f"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}")],
        [InlineKeyboardButton("🔄 تمدید و افزایش حجم این اشتراک", callback_data=f"renew_pin_direct:{pin}", style="danger")],
        [
            InlineKeyboardButton("📥 دانلود نرم‌افزار اندروید", callback_data="download_app_post", style="primary"),
            InlineKeyboardButton("💬 پشتیبانی تلگرام", url=f"https://t.me/{support_username.lstrip('@')}", style="primary")
        ]
    ]

    other_pins = [p for p in all_pins if p != pin]
    if other_pins:
        row = []
        for op in other_pins[:3]:
            row.append(InlineKeyboardButton(f"🔑 اشتراک {op}", callback_data=f"status_view_pin:{op}", style="primary"))
        kb.append(row)

    kb.append([InlineKeyboardButton("🔍 استعلام با کد دستی دیگر", callback_data="status_manual_input", style="primary")])
    kb.append([InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")])

    await msg.edit_text(res_text, reply_markup=InlineKeyboardMarkup(kb), parse_mode=ParseMode.HTML)

async def status_manual_input_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    user_id = query.from_user.id
    USER_STATES[user_id] = "waiting_for_status_pin"
    text = (
        "🔍 <b>استعلام دستی وضعیت اشتراک:</b>\n\n"
        "لطفاً <b>کد اختصاصی ۱۰ رقمی (یا ۸ رقمی)</b> مورد نظر خود را ارسال فرمایید:"
    )
    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")]
    ])
    await query.edit_message_text(text, reply_markup=keyboard, parse_mode=ParseMode.HTML)

async def status_view_pin_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    pin = query.data.split(":")[1]
    await show_user_status_flow(update, context, target_pin=pin)

async def handle_status_pin_input(update: Update, context: ContextTypes.DEFAULT_TYPE, pin: str):
    clean_pin = str(pin).strip()
    await show_user_status_flow(update, context, target_pin=clean_pin)

# ----------------- FREE TEST PLAN (1 TIME: 1GB, 1 DAY, 2 DEVICES) -----------------

async def claim_free_test_plan(update: Update, context: ContextTypes.DEFAULT_TYPE):
    is_query = hasattr(update, "callback_query") and update.callback_query is not None
    query = update.callback_query if is_query else None
    user = query.from_user if is_query else update.effective_user
    user_id = user.id
    USER_STATES.pop(user_id, None)

    if query:
        await query.answer()

    support_username = await db.get_setting("support_username", "@Asanvpnsupport_bot")

    # Check 1: Already claimed test?
    if await db.has_claimed_test(user_id):
        test_info = await db.get_test_claim(user_id)
        claimed_pin = test_info.get("pin", "") if test_info else ""
        text = (
            "⚠️ <b>سهمیه اشتراک تست رایگان شما استفاده شده است</b>\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
            f"👤 <b>کاربر گرامی {user.first_name or ''}:</b>\n"
            "هر کاربر تلگرام مجاز به <b>تنها ۱ بار</b> دریافت اشتراک تست رایگان می‌باشد و سهمیه شما پیش‌تر ثبت شده است.\n\n"
        )
        if claimed_pin:
            text += (
                f"🔑 <b>کد تست اختصاصی ثبت‌شده شما:</b>\n\n"
                f"👉  <code>{claimed_pin}</code>  👈\n\n"
                "👆 <b>(برای کپی کردن، کافیست یک‌بار روی کد بالا ضربه بزنید)</b>\n\n"
                f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={claimed_pin}\"><b>کلیک کنید تا اپلیکیشن آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
            )
        text += (
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
            "💡 در صورتی که زمان یا حجم تست شما به پایان رسیده، می‌توانید یکی از پلن‌های اقتصادی و پرسرعت ۲ کاربره ما را با آی‌پی ثابت و سرورهای بدون قطعی VIP تهیه فرمایید:"
        )
        kb_buttons = []
        if claimed_pin:
            kb_buttons.append([InlineKeyboardButton("🚀 اتصال و فعال‌سازی مستقیم در برنامه", url=f"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={claimed_pin}")])
        kb_buttons.append([InlineKeyboardButton("🛒 خرید اشتراک پرسرعت جدید", callback_data="buy_new_plan", style="danger")])
        if claimed_pin:
            kb_buttons.append([InlineKeyboardButton("📊 استعلام وضعیت این اشتراک", callback_data=f"status_view_pin:{claimed_pin}", style="success")])
        kb_buttons.append([InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")])
        kb = InlineKeyboardMarkup(kb_buttons)
        if query:
            await query.edit_message_text(text, reply_markup=kb, parse_mode=ParseMode.HTML)
        elif update.message:
            await update.message.reply_text(text, reply_markup=kb, parse_mode=ParseMode.HTML)
        return

    # Check 2: Already has an active subscription or existing account?
    all_pins = await db.get_user_all_pins(user_id)
    has_paid = await db.has_paid_order(user_id)
    existing_cf = await worker.find_users_by_telegram_id(user_id)

    if has_paid or all_pins or existing_cf:
        active_pin = all_pins[0] if all_pins else (str(existing_cf[0].get("pin", "")).strip() if existing_cf else "")
        text = (
            "💎 <b>شما در حال حاضر دارای حساب کاربری فعال در آسان وی‌پی‌ان هستید!</b>\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
            f"👤 <b>کاربر گرامی {user.first_name or ''}:</b>\n"
            "اشتراک تست رایگان تنها مختص <b>کاربران جدیدی</b> است که تاکنون هیچ‌گونه اشتراکی در سیستم نداشته‌اند.\n"
            "شما در حال حاضر دارای حساب کاربری و اشتراک فعال در سیستم می‌باشید و نیازی به فعال‌سازی تست ندارید.\n\n"
        )
        if active_pin:
            text += (
                f"🔑 <b>کد اختصاصی اشتراک شما:</b>\n\n"
                f"👉  <code>{active_pin}</code>  👈\n\n"
                "👆 <b>(برای کپی کردن، کافیست یک‌بار روی کد بالا ضربه بزنید)</b>\n\n"
                f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={active_pin}\"><b>کلیک کنید تا اپلیکیشن آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
            )
        text += (
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
            "جهت بررسی مشخصات، تمدید و یا افزایش حجم می‌توانید از گزینه‌های زیر استفاده فرمایید:"
        )
        kb_buttons = []
        if active_pin:
            kb_buttons.append([InlineKeyboardButton("🚀 اتصال و فعال‌سازی مستقیم در برنامه", url=f"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={active_pin}")])
        kb_buttons.append([InlineKeyboardButton("📊 استعلام وضعیت اشتراک من", callback_data="status_flow", style="success")])
        kb_buttons.append([InlineKeyboardButton("🔄 تمدید و افزایش حجم", callback_data="renew_flow", style="danger")])
        kb_buttons.append([InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")])
        kb = InlineKeyboardMarkup(kb_buttons)
        if query:
            await query.edit_message_text(text, reply_markup=kb, parse_mode=ParseMode.HTML)
        elif update.message:
            await update.message.reply_text(text, reply_markup=kb, parse_mode=ParseMode.HTML)
        return

    # Eligible: Create 1GB 1day test plan on Cloudflare Worker (2 devices)
    loading_text = "⏳ در حال ساخت اشتراک تست رایگان ۱ روزه (۱ گیگابایت) در سرورها...\nلطفاً چند ثانیه شکیبا باشید."
    if query:
        msg = await query.edit_message_text(loading_text)
    else:
        msg = await update.message.reply_text(loading_text)

    worker_user = await worker.create_user(
        name=f"Test TG @{user_id} - {user.first_name or ''}",
        days=1,
        traffic_gb=1,
        max_devices=2,
        telegram_id=user_id,
        is_test=True
    )

    if not worker_user or not worker_user.get("pin"):
        error_text = (
            "⚠️ متأسفانه در ساخت اشتراک تست خطایی رخ داد.\n"
            "لطفاً لحظاتی دیگر مجدداً تلاش کنید یا با پشتیبانی در ارتباط باشید."
        )
        kb = InlineKeyboardMarkup([
            [InlineKeyboardButton("💬 پشتیبانی تلگرام", url=f"https://t.me/{support_username.lstrip('@')}", style="primary")],
            [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")]
        ])
        await msg.edit_text(error_text, reply_markup=kb, parse_mode=ParseMode.HTML)
        return

    pin = worker_user.get("pin")
    await db.record_test_claim(user_id, pin)
    logger.info(f"User {user_id} claimed 1-time free test PIN {pin}")

    success_text = (
        "🎉 <b>اشتراک تست رایگان شما با موفقیت فعال شد!</b> 🎁⚡\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "🔑 <b>کد اختصاصی ۸ رقمی شما:</b>\n\n"
        f"👉  <code>{pin}</code>  👈\n\n"
        "👆 <b>برای کپی کردن، کافیست یک‌بار روی کد بالا ضربه بزنید.</b>\n\n"
        f"🚀 <a href=\"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}\"><b>کلیک کنید تا اپلیکیشن آسان وی‌پی‌ان خودکار باز و فعال شود</b></a>\n\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        "📦 <b>مشخصات پلن تست رایگان:</b>\n"
        "• <b>میزان حجم:</b> ۱ گیگابایت\n"
        "• <b>مدت زمان اعتبار:</b> ۲۴ ساعت (۱ روز کامل)\n"
        "• <b>ظرفیت مجاز:</b> ۲ گوشی همزمان (قفل سخت‌افزاری اختصاصی)\n"
        "• <b>پروتکل‌ها:</b> اتصال فوق‌سریع VLESS و Reality هوشمند ضد فیلتر\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "📱 <b>راهنمای اتصال سریع:</b>\n"
        "۱. دکمه «اتصال و فعال‌سازی مستقیم در برنامه» زیر را لمس نمایید تا برنامه خودکار باز شود.\n"
        "۲. یا ابتدا نرم‌افزار <b>آسان وی‌پی‌ان (AsanVPN)</b> را از دکمه زیر دانلود و نصب فرمایید.\n"
        "۳. دکمه اتصال را لمس کنید و از اینترنت آزاد و بدون قطعی لذت ببرید! 🚀"
    )

    kb = InlineKeyboardMarkup([
        [InlineKeyboardButton("🚀 اتصال و فعال‌سازی مستقیم در برنامه", url=f"https://asanvpn-worker.hi-deploy.workers.dev/open?pin={pin}")],
        [InlineKeyboardButton("📥 دانلود مستقیم اپلیکیشن اندروید", callback_data="download_app_post", style="success")],
        [InlineKeyboardButton("📊 استعلام وضعیت این اشتراک", callback_data=f"status_view_pin:{pin}", style="primary")],
        [InlineKeyboardButton("🛒 خرید پلن‌های ویژه و پرحجم", callback_data="buy_new_plan", style="danger")],
        [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")]
    ])

    await msg.edit_text(success_text, reply_markup=kb, parse_mode=ParseMode.HTML)

# ----------------- DOWNLOAD APP GUIDE (ALL ARCHITECTURES) -----------------

def generate_download_qr(url: str) -> io.BytesIO:
    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=10,
        border=2,
    )
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="#0f172a", back_color="#ffffff")
    bio = io.BytesIO()
    bio.name = "asanvpn_download_qr.png"
    img.save(bio, "PNG")
    bio.seek(0)
    return bio

async def send_download_info(update: Update, context: ContextTypes.DEFAULT_TYPE):
    is_query = hasattr(update, "callback_query") and update.callback_query is not None
    query = update.callback_query if is_query else None
    if query:
        await query.answer()

    support_username = await db.get_setting("support_username", "@Asanvpnsupport_bot")
    universal_apk_url = await db.get_setting("app_download_url", "https://github.com/armanpier/AsanVPN/releases/download/v1.3.12/AsanVPN-v1.3.12-universal.apk")
    cdn_download_url = "https://asanvpn-dashboard.armanpier-oxvrx.arvanedge.ir/download"

    text = (
        "📱 <b>دانلود نرم‌افزار رسمی آسان وی‌پی‌ان (نسخه جدید v1.3.12)</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "✨ <b>طراحی سه‌بعدی لوکس با دو تم روشن و تاریک (Porcelain و Obsidian)</b>\n"
        "📦 <b>نسخه همگانی (Universal APK) — سازگار با ۱۰۰٪ دستگاه‌ها</b>\n\n"
        "🔹 <b>امکانات و قابلیت‌های نسخه جدید:</b>\n"
        "• <b>تست تاخیر واقعی (Real Delay):</b> سنجش دقیق پینگ سرورها قبل از برقراری ارتباط برای تضمین نهایت سرعت\n"
        "• <b>اتصال هوشمند آبشاری:</b> تضمین پایداری بدون قطعی و سوئیچ خودکار بین سرورها\n"
        "• <b>تفکیک خودکار ترافیک ایران:</b> باز شدن پرسرعت و مستقیم بانک‌ها و برنامه‌های داخلی\n"
        "• <b>به‌روزرسانی خودکار داخلی:</b> دانلود و نصب نسخه‌های جدید مستقیماً از داخل اپلیکیشن\n"
        "• <b>پشتیبانی از دیپ‌لینک:</b> باز شدن و فعال‌سازی خودکار برنامه با یک لمس\n\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        "📷 <b>بارکد اختصاصی (QR Code):</b> با دوربین گوشی یا بارکدخوان بارکد بالا را اسکن کنید تا دانلود بلافاصله روی گوشی آغاز شود.\n\n"
        "👇 <b>یا از دکمه‌های پرسرعت زیر جهت دریافت مستقیم فایل نصبی استفاده فرمایید:</b>"
    )

    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("⚡ دانلود از سرور پرسرعت کمکی (Cloudflare CDN)", url=cdn_download_url, style="primary")
        ],
        [
            InlineKeyboardButton("📥 دانلود نسخه همگانی (گیت‌هاب رسمی)", url=universal_apk_url, style="success")
        ],
        [
            InlineKeyboardButton("💬 راهنمایی و پشتیبانی", url=f"https://t.me/{support_username.lstrip('@')}", style="primary"),
            InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")
        ]
    ])

    target_chat_id = update.effective_chat.id

    try:
        qr_bio = generate_download_qr(cdn_download_url)
        if query and query.message:
            try:
                await query.message.delete()
            except Exception:
                pass
        await context.bot.send_photo(
            chat_id=target_chat_id,
            photo=qr_bio,
            caption=text,
            parse_mode=ParseMode.HTML,
            reply_markup=keyboard
        )
    except Exception as e:
        logger.error(f"Failed to send QR photo: {e}")
        if query:
            await query.edit_message_text(text, parse_mode=ParseMode.HTML, reply_markup=keyboard, disable_web_page_preview=True)
        elif update.message:
            await update.message.reply_text(text, parse_mode=ParseMode.HTML, reply_markup=keyboard, disable_web_page_preview=True)

async def send_support_info(update: Update, context: ContextTypes.DEFAULT_TYPE):
    support_username = await db.get_setting("support_username", "@Asanvpnsupport_bot")
    text = (
        "💬 <b>پشتیبانی آسان وی‌پی‌ان</b>\n\n"
        "تیم پشتیبانی ما به صورت ۲۴ ساعته در خدمت شماست.\n"
        "در صورت بروز هرگونه سوال یا مشکل، می‌توانید با آیدی زیر در ارتباط باشید:\n\n"
        f"👉 <b>{support_username}</b>"
    )
    keyboard = InlineKeyboardMarkup([
        [InlineKeyboardButton("💬 ارسال پیام به پشتیبانی تلگرام", url=f"https://t.me/{support_username.lstrip('@')}", style="primary")],
        [InlineKeyboardButton("🔙 بازگشت به منوی اصلی", callback_data="back_to_home", style="primary")]
    ])
    if update.message:
        await update.message.reply_text(text, parse_mode=ParseMode.HTML, reply_markup=keyboard)
    elif update.callback_query:
        await update.callback_query.edit_message_text(text, parse_mode=ParseMode.HTML, reply_markup=keyboard)

# ----------------- MESSAGE ROUTER -----------------

def clean_persian_text(s: str) -> str:
    if not s:
        return ""
    # Normalize arabic chars
    s = s.replace("ي", "ی").replace("ك", "ک").replace("ة", "ه").replace("١", "1").replace("٢", "2").replace("٣", "3").replace("٤", "4").replace("٥", "5").replace("٦", "6").replace("٧", "7").replace("٨", "8").replace("٩", "9").replace("٠", "0")
    # Strip emojis and symbols
    s = re.sub(r'[\U00010000-\U0010ffff]', '', s)
    s = re.sub(r'[^\w\s]', '', s)
    return " ".join(s.split()).strip()

async def text_message_router(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    raw_text = (update.message.text or "").strip()

    # Check admin wizards first
    if await handle_admin_text_inputs(update, context):
        return

    clean = clean_persian_text(raw_text)
    state = USER_STATES.get(user_id)

    # 1. Status / Subscription check
    if "وضعیت" in clean or "استعلام" in clean or raw_text.startswith("/status") or raw_text.lower() == "status":
        USER_STATES.pop(user_id, None)
        await show_user_status_flow(update, context)
        return

    # 2. Free test plan
    if "تست" in clean or raw_text.startswith("/test") or raw_text.lower() == "test":
        USER_STATES.pop(user_id, None)
        await claim_free_test_plan(update, context)
        return

    # 3. Download app
    if "دانلود" in clean or "اپلیکیشن" in clean or "برنامه" in clean or "نرم افزار" in clean or raw_text.startswith("/download") or raw_text.startswith("/app"):
        USER_STATES.pop(user_id, None)
        await send_download_info(update, context)
        return

    # 4. Buy new plan
    if "خرید" in clean or raw_text.startswith("/plans") or raw_text.startswith("/buy"):
        USER_STATES.pop(user_id, None)
        await show_plans_menu(update, context)
        return

    # 5. Renew / Extend
    if "تمدید" in clean or "شارژ" in clean or raw_text.startswith("/renew"):
        USER_STATES.pop(user_id, None)
        await ask_renew_pin(update, context)
        return

    # 6. Support
    if "پشتیبانی" in clean or "ارتباط" in clean or raw_text.startswith("/support"):
        USER_STATES.pop(user_id, None)
        await send_support_info(update, context)
        return

    # 7. Restart / Main menu
    if "شروع" in clean or "منو" in clean or "اصلی" in clean or raw_text.startswith("/start"):
        USER_STATES.pop(user_id, None)
        ADMIN_STATES.pop(user_id, None)
        await start_handler(update, context)
        return

    # 8. Admin panel
    if "مدیریت" in clean or raw_text.startswith("/admin"):
        if is_admin(user_id):
            USER_STATES.pop(user_id, None)
            ADMIN_STATES.pop(user_id, None)
            await show_admin_dashboard(update, context)
            return
        else:
            await update.message.reply_text(
                "⛔ دسترسی غیرمجاز: شما اجازه دسترسی به پنل مدیریت را ندارید.",
                reply_markup=get_main_keyboard(user_id)
            )
            return

    # 9. Handle numeric input (10-digit or 8-digit PIN)
    digits_only = re.sub(r'\D', '', raw_text)
    if len(digits_only) in (8, 10):
        if state == "waiting_for_renew_pin":
            await handle_renew_pin_input(update, context, digits_only)
            return
        else:
            await handle_status_pin_input(update, context, digits_only)
            return

    if state == "waiting_for_renew_pin":
        await update.message.reply_text("⚠️ لطفاً یک کد ۱۰ رقمی (یا ۸ رقمی) معتبر ارسال نمایید.")
        return

    if state == "waiting_for_status_pin":
        await update.message.reply_text("⚠️ لطفاً یک کد ۱۰ رقمی (یا ۸ رقمی) معتبر ارسال نمایید.")
        return

    # Fallback
    await update.message.reply_text(
        "لطفاً از منوی زیر یکی از گزینه‌ها را انتخاب نمایید:",
        reply_markup=get_main_keyboard(user_id)
    )

# ----------------- PERIODIC JOBS (INVOICES & DAILY BACKUP) -----------------

async def auto_check_pending_invoices(context: ContextTypes.DEFAULT_TYPE):
    """Background job checking pending invoices."""
    try:
        pending_orders = await db.get_pending_orders(older_than_seconds=1800)
        for order in pending_orders:
            uid = order["hooshpay_uid"]
            if uid.startswith("ADMIN_"):
                continue
            verify_res = await hooshpay.verify_invoice(uid)
            if verify_res.get("paid"):
                tracking_code = (
                    verify_res.get("data", {}).get("tracking_code")
                    or verify_res.get("tracking_code")
                    or "HP-" + uid[-6:]
                )
                logger.info(f"Auto-checker detected paid order {uid} for user {order['telegram_id']}")
                await fulfill_paid_order(context, order, tracking_code)
    except Exception as e:
        logger.error(f"Error in auto_check_pending_invoices: {e}")

async def daily_backup_job(context: ContextTypes.DEFAULT_TYPE):
    """Dynamic background job uploading database snapshot to the backup channel according to admin settings."""
    try:
        auto_backup = await db.get_setting("auto_backup_enabled", "1")
        if auto_backup != "1":
            return

        channel_id = await db.get_setting("backup_channel_id", "-1004389472709")
        interval_hours = int(await db.get_setting("auto_backup_interval_hours", "24"))
        last_backup_ts = int(await db.get_setting("last_backup_time", "0"))

        now = int(time.time())
        if now - last_backup_ts >= (interval_hours * 3600):
            logger.info(f"Executing scheduled backup (Interval: {interval_hours}h) to channel {channel_id}...")
            success = await backup_mgr.perform_backup_and_send(context.bot, channel_id, db=db, worker=worker)
            if success:
                logger.info("Scheduled backup uploaded successfully to channel.")
            else:
                logger.warning("Scheduled backup failed to upload.")
    except Exception as e:
        logger.exception(f"Exception during scheduled backup job: {e}")

# ----------------- MAIN APP INITIALIZATION -----------------

async def setup_bot_commands(application: Application):
    """Sets bot commands and persistent Menu Button in Telegram input box."""
    user_commands = [
        BotCommand("start", "🚀 منوی اصلی و شروع مجدد (Restart)"),
        BotCommand("plans", "🛒 خرید اشتراک جدید"),
        BotCommand("test", "🎁 دریافت اشتراک تست رایگان (۱ روزه)"),
        BotCommand("renew", "🔄 تمدید و شارژ اشتراک"),
        BotCommand("status", "📊 استعلام وضعیت اشتراک من"),
        BotCommand("download", "📥 دانلود نرم‌افزار اندروید"),
        BotCommand("support", "💬 پشتیبانی ۲۴ ساعته")
    ]
    try:
        await application.bot.set_my_commands(user_commands, scope=BotCommandScopeDefault())
        await application.bot.set_my_commands(user_commands, scope=BotCommandScopeDefault(), language_code="fa")
    except Exception as e:
        logger.error(f"Failed to set default bot commands: {e}")

    admin_commands = [
        BotCommand("start", "🚀 منوی اصلی و شروع مجدد (Restart)"),
        BotCommand("admin", "⚙️ پنل مدیریت هوشمند ربات"),
        BotCommand("delpin", "🗑 حذف و ابطال اشتراک کاربر"),
        BotCommand("plans", "🛒 خرید اشتراک جدید"),
        BotCommand("test", "🎁 دریافت اشتراک تست رایگان (۱ روزه)"),
        BotCommand("renew", "🔄 تمدید و شارژ اشتراک"),
        BotCommand("status", "📊 استعلام وضعیت اشتراک من"),
        BotCommand("download", "📥 دانلود نرم‌افزار اندروید"),
        BotCommand("support", "💬 پشتیبانی ۲۴ ساعته")
    ]
    for aid in db.get_admin_ids():
        try:
            await application.bot.set_my_commands(admin_commands, scope=BotCommandScopeChat(chat_id=aid))
            await application.bot.set_my_commands(admin_commands, scope=BotCommandScopeChat(chat_id=aid), language_code="fa")
        except Exception:
            pass

    try:
        await application.bot.set_chat_menu_button(menu_button=MenuButtonCommands())
        for aid in db.get_admin_ids():
            try:
                await application.bot.set_chat_menu_button(chat_id=aid, menu_button=MenuButtonCommands())
            except Exception:
                pass
        logger.info("Chat menu button activated successfully.")
    except Exception as e:
        logger.error(f"Failed to set chat menu button: {e}")

async def back_to_home_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    await start_handler(update, context)

def build_app() -> Application:
    if not BOT_TOKEN:
        logger.warning("BOT_TOKEN is not configured! Please provide BOT_TOKEN in .env.")

    app = Application.builder().token(BOT_TOKEN or "DUMMY_TOKEN").post_init(setup_bot_commands).build()

    # Handlers
    app.add_handler(CommandHandler("start", start_handler))
    app.add_handler(CommandHandler("admin", show_admin_dashboard))
    app.add_handler(CommandHandler("delpin", admin_delpin_command))
    app.add_handler(CommandHandler("removesub", admin_delpin_command))
    app.add_handler(CommandHandler("settings", show_admin_dashboard))
    app.add_handler(CommandHandler("plans", show_plans_menu))
    app.add_handler(CommandHandler("test", claim_free_test_plan))
    app.add_handler(CommandHandler("renew", ask_renew_pin))
    app.add_handler(CommandHandler("status", show_user_status_flow))
    app.add_handler(CommandHandler("support", send_support_info))
    app.add_handler(CommandHandler("download", send_download_info))

    # User callbacks
    app.add_handler(CallbackQueryHandler(show_plans_menu, pattern=r"^buy_new_plan$"))
    app.add_handler(CallbackQueryHandler(claim_free_test_plan, pattern=r"^claim_free_test$"))
    app.add_handler(CallbackQueryHandler(send_download_info, pattern=r"^download_app_post$"))
    app.add_handler(CallbackQueryHandler(ask_renew_pin, pattern=r"^renew_flow$"))
    app.add_handler(CallbackQueryHandler(show_user_status_flow, pattern=r"^status_flow$"))
    app.add_handler(CallbackQueryHandler(status_manual_input_callback, pattern=r"^status_manual_input$"))
    app.add_handler(CallbackQueryHandler(status_view_pin_callback, pattern=r"^status_view_pin:"))
    app.add_handler(CallbackQueryHandler(renew_manual_input_callback, pattern=r"^renew_manual_input$"))
    app.add_handler(CallbackQueryHandler(choose_plan_action_callback, pattern=r"^choose_plan_action:"))
    app.add_handler(CallbackQueryHandler(plan_selected_callback, pattern=r"^buy_plan:"))
    app.add_handler(CallbackQueryHandler(show_plans_menu, pattern=r"^back_to_plans$"))
    app.add_handler(CallbackQueryHandler(renew_option_callback, pattern=r"^renew_opt:"))
    app.add_handler(CallbackQueryHandler(verify_order_callback, pattern=r"^verify_order:"))
    app.add_handler(CallbackQueryHandler(back_to_home_callback, pattern=r"^back_to_home$"))
    app.add_handler(CallbackQueryHandler(lambda u, c: u.callback_query.message.delete(), pattern=r"^cancel_order$"))

    # Direct renewal callback from status card
    async def renew_direct(update: Update, context: ContextTypes.DEFAULT_TYPE):
        q = update.callback_query
        await q.answer()
        pin = q.data.split(":")[1]
        await handle_renew_pin_input(q, context, pin)
    app.add_handler(CallbackQueryHandler(renew_direct, pattern=r"^renew_pin_direct:"))

    # Admin callbacks
    app.add_handler(CallbackQueryHandler(admin_callback_router, pattern=r"^(admin_|toggle_|set_backup_|prio_|test_pnl)"))

    # Plain text messages
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, text_message_router))

    # Job queue
    if app.job_queue:
        # Check pending invoices every 25 seconds
        app.job_queue.run_repeating(auto_check_pending_invoices, interval=25.0, first=10.0)
        # Check backup schedule every 10 minutes (600 seconds)
        app.job_queue.run_repeating(daily_backup_job, interval=600.0, first=30.0)

    return app

if __name__ == "__main__":
    async def main():
        await db.init_db()
        app = build_app()
        logger.info("Starting AsanVPN Telegram Sales & Renewal Bot...")
        await app.initialize()
        await setup_bot_commands(app)
        await app.start()
        await app.updater.start_polling(drop_pending_updates=True)
        while True:
            await asyncio.sleep(3600)

    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        logger.info("Bot stopped.")
