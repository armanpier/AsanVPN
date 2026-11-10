import asyncio
import html
import logging
from datetime import datetime
import pytz
from telegram import (
    Update,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    BotCommand,
    BotCommandScopeDefault,
    BotCommandScopeChat
)
from telegram.constants import ParseMode
from telegram.error import Forbidden, BadRequest, TelegramError
from telegram.ext import (
    ApplicationBuilder,
    CommandHandler,
    MessageHandler,
    CallbackQueryHandler,
    ContextTypes,
    filters
)

from config import BOT_TOKEN, ADMIN_IDS, BOT_USERNAME
from database import db

logging.basicConfig(
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    level=logging.INFO
)
logger = logging.getLogger("AsanSupportBot")

IRAN_TZ = pytz.timezone("Asia/Tehran")

def get_now_iran_str() -> str:
    now = datetime.now(IRAN_TZ)
    return now.strftime("%Y-%m-%d %H:%M:%S")

def is_admin(user_id: int) -> bool:
    return user_id in ADMIN_IDS

# ================= USER HANDLERS ================= #

async def user_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    if not user:
        return

    # If an admin sends /start, show the Admin Dashboard instead
    if is_admin(user.id):
        await admin_start(update, context)
        return

    await db.add_or_update_user(user.id, user.username, user.first_name, user.last_name)

    if await db.is_blocked(user.id):
        await update.message.reply_text(
            "⛔ دسترسی شما به سامانه پشتیبانی مسدود شده است.",
            parse_mode=ParseMode.HTML
        )
        return

    welcome_text = (
        f"👋 <b>درود کاربر گرامی {html.escape(user.first_name or '')}!</b>\n\n"
        "🛡️ <b>به سامانه پشتیبانی اختصاصی آسان وی‌پی‌ان خوش آمدید.</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "💬 شما می‌توانید هرگونه سوال، مشکل در اتصال، درخواست راهنمایی یا انتقاد خود را "
        "در قالب <b>پیام متنی، اسکرین‌شات، ویس یا ویدیو</b> همین‌جا ارسال کنید.\n\n"
        "🚀 پیام شما بلافاصله به کارشناسان پشتیبانی ارجاع داده می‌شود و پاسخ در همین صفحه برای شما ارسال خواهد شد.\n\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        "✍️ <i>لطفاً پیام یا شرح مشکل خود را با جزئیات بنویسید و ارسال کنید:</i>"
    )

    await update.message.reply_text(
        welcome_text,
        parse_mode=ParseMode.HTML
    )

async def handle_user_incoming_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    msg = update.effective_message
    user = update.effective_user
    if not msg or not user:
        return

    # Skip admin messages in this handler (admin replies are handled in admin_message_router)
    if is_admin(user.id):
        return

    # Check blocked
    if await db.is_blocked(user.id):
        await msg.reply_text("⛔ دسترسی شما به سامانه پشتیبانی مسدود شده است.")
        return

    # Update user in DB
    await db.add_or_update_user(user.id, user.username, user.first_name, user.last_name)

    user_name = html.escape(f"{user.first_name or ''} {user.last_name or ''}".strip() or "کاربر بدون نام")
    username_str = f"@{user.username}" if user.username else "ندارد"
    time_str = get_now_iran_str()

    admin_card_text = (
        "📩 <b>#پیام_جدید_پشتیبانی</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        f"👤 <b>نام کاربر:</b> {user_name}\n"
        f"🆔 <b>نام‌کاربری:</b> {username_str}\n"
        f"🔢 <b>شناسه عددی:</b> <code>{user.id}</code>\n"
        f"🕒 <b>زمان ارسال:</b> {time_str}\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        "💡 <i>برای پاسخ دادن به این کاربر، کافیست مستقیماً روی پیام ارسالی یا همین پیام Reply بزنید.</i>"
    )

    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("👤 اطلاعات کاربر", callback_data=f"adm_user_info_{user.id}"),
            InlineKeyboardButton("🚫 مسدودسازی", callback_data=f"adm_block_{user.id}")
        ]
    ])

    # Send to all admins
    for admin_id in ADMIN_IDS:
        try:
            # 1. Send the informative header card
            card_msg = await context.bot.send_message(
                chat_id=admin_id,
                text=admin_card_text,
                parse_mode=ParseMode.HTML,
                reply_markup=keyboard
            )
            await db.save_mapping(card_msg.message_id, user.id, msg.message_id, direction="inbound")

            # 2. Copy the actual user message (preserves text, photos, voice notes, stickers, videos, documents)
            copied_msg = await context.bot.copy_message(
                chat_id=admin_id,
                from_chat_id=user.id,
                message_id=msg.message_id
            )
            await db.save_mapping(copied_msg.message_id, user.id, msg.message_id, direction="inbound")

        except Exception as e:
            logger.error(f"Failed to forward message from user {user.id} to admin {admin_id}: {e}")

    # Acknowledge user
    try:
        await msg.reply_text(
            "✅ <b>پیام شما دریافت و برای تیم پشتیبانی ارسال شد.</b>\n"
            "لطفاً تا بررسی و ارسال پاسخ شکیبا باشید. ⏳",
            parse_mode=ParseMode.HTML
        )
    except Exception as e:
        logger.warning(f"Failed to send confirmation to user {user.id}: {e}")

