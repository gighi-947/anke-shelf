  /* ---------- gululu host layer (批 8/9) ---------- */
  // 运行时只切换遮罩/可见状态与上报事件，绝不重写正文（text_offset 红线）。
  var gululu = {
    unlocked: {},
    autoFired: {},
    lastVfx: '',
    lastBackground: '',
    lastFloor: 0,
  };

  function gululuUnlockedIds(payload) {
    var list = parseJsonSafe(payload) || [];
    var map = {};
    for (var i = 0; i < list.length; i++) map[String(list[i])] = true;
    return map;
  }

  /** 未解锁的骰点值/后缀加 masked，未解锁的迷雾块隐藏。 */
  function applyGululuMasks() {
    var values = document.querySelectorAll('.gululu-dice-value, .gululu-dice-suffix');
    for (var i = 0; i < values.length; i++) {
      var el = values[i];
      var group = el.getAttribute('data-gululu-dice-group') || '';
      if (gululu.unlocked[group]) {
        el.classList.remove('masked');
        el.style.color = '';
        el.style.background = '';
        el.style.visibility = '';
      } else {
        el.classList.add('masked');
        // 内联兜底：部分 WebView 对 EPUB 内联 CSS 的 currentColor+transparent
        // 组合不生效，直接写内联样式保证数值不可见。
        el.style.color = 'transparent';
        el.style.background = 'currentColor';
        el.style.visibility = 'hidden';
      }
      // 直接绑定兜底：部分机型上 document 级委托会被阅读器点击处理挡住。
      el.onclick = function (ev) {
        if (ev) {
          if (ev.preventDefault) ev.preventDefault();
          if (ev.stopPropagation) ev.stopPropagation();
        }
        var g = this.getAttribute('data-gululu-dice-group') || '';
        if (!g) return false;
        if (ev && ev.altKey) revealGululuFloor(g); else revealGululuGroup(g, true);
        return false;
      };
    }
    var fogs = document.querySelectorAll('.gululu-fog-block');
    for (i = 0; i < fogs.length; i++) {
      var fog = fogs[i];
      var lock = fog.getAttribute('data-gululu-fog-lock') || '';
      if (gululu.unlocked[lock]) {
        fog.classList.remove('gululu-fog-hidden');
      } else {
        fog.classList.add('gululu-fog-hidden');
      }
    }
  }

  /** 揭示一组：本地立即生效 + 上报宿主持久化（骰点解锁跨会话保持）。 */
  function revealGululuGroup(groupId, persist) {
    if (!groupId || gululu.unlocked[groupId]) return false;
    gululu.unlocked[groupId] = true;
    var nodes = document.querySelectorAll('[data-gululu-dice-group="' + groupId + '"]');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].classList.remove('masked');
      nodes[i].classList.add('revealed');
      nodes[i].style.color = '';
      nodes[i].style.background = '';
      nodes[i].style.visibility = '';
    }
    var fogs = document.querySelectorAll('[data-gululu-fog-lock="' + groupId + '"]');
    for (i = 0; i < fogs.length; i++) fogs[i].classList.remove('gululu-fog-hidden');
    if (persist !== false) callBridge('gululuUnlock', groupId);
    // 分页模式下迷雾块显隐会改变列数，必须重排（否则页码与内容错位）
    if (state.paged) onResize();
    return true;
  }

  /** 整楼揭示（对齐桌面 Alt 点击整楼）：返回新解锁的组数。 */
  function revealGululuFloor(groupId) {
    var anchor = document.querySelector('[data-gululu-dice-group="' + groupId + '"]');
    var floor = anchor && anchor.closest ? anchor.closest('.gululu-floor') : null;
    if (!floor) return revealGululuGroup(groupId, true) ? 1 : 0;
    var groups = collectGroupIds(floor);
    var added = 0;
    for (var i = 0; i < groups.length; i++) {
      if (revealGululuGroup(groups[i], false)) added++;
    }
    if (added > 0) callBridge('gululuUnlockAll', JSON.stringify(groups));
    return added;
  }

  /** 按 DOM 阅读顺序揭示本章前 n 个未解锁组（对齐桌面「接下来 10 组」）。 */
  function revealNextGululuGroups(n) {
    var limit = Math.max(1, Math.min(100, n || 10));
    var all = collectGroupIds(document);
    var picked = [];
    for (var i = 0; i < all.length && picked.length < limit; i++) {
      if (!gululu.unlocked[all[i]]) picked.push(all[i]);
    }
    for (i = 0; i < picked.length; i++) revealGululuGroup(picked[i], false);
    if (picked.length > 0) callBridge('gululuUnlockAll', JSON.stringify(picked));
    return picked.length;
  }

  function collectGroupIds(root) {
    var nodes = root.querySelectorAll('.gululu-dice-group[data-gululu-dice-group]');
    var seen = {};
    var out = [];
    for (var i = 0; i < nodes.length; i++) {
      var id = nodes[i].getAttribute('data-gululu-dice-group') || '';
      if (id && !seen[id]) {
        seen[id] = true;
        out.push(id);
      }
    }
    return out;
  }

  /**
   * 段落评论徽标：只在段落末尾插入一个 inert 徽标。
   * 徽标带 data-textpos-exclude，不进入折叠纯文本，因此 text_offset 不变。
   */
  function applyParagraphComments(payload) {
    var counts = parseJsonSafe(payload) || {};
    var existing = document.querySelectorAll('.gululu-paragraph-badge');
    for (var i = 0; i < existing.length; i++) {
      if (existing[i].parentNode) existing[i].parentNode.removeChild(existing[i]);
    }
    var applied = 0;
    for (var pid in counts) {
      if (!Object.prototype.hasOwnProperty.call(counts, pid)) continue;
      var count = counts[pid] | 0;
      if (count <= 0) continue;
      var target = document.querySelector('[data-paragraph-id="' + pid + '"]');
      if (!target) continue;
      var badge = document.createElement('span');
      badge.className = 'gululu-paragraph-badge';
      badge.setAttribute('data-textpos-exclude', '');
      badge.setAttribute('data-gululu-paragraph', String(pid));
      badge.setAttribute('role', 'button');
      badge.setAttribute('tabindex', '0');
      badge.setAttribute('aria-label', '查看本段评论');
      badge.textContent = '💬' + count;
      target.appendChild(badge);
      applied++;
    }
    return applied;
  }

  /** 从 DOM 几何中找一个当前阅读线附近的楼层（elementFromPoint 不可用/未命中时兜底）。 */
  function findGululuFloorNearLine() {
    var floors = document.querySelectorAll('.gululu-floor');
    if (!floors.length) return null;
    var line = Math.round(viewH() * 0.4);
    var best = null;
    for (var i = 0; i < floors.length; i++) {
      var r = floors[i].getBoundingClientRect();
      if (state.paged) {
        // 分页模式：优先选水平方向覆盖当前列中心的楼层。
        var center = viewW() / 2;
        if (r.left <= center && r.right > center) return floors[i];
        if (r.right > 0 && r.left < viewW()) best = floors[i];
      } else {
        // 滚动模式：阅读线以上最后一个楼层。
        if (r.top <= line && r.bottom > 0) best = floors[i];
      }
    }
    return best;
  }

  /** 当前阅读线所在楼层 → 上报宿主（评论抽屉、弹幕、视效、自动音乐都用它）。 */
  function reportGululuContext() {
    var line = state.paged ? Math.round(viewH() * 0.4) : Math.round(viewH() * 0.4);
    var x = Math.max(2, Math.round(viewW() / 2));
    var el = document.elementFromPoint(x, line);
    var floor = el && el.closest ? el.closest('.gululu-floor') : null;
    if (!floor) floor = findGululuFloorNearLine();
    var floorId = 0;
    var vfx = '';
    if (floor) {
      var anchor = floor.getAttribute('id') || '';
      if (anchor.indexOf('floor-') === 0) floorId = parseInt(anchor.slice(6), 10) || 0;
      vfx = floor.getAttribute('data-gululu-vfx') || '';
    }
    if (floorId && floorId !== gululu.lastFloor) {
      gululu.lastFloor = floorId;
      callBridge('gululuFloor', floorId);
    }
    if (vfx !== gululu.lastVfx) {
      gululu.lastVfx = vfx;
      callBridge('gululuVfx', vfx);
    }
    reportGululuBackground(line);
    fireGululuAutoMusic(line);
  }

  /** 阅读线之前最后一个背景标记生效（跨章继承由宿主保留上一个值）。 */
  function reportGululuBackground(line) {
    var markers = document.querySelectorAll(
      '[data-gululu-background-url],[data-gululu-background-initial],[data-gululu-background-clear]',
    );
    var current = '';
    for (var i = 0; i < markers.length; i++) {
      var rect = markers[i].getBoundingClientRect();
      var passed = state.paged ? rect.left < viewW() : rect.top <= line;
      if (!passed) continue;
      if (markers[i].hasAttribute('data-gululu-background-clear')) {
        current = '';
      } else {
        current = markers[i].getAttribute('data-gululu-background-url') ||
          markers[i].getAttribute('data-gululu-background-initial') || '';
      }
    }
    if (current !== gululu.lastBackground) {
      gululu.lastBackground = current;
      callBridge('gululuBackground', current);
    }
  }

  /** 自动音乐：标记到达阅读线时触发一次（每标记只触发一次）。 */
  function fireGululuAutoMusic(line) {
    var cues = document.querySelectorAll('.gululu-music-cue[data-gululu-music-auto]');
    for (var i = 0; i < cues.length; i++) {
      var url = cues[i].getAttribute('data-gululu-music-url') || '';
      if (!url || gululu.autoFired[url]) continue;
      var rect = cues[i].getBoundingClientRect();
      var passed = state.paged ? rect.left < viewW() : rect.top <= line;
      if (!passed) continue;
      gululu.autoFired[url] = true;
      var title = cues[i].querySelector('.gululu-music-title');
      callBridge('gululuMusic', url, title ? title.textContent : '', true);
    }
  }

  /** 命中测试：坐标是否落在骨碌碌交互元素上（宿主据此决定是否把点击当作唤出/收起菜单）。 */
  function hitGululuInteractive(x, y) {
    var el = document.elementFromPoint(x, y);
    return !!(el && el.closest && el.closest(
      '.gululu-dice-value, .gululu-dice-suffix, .gululu-secret-cue, ' +
      '.gululu-clue-cue, .gululu-music-cue, .gululu-music-stop, .gululu-paragraph-badge'
    ));
  }

  /** 绑定骨碌碌交互（骰点、秘密、线索、音乐、段落评论徽标）。 */
  function bindGululu() {
    document.addEventListener('click', function (e) {
      var t = e.target;
      if (!t || !t.closest) return;

      var dice = t.closest('.gululu-dice-value, .gululu-dice-suffix');
      if (dice) {
        e.preventDefault();
        e.stopPropagation();
        var group = dice.getAttribute('data-gululu-dice-group') || '';
        if (e.altKey) revealGululuFloor(group);
        else revealGululuGroup(group, true);
        return;
      }

      var secret = t.closest('.gululu-secret-cue');
      if (secret) {
        e.preventDefault();
        e.stopPropagation();
        callBridge(
          'gululuSecret',
          secret.getAttribute('data-gululu-secret-title') || '',
          secret.getAttribute('data-gululu-secret-cipher') || '',
        );
        return;
      }

      var clue = t.closest('.gululu-clue-cue');
      if (clue) {
        e.preventDefault();
        e.stopPropagation();
        callBridge(
          'gululuClue',
          clue.getAttribute('data-gululu-secret-title') || '',
          clue.getAttribute('data-gululu-secret-password') || '',
        );
        return;
      }

      var music = t.closest('.gululu-music-cue');
      if (music) {
        e.preventDefault();
        e.stopPropagation();
        var titleEl = music.querySelector('.gululu-music-title');
        callBridge(
          'gululuMusic',
          music.getAttribute('data-gululu-music-url') || '',
          titleEl ? titleEl.textContent : '',
          false,
        );
        return;
      }

      if (t.closest('.gululu-music-stop')) {
        e.preventDefault();
        e.stopPropagation();
        callBridge('gululuMusicStop');
        return;
      }

      var badge = t.closest('.gululu-paragraph-badge');
      if (badge) {
        e.preventDefault();
        e.stopPropagation();
        callBridge('gululuParagraphComments', badge.getAttribute('data-gululu-paragraph') || '');
      }
    }, true);
  }

  /** 章节内的全部骰点分组（宿主用于「接下来 10 组」按钮的可用性判断）。 */
  function gululuChapterInfo() {
    var groups = collectGroupIds(document);
    var locked = 0;
    for (var i = 0; i < groups.length; i++) {
      if (!gululu.unlocked[groups[i]]) locked++;
    }
    return JSON.stringify({
      groups: groups.length,
      locked: locked,
      secrets: document.querySelectorAll('.gululu-secret-cue').length,
      clues: document.querySelectorAll('.gululu-clue-cue').length,
      music: document.querySelectorAll('.gululu-music-cue').length,
      floors: document.querySelectorAll('.gululu-floor').length,
    });
  }

  /** 重置：清本地已解锁状态并重新加遮罩（宿主已清持久化）。 */
  function gululuResetUnlocks() {
    gululu.unlocked = {};
    gululu.autoFired = {};
    applyGululuMasks();
    if (state.paged) onResize();
    return true;
  }

  function initGululu(payload) {
    gululu.unlocked = gululuUnlockedIds(payload);
    gululu.autoFired = {};
    gululu.lastVfx = '';
    gululu.lastBackground = '';
    gululu.lastFloor = 0;
    applyGululuMasks();
    bindGululu();
    // 首屏也要上报一次上下文（背景/视效/自动音乐/当前楼）
    setTimeout(reportGululuContext, 0);
  }
