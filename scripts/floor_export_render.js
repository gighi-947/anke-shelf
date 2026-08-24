/*
 * 楼层导出渲染器：用 Playwright 把指定楼层元素截图为 PNG/WebP。
 * 由 Python FloorExportService 调用；输入为 JSON 配置文件路径，输出为 JSON 结果。
 *
 * 用法：node scripts/floor_export_render.js <config.json>
 * config: {
 *   deviceScaleFactor: 2,
 *   jobs: [{ url, selector, out, noImages }]
 * }
 * 结果: { results: [{ ok, out, width, height, failedImages }] }
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));

function findChromium() {
  if (process.env.ANKESHELF_CHROMIUM && fs.existsSync(process.env.ANKESHELF_CHROMIUM)) {
    return process.env.ANKESHELF_CHROMIUM;
  }
  try {
    const p = chromium.executablePath();
    if (fs.existsSync(p)) return p;
  } catch (e) { /* ignore */ }
  // Playwright 版本升级后，旧浏览器目录仍可用：按 revision 目录搜索兜底。
  const base = process.env.LOCALAPPDATA || process.env.USERPROFILE;
  const roots = [];
  if (base) {
    roots.push(path.join(base, 'ms-playwright'));
    if (process.env.USERPROFILE) roots.push(path.join(process.env.USERPROFILE, 'AppData', 'Local', 'ms-playwright'));
  }
  for (const root of roots) {
    try {
      if (!fs.existsSync(root)) continue;
      for (const dir of fs.readdirSync(root)) {
        if (dir.startsWith('chromium_headless_shell-')) {
          const p = path.join(root, dir, 'chrome-headless-shell-win64', 'chrome-headless-shell.exe');
          if (fs.existsSync(p)) return p;
        }
        if (dir.startsWith('chromium-')) {
          const p = path.join(root, dir, 'chrome-win64', 'chrome.exe');
          if (fs.existsSync(p)) return p;
        }
      }
    } catch (e) { /* ignore */ }
  }
  return null;
}

async function imageState(el) {
  return el.evaluate((node) => {
    const imgs = Array.from(node.querySelectorAll('img'));
    const failed = imgs.filter((img) => img.complete && !img.naturalWidth).length;
    const total = imgs.length;
    const pending = imgs.filter((img) => !img.complete).length;
    return { total, failed, pending };
  });
}

(async () => {
  const executable = findChromium();
  if (!executable) {
    console.error('找不到可用的 Chromium');
    process.exit(2);
  }
  const browser = await chromium.launch({
    headless: true,
    executablePath: executable,
  });
  const context = await browser.newContext({
    viewport: { width: 900, height: 1000 },
    deviceScaleFactor: Math.max(1, Math.min(4, Number(config.deviceScaleFactor) || 2)),
    ignoreHTTPSErrors: true,
  });
  const page = await context.newPage();
  const results = [];
  try {
    for (const job of config.jobs || []) {
      const item = { out: job.out, ok: false, failedImages: 0, totalImages: 0, width: 0, height: 0, error: '' };
      try {
        await page.goto(job.url, { waitUntil: 'domcontentloaded', timeout: 60000 });
        await page.evaluate(() => document.fonts && document.fonts.ready).catch(() => {});
        if (config.themeCss) {
          await page.evaluate((css) => {
            const style = document.createElement('style');
            style.id = '__floor_export_theme__';
            style.textContent = css;
            document.head.appendChild(style);
          }, config.themeCss);
        }
        const el = page.locator(job.selector).first();
        await el.waitFor({ state: 'attached', timeout: 30000 });
        await el.scrollIntoViewIfNeeded();
        if (job.noImages) {
          await el.evaluate((node) => {
            node.querySelectorAll('img').forEach((img) => img.remove());
          });
        } else {
          // 等图片加载完成；失败的图片 complete 也为 true，不会无限等。
          await page.waitForFunction(() => Array.from(document.images).every((img) => img.complete), null, { timeout: 20000 }).catch(() => {});
        }
        const state = await imageState(el);
        item.totalImages = state.total;
        item.failedImages = job.noImages ? 0 : state.failed;
        await el.screenshot({ path: job.out, type: config.format || 'png', quality: config.format === 'webp' ? 82 : undefined });
        item.ok = true;
        try {
          const box = await el.boundingBox();
          item.width = box ? Math.round(box.width) : 0;
          item.height = box ? Math.round(box.height) : 0;
        } catch (e) { /* ignore */ }
      } catch (e) {
        item.error = String(e && e.message || e);
      }
      results.push(item);
    }
  } finally {
    await browser.close();
  }
  process.stdout.write(JSON.stringify({ results }));
})();
