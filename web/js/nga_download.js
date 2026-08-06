/**
 * 下载 / 导出整合页：帖子下载（含实时状态）、NGA 配置、帖子导出（含实时状态）。
 * 后端：NgaService（下载）+ ExportService（导出），全部走 HTTP API。
 */
(function () {
  'use strict';

  let selectedFmt = 'both';

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
    title.textContent = '下载 / 导出';
    head.append(back, title);
    el.appendChild(head);

    const body = document.createElement('div');
    body.className = 'download-body';
    body.appendChild(buildDownloadSection());
    body.appendChild(buildUpdateSection());
    body.appendChild(buildConfigSection());
    body.appendChild(buildExportSection());
    el.appendChild(body);
    document.body.appendChild(el);
    return el;
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

  function buildDownloadSection() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';

    wrap.appendChild(field('帖子', input('nga-tid', '帖子 tid 或链接，如 41989465 或 read.php?tid=41989465')));

    const authorRow = field('只看楼主', input('nga-authorid', '楼主 uid（留空=全部楼层）'));
    authorRow.id = 'nga-authorid-row';
    wrap.appendChild(authorRow);

    const row1 = document.createElement('div');
    row1.className = 'nga-form-row';
    row1.append(
      field('前 N 楼', numInput('nga-max-floors', '0', '0=全部')),
      field('每章楼层数', numInput('nga-per-chapter', '20', '')),
    );
    wrap.appendChild(row1);

    const row2 = document.createElement('div');
    row2.className = 'nga-form-row';
    row2.append(
      field('图片', select('nga-image-mode', [
        ['online', '在线图片'],
        ['embedded', '嵌入图片'],
        ['none', '不含图片'],
      ])),
      field('主题', select('nga-theme', [
        ['light', '浅色'],
        ['dark', '深色'],
      ])),
    );
    wrap.appendChild(row2);

    const row3 = document.createElement('div');
    row3.className = 'nga-form-row';
    row3.append(
      field('目录楼 pid', numInput('nga-toc-pid', '0', '安科目录楼 pid，0=无')),
      field('目录用途', select('nga-toc-mode', [
        ['index', '仅作索引'],
        ['split', '兼作分章'],
      ])),
      field('完成后打开', checkbox('nga-open-after', true)),
    );
    wrap.appendChild(row3);

    const row4 = document.createElement('div');
    row4.className = 'nga-form-row';
    row4.append(field('全量重下', checkbox('nga-full', false, '清除本地缓存后重新下载')));
    wrap.appendChild(row4);

    const tocHint = document.createElement('p');
    tocHint.className = 'muted settings-hint';
    tocHint.textContent = '目录用途：仅作索引=仍按每章楼层数分章（目录只用于导出/侧栏索引）；兼作分章=按目录章节切分章节（未提供目录楼时仍按楼层数分章）。';
    wrap.appendChild(tocHint);

    const actions = document.createElement('div');
    actions.className = 'nga-actions';
    const startBtn = document.createElement('button');
    startBtn.className = 'btn btn-primary';
    startBtn.id = 'nga-start';
    startBtn.textContent = '开始下载';
    startBtn.addEventListener('click', startDownload);
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'btn';
    cancelBtn.id = 'nga-cancel';
    cancelBtn.textContent = '取消任务';
    cancelBtn.disabled = true;
    cancelBtn.addEventListener('click', cancelDownload);
    actions.append(startBtn, cancelBtn);
    wrap.appendChild(actions);

    // 下载状态
    const status = document.createElement('div');
    status.className = 'nga-progress';
    status.id = 'nga-progress';
    const stage = document.createElement('div');
    stage.className = 'nga-progress-stage';
    stage.id = 'nga-progress-stage';
    stage.textContent = '当前无下载任务';
    const track = document.createElement('div');
    track.className = 'nga-progress-track';
    const fill = document.createElement('div');
    fill.className = 'nga-progress-fill';
    fill.id = 'nga-progress-fill';
    track.appendChild(fill);
    const text = document.createElement('div');
    text.className = 'nga-progress-text';
    text.id = 'nga-progress-text';
    status.append(stage, track, text);
    wrap.appendChild(status);

    return section('帖子下载', wrap);
  }

  function buildUpdateSection() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';

    const bookSel = select('dl-update-book', []);
    wrap.appendChild(field('帖子', bookSel));
    bookSel.addEventListener('change', () => loadUpdateDefaults(val('dl-update-book')));

    const row1 = document.createElement('div');
    row1.className = 'nga-form-row';
    row1.append(
      field('只看楼主 uid', numInput('dl-update-authorid', '0', '0=全部楼层')),
    );
    wrap.appendChild(row1);

    const row2 = document.createElement('div');
    row2.className = 'nga-form-row';
    row2.append(
      field('主题', select('dl-update-theme', [
        ['light', '浅色'],
        ['dark', '深色'],
      ])),
      field('图片', select('dl-update-image-mode', [
        ['online', '在线图片'],
        ['embedded', '嵌入图片'],
        ['none', '不含图片'],
      ])),
    );
    wrap.appendChild(row2);

    const row3 = document.createElement('div');
    row3.className = 'nga-form-row';
    row3.append(
      field('每章楼层数', numInput('dl-update-per-chapter', '20', '')),
      field('目录楼 pid', numInput('dl-update-toc-pid', '0', '0=无')),
    );
    wrap.appendChild(row3);

    const actions = document.createElement('div');
    actions.className = 'nga-actions';
    const startBtn = document.createElement('button');
    startBtn.className = 'btn btn-primary';
    startBtn.id = 'dl-update-start';
    startBtn.textContent = '开始更新';
    startBtn.addEventListener('click', startUpdate);
    actions.appendChild(startBtn);
    wrap.appendChild(actions);

    const hint = document.createElement('p');
    hint.className = 'muted settings-hint';
    hint.textContent = '更新设置仅对本次新增楼层生效，不影响已有楼层；进度显示在下方“帖子下载”区域。';
    wrap.appendChild(hint);

    return section('更新帖子', wrap);
  }

  function buildConfigSection() {
    const cfgBox = document.createElement('div');
    cfgBox.className = 'settings-controls';
    cfgBox.append(
      field('ngaPassportUid', input('nga-cfg-uid', '浏览器 Cookie 中的 ngaPassportUid')),
      field('ngaPassportCid', input('nga-cfg-cid', '浏览器 Cookie 中的 ngaPassportCid')),
      field('User-Agent', input('nga-cfg-ua', '浏览器 UA（已默认填入）')),
    );
    const btns = document.createElement('div');
    btns.className = 'nga-actions';
    const save = document.createElement('button');
    save.className = 'btn';
    save.textContent = '保存配置';
    save.addEventListener('click', saveConfig);
    const clear = document.createElement('button');
    clear.className = 'btn btn-danger';
    clear.textContent = '清除已保存配置';
    clear.addEventListener('click', clearConfig);
    btns.append(save, clear);
    cfgBox.appendChild(btns);
    return section('NGA 登录配置（Cookie）', cfgBox);
  }

  function buildExportSection() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';

    const bookSel = select('dl-export-book', []);
    wrap.appendChild(field('帖子', bookSel));
    const emptyHint = document.createElement('p');
    emptyHint.className = 'muted settings-hint';
    emptyHint.id = 'dl-export-empty';
    emptyHint.textContent = '暂无已下载的 NGA 帖子，请先完成一次下载。';
    emptyHint.classList.add('hidden');
    wrap.appendChild(emptyHint);

    const fmtRow = document.createElement('div');
    fmtRow.className = 'nga-form-row';
    const fmtWrap = document.createElement('div');
    fmtWrap.className = 'settings-control-inline';
    const fmtLabel = document.createElement('span');
    fmtLabel.className = 'settings-label';
    fmtLabel.textContent = '格式';
    const epubBtn = fmtBtn('EPUB', 'epub');
    const mdBtn = fmtBtn('Markdown', 'md');
    const bothBtn = fmtBtn('两者', 'both');
    fmtWrap.append(fmtLabel, epubBtn, mdBtn, bothBtn);
    fmtRow.appendChild(fmtWrap);
    wrap.appendChild(fmtRow);

    const actions = document.createElement('div');
    actions.className = 'nga-actions';
    const start = document.createElement('button');
    start.className = 'btn btn-primary';
    start.id = 'dl-export-start';
    start.textContent = '开始导出';
    start.addEventListener('click', startExport);
    const openDest = document.createElement('button');
    openDest.className = 'btn';
    openDest.id = 'dl-export-open';
    openDest.textContent = '打开文件夹';
    openDest.disabled = true;
    openDest.addEventListener('click', openExportDest);
    actions.append(start, openDest);
    wrap.appendChild(actions);

    const status = document.createElement('div');
    status.className = 'nga-progress';
    status.id = 'dl-export-status';
    const stage = document.createElement('div');
    stage.className = 'nga-progress-stage';
    stage.id = 'dl-export-stage';
    stage.textContent = '暂无导出任务';
    const track = document.createElement('div');
    track.className = 'nga-progress-track';
    const fill = document.createElement('div');
    fill.className = 'nga-progress-fill';
    fill.id = 'dl-export-fill';
    track.appendChild(fill);
    const text = document.createElement('div');
    text.className = 'nga-progress-text';
    text.id = 'dl-export-text';
    status.append(stage, track, text);
    wrap.appendChild(status);

    return section('导出帖子', wrap);
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

  async function startDownload() {
    const params = {
      tid: val('nga-tid'),
      authorid: intVal('nga-authorid'),
      max_floors: intVal('nga-max-floors'),
      per_chapter: intVal('nga-per-chapter') || 20,
      image_mode: val('nga-image-mode'),
      theme: val('nga-theme'),
      toc_pid: intVal('nga-toc-pid'),
      toc_mode: val('nga-toc-mode') || 'index',
      open_after: check('nga-open-after'),
      full_redownload: check('nga-full'),
    };
    try {
      const r = await Bridge.call('nga_start_download', params);
      if (!r.ok) {
        Toast.show(r.error || '启动失败', true);
        if (r.error && r.error.indexOf('已有') !== -1) pollDownload();
        return;
      }
      setDownloadRunning(true);
      pollDownload();
    } catch (e) {
      Toast.show('启动失败：' + (e.message || e), true);
    }
  }

  function cancelDownload() {
    Bridge.call('nga_cancel').catch(() => {});
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
      Bridge.call('nga_download_status').then((s) => {
        setDownloadRunning(s.running);
        renderDownloadStatus(s);
        if (s.running) pollDownload();
      }).catch(() => {});
      return;
    }
    downloadPoller.start(
      () => Bridge.call('nga_download_status'),
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
      const cfg = await Bridge.call('nga_get_config');
      setVal('nga-cfg-uid', cfg.uid || '');
      setVal('nga-cfg-cid', cfg.cid || '');
      setVal('nga-cfg-ua', cfg.ua || '');
    } catch (e) { /* 保持空表单 */ }
  }

  async function saveConfig() {
    try {
      const cfg = await Bridge.call('nga_save_config', {
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
      const cfg = await Bridge.call('nga_clear_config');
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
      books = (await Bridge.call('get_shelf')).filter((b) => b.nga_tid);
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
      const d = await Bridge.call('nga_update_defaults', bookId);
      if (!d || d.error) return;
      setVal('dl-update-authorid', d.author_id || '0');
      setVal('dl-update-theme', d.theme === 'dark' ? 'dark' : 'light');
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
      const r = await Bridge.call('export_start', bookId, selectedFmt);
      if (!r.ok) {
        Toast.show(r.error || '导出启动失败', true);
        if (r.error && r.error.indexOf('已有') !== -1) pollExport();
        return;
      }
      Toast.show('已开始导出，请在文件夹选择窗口中选择保存位置');
      pollExport();
    } catch (e) {
      Toast.show('导出启动失败：' + (e.message || e), true);
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
      theme: val('dl-update-theme'),
      image_mode: val('dl-update-image-mode'),
      per_chapter: intVal('dl-update-per-chapter') || 20,
      toc_pid: intVal('dl-update-toc-pid'),
    };
    try {
      const r = await Bridge.call('nga_update_book', bookId, params);
      if (!r.ok) {
        Toast.show(r.error || '更新启动失败', true);
        if (r.error && r.error.indexOf('已有') !== -1) pollDownload();
        return;
      }
      Toast.show('正在检查更新…');
      pollDownload();
    } catch (e) {
      Toast.show('更新启动失败：' + (e.message || e), true);
    }
  }

  function pollExport() {
    exportPoller.start(
      () => Bridge.call('export_status'),
      renderExportStatus,
      (s) => {
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
      const r = await Bridge.call('export_open_dest');
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
    pollExport();
    el.classList.remove('hidden');
    if (opts && opts.focusUpdate) {
      const upd = document.getElementById('dl-update-book');
      if (upd) {
        upd.scrollIntoView({ block: 'center', behavior: 'smooth' });
        upd.focus();
      }
    }
  }

  function close() {
    const el = view();
    if (el) el.classList.add('hidden');
    stopPolling();
    stopExportPolling();
  }

  window.NgaDownload = { open, close };

  document.addEventListener('DOMContentLoaded', () => {
    const btn = document.getElementById('nga-download-btn');
    if (btn) btn.addEventListener('click', () => NgaDownload.open());
  });
})();
