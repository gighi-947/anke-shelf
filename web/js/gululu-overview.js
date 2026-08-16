/** 骨碌碌沉浸内容总览：按楼层 / 按类型双视图，标注位置并支持跳转。 */
(function () {
  'use strict';

  const state = {
    sourceId: 0,
    doc: null,
    open: false,
    view: 'floor',
    returnFocus: null,
    refreshTimer: null,
  };

  const el = (id) => document.getElementById(id);

  function urlName(url) {
    try {
      const name = decodeURIComponent(url.split('/').pop() || '');
      return name.replace(/\.[a-z0-9]+$/i, '') || '音乐';
    } catch (error) {
      return '音乐';
    }
  }

  /** 扫描当前章节：按楼层聚合沉浸内容。 */
  function scan() {
    const doc = state.doc;
    const empty = { floors: [], totals: { dice: 0, diceUnlocked: 0, folds: 0, secrets: 0 }, active: null };
    if (!doc) return empty;
    let unlocked = new Set();
    if (window.GululuAssistantReader && GululuAssistantReader.snapshot) {
      unlocked = new Set(GululuAssistantReader.snapshot().unlocked || []);
    }
    // 活跃中的沉浸内容（音乐播放/背景/视效）
    let active = null;
    if (window.GululuImmersive && GululuImmersive.snapshot) {
      const snap = GululuImmersive.snapshot();
      if (snap) {
        const items = [];
        if (snap.playing) {
          const title = snap.musicTitle || '音乐播放中';
          items.push(snap.musicFloor ? `${title} · ${snap.musicFloor}` : title);
        }
        if (snap.backgroundUrl) items.push('氛围背景');
        if (snap.effect) items.push(`视效：${snap.effect}`);
        if (items.length) {
          active = {
            items,
            musicPlaying: !!snap.playing,
            musicTitle: snap.musicTitle || '',
            musicFloor: snap.musicFloor || '',
            backgroundUrl: snap.backgroundUrl || '',
            effect: snap.effect || '',
          };
        }
      }
    }
    const floors = [];
    doc.querySelectorAll('.gululu-floor').forEach((floor) => {
      const floorId = Number((floor.id || '').replace(/^floor-/, ''));
      const numberNode = floor.querySelector('.floor-number');
      const label = (numberNode && numberNode.textContent.trim()) || `楼层 ${floorId}`;
      const music = [];
      const backgrounds = [];
      const vfx = [];
      const diceGroups = [];
      const seenMusicCues = new WeakSet();
      floor.querySelectorAll('[data-gululu-music-url], [data-gululu-music-auto="true"]').forEach((cue) => {
        if (seenMusicCues.has(cue)) return; // 同一 cue 同时带 url 与 auto 时只记一次
        seenMusicCues.add(cue);
        const titleNode = cue.querySelector('.gululu-music-title');
        const name = (titleNode && titleNode.textContent.trim()) || urlName(cue.dataset.gululuMusicUrl || '');
        if (cue.hasAttribute('data-gululu-music-url')) {
          const url = String(cue.dataset.gululuMusicUrl || '');
          if (url) music.push({ kind: 'manual', name, url });
        } else if (cue.hasAttribute('data-gululu-music-auto')) {
          music.push({ kind: 'auto', name });
        }
      });
      floor.querySelectorAll('[data-gululu-background-url]').forEach((cue) => {
        const url = String(cue.dataset.gululuBackgroundUrl || '');
        if (url) backgrounds.push({ url });
      });
      const v = floor.dataset.gululuVfx;
      if (v) vfx.push({ kind: v });
      floor.querySelectorAll('[data-gululu-dice-group]').forEach((node) => {
        const group = node.dataset.gululuDiceGroup;
        if (group && !diceGroups.includes(group)) diceGroups.push(group);
      });
      const folds = floor.querySelectorAll('details.gululu-fold').length;
      const secrets = floor.querySelectorAll('[data-gululu-secret-title]').length;
      floors.push({
        floorId, label, music, backgrounds, vfx,
        diceCount: diceGroups.length,
        diceUnlocked: diceGroups.filter((g) => unlocked.has(g)).length,
        folds, secrets,
      });
    });
    const totals = floors.reduce((acc, f) => {
      acc.dice += f.diceCount;
      acc.diceUnlocked += f.diceUnlocked;
      acc.folds += f.folds;
      acc.secrets += f.secrets;
      return acc;
    }, { dice: 0, diceUnlocked: 0, folds: 0, secrets: 0 });
    return { floors, totals, active };
  }

  function sectionNode(title) {
    const section = document.createElement('section');
    section.className = 'gululu-overview-section';
    const head = document.createElement('h4');
    head.textContent = title;
    section.appendChild(head);
    return section;
  }

  function rowNode(text, onClick, extra) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'gululu-overview-row';
    const span = document.createElement('span');
    span.textContent = text;
    row.appendChild(span);
    if (extra) {
      const badge = document.createElement('span');
      badge.className = 'gululu-overview-badge';
      badge.textContent = extra;
      row.appendChild(badge);
    }
    row.addEventListener('click', onClick);
    return row;
  }

  /** 跳转并高亮章节内标记元素（按楼层锚点 + 选择器）。 */
  function jumpTo(selector, floorId) {
    const doc = state.doc;
    if (!doc) return;
    const anchor = floorId === 0
      ? (doc.querySelector('.book-meta') || doc.querySelector('.chapter-title'))
      : doc.getElementById(`floor-${floorId}`);
    const target = anchor && anchor.querySelector(selector);
    if (!target) return;
    const ctx = window.App && App.state.textCtx;
    let offset = null;
    if (ctx && window.TextPos && TextPos.rangeToOffsets) {
      try {
        const range = doc.createRange();
        range.selectNodeContents(target);
        const offsets = TextPos.rangeToOffsets(ctx, range);
        offset = offsets ? offsets[0] : null;
      } catch (error) { /* optional */ }
    }
    if (offset !== null && window.Reader && Reader.seekToOffset) {
      Reader.seekToOffset(offset);
    } else if (target.scrollIntoView) {
      target.scrollIntoView({ block: 'center' });
    }
    target.classList.add('gululu-overview-target');
    setTimeout(() => target.classList.remove('gululu-overview-target'), 2000);
  }

  /** 楼层内的沉浸条目（音乐/背景/视效），供两种视图复用。 */
  function floorContentNodes(floor, section) {
    floor.music.forEach((m) => section.appendChild(rowNode(
      m.name,
      () => jumpTo(m.kind === 'auto'
        ? '[data-gululu-music-auto="true"]'
        : `[data-gululu-music-url="${CSS.escape(m.url)}"]`, floor.floorId),
      m.kind === 'auto' ? '自动' : ''
    )));
    if (floor.backgrounds.length) {
      const grid = document.createElement('div');
      grid.className = 'gululu-overview-bg-grid';
      floor.backgrounds.forEach((b) => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'gululu-overview-bg';
        item.title = '氛围背景预览';
        const img = document.createElement('img');
        img.src = b.url;
        img.alt = '背景预览';
        img.loading = 'lazy';
        item.appendChild(img);
        item.addEventListener('click', () => jumpTo(
          `[data-gululu-background-url="${CSS.escape(b.url)}"]`, floor.floorId
        ));
        grid.appendChild(item);
      });
      section.appendChild(grid);
    }
    floor.vfx.forEach((v) => section.appendChild(rowNode(
      v.kind,
      () => jumpTo(`[data-gululu-vfx="${CSS.escape(v.kind)}"]`, floor.floorId)
    )));
    if (floor.diceCount) {
      section.appendChild(rowNode(
        `骰点 ${floor.diceCount} 组${floor.diceUnlocked ? ` · 已解锁 ${floor.diceUnlocked}` : ''}`,
        () => jumpTo('[data-gululu-dice-group]', floor.floorId),
        floor.diceUnlocked === floor.diceCount ? '全部解锁' : ''
      ));
    }
    if (floor.secrets) {
      const summary = document.createElement('p');
      summary.className = 'gululu-overview-caption';
      summary.textContent = `秘密 ${floor.secrets}`;
      section.appendChild(summary);
    }
  }

  function renderFloorView(list, data) {
    const shown = data.floors.filter((f) => (
      f.music.length || f.backgrounds.length || f.vfx.length ||
      f.diceCount || f.folds || f.secrets
    ));
    if (!shown.length) {
      const empty = document.createElement('p');
      empty.className = 'gululu-overview-empty';
      empty.textContent = '本章暂无沉浸内容';
      list.appendChild(empty);
      return;
    }
    shown.forEach((floor) => {
      const section = sectionNode(`${floor.label}`);
      floorContentNodes(floor, section);
      list.appendChild(section);
    });
  }

  function renderTypeView(list, data) {
    const t = data.totals;
    // 阅读解锁（骰点聚合：进度条 + 全部解锁）
    if (t.dice) {
      const section = sectionNode(`阅读解锁（骰点 ${t.dice} 组 · 已解锁 ${t.diceUnlocked}）`);
      const bar = document.createElement('div');
      bar.className = 'gululu-overview-progress';
      const fill = document.createElement('div');
      fill.className = 'gululu-overview-progress-fill';
      fill.style.width = `${Math.round(t.diceUnlocked / t.dice * 100)}%`;
      bar.appendChild(fill);
      section.appendChild(bar);
      if (t.diceUnlocked < t.dice && window.GululuAssistantReader && GululuAssistantReader.revealAll) {
        const unlockBtn = document.createElement('button');
        unlockBtn.type = 'button';
        unlockBtn.className = 'gululu-overview-unlock';
        unlockBtn.textContent = `一次性解锁本章全部骰点（${t.dice - t.diceUnlocked}）`;
        unlockBtn.addEventListener('click', () => {
          if (GululuAssistantReader.revealAll()) render();
        });
        section.appendChild(unlockBtn);
      }
      list.appendChild(section);
    }
    // 音乐（按楼层标注；播放中的条目高亮）
    const music = data.floors.flatMap((f) => f.music.map((m) => ({ ...m, label: f.label, floorId: f.floorId })));
    if (music.length) {
      const playing = !!data.active && data.active.musicPlaying;
      const section = sectionNode(`音乐（${music.length}）${playing ? ' · 播放中' : ''}`);
      if (playing) section.classList.add('gululu-overview-active-section');
      music.forEach((m) => {
        const row = rowNode(
          `${m.label} · ${m.name}`,
          () => jumpTo(m.kind === 'auto'
            ? '[data-gululu-music-auto="true"]'
            : `[data-gululu-music-url="${CSS.escape(m.url)}"]`, m.floorId),
          m.kind === 'auto' ? '自动' : ''
        );
        if (playing) row.classList.add('gululu-overview-active');
        section.appendChild(row);
      });
      list.appendChild(section);
    }
    // 氛围背景（缩略图 + 楼层标注；当前背景高亮）
    const backgrounds = data.floors.flatMap((f) => f.backgrounds.map((b) => ({ ...b, label: f.label, floorId: f.floorId })));
    if (backgrounds.length) {
      const activeBg = data.active && data.active.backgroundUrl;
      const section = sectionNode(`氛围背景（${backgrounds.length}）${activeBg ? ' · 显示中' : ''}`);
      const grid = document.createElement('div');
      grid.className = 'gululu-overview-bg-grid';
      backgrounds.forEach((b) => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'gululu-overview-bg' + (activeBg && b.url === activeBg ? ' gululu-overview-active' : '');
        item.title = `${b.label} · 背景预览`;
        const img = document.createElement('img');
        img.src = b.url;
        img.alt = `${b.label} 背景`;
        img.loading = 'lazy';
        item.appendChild(img);
        item.addEventListener('click', () => jumpTo(
          `[data-gululu-background-url="${CSS.escape(b.url)}"]`, b.floorId
        ));
        grid.appendChild(item);
      });
      section.appendChild(grid);
      list.appendChild(section);
    }
    // 动态视效
    const vfx = data.floors.flatMap((f) => f.vfx.map((v) => ({ ...v, label: f.label, floorId: f.floorId })));
    if (vfx.length) {
      const activeVfx = data.active && data.active.effect;
      const section = sectionNode(`动态视效（${vfx.length}）`);
      vfx.forEach((v) => {
        const row = rowNode(
          `${v.label} · ${v.kind}`,
          () => jumpTo(`[data-gululu-vfx="${CSS.escape(v.kind)}"]`, v.floorId)
        );
        if (activeVfx && v.kind === activeVfx) row.classList.add('gululu-overview-active');
        section.appendChild(row);
      });
      list.appendChild(section);
    }
    // 内容结构（只保留秘密；折叠不属于沉浸内容，不展示）
    if (t.secrets) {
      const section = sectionNode('内容结构');
      const summary = document.createElement('p');
      summary.className = 'gululu-overview-caption';
      summary.textContent = `秘密 ${t.secrets} 处`;
      section.appendChild(summary);
      list.appendChild(section);
    }
    if (!t.dice && !music.length && !backgrounds.length && !vfx.length && !t.secrets) {
      const empty = document.createElement('p');
      empty.className = 'gululu-overview-empty';
      empty.textContent = '本章暂无沉浸内容';
      list.appendChild(empty);
    }
  }

  function render() {
    const list = el('gululu-overview-list');
    if (!list) return;
    list.innerHTML = '';
    const data = scan();
    // 活跃中的沉浸内容摘要
    if (data.active) {
      const section = sectionNode('活跃中');
      section.classList.add('gululu-overview-active-section');
      const summary = document.createElement('p');
      summary.className = 'gululu-overview-caption';
      summary.textContent = data.active.items.join(' · ');
      section.appendChild(summary);
      if (data.active.backgroundUrl) {
        const thumb = document.createElement('img');
        thumb.className = 'gululu-overview-bg-thumb';
        thumb.src = data.active.backgroundUrl;
        thumb.alt = '当前氛围背景预览';
        section.appendChild(thumb);
      }
      list.appendChild(section);
    }
    if (state.view === 'type') renderTypeView(list, data);
    else renderFloorView(list, data);
    const tabs = document.querySelectorAll('.gululu-overview-tab');
    tabs.forEach((tab) => tab.classList.toggle('active', tab.dataset.view === state.view));
  }

  function togglePanel(force) {
    if (!state.sourceId) return;
    const panel = el('gululu-overview-panel');
    const btn = el('gululu-quick-overview');
    state.open = typeof force === 'boolean' ? force : !state.open;
    panel.classList.toggle('hidden', !state.open);
    if (btn) {
      btn.classList.toggle('active', state.open);
      btn.setAttribute('aria-expanded', String(state.open));
    }
    if (state.open) {
      if (window.App && App.setGululuQuickMenu) App.setGululuQuickMenu(false, false);
      if (window.ViewMenu) ViewMenu.close(false);
      if (window.GululuComments) GululuComments.closePanel();
      if (window.GululuImmersive) GululuImmersive.closePanel();
      render();
      // 播放/背景/视效变化时实时刷新活跃区
      stopRefresh();
      state.refreshTimer = setInterval(render, 1000);
    } else {
      stopRefresh();
      if (state.returnFocus) {
        state.returnFocus.focus();
        state.returnFocus = null;
      }
    }
  }

  function stopRefresh() {
    if (state.refreshTimer) {
      clearInterval(state.refreshTimer);
      state.refreshTimer = null;
    }
  }

  function onChapterLoaded(doc) {
    state.doc = doc || null;
    if (state.open) render();
  }

  function setBook(book) {
    state.sourceId = Number(book && book.gululu_source_id) || 0;
    state.doc = null;
    togglePanel(false);
  }

  function close() {
    state.sourceId = 0;
    state.doc = null;
    stopRefresh();
    togglePanel(false);
  }

  document.addEventListener('DOMContentLoaded', () => {
    // 快捷轨按钮由 app.js 统一绑定（与 comments/immersive 同模式），此处不重复绑定
    const closeBtn = el('gululu-overview-close');
    if (closeBtn) closeBtn.addEventListener('click', () => togglePanel(false));
    document.querySelectorAll('.gululu-overview-tab').forEach((tab) => {
      tab.addEventListener('click', () => {
        state.view = tab.dataset.view === 'type' ? 'type' : 'floor';
        if (state.open) render();
      });
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && state.open) togglePanel(false);
    });
  });

  window.GululuOverview = {
    setBook,
    onChapterLoaded,
    togglePanel: (trigger) => {
      state.returnFocus = trigger || el('gululu-quick-overview');
      togglePanel();
    },
    closePanel: () => togglePanel(false),
    close,
    snapshot: () => ({ sourceId: state.sourceId, open: state.open, view: state.view }),
  };
})();
