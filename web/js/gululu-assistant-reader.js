/** Reader-only runtime for Gululu assistant dice, fog and fold protocols. */
(function () {
  'use strict';

  const PREFS_KEY = 'ankeshelf.gululu.assistant-reader.v1';
  const DEFAULTS = { diceMask: true, fog: true, folding: true, sound: true };
  const state = {
    sourceId: 0,
    doc: null,
    prefs: loadPreferences(),
    unlocked: new Set(),
    listeners: null,
    audioContext: null,
  };

  function loadPreferences() {
    try {
      const saved = JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
      return {
        diceMask: saved.diceMask !== false,
        fog: saved.fog !== false,
        folding: saved.folding !== false,
        sound: saved.sound !== false,
      };
    } catch (error) {
      return { ...DEFAULTS };
    }
  }

  function savePreferences() {
    try { localStorage.setItem(PREFS_KEY, JSON.stringify(state.prefs)); } catch (error) { /* optional */ }
  }

  function unlockedKey() {
    return `ankeshelf.gululu.${state.sourceId}.dice-unlocked.v1`;
  }

  function loadUnlocked() {
    if (!state.sourceId) return new Set();
    try {
      const saved = JSON.parse(localStorage.getItem(unlockedKey()) || '[]');
      return new Set(Array.isArray(saved) ? saved.filter((item) => typeof item === 'string') : []);
    } catch (error) {
      Toast.show('骰点解锁记录损坏，已忽略本地记录', true);
      return new Set();
    }
  }

  function saveUnlocked() {
    if (!state.sourceId) return;
    const values = Array.from(state.unlocked);
    const trimmed = values.length > 3000 ? values.slice(-2000) : values;
    try { localStorage.setItem(unlockedKey(), JSON.stringify(trimmed)); } catch (error) {
      Toast.show('无法保存骰点解锁进度', true);
    }
  }

  function playDiceSound() {
    if (!state.prefs.sound) return;
    try {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      if (!AudioContext) return;
      if (!state.audioContext) state.audioContext = new AudioContext();
      const context = state.audioContext;
      const length = Math.max(1, Math.floor(context.sampleRate * 0.12));
      const buffer = context.createBuffer(1, length, context.sampleRate);
      const channel = buffer.getChannelData(0);
      for (let index = 0; index < length; index += 1) {
        const decay = 1 - index / length;
        channel[index] = (Math.random() * 2 - 1) * decay * decay * 0.28;
      }
      const source = context.createBufferSource();
      const filter = context.createBiquadFilter();
      filter.type = 'bandpass';
      filter.frequency.value = 820;
      source.buffer = buffer;
      source.connect(filter);
      filter.connect(context.destination);
      source.start();
    } catch (error) { /* optional sound */ }
  }

  function allGroups(doc) {
    const result = [];
    const seen = new Set();
    (doc || state.doc)?.querySelectorAll('[data-gululu-dice-group]').forEach((node) => {
      const group = node.dataset.gululuDiceGroup;
      if (group && !seen.has(group)) { seen.add(group); result.push(group); }
    });
    return result;
  }

  function applyState() {
    const doc = state.doc;
    if (!doc) return;
    doc.querySelectorAll('.gululu-dice-value, .gululu-dice-suffix').forEach((node) => {
      const group = node.dataset.gululuDiceGroup;
      const revealed = !state.prefs.diceMask || state.unlocked.has(group);
      node.classList.toggle('masked', !revealed);
      node.classList.toggle('revealed', revealed);
      if (node.classList.contains('gululu-dice-value')) {
        node.setAttribute('aria-expanded', String(revealed));
        node.tabIndex = revealed ? -1 : 0;
      }
    });
    doc.querySelectorAll('[data-gululu-fog-lock]').forEach((node) => {
      const group = node.dataset.gululuFogLock;
      const hidden = state.prefs.diceMask && state.prefs.fog && !state.unlocked.has(group);
      node.classList.toggle('gululu-fog-hidden', hidden);
      node.setAttribute('aria-hidden', String(hidden));
    });
    doc.querySelectorAll('details.gululu-assistant-fold').forEach((details) => {
      if (!state.prefs.folding) details.open = true;
    });
    const revealButton = document.getElementById('gululu-quick-reveal-dice');
    if (revealButton) {
      const remaining = allGroups(doc).filter((group) => !state.unlocked.has(group)).length;
      revealButton.disabled = remaining === 0;
      revealButton.title = remaining ? `揭示接下来的骰点（本章剩余 ${remaining} 组）` : '本章骰点已全部揭示';
    }
    if (window.Reader && Reader.applyLayout) Reader.applyLayout();
  }

  function revealGroups(groups) {
    let changed = 0;
    groups.forEach((group) => {
      if (!group || state.unlocked.has(group)) return;
      state.unlocked.add(group);
      changed += 1;
    });
    if (!changed) return 0;
    saveUnlocked();
    playDiceSound();
    applyState();
    restoreReadingPosition();
    return changed;
  }

  /** 解锁/重置改变正文布局（迷雾块显隐）后，保持当前阅读文本位置。 */
  function restoreReadingPosition() {
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (!state.doc) return;
      if (window.Reader && Reader.currentOffset && Reader.seekToOffset) {
        const off = Reader.currentOffset();
        Reader.seekToOffset(off);
      }
    }));
  }

  function revealGroup(group, wholeFloor) {
    if (!group || !state.doc) return;
    if (wholeFloor) {
      const target = state.doc.querySelector(`[data-gululu-dice-group="${CSS.escape(group)}"]`);
      const floor = target && target.closest('.gululu-floor');
      const groups = allGroups(floor || state.doc);
      revealGroups(groups);
      return;
    }
    revealGroups([group]);
  }

  function revealNext10() {
    if (!state.doc) return 0;
    const groups = allGroups(state.doc).filter((group) => !state.unlocked.has(group)).slice(0, 10);
    const count = revealGroups(groups);
    Toast.show(count ? `已揭示 ${count} 组骰点` : '本章没有未揭示的骰点');
    return count;
  }

  /** 解锁下一组未揭示的骰点。 */
  function revealNextOne() {
    if (!state.doc) return 0;
    const groups = allGroups(state.doc).filter((group) => !state.unlocked.has(group));
    if (!groups.length) {
      Toast.show('本章骰点已全部解锁');
      return 0;
    }
    revealGroups([groups[0]]);
    return 1;
  }

  /** 一次性解锁当前章节全部骰点组。 */
  function revealAll() {
    if (!state.doc) return 0;
    const groups = allGroups(state.doc).filter((group) => !state.unlocked.has(group));
    const count = revealGroups(groups);
    Toast.show(count ? `已解锁本章全部 ${count} 组骰点` : '本章骰点已全部解锁');
    return count;
  }

  function detachDocument() {
    if (!state.doc || !state.listeners) return;
    state.doc.removeEventListener('click', state.listeners.click);
    state.doc.removeEventListener('keydown', state.listeners.keydown);
    state.listeners = null;
  }

  function onChapterLoaded(doc) {
    detachDocument();
    state.doc = doc || null;
    if (!state.sourceId || !state.doc) return;
    const activate = (event, target) => {
      event.preventDefault();
      event.stopPropagation();
      revealGroup(target.dataset.gululuDiceGroup, !!event.altKey);
    };
    const click = (event) => {
      const target = event.target.closest('.gululu-dice-value.masked');
      if (target) activate(event, target);
    };
    const keydown = (event) => {
      const target = event.target.closest('.gululu-dice-value.masked');
      if (target && (event.key === 'Enter' || event.key === ' ')) activate(event, target);
    };
    state.listeners = { click, keydown };
    state.doc.addEventListener('click', click);
    state.doc.addEventListener('keydown', keydown);
    applyState();
  }

  function setBook(book) {
    detachDocument();
    state.doc = null;
    state.sourceId = Number(book && book.gululu_source_id) || 0;
    state.unlocked = loadUnlocked();
  }

  function setPreferences(patch) {
    Object.keys(DEFAULTS).forEach((key) => {
      if (Object.prototype.hasOwnProperty.call(patch || {}, key)) state.prefs[key] = !!patch[key];
    });
    savePreferences();
    applyState();
    if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
  }

  /** 重置当前章节的骰点揭示（从解锁集合移除本章组）。 */
  function resetChapterDice() {
    if (!state.sourceId || !state.doc) return;
    const chapterGroups = allGroups(state.doc);
    let removed = 0;
    chapterGroups.forEach((group) => {
      if (state.unlocked.has(group)) { state.unlocked.delete(group); removed += 1; }
    });
    if (!removed) {
      Toast.show('本章没有已解锁的骰点');
      return;
    }
    saveUnlocked();
    applyState();
    restoreReadingPosition();
    Toast.show(`已重置本章 ${removed} 组骰点揭示`);
  }

  /** 重置全书骰点揭示。 */
  function resetAllDice() {
    if (!state.sourceId) return;
    try { localStorage.removeItem(unlockedKey()); } catch (error) { /* optional */ }
    state.unlocked = new Set();
    applyState();
    restoreReadingPosition();
    Toast.show('已重置全书骰点揭示');
  }

  function close() {
    detachDocument();
    state.doc = null;
    state.sourceId = 0;
    state.unlocked = new Set();
  }

  window.GululuAssistantReader = {
    setBook,
    onChapterLoaded,
    setPreferences,
    revealNext10,
    revealNextOne,
    revealAll,
    resetChapterDice,
    resetAllDice,
    close,
    snapshot: () => ({
      sourceId: state.sourceId,
      prefs: { ...state.prefs },
      unlocked: Array.from(state.unlocked),
      groups: allGroups(state.doc),
    }),
  };
})();
