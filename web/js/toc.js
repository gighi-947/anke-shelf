/**
 * Table of contents: nested tree rendering, navigation and highlight.
 */
(function () {
  'use strict';

  window.Toc = {
    render(toc) {
      const tree = document.getElementById('toc-tree');
      tree.innerHTML = '';
      const ul = document.createElement('ul');
      this._buildLevel(toc, ul);
      tree.appendChild(ul);
    },

    _buildLevel(entries, ul) {
      for (const e of entries) {
        const li = document.createElement('li');
        const btn = document.createElement('button');
        btn.className = 'toc-item';
        btn.textContent = e.label || '(No Title)';
        btn.dataset.spine = e.spine_index;
        if (e.spine_index === null || e.spine_index === undefined) {
          btn.disabled = true;
        } else {
          btn.addEventListener('click', () => {
            Sidebar.switchTab('toc');
            Sidebar.close();
            Reader.loadChapter(e.spine_index, 0);
          });
        }
        li.appendChild(btn);
        if (e.children && e.children.length) {
          const sub = document.createElement('ul');
          this._buildLevel(e.children, sub);
          li.appendChild(sub);
        }
        ul.appendChild(li);
      }
    },

    highlight(index) {
      document.querySelectorAll('#toc-tree .toc-item').forEach((el) => {
        el.classList.toggle('active', el.dataset.spine === String(index));
      });
    },
  };
})();
