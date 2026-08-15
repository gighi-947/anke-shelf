/** 正式 Windows 阅读器的骨碌碌在线评论 Playwright 冒烟检查。 */
'use strict';

const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = process.env.GULULU_FORMAL_URL;
const outputDir = path.join(__dirname, 'workspace', 'screenshots');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function browserExecutable() {
  const candidates = [
    process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE,
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'F:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'F:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  ].filter(Boolean);
  return candidates.find((candidate) => fs.existsSync(candidate));
}

async function openBook(page) {
  await page.addInitScript(() => {
    window.Audio = class FakeAudio {
      constructor(url) { this.src = url; this.volume = 1; this.paused = true; }
      play() { this.paused = false; return Promise.resolve(); }
      pause() { this.paused = true; }
      removeAttribute(name) { if (name === 'src') this.src = ''; }
    };
  });
  await page.route('https://image.gululu.world/**', (route) => route.fulfill({
    status: 200,
    contentType: 'image/png',
    body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+Avn8WQAAAABJRU5ErkJggg==', 'base64'),
  }));
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.App && App._initialized);
  await page.evaluate(async () => {
    const books = await Api.getShelf();
    await App.showReader(books[0].id);
    await Reader.loadChapter(0, 0);
  });
  await page.frameLocator('#chapter-frame').locator('.gululu-floor').first().waitFor();
}

async function inspectDesktop(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const text = message.text();
    const knownResourceNoise = (
      text.includes('Failed to load resource') ||
      text.includes("base-uri 'none'") ||
      text.includes('ERR_NETWORK_ACCESS_DENIED')
    );
    if (!knownResourceNoise) errors.push(text);
  });
  page.on('pageerror', (error) => errors.push(error.message));
  await openBook(page);

  const embedded = await page.frameLocator('#chapter-frame').locator('.gululu-comment').count();
  const before = await page.evaluate(() => App.state.textCtx.text.length);
  assert(embedded === 0, `紧凑 EPUB 仍包含评论：${embedded}`);
  assert(await page.locator('#gululu-comments-btn').isVisible(), '评论按钮不可见');
  assert(await page.locator('#gululu-immersive-btn').isVisible(), '沉浸效果按钮不可见');

  const secretCue = page.frameLocator('#chapter-frame').locator('.gululu-secret-cue').first();
  const clueCue = page.frameLocator('#chapter-frame').locator('.gululu-clue-cue').first();
  await secretCue.waitFor();
  await clueCue.waitFor();
  await secretCue.click();
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('尚未找到线索'));
  await clueCue.click();
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('线索已记录'));
  await secretCue.click();
  await page.locator('.gululu-secret-modal').waitFor();
  const secretText = await page.locator('.gululu-secret-plaintext').textContent();
  assert(secretText === '风雪之后，炉火仍在。', `秘密解锁内容错误：${secretText}`);
  const afterSecret = await page.evaluate(() => App.state.textCtx.text.length);
  assert(before === afterSecret, `秘密解锁改变正文坐标：${before} -> ${afterSecret}`);
  await page.locator('.gululu-secret-close').click();

  const musicCue = page.frameLocator('#chapter-frame').locator('.gululu-music-cue').first();
  await musicCue.waitFor();
  await musicCue.click();
  await page.waitForFunction(() => GululuImmersive.snapshot().playing);
  await page.waitForFunction(() => GululuImmersive.snapshot().backgroundUrl.includes('a.webp'));
  await page.waitForFunction(() => GululuImmersive.snapshot().effect === 'rain');
  const canvasPixels = await page.locator('#gululu-vfx-canvas').evaluate((canvas) => {
    const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    let visible = 0;
    for (let index = 3; index < data.length; index += 4) if (data[index]) visible += 1;
    return visible;
  });
  assert(canvasPixels > 0, '雨效画布没有绘制可见像素');
  await page.evaluate(() => App.setBarsVisible(true));
  await page.locator('#gululu-immersive-btn').click();
  assert(await page.locator('#gululu-immersive-panel').isVisible(), '沉浸效果面板未打开');
  assert(!(await page.locator('#gululu-auto-music-toggle').isChecked()), '自动音乐不应默认开启');
  assert(await page.locator('#gululu-background-toggle').isChecked(), '氛围背景应默认开启');
  await page.screenshot({ path: path.join(outputDir, 'formal-immersive.png'), fullPage: true });
  await page.locator('#gululu-stop-music').click();
  await page.waitForFunction(() => !GululuImmersive.snapshot().playing);
  await page.locator('#gululu-immersive-close').click();
  const afterImmersive = await page.evaluate(() => App.state.textCtx.text.length);
  assert(before === afterImmersive, `沉浸效果改变正文坐标：${before} -> ${afterImmersive}`);

  await page.locator('#gululu-comments-btn').click();
  await page.locator('.gululu-online-comment').first().waitFor();
  const comments = await page.locator('.gululu-online-comment').count();
  const after = await page.evaluate(() => App.state.textCtx.text.length);
  assert(comments >= 3, `在线评论数量不足：${comments}`);
  assert(before === after, `评论面板改变正文坐标：${before} -> ${after}`);

  await page.locator('#gululu-comments-panel .gululu-switch').click();
  await page.locator('.gululu-danmaku-item').first().waitFor();
  const danmaku = await page.locator('.gululu-danmaku-item').first().textContent();
  assert(danmaku.trim().length > 0, '弹幕正文为空');
  await page.locator('#gululu-comments-refresh').click();
  await page.waitForFunction(() => (
    document.querySelector('#gululu-comments-status').textContent.includes('在线更新')
  ));
  await page.screenshot({ path: path.join(outputDir, 'formal-comments.png'), fullPage: true });
  assert(errors.length === 0, `浏览器控制台错误：${errors.join(' | ')}`);
  await page.evaluate(() => App.showShelf());
  const cleanup = await page.evaluate(() => GululuImmersive.snapshot());
  assert(cleanup.sourceId === 0 && !cleanup.playing && !cleanup.effect,
    `返回书架后沉浸效果未清理：${JSON.stringify(cleanup)}`);
  await page.close();
  return { embedded, comments, before, after, afterSecret, afterImmersive,
    secretText, canvasPixels, danmaku, cleanup, errors };
}

