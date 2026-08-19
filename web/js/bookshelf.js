/**
 * Bookshelf view: cover grid / list rows, recent-read strip, sort and local search.
 */
(function () {
  'use strict';

  const RECENT_LIMIT = 8;

  function currentView() {
    return (App.state.settings && App.state.settings.shelf_view) || 'grid';
  }

  function currentSort() {
    return (App.state.settings && App.state.settings.shelf_sort) || 'recent';
  }

  /** 剥离书名首个【…】前缀（设置开启时；仅显示层，不改存储/搜索/导出）。 */
  function displayTitle(title) {
    const raw = title || '(No Title)';
    if (!(App.state.settings && App.state.settings.hide_title_brackets)) return raw;
    return raw.replace(/^(?:【[^】]*】|\[[^\]]*\])[\s　]*(?:(?:【[^】]*】|\[[^\]]*\])[\s　]*)*/, '').trim() || raw;
  }

  function gululuBadge(book) {
    if (!Number(book && book.gululu_source_id)) return null;
    const badge = document.createElement('span');
    badge.className = 'gululu-badge';
    badge.textContent = '骨碌碌';
    return badge;
  }

  function closeBookMenus() {
    document.querySelectorAll('.book-menu').forEach((m) => m.remove());
  }

  window.Shelf = {
    _renderToken: 0,

    async render() {
      const token = ++this._renderToken;
      let books = [];
      try {
        books = await Api.getShelf();
      } catch (e) {
        Toast.show('Failed to load shelf: ' + (e.message || e), true);
      }
      const grid = document.getElementById('book-grid');
      grid.innerHTML = '';
      const hasBooks = books.length > 0;
      document.getElementById('shelf-empty').classList.toggle('hidden', hasBooks);
      document.getElementById('shelf-no-match').classList.add('hidden');

      const sorted = this._sort(books);
      this.renderRecent(books);
      this.syncControls();

      grid.classList.toggle('list-view', currentView() === 'list');
      const items = sorted.map((b) => this._makeItem(b));
      // Render in chunks so large shelves never block the UI thread.
      const CHUNK = 48;
      let i = 0;
      const flush = () => {
        if (token !== this._renderToken) return;
        const end = Math.min(items.length, i + CHUNK);
        for (; i < end; i++) grid.appendChild(items[i]);
        if (hasBooks && i >= items.length) {
          grid.appendChild(currentView() === 'list' ? this._importRow() : this._importTile());
        }
        this.applyFilter();
        if (i < items.length && token === this._renderToken) requestAnimationFrame(flush);
      };
      flush();
    },

    _sort(books) {
      const mode = currentSort();
      const arr = books.slice();
      const cmpTitle = (a, b) =>
        (a.title || '').localeCompare(b.title || '', 'zh-Hans-CN', { sensitivity: 'base' });
      if (mode === 'title') {
        arr.sort((a, b) => cmpTitle(a, b) || (a.author || '').localeCompare(b.author || ''));
      } else if (mode === 'author') {
        arr.sort(
          (a, b) =>
            (a.author || '').localeCompare(b.author || '', 'zh-Hans-CN', { sensitivity: 'base' }) ||
            cmpTitle(a, b)
        );
      } else if (mode === 'added') {
        arr.sort((a, b) => (b.added_at || '').localeCompare(a.added_at || ''));
      } else {
        arr.sort((a, b) => (b.last_read_at || '').localeCompare(a.last_read_at || ''));
      }
      return arr;
    },

    /** Readest 风格“最近阅读”横条：最近读过的书（≤8 本）横向滚动。 */
    renderRecent(books) {
      const strip = document.getElementById('recent-strip');
      if (!strip) return;
      strip.innerHTML = '';
      const recent = books
        .filter((b) => b.last_read_at)
        .sort((a, b) => (b.last_read_at || '').localeCompare(a.last_read_at || ''))
        .slice(0, RECENT_LIMIT);
      strip.classList.toggle('hidden', recent.length === 0);
      for (const b of recent) strip.appendChild(this._recentCard(b));
    },

    _recentCard(book) {
      const card = document.createElement('button');
      card.className = 'recent-card';
      card.title = book.title || '';

      const cover = document.createElement('span');
      cover.className = 'recent-cover';
      const fb = document.createElement('span');
      fb.className = 'recent-cover-fallback';
      if (book.nga_tid && !book.cover_rel) {
        fb.classList.add('cover-dice');
        fb.appendChild(Icons.icon('dice', 20));
      } else {
        fb.textContent = displayTitle(book.title).slice(0, 1);
      }
      cover.appendChild(fb);
      const img = new Image();
      img.alt = displayTitle(book.title);
      img.loading = 'lazy';
      img.addEventListener('error', () => img.remove(), { once: true });
      if (book.cover_rel && book.cover_url) img.src = book.cover_url;
      cover.appendChild(img);
      if (book.nga_tid) {
        const badge = document.createElement('span');
        badge.className = 'nga-badge';
        badge.textContent = 'NGA';
        cover.appendChild(badge);
      }
      const badge = gululuBadge(book);
      if (badge) cover.appendChild(badge);

      const title = document.createElement('span');
      title.className = 'recent-title';
      title.textContent = displayTitle(book.title);

      const meta = document.createElement('span');
      meta.className = 'recent-meta';
      const parts = [];
      if (book.nga_tid) parts.push('NGA');
      else if (book.author) parts.push(book.author);
      if (book.progress_pct > 0) parts.push(Math.round(book.progress_pct * 100) + '%');
      meta.textContent = parts.join(' · ');

      card.append(cover, title, meta);
      card.addEventListener('click', () => App.showReader(book.id));
      return card;
    },

    _makeItem(book) {
      return currentView() === 'list' ? this._row(book) : this._card(book);
    },

    _card(book) {
      const card = document.createElement('div');
      card.className = 'book-card';
      card.dataset.title = (book.title || '').toLowerCase();
      card.dataset.author = ((book.author || '') + ' tid ' + (book.nga_tid || '')).toLowerCase();

      const main = document.createElement('div');
      main.className = 'bookitem-main';

      const cover = document.createElement('div');
      cover.className = 'book-cover';
      if (book.nga_tid && !book.cover_rel) {
        const dice = document.createElement('span');
        dice.className = 'cover-dice';
        dice.appendChild(Icons.icon('dice', 40));
        cover.appendChild(dice);
      } else {
        const ft = document.createElement('div');
        ft.className = 'cover-fallback-title';
        ft.textContent = displayTitle(book.title);
        const fa = document.createElement('div');
        fa.className = 'cover-fallback-author';
        fa.textContent = book.nga_tid
          ? (book.author || '') + ' · tid ' + book.nga_tid
          : (book.author || 'Unknown');
        cover.append(ft, fa);
      }

      const img = new Image();
      img.alt = displayTitle(book.title);
      img.loading = 'lazy';
      img.addEventListener('error', () => img.remove(), { once: true });
      if (book.cover_rel && book.cover_url) img.src = book.cover_url;
      cover.appendChild(img);

      if (book.nga_tid) {
        const badge = document.createElement('span');
        badge.className = 'nga-badge';
        badge.textContent = 'NGA';
        main.appendChild(badge);
      }
      const sourceBadge = gululuBadge(book);
      if (sourceBadge) main.appendChild(sourceBadge);
      main.appendChild(cover);

      const actions = this._gridActions(book);

      const meta = document.createElement('div');
      meta.className = 'book-meta';
      const title = document.createElement('div');
      title.className = 'book-title';
      title.textContent = displayTitle(book.title);
      const author = document.createElement('div');
      author.className = 'book-author';
      author.textContent = book.nga_tid
        ? (book.author ? book.author + ' · tid ' + book.nga_tid : 'tid ' + book.nga_tid)
        : (book.author || 'Unknown');

      const pct = book.progress_pct || 0;
      const pctLabel = document.createElement('div');
      pctLabel.className = 'book-progress-pct';
      pctLabel.textContent = pct > 0 ? Math.round(pct * 100) + '%' : '';
      const track = document.createElement('div');
      track.className = 'book-progress-track';
      const fill = document.createElement('div');
      fill.className = 'book-progress-fill';
      fill.style.width = (pct * 100).toFixed(1) + '%';
      track.appendChild(fill);
      const progRow = document.createElement('div');
      progRow.className = 'book-progress-row';
      progRow.appendChild(pctLabel);
      if (pct > 0) progRow.appendChild(track);
      meta.append(title, author, progRow);

      card.append(main, actions, meta);
      card.addEventListener('click', () => App.showReader(book.id));
      return card;
    },

    _row(book) {
      const row = document.createElement('div');
      row.className = 'book-row';
      row.dataset.title = (book.title || '').toLowerCase();
      row.dataset.author = ((book.author || '') + ' tid ' + (book.nga_tid || '')).toLowerCase();

      const cover = document.createElement('div');
      cover.className = 'book-row-cover';
      const fb = document.createElement('div');
      fb.className = 'book-row-cover-fallback';
      if (book.nga_tid && !book.cover_rel) {
        fb.classList.add('cover-dice');
        fb.appendChild(Icons.icon('dice', 24));
      } else {
        fb.textContent = displayTitle(book.title).slice(0, 2);
      }
      const img = new Image();
      img.alt = displayTitle(book.title);
      img.loading = 'lazy';
      img.addEventListener('error', () => img.remove(), { once: true });
      if (book.cover_rel && book.cover_url) img.src = book.cover_url;
      cover.append(fb, img);

      const meta = document.createElement('div');
      meta.className = 'book-row-meta';
      const title = document.createElement('div');
      title.className = 'book-row-title';
      if (book.nga_tid) {
        const badge = document.createElement('span');
        badge.className = 'nga-badge';
        badge.textContent = 'NGA';
        title.appendChild(badge);
      }
      const sourceBadge = gululuBadge(book);
      if (sourceBadge) title.appendChild(sourceBadge);
      const titleText = document.createElement('span');
      titleText.textContent = displayTitle(book.title);
      title.appendChild(titleText);
      const author = document.createElement('div');
      author.className = 'book-row-author';
      author.textContent = book.nga_tid
        ? (book.author ? book.author + ' · tid ' + book.nga_tid : 'tid ' + book.nga_tid)
        : (book.author || 'Unknown');
      const pct = book.progress_pct || 0;
      const pctLabel = document.createElement('span');
      pctLabel.className = 'book-progress-pct';
      pctLabel.textContent = pct > 0 ? Math.round(pct * 100) + '%' : '';
      const track = document.createElement('span');
      track.className = 'book-progress-track';
      const fill = document.createElement('span');
      fill.className = 'book-progress-fill';
      fill.style.width = (pct * 100).toFixed(1) + '%';
      track.appendChild(fill);
      const prog = document.createElement('div');
      prog.className = 'book-row-progress';
      prog.append(pctLabel, track);
      meta.append(title, author, prog);

      row.append(cover, meta, this._actions(book));
      row.addEventListener('click', () => App.showReader(book.id));
      return row;
    },

    _actions(book) {
      const actions = document.createElement('div');
      actions.className = 'book-actions';
      if (book.nga_tid) {
        const upd = document.createElement('button');
        upd.className = 'export-btn update-btn';
        upd.title = '更新帖子（可调整只看楼主/主题等设置）';
        upd.appendChild(Icons.icon('refresh', 14));
        upd.addEventListener('click', (ev) => {
          ev.stopPropagation();
          if (window.NgaDownload) NgaDownload.open({ bookId: book.id, focusUpdate: true });
        });
        actions.appendChild(upd);
        const exp = document.createElement('button');
        exp.className = 'export-btn';
        exp.title = '导出帖子（EPUB / Markdown）';
        exp.appendChild(Icons.icon('download', 14));
        exp.addEventListener('click', async (ev) => {
          ev.stopPropagation();
          if (window.NgaDownload) NgaDownload.open({ bookId: book.id });
        });
        actions.appendChild(exp);
      } else if (Number(book.gululu_source_id) > 0) {
        const upd = document.createElement('button');
        upd.className = 'export-btn update-btn';
        upd.title = '检查骨碌碌更新';
        upd.appendChild(Icons.icon('refresh', 14));
        upd.addEventListener('click', async (ev) => {
          ev.stopPropagation();
          try {
            await Api.gululuStartUpdate(String(book.gululu_source_id), 'online');
            if (window.NgaDownload) NgaDownload.open({ tab: 'dl-gululu' });
          } catch (e) {
            Toast.show('更新失败：' + (e.message || e), true);
          }
        });
        actions.appendChild(upd);
        const exp = document.createElement('button');
        exp.className = 'export-btn';
        exp.title = '导出帖子（含评论 EPUB）';
        exp.appendChild(Icons.icon('download', 14));
        exp.addEventListener('click', async (ev) => {
          ev.stopPropagation();
          try {
            await Api.gululuStartExport(String(book.gululu_source_id), 'online');
            if (window.NgaDownload) NgaDownload.open({ tab: 'dl-gululu' });
          } catch (e) {
            Toast.show('导出失败：' + (e.message || e), true);
          }
        });
        actions.appendChild(exp);
      }
      const rn = document.createElement('button');
      rn.className = 'rename-btn';
      rn.title = 'Rename';
      rn.appendChild(Icons.icon('edit', 14));
      rn.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        const name = prompt('Rename book:', book.title || '');
        if (name === null) return;
        const trimmed = name.trim();
        if (!trimmed || trimmed === book.title) return;
        try {
          await Api.renameBook(book.id, trimmed);
          Toast.show('Renamed');
          this.render();
        } catch (e) {
          Toast.show('Rename failed: ' + (e.message || e), true);
        }
      });
      actions.appendChild(rn);
      const coverBtn = document.createElement('button');
      coverBtn.className = 'rename-btn';
      coverBtn.title = '设置封面';
      coverBtn.appendChild(Icons.icon('image', 14));
      coverBtn.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        try {
          const r = await Api.setCover(book.id);
          if (r && r.cancelled) return;
          Toast.show('封面已更新');
          this.render();
        } catch (e) {
          Toast.show('设置封面失败：' + (e.message || e), true);
        }
      });
      actions.appendChild(coverBtn);
      const resetCoverBtn = document.createElement('button');
      resetCoverBtn.className = 'rename-btn';
      resetCoverBtn.title = '恢复默认封面';
      resetCoverBtn.appendChild(Icons.icon('undo', 14));
      resetCoverBtn.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        try {
          await Api.resetCover(book.id);
          Toast.show('已恢复默认封面');
          this.render();
        } catch (e) {
          Toast.show('恢复封面失败：' + (e.message || e), true);
        }
      });
      actions.appendChild(resetCoverBtn);
      const del = document.createElement('button');
      del.className = 'delete-btn';
      del.title = 'Remove from shelf';
      del.appendChild(Icons.icon('trash', 14));
      del.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        if (!confirm('Remove "' + (book.title || '') + '" from the shelf?\n(The original file is kept.)')) return;
        try {
          await Api.removeBook(book.id);
          Toast.show('Removed');
          this.render();
        } catch (e) {
          Toast.show('Remove failed: ' + (e.message || e), true);
        }
      });
      actions.appendChild(del);
      return actions;
    },

    _gridActions(book) {
      const actions = document.createElement('div');
      actions.className = 'book-actions';
      if (book.nga_tid) {
        const upd = document.createElement('button');
        upd.className = 'export-btn update-btn';
        upd.title = '更新帖子（可调整只看楼主/主题等设置）';
        upd.appendChild(Icons.icon('refresh', 14));
        upd.addEventListener('click', (ev) => {
          ev.stopPropagation();
          if (window.NgaDownload) NgaDownload.open({ bookId: book.id, focusUpdate: true });
        });
        actions.appendChild(upd);
      } else if (Number(book.gululu_source_id) > 0) {
        const upd = document.createElement('button');
        upd.className = 'export-btn update-btn';
        upd.title = '检查骨碌碌更新';
        upd.appendChild(Icons.icon('refresh', 14));
        upd.addEventListener('click', async (ev) => {
          ev.stopPropagation();
          try {
            await Api.gululuStartUpdate(String(book.gululu_source_id), 'online');
            if (window.NgaDownload) NgaDownload.open({ tab: 'dl-gululu' });
          } catch (e) {
            Toast.show('更新失败：' + (e.message || e), true);
          }
        });
        actions.appendChild(upd);
      }
      const more = document.createElement('button');
      more.className = 'rename-btn';
      more.title = '更多管理';
      more.setAttribute('aria-label', '更多管理');
      more.appendChild(Icons.icon('dots', 14));

      let menu = null;
      const buildMenu = () => {
        const m = document.createElement('div');
        m.className = 'book-menu hidden';
        const addItem = (label, fn) => {
          const b = document.createElement('button');
          b.className = 'book-menu-item';
          b.textContent = label;
          b.addEventListener('click', async (ev) => {
            ev.stopPropagation();
            closeBookMenus();
            await fn();
          });
          m.appendChild(b);
        };
        if (book.nga_tid) {
          addItem('导出帖子（EPUB / Markdown）', async () => {
            if (window.NgaDownload) NgaDownload.open({ bookId: book.id });
          });
        } else if (Number(book.gululu_source_id) > 0) {
          addItem('导出帖子（含评论 EPUB）', async () => {
            try {
              await Api.gululuStartExport(String(book.gululu_source_id), 'online');
              if (window.NgaDownload) NgaDownload.open({ tab: 'dl-gululu' });
            } catch (e) {
              Toast.show('导出失败：' + (e.message || e), true);
            }
          });
        }
        addItem('重命名', async () => {
          const name = prompt('Rename book:', book.title || '');
          if (name === null) return;
          const trimmed = name.trim();
          if (!trimmed || trimmed === book.title) return;
          try {
            await Api.renameBook(book.id, trimmed);
            Toast.show('Renamed');
            this.render();
          } catch (e) {
            Toast.show('Rename failed: ' + (e.message || e), true);
          }
        });
        addItem('设置封面', async () => {
          try {
            const r = await Api.setCover(book.id);
            if (r && r.cancelled) return;
            Toast.show('封面已更新');
            this.render();
          } catch (e) {
            Toast.show('设置封面失败：' + (e.message || e), true);
          }
        });
        addItem('恢复默认封面', async () => {
          try {
            await Api.resetCover(book.id);
            Toast.show('已恢复默认封面');
            this.render();
          } catch (e) {
            Toast.show('恢复封面失败：' + (e.message || e), true);
          }
        });
        addItem('从书架移除', async () => {
          if (!confirm('Remove "' + (book.title || '') + '" from the shelf?\n(The original file is kept.)')) return;
          try {
            await Api.removeBook(book.id);
            Toast.show('Removed');
            this.render();
          } catch (e) {
            Toast.show('Remove failed: ' + (e.message || e), true);
          }
        });
        return m;
      };

      more.addEventListener('click', (ev) => {
        ev.stopPropagation();
        const wasOpen = menu && menu.isConnected;
        closeBookMenus();
        if (wasOpen) return;
        if (!menu) menu = buildMenu();
        document.body.appendChild(menu);
        menu.classList.remove('hidden');
        menu.style.position = 'fixed';
        menu.style.right = 'auto';
        menu.style.left = '0px';
        menu.style.top = '0px';
        menu.style.visibility = 'hidden';
        const rect = more.getBoundingClientRect();
        const m = menu.getBoundingClientRect();
        menu.style.left = Math.max(8, Math.min(window.innerWidth - m.width - 8, rect.right - m.width)) + 'px';
        menu.style.top = Math.max(8, Math.min(window.innerHeight - m.height - 8, rect.bottom + 4)) + 'px';
        menu.style.visibility = 'visible';
      });
      actions.appendChild(more);
      return actions;
    },

    _importTile() {
      const tile = document.createElement('button');
      tile.className = 'book-import-tile';
      tile.title = 'Import Books';
      tile.setAttribute('aria-label', 'Import Books');
      tile.appendChild(Icons.icon('plus', 36));
      tile.addEventListener('click', importBooks);
      return tile;
    },

    _importRow() {
      const tile = document.createElement('button');
      tile.className = 'book-import-row';
      tile.title = 'Import Books';
      tile.appendChild(Icons.icon('plus', 18));
      const label = document.createElement('span');
      label.textContent = '导入书籍';
      tile.appendChild(label);
      tile.addEventListener('click', importBooks);
      return tile;
    },

    syncControls() {
      const sort = document.getElementById('shelf-sort');
      if (sort) sort.value = currentSort();
      const gridBtn = document.getElementById('shelf-view-grid');
      const listBtn = document.getElementById('shelf-view-list');
      if (gridBtn) gridBtn.classList.toggle('active', currentView() !== 'list');
      if (listBtn) listBtn.classList.toggle('active', currentView() === 'list');
    },

    applyFilter() {
      const input = document.getElementById('shelf-search');
      const q = (input.value || '').trim().toLowerCase();
      const cards = document.querySelectorAll('#book-grid .book-card, #book-grid .book-row');
      let visible = 0;
      cards.forEach((c) => {
        const hit = !q ||
          (c.dataset.title || '').includes(q) ||
          (c.dataset.author || '').includes(q);
        c.classList.toggle('hidden', !hit);
        if (hit) visible++;
      });
      const noMatch = document.getElementById('shelf-no-match');
      if (noMatch) noMatch.classList.toggle('hidden', visible > 0 || cards.length === 0);
      const tile = document.querySelector('#book-grid .book-import-tile, #book-grid .book-import-row');
      if (tile) tile.classList.toggle('hidden', visible === 0 && cards.length > 0);
    },
  };

  async function importBooks() {
    try {
      const results = await Api.importBooks();
      let ok = 0;
      for (const r of results) {
        if (r.ok) ok++;
        else Toast.show('Import failed: ' + r.file + ' ' + r.error, true);
      }
      if (ok > 0) Toast.show('Imported ' + ok + ' book(s)');
      Shelf.render();
    } catch (e) {
      Toast.show('Import error: ' + (e.message || e), true);
    }
  }

  document.addEventListener('DOMContentLoaded', () => {
    document.addEventListener('click', (ev) => {
      if (!ev.target.closest('.book-menu') && !ev.target.closest('.book-actions')) closeBookMenus();
    });
    document.getElementById('import-btn').addEventListener('click', importBooks);
    const emptyImport = document.getElementById('empty-import-btn');
    if (emptyImport) emptyImport.addEventListener('click', importBooks);
    const emptyNga = document.getElementById('empty-nga-btn');
    if (emptyNga) emptyNga.addEventListener('click', () => NgaDownload.open());
    const search = document.getElementById('shelf-search');
    if (search) search.addEventListener('input', () => Shelf.applyFilter());

    const sort = document.getElementById('shelf-sort');
    if (sort) {
      sort.addEventListener('change', () => {
        App.state.settings.shelf_sort = sort.value;
        Api.saveSettings( { shelf_sort: sort.value });
        Shelf.render();
      });
    }
    const gridBtn = document.getElementById('shelf-view-grid');
    const listBtn = document.getElementById('shelf-view-list');
    const setView = (v) => {
      App.state.settings.shelf_view = v;
      Api.saveSettings( { shelf_view: v });
      Shelf.render();
    };
    if (gridBtn) gridBtn.addEventListener('click', () => setView('grid'));
    if (listBtn) listBtn.addEventListener('click', () => setView('list'));

    const bracketsBtn = document.getElementById('shelf-hide-brackets');
    if (bracketsBtn) {
      const active = App.state.settings && App.state.settings.hide_title_brackets;
      bracketsBtn.classList.toggle('active', !!active);
      bracketsBtn.setAttribute('aria-pressed', String(!!active));
      bracketsBtn.addEventListener('click', () => {
        const next = !(App.state.settings && App.state.settings.hide_title_brackets);
        App.state.settings.hide_title_brackets = next;
        Api.saveSettings({ hide_title_brackets: next });
        bracketsBtn.classList.toggle('active', next);
        bracketsBtn.setAttribute('aria-pressed', String(next));
        Shelf.render();
      });
    }
  });
})();
