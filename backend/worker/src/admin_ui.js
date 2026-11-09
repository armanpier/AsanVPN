/**
 * Embedded Web Admin Dashboard for AsanVPN
 * Served directly by the Cloudflare Worker at /admin or /
 */

export function getAdminDashboardHtml() {
  return `<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
  <title>پنل مدیریت آسان وی‌پی‌ان | AsanVPN Dashboard</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
  <script src="https://cdn.jsdelivr.net/npm/sweetalert2@11"></script>
  <style>
    @import url('https://fonts.googleapis.com/css2?family=Vazirmatn:wght@300;400;500;700;800&display=swap');
    *, *::before, *::after {
      box-sizing: border-box;
    }
    html {
      overflow-x: hidden;
      width: 100%;
      height: 100%;
    }
    body {
      overflow-x: hidden !important;
      width: 100% !important;
      max-width: 100% !important;
      margin: 0;
      padding: 0;
      font-family: 'Vazirmatn', -apple-system, BlinkMacSystemFont, sans-serif;
      background-color: #0B1120;
      color: #F8FAFC;
      position: relative;
    }
    .glass-card {
      background: rgba(30, 41, 59, 0.7);
      backdrop-filter: blur(12px);
      -webkit-backdrop-filter: blur(12px);
      border: 1px solid rgba(255, 255, 255, 0.08);
    }
    .panel-card-hover:hover {
      border-color: rgba(99, 102, 241, 0.4);
      transform: translateY(-2px);
      transition: all 0.2s ease-in-out;
    }

    /* Professional Responsive Modal Architecture */
    .modal-backdrop {
      position: fixed !important;
      top: 0 !important;
      left: 0 !important;
      right: 0 !important;
      bottom: 0 !important;
      width: 100% !important;
      height: 100% !important;
      height: 100dvh !important;
      z-index: 1000 !important;
      display: flex !important;
      align-items: center !important;
      justify-content: center !important;
      padding: 0.75rem !important;
      background: rgba(2, 6, 23, 0.88) !important;
      backdrop-filter: blur(12px) !important;
      -webkit-backdrop-filter: blur(12px) !important;
      overflow-y: auto !important;
      overflow-x: hidden !important;
      box-sizing: border-box !important;
    }
    .modal-backdrop.hidden {
      display: none !important;
    }
    .modal-card {
      width: 100% !important;
      max-width: 38rem !important;
      margin: auto !important;
      max-height: 90vh !important;
      max-height: 90dvh !important;
      display: flex !important;
      flex-direction: column !important;
      border-radius: 1.25rem !important;
      border: 1px solid rgba(255, 255, 255, 0.14) !important;
      background: rgba(15, 23, 42, 0.98) !important;
      backdrop-filter: blur(20px) !important;
      -webkit-backdrop-filter: blur(20px) !important;
      box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.85) !important;
      overflow: hidden !important;
      box-sizing: border-box !important;
    }
    @media (max-width: 640px) {
      .modal-backdrop {
        padding: 0.5rem !important;
        align-items: center !important;
        overflow-x: hidden !important;
      }
      .modal-card {
        width: 100% !important;
        max-width: 100% !important;
        max-height: 94dvh !important;
        border-radius: 1rem !important;
        margin: auto !important;
        box-sizing: border-box !important;
      }
      .modal-header {
        padding: 0.85rem 1rem !important;
      }
      .modal-body {
        padding: 0.85rem 1rem !important;
        overflow-x: hidden !important;
      }
      .modal-footer {
        padding: 0.75rem 1rem !important;
        flex-direction: column !important;
        gap: 0.5rem !important;
      }
      .modal-footer > * {
        width: 100% !important;
        display: flex !important;
        gap: 0.5rem !important;
      }
      .modal-footer button {
        flex: 1 1 auto !important;
        justify-content: center !important;
        padding-top: 0.65rem !important;
        padding-bottom: 0.65rem !important;
        text-align: center !important;
      }
      input, select, textarea {
        font-size: 13px !important;
        max-width: 100% !important;
        box-sizing: border-box !important;
      }
    }
    .modal-header {
      padding: 1rem 1.25rem;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-shrink: 0;
      background: rgba(30, 41, 59, 0.7);
    }
    .modal-body {
      padding: 1rem 1.25rem;
      overflow-y: auto;
      overflow-x: hidden;
      flex: 1 1 auto;
      -webkit-overflow-scrolling: touch;
      overscroll-behavior: contain;
    }
    .modal-footer {
      padding: 0.85rem 1.25rem;
      border-top: 1px solid rgba(255, 255, 255, 0.08);
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      flex-shrink: 0;
      background: rgba(30, 41, 59, 0.8);
      flex-wrap: wrap;
    }
    .custom-scrollbar::-webkit-scrollbar {
      width: 5px;
    }
    .custom-scrollbar::-webkit-scrollbar-track {
      background: rgba(15, 23, 42, 0.6);
      border-radius: 4px;
    }
    .custom-scrollbar::-webkit-scrollbar-thumb {
      background: rgba(99, 102, 241, 0.4);
      border-radius: 4px;
    }
    .custom-scrollbar::-webkit-scrollbar-thumb:hover {
      background: rgba(99, 102, 241, 0.7);
    }
  </style>
</head>
<body class="min-h-screen bg-slate-950 text-slate-100 flex flex-col justify-between">

  <!-- LOGIN OVERLAY -->
  <div id="login-modal" class="modal-backdrop">
    <div class="glass-card modal-card max-w-md p-6 sm:p-8">
      <div class="text-center mb-6">
        <div class="w-16 h-16 bg-emerald-500/10 border border-emerald-500/20 rounded-2xl flex items-center justify-center mx-auto mb-4 text-emerald-400 text-3xl">
          <i class="fa-solid fa-shield-halved"></i>
        </div>
        <h1 class="text-xl sm:text-2xl font-bold tracking-tight text-white">ورود به پنل مدیریت آسان وی‌پی‌ان</h1>
        <p class="text-xs sm:text-sm text-slate-400 mt-1">رمز عبور یا توکن ادمین خود را وارد نمایید</p>
      </div>
      <div class="space-y-4">
        <div>
          <label class="block text-xs font-medium text-slate-300 mb-1">رمز عبور یا توکن ادمین</label>
          <div class="relative">
            <input type="password" id="admin-token-input" onkeydown="if(event.key==='Enter') handleLogin()" placeholder="رمز عبور مدیریت را وارد نمایید..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-4 py-3 text-sm focus:outline-none focus:border-emerald-500 transition-colors text-left font-mono">
            <button onclick="toggleTokenVisibility()" class="absolute left-3 top-3 text-slate-400 hover:text-white">
              <i id="toggle-token-icon" class="fa-solid fa-eye"></i>
            </button>
          </div>
          <div id="login-error-msg" class="hidden text-rose-400 text-xs mt-2 bg-rose-500/10 border border-rose-500/20 p-2.5 rounded-xl text-center"></div>
        </div>
        <button onclick="handleLogin()" id="login-submit-btn" class="w-full bg-emerald-600 hover:bg-emerald-500 text-white font-bold py-3 px-4 rounded-xl shadow-lg shadow-emerald-900/30 transition-all flex items-center justify-center gap-2">
          <span id="login-btn-text">ورود به داشبورد</span>
          <i id="login-btn-icon" class="fa-solid fa-arrow-left"></i>
        </button>
        <div class="mt-4 pt-4 border-t border-slate-800 text-center">
          <a href="/download" download="AsanVPN.apk" class="inline-flex items-center gap-2 text-xs font-semibold text-emerald-400 hover:text-emerald-300 hover:underline">
            <i class="fa-solid fa-cloud-arrow-down text-sm"></i>
            <span>دانلود مستقیم اپلیکیشن اندروید (نسخه ۱.۳.۲)</span>
          </a>
        </div>
      </div>
    </div>
  </div>

  <!-- MAIN APP CONTAINER -->
  <div id="dashboard-app" class="hidden flex-1 flex flex-col min-w-0 max-w-full overflow-x-hidden">
    <!-- TOP NAVIGATION -->
    <header class="glass-card sticky top-0 z-40 border-b border-slate-800 px-3 sm:px-6 py-2.5 sm:py-3.5 flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-2.5 w-full max-w-full">
      <div class="flex items-center justify-between sm:justify-start gap-3 sm:gap-6">
        <div class="flex items-center gap-2 sm:gap-3">
          <div class="w-9 h-9 sm:w-10 sm:h-10 bg-emerald-500/20 border border-emerald-500/30 rounded-xl flex items-center justify-center text-emerald-400 text-lg sm:text-xl font-bold shrink-0">
            <i class="fa-solid fa-bolt"></i>
          </div>
          <div>
            <h2 class="font-bold text-sm sm:text-lg leading-tight">آسان وی‌پی‌ان</h2>
            <span class="text-[10px] sm:text-xs text-emerald-400 flex items-center gap-1">
              <span class="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
              سامانه ابری کلودفلر
            </span>
          </div>
        </div>

        <!-- MAIN NAVIGATION TABS (Desktop) -->
        <div class="hidden sm:flex items-center bg-slate-900/90 p-1 rounded-xl border border-slate-800">
          <button id="tab-nav-users" onclick="switchMainTab('users')" class="px-4 py-2 rounded-lg text-xs font-bold transition flex items-center gap-2 bg-emerald-600 text-white shadow-sm">
            <i class="fa-solid fa-users"></i>
            <span>کاربران (پین اختصاصی)</span>
            <span id="badge-nav-users" class="bg-white/20 text-white text-[10px] px-1.5 py-0.5 rounded-full">0</span>
          </button>
          <button id="tab-nav-panels" onclick="switchMainTab('panels')" class="px-4 py-2 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition flex items-center gap-2">
            <i class="fa-solid fa-server"></i>
            <span>پنل‌های پروکسی</span>
            <span id="badge-nav-panels" class="bg-indigo-500/20 text-indigo-300 text-[10px] px-1.5 py-0.5 rounded-full">0</span>
          </button>
        </div>
      </div>

      <!-- ACTION BUTTONS -->
      <div class="flex items-center flex-wrap gap-1.5 sm:gap-2 w-full sm:w-auto justify-end">
        <a href="/download" download="AsanVPN.apk" title="دانلود مستقیم فایل نصبی اندروید (نسخه ۱.۳.۴)" class="bg-emerald-500/20 hover:bg-emerald-500/30 border border-emerald-500/40 text-emerald-300 text-xs font-bold px-2 sm:px-3 py-1.5 sm:py-2 rounded-xl transition flex items-center gap-1">
          <i class="fa-solid fa-download"></i>
          <span class="hidden md:inline">دانلود APK</span>
        </a>
        <button onclick="syncAllPanels()" id="sync-all-btn" title="همگام‌سازی فوری با تمامی پنل‌ها" class="bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-300 text-xs font-medium p-1.5 sm:px-3 sm:py-2 rounded-xl transition flex items-center gap-1">
          <i id="sync-all-icon" class="fa-solid fa-arrows-rotate"></i>
          <span class="hidden md:inline">همگام‌سازی پنل‌ها</span>
        </button>
        <button onclick="triggerMergeDuplicates()" id="merge-dups-btn" title="ادغام کاربران همنام در چند پنل (ترکیب اشتراک‌ها)" class="bg-purple-600/20 hover:bg-purple-600/30 border border-purple-500/30 text-purple-300 text-xs font-medium p-1.5 sm:px-3 sm:py-2 rounded-xl transition flex items-center gap-1">
          <i class="fa-solid fa-code-merge"></i>
          <span class="hidden lg:inline">ادغام همنام‌ها</span>
        </button>
        <button onclick="triggerMigrateTo8Pin()" id="migrate-8pin-btn" title="تبدیل تمام کدهای قدیمی به ۸ رقمی امن" class="bg-amber-600/20 hover:bg-amber-600/30 border border-amber-500/30 text-amber-300 text-xs font-medium p-1.5 sm:px-3 sm:py-2 rounded-xl transition flex items-center gap-1">
          <i class="fa-solid fa-shield-halved"></i>
          <span class="hidden lg:inline">تبدیل ۸ رقمی</span>
        </button>
        <button onclick="openUnlockLockoutModal()" title="رفع مسدودی و قفل ۳۰ دقیقه‌ای کاربر یا دستگاه" class="bg-rose-600/20 hover:bg-rose-600/30 border border-rose-500/30 text-rose-300 text-xs font-medium p-1.5 sm:px-3 sm:py-2 rounded-xl transition flex items-center gap-1">
          <i class="fa-solid fa-lock-open"></i>
          <span class="hidden md:inline">رفع قفل ۳۰ دقیقه</span>
        </button>
        <button onclick="openAddPanelModal()" class="bg-blue-600 hover:bg-blue-500 text-white text-xs font-bold px-2.5 sm:px-3.5 py-1.5 sm:py-2 rounded-xl shadow-lg shadow-blue-900/20 transition flex items-center gap-1">
          <i class="fa-solid fa-plus"></i>
          <span class="hidden sm:inline">افزودن پنل</span>
          <span class="sm:hidden">پنل +</span>
        </button>
        <button onclick="openCreateModal()" class="bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold px-2.5 sm:px-3.5 py-1.5 sm:py-2 rounded-xl shadow-lg shadow-emerald-900/20 transition flex items-center gap-1">
          <i class="fa-solid fa-user-plus"></i>
          <span class="hidden sm:inline">کاربر جدید</span>
          <span class="sm:hidden">کاربر +</span>
        </button>
        <button onclick="openMigrationModal()" title="انتقال دامنه یا پنل" class="bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs p-1.5 sm:p-2 rounded-xl transition">
          <i class="fa-solid fa-shuffle"></i>
        </button>
        <button onclick="openChangePasswordModal()" title="تغییر رمز عبور مدیریت" class="bg-slate-800 hover:bg-slate-700 text-amber-300 text-xs p-1.5 sm:p-2 rounded-xl transition">
          <i class="fa-solid fa-key"></i>
        </button>
        <button onclick="handleLogout()" title="خروج" class="bg-slate-800 hover:bg-slate-700 text-slate-400 hover:text-white p-1.5 sm:p-2 rounded-xl transition">
          <i class="fa-solid fa-arrow-right-from-bracket"></i>
        </button>
      </div>
    </header>

    <!-- MOBILE NAVIGATION TABS -->
    <div class="sm:hidden flex items-center justify-around bg-slate-900 border-b border-slate-800 p-2">
      <button onclick="switchMainTab('users')" id="tab-nav-users-mob" class="text-xs font-bold py-1.5 px-3 rounded-lg text-emerald-400 bg-emerald-500/10 flex items-center gap-1.5">
        <i class="fa-solid fa-users"></i>
        <span>کاربران</span>
        <span id="badge-nav-users-mob" class="bg-emerald-500/20 text-[10px] px-1.5 py-0.2 rounded-full font-mono">0</span>
      </button>
      <button onclick="switchMainTab('panels')" id="tab-nav-panels-mob" class="text-xs font-medium py-1.5 px-3 rounded-lg text-slate-400 flex items-center gap-1.5">
        <i class="fa-solid fa-server"></i>
        <span>پنل‌های پروکسی</span>
        <span id="badge-nav-panels-mob" class="bg-indigo-500/20 text-indigo-300 text-[10px] px-1.5 py-0.2 rounded-full font-mono">0</span>
      </button>
    </div>

    <!-- MAIN CONTENT AREA -->
    <main class="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 space-y-6">

      <!-- ========================================== -->
      <!-- VIEW 1: USERS MANAGEMENT TAB -->
      <!-- ========================================== -->
      <div id="view-users" class="space-y-6">
        <!-- STATS OVERVIEW -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div class="glass-card p-4 sm:p-5 rounded-2xl flex items-center justify-between border-slate-800/80">
            <div>
              <p class="text-xs text-slate-400 font-medium">کل کاربران</p>
              <h3 id="stat-total" class="text-2xl font-bold mt-1 text-white">0</h3>
            </div>
            <div class="w-12 h-12 bg-blue-500/10 text-blue-400 rounded-xl flex items-center justify-center text-xl">
              <i class="fa-solid fa-users"></i>
            </div>
          </div>

          <div class="glass-card p-4 sm:p-5 rounded-2xl flex items-center justify-between border-slate-800/80">
            <div>
              <p class="text-xs text-slate-400 font-medium">اشتراک‌های فعال</p>
              <h3 id="stat-active" class="text-2xl font-bold mt-1 text-emerald-400">0</h3>
            </div>
            <div class="w-12 h-12 bg-emerald-500/10 text-emerald-400 rounded-xl flex items-center justify-center text-xl">
              <i class="fa-solid fa-circle-check"></i>
            </div>
          </div>

          <div class="glass-card p-4 sm:p-5 rounded-2xl flex items-center justify-between border-slate-800/80">
            <div>
              <p class="text-xs text-slate-400 font-medium">قفل دستگاه فعال</p>
              <h3 id="stat-locked" class="text-2xl font-bold mt-1 text-amber-400">0</h3>
            </div>
            <div class="w-12 h-12 bg-amber-500/10 text-amber-400 rounded-xl flex items-center justify-center text-xl">
              <i class="fa-solid fa-mobile-screen-button"></i>
            </div>
          </div>

          <div class="glass-card p-4 sm:p-5 rounded-2xl flex items-center justify-between border-slate-800/80">
            <div>
              <p class="text-xs text-slate-400 font-medium">منقضی شده</p>
              <h3 id="stat-expired" class="text-2xl font-bold mt-1 text-rose-400">0</h3>
            </div>
            <div class="w-12 h-12 bg-rose-500/10 text-rose-400 rounded-xl flex items-center justify-center text-xl">
              <i class="fa-solid fa-clock-rotate-left"></i>
            </div>
          </div>
        </div>

        <!-- USERS TABLE -->
        <div class="glass-card rounded-2xl border border-slate-800 overflow-hidden shadow-xl">
          <div class="p-5 border-b border-slate-800 flex flex-col md:flex-row items-center justify-between gap-4">
            <div class="flex items-center gap-3 w-full md:w-auto">
              <h3 class="font-bold text-lg text-white">لیست کاربران و پین‌های فعال</h3>
              <span id="badge-count" class="bg-slate-800 text-slate-300 text-xs px-2.5 py-1 rounded-full">0 نفر</span>
            </div>
            <div class="flex items-center gap-3 w-full md:w-auto">
              <div class="relative w-full md:w-64">
                <input type="text" id="search-input" oninput="filterUsers()" placeholder="جستجو نام یا پین..." class="w-full bg-slate-900/80 border border-slate-700/80 rounded-xl pr-9 pl-4 py-2 text-xs focus:outline-none focus:border-emerald-500">
                <i class="fa-solid fa-magnifying-glass absolute right-3 top-2.5 text-slate-500 text-xs"></i>
              </div>
              <button onclick="loadUsers()" class="bg-slate-800 hover:bg-slate-700 text-slate-300 p-2.5 rounded-xl transition" title="بروزرسانی لیست">
                <i class="fa-solid fa-rotate-right"></i>
              </button>
            </div>
          </div>

          <div class="overflow-x-auto">
            <table class="w-full text-right text-sm">
              <thead class="bg-slate-900/60 text-slate-400 text-xs uppercase font-medium">
                <tr>
                  <th class="py-3.5 px-4">کد پین</th>
                  <th class="py-3.5 px-4">نام کاربر</th>
                  <th class="py-3.5 px-4">پنل‌های متصل</th>
                  <th class="py-3.5 px-4">حجم باقی‌مانده</th>
                  <th class="py-3.5 px-4">وضعیت اتصال</th>
                  <th class="py-3.5 px-4">اعتبار</th>
                  <th class="py-3.5 px-4 text-center">عملیات</th>
                </tr>
              </thead>
              <tbody id="users-table-body" class="divide-y divide-slate-800/60">
                <tr><td colspan="7" class="py-8 text-center text-slate-500">در حال دریافت لیست کاربران...</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <!-- ========================================== -->
      <!-- VIEW 2: PROXY PANELS MANAGEMENT TAB -->
      <!-- ========================================== -->
      <div id="view-panels" class="hidden space-y-6">
        <!-- PROXY PANELS HEADER & BANNER -->
        <div class="glass-card p-6 rounded-2xl border border-indigo-500/20 bg-gradient-to-l from-indigo-950/40 to-slate-900/60 flex flex-col md:flex-row items-center justify-between gap-4">
          <div>
            <div class="flex items-center gap-2 text-indigo-400 font-bold text-base mb-1">
              <i class="fa-solid fa-network-wired text-lg"></i>
              <span>مدیریت و اتصال خودکار پنل‌های پروکسی</span>
            </div>
            <p class="text-xs text-slate-300 leading-relaxed max-w-3xl">
              در این بخش می‌توانید سرورهای ۳X-UI، پاسارگارد، مرزبان یا هیدیفای خود را ثبت و مدیریت کنید. ورکر کلودفلر به صورت خودکار (هر ۱۵ دقیقه یا با کلیک دستی) کاربران، کلاینت‌ها و لینک‌های ساب‌اسکریپشن را استخراج و همگام‌سازی می‌کند.
            </p>
          </div>
          <div class="flex items-center gap-3">
            <button onclick="openAddPanelModal()" class="bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-bold px-4 py-2.5 rounded-xl shadow-lg shadow-indigo-900/30 transition flex items-center gap-2 whitespace-nowrap">
              <i class="fa-solid fa-plus"></i>
              <span>افزودن پنل جدید</span>
            </button>
          </div>
        </div>

        <!-- CONNECTED PANELS GRID -->
        <div id="panels-grid" class="grid grid-cols-1 md:grid-cols-2 gap-5">
          <!-- Dynamically populated panel cards -->
        </div>
      </div>

    </main>

    <!-- FOOTER -->
    <footer class="p-6 text-center text-xs text-slate-600 border-t border-slate-900 mt-12">
      سامانه یکپارچه آسان وی‌پی‌ان &copy; ۲۰۲۶ - متصل به شبکه لبه کلودفلر
    </footer>
  </div>

  <!-- ========================================== -->
  <!-- MODAL: ADD / EDIT PROXY PANEL -->
  <!-- ========================================== -->
  <div id="panel-modal" class="hidden modal-backdrop">
    <div class="glass-card modal-card">
      <div class="modal-header">
        <div class="min-w-0 pr-1">
          <h3 id="panel-modal-title" class="font-bold text-base sm:text-lg text-white truncate">افزودن پنل پروکسی جدید</h3>
          <p class="text-[11px] sm:text-xs text-slate-400 mt-0.5 truncate">اتصال مستقیم به API پنل‌های PasarGuard, 3X-UI, Marzban, Hiddify</p>
        </div>
        <button onclick="closeModal('panel-modal')" type="button" class="text-slate-400 hover:text-white p-2 rounded-lg hover:bg-slate-800 transition shrink-0"><i class="fa-solid fa-xmark text-lg"></i></button>
      </div>

      <input type="hidden" id="panel-form-id">

      <div class="modal-body space-y-4 text-sm custom-scrollbar">
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div class="sm:col-span-1">
            <label class="block text-xs text-slate-300 mb-1">نام پنل (جهت شناسایی)</label>
            <input type="text" id="panel-form-name" placeholder="مثال: سرور آلمان ۳X-UI" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-slate-100">
          </div>
          <div>
            <label class="block text-xs text-slate-300 mb-1">نوع پنل</label>
            <select id="panel-form-type" onchange="onPanelTypeChange()" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-slate-200">
              <option value="pasarguard">پاسارگارد (PasarGuard)</option>
              <option value="3xui">3X-UI / X-UI (Sanaei)</option>
              <option value="goguard">گوگارد (GoGuard / Guard)</option>
              <option value="marzban">مرزبان (Marzban)</option>
              <option value="hiddify">هیدیفای (Hiddify)</option>
              <option value="container">کانتینر لینک‌های Xray (مخزن گیت‌هاب / ساب مستقیم / خام)</option>
            </select>
          </div>
          <div>
            <label class="block text-xs text-slate-300 mb-1">اولویت اتصال (۱ = بالاترین)</label>
            <input type="number" id="panel-form-priority" min="1" max="99" value="1" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-slate-200">
          </div>
        </div>

        <div>
          <label class="block text-xs text-slate-300 mb-1">آدرس پنل یا لینک کانتینر (Base URL)</label>
          <input type="text" id="panel-form-url" dir="ltr" placeholder="https://irtun.darklink.ir:8000 یا https://raw.githubusercontent.com/..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-left font-mono text-xs">
          <p id="panel-url-hint" class="text-[11px] text-slate-500 mt-1">آدرس ورود به پنل یا لینک خام کانتینر نودهای Xray</p>
        </div>

        <div id="panel-form-api-key-container">
          <label class="block text-xs text-slate-300 mb-1">کلید دسترسی API / سشن کوکی / توکن</label>
          <input type="password" id="panel-form-api-key" dir="ltr" placeholder="API Key یا Session Cookie..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-left font-mono text-xs">
          <p id="panel-key-hint" class="text-[11px] text-slate-500 mt-1">برای پاسارگاد: X-API-KEY | برای ۳X-UI: کوکی session یا توکن API</p>
        </div>

        <div id="panel-form-sub-base-container">
          <label class="block text-xs text-slate-300 mb-1">قالب لینک ساب‌اسکریپشن (اختیاری برای ساخت لینک)</label>
          <input type="text" id="panel-form-sub-base" dir="ltr" placeholder="https://sub.your-domain.com:2016/sub_path/{subId}" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-left font-mono text-xs">
          <p class="text-[11px] text-slate-500 mt-1">از عبارت <code class="text-indigo-400 font-mono">{subId}</code> به عنوان جایگزین کد ساب استفاده کنید.</p>
        </div>

        <div class="flex items-center gap-2 pt-1">
          <input type="checkbox" id="panel-form-auto-sync" checked class="w-4 h-4 rounded text-indigo-600 focus:ring-indigo-500 bg-slate-900 border-slate-700">
          <label for="panel-form-auto-sync" class="text-xs text-slate-300 font-medium cursor-pointer">
            همگام‌سازی خودکار فعال باشد (بررسی و دریافت کاربران به صورت اتوماتیک)
          </label>
        </div>
      </div>

      <div class="modal-footer">
        <button onclick="testPanelFormConnection()" type="button" class="bg-slate-800 hover:bg-slate-700 text-indigo-400 px-3.5 py-2.5 rounded-xl text-xs font-medium transition flex items-center justify-center gap-1.5 w-full sm:w-auto">
          <i class="fa-solid fa-bolt"></i>
          <span>تست اتصال زنده</span>
        </button>
        <div class="flex items-center gap-2 w-full sm:w-auto">
          <button onclick="closeModal('panel-modal')" type="button" class="flex-1 sm:flex-initial bg-slate-800 hover:bg-slate-700 px-4 py-2.5 rounded-xl text-xs text-slate-300 text-center">انصراف</button>
          <button onclick="savePanelForm()" type="button" class="flex-1 sm:flex-initial bg-indigo-600 hover:bg-indigo-500 px-4 py-2.5 rounded-xl text-xs font-bold text-white shadow-lg shadow-indigo-900/30 flex items-center justify-center gap-1.5 text-center">
            <i class="fa-solid fa-cloud-arrow-up"></i>
            <span>ذخیره پنل</span>
          </button>
        </div>
      </div>
    </div>
  </div>

  <!-- ========================================== -->
  <!-- MODAL: CREATE USER (WITH PANEL AUTO-PICK) -->
  <!-- ========================================== -->
  <div id="create-modal" class="hidden modal-backdrop">
    <div class="glass-card modal-card">
      <div class="modal-header">
        <h3 class="font-bold text-base sm:text-lg text-white">ایجاد اشتراک و پین اختصاصی جدید</h3>
        <button onclick="closeModal('create-modal')" type="button" class="text-slate-400 hover:text-white p-2 rounded-lg hover:bg-slate-800 transition shrink-0"><i class="fa-solid fa-xmark text-lg"></i></button>
      </div>

      <div class="modal-body space-y-4 text-sm custom-scrollbar">
        <!-- AUTO-FILL FROM CONNECTED PANEL -->
        <div class="bg-slate-900/90 border border-indigo-500/30 rounded-xl p-3.5 space-y-2">
          <div class="flex items-center justify-between">
            <span class="text-xs font-bold text-indigo-400 flex items-center gap-1.5">
              <i class="fa-solid fa-wand-magic-sparkles"></i>
              <span>انتخاب مستقیم از پنل‌های متصل (سریع):</span>
            </span>
          </div>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <select id="auto-pick-panel" onchange="onAutoPickPanelChange()" class="w-full bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-slate-200">
              <option value="">-- انتخاب پنل سرور --</option>
            </select>
            <select id="auto-pick-user" onchange="onAutoPickUserChange()" disabled class="w-full bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-slate-200">
              <option value="">-- انتخاب کاربر از پنل --</option>
            </select>
          </div>
        </div>

        <div>
          <label class="block text-xs text-slate-300 mb-1">نام یا مشخصات کاربر</label>
          <input type="text" id="new-user-name" placeholder="مثال: علی رضایی" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-emerald-500 outline-none text-slate-100">
        </div>

        <div>
          <label class="block text-xs font-semibold text-slate-200 mb-1.5 flex flex-wrap items-center justify-between gap-1">
            <span class="flex items-center gap-1.5">
              <i class="fa-solid fa-server text-indigo-400"></i>
              <span>انتخاب پنل‌ها و کانتینرهای متصل:</span>
            </span>
            <span class="text-[11px] text-slate-400 font-normal">ترافیک و روزها هماهنگ می‌شود</span>
          </label>
          <div id="new-user-panels-container" class="space-y-1.5 max-h-36 overflow-y-auto p-2.5 bg-slate-900/90 border border-slate-700 rounded-xl custom-scrollbar">
            <!-- Dynamically populated panel checkboxes -->
          </div>
        </div>

        <details class="text-xs text-slate-400 group">
          <summary class="cursor-pointer hover:text-slate-200 text-xs py-1 select-none flex items-center gap-1.5 list-none">
            <i class="fa-solid fa-chevron-left text-[10px] text-slate-500 group-open:-rotate-90 transition-transform"></i>
            <span class="text-indigo-400 font-medium">تنظیمات پیشرفته: لینک ساب‌اسکریپشن سفارشی یا دستی (اختیاری)</span>
          </summary>
          <div class="pt-2">
            <textarea id="new-sub-url" rows="2" placeholder="در صورت نیاز به لینک‌های اختصاصی علاوه بر پنل‌ها، اینجا وارد کنید..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2 focus:border-emerald-500 outline-none text-left font-mono text-xs"></textarea>
          </div>
        </details>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label class="block text-xs text-slate-300 mb-1">مدت اعتبار پیش‌فرض (روز)</label>
            <input type="number" id="new-user-days" value="30" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-emerald-500 outline-none text-slate-100">
          </div>
          <div>
            <label class="block text-xs text-slate-300 mb-1">کد ۸ رقمی امن (اختیاری)</label>
            <input type="text" id="new-custom-pin" maxlength="8" placeholder="تولید خودکار در صورت خالی بودن" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-emerald-500 outline-none text-center font-mono font-bold tracking-widest text-emerald-400">
          </div>
        </div>

        <div>
          <label class="block text-xs text-slate-300 mb-1 flex items-center justify-between">
            <span class="flex items-center gap-1.5">
              <i class="fa-solid fa-mobile-screen text-indigo-400"></i>
              <span>تعداد دستگاه‌های مجاز (گوشی):</span>
            </span>
            <span class="text-[11px] text-slate-400 font-normal">۰ یا منفی = نامحدود</span>
          </label>
          <div class="flex items-center gap-2 flex-wrap">
            <input type="number" id="new-max-devices" value="1" min="0" placeholder="1" class="w-20 bg-slate-900 border border-slate-700 rounded-xl px-3 py-2 text-center font-mono text-sm focus:border-emerald-500 outline-none text-emerald-400 font-bold">
            <div class="flex items-center gap-1 flex-wrap">
              <button type="button" onclick="setMaxDevicesPreset('new-max-devices', 1)" class="px-2 py-1 text-xs bg-slate-800 hover:bg-slate-700 rounded-lg text-slate-300 transition">۱ گوشی</button>
              <button type="button" onclick="setMaxDevicesPreset('new-max-devices', 2)" class="px-2 py-1 text-xs bg-slate-800 hover:bg-slate-700 rounded-lg text-slate-300 transition">۲ گوشی</button>
              <button type="button" onclick="setMaxDevicesPreset('new-max-devices', 5)" class="px-2 py-1 text-xs bg-slate-800 hover:bg-slate-700 rounded-lg text-slate-300 transition">۵ گوشی</button>
              <button type="button" onclick="setMaxDevicesPreset('new-max-devices', 20)" class="px-2.5 py-1 text-xs bg-indigo-600/30 hover:bg-indigo-600/50 text-indigo-300 border border-indigo-500/30 rounded-lg transition font-medium">۲۰ گوشی</button>
              <button type="button" onclick="setMaxDevicesPreset('new-max-devices', 0)" class="px-2.5 py-1 text-xs bg-purple-600/30 hover:bg-purple-600/50 text-purple-300 border border-purple-500/30 rounded-lg transition font-medium">∞ نامحدود</button>
            </div>
          </div>
        </div>
      </div>

      <div class="modal-footer justify-end">
        <button onclick="closeModal('create-modal')" type="button" class="bg-slate-800 hover:bg-slate-700 px-4 py-2 rounded-xl text-xs text-slate-300">انصراف</button>
        <button onclick="submitCreateUser()" type="button" class="bg-emerald-600 hover:bg-emerald-500 px-5 py-2 rounded-xl text-xs font-bold text-white shadow-lg shadow-emerald-900/30 flex items-center gap-1.5">
          <i class="fa-solid fa-user-plus"></i>
          <span>ایجاد اشتراک و دریافت پین</span>
        </button>
      </div>
    </div>
  </div>

  <!-- MODAL: EDIT USER -->
  <div id="edit-modal" class="hidden modal-backdrop">
    <div class="glass-card modal-card">
      <div class="modal-header">
        <div class="min-w-0 pr-1">
          <h3 class="font-bold text-base sm:text-lg text-white">ویرایش مشخصات کاربر</h3>
          <p class="text-xs text-emerald-400 font-mono mt-0.5">پین فعال‌سازی: <span id="edit-pin-display"></span></p>
        </div>
        <button onclick="closeModal('edit-modal')" type="button" class="text-slate-400 hover:text-white p-2 rounded-lg hover:bg-slate-800 transition shrink-0"><i class="fa-solid fa-xmark text-lg"></i></button>
      </div>

      <input type="hidden" id="edit-pin-val">

      <div class="modal-body space-y-4 text-sm custom-scrollbar">
        <div>
          <label class="block text-xs text-slate-300 mb-1">نام یا مشخصات خریدار</label>
          <input type="text" id="edit-user-name" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-slate-100">
        </div>

        <div>
          <label class="block text-xs font-semibold text-slate-200 mb-1.5 flex flex-wrap items-center justify-between gap-1">
            <span class="flex items-center gap-1.5">
              <i class="fa-solid fa-server text-indigo-400"></i>
              <span>پنل‌ها و کانتینرهای متصل به این کاربر:</span>
            </span>
            <span class="text-[11px] text-slate-400 font-normal">ترافیک و روزها بر اساس بالاترین پنل اعمال می‌شود</span>
          </label>
          <div id="edit-user-panels-container" class="space-y-1.5 max-h-36 overflow-y-auto p-2.5 bg-slate-900/90 border border-slate-700 rounded-xl custom-scrollbar">
            <!-- Dynamically populated panel checkboxes -->
          </div>
        </div>

        <details class="text-xs text-slate-400 group">
          <summary class="cursor-pointer hover:text-slate-200 text-xs py-1 select-none flex items-center gap-1.5 list-none">
            <i class="fa-solid fa-chevron-left text-[10px] text-slate-500 group-open:-rotate-90 transition-transform"></i>
            <span class="text-indigo-400 font-medium">تنظیمات پیشرفته: لینک ساب‌اسکریپشن سفارشی یا دستی (اختیاری)</span>
          </summary>
          <div class="pt-2">
            <textarea id="edit-sub-url" rows="2" placeholder="لینک‌های اختصاصی دستی..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2 focus:border-indigo-500 outline-none text-left font-mono text-xs"></textarea>
          </div>
        </details>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label class="block text-xs text-slate-300 mb-1">تنظیم مجدد روزهای اعتبار (اختیاری)</label>
            <input type="number" id="edit-user-days" placeholder="تعداد روز جدید..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-slate-100">
          </div>
          <div>
            <label class="block text-xs text-slate-300 mb-1">وضعیت اشتراک</label>
            <select id="edit-active-status" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-slate-200">
              <option value="true">فعال (Active)</option>
              <option value="false">مسدود / غیرفعال (Disabled)</option>
            </select>
          </div>
        </div>

        <div>
          <label class="block text-xs text-slate-300 mb-1 flex items-center justify-between">
            <span class="flex items-center gap-1.5">
              <i class="fa-solid fa-mobile-screen text-indigo-400"></i>
              <span>تعداد دستگاه‌های مجاز (گوشی):</span>
            </span>
            <span id="edit-devices-status-badge" class="text-[11px] text-amber-400 font-mono"></span>
          </label>
          <div class="flex items-center gap-2 flex-wrap">
            <input type="number" id="edit-max-devices" min="0" placeholder="1" class="w-20 bg-slate-900 border border-slate-700 rounded-xl px-3 py-2 text-center font-mono text-sm focus:border-indigo-500 outline-none text-indigo-400 font-bold">
            <div class="flex items-center gap-1 flex-wrap">
              <button type="button" onclick="setMaxDevicesPreset('edit-max-devices', 1)" class="px-2 py-1 text-xs bg-slate-800 hover:bg-slate-700 rounded-lg text-slate-300 transition">۱ گوشی</button>
              <button type="button" onclick="setMaxDevicesPreset('edit-max-devices', 2)" class="px-2 py-1 text-xs bg-slate-800 hover:bg-slate-700 rounded-lg text-slate-300 transition">۲ گوشی</button>
              <button type="button" onclick="setMaxDevicesPreset('edit-max-devices', 5)" class="px-2 py-1 text-xs bg-slate-800 hover:bg-slate-700 rounded-lg text-slate-300 transition">۵ گوشی</button>
              <button type="button" onclick="setMaxDevicesPreset('edit-max-devices', 20)" class="px-2.5 py-1 text-xs bg-indigo-600/30 hover:bg-indigo-600/50 text-indigo-300 border border-indigo-500/30 rounded-lg transition font-medium">۲۰ گوشی</button>
              <button type="button" onclick="setMaxDevicesPreset('edit-max-devices', 0)" class="px-2.5 py-1 text-xs bg-purple-600/30 hover:bg-purple-600/50 text-purple-300 border border-purple-500/30 rounded-lg transition font-medium">∞ نامحدود</button>
            </div>
            <button type="button" onclick="resetEditUserDevices()" title="ریست قفل تمامی گوشی‌های این کاربر" class="mr-auto px-2.5 py-1.5 text-xs bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 border border-rose-500/30 rounded-lg transition flex items-center gap-1">
              <i class="fa-solid fa-arrows-rotate text-[10px]"></i>
              <span>ریست دستگاه‌ها</span>
            </button>
            <button type="button" onclick="unlockEditUserLockout()" title="رفع قفل و مسدودی ۳۰ دقیقه‌ای ورود" class="px-2.5 py-1.5 text-xs bg-amber-500/10 hover:bg-amber-500/20 text-amber-300 border border-amber-500/30 rounded-lg transition flex items-center gap-1">
              <i class="fa-solid fa-lock-open text-[10px]"></i>
              <span>رفع قفل ۳۰ دقیقه</span>
            </button>
          </div>
        </div>
      </div>

      <div class="modal-footer justify-end">
        <button onclick="closeModal('edit-modal')" type="button" class="bg-slate-800 hover:bg-slate-700 px-4 py-2 rounded-xl text-xs text-slate-300">انصراف</button>
        <button onclick="submitEditUser()" type="button" class="bg-indigo-600 hover:bg-indigo-500 px-5 py-2 rounded-xl text-xs font-bold text-white shadow-lg shadow-indigo-900/30 flex items-center gap-1.5">
          <i class="fa-solid fa-check"></i>
          <span>ذخیره تغییرات</span>
        </button>
      </div>
    </div>
  </div>

  <!-- MODAL: CHANGE ADMIN PASSWORD -->
  <div id="change-password-modal" class="hidden modal-backdrop">
    <div class="glass-card modal-card max-w-md">
      <div class="modal-header">
        <h3 class="font-bold text-base sm:text-lg text-white flex items-center gap-2">
          <i class="fa-solid fa-key text-amber-400"></i>
          <span>تغییر رمز عبور مدیریت</span>
        </h3>
        <button onclick="closeModal('change-password-modal')" type="button" class="text-slate-400 hover:text-white p-2 rounded-lg hover:bg-slate-800 transition shrink-0"><i class="fa-solid fa-xmark text-lg"></i></button>
      </div>
      <div class="modal-body space-y-3 text-sm custom-scrollbar">
        <p class="text-xs text-slate-400 leading-relaxed">
          برای ارتقای امنیت پنل، رمز عبور جدیدی تعیین نمایید. پس از تغییر، رمزهای قبلی و پیش‌فرض کاملاً غیرفعال خواهند شد.
        </p>
        <div>
          <label class="block text-xs text-slate-300 mb-1">رمز عبور فعلی</label>
          <input type="password" id="cp-current" placeholder="رمز عبور فعلی..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-amber-500 outline-none text-left font-mono text-xs">
        </div>
        <div>
          <label class="block text-xs text-slate-300 mb-1">رمز عبور جدید (حداقل ۶ کاراکتر)</label>
          <input type="password" id="cp-new" placeholder="رمز عبور جدید..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-amber-500 outline-none text-left font-mono text-xs">
        </div>
        <div>
          <label class="block text-xs text-slate-300 mb-1">تکرار رمز عبور جدید</label>
          <input type="password" id="cp-confirm" placeholder="تکرار رمز عبور جدید..." class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-amber-500 outline-none text-left font-mono text-xs">
        </div>
      </div>
      <div class="modal-footer justify-end">
        <button onclick="closeModal('change-password-modal')" type="button" class="bg-slate-800 hover:bg-slate-700 px-4 py-2 rounded-xl text-xs text-slate-300">انصراف</button>
        <button onclick="submitChangePassword()" type="button" id="cp-submit-btn" class="bg-amber-600 hover:bg-amber-500 px-5 py-2 rounded-xl text-xs font-bold text-white shadow-lg shadow-amber-900/30 flex items-center gap-1.5">
          <i class="fa-solid fa-key"></i>
          <span>ذخیره رمز جدید</span>
        </button>
      </div>
    </div>
  </div>

  <!-- MODAL: BULK DOMAIN MIGRATION -->
  <div id="migration-modal" class="hidden modal-backdrop">
    <div class="glass-card modal-card">
      <div class="modal-header">
        <h3 class="font-bold text-base sm:text-lg text-white">انتقال دسته جمعی دامنه یا پنل</h3>
        <button onclick="closeModal('migration-modal')" type="button" class="text-slate-400 hover:text-white p-2 rounded-lg hover:bg-slate-800 transition shrink-0"><i class="fa-solid fa-xmark text-lg"></i></button>
      </div>
      <div class="modal-body space-y-3 text-sm custom-scrollbar">
        <p class="text-xs text-slate-400">
          اگر دامنه قبلی فیلتر شده است یا سرور را به پنل جدید انتقال داده‌اید، با وارد کردن دامنه قدیم و جدید، اشتراک تمام کاربران در ۲ ثانیه بدون نیاز به تغییر پین جابجا می‌شود.
        </p>
        <div>
          <label class="block text-xs text-slate-300 mb-1">دامنه یا آی‌پی قدیمی (Old Domain/IP)</label>
          <input type="text" id="mig-old-domain" placeholder="old-panel.example.com" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-left font-mono text-xs">
        </div>
        <div>
          <label class="block text-xs text-slate-300 mb-1">دامنه یا آی‌پی جدید (New Domain/IP)</label>
          <input type="text" id="mig-new-domain" placeholder="new-pasarguard.example.com" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-left font-mono text-xs">
        </div>
        <div>
          <label class="block text-xs text-slate-300 mb-1">تغییر نوع پنل (اختیاری)</label>
          <select id="mig-panel-type" class="w-full bg-slate-900 border border-slate-700 rounded-xl px-3 py-2.5 focus:border-indigo-500 outline-none text-slate-200">
            <option value="">بدون تغییر نوع پنل</option>
            <option value="pasarguard">PasarGuard</option>
            <option value="3xui">3X-UI</option>
            <option value="marzban">Marzban</option>
            <option value="hiddify">Hiddify</option>
          </select>
        </div>
      </div>
      <div class="modal-footer justify-end">
        <button onclick="closeModal('migration-modal')" type="button" class="bg-slate-800 hover:bg-slate-700 px-4 py-2 rounded-xl text-xs text-slate-300">انصراف</button>
        <button onclick="submitMigration()" type="button" class="bg-indigo-600 hover:bg-indigo-500 px-5 py-2 rounded-xl text-xs font-bold text-white shadow-lg shadow-indigo-900/30 flex items-center gap-1.5">
          <i class="fa-solid fa-shuffle"></i>
          <span>اعمال تغییرات روی تمام کاربران</span>
        </button>
      </div>
    </div>
  </div>

  <script>
    // Fallback if SweetAlert2 is blocked or fails to load
    if (typeof Swal === 'undefined') {
      window.Swal = {
        fire: function(opts) {
          if (typeof opts === 'string') alert(opts);
          else alert((opts.title ? opts.title + '\\n' : '') + (opts.text || opts.html || ''));
          return Promise.resolve({ isConfirmed: true });
        }
      };
    }

    let currentToken = localStorage.getItem('asanvpn_admin_token') || '';
    let cachedUsers = [];
    let cachedPanels = [];
    let currentTab = 'users';

    async function fetchJson(url, options = {}) {
      const res = await fetch(url, options);
      const text = await res.text();
      let data = {};
      try {
        data = text ? JSON.parse(text) : {};
      } catch (e) {
        if (!res.ok) {
          throw new Error('خطای ارتباط با سرور (کد ' + res.status + ')');
        }
        throw new Error('قالب پاسخ سرور نامعتبر است.');
      }
      return { ok: res.ok, status: res.status, data };
    }

    window.addEventListener('DOMContentLoaded', () => {
      if (currentToken) {
        testTokenAndLoad(currentToken, true);
      }
    });

    function toggleTokenVisibility() {
      const input = document.getElementById('admin-token-input');
      const icon = document.getElementById('toggle-token-icon');
      if (input.type === 'password') {
        input.type = 'text';
        icon.className = 'fa-solid fa-eye-slash';
      } else {
        input.type = 'password';
        icon.className = 'fa-solid fa-eye';
      }
    }

    async function handleLogin() {
      const errBox = document.getElementById('login-error-msg');
      if (errBox) errBox.classList.add('hidden');

      let token = document.getElementById('admin-token-input').value.trim();
      token = token.replace(/[۰-۹]/g, d => '۰۱۲۳۴۵۶۷۸۹'.indexOf(d));

      if (!token) {
        showLoginError('لطفاً رمز عبور یا توکن ادمین را وارد نمایید.');
        return;
      }
      await testTokenAndLoad(token, false);
    }

    function showLoginError(msg) {
      const errBox = document.getElementById('login-error-msg');
      if (errBox) {
        errBox.innerText = msg;
        errBox.classList.remove('hidden');
      }
      if (typeof Swal !== 'undefined' && Swal.fire) {
        Swal.fire({ icon: 'error', title: 'عدم دسترسی', text: msg });
      }
    }

    async function testTokenAndLoad(token, isAuto = false) {
      const btn = document.getElementById('login-submit-btn');
      const btnText = document.getElementById('login-btn-text');
      const btnIcon = document.getElementById('login-btn-icon');

      if (!isAuto && btn) {
        btn.disabled = true;
        if (btnText) btnText.innerText = 'در حال بررسی...';
        if (btnIcon) btnIcon.className = 'fa-solid fa-circle-notch fa-spin';
      }

      try {
        const res = await fetch('/api/v1/admin/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': token },
          body: JSON.stringify({ token: token })
        });

        if (res.status === 401) {
          localStorage.removeItem('asanvpn_admin_token');
          if (!isAuto) {
            showLoginError('رمز عبور وارد شده اشتباه است.');
          }
          return;
        }

        if (!res.ok) {
          // Fallback test via users endpoint
          const fallbackRes = await fetch('/api/v1/admin/users', {
            headers: { 'X-Admin-Token': token }
          });
          if (!fallbackRes.ok) {
            if (!isAuto) showLoginError('خطا در ورود به داشبورد.');
            return;
          }
        }

        currentToken = token;
        localStorage.setItem('asanvpn_admin_token', token);
        document.getElementById('login-modal').classList.add('hidden');
        document.getElementById('dashboard-app').classList.remove('hidden');
        await Promise.all([loadUsers(), loadPanels()]);
      } catch (e) {
        if (!isAuto) {
          showLoginError('خطا در ارتباط با سرور: ' + e.message);
        }
      } finally {
        if (!isAuto && btn) {
          btn.disabled = false;
          if (btnText) btnText.innerText = 'ورود به داشبورد';
          if (btnIcon) btnIcon.className = 'fa-solid fa-arrow-left';
        }
      }
    }

    function handleLogout() {
      localStorage.removeItem('asanvpn_admin_token');
      currentToken = '';
      document.getElementById('dashboard-app').classList.add('hidden');
      document.getElementById('login-modal').classList.remove('hidden');
    }

    function switchMainTab(tab) {
      currentTab = tab;
      const viewUsers = document.getElementById('view-users');
      const viewPanels = document.getElementById('view-panels');
      const btnUsers = document.getElementById('tab-nav-users');
      const btnPanels = document.getElementById('tab-nav-panels');
      const mobUsers = document.getElementById('tab-nav-users-mob');
      const mobPanels = document.getElementById('tab-nav-panels-mob');

      if (tab === 'users') {
        viewUsers.classList.remove('hidden');
        viewPanels.classList.add('hidden');
        if (btnUsers) btnUsers.className = 'px-4 py-2 rounded-lg text-xs font-bold transition flex items-center gap-2 bg-emerald-600 text-white shadow-sm';
        if (btnPanels) btnPanels.className = 'px-4 py-2 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition flex items-center gap-2';
        if (mobUsers) mobUsers.className = 'text-xs font-bold py-1.5 px-3 rounded-lg text-emerald-400 bg-emerald-500/10 flex items-center gap-1.5';
        if (mobPanels) mobPanels.className = 'text-xs font-medium py-1.5 px-3 rounded-lg text-slate-400 flex items-center gap-1.5';
      } else {
        viewUsers.classList.add('hidden');
        viewPanels.classList.remove('hidden');
        if (btnPanels) btnPanels.className = 'px-4 py-2 rounded-lg text-xs font-bold transition flex items-center gap-2 bg-indigo-600 text-white shadow-sm';
        if (btnUsers) btnUsers.className = 'px-4 py-2 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition flex items-center gap-2';
        if (mobPanels) mobPanels.className = 'text-xs font-bold py-1.5 px-3 rounded-lg text-indigo-400 bg-indigo-500/10 flex items-center gap-1.5';
        if (mobUsers) mobUsers.className = 'text-xs font-medium py-1.5 px-3 rounded-lg text-slate-400 flex items-center gap-1.5';
        loadPanels();
      }
    }

    // =========================================================
    // USERS MANAGEMENT LOGIC
    // =========================================================

    async function loadUsers() {
      try {
        const { ok, data } = await fetchJson('/api/v1/admin/users', {
          headers: { 'X-Admin-Token': currentToken }
        });
        if (!ok) {
          console.error('Failed to load users:', data);
          return;
        }
        cachedUsers = data.users || [];
        renderTable(cachedUsers);
        updateStats(cachedUsers);
      } catch (err) {
        console.error('Failed to load users:', err);
      }
    }

    function updateStats(users) {
      const now = Math.floor(Date.now() / 1000);
      document.getElementById('stat-total').innerText = users.length;
      document.getElementById('badge-count').innerText = users.length + ' نفر';
      document.getElementById('badge-nav-users').innerText = users.length;
      const mobUsersBadge = document.getElementById('badge-nav-users-mob');
      if (mobUsersBadge) mobUsersBadge.innerText = users.length;

      const active = users.filter(u => u.active !== false && (!u.expires_at || u.expires_at > now)).length;
      document.getElementById('stat-active').innerText = active;

      const locked = users.filter(u => u.device_id).length;
      document.getElementById('stat-locked').innerText = locked;

      const expired = users.filter(u => u.expires_at && u.expires_at <= now).length;
      document.getElementById('stat-expired').innerText = expired;
    }

    function renderTable(users) {
      const tbody = document.getElementById('users-table-body');
      if (!users || users.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="py-12 text-center text-slate-500">هیچ کاربری یافت نشد. دکمه "همگام‌سازی پنل‌ها" یا "کاربر جدید" را بزنید.</td></tr>';
        return;
      }

      const now = Math.floor(Date.now() / 1000);
      tbody.innerHTML = users.map(u => {
        const isExp = u.expires_at && u.expires_at <= now;
        const daysLeft = u.expires_at ? Math.max(0, Math.ceil((u.expires_at - now) / 86400)) : 'نامحدود';
        const isUserActive = u.active !== false;

        // Device lock status calculation
        const maxDev = (u.max_devices !== undefined && u.max_devices !== null && u.max_devices !== '')
          ? parseInt(u.max_devices, 10)
          : 1;
        const boundDevices = Array.isArray(u.devices)
          ? u.devices
          : (u.device_id ? [u.device_id] : []);
        const boundCount = boundDevices.length;

        let lockStatusHtml = '';
        if (maxDev <= 0) {
          lockStatusHtml = '<span class="inline-flex items-center gap-1.5 text-xs text-purple-300 bg-purple-500/15 border border-purple-500/30 px-2.5 py-1 rounded-full font-medium" title="استفاده روی هر تعداد گوشی بدون محدودیت"><i class="fa-solid fa-infinity text-[10px]"></i> نامحدود (' + boundCount + ' گوشی)</span>';
        } else if (maxDev > 1) {
          const isFull = boundCount >= maxDev;
          const colorCls = isFull ? 'text-amber-400 bg-amber-500/10 border-amber-500/30' : 'text-blue-400 bg-blue-500/10 border-blue-500/30';
          lockStatusHtml = '<span class="inline-flex items-center gap-1.5 text-xs ' + colorCls + ' border px-2.5 py-1 rounded-full font-medium" title="' + boundCount + ' از ' + maxDev + ' گوشی ثبت شده"><i class="fa-solid fa-mobile-screen text-[10px]"></i> ' + boundCount + ' از ' + maxDev + ' گوشی' + (isFull ? ' (تکمیل)' : '') + '</span>';
        } else {
          if (boundCount > 0) {
            lockStatusHtml = '<span class="inline-flex items-center gap-1.5 text-xs text-amber-400 bg-amber-500/10 border border-amber-500/20 px-2.5 py-1 rounded-full font-medium"><i class="fa-solid fa-lock text-[10px]"></i> قفل شده (۱ گوشی)</span>';
          } else {
            lockStatusHtml = '<span class="inline-flex items-center gap-1.5 text-xs text-slate-400 bg-slate-800 border border-slate-700/60 px-2.5 py-1 rounded-full font-medium"><i class="fa-solid fa-lock-open text-[10px]"></i> آزاد (۱ گوشی)</span>';
          }
        }

        // Render connected panels badges
        let panelsHtml = '';
        if (Array.isArray(u.panel_ids) && u.panel_ids.length > 0) {
          panelsHtml = '<div class="flex flex-wrap gap-1 max-w-[220px]">' + u.panel_ids.map(pid => {
            const pObj = cachedPanels.find(p => p.id === pid);
            const pName = pObj ? pObj.name : pid;
            const isCont = pObj && (pObj.type === 'container' || pObj.type === 'raw');
            const cls = isCont 
              ? 'bg-fuchsia-900/30 text-fuchsia-300 border-fuchsia-500/40' 
              : 'bg-indigo-900/30 text-indigo-300 border-indigo-500/40';
            const icon = isCont ? 'fa-box-archive' : 'fa-server';
            return '<span class="inline-flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-full border ' + cls + ' font-medium">' +
              '<i class="fa-solid ' + icon + ' text-[9px]"></i>' +
              '<span class="truncate max-w-[90px]">' + pName + '</span>' +
            '</span>';
          }).join('') + '</div>';
        } else if (u.panel_type === 'multi') {
          panelsHtml = '<span class="bg-purple-900/40 text-purple-300 text-xs px-2.5 py-1 rounded-full font-mono border border-purple-500/50 inline-flex items-center gap-1.5"><i class="fa-solid fa-layer-group text-[10px]"></i> چندپنله</span>';
        } else {
          panelsHtml = '<span class="bg-slate-800 text-slate-300 text-xs px-2.5 py-1 rounded-full font-mono uppercase border border-slate-700/60">' + (u.panel_type || 'تک‌پنل') + '</span>';
        }

        // Render remaining traffic
        let trafficHtml = '';
        if (u.traffic_remaining_bytes === -1 || (u.traffic_total_bytes === 0 && u.traffic_remaining_bytes !== undefined)) {
          trafficHtml = '<span class="inline-flex items-center gap-1 text-xs text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 px-2.5 py-1 rounded-full font-bold"><i class="fa-solid fa-infinity text-[10px]"></i> نامحدود</span>';
        } else if (typeof u.traffic_remaining_bytes === 'number' && u.traffic_remaining_bytes > 0) {
          const gb = (u.traffic_remaining_bytes / (1024 * 1024 * 1024)).toFixed(1);
          trafficHtml = '<span class="text-xs font-mono font-bold text-cyan-400 bg-cyan-950/40 border border-cyan-500/30 px-2.5 py-1 rounded-full inline-flex items-center gap-1">' +
            gb + ' <span class="text-[10px] text-slate-400 font-sans font-normal">GB مانده</span>' +
          '</span>';
        } else {
          trafficHtml = '<span class="text-xs text-slate-500 font-mono">-</span>';
        }

        return '<tr class="hover:bg-slate-800/40 transition">' +
          '<td class="py-3.5 px-4 font-mono font-bold text-emerald-400 text-base">' + u.pin + '</td>' +
          '<td class="py-3.5 px-4 font-medium text-slate-200">' +
            (u.name || 'بدون نام') +
            (!isUserActive ? '<span class="text-rose-400 text-[10px] block font-normal">(غیرفعال)</span>' : '') +
          '</td>' +
          '<td class="py-3.5 px-4">' + panelsHtml + '</td>' +
          '<td class="py-3.5 px-4">' + trafficHtml + '</td>' +
          '<td class="py-3.5 px-4">' + lockStatusHtml + '</td>' +
          '<td class="py-3.5 px-4">' +
            (isExp ? 
              '<span class="text-rose-400 font-medium text-xs bg-rose-500/10 px-2.5 py-1 rounded-full">منقضی شده</span>' : 
              (u.expires_at ? 
                '<span class="text-slate-300 font-medium text-xs">' + daysLeft + ' روز</span>' : 
                '<span class="text-emerald-400 font-medium text-xs bg-emerald-500/10 border border-emerald-500/20 px-2.5 py-1 rounded-full">نامحدود</span>'
              )
            ) +
          '</td>' +
          '<td class="py-3.5 px-4 text-center">' +
            '<div class="flex items-center justify-center gap-1.5">' +
              '<button data-pin="' + u.pin + '" onclick="openEditModal(this.dataset.pin)" title="ویرایش پنل‌ها و مشخصات" class="p-2 text-slate-400 hover:text-indigo-400 hover:bg-slate-800 rounded-lg transition">' +
                '<i class="fa-solid fa-pen-to-square"></i>' +
              '</button>' +
              '<button data-pin="' + u.pin + '" data-name="' + (u.name || '') + '" data-days="' + daysLeft + '" onclick="copyClientMessage(this.dataset.pin, this.dataset.name, this.dataset.days)" title="کپی پیام آماده برای مشتری" class="p-2 text-slate-400 hover:text-emerald-400 hover:bg-slate-800 rounded-lg transition">' +
                '<i class="fa-solid fa-copy"></i>' +
              '</button>' +
              '<button data-pin="' + u.pin + '" onclick="resetDevice(this.dataset.pin)" title="ریست قفل دستگاه (جهت تغییر گوشی)" class="p-2 text-slate-400 hover:text-amber-400 hover:bg-slate-800 rounded-lg transition">' +
                '<i class="fa-solid fa-mobile-screen"></i>' +
              '</button>' +
              '<button data-pin="' + u.pin + '" onclick="unlockUserLockout(this.dataset.pin)" title="رفع قفل و مسدودی ۳۰ دقیقه‌ای ورود" class="p-2 text-slate-400 hover:text-rose-400 hover:bg-slate-800 rounded-lg transition">' +
                '<i class="fa-solid fa-lock-open"></i>' +
              '</button>' +
              '<button data-pin="' + u.pin + '" onclick="extendUser(this.dataset.pin)" title="تمدید ۳۰ روزه" class="p-2 text-slate-400 hover:text-blue-400 hover:bg-slate-800 rounded-lg transition">' +
                '<i class="fa-solid fa-calendar-plus"></i>' +
              '</button>' +
              '<button data-pin="' + u.pin + '" onclick="deleteUser(this.dataset.pin)" title="حذف کاربر" class="p-2 text-slate-400 hover:text-rose-400 hover:bg-slate-800 rounded-lg transition">' +
                '<i class="fa-solid fa-trash-can"></i>' +
              '</button>' +
            '</div>' +
          '</td>' +
        '</tr>';
      }).join('');
    }

    function renderPanelCheckboxes(containerId, selectedIds = []) {
      const container = document.getElementById(containerId);
      if (!container) return;
      if (!cachedPanels || cachedPanels.length === 0) {
        container.innerHTML = '<p class="text-xs text-slate-400 p-2">هیچ پنلی ثبت نشده است. ابتدا در تب پنل‌های پروکسی پنل اضافه کنید.</p>';
        return;
      }
      container.innerHTML = cachedPanels.map(p => {
        const isChecked = Array.isArray(selectedIds) && selectedIds.includes(p.id);
        const isContainer = (p.type === 'container' || p.type === 'raw');
        const typeLabel = isContainer ? 'کانتینر Xray' : p.type;
        const badgeBg = isContainer 
          ? 'bg-fuchsia-900/30 text-fuchsia-300 border-fuchsia-500/40' 
          : 'bg-indigo-900/30 text-indigo-300 border-indigo-500/40';
        return '<label class="flex items-center justify-between p-2 rounded-lg hover:bg-slate-800/60 cursor-pointer border border-transparent hover:border-slate-700 transition select-none">' +
          '<div class="flex items-center gap-2.5">' +
            '<input type="checkbox" value="' + p.id + '" ' + (isChecked ? 'checked' : '') + ' class="w-4 h-4 rounded text-indigo-600 focus:ring-indigo-500 bg-slate-950 border-slate-600 cursor-pointer">' +
            '<span class="text-xs text-slate-200 font-medium">' + p.name + '</span>' +
          '</div>' +
          '<span class="text-[10px] font-mono px-2 py-0.5 rounded border ' + badgeBg + '">' + typeLabel + '</span>' +
        '</label>';
      }).join('');
    }

    function filterUsers() {
      const q = document.getElementById('search-input').value.toLowerCase();
      const filtered = cachedUsers.filter(u => 
        (u.name && u.name.toLowerCase().includes(q)) || 
        (u.pin && u.pin.includes(q))
      );
      renderTable(filtered);
    }

    function setMaxDevicesPreset(inputId, val) {
      const input = document.getElementById(inputId);
      if (input) input.value = val;
    }

    async function resetEditUserDevices() {
      const pin = document.getElementById('edit-pin-val').value;
      if (!pin) return;
      await resetDevice(pin);
      const user = cachedUsers.find(u => u.pin === pin);
      if (user) {
        user.device_id = null;
        user.devices = [];
        const badge = document.getElementById('edit-devices-status-badge');
        if (badge) badge.innerText = '۰ گوشی فعال (قفل‌ها ریست شد)';
      }
    }

    async function unlockEditUserLockout() {
      const pin = document.getElementById('edit-pin-val').value;
      if (!pin) return;
      await unlockUserLockout(pin);
    }

    function openCreateModal() {
      populatePanelsDropdown();
      renderPanelCheckboxes('new-user-panels-container', []);
      document.getElementById('new-user-name').value = '';
      document.getElementById('new-sub-url').value = '';
      document.getElementById('new-custom-pin').value = '';
      document.getElementById('new-max-devices').value = '1';
      document.getElementById('create-modal').classList.remove('hidden');
    }
    function openMigrationModal() { document.getElementById('migration-modal').classList.remove('hidden'); }
    function closeModal(id) { document.getElementById(id).classList.add('hidden'); }

    function openEditModal(pin) {
      const user = cachedUsers.find(u => u.pin === pin);
      if (!user) return;

      document.getElementById('edit-pin-val').value = user.pin;
      document.getElementById('edit-pin-display').innerText = user.pin;
      document.getElementById('edit-user-name').value = user.name || '';
      
      const userPanelIds = Array.isArray(user.panel_ids)
        ? user.panel_ids
        : (user.panel_id ? [user.panel_id] : []);
      renderPanelCheckboxes('edit-user-panels-container', userPanelIds);

      const allUrls = (Array.isArray(user.upstream_sub_urls) && user.upstream_sub_urls.length > 0)
        ? user.upstream_sub_urls.join('\\n')
        : (user.upstream_sub_url || '');
      document.getElementById('edit-sub-url').value = allUrls;
      document.getElementById('edit-user-days').value = '';
      document.getElementById('edit-active-status').value = user.active !== false ? 'true' : 'false';

      const maxDev = (user.max_devices !== undefined && user.max_devices !== null) ? user.max_devices : 1;
      document.getElementById('edit-max-devices').value = maxDev;

      const boundDevices = Array.isArray(user.devices) ? user.devices : (user.device_id ? [user.device_id] : []);
      const badge = document.getElementById('edit-devices-status-badge');
      if (badge) {
        badge.innerText = maxDev <= 0 ? (boundDevices.length + ' گوشی فعال') : (boundDevices.length + ' از ' + maxDev + ' گوشی ثبت شده');
      }

      document.getElementById('edit-modal').classList.remove('hidden');
    }

    async function submitEditUser() {
      const pin = document.getElementById('edit-pin-val').value;
      const name = document.getElementById('edit-user-name').value.trim();
      const subUrl = document.getElementById('edit-sub-url').value.trim();
      const days = document.getElementById('edit-user-days').value.trim();
      const active = document.getElementById('edit-active-status').value === 'true';
      const maxDevices = parseInt(document.getElementById('edit-max-devices').value, 10);

      const panelCheckboxes = document.querySelectorAll('#edit-user-panels-container input[type="checkbox"]:checked');
      const panel_ids = Array.from(panelCheckboxes).map(cb => cb.value);

      Swal.fire({
        title: 'در حال ذخیره تغییرات...',
        allowOutsideClick: false,
        didOpen: () => Swal.showLoading()
      });

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/update', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({
            pin,
            name,
            panel_ids,
            upstream_sub_url: subUrl || undefined,
            days: days || undefined,
            max_devices: isNaN(maxDevices) ? 1 : maxDevices,
            active
          })
        });
        if (ok) {
          closeModal('edit-modal');
          Swal.fire({
            icon: 'success',
            title: 'تغییرات ذخیره شد!',
            text: 'مشخصات کاربر، پنل‌های متصل و تعداد مجاز گوشی‌ها با موفقیت بروزرسانی شد.'
          });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در ویرایش کاربر' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function submitCreateUser() {
      const name = document.getElementById('new-user-name').value.trim();
      const subUrl = document.getElementById('new-sub-url').value.trim();
      const days = document.getElementById('new-user-days').value.trim();
      const pin = document.getElementById('new-custom-pin').value.trim();
      const maxDevices = parseInt(document.getElementById('new-max-devices').value, 10);

      const panelCheckboxes = document.querySelectorAll('#new-user-panels-container input[type="checkbox"]:checked');
      const panel_ids = Array.from(panelCheckboxes).map(cb => cb.value);

      if (panel_ids.length === 0 && !subUrl) {
        Swal.fire({
          icon: 'warning',
          title: 'پنل یا لینکی انتخاب نشده است',
          text: 'لطفاً حداقل یک پنل یا کانتینر را انتخاب کنید، یا در تنظیمات پیشرفته لینک ساب‌اسکریپشن دستی وارد نمایید.'
        });
        return;
      }

      Swal.fire({
        title: 'در حال ایجاد اشتراک...',
        allowOutsideClick: false,
        didOpen: () => Swal.showLoading()
      });

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/create', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({
            name,
            panel_ids,
            upstream_sub_url: subUrl || undefined,
            days: parseInt(days) || 30,
            max_devices: isNaN(maxDevices) ? 1 : maxDevices,
            pin: pin || undefined
          })
        });
        if (ok) {
          closeModal('create-modal');
          Swal.fire({
            icon: 'success',
            title: 'اشتراک با موفقیت ایجاد شد!',
            html: '<p class="text-sm text-slate-300">کد فعال‌سازی اختصاصی:</p><b class="text-emerald-400 text-2xl font-mono block mt-1">' + (data.user ? data.user.pin : pin) + '</b>'
          });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.message_fa || data.error || 'خطا در ثبت کاربر' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function triggerMigrateTo8Pin() {
      const confirmRes = await Swal.fire({
        title: 'تبدیل تمام کدهای قدیمی به ۸ رقمی امن؟',
        text: 'تمامی کدهای ۴ رقمی و کدهای ساده به کدهای ۸ رقمی غیرقابل حدس و تصادفی تبدیل خواهند شد و قفل سخت‌افزاری آن‌ها برای اتصال مجدد ریست می‌شود.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'بله، تبدیل کن',
        cancelButtonText: 'انصراف',
        confirmButtonColor: '#d97706'
      });
      if (!confirmRes.isConfirmed) return;

      Swal.fire({
        title: 'در حال تبدیل کدها...',
        text: 'لطفاً چند لحظه صبر کنید.',
        allowOutsideClick: false,
        didOpen: () => Swal.showLoading()
      });

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/migrate_to_8pin', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken }
        });
        if (ok) {
          let rowsHtml = '';
          (data.migrated_users || []).forEach(function(u) {
            rowsHtml += '<tr>' +
              '<td class="p-2 border border-slate-700">' + (u.name || '') + '</td>' +
              '<td class="p-2 border border-slate-700 font-mono text-slate-400">' + u.old_pin + '</td>' +
              '<td class="p-2 border border-slate-700 font-mono font-bold text-emerald-400">' + u.new_pin + '</td>' +
            '</tr>';
          });

          Swal.fire({
            icon: 'success',
            title: 'تبدیل کدهای اشتراک با موفقیت انجام شد!',
            html: '<p class="text-xs text-slate-300 mb-3">تعداد <b>' + data.migrated_count + '</b> کاربر به کدهای ۸ رقمی امن منتقل شدند:</p>' +
              '<div class="max-h-60 overflow-y-auto text-xs text-right">' +
                '<table class="w-full border-collapse border border-slate-700 text-center">' +
                  '<thead class="bg-slate-800 text-slate-300">' +
                    '<tr><th class="p-2">نام</th><th class="p-2">پین قبلی</th><th class="p-2">پین جدید (۸ رقمی)</th></tr>' +
                  '</thead>' +
                  '<tbody class="text-slate-200">' + rowsHtml + '</tbody>' +
                '</table>' +
              '</div>',
            confirmButtonText: 'بسیار عالی'
          });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا در تبدیل', text: data.message || 'عملیات با خطا مواجه شد.' });
        }
      } catch (err) {
        Swal.fire({ icon: 'error', title: 'خطا در ارتباط', text: err.message });
      }
    }

    async function submitMigration() {
      const oldDomain = document.getElementById('mig-old-domain').value.trim();
      const newDomain = document.getElementById('mig-new-domain').value.trim();
      const panelType = document.getElementById('mig-panel-type').value;

      if (!oldDomain || !newDomain) {
        Swal.fire({ icon: 'warning', title: 'خطا', text: 'دامنه قدیم و جدید الزامی است.' });
        return;
      }

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/migrate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ old_domain: oldDomain, new_domain: newDomain, new_panel_type: panelType || undefined })
        });
        closeModal('migration-modal');
        if (ok) {
          Swal.fire({
            icon: 'success',
            title: 'مهاجرت انجام شد!',
            text: 'تعداد ' + (data.migrated_count || 0) + ' کاربر به دامنه جدید منتقل شدند.'
          });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا در مهاجرت', text: data.error || 'خطایی رخ داد' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function resetDevice(pin) {
      const confirm = await Swal.fire({
        title: 'ریست قفل دستگاه؟',
        text: 'آیا قفل گوشی برای پین ' + pin + ' آزاد شود؟ مشتری می‌تواند با گوشی جدید متصل شود.',
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: 'بله، ریست شود',
        cancelButtonText: 'انصراف'
      });
      if (!confirm.isConfirmed) return;

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/reset_device', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ pin })
        });
        if (ok) {
          Swal.fire({ icon: 'success', title: 'انجام شد', text: 'قفل دستگاه آزاد شد.' });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در ریست قفل دستگاه' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function unlockUserLockout(pin) {
      const confirm = await Swal.fire({
        title: 'رفع قفل ۳۰ دقیقه‌ای؟',
        text: 'آیا مسدودی موقت و قفل ۳۰ دقیقه‌ای برای پین ' + pin + ' برطرف شود؟',
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: 'بله، رفع قفل شود',
        cancelButtonText: 'انصراف'
      });
      if (!confirm.isConfirmed) return;

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/unlock_rate_limit', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ pin })
        });
        if (ok) {
          Swal.fire({ icon: 'success', title: 'انجام شد', text: data.message || 'قفل ۳۰ دقیقه‌ای با موفقیت باز شد.' });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در رفع قفل' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function openUnlockLockoutModal() {
      const { value: formValues } = await Swal.fire({
        title: 'رفع مسدودی و قفل ۳۰ دقیقه',
        html:
          '<p class="text-xs text-slate-400 mb-3 text-right">اگر کاربر چند بار کد اشتباه زده یا برنامه قفل شده است، با وارد کردن پین یا شناسه دستگاه مسدودی فوراً برداشته می‌شود:</p>' +
          '<div class="flex flex-col gap-2 text-right">' +
            '<label class="text-xs text-slate-300">کد ۸ رقمی کاربر (PIN):</label>' +
            '<input id="swal-unlock-pin" class="swal2-input !m-0 !w-full !text-center text-sm font-mono" placeholder="مثال: 36329986">' +
            '<label class="text-xs text-slate-300 mt-2">شناسه دستگاه (Device ID اختیاری):</label>' +
            '<input id="swal-unlock-dev" class="swal2-input !m-0 !w-full !text-center text-xs font-mono" placeholder="اختیاری">' +
          '</div>',
        focusConfirm: false,
        showCancelButton: true,
        confirmButtonText: '🔓 رفع قفل',
        cancelButtonText: 'انصراف',
        preConfirm: () => {
          const pin = document.getElementById('swal-unlock-pin').value.trim();
          const deviceId = document.getElementById('swal-unlock-dev').value.trim();
          if (!pin && !deviceId) {
            Swal.showValidationMessage('حداقل پین یا شناسه دستگاه را وارد کنید');
            return false;
          }
          return { pin, deviceId };
        }
      });

      if (!formValues) return;

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/unlock_rate_limit', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ pin: formValues.pin, device_id: formValues.deviceId })
        });
        if (ok) {
          Swal.fire({ icon: 'success', title: 'قفل برطرف شد', text: data.message || 'مسدودی موقت با موفقیت باز شد.' });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در رفع قفل' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function extendUser(pin) {
      try {
        const { ok, data } = await fetchJson('/api/v1/admin/extend', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ pin, days: 30 })
        });
        if (ok) {
          Swal.fire({ icon: 'success', title: 'تمدید شد', text: '۳۰ روز به اشتراک کاربر اضافه شد.' });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در تمدید اشتراک' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function deleteUser(pin) {
      const confirm = await Swal.fire({
        title: 'حذف کاربر؟',
        text: 'آیا از حذف اشتراک با پین ' + pin + ' مطمئن هستید؟',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#e11d48',
        confirmButtonText: 'بله، حذف شود',
        cancelButtonText: 'انصراف'
      });
      if (!confirm.isConfirmed) return;

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/delete', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ pin })
        });
        if (ok) {
          Swal.fire({ icon: 'success', title: 'حذف شد', text: 'کاربر با موفقیت حذف گردید.' });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در حذف کاربر' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    function copyClientMessage(pin, name, days) {
      const text = 'سلام ' + (name || 'کاربر گرامی') + ' عزیز\\n' +
        'اشتراک آسان وی‌پی‌ان شما با مشخصات زیر فعال شد:\\n\\n' +
        '🔑 کد اختصاصی فعال‌سازی: ' + pin + '\\n' +
        '⏳ مدت اعتبار: ' + days + ' روز\\n\\n' +
        'کافیست برنامه آسان وی‌پی‌ان را باز کرده و کد ۸ رقمی فوق را وارد نمایید.';

      navigator.clipboard.writeText(text);
      Swal.fire({
        icon: 'success',
        title: 'کپی شد!',
        text: 'پیام آماده برای ارسال به مشتری در کلیپ‌بورد کپی شد.',
        timer: 1800,
        showConfirmButton: false
      });
    }

    // =========================================================
    // PROXY PANELS MANAGEMENT LOGIC
    // =========================================================

    async function loadPanels() {
      try {
        const { ok, data } = await fetchJson('/api/v1/admin/panels', {
          headers: { 'X-Admin-Token': currentToken }
        });
        if (!ok) {
          console.error('Failed to load panels:', data);
          return;
        }
        cachedPanels = data.panels || [];
        renderPanels(cachedPanels);
        document.getElementById('badge-nav-panels').innerText = cachedPanels.length + ' پنل';
        const mobPanelsBadge = document.getElementById('badge-nav-panels-mob');
        if (mobPanelsBadge) mobPanelsBadge.innerText = cachedPanels.length;
        if (cachedUsers && cachedUsers.length > 0) {
          renderTable(cachedUsers);
        }
      } catch (err) {
        console.error('Failed to load panels:', err);
      }
    }

    function renderPanels(panels) {
      const grid = document.getElementById('panels-grid');
      if (!panels || panels.length === 0) {
        grid.innerHTML = '<div class="col-span-2 glass-card p-12 text-center text-slate-400">هیچ پنلی ثبت نشده است. روی "افزودن پنل جدید" کلیک کنید.</div>';
        return;
      }

      grid.innerHTML = panels.map(p => {
        const isOnline = p.lastSyncStatus === 'success';
        const isContainer = (p.type === 'container' || p.type === 'raw');
        const typeBadgeColor = {
          pasarguard: 'bg-purple-500/20 text-purple-300 border-purple-500/30',
          '3xui': 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30',
          goguard: 'bg-rose-500/20 text-rose-300 border-rose-500/30',
          guard: 'bg-rose-500/20 text-rose-300 border-rose-500/30',
          marzban: 'bg-amber-500/20 text-amber-300 border-amber-500/30',
          hiddify: 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30',
          container: 'bg-fuchsia-500/20 text-fuchsia-300 border-fuchsia-500/30'
        }[p.type] || 'bg-slate-700 text-slate-300 border-slate-600';

        const lastSyncTimeStr = p.lastSync ? new Date(p.lastSync * 1000).toLocaleTimeString('fa-IR') : 'هنوز انجام نشده';

        return '<div class="glass-card panel-card-hover rounded-2xl p-5 border border-slate-800 flex flex-col justify-between space-y-4 shadow-lg">' +
          '<div class="space-y-3">' +
            '<div class="flex items-start justify-between gap-2">' +
              '<div>' +
                '<div class="flex items-center gap-2">' +
                  '<span class="w-2.5 h-2.5 rounded-full ' + (isOnline ? 'bg-emerald-500 animate-pulse' : (p.lastSync ? 'bg-rose-500' : 'bg-slate-500')) + '"></span>' +
                  '<h4 class="font-bold text-base text-white">' + p.name + '</h4>' +
                '</div>' +
                '<div class="flex items-center gap-1.5 mt-1.5 flex-wrap">' +
                  '<span class="text-[11px] font-mono px-2 py-0.5 rounded-full border uppercase ' + typeBadgeColor + '">' +
                    (isContainer ? 'کانتینر لینک‌های Xray' : p.type) +
                  '</span>' +
                  '<span class="text-[11px] font-bold px-2 py-0.5 rounded-full border bg-amber-500/10 text-amber-300 border-amber-500/30 flex items-center gap-1" title="اولویت اتصال آبشاری">' +
                    '<i class="fa-solid fa-arrow-down-1-9 text-[10px]"></i>' +
                    '<span>اولویت:</span> <span class="font-mono">' + (p.priority || 1) + '</span>' +
                  '</span>' +
                '</div>' +
              '</div>' +
              '<div class="flex items-center gap-1.5">' +
                '<button data-id="' + p.id + '" onclick="testPanelCard(this.dataset.id)" title="تست اتصال زنده" class="p-2 text-slate-400 hover:text-indigo-400 hover:bg-slate-800 rounded-lg transition">' +
                  '<i class="fa-solid fa-bolt"></i>' +
                '</button>' +
                '<button data-id="' + p.id + '" onclick="openEditPanelModal(this.dataset.id)" title="ویرایش پنل" class="p-2 text-slate-400 hover:text-blue-400 hover:bg-slate-800 rounded-lg transition">' +
                  '<i class="fa-solid fa-pen-to-square"></i>' +
                '</button>' +
                '<button data-id="' + p.id + '" onclick="deletePanel(this.dataset.id)" title="حذف پنل" class="p-2 text-slate-400 hover:text-rose-400 hover:bg-slate-800 rounded-lg transition">' +
                  '<i class="fa-solid fa-trash-can"></i>' +
                '</button>' +
              '</div>' +
            '</div>' +

            '<div class="bg-slate-900/80 rounded-xl p-3 border border-slate-800 text-xs font-mono space-y-1.5 overflow-hidden">' +
              '<div class="flex items-center justify-between text-slate-400 min-w-0 gap-2">' +
                '<span class="text-slate-500 text-[10px] shrink-0">' + (isContainer ? 'لینک کانتینر:' : 'آدرس API:') + '</span>' +
                '<span class="text-slate-300 truncate min-w-0 text-left font-mono" dir="ltr">' + p.url + '</span>' +
              '</div>' +
              (p.subBaseUrl ? (
                '<div class="flex items-center justify-between text-slate-400 min-w-0 gap-2">' +
                  '<span class="text-slate-500 text-[10px] shrink-0">قالب ساب:</span>' +
                  '<span class="text-slate-300 truncate min-w-0 text-left font-mono" dir="ltr">' + p.subBaseUrl + '</span>' +
                '</div>'
              ) : '') +
            '</div>' +

            '<div class="flex items-center justify-between text-xs pt-1">' +
              '<span class="text-slate-400 flex items-center gap-1.5">' +
                '<i class="fa-solid ' + (isContainer ? 'fa-box-archive' : 'fa-users') + ' text-indigo-400"></i>' +
                '<span>' + (isContainer ? 'نودهای فعال: ' : 'کاربران متصل: ') + '<b class="text-white">' + (p.userCount || 0) + (isContainer ? ' نود' : '') + '</b></span>' +
              '</span>' +
              '<span class="text-slate-400 flex items-center gap-1.5">' +
                '<i class="fa-solid fa-clock-rotate-left text-slate-500"></i>' +
                '<span>آخرین سینک: <b class="text-slate-300">' + lastSyncTimeStr + '</b></span>' +
              '</span>' +
            '</div>' +

            (p.lastSyncMessage ? (
              '<p class="text-[11px] truncate ' + (p.lastSyncStatus === 'success' ? 'text-emerald-400/90' : 'text-rose-400/90') + '">' +
                '<i class="fa-solid ' + (p.lastSyncStatus === 'success' ? 'fa-check' : 'fa-circle-exclamation') + ' ml-1"></i>' +
                p.lastSyncMessage +
              '</p>'
            ) : '') +
          '</div>' +

          '<div class="pt-3 border-t border-slate-800/80 flex items-center justify-between">' +
            '<span class="text-[11px] text-slate-400 flex items-center gap-1">' +
              '<i class="fa-solid fa-rotate text-emerald-400"></i>' +
              '<span>سینک خودکار (هر ۱۵ دقیقه):</span> ' +
              '<b class="' + (p.autoSync !== false ? 'text-emerald-400' : 'text-slate-500') + '">' + (p.autoSync !== false ? 'فعال' : 'غیرفعال') + '</b>' +
            '</span>' +
            '<button data-id="' + p.id + '" onclick="syncSinglePanel(this.dataset.id)" class="bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-bold px-3.5 py-1.5 rounded-xl shadow-md transition flex items-center gap-1.5">' +
              '<i class="fa-solid fa-arrows-rotate"></i>' +
              '<span>همگام‌سازی فوری</span>' +
            '</button>' +
          '</div>' +
        '</div>';
      }).join('');
    }

    function onPanelTypeChange() {
      const type = document.getElementById('panel-form-type').value;
      const hintUrl = document.getElementById('panel-url-hint');
      const hintKey = document.getElementById('panel-key-hint');
      const subBase = document.getElementById('panel-form-sub-base');
      const apiKeyCont = document.getElementById('panel-form-api-key-container');
      const subBaseCont = document.getElementById('panel-form-sub-base-container');

      if (type === 'container') {
        hintUrl.innerText = 'آدرس کامل لینک Raw در گیت‌هاب یا فایل متنی حاوی کانفیگ‌های VLESS, VMess, Trojan, SS';
        document.getElementById('panel-form-url').placeholder = 'https://raw.githubusercontent.com/... یا https://example.com/sub';
        if (apiKeyCont) apiKeyCont.style.display = 'none';
        if (subBaseCont) subBaseCont.style.display = 'none';
      } else {
        if (apiKeyCont) apiKeyCont.style.display = 'block';
        if (subBaseCont) subBaseCont.style.display = 'block';
        if (type === 'pasarguard') {
          hintUrl.innerText = 'مثال: https://irtun.darklink.ir:8000';
          hintKey.innerText = 'مقدار API Key پاسارگارد (مثال: pg_key_f85f5abb-...)';
          subBase.placeholder = 'https://irsub.trafficfast.ir:9090';
        } else if (type === '3xui') {
          hintUrl.innerText = 'مثال: https://13.140.94.235:14393/eX6Vvc2csV2GKtzmPF';
          hintKey.innerText = 'کوکی سشن (session=...) یا توکن API';
          subBase.placeholder = 'https://13.140.94.235:2096/xx11fs28c0ea3gr5/{subId}';
        } else if (type === 'goguard' || type === 'guard') {
          hintUrl.innerText = 'مثال: https://core.erfjab.com';
          hintKey.innerText = 'مقدار API Key (X-API-Key)';
          subBase.placeholder = '';
        } else if (type === 'marzban') {
          hintUrl.innerText = 'مثال: https://my-marzban.example.com:8000';
          hintKey.innerText = 'Bearer Access Token ادمین مرزبان';
          subBase.placeholder = 'https://sub.example.com/sub/';
        } else if (type === 'hiddify') {
          hintUrl.innerText = 'مثال: https://my-hiddify.example.com/secret_path/';
          hintKey.innerText = 'Admin API Secret هیدیفای';
          subBase.placeholder = '';
        }
      }
    }

    function openAddPanelModal() {
      document.getElementById('panel-form-id').value = '';
      document.getElementById('panel-modal-title').innerText = 'افزودن پنل پروکسی جدید';
      document.getElementById('panel-form-name').value = '';
      document.getElementById('panel-form-type').value = 'pasarguard';
      document.getElementById('panel-form-url').value = '';
      document.getElementById('panel-form-api-key').value = '';
      document.getElementById('panel-form-sub-base').value = '';
      document.getElementById('panel-form-priority').value = (cachedPanels.length + 1);
      document.getElementById('panel-form-auto-sync').checked = true;
      onPanelTypeChange();
      document.getElementById('panel-modal').classList.remove('hidden');
    }

    function openEditPanelModal(panelId) {
      const panel = cachedPanels.find(p => p.id === panelId);
      if (!panel) return;

      document.getElementById('panel-form-id').value = panel.id;
      document.getElementById('panel-modal-title').innerText = 'ویرایش مشخصات پنل: ' + panel.name;
      document.getElementById('panel-form-name').value = panel.name || '';
      document.getElementById('panel-form-type').value = panel.type || 'pasarguard';
      document.getElementById('panel-form-url').value = panel.url || '';
      document.getElementById('panel-form-api-key').value = panel.apiKey || '';
      document.getElementById('panel-form-sub-base').value = panel.subBaseUrl || '';
      document.getElementById('panel-form-priority').value = panel.priority || 1;
      document.getElementById('panel-form-auto-sync').checked = panel.autoSync !== false;
      onPanelTypeChange();
      document.getElementById('panel-modal').classList.remove('hidden');
    }

    async function testPanelFormConnection() {
      const type = document.getElementById('panel-form-type').value;
      const url = document.getElementById('panel-form-url').value.trim();
      const apiKey = document.getElementById('panel-form-api-key').value.trim();

      if (!url) {
        Swal.fire({ icon: 'warning', title: 'خطا', text: 'لطفاً آدرس پنل را وارد نمایید.' });
        return;
      }

      Swal.fire({
        title: 'در حال بررسی اتصال...',
        text: 'ارتباط مستقیم با API پنل در حال سنجش است',
        allowOutsideClick: false,
        didOpen: () => Swal.showLoading()
      });

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/panels/test', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ type, url, apiKey })
        });
        if (ok && data.ok) {
          Swal.fire({
            icon: 'success',
            title: 'اتصال موفق!',
            html: '<p class="text-sm">' + (data.message || 'ارتباط با پنل با موفقیت برقرار شد.') + '</p><p class="text-xs text-slate-400 mt-2 font-mono">پینگ سرور: ' + data.latency + ' میلی‌ثانیه</p>'
          });
        } else {
          Swal.fire({
            icon: 'error',
            title: 'خطا در برقراری ارتباط',
            text: data.error || 'پاسخی از پنل دریافت نشد.'
          });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function testPanelCard(panelId) {
      const panel = cachedPanels.find(p => p.id === panelId);
      if (!panel) return;

      Swal.fire({
        title: 'در حال تست پینگ ' + panel.name + '...',
        didOpen: () => Swal.showLoading(),
        allowOutsideClick: false
      });

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/panels/test', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify(panel)
        });
        if (ok && data.ok) {
          Swal.fire({
            icon: 'success',
            title: 'سرور آنلاین است!',
            html: '<p class="text-sm">' + data.message + '</p><p class="text-xs text-slate-400 mt-2 font-mono">پینگ: ' + data.latency + ' میلی‌ثانیه</p>'
          });
        } else {
          Swal.fire({
            icon: 'error',
            title: 'قطع اتصال',
            text: data.error || 'خطا در ارتباط با سرور'
          });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function savePanelForm() {
      const id = document.getElementById('panel-form-id').value;
      const name = document.getElementById('panel-form-name').value.trim();
      const type = document.getElementById('panel-form-type').value;
      const url = document.getElementById('panel-form-url').value.trim();
      const apiKey = document.getElementById('panel-form-api-key').value.trim();
      const subBaseUrl = document.getElementById('panel-form-sub-base').value.trim();
      const priority = parseInt(document.getElementById('panel-form-priority').value, 10) || 1;
      const autoSync = document.getElementById('panel-form-auto-sync').checked;

      if (!name || !url) {
        Swal.fire({ icon: 'warning', title: 'خطا', text: 'نام و آدرس پنل الزامی هستند.' });
        return;
      }

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/panels/save', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ id: id || undefined, name, type, url, apiKey, subBaseUrl, priority, autoSync })
        });
        if (ok) {
          closeModal('panel-modal');
          Swal.fire({
            icon: 'success',
            title: 'پنل ذخیره شد!',
            text: 'مشخصات پنل ذخیره شد. مایلید هم‌اکنون همگام‌سازی کاربران انجام شود؟',
            showCancelButton: true,
            confirmButtonText: 'بله، همگام‌سازی کن',
            cancelButtonText: 'بعداً'
          }).then(result => {
            if (result.isConfirmed) {
              syncSinglePanel(data.panel.id);
            }
          });
          loadPanels();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در ذخیره پنل' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function deletePanel(panelId) {
      const confirm = await Swal.fire({
        title: 'حذف پنل پروکسی؟',
        text: 'آیا از حذف این پنل اطمینان دارید؟ کاربران قبلی در سیستم باقی می‌مانند.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#e11d48',
        confirmButtonText: 'بله، حذف شود',
        cancelButtonText: 'انصراف'
      });
      if (!confirm.isConfirmed) return;

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/panels/delete', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ id: panelId })
        });
        if (ok) {
          Swal.fire({ icon: 'success', title: 'حذف شد', text: 'پنل مورد نظر حذف گردید.' });
          loadPanels();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در حذف پنل' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function syncSinglePanel(panelId) {
      const panel = cachedPanels.find(p => p.id === panelId);
      const panelName = panel ? panel.name : 'پنل';

      Swal.fire({
        title: 'در حال همگام‌سازی با ' + panelName + '...',
        text: 'در حال دریافت کلاینت‌ها و بروزرسانی لینک‌های اشتراک',
        didOpen: () => Swal.showLoading(),
        allowOutsideClick: false
      });

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/panels/sync', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ id: panelId })
        });
        if (ok && data.success !== false) {
          Swal.fire({
            icon: 'success',
            title: 'همگام‌سازی موفق!',
            html: '<div class="space-y-1 text-sm">' +
              '<p>تعداد کل کاربران پنل: <b class="text-white">' + (data.total || 0) + '</b></p>' +
              '<p class="text-emerald-400">کاربران جدید اضافه شده: <b>' + (data.added || 0) + '</b></p>' +
              '<p class="text-blue-400">کاربران بروزرسانی شده: <b>' + (data.updated || 0) + '</b></p>' +
              '</div>'
          });
          loadPanels();
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا در همگام‌سازی', text: data.error || 'خطا در ارتباط با پنل' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      }
    }

    async function syncAllPanels() {
      const icon = document.getElementById('sync-all-icon');
      if (icon) icon.classList.add('fa-spin');

      Swal.fire({
        title: 'در حال همگام‌سازی همه پنل‌ها...',
        text: 'دریافت آخرین کاربران از تمامی پنل‌های متصل',
        didOpen: () => Swal.showLoading(),
        allowOutsideClick: false
      });

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/panels/sync', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({})
        });
        if (ok && data.success !== false) {
          Swal.fire({
            icon: 'success',
            title: 'همگام‌سازی همه پنل‌ها انجام شد!',
            html: '<div class="space-y-1 text-sm">' +
              '<p class="text-emerald-400">کاربران جدید ایجاد شده: <b>' + (data.totalAdded || 0) + '</b></p>' +
              '<p class="text-blue-400">کاربران بروزرسانی شده: <b>' + (data.totalUpdated || 0) + '</b></p>' +
              '</div>'
          });
          loadPanels();
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در همگام‌سازی' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      } finally {
        if (icon) icon.classList.remove('fa-spin');
      }
    }

    // =========================================================
    // AUTO-PICK REMOTE USER IN CREATE MODAL
    // =========================================================

    function populatePanelsDropdown() {
      const select = document.getElementById('auto-pick-panel');
      select.innerHTML = '<option value="">-- انتخاب پنل سرور --</option>' +
        cachedPanels.map(p => '<option value="' + p.id + '">' + p.name + ' (' + p.type + ')</option>').join('');
      document.getElementById('auto-pick-user').disabled = true;
      document.getElementById('auto-pick-user').innerHTML = '<option value="">-- ابتدا پنل را انتخاب کنید --</option>';
    }

    async function onAutoPickPanelChange() {
      const panelId = document.getElementById('auto-pick-panel').value;
      const userSelect = document.getElementById('auto-pick-user');

      if (!panelId) {
        userSelect.disabled = true;
        userSelect.innerHTML = '<option value="">-- ابتدا پنل را انتخاب کنید --</option>';
        return;
      }

      userSelect.disabled = true;
      userSelect.innerHTML = '<option value="">در حال بارگذاری کاربران پنل...</option>';

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/panels/remote_users?panel_id=' + encodeURIComponent(panelId), {
          headers: { 'X-Admin-Token': currentToken }
        });
        const users = (ok && data.users) ? data.users : [];

        if (users.length === 0) {
          userSelect.innerHTML = '<option value="">هیچ کاربری در این پنل یافت نشد</option>';
          return;
        }

        userSelect.disabled = false;
        userSelect.innerHTML = '<option value="">-- انتخاب کاربر (' + users.length + ' کاربر) --</option>' +
          users.map((u, i) => '<option value="' + i + '" data-name="' + (u.name || '') + '" data-sub="' + (u.subUrl || '') + '" data-type="' + (u.panelType || '') + '">' + (u.name || u.username) + '</option>').join('');

        // Store temp list
        window.currentRemoteUsers = users;
      } catch (e) {
        userSelect.innerHTML = '<option value="">خطا در بارگذاری کاربران</option>';
      }
    }

    function onAutoPickUserChange() {
      const userSelect = document.getElementById('auto-pick-user');
      const panelSelect = document.getElementById('auto-pick-panel');
      const idx = userSelect.value;
      if (idx === '' || !window.currentRemoteUsers) return;

      const user = window.currentRemoteUsers[parseInt(idx, 10)];
      if (user) {
        document.getElementById('new-user-name').value = user.name || user.username || '';
        const subInput = document.getElementById('new-sub-url');
        if (subInput) subInput.value = user.subUrl || '';
        
        // Auto-check this panel in the panel checklist
        const chosenPanelId = panelSelect ? panelSelect.value : null;
        if (chosenPanelId) {
          const cb = document.querySelector('#new-user-panels-container input[value="' + chosenPanelId + '"]');
          if (cb) cb.checked = true;
        }
      }
    }

    // =========================================================
    // CHANGE PASSWORD LOGIC
    // =========================================================

    function openChangePasswordModal() {
      document.getElementById('cp-current').value = '';
      document.getElementById('cp-new').value = '';
      document.getElementById('cp-confirm').value = '';
      document.getElementById('change-password-modal').classList.remove('hidden');
    }

    async function submitChangePassword() {
      const currentPassword = document.getElementById('cp-current').value.trim();
      const newPassword = document.getElementById('cp-new').value.trim();
      const confirmPassword = document.getElementById('cp-confirm').value.trim();

      if (!currentPassword || !newPassword) {
        Swal.fire({ icon: 'warning', title: 'خطا', text: 'لطفاً تمام فیلدها را وارد نمایید.' });
        return;
      }
      if (newPassword.length < 6) {
        Swal.fire({ icon: 'warning', title: 'خطا', text: 'رمز عبور جدید باید حداقل ۶ کاراکتر باشد.' });
        return;
      }
      if (newPassword !== confirmPassword) {
        Swal.fire({ icon: 'warning', title: 'خطا', text: 'رمز عبور جدید با تکرار آن همخوانی ندارد.' });
        return;
      }

      const btn = document.getElementById('cp-submit-btn');
      btn.disabled = true;
      btn.innerText = 'در حال ذخیره...';

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/change_password', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({ currentPassword, newPassword })
        });

        if (ok && data.success) {
          closeModal('change-password-modal');
          currentToken = newPassword;
          localStorage.setItem('asanvpn_admin_token', newPassword);
          Swal.fire({
            icon: 'success',
            title: 'رمز عبور با موفقیت تغییر کرد!',
            text: 'رمز عبور جدید با موفقیت در سیستم ثبت گردید و رمزهای پیش‌فرض منقضی شدند.'
          });
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در تغییر رمز عبور' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      } finally {
        btn.disabled = false;
        btn.innerText = 'ذخیره رمز جدید';
      }
    }

    // =========================================================
    // MERGE DUPLICATES LOGIC
    // =========================================================

    async function triggerMergeDuplicates() {
      const res = await Swal.fire({
        title: 'ادغام کاربران همنام در چند پنل',
        text: 'آیا مایلید کاربرانی که در پنل‌های مختلف نام کاربری یکسانی دارند (مانند admin) در قالب یک پین فعال‌سازی با چندین لینک اشتراک تجمیع شوند؟',
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: 'بله، ادغام کن',
        cancelButtonText: 'انصراف',
        confirmButtonColor: '#7c3aed'
      });

      if (!res.isConfirmed) return;

      const btn = document.getElementById('merge-dups-btn');
      if (btn) btn.disabled = true;

      try {
        const { ok, data } = await fetchJson('/api/v1/admin/merge_duplicates', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': currentToken },
          body: JSON.stringify({})
        });

        if (ok && data.success) {
          Swal.fire({
            icon: 'success',
            title: 'ادغام همنام‌ها انجام شد!',
            html: '<div class="space-y-1 text-sm">' +
                  '<p class="text-emerald-400 font-bold">تعداد کاربران ادغام شده: ' + (data.mergedCount || 0) + ' نفر</p>' +
                  '<p class="text-xs text-slate-400 mt-2">' + (data.message || '') + '</p>' +
                  '</div>'
          });
          loadUsers();
        } else {
          Swal.fire({ icon: 'error', title: 'خطا', text: data.error || 'خطا در انجام عملیات ادغام' });
        }
      } catch (e) {
        Swal.fire({ icon: 'error', title: 'خطا', text: e.message });
      } finally {
        if (btn) btn.disabled = false;
      }
    }

  </script>
</body>
</html>
`;
}
