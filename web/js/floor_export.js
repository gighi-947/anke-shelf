/* 楼层导出（图片渲染）：批量导出 NGA / 骨碌碌楼层为 PNG/WebP，并在阅读器楼层头部注入“分享”按钮。 */
(function () {
  'use strict';

  const STORE_KEY = 'ankeshelf.floor_export.v1';
  const THEMES = [['light', '浅色'], ['sepia', '羊皮纸'], ['dark', '深色']];
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
    try {
      return Object.assign({ theme: 'light', fmt: 'png', scale: 2, outputDir: '' },
        JSON.parse(localStorage.getItem(STORE_KEY) || '{}'));
    } catch (e) { return { theme: 'light', fmt: 'png', scale: 2, outputDir: '' }; }
  }

  function saveSettings(patch) {
    const next = Object.assign(loadSettings(), patch);
    localStorage.setItem(STORE_KEY, JSON.stringify(next));
  }

  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function view() { return document.getElementById('floor-export-view'); }

  function close() {
    const v = view();
    if (v) v.classList.add('hidden');
    if (state.poller) { clearInterval(state.poller); state.poller = null; }
  }

  function currentTheme() {
    const s = App.state.settings || {};
    if (s.theme_mode === 'system') {
      return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }
    if (s.theme === 'dark' || s.theme === 'sepia' || s.theme === 'light') return s.theme;
    return 'light';
  }

  function shareFloor(bookId, floorNum) {
    const s = loadSettings();
    return Api.floorExportStart({
      book_id: bookId,
      floors: [floorNum],
      theme: currentTheme(),
      fmt: s.fmt,
      scale: s.scale,
      output_dir: s.outputDir || '',
    }).then((r) => {
      if (r && r.ok === false) throw new Error(r.error || '导出失败');
      pollStatus(null, [floorNum]);
      return r;
    });
  }

  function toast(msg) {
    if (window.Toast && Toast.show) Toast.show(msg);
    else if (window.App && App.state) App.setStatus(msg);
  }

  function pollStatus(onDone, fallbackFloors) {
    if (state.poller) clearInterval(state.poller);
    state.poller = setInterval(async () => {
      let s;
      try { s = await Api.floorExportStatus(); } catch (e) { return; }
      const st = document.getElementById('fe-status');
      if (st) st.textContent = s.detail || s.stage || '';
      if (!s.running) {
        clearInterval(state.poller);
        state.poller = null;
        const btn = document.getElementById('fe-start');
        if (btn) btn.disabled = false;
        if (s.stage === 'done') {
          toast(`已导出 ${s.files.length} 个楼层`);
          if (s.image_failed > 0) {
            if (confirm(`有 ${s.image_failed} 张图片加载失败，是否以无图模式重新导出这些楼层？`)) {
              const cur = loadSettings();
              restartNoImages(cur, fallbackFloors || Array.from(state.selected));
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

  async function restartNoImages(cur, floors) {
    await Api.floorExportStart({
      book_id: state.bookId,
      floors,
      theme: cur.theme,
      fmt: cur.fmt,
      scale: cur.scale,
      output_dir: cur.outputDir || '',
      no_images: true,
    });
    pollStatus();
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

    const layout = el('div', 'settings-layout');
    const panels = el('div', 'settings-panels');
    const panel = el('div', 'settings-panel active');
    panel.style.flexDirection = 'column';

    // 书籍选择
    const bookSection = el('div', 'settings-section');
    bookSection.appendChild(el('div', 'settings-section-title', '选择安科'));
    const bookSelect = el('select', 'nga-field');
    bookSelect.id = 'fe-book';
    bookSelect.appendChild(el('option', '', '请选择书籍…'));
    bookSelect.addEventListener('change', async () => {
      state.bookId = bookSelect.value;
      state.selected.clear();
      await refreshFloors();
    });
    bookSection.appendChild(bookSelect);
    panel.appendChild(bookSection);

    // 楼层选择
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
    const filter = el('input', 'nga-field');
    filter.placeholder = '筛选楼层…';
    filter.addEventListener('input', () => renderFloorList(filter.value));
    floorTools.append(allBtn, noneBtn, filter);
    floorSection.appendChild(floorTools);
    const floorList = el('div', 'fe-floor-list');
    floorList.id = 'fe-floor-list';
    floorSection.appendChild(floorList);
    panel.appendChild(floorSection);

    // 导出设定
    const optSection = el('div', 'settings-section');
    optSection.appendChild(el('div', 'settings-section-title', '导出设定'));
    const row = el('div', 'nga-form-row');
    const themeSelect = el('select', 'nga-field');
    THEMES.forEach(([value, label]) => themeSelect.appendChild(new Option(label, value)));
    const fmtSelect = el('select', 'nga-field');
    FORMATS.forEach(([value, label]) => fmtSelect.appendChild(new Option(label, value)));
    const scaleSelect = el('select', 'nga-field');
    SCALES.forEach(([value, label]) => scaleSelect.appendChild(new Option(label, String(value))));
    const dirBtn = el('button', 'btn', '选择输出目录');
    const dirLabel = el('span', 'fe-dir', '未选择');
    dirBtn.addEventListener('click', async () => {
      const r = await Api.pickFolder('选择楼层导出文件夹');
      if (r && r.path) {
        saveSettings({ outputDir: r.path });
        dirLabel.textContent = r.path;
      }
    });
    row.append(themeSelect, fmtSelect, scaleSelect, dirBtn, dirLabel);
    optSection.appendChild(row);
    panel.appendChild(optSection);

    // 进度与操作
    const runSection = el('div', 'settings-section');
    const status = el('div', 'fe-status', '请先选择书籍与楼层');
    status.id = 'fe-status';
    const actions = el('div', 'nga-form-row');
    const startBtn = el('button', 'btn', '开始导出');
    startBtn.id = 'fe-start';
    const cancelBtn = el('button', 'btn', '取消');
    const openBtn = el('button', 'btn', '打开目录');
    startBtn.addEventListener('click', async () => {
      const cur = loadSettings();
      const floors = Array.from(state.selected).sort((a, b) => a - b);
      if (!state.bookId || floors.length === 0) {
        toast('请选择书籍和至少一个楼层');
        return;
      }
      startBtn.disabled = true;
      saveSettings({ theme: themeSelect.value, fmt: fmtSelect.value, scale: Number(scaleSelect.value) });
      await Api.floorExportStart({
        book_id: state.bookId,
        floors,
        theme: themeSelect.value,
        fmt: fmtSelect.value,
        scale: Number(scaleSelect.value),
        output_dir: cur.outputDir || '',
      });
      pollStatus();
    });
    cancelBtn.addEventListener('click', () => Api.floorExportCancel());
    openBtn.addEventListener('click', () => Api.floorExportOpenDest());
    actions.append(startBtn, cancelBtn, openBtn);
    runSection.append(status, actions);
    panel.appendChild(runSection);

    panels.appendChild(panel);
    layout.appendChild(panels);
    v.appendChild(layout);
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
      const label = el('label', 'fe-floor-item');
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
    const style = doc.createElement('style');
    style.id = '__floor_share_buttons__';
    style.textContent = `
      .floor-head { display:flex; align-items:center; gap:.6em; }
      .floor-share-button {
        flex:0 0 auto; border:1px solid color-mix(in srgb, currentColor 24%, transparent);
        border-radius:4px; padding:.28em .6em; background:transparent; color:inherit;
        cursor:pointer; font:inherit; font-size:.86em;
      }
      .floor-share-button:hover { background:color-mix(in srgb, currentColor 8%, transparent); }
    `;
    if (!doc.getElementById('__floor_share_buttons__')) doc.head.appendChild(style);
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
