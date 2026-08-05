/**
 * 阅读辅助套件：亮度、阅读标尺、逐段阅读、速读 RSVP、自动滚动。
 * 全部宿主层覆盖层 + 与 iframe 交互，不改 iframe 语义。
 */
(function () {
  'use strict';

  const frameEl = () => document.getElementById('chapter-frame');

  const Assist = {
    // ---- 亮度 ----
    setBrightness(v) {
      const el = document.getElementById('brightness-overlay');
      v = Math.max(0, Math.min(0.7, v || 0));
      el.classList.toggle('hidden', v < 0.02);
      el.style.opacity = v;
    },

    // ---- 阅读标尺 ----
    setRuler(active) {
      document.getElementById('ruler').classList.toggle('hidden', !active);
      if (active) this._bindRuler();
    },

    _bindRuler() {
      if (this._rulerBound) return;
      this._rulerBound = true;
      frameEl().contentDocument.addEventListener('mousemove', (e) => {
        const el = document.getElementById('ruler');
        if (el.classList.contains('hidden')) return;
        const fr = frameEl().getBoundingClientRect();
        el.style.top = fr.top + e.clientY - 15 + 'px';
      });
      // 翻页后标尺跟随 y 需重定位（覆盖层在 iframe 之上，滚动不影响）
    },

    // ---- 逐段阅读 ----
    setParagraphMode(active) {
      const el = document.getElementById('paragraph-mask');
      el.classList.toggle('hidden', !active);
      if (active) {
        el.innerHTML = '<div class="pm-hole"></div>';
        this._refreshParagraph();
      } else {
        el.innerHTML = '';
      }
    },

    _refreshParagraph() {
      const mask = document.getElementById('paragraph-mask');
      if (mask.classList.contains('hidden')) return;
      const doc = frameEl().contentDocument;
      if (!doc || !doc.body) return;
      // 取当前视口顶部区块作为「当前段」（简化：视口顶部的块级元素）
      const y = 20;
      const range = doc.caretRangeFromPoint(doc.body.clientWidth / 2, y);
      let el = range && range.startContainer;
      if (!el) return;
      if (el.nodeType === Node.TEXT_NODE) el = el.parentElement;
      // 上溯到块级元素
      while (el && el !== doc.body) {
        const display = getComputedStyle(el).display;
        if (display === 'block' || display === 'list-item') break;
        el = el.parentElement;
      }
      if (!el || el === doc.body) return;
      const rect = el.getBoundingClientRect();
      const fr = frameEl().getBoundingClientRect();
      const hole = mask.querySelector('.pm-hole');
      if (hole) {
        hole.style.left = (fr.left + rect.left - 8) + 'px';
        hole.style.top = (fr.top + rect.top - 6) + 'px';
        hole.style.width = rect.width + 16 + 'px';
        hole.style.height = rect.height + 12 + 'px';
      }
    },

    // ---- 速读 RSVP ----
    _rsvp: { timer: null, idx: 0, tokens: [] },

    setRsvp(active) {
      const box = document.getElementById('rsvp-box');
      if (!active) {
        box.classList.add('hidden');
        if (this._rsvp.timer) { clearInterval(this._rsvp.timer); this._rsvp.timer = null; }
        return;
      }
      this._startRsvp();
    },

    _startRsvp() {
      const ctx = App.state.textCtx;
      const box = document.getElementById('rsvp-box');
      if (!ctx) return;
      // 从当前 offset 起分词
      const from = Reader.currentOffset();
      const text = ctx.text.slice(from, from + 3000);
      // 按空白 + CJK 切词
      const tokens = text.split(/(\s+)/).filter((t) => t.trim().length);
      if (!tokens.length) return;
      this._rsvp.tokens = tokens;
      this._rsvp.idx = 0;
      box.innerHTML = '<span class="rsvp-word"></span><span class="rsvp-control">' +
        '<button class="vm-btn" id="rsvp-slow">−</button>' +
        '<button class="vm-btn" id="rsvp-pause">⏸</button>' +
        '<button class="vm-btn" id="rsvp-fast">＋</button>' +
        '<button class="vm-btn" id="rsvp-stop">✕</button></span>';
      box.classList.remove('hidden');
      const wordEl = box.querySelector('.rsvp-word');
      wordEl.textContent = tokens[0];
      const rate = App.state.settings.rsvp_rate || 300;
      let ms = 60000 / rate;
      let running = true;

      const advance = () => {
        this._rsvp.idx++;
        if (this._rsvp.idx >= this._rsvp.tokens.length) {
          // 结束：跳到下一段继续或停止
          this.setRsvp(false);
          return;
        }
        wordEl.textContent = this._rsvp.tokens[this._rsvp.idx];
      };
      this._rsvp.timer = setInterval(() => { if (running) advance(); }, ms);

      const setSpeed = (k) => {
        ms = Math.max(80, Math.min(2000, ms * k));
        clearInterval(this._rsvp.timer);
        this._rsvp.timer = setInterval(() => { if (running) advance(); }, ms);
      };
      box.querySelector('#rsvp-slow').onclick = () => setSpeed(1.4);
      box.querySelector('#rsvp-fast').onclick = () => setSpeed(1 / 1.4);
      box.querySelector('#rsvp-pause').onclick = (e) => {
        running = !running;
        e.target.textContent = running ? '⏸' : '▶';
      };
      box.querySelector('#rsvp-stop').onclick = () => this.setRsvp(false);
    },

    // ---- 自动滚动 ----
    _auto: { timer: null },

    setAutoScroll(active) {
      if (!active) {
        if (this._auto.timer) { clearInterval(this._auto.timer); this._auto.timer = null; }
        return;
      }
      if (this._auto.timer) return;
      const speed = App.state.settings.autoscroll_speed || 2;
      this._auto.timer = setInterval(() => {
        if (Paged.isActive()) {
          const m = Paged.measure();
          if (m.current >= m.total - 1) { Reader.nextChapter(); return; }
          Paged.nextPage(false);
        } else {
          const sc = document.getElementById('chapter-scroll');
          if (sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 4) {
            Reader.nextChapter();
            return;
          }
          sc.scrollTop += speed * 30;
        }
      }, 200);
    },
  };

  // 翻页/滚动后刷新逐段遮罩与标尺
  window.addEventListener('reader-updated', () => {
    Assist._refreshParagraph();
  });

  window.Assist = Assist;
})();
