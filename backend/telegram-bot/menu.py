#!/usr/bin/env python3
"""
AsanVPN Terminal Management CLI (asanbot)
Command Line Interface to manage plans, settings, backups, services, and statistics.
English terminal edition.
"""

import os
import sys
import time
import json
import sqlite3
import subprocess
import urllib.request
from datetime import datetime

# Path configuration
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

from config import DB_PATH, BOT_TOKEN, WORKER_URL, ADMIN_TOKEN

CHANNEL_BACKUP_ID = "-1004389472709"

# Colors for terminal output
CYAN = "\033[96m"
GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
BOLD = "\033[1m"
RESET = "\033[0m"

def clear_screen():
    try:
        os.system("clear" if os.name != "nt" else "cls")
    except Exception:
        pass

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

# ---------------- CLI ACTIONS ----------------

def show_stats():
    clear_screen()
    print(f"{CYAN}{BOLD}=== [ AsanVPN System Statistics & Financial Reports ] ==={RESET}\n")
    try:
        conn = get_db()
        cur = conn.cursor()

        cur.execute("SELECT COUNT(*) FROM users")
        total_users = cur.fetchone()[0]

        cur.execute("SELECT COUNT(*) FROM orders")
        total_orders = cur.fetchone()[0]

        cur.execute("SELECT COUNT(*), COALESCE(SUM(amount_toman), 0) FROM orders WHERE status = 'paid'")
        row = cur.fetchone()
        paid_orders = row[0]
        total_revenue = row[1]

        now = int(time.time())
        today_start = now - (now % 86400)
        cur.execute("SELECT COUNT(*), COALESCE(SUM(amount_toman), 0) FROM orders WHERE status = 'paid' AND paid_at >= ?", (today_start,))
        row_today = cur.fetchone()

        cur.execute("SELECT COUNT(*) FROM plans WHERE is_active = 1")
        active_plans = cur.fetchone()[0]

        print(f"  {BOLD}Total Registered Users:{RESET}  {GREEN}{total_users:,}{RESET}")
        print(f"  {BOLD}Total Orders Created:{RESET}    {total_orders:,}")
        print(f"  {BOLD}Paid (Successful) Orders:{RESET}{GREEN}{paid_orders:,}{RESET}")
        print(f"  {BOLD}Total Revenue:{RESET}           {YELLOW}{total_revenue:,} Toman{RESET}")
        print(f"  {BOLD}Today's Revenue:{RESET}         {GREEN}{row_today[1]:,} Toman{RESET} ({row_today[0]} paid transactions)")
        print(f"  {BOLD}Active Sales Plans:{RESET}      {active_plans}")

        print(f"\n{BOLD}--- Latest 5 Paid Orders ---{RESET}")
        cur.execute("SELECT order_id, telegram_id, amount_toman, assigned_pin, tracking_code, paid_at FROM orders WHERE status = 'paid' ORDER BY paid_at DESC LIMIT 5")
        recent = cur.fetchall()
        if not recent:
            print("  (No paid orders yet)")
        else:
            for r in recent:
                paid_time = datetime.fromtimestamp(r["paid_at"]).strftime("%Y-%m-%d %H:%M") if r["paid_at"] else "-"
                print(f"  PIN: {BOLD}{r['assigned_pin']}{RESET} | Amount: {r['amount_toman']:,} T | User: {r['telegram_id']} | Track: {r['tracking_code']} | Date: {paid_time}")

        conn.close()
    except Exception as e:
        print(f"{RED}Error fetching stats: {e}{RESET}")

    input(f"\n{CYAN}Press [Enter] to return to main menu...{RESET}")