# ================= ADMIN HANDLERS ================= #

async def admin_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    stats = await db.get_stats()
    text = (
        "🛡️ <b>پنل مدیریت پشتیبانی آسان وی‌پی‌ان</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        f"👥 <b>تعداد کل کاربران ثبت‌شده:</b> {stats['total_users']}\n"
        f"🚫 <b>کاربران مسدود شده:</b> {stats['blocked_users']}\n"
        f"📥 <b>پیام‌های دریافتی:</b> {stats['inbound_messages']}\n"
        f"📤 <b>پاسخ‌های ارسالی:</b> {stats['outbound_messages']}\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "💡 <b>نحوه پاسخگویی:</b>\n"
        "هنگامی که پیامی از کاربر دریافت می‌کنید، کافیست مستقیماً روی پیام کاربر یا کارت مشخصات او <b>Reply</b> کنید.\n"
        "پاسخ شما (متن، عکس، ویس، ویدیو یا فایل) بدون واسطه برای کاربر ارسال خواهد شد.\n\n"
        "📋 <b>دستورات در دسترس:</b>\n"
        "• <code>/stats</code> — مشاهده وضعیت و آمار پیام‌ها\n"
        "• <code>/blocked</code> — لیست کاربران مسدود شده\n"
        "• <code>/send &lt;user_id&gt; &lt;متن&gt;</code> — ارسال مستقیم پیام به کاربر با آیدی عددی\n"
        "• <code>/block &lt;user_id&gt; [دلیل]</code> — مسدود کردن کاربر\n"
        "• <code>/unblock &lt;user_id&gt;</code> — رفع مسدودیت کاربر\n"
        "• <code>/info &lt;user_id&gt;</code> — استعلام جزئیات و آمار کاربر\n"
        "• <code>/broadcast &lt;متن&gt;</code> — ارسال همگانی به تمام مراجعین"
    )

    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton("📊 بروزرسانی آمار", callback_data="adm_refresh_stats"),
            InlineKeyboardButton("🚫 کاربران مسدود", callback_data="adm_list_blocked")
        ]
    ])

    await update.message.reply_text(text, parse_mode=ParseMode.HTML, reply_markup=keyboard)

