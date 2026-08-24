/* 楼层导出（图片渲染）：批量导出 NGA / 骨碌碌楼层为 PNG/WebP，并在阅读器楼层头部注入“分享”按钮。 */
(function () {
  'use strict';

  const STORE_KEY = 'ankeshelf.floor_export.v1';
  const THEMES = [['light', '浅色'], ['sepia', '羊皮纸'], ['dark', '深色'], ['current', '按当前阅读设定']];
  const FORMATS = [['png', 'PNG'], ['webp', 'WebP']];
  const SCALES = [[1, '1x'], [1.5, '1.5x'], [2, '2x'], [3, '3x']];

  const state = {
    bookId: '',
    floors: [],
    selected: new Set(),
    poller: null,
    built: false,
  };

  function loadSettings() {
    const defaults = { theme: 'light', fmt: 'png', scale: 2, outputDir: '', lastBookId: '' };
    let local = {};
    try {
      local = JSON.parse(localStorage.getItem(STORE_KEY) || '{}');
    } catch (e) { local = {}; }
    const backend = (App.state && App.state.settings && App.state.settings.floor_export) || {};
    return Object.assign(defaults, local, backend);
  }

  function saveSettings(patch) {
    const next = Object.assign(loadSettings(), patch);
    try { localStorage.setItem(STORE_KEY, JSON.stringify(next)); } catch (e) { /* ignore */ }
    if (App.state && App.state.settings) {
      App.state.settings.floor_export = next;
      Api.saveSettings({ floor_export: next }).catch(() => {});
    }
  }

  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function field(labelText, input) {
    const wrap = el('div', 'nga-field');
    const label = el('label', 'nga-field-label', labelText);
    wrap.append(label, input);
    return wrap;
  }

  function view() { return document.getElementById('floor-export-view'); }

  function close() {
    const v = view();
    if (v) v.classList.add('hidden');
    stopPoller();
  }

  function stopPoller() {
    if (state.poller) { clearInterval(state.poller); state.poller = null; }
  }

  function readerColors() {
    const root = document.getElementById('reader-root') || document.documentElement;
    const cs = getComputedStyle(root);
    return {
      bg: cs.getPropertyValue('--reader-bg').trim() || '#ffffff',
      fg: cs.getPropertyValue('--reader-fg').trim() || '#201a15',
      accent: cs.getPropertyValue('--reader-accent').trim() || '#8b5a2b',
    };
  }

  function readerStyle() {
    const s = (App.state && App.state.settings) || {};
    const pageWidth = Number(s.page_width) || 1.0;
    // 阅读界面 .chapter-wrap 的 max-width=46em*page_width，em 相对父文档
    // 字号（默认 16px，可受 UI 字体缩放影响），不是 iframe 正文字号。
    let parentFontSize = 16;
    try {
      const v = parseFloat(getComputedStyle(document.body).fontSize);
      if (Number.isFinite(v) && v > 0) parentFontSize = v;
    } catch (e) { parentFontSize = 16; }
    const contentWidthPx = Math.max(200, Math.round(46 * pageWidth * parentFontSize - 64));
    return {
      font_family: window.ReaderUtils ? ReaderUtils.resolveFamily() : '"Segoe UI", "Microsoft YaHei", serif',
      font_size: s.font_size || 18,
      line_height: s.line_height || 1.8,
      page_width: pageWidth,
      content_width_px: contentWidthPx,
      font_face_css: window.ReaderUtils ? ReaderUtils.fontFaceCss() : '',
    };
  }

  function startExport(bookId, floors, theme, opts, noImages) {
    const s = Object.assign({}, opts);
    const colors = theme === 'current' ? readerColors() : null;
    // 后端 handler 是位置参数：
    // (book_id, floors, theme, fmt, scale, output_dir, no_images, theme_colors, reader_style)
    return Api.floorExportStart(
      bookId,
      floors,
      colors ? 'light' : theme,
      s.fmt,
      s.scale,
      s.outputDir || '',
      !!noImages,
      colors,
      readerStyle(),
    );
  }

  function shareFloor(bookId, floorNum) {
    const s = loadSettings();
    return startExport(bookId, [floorNum], 'current', s, false).then((r) => {
      if (r && r.ok === false) throw new Error(r.error || '导出失败');
      pollStatus(null, { bookId, floors: [floorNum] });
      return r;
    });
  }

  function toast(msg) {
    if (window.Toast && Toast.show) Toast.show(msg);
    else if (window.App && App.setStatus) App.setStatus(msg);
  }

  function pollStatus(onDone, ctx) {
    stopPoller();
    state.poller = setInterval(async () => {
      let s;
      try { s = await Api.floorExportStatus(); } catch (e) { return; }
      const st = document.getElementById('fe-status');
      if (st) st.textContent = s.detail || s.stage || '';
      const pb = document.getElementById('fe-progress-bar');
      if (pb) {
        const pct = s.total > 0 ? Math.round((s.current / s.total) * 100) : 0;
        pb.style.width = Math.max(0, Math.min(100, pct)) + '%';
      }
      if (!s.running) {
        stopPoller();
        const btn = document.getElementById('fe-start');
        if (btn) btn.disabled = false;
        if (s.stage === 'done') {
          toast(`已导出 ${s.files.length} 个楼层`);
          if (s.image_failed > 0 && ctx) {
            if (confirm(`有 ${s.image_failed} 张图片加载失败，是否以无图模式重新导出这些楼层？`)) {
              restartNoImages(ctx);
            }
          }
        } else if (s.stage === 'error') {
          toast(`导出失败：${s.error || '未知错误'}`);
        } else if (s.stage === 'cancelled') {
          toast('导出已取消');
        }
        if (onDone) onDone(s);
      }
    }, 700);
  }

  async function restartNoImages(ctx) {
    const s = loadSettings();
    try {
      await startExport(ctx.bookId, ctx.floors, s.theme, s, true);
      pollStatus(null, ctx);
    } catch (e) {
      toast(`无图重导失败：${e && e.message || e}`);
    }
  }

  function buildView() {
    const v = el('div', 'download-view hidden');
    v.id = 'floor-export-view';

    const head = el('div', 'settings-head');
    const back = el('button', 'top-btn');
    back.title = '返回';
    back.appendChild(Icons.icon('library', 18));
    back.addEventListener('click', close);
    const title = el('div', 'settings-title', '楼层导出');
    head.append(back, title);
    v.appendChild(head);

    const body = el('div', 'download-body');

    const bookSection = el('div', 'settings-section');
    bookSection.appendChild(el('div', 'settings-section-title', '选择安科'));
    const bookSelect = el('select');
    bookSelect.id = 'fe-book';
    bookSelect.appendChild(el('option', '', '请选择书籍…'));
    bookSelect.addEventListener('change', async () => {
      state.bookId = bookSelect.value;
      state.selected.clear();
      saveSettings({ lastBookId: bookSelect.value });
      await refreshFloors();
    });
    bookSection.appendChild(field('安科', bookSelect));
    body.appendChild(bookSection);

    const floorSection = el('div', 'settings-section');
    floorSection.appendChild(el('div', 'settings-section-title', '选择楼层'));
    const floorTools = el('div', 'nga-form-row');
    const allBtn = el('button', 'btn', '全选');
    allBtn.addEventListener('click', () => {
      state.floors.forEach((f) => state.selected.add(f.num));
      renderFloorList();
    });
    const noneBtn = el('button', 'btn', '清空');
    noneBtn.addEventListener('click', () => {
      state.selected.clear();
      renderFloorList();
    });
    floorTools.append(allBtn, noneBtn);
    floorSection.appendChild(floorTools);
    const filterRow = el('div', 'nga-form-row');
    const filter = el('input');
    filter.placeholder = '筛选楼层，例如 1-10、15、20';
    filter.addEventListener('input', () => renderFloorList(filter.value));
    filterRow.appendChild(field('筛选', filter));
    floorSection.appendChild(filterRow);
    const floorList = el('div', 'fe-floor-list');
    floorList.id = 'fe-floor-list';
    floorSection.appendChild(floorList);
    body.appendChild(floorSection);

    const optSection = el('div', 'settings-section');
    optSection.appendChild(el('div', 'settings-section-title', '导出设定'));
    const row = el('div', 'nga-form-row');
    const themeSelect = el('select');
    THEMES.forEach(([value, label]) => themeSelect.appendChild(new Option(label, value)));
    const fmtSelect = el('select');
    FORMATS.forEach(([value, label]) => fmtSelect.appendChild(new Option(label, value)));
    const scaleSelect = el('select');
    SCALES.forEach(([value, label]) => scaleSelect.appendChild(new Option(label, String(value))));
    const persistSelects = () => saveSettings({
      theme: themeSelect.value,
      fmt: fmtSelect.value,
      scale: Number(scaleSelect.value),
    });
    themeSelect.addEventListener('change', persistSelects);
    fmtSelect.addEventListener('change', persistSelects);
    scaleSelect.addEventListener('change', persistSelects);
    const dirBtn = el('button', 'btn', '选择输出目录');
    const dirLabel = el('span', 'fe-dir', '未选择');
    dirBtn.addEventListener('click', async () => {
      const r = await Api.pickFolder('选择楼层导出文件夹');
      if (r && r.path) {
        saveSettings({ outputDir: r.path });
        dirLabel.textContent = r.path;
      }
    });
    const dirWrap = field('输出目录', (() => {
      const box = el('div', 'nga-form-row');
      box.append(dirBtn, dirLabel);
      return box;
    })());
    row.append(field('主题', themeSelect), field('格式', fmtSelect), field('倍率', scaleSelect), dirWrap);
    optSection.appendChild(row);
    body.appendChild(optSection);

    const runSection = el('div', 'settings-section');
    const progress = el('div', 'fe-progress');
    progress.id = 'fe-progress';
    const bar = el('div', 'fe-progress-bar');
    bar.id = 'fe-progress-bar';
    progress.appendChild(bar);
    const status = el('div', 'fe-status', '请先选择书籍与楼层');
    status.id = 'fe-status';
    const actions = el('div', 'nga-form-row');
    const startBtn = el('button', 'btn', '开始导出');
    startBtn.id = 'fe-start';
    const cancelBtn = el('button', 'btn', '取消');
    const openBtn = el('button', 'btn', '打开目录');
    startBtn.addEventListener('click', async () => {
      const floors = Array.from(state.selected).sort((a, b) => a - b);
      if (!state.bookId || floors.length === 0) {
        toast('请选择书籍和至少一个楼层');
        return;
      }
      startBtn.disabled = true;
      const s = loadSettings();
      saveSettings({ theme: themeSelect.value, fmt: fmtSelect.value, scale: Number(scaleSelect.value) });
      try {
        await startExport(state.bookId, floors, themeSelect.value, s, false);
        pollStatus(null, { bookId: state.bookId, floors });
      } catch (e) {
        toast(`导出失败：${e && e.message || e}`);
        startBtn.disabled = false;
      }
    });
    cancelBtn.addEventListener('click', () => Api.floorExportCancel());
    openBtn.addEventListener('click', () => Api.floorExportOpenDest());
    actions.append(startBtn, cancelBtn, openBtn);
    runSection.append(progress, status, actions);
    body.appendChild(runSection);

    v.appendChild(body);
    document.body.appendChild(v);

    const s = loadSettings();
    themeSelect.value = s.theme;
    fmtSelect.value = s.fmt;
    scaleSelect.value = String(s.scale);
    dirLabel.textContent = s.outputDir || '未选择';

    state.built = true;
    return v;
  }

  function renderFloorList(filterText) {
    const box = document.getElementById('fe-floor-list');
    if (!box) return;
    box.textContent = '';
    const filter = (filterText || '').trim();
    const items = state.floors.filter((f) => !filter || f.label.includes(filter) || String(f.num).includes(filter));
    const frag = document.createDocumentFragment();
    items.forEach((f) => {
      const label = el('label', 'nga-check');
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = state.selected.has(f.num);
      cb.addEventListener('change', () => {
        if (cb.checked) state.selected.add(f.num);
        else state.selected.delete(f.num);
      });
      label.appendChild(cb);
      label.appendChild(document.createTextNode(f.label));
      frag.appendChild(label);
    });
    box.appendChild(frag);
  }

  async function refreshFloors() {
    const status = document.getElementById('fe-status');
    if (!state.bookId) {
      state.floors = [];
      renderFloorList();
      return;
    }
    status.textContent = '正在读取楼层…';
    const data = await Api.floorExportFloors(state.bookId);
    state.floors = data.floors || [];
    state.selected.clear();
    renderFloorList();
    status.textContent = `共 ${state.floors.length} 层，请选择要导出的楼层`;
  }

  async function open() {
    const v = view() || buildView();
    v.classList.remove('hidden');
    const select = document.getElementById('fe-book');
    const s = loadSettings();
    const dirLabel = document.querySelector('.fe-dir');
    if (dirLabel) dirLabel.textContent = s.outputDir || '未选择';
    if (select && select.options.length <= 1) {
      const shelf = await Api.getShelf();
      shelf.forEach((book) => {
        if (!book.nga_tid && !book.gululu_source_id) return;
        const opt = new Option(book.title, book.id);
        select.appendChild(opt);
      });
    }
    const s2 = loadSettings();
    if (s2.lastBookId && select) {
      select.value = s2.lastBookId;
      state.bookId = s2.lastBookId;
      state.selected.clear();
      await refreshFloors();
    }
    const status = document.getElementById('fe-status');
    if (status) status.textContent = '请先选择书籍';
  }

  // ---------- 阅读器楼层头部“分享”按钮 ----------

  function floorNumFromHead(head) {
    const node = head.querySelector('.lou') || head.querySelector('.floor-number');
    const text = node ? node.textContent.trim() : '';
    const m = text.match(/(\d+)/);
    return m ? Number(m[1]) : 0;
  }

  function onChapterLoaded(doc) {
    if (!doc || !doc.body) return;
    if (!doc.getElementById('__floor_share_buttons__')) {
      const style = doc.createElement('style');
      style.id = '__floor_share_buttons__';
      style.textContent = `
        .gululu-floor > .floor-head { display:flex; align-items:center; gap:.6em; }
        .nga-floor .floor-head { line-height:1.6; }
        .floor-share-button {
          flex:0 0 auto; border:1px solid color-mix(in srgb, currentColor 24%, transparent);
          border-radius:4px; padding:.28em .6em; margin-left:.6em; background:transparent; color:inherit;
          cursor:pointer; font:inherit; font-size:.86em; vertical-align:middle;
        }
        .floor-share-button:hover { background:color-mix(in srgb, currentColor 8%, transparent); }
      `;
      doc.head.appendChild(style);
    }
    doc.querySelectorAll('.nga-floor[id^="pid"], .gululu-floor[id^="floor-"]').forEach((section) => {
      const head = section.querySelector('.floor-head');
      if (!head || head.querySelector('.floor-share-button')) return;
      const num = floorNumFromHead(head);
      if (!num && num !== 0) return;
      const button = doc.createElement('button');
      button.type = 'button';
      button.className = 'floor-share-button';
      button.setAttribute('data-textpos-exclude', 'true');
      button.textContent = '分享';
      button.addEventListener('click', async (event) => {
        event.preventDefault();
        event.stopPropagation();
        button.disabled = true;
        try {
          await shareFloor(App.state.bookId, num);
          toast('已开始导出本楼，请稍候…');
        } catch (e) {
          toast(`分享失败：${e && e.message || e}`);
        } finally {
          button.disabled = false;
        }
      });
      head.appendChild(button);
    });
  }

  window.FloorExport = { open, close, onChapterLoaded, shareFloor };

  document.addEventListener('DOMContentLoaded', () => {
    const btn = document.getElementById('floor-export-btn');
    if (btn) btn.addEventListener('click', () => FloorExport.open());
  });
})();