def manage_plans():
    while True:
        clear_screen()
        print(f"{CYAN}{BOLD}=== [ Sales Plans Management ] ==={RESET}\n")
        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT * FROM plans ORDER BY sort_order ASC, price_toman ASC")
        plans = cur.fetchall()

        if not plans:
            print(f"{YELLOW}No plans found in database.{RESET}")
        else:
            print(f"{BOLD}{'#':<3} {'ID':<14} {'Title':<24} {'Traffic':<10} {'Days':<8} {'Price (Toman)':<15} {'Devices':<9} {'Status':<8}{RESET}")
            print("-" * 95)
            for idx, p in enumerate(plans, 1):
                status_str = f"{GREEN}ACTIVE{RESET}" if p["is_active"] == 1 else f"{RED}INACTIVE{RESET}"
                traffic_str = "Unlimited" if p["traffic_gb"] == 0 else f"{p['traffic_gb']} GB"
                print(f"{idx:<3} {p['id']:<14} {p['title']:<24} {traffic_str:<10} {p['days']}d      {p['price_toman']:<15,} {p['max_devices']:<9} {status_str}")

        print("\n" + "=" * 55)
        print("  [1] Add New Plan")
        print("  [2] Edit Plan Price")
        print("  [3] Toggle Plan Active / Inactive")
        print("  [4] Delete Plan")
        print("  [0] Back to Main Menu")
        print("=" * 55)

        choice = input(f"{BOLD}Select an option [0-4]: {RESET}").strip()

        if choice == "0":
            conn.close()
            break
        elif choice == "1":
            try:
                pid = input("Plan unique ID (e.g. plan_50g): ").strip()
                if not pid:
                    continue
                title = input("Plan Title (e.g. 50 GB - 30 Days): ").strip()
                traffic = int(input("Traffic in GB (number, 0 for unlimited) [default 30]: ").strip() or "30")
                days = int(input("Validity in Days [default 30]: ").strip() or "30")
                price = int(input("Price in Toman (e.g. 150000): ").strip())
                devices = int(input("Max concurrent devices [default 2]: ").strip() or "2")
                sort_order = int(input("Display sort order [default 5]: ").strip() or "5")

                cur.execute("""
                    INSERT INTO plans (id, title, days, traffic_gb, max_devices, price_toman, is_active, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """, (pid, title, days, traffic, devices, price, sort_order))
                conn.commit()
                print(f"\n{GREEN}Plan '{pid}' created successfully.{RESET}")
                time.sleep(1.5)
            except Exception as e:
                print(f"\n{RED}Error creating plan: {e}{RESET}")
                time.sleep(2)
        elif choice == "2":
            pid = input("Enter Plan ID or # to update price: ").strip()
            target_pid = pid
            if pid.isdigit() and 1 <= int(pid) <= len(plans):
                target_pid = plans[int(pid) - 1]["id"]

            new_price = input(f"New price for '{target_pid}' in Toman: ").strip()
            if target_pid and new_price.isdigit():
                cur.execute("UPDATE plans SET price_toman = ? WHERE id = ?", (int(new_price), target_pid))
                conn.commit()
                print(f"\n{GREEN}Price for '{target_pid}' updated to {int(new_price):,} Toman.{RESET}")
                time.sleep(1.5)
            else:
                print(f"\n{RED}Invalid input.{RESET}")
                time.sleep(1.5)
        elif choice == "3":
            pid = input("Enter Plan ID or # to toggle active/inactive: ").strip()
            target_pid = pid
            if pid.isdigit() and 1 <= int(pid) <= len(plans):
                target_pid = plans[int(pid) - 1]["id"]

            cur.execute("SELECT is_active, title FROM plans WHERE id = ?", (target_pid,))
            row = cur.fetchone()
            if row:
                new_st = 0 if row[0] == 1 else 1
                cur.execute("UPDATE plans SET is_active = ? WHERE id = ?", (new_st, target_pid))
                conn.commit()
                status_txt = "ACTIVE" if new_st == 1 else "INACTIVE"
                print(f"\n{GREEN}Plan '{target_pid}' status changed to {status_txt}.{RESET}")
            else:
                print(f"\n{RED}Plan '{target_pid}' not found.{RESET}")
            time.sleep(1.5)
        elif choice == "4":
            pid = input("Enter Plan ID or # to delete permanently (or 0 to cancel): ").strip()
            if not pid or pid == "0":
                conn.close()
                continue
            target_p = None
            if pid.isdigit() and 1 <= int(pid) <= len(plans):
                target_p = plans[int(pid) - 1]
            else:
                for p in plans:
                    if p["id"].lower() == pid.lower():
                        target_p = p
                        break
            if not target_p:
                print(f"\n{RED}Plan '{pid}' not found.{RESET}")
                time.sleep(1.5)
            else:
                target_id = target_p["id"]
                confirm = input(f"Are you sure you want to permanently DELETE '{target_p['title']}' ({target_id})? (y/n): ").strip().lower()
                if confirm in ("y", "yes"):
                    cur.execute("DELETE FROM plans WHERE id = ?", (target_id,))
                    conn.commit()
                    print(f"\n{GREEN}Plan '{target_id}' deleted successfully.{RESET}")
                    time.sleep(1.5)

        conn.close()