async def admin_message_router(update: Update, context: ContextTypes.DEFAULT_TYPE):
    msg = update.effective_message
    user = update.effective_user
    if not msg or not user or not is_admin(user.id):
        return

    # Check if admin is replying to a message
    if not msg.reply_to_message:
        await msg.reply_text(
            "💡 <b>راهنمایی:</b> برای پاسخ به کاربر، لطفاً مستقیماً روی پیام کاربر یا کارت مشخصات او <b>Reply</b> بزنید.\n\n"
            "یا از دستور زیر استفاده کنید:\n"
            "<code>/send &lt;شناسه_عددی&gt; &lt;متن پیام&gt;</code>",
            parse_mode=ParseMode.HTML
        )
        return

    replied_msg_id = msg.reply_to_message.message_id
    mapping = await db.get_user_by_admin_msg(replied_msg_id)

    if not mapping:
        await msg.reply_text(
            "⚠️ <b>خطا:</b> اطلاعات کاربری برای این پیام یافت نشد (ممکن است پیام قدیمی باشد یا در دیتابیس ثبت نشده باشد).\n\n"
            "می‌توانید با دستور <code>/send &lt;user_id&gt; &lt;متن&gt;</code> مستقیماً به کاربر پیام دهید.",
            parse_mode=ParseMode.HTML
        )
        return

    target_user_id = mapping["user_id"]
    target_msg_id = mapping["user_message_id"]

    # Deliver reply to target user
    try:
        # If admin sent pure text
        if msg.text and not msg.photo and not msg.video and not msg.voice and not msg.audio and not msg.document:
            reply_text = (
                "💬 <b>پاسخ پشتیبانی آسان وی‌پی‌ان:</b>\n"
                "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
                f"{html.escape(msg.text)}\n\n"
                "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
                "<i>در صورتی که سوال یا ابهام دیگری دارید، می‌توانید همین‌جا ارسال نمایید.</i>"
            )
            try:
                sent_msg = await context.bot.send_message(
                    chat_id=target_user_id,
                    text=reply_text,
                    parse_mode=ParseMode.HTML,
                    reply_to_message_id=target_msg_id
                )
            except BadRequest:
                # If original message was deleted by user, send without reply_to_message_id
                sent_msg = await context.bot.send_message(
                    chat_id=target_user_id,
                    text=reply_text,
                    parse_mode=ParseMode.HTML
                )
        else:
            # Media message (voice, photo, video, document, audio, sticker)
            header_text = "💬 <b>پاسخ پشتیبانی آسان وی‌پی‌ان:</b>"
            await context.bot.send_message(chat_id=target_user_id, text=header_text, parse_mode=ParseMode.HTML)
            sent_msg = await context.bot.copy_message(
                chat_id=target_user_id,
                from_chat_id=user.id,
                message_id=msg.message_id
            )

        # Record outbound mapping
        await db.save_mapping(msg.message_id, target_user_id, sent_msg.message_id, direction="outbound")

        user_info = await db.get_user_info(target_user_id)
        user_name = user_info["first_name"] if user_info and user_info.get("first_name") else str(target_user_id)

        await msg.reply_text(
            f"✅ <b>پاسخ شما با موفقیت برای {html.escape(user_name)} (<code>{target_user_id}</code>) ارسال شد.</b>",
            parse_mode=ParseMode.HTML
        )

    except Forbidden:
        await msg.reply_text(
            f"❌ <b>ارسال ناموفق:</b> کاربر <code>{target_user_id}</code> ربات پشتیبانی را مسدود (بلاک) کرده است.",
            parse_mode=ParseMode.HTML
        )
    except Exception as e:
        logger.error(f"Failed to send admin reply to {target_user_id}: {e}")
        await msg.reply_text(f"❌ <b>خطا در ارسال پیام به کاربر:</b> {e}", parse_mode=ParseMode.HTML)

# ================= ADMIN COMMANDS ================= #

