/** 骨碌碌公开书籍 → 标准 EPUB → 自动加入书架。 */
(function () {
  'use strict';

  const {
    section, field, input, select, checkbox, check, val, makePoller, refreshBooks,
  } = window.NgaPage;
  const poller = makePoller();

  function parseBookId(raw) {
    const text = String(raw || '').trim();
    const match = text.match(/^\d+$/) ||
      text.match(/^https:\/\/(?:www\.)?gululu\.world\/book\/(\d+)\/?(?:[?#].*)?$/i);
    if (!match) return null;
    const value = match[1] || match[0];
    return /^\d{1,12}$/.test(value) && Number(value) > 0 ? value : null;
  }

  function buildSection() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    wrap.appendChild(field(
      '书籍',
      input('gululu-source', '书籍 ID 或 gululu.world/book/… 链接'),
    ));

    const options = document.createElement('div');
    options.className = 'nga-form-row';
    options.appendChild(field('图片', select('gululu-image-mode', [
      ['online', '在线图片'],
      ['embedded', '内嵌图片'],
      ['none', '不含图片'],
    ])));
    options.appendChild(field('完成后打开', checkbox('gululu-open-after', true)));
    wrap.appendChild(options);

    const actions = document.createElement('div');
    actions.className = 'nga-actions';
    const start = document.createElement('button');
    start.className = 'btn btn-primary';
    start.id = 'gululu-start';
    start.textContent = '生成并导入';
    start.addEventListener('click', startImport);
    const update = document.createElement('button');
    update.className = 'btn';
    update.id = 'gululu-update';
    update.append(Icons.icon('refresh', 16), document.createTextNode(' 检查更新'));
    update.addEventListener('click', startUpdate);
    const cancel = document.createElement('button');
    cancel.className = 'btn';
    cancel.id = 'gululu-cancel';
    cancel.textContent = '取消任务';
    cancel.disabled = true;
    cancel.addEventListener('click', cancelImport);
    const exportButton = document.createElement('button');
    exportButton.className = 'btn';
    exportButton.id = 'gululu-export';
    exportButton.append(Icons.icon('download', 16), document.createTextNode(' 导出含评论 EPUB'));
    exportButton.addEventListener('click', startExport);
    actions.append(start, update, exportButton, cancel);
    wrap.appendChild(actions);

    const status = document.createElement('div');
    status.className = 'nga-progress';
    const stage = document.createElement('div');
    stage.className = 'nga-progress-stage';
    stage.id = 'gululu-progress-stage';
    stage.textContent = '当前无导入任务';
    const track = document.createElement('div');
    track.className = 'nga-progress-track';
    const fill = document.createElement('div');
    fill.className = 'nga-progress-fill';
    fill.id = 'gululu-progress-fill';
    track.appendChild(fill);
    const text = document.createElement('div');
    text.className = 'nga-progress-text';
    text.id = 'gululu-progress-text';
    status.append(stage, track, text);
    wrap.appendChild(status);
    return section('骨碌碌 EPUB 导入', wrap);
  }

  async function startImport() {
    const source = val('gululu-source');
    if (!parseBookId(source)) {
      Toast.show('请输入有效的骨碌碌书籍 ID 或链接', true);
      return;
    }
    try {
      const result = await Api.gululuStartImport(
        source,
        val('gululu-image-mode') || 'online',
      );
      if (!result.ok) {
        Toast.show(result.error || '启动失败', true);
        if (result.error && result.error.includes('已有')) resume(true);
        return;
      }
      setRunning(true);
      resume(true);
    } catch (error) {
      Toast.show('启动失败：' + (error.message || error), true);
    }
  }

  async function cancelImport() {
    try {
      await Api.gululuCancel();
      const stage = document.getElementById('gululu-progress-stage');
      if (stage) stage.textContent = '正在取消…';
    } catch (error) {
      Toast.show('取消失败：' + (error.message || error), true);
    }
  }

  async function startUpdate() {
    const source = val('gululu-source');
    if (!parseBookId(source)) {
      Toast.show('请输入已导入的骨碌碌书籍 ID 或链接', true);
      return;
    }
    try {
      const result = await Api.gululuStartUpdate(
        source,
        val('gululu-image-mode') || 'online',
      );
      if (!result.ok) {
        Toast.show(result.error || '更新启动失败', true);
        if (result.error && result.error.includes('已有')) resume(true);
        return;
      }
      setRunning(true);
      resume(true);
    } catch (error) {
      Toast.show('更新启动失败：' + (error.message || error), true);
    }
  }

  async function startExport() {
    const source = val('gululu-source');
    if (!parseBookId(source)) {
      Toast.show('请输入有效的骨碌碌书籍 ID 或链接', true);
      return;
    }
    try {
      const result = await Api.gululuStartExport(
        source,
        val('gululu-image-mode') || 'online',
      );
      if (!result.ok) {
        if (!result.cancelled) Toast.show(result.error || '启动导出失败', true);
        return;
      }
      setRunning(true);
      resume(true);
    } catch (error) {
      Toast.show('启动导出失败：' + (error.message || error), true);
    }
  }

  function resume(fireOnDone = false) {
    if (!fireOnDone) {
      Api.gululuImportStatus().then((status) => {
        setRunning(status.running);
        renderStatus(status);
        if (status.running) resume(true);
      }).catch(() => {});
      return;
    }
    poller.start(
      () => Api.gululuImportStatus(),
      (status) => {
        setRunning(status.running);
        renderStatus(status);
      },
      onFinished,
    );
  }

  function setRunning(running) {
    const start = document.getElementById('gululu-start');
    const update = document.getElementById('gululu-update');
    const exportButton = document.getElementById('gululu-export');
    const cancel = document.getElementById('gululu-cancel');
    if (start) start.disabled = !!running;
    if (update) update.disabled = !!running;
    if (exportButton) exportButton.disabled = !!running;
    if (cancel) cancel.disabled = !running;
    const imageMode = document.getElementById('gululu-image-mode');
    if (imageMode) imageMode.disabled = !!running;
    const tab = document.querySelector('.download-tab[data-tab="dl-gululu"]');
    if (tab) tab.classList.toggle('running', !!running);
  }

  function renderStatus(status) {
    const stage = document.getElementById('gululu-progress-stage');
    const fill = document.getElementById('gululu-progress-fill');
    const text = document.getElementById('gululu-progress-text');
    if (!stage || !fill || !text) return;
    const labels = {
      idle: '当前无导入任务', update: '检查更新', metadata: '读取书籍信息', index: '读取目录',
      floors: '获取楼层', comments: '获取评论', images: '内嵌图片',
      epub: '生成 EPUB', register: '加入书架',
      done: '完成', cancelled: '已取消', error: '失败',
    };
    stage.textContent = labels[status.stage] || status.stage || '';
    const percent = status.total > 0
      ? Math.round((status.current / status.total) * 100)
      : (status.stage === 'done' ? 100 : 0);
    fill.style.width = percent + '%';
    text.textContent = status.error || status.detail || '';
  }

  function onFinished(status) {
    setRunning(false);
    if (status.stage === 'done') {
      if (status.action === 'update') {
        const warning = status.image_failed > 0
          ? `；${status.image_failed} 张图片失败并显示占位`
          : '';
        Toast.show((status.detail || '更新完成') + warning, status.image_failed > 0);
        refreshBooks();
      } else if (status.action === 'export') {
        const file = status.files && status.files[0] ? `：${status.files[0]}` : '';
        const warning = status.image_failed > 0
          ? `，${status.image_failed} 张图片失败并显示占位`
          : '';
        Toast.show('含评论 EPUB 已导出' + file + warning, status.image_failed > 0);
      } else {
        const warning = status.image_failed > 0
          ? `，${status.image_failed} 张图片失败并显示占位`
          : '';
        Toast.show('骨碌碌 EPUB 已加入书架' + warning, status.image_failed > 0);
        refreshBooks();
        if (check('gululu-open-after') && status.book_id) {
          NgaDownload.close();
          App.showReader(status.book_id);
        }
      }
    } else if (status.stage === 'error') {
      Toast.show('导入失败：' + (status.error || '未知错误'), true);
    } else if (status.stage === 'cancelled') {
      Toast.show('导入已取消');
    }
  }

  function stop() {
    poller.stop();
  }

  window.GululuDownload = { buildSection, resume, stop };
})();