def manage_settings():
    while True:
        clear_screen()
        print(f"{CYAN}{BOLD}=== [ Bot Configuration & Notifications ] ==={RESET}\n")
        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT key, value FROM settings")
        settings = {r["key"]: r["value"] for r in cur.fetchall()}

        sale_notif = f"{GREEN}ON{RESET}" if settings.get("notify_admin_on_sale") == "1" else f"{RED}OFF{RESET}"
        renew_notif = f"{GREEN}ON{RESET}" if settings.get("notify_admin_on_renew") == "1" else f"{RED}OFF{RESET}"
        maint_mode = f"{RED}ENABLED (Sales Blocked){RESET}" if settings.get("maintenance_mode") == "1" else f"{GREEN}DISABLED (Normal Operations){RESET}"

        print(f"  [1] Edit Welcome Message (/start text)")
        print(f"  [2] Support Username:       {BOLD}{settings.get('support_username', '@Sib_Support_P')}{RESET}")
        print(f"  [3] App Download URL:       {BOLD}{settings.get('app_download_url', '')}{RESET}")
        print(f"  [4] Admin Sale Notification:  {sale_notif}")
        print(f"  [5] Admin Renew Notification: {renew_notif}")
        print(f"  [6] Maintenance Mode:         {maint_mode}")
        print(f"  [0] Back to Main Menu")
        print("=" * 55)

        choice = input(f"{BOLD}Select an option [0-6]: {RESET}").strip()

        if choice == "0":
            conn.close()
            break
        elif choice == "1":
            print(f"\n{BOLD}Current Welcome Message:{RESET}")
            print(settings.get("welcome_message", ""))
            print("\nEnter new welcome message (or leave empty to cancel):")
            new_text = input("> ").strip()
            if new_text:
                cur.execute("UPDATE settings SET value = ? WHERE key = 'welcome_message'", (new_text,))
                conn.commit()
                print(f"\n{GREEN}Welcome message updated successfully.{RESET}")
                time.sleep(1.5)
        elif choice == "2":
            new_supp = input("Enter new Support Telegram username (e.g. @Sib_Support_P): ").strip()
            if new_supp:
                cur.execute("UPDATE settings SET value = ? WHERE key = 'support_username'", (new_supp,))
                conn.commit()
                print(f"\n{GREEN}Support username updated to '{new_supp}'.{RESET}")
                time.sleep(1.5)
        elif choice == "3":
            new_url = input("Enter new Android App download URL: ").strip()
            if new_url:
                cur.execute("UPDATE settings SET value = ? WHERE key = 'app_download_url'", (new_url,))
                conn.commit()
                print(f"\n{GREEN}App download URL updated successfully.{RESET}")
                time.sleep(1.5)
        elif choice == "4":
            cur_val = settings.get("notify_admin_on_sale", "1")
            new_val = "0" if cur_val == "1" else "1"
            cur.execute("UPDATE settings SET value = ? WHERE key = 'notify_admin_on_sale'", (new_val,))
            conn.commit()
            status_txt = "ON" if new_val == "1" else "OFF"
            print(f"\n{GREEN}Admin sale notification set to {status_txt}.{RESET}")
            time.sleep(1)
        elif choice == "5":
            cur_val = settings.get("notify_admin_on_renew", "1")
            new_val = "0" if cur_val == "1" else "1"
            cur.execute("UPDATE settings SET value = ? WHERE key = 'notify_admin_on_renew'", (new_val,))
            conn.commit()
            status_txt = "ON" if new_val == "1" else "OFF"
            print(f"\n{GREEN}Admin renewal notification set to {status_txt}.{RESET}")
            time.sleep(1)
        elif choice == "6":
            cur_val = settings.get("maintenance_mode", "0")
            new_val = "0" if cur_val == "1" else "1"
            cur.execute("UPDATE settings SET value = ? WHERE key = 'maintenance_mode'", (new_val,))
            conn.commit()
            status_txt = "ENABLED (Store Locked)" if new_val == "1" else "DISABLED (Store Normal)"
            print(f"\n{GREEN}Maintenance mode set to {status_txt}.{RESET}")
            time.sleep(1.5)

        conn.close()

