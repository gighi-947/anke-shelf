/** 骨碌碌专版阅读器的可选 Playwright 冒烟检查。 */
'use strict';

const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = process.env.GULULU_DEBUG_URL || 'http://127.0.0.1:8877/';
const outputDir = path.join(__dirname, 'workspace', 'screenshots');

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

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function inspectDesktop(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.chapter-item:nth-child(20)');
  await page.frameLocator('#chapter-frame').locator('.gululu-floor').first().waitFor();

  const title = await page.locator('#book-title').textContent();
  const chapterCount = await page.locator('.chapter-item').count();
  const bodyLength = await page.frameLocator('#chapter-frame').locator('body').evaluate(
    (body) => body.innerText.length,
  );
  const commentCount = await page.frameLocator('#chapter-frame').locator('.gululu-comment').count();
  const overflow = await page.evaluate(() => ({
    width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  assert(title === '魔法少女与地下城', `书名错误：${title}`);
  assert(chapterCount === 20, `目录数量错误：${chapterCount}`);
  assert(bodyLength > 100, `首章正文过短：${bodyLength}`);
  assert(commentCount > 0, '首章没有导入评论');
  assert(overflow.scrollWidth <= overflow.width, '桌面页面出现横向溢出');

  const quickActions = await page.locator('#quick-actions').boundingBox();
  const footer = await page.locator('.reader-controls').boundingBox();
  assert(quickActions && footer && quickActions.y + quickActions.height <= footer.y,
    '桌面快捷入口遮挡底部翻页区');
  await page.locator('#quick-menu-toggle').click();
  assert(await page.locator('#quick-menu').isVisible(), '快捷菜单没有展开');
  assert(await page.locator('#quick-menu-toggle').getAttribute('aria-expanded') === 'true',
    '快捷菜单展开状态未同步');
  await page.locator('#quick-settings').click();
  assert(await page.locator('#settings-panel').isVisible(), '设置面板没有打开');
  assert(!(await page.locator('#quick-menu').isVisible()), '打开设置后快捷菜单仍未收起');
  await page.keyboard.press('Escape');
  assert(!(await page.locator('#settings-panel').isVisible()), 'Escape 没有关闭设置面板');
  assert(await page.evaluate(() => document.activeElement?.id) === 'quick-settings',
    '关闭设置后焦点没有返回触发按钮');

  await page.locator('#quick-comments').click();
  await page.locator('#comments-drawer .comment-floor').first().waitFor();
  assert(await page.locator('#comments-drawer').isVisible(), '评论抽屉没有打开');
  await page.locator('#quick-settings').click();
  assert(await page.locator('#settings-panel').isVisible(), '设置面板没有重新打开');
  assert(!(await page.locator('#comments-drawer').isVisible()), '评论抽屉与设置面板同时打开');
  await page.locator('#danmaku-toggle').click();
  await page.locator('.danmaku-item').first().waitFor();
  const danmakuText = await page.locator('.danmaku-item').first().textContent();
  assert(danmakuText.trim().length > 0, '弹幕正文为空');
  await page.screenshot({ path: path.join(outputDir, 'desktop-settings.png'), fullPage: true });

  await page.locator('[data-mode="paged"]').click();
  await page.waitForFunction(() => /页$/.test(document.querySelector('#page-position').textContent));
  const pagedLabel = await page.locator('#page-position').textContent();
  assert(/第 \d+ \/ \d+ 页/.test(pagedLabel), `分页状态错误：${pagedLabel}`);
  await page.locator('[data-mode="scroll"]').click();
  await page.waitForFunction(() => document.querySelector('#page-position').textContent === '滚动阅读');
  await page.locator('#settings-close').click();

  await page.locator('#toc-toggle').click();
  await page.locator('.chapter-item').nth(1).click();
  await page.waitForFunction(() => document.querySelector('#chapter-position').textContent.trim() === '2 / 20');
  await page.frameLocator('#chapter-frame').locator('.chapter-title', { hasText: '龙金劫' }).waitFor();
  const secondTitle = await page.frameLocator('#chapter-frame').locator('.chapter-title').textContent();
  assert(secondTitle.includes('龙金劫'), `切章失败：${secondTitle}`);
  await page.screenshot({ path: path.join(outputDir, 'desktop-reader.png'), fullPage: true });
  const floorButtons = page.frameLocator('#chapter-frame').locator('.gululu-debug-comment-button');
  assert(await floorButtons.count() > 1, '多楼层章节没有楼层评论入口');
  await floorButtons.nth(1).click();
  await page.locator('#comments-drawer .comment-floor').first().waitFor();
  const focusedFloor = await page.locator('#comments-drawer .comment-floor h3').first().textContent();
  assert(focusedFloor.includes('第 3 楼'), `评论抽屉没有聚焦所选楼层：${focusedFloor}`);

  await page.screenshot({ path: path.join(outputDir, 'desktop.png'), fullPage: true });
  assert(errors.length === 0, `浏览器控制台错误：${errors.join(' | ')}`);
  await page.close();
  return { title, chapterCount, bodyLength, commentCount, danmakuText, pagedLabel,
    secondTitle, focusedFloor, errors };
}

async function inspectNarrow(browser) {
  const page = await browser.newPage({ viewport: { width: 430, height: 800 } });
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.chapter-item:nth-child(20)');
  const actions = await page.locator('#quick-actions').boundingBox();
  const footer = await page.locator('.reader-controls').boundingBox();
  assert(actions && footer && actions.x >= 0 && actions.x + actions.width <= 431,
    `窄屏快捷入口越界：${JSON.stringify(actions)}`);
  assert(actions.y + actions.height <= footer.y, '窄屏快捷入口遮挡底部翻页区');
  await page.locator('#quick-settings').click();
  const settings = await page.locator('#settings-panel').boundingBox();
  assert(settings && settings.x >= -1 && settings.x + settings.width <= 431,
    `窄屏设置面板越界：${JSON.stringify(settings)}`);
  await page.screenshot({ path: path.join(outputDir, 'narrow-settings.png'), fullPage: true });
  await page.keyboard.press('Escape');
  await page.locator('#quick-comments').click();
  const comments = await page.locator('#comments-drawer').boundingBox();
  assert(comments && comments.x >= -1 && comments.x + comments.width <= 431,
    `窄屏评论抽屉越界：${JSON.stringify(comments)}`);
  await page.locator('#comments-close').click();
  await page.locator('#toc-toggle').click();
  await page.waitForFunction(() => {
    const panel = document.querySelector('#toc-panel');
    return panel.classList.contains('open') && panel.getBoundingClientRect().x >= -1;
  });
  const panel = await page.locator('#toc-panel').boundingBox();
  const overflow = await page.evaluate(() => ({
    width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  assert(panel && panel.x >= -1 && panel.width <= 430, `窄屏目录未正确展开：${JSON.stringify(panel)}`);
  assert(overflow.scrollWidth <= overflow.width, '窄屏页面出现横向溢出');
  await page.screenshot({ path: path.join(outputDir, 'narrow.png'), fullPage: true });
  await page.close();
  return { panel, settings, comments, actions, overflow };
}

(async () => {
  fs.mkdirSync(outputDir, { recursive: true });
  const executablePath = browserExecutable();
  const browser = await chromium.launch({ headless: true, executablePath });
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
