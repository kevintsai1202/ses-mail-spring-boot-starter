// 宣傳頁本機預覽：模擬 Artifact 發佈時包上的外框（doctype、viewport、基礎樣式），
// 把頁面引用的 img/ 對應到 docs/images/，輸出桌機淺色、手機深色兩張全頁截圖，
// 並檢查橫向捲動與 console 錯誤。截圖存到 logs/promo-preview/（已在 .gitignore）。
//
// 用法（repo 根目錄，需全域安裝 playwright）：
//   node docs/promo/preview.mjs                 # 本機原始檔
//   node docs/promo/preview.mjs <網址>          # 已部署的網頁（例如 GitHub Pages），不做本機對應
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import { readFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// repo 根目錄與輸入、輸出位置
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const pagePath = path.join(root, 'docs', 'promo', 'mail-architecture.html');
const imagesDir = path.join(root, 'docs', 'images');
const outDir = path.join(root, 'logs', 'promo-preview');
mkdirSync(outDir, { recursive: true });

// 全域 playwright 的 ESM 進入點在本機不穩，改用 createRequire 載 CJS
const globalRoot = execSync('npm root -g').toString().trim();
const require = createRequire(path.join(globalRoot, 'noop.js'));
const { chromium } = require('playwright');

// Artifact 發佈時的外框（與線上版相同的 head 與 reset）
const skeleton = (body) => `<!doctype html><html><head><meta charset=utf8><meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover"><style>:root{color-scheme:light;box-sizing:border-box;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}html{scroll-padding-top:env(safe-area-inset-top,0px)}body{margin:0;padding:0;font:14px -apple-system,BlinkMacSystemFont,sans-serif;background:#fff;color:#000}img{max-width:100%}[hidden]:not([hidden=until-found i]){display:none!important}</style></head><body>${body}</body></html>`;
const html = skeleton(readFileSync(pagePath, 'utf8'));

// 兩種截圖情境：桌機淺色、手機深色
const shots = [
  { name: 'desktop-light', viewport: { width: 1280, height: 900 }, colorScheme: 'light' },
  { name: 'phone-dark', viewport: { width: 390, height: 844 }, colorScheme: 'dark' },
];

const browser = await chromium.launch();
let failed = false;
for (const shot of shots) {
  const context = await browser.newContext({ viewport: shot.viewport, colorScheme: shot.colorScheme });
  const page = await context.newPage();
  const errors = [];
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
  page.on('pageerror', (err) => errors.push(err.message));

  // 有給網址就直接開；否則頁面本身與 img/ 由本機檔案供應，Google Fonts 照常連線
  const target = process.argv[2];
  if (!target) {
    await page.route('http://preview.local/**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      if (url.pathname.startsWith('/img/')) {
        return route.fulfill({ contentType: 'image/webp', body: readFileSync(path.join(imagesDir, path.basename(url.pathname))) });
      }
      return route.fulfill({ status: 404, body: '' });
    });
  }

  await page.goto(target || 'http://preview.local/', { waitUntil: 'networkidle' });
  // lazy 圖片要捲到才載入：先捲到底再回頂端
  await page.evaluate(async () => {
    for (let y = 0; y < document.body.scrollHeight; y += 600) { window.scrollTo(0, y); await new Promise((r) => setTimeout(r, 60)); }
    window.scrollTo(0, 0);
  });
  await page.waitForLoadState('networkidle');

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  const file = path.join(outDir, `${shot.name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  console.log(`${shot.name}: ${file}　橫向溢出 ${overflow}px　console 錯誤 ${errors.length} 筆`);
  errors.forEach((e) => console.log(`  - ${e}`));
  if (overflow > 0 || errors.length) failed = true;
  await context.close();
}
await browser.close();
process.exit(failed ? 1 : 0);
