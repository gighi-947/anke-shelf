/** 骨碌碌全能助手秘密：线索本地保存，明文只在宿主层临时展示。 */
(function () {
  'use strict';

  const STORAGE_KEY = 'ankeshelf.gululu.secret-clues.v1';
  const state = {
    sourceId: 0,
    doc: null,
    modal: null,
    escapeHandler: null,
  };

  function loadClues() {
    try {
      const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (error) {
      Toast.show('线索记录损坏，已忽略本地记录', true);
      return {};
    }
  }

  function saveClue(title, password) {
    const clues = loadClues();
    const sourceKey = String(state.sourceId);
    const bookClues = clues[sourceKey] && typeof clues[sourceKey] === 'object'
      ? clues[sourceKey] : {};
    bookClues[title] = password;
    clues[sourceKey] = bookClues;
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(clues));
      return true;
    } catch (error) {
      Toast.show('无法保存线索：' + (error.message || error), true);
      return false;
    }
  }

  function clueFor(title) {
    const clues = loadClues();
    const bookClues = clues[String(state.sourceId)];
    return bookClues && typeof bookClues[title] === 'string' ? bookClues[title] : '';
  }

  function closeModal() {
    if (state.escapeHandler) {
      document.removeEventListener('keydown', state.escapeHandler);
      state.escapeHandler = null;
    }
    if (state.modal) state.modal.remove();
    state.modal = null;
  }

  function showPlaintext(title, plaintext) {
    closeModal();
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay gululu-secret-overlay';

    const dialog = document.createElement('section');
    dialog.className = 'modal gululu-secret-modal';
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    dialog.setAttribute('aria-labelledby', 'gululu-secret-title');

    const heading = document.createElement('h2');
    heading.id = 'gululu-secret-title';
    heading.className = 'gululu-secret-title';
    heading.textContent = title;

    const plaintextNode = document.createElement('pre');
    plaintextNode.className = 'gululu-secret-plaintext';
    plaintextNode.textContent = plaintext;

    const actions = document.createElement('div');
    actions.className = 'modal-actions';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.className = 'btn-primary gululu-secret-close';
    closeButton.textContent = '关闭';
    closeButton.addEventListener('click', closeModal);
    actions.appendChild(closeButton);
    dialog.append(heading, plaintextNode, actions);
    overlay.appendChild(dialog);
    overlay.addEventListener('click', (event) => {
      if (event.target === overlay) closeModal();
    });
    state.escapeHandler = (event) => {
      if (event.key === 'Escape') closeModal();
    };
    document.addEventListener('keydown', state.escapeHandler);
    document.getElementById('modal-root').appendChild(overlay);
    state.modal = overlay;
    closeButton.focus();
  }

  async function unlockSecret(button) {
    const title = String(button.dataset.gululuSecretTitle || '').trim();
    const cipher = String(button.dataset.gululuSecretCipher || '').trim();
    const password = clueFor(title);
    if (!password) {
      Toast.show(`尚未找到线索：${title}`, true);
      return;
    }
    button.disabled = true;
    try {
      const result = await Api.gululuDecryptSecret(state.sourceId, title, cipher, password);
      if (!result || !result.plaintext) throw new Error('秘密内容为空');
      showPlaintext(title, String(result.plaintext));
    } catch (error) {
      Toast.show('秘密解锁失败：' + (error.message || error), true);
    } finally {
      button.disabled = false;
    }
  }

  function collectClue(button) {
    const title = String(button.dataset.gululuSecretTitle || '').trim();
    const password = String(button.dataset.gululuSecretPassword || '');
    if (!title || !password) {
      Toast.show('线索内容无效', true);
      return;
    }
    if (saveClue(title, password)) Toast.show(`线索已记录：${title}`);
  }

  function onChapterLoaded(doc) {
    state.doc = doc || null;
    if (!state.sourceId || !doc) return;
    doc.addEventListener('click', (event) => {
      const secret = event.target.closest('.gululu-secret-cue');
      const clue = event.target.closest('.gululu-clue-cue');
      if (!secret && !clue) return;
      event.preventDefault();
      event.stopPropagation();
      if (secret) unlockSecret(secret);
      else collectClue(clue);
    });
  }

  function setBook(book) {
    closeModal();
    state.doc = null;
    state.sourceId = Number(book && book.gululu_source_id) || 0;
  }

  function close() {
    closeModal();
    state.doc = null;
    state.sourceId = 0;
  }

  /** 重置当前章节的秘密线索（按本章秘密标题移除）。 */
  function resetChapterSecrets() {
    if (!state.sourceId) return;
    const clues = loadClues();
    const bookClues = clues[String(state.sourceId)];
    if (!bookClues || typeof bookClues !== 'object') {
      Toast.show('本书没有已保存的线索');
      return;
    }
    const titles = new Set();
    if (state.doc) {
      state.doc.querySelectorAll('[data-gululu-secret-title]').forEach((node) => {
        const t = String(node.dataset.gululuSecretTitle || '').trim();
        if (t) titles.add(t);
      });
    }
    let removed = 0;
    titles.forEach((title) => {
      if (Object.prototype.hasOwnProperty.call(bookClues, title)) {
        delete bookClues[title];
        removed += 1;
      }
    });
    if (!removed) {
      Toast.show('本章没有已保存的线索');
      return;
    }
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(clues)); } catch (error) {
      Toast.show('无法重置秘密线索', true);
      return;
    }
    closeModal();
    Toast.show(`已重置本章 ${removed} 条线索`);
  }

  /** 重置全书秘密线索。 */
  function resetAllSecrets() {
    if (!state.sourceId) return;
    const clues = loadClues();
    delete clues[String(state.sourceId)];
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(clues)); } catch (error) {
      Toast.show('无法重置秘密线索', true);
      return;
    }
    closeModal();
    Toast.show('已重置全书秘密线索');
  }

  window.GululuSecrets = {
    setBook,
    onChapterLoaded,
    resetChapterSecrets,
    resetAllSecrets,
    close,
    snapshot: () => ({
      sourceId: state.sourceId,
      clueTitles: state.sourceId
        ? Object.keys(loadClues()[String(state.sourceId)] || {}) : [],
      modalOpen: !!state.modal,
    }),
  };
})();
