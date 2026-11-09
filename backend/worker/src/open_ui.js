/**
 * Returns the HTML for the /open auto-connect redirect landing page.
 * Copies the PIN to clipboard and launches the AsanVPN Android app via deep link.
 */
export function getOpenAppHtml(pin = '') {
  const safePin = String(pin).replace(/[^0-9]/g, '');
  
  return `<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>اتصال به آسان وی‌پی‌ان</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Vazirmatn:wght@400;600;700;800;900&family=JetBrains+Mono:wght@700;800&display=swap" rel="stylesheet">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background-color: #F0F3F8;
      font-family: 'Vazirmatn', -apple-system, BlinkMacSystemFont, sans-serif;
      color: #1E293B;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      padding: 20px;
    }
    .card {
      background: #FFFFFF;
      border-radius: 32px;
      box-shadow: 14px 14px 32px #CBD5E1, -14px -14px 32px #FFFFFF;
      padding: 40px 28px;
      max-width: 420px;
      width: 100%;
      text-align: center;
    }
    .logo-container {
      width: 88px;
      height: 88px;
      margin: 0 auto 20px;
      border-radius: 50%;
      background: #F0F3F8;
      box-shadow: inset 4px 4px 8px #CBD5E1, inset -4px -4px 8px #FFFFFF;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 42px;
    }
    h1 {
      font-size: 22px;
      font-weight: 900;
      color: #004E64;
      margin-bottom: 8px;
    }
    .subtext {
      font-size: 14px;
      color: #64748B;
      line-height: 1.6;
      margin-bottom: 24px;
    }
    .pin-card {
      background: #F8FAFC;
      border-radius: 20px;
      padding: 18px;
      box-shadow: inset 3px 3px 6px #CBD5E1, inset -3px -3px 6px #FFFFFF;
      margin-bottom: 24px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .pin-digits {
      font-family: 'JetBrains Mono', monospace;
      font-size: 28px;
      font-weight: 800;
      letter-spacing: 4px;
      color: #004E64;
    }
    .copy-btn {
      background: #FF6F52;
      color: #FFFFFF;
      border: none;
      border-radius: 12px;
      padding: 10px 18px;
      font-weight: 700;
      cursor: pointer;
      font-family: inherit;
      font-size: 13px;
      box-shadow: 0 4px 12px rgba(255, 111, 82, 0.35);
      transition: all 0.2s ease;
    }
    .copy-btn:active {
      transform: scale(0.96);
    }
    .btn-open {
      display: block;
      width: 100%;
      background: linear-gradient(135deg, #FF6F52, #FF8566);
      color: #FFFFFF;
      text-decoration: none;
      padding: 16px;
      border-radius: 18px;
      font-weight: 800;
      font-size: 16px;
      box-shadow: 0 8px 24px rgba(255, 111, 82, 0.38);
      margin-bottom: 12px;
      transition: all 0.2s ease;
    }
    .btn-open:active {
      transform: scale(0.98);
    }
    .btn-download {
      display: block;
      width: 100%;
      background: #F0F3F8;
      color: #004E64;
      text-decoration: none;
      padding: 14px;
      border-radius: 18px;
      font-weight: 700;
      font-size: 14px;
      box-shadow: 5px 5px 12px #CBD5E1, -5px -5px 12px #FFFFFF;
      transition: all 0.2s ease;
    }
    .status-msg {
      margin-top: 18px;
      font-size: 13px;
      color: #059669;
      font-weight: 700;
      min-height: 20px;
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="logo-container">🚀</div>
    <h1>اتصال به آسان وی‌پی‌ان</h1>
    <p class="subtext">کد اختصاصی شما کپی شد و اپلیکیشن به صورت خودکار در حال فراخوانی است...</p>

    <div class="pin-card">
      <span class="pin-digits" id="pinText">${safePin || '--------'}</span>
      <button class="copy-btn" onclick="copyPin()">📋 کپی</button>
    </div>

    <a href="asanvpn://open?pin=${safePin}" class="btn-open" id="openLink">⚡ باز کردن اپلیکیشن آسان وی‌پی‌ان</a>
    <a href="/download" class="btn-download">📥 دانلود نسخه جدید اپلیکیشن</a>

    <div class="status-msg" id="statusMsg">در حال انتقال به برنامه...</div>
  </div>

  <script>
    const pin = "${safePin}";
    function copyPin() {
      if (pin && navigator.clipboard) {
        navigator.clipboard.writeText(pin).then(() => {
          document.getElementById('statusMsg').innerText = '✅ کد اشتراک ' + pin + ' کپی شد!';
        }).catch(() => {});
      }
    }
    
    // Auto-copy on load
    copyPin();

    // Trigger deep link automatically
    if (pin) {
      setTimeout(() => {
        window.location.href = "asanvpn://open?pin=" + pin;
      }, 350);
    }
  </script>
</body>
</html>`;
}