async def cmd_stats(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_admin(update.effective_user.id):
        return

    stats = await db.get_stats()
    text = (
        "📊 <b>آمار سامانه پشتیبانی آسان وی‌پی‌ان</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        f"👥 <b>تعداد کل کاربران:</b> {stats['total_users']}\n"
        f"🚫 <b>کاربران مسدود:</b> {stats['blocked_users']}\n"
        f"📥 <b>پیام‌های دریافتی:</b> {stats['inbound_messages']}\n"
        f"📤 <b>پاسخ‌های ارسالی پشتیبانی:</b> {stats['outbound_messages']}\n"
        f"🔄 <b>کل تبادلات:</b> {stats['total_messages']}\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦"
    )
    await update.message.reply_text(text, parse_mode=ParseMode.HTML)

async def cmd_help(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_admin(update.effective_user.id):
        return

    text = (
        "📖 <b>راهنمای جامع مدیریت پشتیبانی</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        "1️⃣ <b>پاسخ به پیام‌ها:</b>\n"
        "کافیست مستقیماً روی هر پیام یا کارت اطلاعات کاربر، کلید Reply را بزنید و پیام متنی یا ویس ارسال کنید.\n\n"
        "2️⃣ <b>ارسال مستقیم پیام به کاربر با آیدی عددی:</b>\n"
        "<code>/send 123456789 متن پیام شما</code>\n\n"
        "3️⃣ <b>مسدود کردن کاربر مزاحم:</b>\n"
        "<code>/block 123456789 ارسال اسپم</code>\n\n"
        "4️⃣ <b>رفع مسدودیت:</b>\n"
        "<code>/unblock 123456789</code>\n\n"
        "5️⃣ <b>استعلام مشخصات کاربر:</b>\n"
        "<code>/info 123456789</code>\n\n"
        "6️⃣ <b>لیست کاربران مسدود شده:</b>\n"
        "<code>/blocked</code>\n\n"
        "7️⃣ <b>ارسال همگانی به همه مراجعین:</b>\n"
        "<code>/broadcast متن پیام همگانی</code>"
    )
    await update.message.reply_text(text, parse_mode=ParseMode.HTML)

async def cmd_send(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_admin(update.effective_user.id):
        return

    if not context.args or len(context.args) < 2:
        await update.message.reply_text(
            "⚠️ فرمت دستور نادرست است.\n"
            "الگو: <code>/send &lt;user_id&gt; &lt;متن پیام&gt;</code>",
            parse_mode=ParseMode.HTML
        )
        return

    raw_target = context.args[0]
    if not raw_target.isdigit():
        await update.message.reply_text("⚠️ شناسه عددی نامعتبر است.")
        return

    target_user_id = int(raw_target)
    message_text = " ".join(context.args[1:])

    text_to_user = (
        "💬 <b>پیام از پشتیبانی آسان وی‌پی‌ان:</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        f"{html.escape(message_text)}\n\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        "<i>برای پاسخ به این پیام، می‌توانید همین‌جا پاسخ دهید.</i>"
    )

    try:
        sent_msg = await context.bot.send_message(
            chat_id=target_user_id,
            text=text_to_user,
            parse_mode=ParseMode.HTML
        )
        await db.save_mapping(update.message.message_id, target_user_id, sent_msg.message_id, direction="outbound")
        await update.message.reply_text(f"✅ پیام با موفقیت به کاربر <code>{target_user_id}</code> ارسال گردید.", parse_mode=ParseMode.HTML)
    except Forbidden:
        await update.message.reply_text(f"❌ کاربر <code>{target_user_id}</code> ربات را بلاک کرده است.", parse_mode=ParseMode.HTML)
    except Exception as e:
        await update.message.reply_text(f"❌ خطا در ارسال پیام: {e}", parse_mode=ParseMode.HTML)

async def cmd_block(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_admin(update.effective_user.id):
        return

    if not context.args or len(context.args) < 1:
        await update.message.reply_text("⚠️ الگو: <code>/block &lt;user_id&gt; [دلیل]</code>", parse_mode=ParseMode.HTML)
        return

    if not context.args[0].isdigit():
        await update.message.reply_text("⚠️ شناسه عددی نامعتبر است.")
        return

    target_user_id = int(context.args[0])
    reason = " ".join(context.args[1:]) if len(context.args) > 1 else "توسط مدیر مسدود شد"

    await db.block_user(target_user_id, reason)
    await update.message.reply_text(
        f"🚫 کاربر <code>{target_user_id}</code> با موفقیت مسدود شد.\nدلیل: {html.escape(reason)}",
        parse_mode=ParseMode.HTML
    )

async def cmd_unblock(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_admin(update.effective_user.id):
        return

    if not context.args or not context.args[0].isdigit():
        await update.message.reply_text("⚠️ الگو: <code>/unblock &lt;user_id&gt;</code>", parse_mode=ParseMode.HTML)
        return

    target_user_id = int(context.args[0])
    await db.unblock_user(target_user_id)
    await update.message.reply_text(
        f"✅ کاربر <code>{target_user_id}</code> با موفقیت رفع مسدودیت شد.",
        parse_mode=ParseMode.HTML
    )

async def cmd_blocked(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_admin(update.effective_user.id):
        return

    blocked_list = await db.get_blocked_users()
    if not blocked_list:
        await update.message.reply_text("✅ هیچ کاربری در لیست مسدودها قرار ندارد.")
        return

    text = "🚫 <b>لیست کاربران مسدود شده:</b>\n✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
    for u in blocked_list:
        uid = u["user_id"]
        fn = html.escape(u.get("first_name") or "")
        un = f"@{u['username']}" if u.get("username") else "ندارد"
        reason = html.escape(u.get("block_reason") or "بدون توضیح")
        text += f"• <code>{uid}</code> | {fn} ({un})\n  دلیل: {reason}\n  رفع مسدودیت: <code>/unblock {uid}</code>\n\n"

    await update.message.reply_text(text, parse_mode=ParseMode.HTML)

async def cmd_info(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_admin(update.effective_user.id):
        return

    if not context.args or not context.args[0].isdigit():
        await update.message.reply_text("⚠️ الگو: <code>/info &lt;user_id&gt;</code>", parse_mode=ParseMode.HTML)
        return

    target_user_id = int(context.args[0])
    info = await db.get_user_info(target_user_id)
    if not info:
        await update.message.reply_text("⚠️ کاربر مورد نظر در دیتابیس یافت نشد.")
        return

    fn = html.escape(f"{info.get('first_name') or ''} {info.get('last_name') or ''}".strip() or "بدون نام")
    un = f"@{info['username']}" if info.get("username") else "ندارد"
    status_str = "🚫 مسدود" if info.get("is_blocked") else "✅ فعال"

    text = (
        f"👤 <b>اطلاعات کاربر:</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
        f"🔢 <b>شناسه عددی:</b> <code>{info['user_id']}</code>\n"
        f"👤 <b>نام:</b> {fn}\n"
        f"🆔 <b>نام‌کاربری:</b> {un}\n"
        f"🚦 <b>وضعیت:</b> {status_str}\n"
        f"📥 <b>پیام‌های ارسالی کاربر:</b> {info['inbound_messages']}\n"
        f"📤 <b>پاسخ‌های داده شده به کاربر:</b> {info['outbound_messages']}\n"
        f"🕒 <b>تاریخ اولین مراجعه:</b> {info.get('created_at')}\n"
        f"🕒 <b>آخرین فعالیت:</b> {info.get('last_active')}\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦"
    )

    keyboard = InlineKeyboardMarkup([
        [
            InlineKeyboardButton(
                "✅ رفع مسدودیت" if info.get("is_blocked") else "🚫 مسدود کردن",
                callback_data=f"adm_toggle_block_{target_user_id}"
            )
        ]
    ])

    await update.message.reply_text(text, parse_mode=ParseMode.HTML, reply_markup=keyboard)

async def cmd_broadcast(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_admin(update.effective_user.id):
        return

    if not context.args:
        await update.message.reply_text("⚠️ الگو: <code>/broadcast &lt;متن پیام&gt;</code>", parse_mode=ParseMode.HTML)
        return

    broadcast_text = " ".join(context.args)
    user_ids = await db.get_all_user_ids()
    total = len(user_ids)

    if total == 0:
        await update.message.reply_text("⚠️ کاربری برای ارسال پیام یافت نشد.")
        return

    status_msg = await update.message.reply_text(f"⏳ در حال ارسال همگانی به {total} کاربر...")

    success = 0
    failed = 0

    formatted = (
        "📢 <b>پیام همگانی پشتیبانی آسان وی‌پی‌ان</b>\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        f"{html.escape(broadcast_text)}\n\n"
        "✦ ━━━━━━━━━━━━━━━━━ ✦"
    )

    for uid in user_ids:
        try:
            await context.bot.send_message(chat_id=uid, text=formatted, parse_mode=ParseMode.HTML)
            success += 1
            await asyncio.sleep(0.05)
        except Exception:
            failed += 1

    await status_msg.edit_text(
        f"✅ <b>ارسال همگانی پایان یافت.</b>\n"
        f"👥 کل مخاطبان: {total}\n"
        f"✔️ موفق: {success}\n"
        f"❌ ناموفق: {failed}",
        parse_mode=ParseMode.HTML
    )

# ================= CALLBACK QUERY HANDLERS ================= #

async def handle_callback_query(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    data = query.data or ""
    user_id = update.effective_user.id

    if not is_admin(user_id):
        return

    if data == "adm_refresh_stats":
        stats = await db.get_stats()
        text = (
            "📊 <b>آمار سامانه پشتیبانی آسان وی‌پی‌ان (بروزرسانی شد)</b>\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
            f"👥 <b>تعداد کل کاربران:</b> {stats['total_users']}\n"
            f"🚫 <b>کاربران مسدود:</b> {stats['blocked_users']}\n"
            f"📥 <b>پیام‌های دریافتی:</b> {stats['inbound_messages']}\n"
            f"📤 <b>پاسخ‌های ارسالی پشتیبانی:</b> {stats['outbound_messages']}\n"
            f"🔄 <b>کل تبادلات:</b> {stats['total_messages']}\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦"
        )
        keyboard = InlineKeyboardMarkup([
            [
                InlineKeyboardButton("📊 بروزرسانی مجدد", callback_data="adm_refresh_stats"),
                InlineKeyboardButton("🚫 کاربران مسدود", callback_data="adm_list_blocked")
            ]
        ])
        await query.edit_message_text(text, parse_mode=ParseMode.HTML, reply_markup=keyboard)

    elif data == "adm_list_blocked":
        blocked_list = await db.get_blocked_users()
        if not blocked_list:
            await query.message.reply_text("✅ هیچ کاربری در لیست مسدودها قرار ندارد.")
            return

        text = "🚫 <b>لیست کاربران مسدود شده:</b>\n✦ ━━━━━━━━━━━━━━━━━ ✦\n\n"
        for u in blocked_list:
            uid = u["user_id"]
            fn = html.escape(u.get("first_name") or "")
            un = f"@{u['username']}" if u.get("username") else "ندارد"
            reason = html.escape(u.get("block_reason") or "بدون توضیح")
            text += f"• <code>{uid}</code> | {fn} ({un})\n  دلیل: {reason}\n  رفع مسدودیت: <code>/unblock {uid}</code>\n\n"

        await query.message.reply_text(text, parse_mode=ParseMode.HTML)

    elif data.startswith("adm_user_info_"):
        target_uid = int(data.replace("adm_user_info_", ""))
        info = await db.get_user_info(target_uid)
        if not info:
            await query.message.reply_text("⚠️ اطلاعات کاربر یافت نشد.")
            return

        fn = html.escape(f"{info.get('first_name') or ''} {info.get('last_name') or ''}".strip() or "بدون نام")
        un = f"@{info['username']}" if info.get("username") else "ندارد"
        status_str = "🚫 مسدود" if info.get("is_blocked") else "✅ فعال"

        text = (
            f"👤 <b>اطلاعات کاربر:</b>\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦\n"
            f"🔢 <b>شناسه عددی:</b> <code>{info['user_id']}</code>\n"
            f"👤 <b>نام:</b> {fn}\n"
            f"🆔 <b>نام‌کاربری:</b> {un}\n"
            f"🚦 <b>وضعیت:</b> {status_str}\n"
            f"📥 <b>پیام‌های دریافتی:</b> {info['inbound_messages']}\n"
            f"📤 <b>پاسخ‌ها:</b> {info['outbound_messages']}\n"
            f"🕒 <b>تاریخ عضویت:</b> {info.get('created_at')}\n"
            "✦ ━━━━━━━━━━━━━━━━━ ✦"
        )
        await query.message.reply_text(text, parse_mode=ParseMode.HTML)

    elif data.startswith("adm_block_"):
        target_uid = int(data.replace("adm_block_", ""))
        await db.block_user(target_uid, "توسط دکمه مسدودسازی")
        await query.message.reply_text(f"🚫 کاربر <code>{target_uid}</code> مسدود شد.", parse_mode=ParseMode.HTML)

    elif data.startswith("adm_toggle_block_"):
        target_uid = int(data.replace("adm_toggle_block_", ""))
        is_b = await db.is_blocked(target_uid)
        if is_b:
            await db.unblock_user(target_uid)
            await query.message.reply_text(f"✅ کاربر <code>{target_uid}</code> رفع مسدود شد.", parse_mode=ParseMode.HTML)
        else:
            await db.block_user(target_uid, "توسط مدیر")
            await query.message.reply_text(f"🚫 کاربر <code>{target_uid}</code> مسدود شد.", parse_mode=ParseMode.HTML)

# ================= BOT INITIALIZATION ================= #

async def post_init(application):
    await db.init_db()

    # Commands for general users
    user_commands = [
        BotCommand("start", "🚀 شروع و ارسال پیام به پشتیبانی")
    ]
    await application.bot.set_my_commands(user_commands, scope=BotCommandScopeDefault())

    # Commands for admin
    admin_commands = [
        BotCommand("start", "🛡️ پنل مدیریت پشتیبانی"),
        BotCommand("stats", "📊 آمار پیام‌ها و کاربران"),
        BotCommand("blocked", "🚫 لیست مسدود شدگان"),
        BotCommand("help", "❓ راهنمای فرامین")
    ]
    for admin_id in ADMIN_IDS:
        try:
            await application.bot.set_my_commands(admin_commands, scope=BotCommandScopeChat(chat_id=admin_id))
        except Exception as e:
            logger.warning(f"Could not set admin commands for {admin_id}: {e}")

    logger.info("Bot commands configured.")

def main():
    logger.info("Starting Asan VPN Support Bot...")
    app = ApplicationBuilder().token(BOT_TOKEN).post_init(post_init).build()

    # Admin commands
    app.add_handler(CommandHandler("start", user_start))
    app.add_handler(CommandHandler("help", cmd_help))
    app.add_handler(CommandHandler("stats", cmd_stats))
    app.add_handler(CommandHandler("send", cmd_send))
    app.add_handler(CommandHandler("block", cmd_block))
    app.add_handler(CommandHandler("unblock", cmd_unblock))
    app.add_handler(CommandHandler("blocked", cmd_blocked))
    app.add_handler(CommandHandler("info", cmd_info))
    app.add_handler(CommandHandler("broadcast", cmd_broadcast))

    # Callback queries
    app.add_handler(CallbackQueryHandler(handle_callback_query))

    # Admin replies router
    admin_filter = filters.User(user_id=ADMIN_IDS)
    app.add_handler(MessageHandler(admin_filter & ~filters.COMMAND, admin_message_router))

    # User messages router (non-admin, all content types)
    app.add_handler(MessageHandler(~admin_filter & ~filters.COMMAND, handle_user_incoming_message))

    app.run_polling(drop_pending_updates=False)

if __name__ == "__main__":
    main()
