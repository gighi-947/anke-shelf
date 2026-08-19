/**
 * Reader 图片查看器（B4）：滚轮缩放、双击 1:1、点击/×/Esc 关闭。
 * 在 reader.js 之后加载，合并回 window.Reader。
 */
(function () {
  'use strict';

  let lightboxScale = 1;

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
    /** 图片点击放大：滚轮缩放（0.5x~5x），双击在适配/1:1 间切换。 */
    openImage(src) {
      if (!src) return;
      let ov = document.getElementById('image-lightbox');
      if (!ov) {
        ov = document.createElement('div');
        ov.className = 'image-lightbox hidden';
        ov.id = 'image-lightbox';
        const img = document.createElement('img');
        img.id = 'lightbox-img';
        img.alt = '';
        const close = document.createElement('button');
        close.className = 'lightbox-close';
        close.title = '关闭 (Esc)';
        close.setAttribute('aria-label', '关闭 (Esc)');
        close.appendChild(Icons.icon('close', 16));
        const hint = document.createElement('span');
        hint.className = 'lightbox-hint';
        hint.textContent = '滚轮缩放 · 双击 1:1 · 点击关闭';
        ov.append(img, close, hint);
        ov.addEventListener('click', (e) => {
          if (e.target === ov || e.target === close || e.target === hint) Reader.closeImage();
        });
        ov.addEventListener('wheel', (e) => {
          e.preventDefault();
          lightboxScale = Math.max(0.5, Math.min(5, lightboxScale + (e.deltaY < 0 ? 0.15 : -0.15)));
          img.style.transform = 'scale(' + lightboxScale + ')';
        }, { passive: false });
        img.addEventListener('dblclick', () => {
          lightboxScale = lightboxScale === 1 ? 2 : 1;
          img.style.transform = 'scale(' + lightboxScale + ')';
        });
        overlayRoot().appendChild(ov);
      }
      lightboxScale = 1;
      const img = document.getElementById('lightbox-img');
      img.src = src;
      img.style.transform = 'scale(1)';
      ov.classList.remove('hidden');
    },

    closeImage() {
      const ov = document.getElementById('image-lightbox');
      if (!ov || ov.classList.contains('hidden')) return false;
      ov.classList.add('hidden');
      return true;
    },
  });
})();
