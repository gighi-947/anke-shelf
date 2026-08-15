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
  await page.evaluate(() => Shelf.render());
  await page.locator('#book-grid .book-card').first().waitFor();
  const gululuBadge = page.locator('.book-card .gululu-badge');
  assert(await gululuBadge.count() === 1, '骨碌碌书籍封面缺少来源标签');
  assert((await gululuBadge.first().textContent()).trim() === '骨碌碌', '骨碌碌来源标签文字错误');
  await page.evaluate(async () => {
    const books = await Api.getShelf();
    const gululu = books.find((book) => book.title === '测试安科');
    await App.showReader(gululu.id);
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
  assert(await page.locator('#gululu-quick-actions').isVisible(), '骨碌碌右下角快捷操作轨不可见');
  assert(!(await page.locator('#gululu-comments-btn').isVisible()), '骨碌碌评论入口仍重复显示在顶栏');
  assert(!(await page.locator('#gululu-immersive-btn').isVisible()), '骨碌碌沉浸入口仍重复显示在顶栏');
  await page.evaluate(() => App.setBarsVisible(false));
  assert(await page.locator('#gululu-quick-actions').isVisible(), '顶栏收起后快捷操作轨随之消失');

  for (const id of [
    '#gululu-quick-toc',
    '#gululu-quick-comments',
    '#gululu-quick-bookmark',
    '#gululu-quick-immersive',
    '#gululu-quick-reveal-dice',
    '#gululu-quick-settings',
  ]) {
    assert(await page.locator(id).isVisible(), `核心阅读入口未直接显示：${id}`);
  }
  assert(await page.locator('#gululu-quick-toc').getAttribute('role') !== 'menuitem',
    '目录仍被放在更多操作菜单');
  assert(await page.locator('#gululu-quick-immersive').getAttribute('role') !== 'menuitem',
    '音乐与氛围仍被放在更多操作菜单');

  await page.locator('#gululu-quick-toc').click();
  assert(await page.locator('#sidebar').isVisible(), '一级目录入口没有打开目录');
  await page.locator('#sidebar-toggle2').click();

  const bookmarkBefore = await page.locator('#gululu-quick-bookmark').getAttribute('aria-pressed');
  await page.locator('#gululu-quick-bookmark').click();
  await page.waitForFunction((before) => (
    document.getElementById('gululu-quick-bookmark').getAttribute('aria-pressed') !== before
  ), bookmarkBefore);
  assert(await page.locator('#gululu-quick-bookmark').getAttribute('aria-pressed') !== bookmarkBefore,
    '一级书签入口没有切换当前位置标记');

  const chapterTextBeforeDice = await page.evaluate(() => App.state.textCtx.text);
  const diceValue = page.frameLocator('#chapter-frame').locator('.gululu-dice-value').first();
  const diceValues = page.frameLocator('#chapter-frame').locator('.gululu-dice-value');
  const fogBlock = page.frameLocator('#chapter-frame').locator('[data-gululu-fog-lock]').first();
  await diceValue.waitFor();
  assert(await diceValues.count() === 2, '正式样本没有覆盖批量揭示的多组骰点');
  assert(await diceValue.evaluate((node) => node.classList.contains('masked')), '骰点结果默认未遮罩');
  assert(await fogBlock.evaluate((node) => node.classList.contains('gululu-fog-hidden')), '骰点后的正文默认未进入迷雾');
  await diceValue.click();
  assert(await diceValue.evaluate((node) => node.classList.contains('revealed')), '点击后骰点没有揭示');
  assert(!(await fogBlock.evaluate((node) => node.classList.contains('gululu-fog-hidden'))), '揭示骰点后迷雾没有解除');
  await page.locator('#gululu-quick-reveal-dice').click();
  assert(await diceValues.nth(1).evaluate((node) => node.classList.contains('revealed')),
    '一级骰点入口没有揭示接下来的骰点');
  const chapterTextAfterDice = await page.evaluate(() => TextPos.build(
    document.querySelector('#chapter-frame').contentDocument
  ).text);
  assert(chapterTextAfterDice === chapterTextBeforeDice, '骰点揭示改变 text_offset 文本坐标');
  await page.evaluate(() => Reader.loadChapter(0, 0));
  await page.frameLocator('#chapter-frame').locator('.gululu-dice-value').first().waitFor();
  assert(await page.frameLocator('#chapter-frame').locator('.gululu-dice-value.revealed').count() === 2,
    '章节重载后骰点解锁状态丢失');

  await page.evaluate(() => {
    App.state.settings.pagination = false;
    Reader.applyLayout();
  });
  await page.frameLocator('#chapter-frame').locator('.gululu-assistant-quote').click();
  await page.waitForFunction(() => App.state.chapterIndex === 1);
  await page.waitForTimeout(250);
  const scrollQuote = await page.evaluate(() => ({
    offset: Reader.currentOffset(),
    scrollTop: document.getElementById('chapter-scroll').scrollTop,
    paged: Paged.isActive(),
  }));
  assert(scrollQuote.offset > 0 && scrollQuote.scrollTop > 0 && !scrollQuote.paged,
    `滚动引用没有定位目标楼层：${JSON.stringify(scrollQuote)}`);
  await page.evaluate(() => Reader.loadChapter(0, 0));
  await page.frameLocator('#chapter-frame').locator('.gululu-floor').first().waitFor();

  await page.locator('#gululu-quick-settings').click();
  assert(await page.locator('#view-menu').isVisible(), '快捷设置没有打开浮动排版面板');
  assert(await page.locator('#view-menu').evaluate((menu) => menu.classList.contains('gululu-quick-anchor')),
    '浮动排版面板没有锚定右下角快捷入口');
  assert(await page.locator('#vm-gululu-settings').isVisible(), '浮动排版面板缺少骨碌碌集中配置');
  assert(await page.locator('#vm-gululu-comments-mode').count() === 1, '集中配置缺少评论显示方式');
  assert(await page.locator('#vm-gululu-danmaku').count() === 1, '集中配置缺少弹幕开关');

  await page.locator('#gululu-quick-comments').click();
  await page.locator('.gululu-online-comment').first().waitFor();
  assert(!(await page.locator('#view-menu').isVisible()), '打开评论后浮动设置面板没有互斥关闭');
  assert(await page.locator('#gululu-comments-panel').isVisible(), '快捷评论没有打开评论抽屉');
  await page.locator('#gululu-comments-close').click();

  await page.locator('#gululu-quick-more-toggle').click();
  assert(await page.locator('#gululu-quick-menu').isVisible(), '更多操作菜单没有展开');
  assert(await page.locator('#gululu-quick-menu #gululu-quick-toc').count() === 0,
    '更多操作菜单仍包含目录');
  assert(await page.locator('#gululu-quick-menu #gululu-quick-immersive').count() === 0,
    '更多操作菜单仍包含音乐与氛围');
  await page.keyboard.press('Escape');
  assert(!(await page.locator('#gululu-quick-menu').isVisible()), 'Esc 没有关闭更多操作菜单');
  assert(await page.evaluate(() => document.activeElement?.id) === 'gululu-quick-more-toggle',
    '关闭更多操作菜单后焦点没有回到触发按钮');

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
  await page.locator('#gululu-quick-immersive').click();
  assert(await page.locator('#gululu-immersive-panel').isVisible(), '沉浸效果面板未打开');
  assert(await page.locator('#gululu-auto-music-toggle').isChecked(), '自动音乐应按参考助手默认开启');
  assert(await page.locator('#gululu-background-toggle').isChecked(), '氛围背景应默认开启');
  await page.screenshot({ path: path.join(outputDir, 'formal-immersive.png'), fullPage: true });
  await page.locator('#gululu-stop-music').click();
  await page.waitForFunction(() => !GululuImmersive.snapshot().playing);
  await page.locator('#gululu-immersive-close').click();
  const afterImmersive = await page.evaluate(() => App.state.textCtx.text.length);
  assert(before === afterImmersive, `沉浸效果改变正文坐标：${before} -> ${afterImmersive}`);

  await page.locator('#gululu-quick-comments').click();
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

  await page.locator('#gululu-comments-close').click();
  const loadingStarted = await page.evaluate(() => {
    window.__formalChapterLoad = Reader.loadChapter(1, 0);
    const mask = document.getElementById('reader-loading-mask');
    const root = document.getElementById('reader-root');
    return {
      exists: !!mask,
      visible: !!mask && !mask.classList.contains('hidden'),
      busy: root && root.getAttribute('aria-busy'),
    };
  });
  assert(loadingStarted.exists && loadingStarted.visible && loadingStarted.busy === 'true',
    `换章时没有显示加载遮罩：${JSON.stringify(loadingStarted)}`);
  await page.evaluate(() => window.__formalChapterLoad);
  const loadingFinished = await page.evaluate(() => ({
    visible: !document.getElementById('reader-loading-mask').classList.contains('hidden'),
    busy: document.getElementById('reader-root').getAttribute('aria-busy'),
  }));
  assert(!loadingFinished.visible && loadingFinished.busy === 'false',
    `排版稳定后加载遮罩没有撤下：${JSON.stringify(loadingFinished)}`);
  const chapterFrame = page.frameLocator('#chapter-frame');
  await chapterFrame.locator('.gululu-floor').nth(1).waitFor();
  const floorCardStyle = await chapterFrame.locator('.gululu-floor').first().evaluate((floor) => {
    const card = getComputedStyle(floor);
    const head = getComputedStyle(floor.querySelector('.floor-head'));
    const number = getComputedStyle(floor.querySelector('.floor-number'));
    const channels = (card.borderLeftColor.match(/\d+(?:\.\d+)?/g) || [])
      .slice(0, 3).map(Number).map((value) => value / 255);
    const max = Math.max(...channels);
    const min = Math.min(...channels);
    const lightness = (max + min) / 2;
    const accentSaturation = max === min ? 0
      : (max - min) / (1 - Math.abs(2 * lightness - 1));
    return {
      borderTopWidth: card.borderTopWidth,
      borderLeftWidth: card.borderLeftWidth,
      borderLeftColor: card.borderLeftColor,
      borderRadius: card.borderRadius,
      paddingTop: card.paddingTop,
      paddingRight: card.paddingRight,
      borderBottomStyle: head.borderBottomStyle,
      numberColor: number.color,
      accentSaturation,
    };
  });
  assert(parseFloat(floorCardStyle.borderTopWidth) >= 0.75 &&
    parseFloat(floorCardStyle.borderLeftWidth) >= 3.75,
    `骨碌碌楼层未采用 NGA 卡片边框：${JSON.stringify(floorCardStyle)}`);
  assert(floorCardStyle.borderRadius === '2px' && floorCardStyle.paddingTop === '12px' &&
    floorCardStyle.paddingRight === '14px',
  `骨碌碌楼层未采用 NGA 卡片尺寸：${JSON.stringify(floorCardStyle)}`);
  assert(floorCardStyle.borderBottomStyle === 'dotted',
    `骨碌碌楼头未采用 NGA 点状分隔线：${JSON.stringify(floorCardStyle)}`);
  assert(floorCardStyle.numberColor === floorCardStyle.borderLeftColor,
    `骨碌碌楼号与卡片强调线颜色不一致：${JSON.stringify(floorCardStyle)}`);
  assert(floorCardStyle.accentSaturation < 0.5,
    `骨碌碌楼层强调色饱和度过高：${JSON.stringify(floorCardStyle)}`);
  const chapterTextBeforeComments = await page.evaluate(() => (
    TextPos.build(document.querySelector('#chapter-frame').contentDocument).text
  ));
  const floorCommentButtons = chapterFrame.locator('.gululu-floor-comment-button');
  assert(await floorCommentButtons.count() === 2, '多楼层章节没有逐楼评论入口');
  await floorCommentButtons.nth(1).click();
  await page.locator('.gululu-online-comment').first().waitFor();
  assert(await page.locator('.gululu-comment-floor').count() === 1, '逐楼入口没有聚焦对应楼层');
  const focusedComment = await page.locator('.gululu-online-comment > p').first().textContent();
  assert(focusedComment === '第一章第三楼评论', `逐楼评论内容错误：${focusedComment}`);

  await page.locator('#gululu-comments-mode').selectOption('inline');
  await chapterFrame.locator('.gululu-inline-comments').nth(1).waitFor();
  const inlineBlocks = chapterFrame.locator('.gululu-inline-comments');
  assert(await inlineBlocks.count() === 2, '楼末折叠没有覆盖本章全部楼层');
  const inlineComment = await inlineBlocks.nth(1).locator('.gululu-inline-comment-body').first().textContent();
  assert(inlineComment === '第一章第三楼评论', `楼末折叠评论内容错误：${inlineComment}`);
  const chapterTextAfterComments = await page.evaluate(() => (
    TextPos.build(document.querySelector('#chapter-frame').contentDocument).text
  ));
  assert(chapterTextAfterComments === chapterTextBeforeComments,
    '楼末评论被计入 text_offset 文本坐标');

  await page.evaluate(async () => {
    App.state.settings.pagination = true;
    App.state.settings.auto_dual = false;
    App.state.settings.dual_page = false;
    Reader.applyLayout();
    await Reader.loadChapter(1, 0);
  });
  const pagedTotal = await page.evaluate(() => Paged.measure().total);
  assert(pagedTotal >= 4, `分页全屏回归章节页数不足：${pagedTotal}`);
  await page.evaluate(() => Paged.gotoPage(3));
  const pagedBeforeFullscreen = await page.evaluate(() => ({
    offset: Paged.currentOffset(),
    page: Paged.measure().current,
  }));
  assert(pagedBeforeFullscreen.offset > 0 && pagedBeforeFullscreen.page > 0,
    `分页全屏回归没有从非首屏开始：${JSON.stringify(pagedBeforeFullscreen)}`);

  await page.evaluate(() => {
    Api.toggleFullscreen = () => new Promise((resolve) => {
      setTimeout(() => resolve({ ok: true }), 80);
    });
    window.__immersiveToggle = App.toggleImmersive();
  });
  await page.setViewportSize({ width: 1100, height: 720 });
  await page.evaluate(() => window.__immersiveToggle);
  await page.waitForTimeout(300);
  const pagedInFullscreen = await page.evaluate(() => ({
    offset: Paged.currentOffset(),
    page: Paged.measure().current,
  }));
  assert(pagedInFullscreen.offset > 0 && pagedInFullscreen.page > 0,
    `分页进入沉浸式后回到章节首：${JSON.stringify(pagedInFullscreen)}`);

  await page.evaluate(() => {
    window.__immersiveToggle = App.toggleImmersive();
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.evaluate(() => window.__immersiveToggle);
  await page.waitForTimeout(300);
  const pagedAfterFullscreen = await page.evaluate(() => ({
    offset: Paged.currentOffset(),
    page: Paged.measure().current,
  }));
  assert(pagedAfterFullscreen.offset > 0 && pagedAfterFullscreen.page > 0,
    `分页退出沉浸式后回到章节首：${JSON.stringify(pagedAfterFullscreen)}`);
  assert(pagedAfterFullscreen.offset === pagedBeforeFullscreen.offset &&
    pagedAfterFullscreen.page === pagedBeforeFullscreen.page,
  `分页进出沉浸式后阅读位置漂移：${JSON.stringify({
    before: pagedBeforeFullscreen,
    exited: pagedAfterFullscreen,
  })}`);
  const paginationResize = {
    total: pagedTotal,
    before: pagedBeforeFullscreen,
    entered: pagedInFullscreen,
    exited: pagedAfterFullscreen,
  };

  await page.evaluate(() => Reader.loadChapter(0, 0));
  await page.frameLocator('#chapter-frame').locator('.gululu-assistant-quote').click();
  await page.waitForFunction(() => (
    App.state.chapterIndex === 1 && Paged.measure().current > 0
  ));
  const pagedQuote = await page.evaluate(() => ({
    page: Paged.measure().current,
    offset: Reader.currentOffset(),
  }));
  assert(pagedQuote.page > 0 && pagedQuote.offset > 0,
    `分页引用没有定位目标楼层：${JSON.stringify(pagedQuote)}`);

  assert(errors.length === 0, `浏览器控制台错误：${errors.join(' | ')}`);
  await page.evaluate(async () => {
    const books = await Api.getShelf();
    const nga = books.find((book) => book.title === 'NGA 隔离测试');
    await App.showReader(nga.id);
  });
  const isolationFrame = page.frameLocator('#chapter-frame');
  const isolationMusic = isolationFrame.locator('.gululu-music-cue');
  await isolationMusic.waitFor();
  assert(!(await page.locator('#gululu-quick-actions').isVisible()), 'NGA 阅读界面显示骨碌碌快捷操作轨');
  assert(!(await page.locator('#gululu-comments-btn').isVisible()), 'NGA 阅读界面显示骨碌碌评论入口');
  assert(!(await page.locator('#gululu-immersive-btn').isVisible()), 'NGA 阅读界面显示骨碌碌沉浸入口');
  assert(await isolationFrame.locator('.gululu-floor-comment-button').count() === 0,
    'NGA 正文被注入骨碌碌楼层评论按钮');
  await isolationMusic.click();
  await isolationFrame.locator('.gululu-secret-cue').click();
  await page.waitForTimeout(50);
  const isolation = await page.evaluate(() => ({
    immersive: GululuImmersive.snapshot(),
    secrets: GululuSecrets.snapshot(),
    secretModal: !!document.querySelector('.gululu-secret-modal'),
  }));
  assert(!isolation.immersive.playing && isolation.immersive.sourceId === 0,
    `NGA 正文触发骨碌碌音乐：${JSON.stringify(isolation)}`);
  assert(isolation.secrets.sourceId === 0 && !isolation.secretModal,
    `NGA 正文触发骨碌碌秘密：${JSON.stringify(isolation)}`);
  await page.evaluate(() => App.showShelf());
  const cleanup = await page.evaluate(() => GululuImmersive.snapshot());
  assert(cleanup.sourceId === 0 && !cleanup.playing && !cleanup.effect,
    `返回书架后沉浸效果未清理：${JSON.stringify(cleanup)}`);
  await page.close();
  return { embedded, comments, before, after, afterSecret, afterImmersive, floorCardStyle,
    secretText, canvasPixels, danmaku, focusedComment, inlineComment,
    loadingStarted, loadingFinished, paginationResize, isolation, cleanup, errors };
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
  await page.locator('#gululu-quick-immersive').click();
  const immersivePanel = await page.locator('#gululu-immersive-panel').boundingBox();
  assert(immersivePanel && immersivePanel.x >= -1 && immersivePanel.width <= 431,
    `窄屏沉浸面板错误：${JSON.stringify(immersivePanel)}`);
  await page.screenshot({ path: path.join(outputDir, 'formal-immersive-narrow.png'), fullPage: true });
  await page.locator('#gululu-immersive-close').click();
  await page.locator('#gululu-quick-comments').click();
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
  const quickActions = await page.locator('#gululu-quick-actions').boundingBox();
  assert(panel && panel.x >= -1 && panel.width <= 431, `窄屏评论面板错误：${JSON.stringify(panel)}`);
  assert(overflow.scrollWidth <= overflow.width, '窄屏页面出现横向溢出');
  assert(quickActions && quickActions.x >= -1 && quickActions.x + quickActions.width <= 431,
    `窄屏快捷操作轨越界：${JSON.stringify(quickActions)}`);
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