def send_instant_backup():
    clear_screen()
    print(f"{CYAN}{BOLD}=== [ Instant Database Backup to Telegram Channel ] ==={RESET}\n")
    print(f"Target Backup Channel: {BOLD}{CHANNEL_BACKUP_ID}{RESET} (https://t.me/+HPRtyAF4Q6k1N2Rk)")
    print("Creating safe SQLite snapshot...")

    timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    backup_file = f"/tmp/asanvpn_db_{timestamp}.sqlite"

    try:
        src = sqlite3.connect(DB_PATH)
        dst = sqlite3.connect(backup_file)
        with dst:
            src.backup(dst)
        dst.close()
        src.close()
        file_size = os.path.getsize(backup_file)
        print(f"{GREEN}Database snapshot created: {backup_file} ({file_size:,} bytes){RESET}")
        print("Uploading to Telegram channel...")

        url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendDocument"

        with open(backup_file, "rb") as f:
            file_data = f.read()

        boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        body = []
        body.append(f"--{boundary}".encode())
        body.append(b'Content-Disposition: form-data; name="chat_id"')
        body.append(b'')
        body.append(CHANNEL_BACKUP_ID.encode())

        caption = f"📦 <b>Manual Database Backup (asanbot CLI)</b>\n📅 Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S UTC')}\nSize: {file_size:,} bytes\n#cli_backup"
        body.append(f"--{boundary}".encode())
        body.append(b'Content-Disposition: form-data; name="caption"')
        body.append(b'')
        body.append(caption.encode('utf-8'))

        body.append(f"--{boundary}".encode())
        body.append(b'Content-Disposition: form-data; name="parse_mode"')
        body.append(b'')
        body.append(b'HTML')

        body.append(f"--{boundary}".encode())
        body.append(f'Content-Disposition: form-data; name="document"; filename="{os.path.basename(backup_file)}"'.encode())
        body.append(b'Content-Type: application/octet-stream')
        body.append(b'')
        body.append(file_data)
        body.append(f"--{boundary}--".encode())
        body.append(b'')

        req_body = b"\r\n".join(body)
        req = urllib.request.Request(
            url,
            data=req_body,
            headers={
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "User-Agent": "AsanVpn-TerminalCLI/1.0"
            }
        )

        with urllib.request.urlopen(req) as resp:
            resp_data = json.loads(resp.read().decode())
            if resp_data.get("ok"):
                print(f"\n{GREEN}{BOLD}SUCCESS: Backup snapshot successfully uploaded to Telegram channel!{RESET}")
            else:
                print(f"\n{RED}Telegram API error: {resp_data}{RESET}")

    except Exception as e:
        print(f"\n{RED}Backup failed: {e}{RESET}")

    input(f"\n{CYAN}Press [Enter] to return to main menu...{RESET}")

