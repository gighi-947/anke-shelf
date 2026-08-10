/**
 * Reader 快捷键帮助弹窗（B4）：在 reader.js 之后加载，合并回 window.Reader。
 */
(function () {
  'use strict';

  function overlayRoot() {
    let root = document.getElementById('overlay-root');
    if (!root) {
      root = document.createElement('div');
      root.id = 'overlay-root';
      document.body.appendChild(root);
    }
    return root;
  }

  Object.assign(window.Reader || {}, {
    /** 快捷键帮助弹窗（按 ? 或顶栏帮助按钮打开，Esc / 点击空白关闭）。 */
    showShortcuts() {
      let ov = document.getElementById('shortcut-help');
      if (!ov) {
        ov = document.createElement('div');
        ov.className = 'modal-overlay hidden';
        ov.id = 'shortcut-help';
        const box = document.createElement('div');
        box.className = 'help-modal';
        const title = document.createElement('div');
        title.className = 'help-modal-title';
        title.textContent = '快捷键';
        const close = document.createElement('button');
        close.className = 'vm-btn';
        close.textContent = '关闭 (Esc)';
        close.addEventListener('click', () => Reader.closeShortcuts());
        title.appendChild(close);
        const list = document.createElement('div');
        list.className = 'help-modal-list';
        list.id = 'shortcut-help-list';
        const hint = document.createElement('p');
        hint.className = 'help-hint';
        hint.textContent = 'Ctrl+F 打开全文搜索；滚动阅读模式下左右方向键直接切换章节；点击页面中央可切换顶栏/底栏；Esc 关闭弹窗或侧栏。';
        box.append(title, list, hint);
        ov.appendChild(box);
        ov.addEventListener('click', (e) => {
          if (e.target === ov) Reader.closeShortcuts();
        });
        overlayRoot().appendChild(ov);
      }
      const sc = Object.assign({}, ReaderUtils.HELP_SHORTCUTS, (App.state.settings && App.state.settings.shortcuts) || {});
      const list = document.getElementById('shortcut-help-list');
      list.innerHTML = '';
      for (const [action, label] of ReaderUtils.HELP_ACTIONS) {
        const row = document.createElement('div');
        row.className = 'help-row';
        const l = document.createElement('span');
        l.textContent = label;
        const k = document.createElement('kbd');
        k.className = 'help-key';
        k.textContent = Util.displayKey(sc[action]);
        row.append(l, k);
        list.appendChild(row);
      }
      ov.classList.remove('hidden');
    },

    closeShortcuts() {
      const ov = document.getElementById('shortcut-help');
      if (!ov || ov.classList.contains('hidden')) return false;
      ov.classList.add('hidden');
      return true;
    },
  });
})();
