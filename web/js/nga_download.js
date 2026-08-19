/**
 * 安科下载 / 导出整合页：骨碌碌 / NGA / 更新 / 导出 / 配置标签页，
 * 后台任务状态实时轮询并带运行指示。
 */
(function () {
  'use strict';

  let selectedFmt = 'both';
  let activeTab = 'dl-gululu';

  const TABS = [
    ['dl-gululu', '骨碌碌'],
    ['dl-download', 'NGA'],
    ['dl-update', '更新'],
    ['dl-export', '导出'],
    ['dl-config', '配置'],
  ];

  const view = () => document.getElementById('download-view');

  /** 通用后台任务轮询器：每次 start 使旧轮询失效，任务结束自动停止。 */
  function makePoller() {
    let timer = null;
    let token = 0;
    return {
      start(getStatus, onUpdate, onDone) {
        this.stop();
        const myToken = ++token;
        timer = setInterval(async () => {
          let s;
          try {
            s = await getStatus();
          } catch (e) {
            this.stop();
            s = { running: false, stage: 'error', error: e.message || String(e), detail: '' };
          }
          if (myToken !== token) return;
          onUpdate(s);
          if (!s.running) {
            this.stop();
            if (onDone) onDone(s);
          }
        }, 600);
      },
      stop() {
        if (timer) {
          clearInterval(timer);
          timer = null;
        }
      },
    };
  }

  const downloadPoller = makePoller();
  const exportPoller = makePoller();

  function ensureBuilt() {
    let el = view();
    if (el) return el;
    el = document.createElement('div');
    el.className = 'download-view hidden';
    el.id = 'download-view';

    const head = document.createElement('div');
    head.className = 'settings-head';
    const back = document.createElement('button');
    back.className = 'top-btn';
    back.title = '返回';
    back.appendChild(Icons.icon('library', 18));
    back.addEventListener('click', close);
    const title = document.createElement('div');
    title.className = 'settings-title';
    title.textContent = '安科下载 / 导出';
    head.append(back, title);
    el.appendChild(head);

    const layout = document.createElement('div');
    layout.className = 'settings-layout';
    const tabs = document.createElement('nav');
    tabs.className = 'settings-tabs download-tabs';
    const panels = document.createElement('div');
    panels.className = 'settings-panels download-panels';
    const panelById = {};

    for (const [id, label] of TABS) {
      const t = document.createElement('button');
      t.className = 'settings-tab download-tab' + (id === activeTab ? ' active' : '');
      t.dataset.tab = id;
      t.textContent = label;
      t.addEventListener('click', () => switchTab(id));
      tabs.appendChild(t);
      const p = document.createElement('div');
      p.className = 'settings-panel' + (id === activeTab ? ' active' : '');
      p.dataset.panel = id;
      p.id = 'dl-panel-' + id;
      panels.appendChild(p);
      panelById[id] = p;
    }
    layout.append(tabs, panels);
    el.appendChild(layout);

    panelById['dl-gululu'].appendChild(GululuDownload.buildSection());
    panelById['dl-download'].appendChild(NgaPanels.buildDownloadSection());
    panelById['dl-update'].appendChild(NgaPanels.buildUpdateSection());
    panelById['dl-export'].appendChild(NgaPanels.buildExportSection());
    panelById['dl-config'].appendChild(NgaPanels.buildConfigSection());

    document.body.appendChild(el);
    return el;
  }

  function switchTab(id) {
    activeTab = id;
    const el = view();
    if (!el) return;
    el.querySelectorAll('.download-tab').forEach((t) => {
      t.classList.toggle('active', t.dataset.tab === id);
    });
    el.querySelectorAll('.settings-panel').forEach((p) => {
      p.classList.toggle('active', p.dataset.panel === id);
    });
  }

  function section(titleText, content) {
    const s = document.createElement('div');
    s.className = 'settings-section';
    const t = document.createElement('div');
    t.className = 'settings-section-title';
    t.textContent = titleText;
    s.append(t, content);
    return s;
  }

  function fmtBtn(label, fmt) {
    const b = document.createElement('button');
    b.className = 'vm-btn';
    b.textContent = label;
    b.dataset.fmt = fmt;
    b.addEventListener('click', () => {
      selectedFmt = fmt;
      document.querySelectorAll('#download-view .vm-btn[data-fmt]').forEach((x) => {
        x.classList.toggle('active', x.dataset.fmt === fmt);
      });
    });
    if (fmt === selectedFmt) b.classList.add('active');
    return b;
  }

  function field(labelText, control) {
    const f = document.createElement('label');
    f.className = 'nga-field';
    const l = document.createElement('span');
    l.className = 'nga-field-label';
    l.textContent = labelText;
    f.append(l, control);
    return f;
  }

  function input(id, placeholder) {
    const el = document.createElement('input');
    el.id = id;
    el.type = 'text';
    el.placeholder = placeholder || '';
    el.autocomplete = 'off';
    el.spellcheck = false;
    return el;
  }

  function numInput(id, value, placeholder) {
    const el = document.createElement('input');
    el.id = id;
    el.type = 'number';
    el.value = value;
    el.placeholder = placeholder || '';
    el.min = '0';
    return el;
  }

  function select(id, options) {
    const el = document.createElement('select');
    el.id = id;
    for (const [v, label] of options) {
      const o = document.createElement('option');
      o.value = v;
      o.textContent = label;
      el.appendChild(o);
    }
    return el;
  }

  function checkbox(id, checked, hint) {
    const box = document.createElement('label');
    box.className = 'nga-check';
    const el = document.createElement('input');
    el.id = id;
    el.type = 'checkbox';
    el.checked = !!checked;
    const span = document.createElement('span');
    span.textContent = hint || '';
    box.append(el, span);
    return box;
  }

  // ---------- 下载 ----------

  function parseTid(raw) {
    const s = String(raw || '').trim();
    const m = s.match(/tid=(\d+)/i) || s.match(/^(\d+)$/);
    if (!m) return null;
    const tid = m[1];
    return /^\d{1,12}$/.test(tid) ? tid : null;
  }

  async function startDownload() {
    const tid = parseTid(val('nga-tid'));
    if (!tid) {
      Toast.show('请输入有效的帖子 tid 或链接（如 41989465）', true);
      return;
    }
    if (intVal('nga-per-chapter') < 1) {
      Toast.show('每章楼层数必须大于 0', true);
      return;
    }
    const params = {
      tid,
      authorid: intVal('nga-authorid'),
      max_floors: intVal('nga-max-floors'),
      per_chapter: intVal('nga-per-chapter') || 20,
      image_mode: val('nga-image-mode'),
      toc_pid: intVal('nga-toc-pid'),
      toc_mode: val('nga-toc-mode') || 'index',
      open_after: check('nga-open-after'),
      full_redownload: check('nga-full'),
    };
    try {
      await Api.ngaStartDownload( params);
      setDownloadRunning(true);
      pollDownload();
    } catch (e) {
      const msg = e.message || e;
      Toast.show('启动失败：' + msg, true);
      if (msg.indexOf('已有') !== -1) pollDownload();
    }
  }

  function cancelDownload() {
    Api.ngaCancel().catch(() => {});
    const stage = document.getElementById('nga-progress-stage');
    if (stage) stage.textContent = '正在取消…';
    const start = document.getElementById('nga-start');
    const cancel = document.getElementById('nga-cancel');
    if (start) start.disabled = true;
    if (cancel) cancel.disabled = true;
  }

  function pollDownload(fireOnDone = true) {
    if (!fireOnDone) {
      // 打开面板时只“接续”正在运行的任务；任务已结束（done/error/cancelled/idle）
      // 仅展示当前状态，避免把上一次的终态再次当作完成事件处理（例如重复跳转阅读器）。
      Api.ngaDownloadStatus().then((s) => {
        setDownloadRunning(s.running);
        renderDownloadStatus(s);
        if (s.running) pollDownload();
      }).catch(() => {});
      return;
    }
    downloadPoller.start(
      () => Api.ngaDownloadStatus(),
      (s) => {
        setDownloadRunning(s.running);
        renderDownloadStatus(s);
      },
      (s) => {
        setDownloadRunning(false);
        onDownloadFinished(s);
      },
    );
  }

  function stopPolling() {
    downloadPoller.stop();
  }

  function setDownloadRunning(running) {
    const start = document.getElementById('nga-start');
    const cancel = document.getElementById('nga-cancel');
    const update = document.getElementById('dl-update-start');
    if (start) start.disabled = running;
    if (cancel) cancel.disabled = !running;
    if (update) update.disabled = running;
    const el = view();
    if (el) {
      const tab = el.querySelector('.download-tab[data-tab="dl-download"]');
      if (tab) tab.classList.toggle('running', !!running);
    }
  }

  function renderDownloadStatus(s) {
    const stageEl = document.getElementById('nga-progress-stage');
    const fill = document.getElementById('nga-progress-fill');
    const text = document.getElementById('nga-progress-text');
    if (!stageEl || !fill || !text) return;
    const STAGE = {
      pages: '拉取页面', format: '格式化内容', markdown: '生成 Markdown',
      epub: '生成 EPUB', update: '检查更新', done: '完成', error: '失败',
      cancelled: '已取消', idle: '等待',
    };
    if (!s.running && s.stage === 'idle') {
      stageEl.textContent = '当前无下载任务';
      fill.style.width = '0%';
      text.textContent = '';
      return;
    }
    stageEl.textContent = STAGE[s.stage] || s.stage || '';
    const pct = s.total > 0 ? Math.round((s.current / s.total) * 100) : (s.stage === 'done' ? 100 : 0);
    fill.style.width = pct + '%';
    text.textContent = s.detail || '';
    if (s.stage === 'error' && s.error) {
      stageEl.textContent = '失败';
      text.textContent = s.error;
    }
  }

  function onDownloadFinished(s) {
    if (s.action === 'update') {
      if (s.stage === 'done') {
        Toast.show(s.detail || '帖子已更新');
        refreshBooks();
        if (window.Shelf) Shelf.render();
        if (App.state.bookId === s.book_id && App.state.view === 'reader') {
          Reader.loadChapter(App.state.chapterIndex, 0);
        }
      } else if (s.stage === 'error') {
        Toast.show('更新失败：' + (s.error || '未知错误'), true);
      } else if (s.stage === 'cancelled') {
        Toast.show('更新已取消');
      }
      return;
    }
    if (s.stage === 'done') {
      Toast.show('下载完成，已加入书架');
      refreshBooks();
      if (window.Shelf) Shelf.render();
      const openAfter = check('nga-open-after');
      if (openAfter && s.book_id) {
        close();
        App.showReader(s.book_id);
      }
    } else if (s.stage === 'error') {
      Toast.show('下载失败：' + (s.error || '未知错误'), true);
    } else if (s.stage === 'cancelled') {
      Toast.show('任务已取消，未完成文件已清理');
    }
  }

  // ---------- 配置 ----------

  async function refreshConfig() {
    try {
      const cfg = await Api.ngaGetConfig();
      setVal('nga-cfg-uid', cfg.uid || '');
      setVal('nga-cfg-cid', cfg.cid || '');
      setVal('nga-cfg-ua', cfg.ua || '');
    } catch (e) { /* 保持空表单 */ }
  }

  async function saveConfig() {
    try {
      const cfg = await Api.ngaSaveConfig( {
        uid: val('nga-cfg-uid'),
        cid: val('nga-cfg-cid'),
        ua: val('nga-cfg-ua'),
      });
      Toast.show(cfg.configured ? 'NGA 配置已保存' : '已保存（仍缺少必要 Cookie）');
    } catch (e) {
      Toast.show('保存失败：' + (e.message || e), true);
    }
  }

  async function clearConfig() {
    if (!confirm('确定清除本机已保存的 NGA 登录配置（Cookie/UA）？')) return;
    try {
      const cfg = await Api.ngaClearConfig();
      setVal('nga-cfg-uid', cfg.uid || '');
      setVal('nga-cfg-cid', cfg.cid || '');
      setVal('nga-cfg-ua', cfg.ua || '');
      Toast.show('NGA 配置已清除');
    } catch (e) {
      Toast.show('清除失败：' + (e.message || e), true);
    }
  }

  // ---------- 导出 ----------

  function fillBookSelect(sel, books, placeholder) {
    if (!sel) return;
    sel.innerHTML = '';
    if (!books.length) {
      sel.disabled = true;
      return;
    }
    sel.disabled = false;
    const ph = document.createElement('option');
    ph.value = '';
    ph.textContent = placeholder;
    sel.appendChild(ph);
    for (const b of books) {
      const o = document.createElement('option');
      o.value = b.id;
      o.textContent = (b.title || '未命名') + '（tid ' + b.nga_tid + '）';
      sel.appendChild(o);
    }
  }

  async function refreshBooks(preselectId) {
    const sel = document.getElementById('dl-export-book');
    const empty = document.getElementById('dl-export-empty');
    let books = [];
    try {
      books = (await Api.getShelf()).filter((b) => b.nga_tid);
    } catch (e) { /* 保持空 */ }
    fillBookSelect(sel, books, '选择要导出的帖子…');
    const uSel = document.getElementById('dl-update-book');
    fillBookSelect(uSel, books, '选择要更新的帖子…');
    if (!books.length) {
      if (empty) empty.classList.remove('hidden');
      return;
    }
    if (empty) empty.classList.add('hidden');
    if (preselectId) {
      if (sel) sel.value = preselectId;
      if (uSel) uSel.value = preselectId;
    }
    if (uSel && uSel.value) loadUpdateDefaults(uSel.value);
  }

  async function loadUpdateDefaults(bookId) {
    if (!bookId) return;
    try {
      const d = await Api.ngaUpdateDefaults( bookId);
      setVal('dl-update-authorid', d.author_id || '0');
      setVal('dl-update-image-mode', d.image_mode || 'online');
      setVal('dl-update-per-chapter', d.per_chapter || '20');
      setVal('dl-update-toc-pid', d.toc_pid || '0');
    } catch (e) { /* 保持当前表单值 */ }
  }

  async function startExport() {
    const bookId = val('dl-export-book');
    if (!bookId) {
      Toast.show('请先选择要导出的帖子', true);
      return;
    }
    try {
      await Api.exportStart( bookId, selectedFmt);
      Toast.show('已开始导出，请在文件夹选择窗口中选择保存位置');
      pollExport();
    } catch (e) {
      const msg = e.message || e;
      Toast.show('导出启动失败：' + msg, true);
      if (msg.indexOf('已有') !== -1) pollExport();
    }
  }

  async function startUpdate() {
    const bookId = val('dl-update-book');
    if (!bookId) {
      Toast.show('请先选择要更新的帖子', true);
      return;
    }
    const params = {
      authorid: intVal('dl-update-authorid'),
      image_mode: val('dl-update-image-mode'),
      per_chapter: intVal('dl-update-per-chapter') || 20,
      toc_pid: intVal('dl-update-toc-pid'),
    };
    try {
      await Api.ngaUpdateBook( bookId, params);
      Toast.show('正在检查更新…');
      switchTab('dl-download');
      pollDownload();
    } catch (e) {
      const msg = e.message || e;
      Toast.show('更新启动失败：' + msg, true);
      if (msg.indexOf('已有') !== -1) {
        switchTab('dl-download');
        pollDownload();
      }
    }
  }

  function pollExport() {
    exportPoller.start(
      () => Api.exportStatus(),
      renderExportStatus,
      (s) => {
        const cancelBtn = document.getElementById('dl-export-cancel');
        if (cancelBtn) cancelBtn.disabled = !s.running;
        if (s.stage === 'done') {
          const openBtn = document.getElementById('dl-export-open');
          if (openBtn) openBtn.disabled = false;
        }
      },
    );
  }

  function stopExportPolling() {
    exportPoller.stop();
  }

  function renderExportStatus(s) {
    const stage = document.getElementById('dl-export-stage');
    const fill = document.getElementById('dl-export-fill');
    const text = document.getElementById('dl-export-text');
    if (!stage || !fill || !text) return;
    const STAGE = {
      prepare: '准备导出', copy: '复制文件', done: '完成',
      error: '失败', cancelled: '已取消', idle: '暂无导出任务',
    };
    const el = view();
    if (el) {
      const tab = el.querySelector('.download-tab[data-tab="dl-export"]');
      if (tab) tab.classList.toggle('running', !!s.running);
    }
    if (!s.running && s.stage === 'idle') {
      stage.textContent = '暂无导出任务';
      fill.style.width = '0%';
      text.textContent = '';
      return;
    }
    stage.textContent = STAGE[s.stage] || s.stage || '';
    const pct = s.total > 0 ? Math.round((s.current / s.total) * 100) : (s.stage === 'done' ? 100 : 0);
    fill.style.width = pct + '%';
    text.textContent = s.detail || '';
    if (s.stage === 'error' && s.error) {
      stage.textContent = '失败';
      text.textContent = s.error;
    }
    if (s.stage === 'done' && s.dest) {
      text.textContent = '已导出到：' + s.dest;
    }
  }

  async function openExportDest() {
    try {
      const r = await Api.exportOpenDest();
      if (r && r.error) Toast.show('打开失败：' + r.error, true);
    } catch (e) {
      Toast.show('打开失败：' + (e.message || e), true);
    }
  }

  // ---------- 小工具 ----------

  function val(id) {
    const el = document.getElementById(id);
    return el ? el.value.trim() : '';
  }

  function intVal(id) {
    const v = val(id);
    return v ? parseInt(v, 10) || 0 : 0;
  }

  function check(id) {
    const el = document.getElementById(id);
    return el ? el.checked : false;
  }

  function setVal(id, v) {
    const el = document.getElementById(id);
    if (el) el.value = v;
  }

  // ---------- 页面开关 ----------

  function open(opts) {
    const el = ensureBuilt();
    refreshBooks(opts && opts.bookId);
    refreshConfig();
    const openBtn = document.getElementById('dl-export-open');
    if (openBtn) openBtn.disabled = true;
    pollDownload(false);
    GululuDownload.resume();
    pollExport();
    el.classList.remove('hidden');
    if (opts && opts.focusUpdate) {
      switchTab('dl-update');
      const upd = document.getElementById('dl-update-book');
      if (upd) {
        upd.focus();
      }
    } else if (opts && opts.tab) {
      switchTab(opts.tab);
    }
  }

  function close() {
    const el = view();
    if (el) el.classList.add('hidden');
    stopPolling();
    stopExportPolling();
    GululuDownload.stop();
    if (window.Shelf) Shelf.render();
  }

  window.NgaPage = { section, fmtBtn, field, input, numInput, select, checkbox, check, val, makePoller, refreshBooks, startDownload, cancelDownload, loadUpdateDefaults, startUpdate, saveConfig, clearConfig, startExport, openExportDest };

  window.NgaDownload = { open, close };

  document.addEventListener('DOMContentLoaded', () => {
    const btn = document.getElementById('nga-download-btn');
    if (btn) btn.addEventListener('click', () => NgaDownload.open());
  });
})();
