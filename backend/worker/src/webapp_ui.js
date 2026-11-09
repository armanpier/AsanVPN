/**
 * WebApp UI: Interactive Telegram Mini App styled with the retro layered wave palette.
 * Colors extracted from user's artwork:
 *  - Midnight Plum: #2F242D / #1B192E
 *  - Slate Denim: #58617D / #4B82A0
 *  - Ochre Gold: #DAAA6A / #D3AC80
 *  - Terracotta: #CB7753 / #B86A57
 *  - Deep Crimson: #914341 / #B8335A
 */

export function getWebAppHtml() {
  return `<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>آسان وی‌پی‌ان | منوی هوشمند</title>
  <script src="https://telegram.org/js/telegram-web-app.js"></script>
  <style>
    :root {
      --bg-midnight: #1B192E;
      --plum: #2F242D;
      --slate-blue: #58617D;
      --ochre-gold: #DAAA6A;
      --terracotta: #CB7753;
      --crimson: #914341;
      --cyan: #4B82A0;
    }

    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
      font-family: system-ui, -apple-system, 'Segoe UI', Roboto, Tahoma, sans-serif;
      -webkit-tap-highlight-color: transparent;
    }

    body {
      background: linear-gradient(180deg, #1B192E 0%, #2F242D 100%);
      color: #FFFFFF;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 16px;
      overflow-x: hidden;
    }

    /* Wave visual header matching the user's palette */
    .wave-container {
      width: 100%;
      max-width: 440px;
      margin-bottom: 18px;
      position: relative;
      border-radius: 20px;
      overflow: hidden;
      box-shadow: 0 10px 30px rgba(0,0,0,0.45);
      background: #141220;
    }

    .wave-header {
      padding: 24px 20px 14px;
      text-align: center;
      position: relative;
      z-index: 2;
    }

    .wave-header h1 {
      font-size: 22px;
      font-weight: 800;
      letter-spacing: -0.5px;
      margin-bottom: 6px;
      color: #FFFFFF;
      text-shadow: 0 2px 8px rgba(0,0,0,0.6);
    }

    .wave-header p {
      font-size: 13px;
      color: #E0D8D0;
      opacity: 0.9;
    }

    .badge-2dev {
      display: inline-block;
      margin-top: 8px;
      background: rgba(218, 170, 106, 0.25);
      border: 1px solid var(--ochre-gold);
      color: var(--ochre-gold);
      font-size: 11px;
      font-weight: 700;
      padding: 4px 12px;
      border-radius: 12px;
    }

    /* Layered wave bands svg */
    .wave-art {
      width: 100%;
      height: 70px;
      display: block;
      margin-top: -10px;
    }

    /* Button stack */
    .buttons-grid {
      width: 100%;
      max-width: 440px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      position: relative;
      z-index: 3;
    }

    .btn {
      width: 100%;
      padding: 15px 18px;
      border-radius: 16px;
      border: none;
      font-size: 15px;
      font-weight: 700;
      color: #FFFFFF;
      display: flex;
      align-items: center;
      justify-content: space-between;
      cursor: pointer;
      text-decoration: none;
      box-shadow: 0 6px 18px rgba(0,0,0,0.3);
      transition: transform 0.15s ease, box-shadow 0.15s ease, filter 0.15s ease;
    }

    .btn:active {
      transform: scale(0.97);
      filter: brightness(0.92);
    }

    .btn-icon {
      font-size: 20px;
      margin-left: 10px;
    }

    .btn-label {
      flex: 1;
      text-align: right;
    }

    .btn-arrow {
      font-size: 14px;
      opacity: 0.8;
      margin-right: 4px;
    }

    /* Distinct palette colors for each button */
    .btn-gold {
      background: linear-gradient(135deg, #E5B26E 0%, #CB8E3A 100%);
      color: #241400;
      box-shadow: 0 6px 20px rgba(218, 170, 106, 0.35);
    }
    .btn-gold .btn-arrow { color: #241400; }

    .btn-terracotta {
      background: linear-gradient(135deg, #DE7652 0%, #B8502D 100%);
      box-shadow: 0 6px 20px rgba(203, 119, 83, 0.35);
    }

    .btn-blue {
      background: linear-gradient(135deg, #667699 0%, #465474 100%);
      box-shadow: 0 6px 20px rgba(88, 97, 125, 0.35);
    }

    .btn-purple {
      background: linear-gradient(135deg, #4A3E50 0%, #2A2230 100%);
      border: 1px solid rgba(255,255,255,0.12);
      box-shadow: 0 6px 20px rgba(47, 36, 45, 0.4);
    }

    .btn-crimson {
      background: linear-gradient(135deg, #A84550 0%, #7A2832 100%);
      box-shadow: 0 6px 20px rgba(145, 67, 65, 0.35);
    }

    .btn-restart {
      background: linear-gradient(135deg, #38708C 0%, #244C60 100%);
      box-shadow: 0 6px 20px rgba(75, 130, 160, 0.35);
    }

    /* Footer info */
    .footer-note {
      margin-top: 18px;
      font-size: 12px;
      color: rgba(255,255,255,0.55);
      text-align: center;
      line-height: 1.6;
    }
  </style>
</head>
<body>

  <div class="wave-container">
    <div class="wave-header">
      <h1>🛡️ آسان وی‌پی‌ان (AsanVPN)</h1>
      <p>اینترنت پرسرعت، پایدار و بدون قطعی</p>
      <div class="badge-2dev">🔒 قفل هوشمند اختصاصی روی ۲ دستگاه همزمان</div>
    </div>
    <!-- Layered wave graphic replicating the user's image palette -->
    <svg class="wave-art" viewBox="0 0 500 120" preserveAspectRatio="none">
      <path d="M0,40 C150,90 350,10 500,50 L500,120 L0,120 Z" fill="#914341" opacity="0.95"></path>
      <path d="M0,55 C160,110 330,30 500,75 L500,120 L0,120 Z" fill="#CB7753" opacity="0.95"></path>
      <path d="M0,75 C140,120 360,50 500,95 L500,120 L0,120 Z" fill="#DAAA6A" opacity="0.95"></path>
      <path d="M0,95 C170,125 320,80 500,110 L500,120 L0,120 Z" fill="#58617D" opacity="1.0"></path>
    </svg>
  </div>

  <div class="buttons-grid">
    <button class="btn btn-gold" onclick="sendAction('/plans')">
      <span class="btn-icon">🟡</span>
      <span class="btn-label">خرید اشتراک جدید (۲ دستگاه)</span>
      <span class="btn-arrow">◀</span>
    </button>

    <button class="btn btn-terracotta" onclick="sendAction('/renew')">
      <span class="btn-icon">🟠</span>
      <span class="btn-label">تمدید و شارژ اشتراک فعال</span>
      <span class="btn-arrow">◀</span>
    </button>

    <button class="btn btn-blue" onclick="sendAction('/status')">
      <span class="btn-icon">🔵</span>
      <span class="btn-label">استعلام مانده حجم و روزها</span>
      <span class="btn-arrow">◀</span>
    </button>

    <button class="btn btn-purple" onclick="openLink('https://github.com/armanpier/AsanVPN/releases/latest')">
      <span class="btn-icon">🟣</span>
      <span class="btn-label">دانلود اپلیکیشن اندروید</span>
      <span class="btn-arrow">↗</span>
    </button>

    <button class="btn btn-crimson" onclick="openLink('https://t.me/Sib_Support_P')">
      <span class="btn-icon">🔴</span>
      <span class="btn-label">ارتباط با پشتیبانی ۲۴ ساعته</span>
      <span class="btn-arrow">↗</span>
    </button>

    <button class="btn btn-restart" onclick="sendAction('/start')">
      <span class="btn-icon">🔄</span>
      <span class="btn-label">شروع مجدد ربات (Restart)</span>
      <span class="btn-arrow">◀</span>
    </button>
  </div>

  <div class="footer-note">
    ⚡ طراحی شده بر پایه پالت رنگی رترو ویو آسان وی‌پی‌ان<br>
    ربات رسمی: @ASANVPNBOT_AV
  </div>

  <script>
    const tg = window.Telegram?.WebApp;
    if (tg) {
      tg.ready();
      tg.expand();
    }

    function sendAction(cmd) {
      if (tg) {
        tg.sendData(cmd);
        tg.close();
      } else {
        window.location.href = 'https://t.me/asan_vpn_p_bot?start=' + cmd.replace('/', '');
      }
    }

    function openLink(url) {
      if (tg && tg.openLink) {
        tg.openLink(url);
      } else {
        window.open(url, '_blank');
      }
    }
  </script>
</body>
</html>`;
}