async function inspectNarrow(browser) {
  const page = await browser.newPage({ viewport: { width: 430, height: 800 } });
  await openBook(page);
  const narrowFrame = page.frameLocator('#chapter-frame');
  await narrowFrame.locator('.gululu-clue-cue').first().click();
  await narrowFrame.locator('.gululu-secret-cue').first().click();
  const secretModal = await page.locator('.gululu-secret-modal').boundingBox();
  assert(secretModal && secretModal.x >= -1 && secretModal.width <= 431,
    `窄屏秘密弹窗错误：${JSON.stringify(secretModal)}`);
  await page.locator('.gululu-secret-close').click();
  await page.evaluate(() => App.setBarsVisible(true));
  await page.locator('#gululu-immersive-btn').click();
  const immersivePanel = await page.locator('#gululu-immersive-panel').boundingBox();
  assert(immersivePanel && immersivePanel.x >= -1 && immersivePanel.width <= 431,
    `窄屏沉浸面板错误：${JSON.stringify(immersivePanel)}`);
  await page.screenshot({ path: path.join(outputDir, 'formal-immersive-narrow.png'), fullPage: true });
  await page.locator('#gululu-immersive-close').click();
  await page.locator('#gululu-comments-btn').click();
  await page.locator('.gululu-online-comment').first().waitFor();
  const panel = await page.locator('#gululu-comments-panel').boundingBox();
  const chapterLayout = await page.frameLocator('#chapter-frame').locator('body').evaluate(() => {
    const doc = document;
    const sample = doc.querySelector('[data-paragraph-id="overflow-regression"] span');
    return {
      clientWidth: doc.documentElement.clientWidth,
      scrollWidth: doc.documentElement.scrollWidth,
      bodyColor: getComputedStyle(doc.body).color,
      sampleColor: sample ? getComputedStyle(sample).color : '',
    };
  });
  const overflow = await page.evaluate(() => ({
    width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  assert(panel && panel.x >= -1 && panel.width <= 431, `窄屏评论面板错误：${JSON.stringify(panel)}`);
  assert(overflow.scrollWidth <= overflow.width, '窄屏页面出现横向溢出');
  assert(chapterLayout.scrollWidth <= chapterLayout.clientWidth,
    `窄屏正文出现横向溢出：${JSON.stringify(chapterLayout)}`);
  assert(chapterLayout.sampleColor === chapterLayout.bodyColor,
    `骨碌碌默认黑字未适配主题：${JSON.stringify(chapterLayout)}`);
  await page.screenshot({ path: path.join(outputDir, 'formal-comments-narrow.png'), fullPage: true });
  await page.close();
  return { panel, immersivePanel, secretModal, overflow, chapterLayout };
}

(async () => {
  assert(baseUrl, '缺少 GULULU_FORMAL_URL');
  fs.mkdirSync(outputDir, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: browserExecutable() });
  try {
    const desktop = await inspectDesktop(browser);
    const narrow = await inspectNarrow(browser);
    console.log(JSON.stringify({ ok: true, desktop, narrow }, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error.stack || error);
  process.exitCode = 1;
});