def manage_service():
    while True:
        clear_screen()
        print(f"{CYAN}{BOLD}=== [ Systemd Service Control (asanvpn-bot.service) ] ==={RESET}\n")
        print("  [1] Service Status (systemctl status asanvpn-bot)")
        print("  [2] Restart Service (systemctl restart asanvpn-bot)")
        print("  [3] Stop Service (systemctl stop asanvpn-bot)")
        print("  [4] Start Service (systemctl start asanvpn-bot)")
        print("  [0] Back to Main Menu")
        print("=" * 55)

        ch = input(f"\n{BOLD}Select an option [0-4]: {RESET}").strip()
        if ch == "0":
            break
        elif ch == "1":
            subprocess.run(["systemctl", "status", "asanvpn-bot.service", "--no-pager"])
            input(f"\n{CYAN}Press [Enter] to continue...{RESET}")
        elif ch == "2":
            print("Restarting asanvpn-bot.service...")
            subprocess.run(["systemctl", "restart", "asanvpn-bot.service"])
            print(f"{GREEN}Service restarted successfully.{RESET}")
            time.sleep(1.5)
        elif ch == "3":
            print("Stopping asanvpn-bot.service...")
            subprocess.run(["systemctl", "stop", "asanvpn-bot.service"])
            print(f"{YELLOW}Service stopped.{RESET}")
            time.sleep(1.5)
        elif ch == "4":
            print("Starting asanvpn-bot.service...")
            subprocess.run(["systemctl", "start", "asanvpn-bot.service"])
            print(f"{GREEN}Service started.{RESET}")
            time.sleep(1.5)

def view_live_logs():
    clear_screen()
    print(f"{CYAN}{BOLD}=== [ Live Service Logs (Press Ctrl+C to Exit) ] ==={RESET}\n")
    try:
        subprocess.run(["journalctl", "-u", "asanvpn-bot.service", "-n", "40", "-f"])
    except KeyboardInterrupt:
        pass

def broadcast_message():
    clear_screen()
    print(f"{CYAN}{BOLD}=== [ Broadcast Message to All Telegram Users ] ==={RESET}\n")
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT telegram_id FROM users")
    users = [r[0] for r in cur.fetchall()]
    conn.close()

    print(f"Total registered recipients: {BOLD}{len(users)}{RESET}")
    if not users:
        print(f"\n{YELLOW}No users found in database.{RESET}")
        input(f"\n{CYAN}Press [Enter] to return...{RESET}")
        return

    print("\nEnter message text (supports HTML tags: <b>bold</b>, <i>italic</i>, <code>code</code>):")
    print("(Leave empty to cancel)")
    msg = input("> ").strip()
    if not msg:
        return

    confirm = input(f"\nAre you sure you want to broadcast this message to {len(users)} users? (y/n): ").strip().lower()
    if confirm not in ("y", "yes"):
        return

    print("\nBroadcasting messages...")
    success = 0
    fail = 0
    for uid in users:
        try:
            url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage"
            payload = json.dumps({"chat_id": uid, "text": msg, "parse_mode": "HTML"}).encode('utf-8')
            req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=5) as r:
                success += 1
            time.sleep(0.05)
        except Exception:
            fail += 1

    print(f"\n{GREEN}Broadcast finished: {success} delivered, {fail} failed.{RESET}")
    input(f"\n{CYAN}Press [Enter] to return to main menu...{RESET}")

def main_menu():
    while True:
        clear_screen()
        print(f"{CYAN}{BOLD}======================================================={RESET}")
        print(f"{CYAN}{BOLD}       🛡️   AsanVPN Terminal Management CLI (asanbot)  🛡️     {RESET}")
        print(f"{CYAN}{BOLD}======================================================={RESET}")
        print("  [1] Statistics & Revenue Reports")
        print("  [2] Sales Plans Management (Add, Edit Price, Toggle, Delete)")
        print("  [3] Bot Configuration (Welcome Message, Support, App URL)")
        print("  [4] Instant Database Backup (Upload to Telegram Channel)")
        print("  [5] Systemd Service Control (Status, Restart, Stop, Start)")
        print("  [6] View Live Service Logs (journalctl -f)")
        print("  [7] Broadcast Message to All Users")
        print("  [0] Exit")
        print(f"{CYAN}{BOLD}======================================================={RESET}")

        ch = input(f"{BOLD}Enter choice [0-7]: {RESET}").strip()

        if ch == "0":
            print("\nGoodbye!\n")
            break
        elif ch == "1":
            show_stats()
        elif ch == "2":
            manage_plans()
        elif ch == "3":
            manage_settings()
        elif ch == "4":
            send_instant_backup()
        elif ch == "5":
            manage_service()
        elif ch == "6":
            view_live_logs()
        elif ch == "7":
            broadcast_message()

if __name__ == "__main__":
    try:
        main_menu()
    except (KeyboardInterrupt, EOFError):
        print("\nExiting.\n")
