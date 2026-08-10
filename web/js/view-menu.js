/**
 * 阅读界面快捷排版菜单：仅保留字体、字号、行高、翻页方式、页面宽度。
 * 主题/亮度/辅助/快捷键/导出/数据等完整设置已移至独立设置页（settings.js）。
 */
(function () {
  'use strict';

  let fontsCache = null;

  async function loadFonts(force) {
    if (!force && fontsCache) return fontsCache;
    try {
      fontsCache = await Api.getFonts();
    } catch (e) {
      fontsCache = { fonts: [], global_font: '', book_fonts: {} };
    }
    return fontsCache;
  }

  function fillSelect(sel, fonts, placeholder) {
    sel.innerHTML = '';
    const ph = document.createElement('option');
    ph.value = '';
    ph.textContent = placeholder;
    sel.appendChild(ph);
    for (const f of fonts) {
      const o = document.createElement('option');
      o.value = f.key;
      o.textContent = f.label;
      sel.appendChild(o);
    }
    const pick = document.createElement('option');
    pick.value = '__pick__';
    pick.textContent = '导入自定义字体…';
    sel.appendChild(pick);
  }

  function build() {
    const el = document.createElement('div');
    el.className = 'view-menu hidden';
    el.id = 'view-menu';

    const title = document.createElement('div');
    title.className = 'vm-title';
    title.textContent = '排版';
    el.appendChild(title);

    // 全局字体
    el.appendChild(row('全局字体', fontSelect('vm-font-global', '跟随系统')));
    // 本书字体
    el.appendChild(row('本书字体', fontSelect('vm-font-book', '跟随全局')));
    // 字号
    el.appendChild(row('字号', fontSizeRow()));
    // 行高
    el.appendChild(row('行高', lineHeightRow()));
    // 翻页方式
    el.appendChild(row('翻页方式', layoutRow()));
    // 页面宽度
    el.appendChild(row('页面宽度', pageWidthRow()));
    // 完整设置入口
    const full = document.createElement('div');
    full.className = 'vm-row vm-full-settings';
    const b = document.createElement('button');
    b.className = 'vm-btn';
    b.textContent = '完整设置…';
    b.addEventListener('click', () => {
      ViewMenu.close();
      if (window.SettingsPage) SettingsPage.open();
    });
    full.appendChild(b);
    el.appendChild(full);

    document.body.appendChild(el);
    return el;
  }

  function row(label, controls) {
    const r = document.createElement('div');
    r.className = 'vm-row';
    const l = document.createElement('span');
    l.className = 'vm-label';
    l.textContent = label;
    r.append(l, controls);
    return r;
  }

  function fontSelect(id, placeholder) {
    const wrap = document.createElement('div');
    wrap.className = 'vm-control vm-font-wrap';
    const sel = document.createElement('select');
    sel.id = id;
    sel.className = 'vm-select';
    fillSelect(sel, [], placeholder);
    sel.addEventListener('change', async () => {
      const v = sel.value;
      if (v === '__pick__') {
        try {
          const r = await Api.pickFontFile();
          if (r && r.error) { Toast.show('导入字体失败：' + r.error, true); }
          else if (r && r.key) {
            fontsCache = null;
            await loadFonts(true);
            const gf = document.getElementById('vm-font-global');
            const bf = document.getElementById('vm-font-book');
            if (gf) fillSelect(gf, fontsCache.fonts || [], '跟随系统');
            if (bf) fillSelect(bf, fontsCache.fonts || [], '跟随全局');
            sel.value = r.key;
            applyFontChoice(id, r.key);
          }
        } catch (e) {
          Toast.show('导入字体失败：' + (e.message || e), true);
        }
        sync();
        return;
      }
      applyFontChoice(id, v);
      sync();
    });
    wrap.appendChild(sel);
    return wrap;
  }

  function applyFontChoice(id, value) {
    const s = App.state.settings;
    if (id === 'vm-font-global') {
      s.custom_font = value || '';
      Api.saveSettings( { custom_font: s.custom_font });
    } else {
      s.book_fonts = s.book_fonts || {};
      if (value) s.book_fonts[App.state.bookId] = value;
      else delete s.book_fonts[App.state.bookId];
      Api.saveSettings( { book_fonts: s.book_fonts });
    }
    if (window.Reader) Reader.updateOverrides();
  }

  function fontSizeRow() {
    const wrap = document.createElement('div');
    wrap.className = 'vm-control';
    const minus = btn('A−', () => { Reader.fontSize(-1); sync(); });
    const val = document.createElement('span');
    val.id = 'vm-font-size';
    val.className = 'vm-value';
    const plus = btn('A＋', () => { Reader.fontSize(1); sync(); });
    wrap.append(minus, val, plus);
    return wrap;
  }

  function lineHeightRow() {
    const wrap = document.createElement('div');
    wrap.className = 'vm-control';
    const minus = btn('−', () => { Reader.lineHeight(-0.1); sync(); });
    const val = document.createElement('span');
    val.id = 'vm-line-height';
    val.className = 'vm-value';
    const plus = btn('＋', () => { Reader.lineHeight(0.1); sync(); });
    wrap.append(minus, val, plus);
    return wrap;
  }

  function layoutRow() {
    const wrap = document.createElement('div');
    wrap.className = 'vm-control';
    const sel = document.createElement('select');
    sel.id = 'vm-layout';
    sel.className = 'vm-select';
    const o1 = document.createElement('option');
    o1.value = 'scroll';
    o1.textContent = '滚动阅读';
    const o2 = document.createElement('option');
    o2.value = 'auto';
    o2.textContent = '自动双页（横屏双页）';
    const o3 = document.createElement('option');
    o3.value = 'single';
    o3.textContent = '单页分页';
    const o4 = document.createElement('option');
    o4.value = 'dual';
    o4.textContent = '横屏双页（强制）';
    sel.append(o1, o2, o3, o4);
    sel.addEventListener('change', () => {
      const s = App.state.settings;
      const v = sel.value;
      s.pagination = v !== 'scroll';
      s.dual_page = v === 'dual';
      if (v === 'auto') s.auto_dual = true;
      else if (v === 'single') s.auto_dual = false;
      Api.saveSettings( {
        pagination: s.pagination,
        dual_page: s.dual_page,
        auto_dual: s.auto_dual !== false,
      });
      if (window.Reader) Reader.onPaginationChange();
      sync();
    });
    wrap.appendChild(sel);
    return wrap;
  }

  function pageWidthRow() {
    const wrap = document.createElement('div');
    wrap.className = 'vm-control vm-width-control';
    const input = document.createElement('input');
    input.type = 'range';
    input.id = 'vm-page-width';
    input.min = 50;
    input.max = 150;
    input.step = 5;
    input.value = Math.round((App.state.settings.page_width || 1) * 100);
    const val = document.createElement('span');
    val.id = 'vm-page-width-val';
    val.className = 'vm-value';
    val.textContent = input.value + '%';
    let saveTimer = null;
    input.addEventListener('input', () => {
      const v = parseInt(input.value, 10) / 100;
      App.state.settings.page_width = v;
      val.textContent = input.value + '%';
      if (window.Reader) Reader.setPageWidth(v);
      clearTimeout(saveTimer);
      saveTimer = setTimeout(() => {
        Api.saveSettings( { page_width: v });
      }, 250);
    });
    wrap.append(input, val);
    return wrap;
  }

  function btn(label, onClick) {
    const b = document.createElement('button');
    b.className = 'vm-btn';
    b.textContent = label;
    b.addEventListener('click', onClick);
    return b;
  }

  function sync() {
    const s = App.state.settings;
    const fs = document.getElementById('vm-font-size');
    if (fs) fs.textContent = s.font_size + 'px';
    const lh = document.getElementById('vm-line-height');
    if (lh) lh.textContent = s.line_height.toFixed(1);
    const layout = document.getElementById('vm-layout');
    if (layout) {
      if (!s.pagination) layout.value = 'scroll';
      else if (s.dual_page) layout.value = 'dual';
      else layout.value = s.auto_dual === false ? 'single' : 'auto';
    }
    const width = document.getElementById('vm-page-width');
    if (width) {
      width.value = Math.round((s.page_width || 1) * 100);
      const wv = document.getElementById('vm-page-width-val');
      if (wv) wv.textContent = width.value + '%';
    }
    const gf = document.getElementById('vm-font-global');
    if (gf && gf.value !== '__pick__') gf.value = s.custom_font || '';
    const bf = document.getElementById('vm-font-book');
    if (bf && bf.value !== '__pick__') {
      bf.value = (s.book_fonts && s.book_fonts[App.state.bookId]) || '';
    }
  }

  window.ViewMenu = {
    async toggle() {
      let el = document.getElementById('view-menu');
      if (!el) el = build();
      if (el.classList.contains('hidden')) {
        const data = await loadFonts(false);
        const gf = document.getElementById('vm-font-global');
        const bf = document.getElementById('vm-font-book');
        if (gf && gf.options.length <= 1) fillSelect(gf, data.fonts || [], '跟随系统');
        if (bf && bf.options.length <= 1) fillSelect(bf, data.fonts || [], '跟随全局');
        sync();
        el.classList.remove('hidden');
      } else {
        el.classList.add('hidden');
      }
    },
    close() {
      const el = document.getElementById('view-menu');
      if (el) el.classList.add('hidden');
    },
    sync,
  };
})();
