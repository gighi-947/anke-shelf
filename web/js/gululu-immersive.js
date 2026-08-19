/** Gululu music, atmospheric backgrounds and host-layer visual effects. */
(function () {
  'use strict';

  const STORAGE_KEY = 'ankeshelf.gululu.immersive.v1';
  const DEFAULTS = { autoMusic: true, backgrounds: true, vfx: true, volume: 0.45 };
  const state = {
    sourceId: 0,
    doc: null,
    panelOpen: false,
    returnFocus: null,
    prefs: loadPreferences(),
    audio: null,
    audioCue: null,
    audioGeneration: 0,
    playedAuto: new WeakSet(),
    backgroundUrl: '',
    backgroundGeneration: 0,
    effect: '',
    effectTimer: null,
    particles: [],
    animationFrame: 0,
    scanTimer: null,
    reducedMotion: window.matchMedia('(prefers-reduced-motion: reduce)'),
  };

  const el = (id) => document.getElementById(id);

  function loadPreferences() {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      const savedVolume = Number(saved.volume);
      return {
        autoMusic: saved.autoMusic !== false,
        backgrounds: saved.backgrounds !== false,
        vfx: saved.vfx !== false,
        volume: Number.isFinite(savedVolume)
          ? Math.max(0, Math.min(1, savedVolume)) : DEFAULTS.volume,
      };
    } catch (error) {
      return { ...DEFAULTS };
    }
  }

  function savePreferences() {
    try {
      if (window.Api) Api.saveSettings({ gululu_immersive: { ...state.prefs } }).catch(() => {});
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state.prefs));
    } catch (error) { /* optional */ }
  }

  function syncPreferences() {
    const saved = App.state.settings && App.state.settings.gululu_immersive;
    if (!saved) return;
    const savedVolume = Number(saved.volume);
    state.prefs = {
      autoMusic: saved.autoMusic !== false,
      backgrounds: saved.backgrounds !== false,
      vfx: saved.vfx !== false,
      volume: Number.isFinite(savedVolume)
        ? Math.max(0, Math.min(1, savedVolume)) : DEFAULTS.volume,
    };
    applyControls();
  }

  function safeHttpsUrl(value) {
    try {
      const parsed = new URL(String(value || ''));
      if (parsed.protocol !== 'https:' || parsed.username || parsed.password) return '';
      return parsed.href;
    } catch (error) {
      return '';
    }
  }

  function setStatus(message, isError) {
    const status = el('gululu-immersive-status');
    if (!status) return;
    status.textContent = message;
    status.classList.toggle('error', !!isError);
  }

  function updatePlayingCue(next) {
    if (state.audioCue) state.audioCue.classList.remove('playing');
    state.audioCue = next || null;
    if (state.audioCue) state.audioCue.classList.add('playing');
  }

  /** 当前播放音乐的楼层标签（如“第3楼”），用于顶栏气泡与控件。 */
  function floorLabel(cue) {
    const floor = cue && cue.closest ? cue.closest('.gululu-floor') : null;
    const num = floor && floor.querySelector('.floor-number');
    return (num && num.textContent.trim()) || '';
  }

  function showMusicToast(title, label) {
    const toast = el('gululu-music-toast');
    const text = el('gululu-music-toast-text');
    if (!toast || !text) return;
    text.textContent = label ? `${title} · ${label}` : title;
    toast.classList.remove('hidden');
  }

  function hideMusicToast() {
    const toast = el('gululu-music-toast');
    if (toast) toast.classList.add('hidden');
  }

  function syncMusicControls() {
    const toggle = el('gululu-music-toggle');
    const seek = el('gululu-music-seek');
    const audio = state.audio;
    if (toggle) {
      const paused = !audio || audio.paused;
      const icon = toggle.querySelector('use');
      const span = toggle.querySelector('span');
      if (icon) icon.setAttribute('href', paused ? '#i-play' : '#i-pause');
      if (span) span.textContent = paused ? '播放' : '暂停';
    }
    if (seek && audio && Number.isFinite(audio.duration) && audio.duration > 0) {
      seek.value = String(Math.round((audio.currentTime / audio.duration) * 1000));
    }
  }

  function toggleMusic() {
    const audio = state.audio;
    if (!audio) return;
    if (audio.paused) {
      audio.volume = state.prefs.volume;
      audio.play().catch(() => { /* optional */ });
    } else {
      audio.pause();
    }
    syncMusicControls();
  }

  function onMusicSeek() {
    const audio = state.audio;
    const seek = el('gululu-music-seek');
    if (!audio || !seek) return;
    const value = Number(seek.value);
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      audio.currentTime = (value / 1000) * audio.duration;
    }
  }

  function stopMusic(options) {
    const settings = options || {};
    if (window.__gululuDiag) console.log('[gululu] stopMusic', settings.silent ? 'silent' : 'user', settings.immediate ? 'immediate' : '');
    const audio = state.audio;
    state.audioGeneration += 1;
    state.audio = null;
    updatePlayingCue(null);
    hideMusicToast();
    syncMusicControls();
    if (audio) {
      const start = Number(audio.volume) || 0;
      const startedAt = performance.now();
      const fade = () => {
        const progress = Math.min(1, (performance.now() - startedAt) / 320);
        audio.volume = Math.max(0, start * (1 - progress));
        if (progress < 1) requestAnimationFrame(fade);
        else { audio.pause(); audio.removeAttribute('src'); }
      };
      if (settings.immediate) { audio.pause(); audio.removeAttribute('src'); }
      else requestAnimationFrame(fade);
    }
    if (!settings.silent) setStatus('音乐已停止');
  }

  async function playMusic(cue, automatic) {
    const url = safeHttpsUrl(cue && cue.dataset.gululuMusicUrl);
    const titleNode = cue && cue.querySelector('.gululu-music-title');
    const title = (titleNode && titleNode.textContent.trim()) || 'BGM';
    if (window.__gululuDiag) console.log('[gululu] playMusic', automatic ? 'auto' : 'manual', title, 'url=' + url);
    if (!url) {
      setStatus('音乐链接不可用', true);
      return;
    }
    if (state.audio && state.audioCue === cue) {
      // 同曲切停只对手动点击生效；自动播放（scanChapter 轮询）遇到
      // 同 cue 已在播放时应直接跳过，避免 250ms 轮询把手动播放杀掉。
      if (!automatic) stopMusic();
      return;
    }
    stopMusic({ silent: true });
    const generation = ++state.audioGeneration;
    const audio = new Audio(url);
    audio.loop = true;
    audio.preload = 'none';
    audio.volume = 0;
    if (audio.addEventListener) audio.addEventListener('timeupdate', syncMusicControls);
    state.audio = audio;
    updatePlayingCue(cue);
    setStatus(`${automatic ? '自动音乐' : '正在播放'}：${title}`);
    showMusicToast(title, floorLabel(cue));
    syncMusicControls();
    if (audio.addEventListener) {
      audio.addEventListener('error', () => {
        if (state.audio !== audio || generation !== state.audioGeneration) return;
        stopMusic({ silent: true, immediate: true });
        setStatus(`音乐加载失败：${title}`, true);
      }, { once: true });
    }
    try {
      await audio.play();
      const startedAt = performance.now();
      const fade = () => {
        if (state.audio !== audio || generation !== state.audioGeneration) return;
        const progress = Math.min(1, (performance.now() - startedAt) / 600);
        audio.volume = state.prefs.volume * progress;
        if (progress < 1) requestAnimationFrame(fade);
      };
      requestAnimationFrame(fade);
    } catch (error) {
      if (generation !== state.audioGeneration) return;
      const blocked = error && error.name === 'NotAllowedError';
      if (blocked) {
        setStatus(`点击页面继续播放：${title}`, true);
        const resume = async () => {
          if (state.audio !== audio || generation !== state.audioGeneration) return;
          try {
            await audio.play();
            setStatus(`自动音乐：${title}`);
            const startedAt = performance.now();
            const fade = () => {
              if (state.audio !== audio || generation !== state.audioGeneration) return;
              const progress = Math.min(1, (performance.now() - startedAt) / 600);
              audio.volume = state.prefs.volume * progress;
              if (progress < 1) requestAnimationFrame(fade);
            };
            requestAnimationFrame(fade);
          } catch (resumeError) { /* wait for another explicit cue click */ }
        };
        ['click', 'keydown', 'touchstart'].forEach((type) => {
          document.addEventListener(type, resume, { once: true });
          if (state.doc) state.doc.addEventListener(type, resume, { once: true });
        });
      } else {
        stopMusic({ silent: true, immediate: true });
        setStatus(`音乐加载失败：${title}`, true);
      }
    }
  }

  function currentViewport() {
    const frame = el('chapter-frame');
    const root = el('reader-root');
    if (!frame || !root || !state.doc) return null;
    return {
      frameRect: frame.getBoundingClientRect(),
      centerY: root.getBoundingClientRect().top + root.clientHeight * 0.5,
      pageWidth: state.doc.documentElement.clientWidth || frame.clientWidth || 1,
      paged: !!(window.Paged && Paged.isActive()),
    };
  }

  function isReached(node, viewport) {
    const rect = node.getBoundingClientRect();
    if (viewport.paged) return rect.left <= viewport.pageWidth * 0.55;
    return viewport.frameRect.top + rect.top <= viewport.centerY;
  }

  function activeFloor(viewport) {
    const floors = Array.from(state.doc.querySelectorAll('.gululu-floor'));
    if (!floors.length) return null;
    if (viewport.paged) {
      const centerX = viewport.pageWidth * 0.5;
      let closest = floors[0];
      let distance = Infinity;
      floors.forEach((floor) => {
        Array.from(floor.getClientRects()).forEach((rect) => {
          if (rect.right <= 0 || rect.left >= viewport.pageWidth || rect.bottom <= 0) return;
          const nextDistance = centerX < rect.left ? rect.left - centerX
            : (centerX > rect.right ? centerX - rect.right : 0);
          if (nextDistance < distance) { closest = floor; distance = nextDistance; }
        });
      });
      return closest;
    }
    let closest = floors[0];
    let distance = Infinity;
    floors.forEach((floor) => {
      const rect = floor.getBoundingClientRect();
      const top = viewport.frameRect.top + rect.top;
      const bottom = viewport.frameRect.top + rect.bottom;
      const nextDistance = viewport.centerY < top ? top - viewport.centerY
        : (viewport.centerY > bottom ? viewport.centerY - bottom : 0);
      if (nextDistance < distance) { closest = floor; distance = nextDistance; }
    });
    return closest;
  }

  function desiredBackground(viewport) {
    const initial = state.doc.querySelector('[data-gululu-background-initial]');
    let desired = safeHttpsUrl(initial && initial.dataset.gululuBackgroundInitial);
    state.doc.querySelectorAll('[data-gululu-background-url], [data-gululu-background-clear]').forEach((node) => {
      if (!isReached(node, viewport)) return;
      desired = node.hasAttribute('data-gululu-background-clear')
        ? '' : safeHttpsUrl(node.dataset.gululuBackgroundUrl);
    });
    return desired;
  }

  function applyBackground(url) {
    state.backgroundUrl = url || '';
    const layer = el('gululu-background-layer');
    const root = el('reader-root');
    const enabledUrl = state.prefs.backgrounds ? state.backgroundUrl : '';
    const generation = ++state.backgroundGeneration;
    if (!enabledUrl) {
      layer.style.backgroundImage = '';
      root.classList.remove('gululu-background-active');
      return;
    }
    const image = new Image();
    image.onload = () => {
      if (generation !== state.backgroundGeneration) return;
      // 切图淡入：先透明（强制 reflow 确保生效），换图后恢复（触发 800ms transition）
      layer.style.backgroundImage = `url(${JSON.stringify(enabledUrl)})`;
      layer.style.opacity = '0';
      void layer.offsetWidth; // 强制同步布局，避免 opacity 0 未生效就恢复
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (generation === state.backgroundGeneration) layer.style.opacity = '';
      }));
      root.classList.add('gululu-background-active');
    };
    image.onerror = () => {
      if (generation === state.backgroundGeneration) setStatus('氛围背景加载失败', true);
    };
    image.src = enabledUrl;
  }

  function resizeCanvas() {
    const canvas = el('gululu-vfx-canvas');
    const root = el('reader-root');
    if (!canvas || !root) return;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    const width = Math.max(1, root.clientWidth);
    const height = Math.max(1, root.clientHeight);
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;
    const context = canvas.getContext('2d');
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
  }

  function makeParticle(effect, width, height, initial) {
    if (effect === 'snow') {
      return { x: Math.random() * width, y: initial ? Math.random() * height : -8,
        vx: Math.random() * 0.5 - 0.25, vy: 0.5 + Math.random(), size: 1.5 + Math.random() * 2.5 };
    }
    if (effect === 'wind') {
      return { x: initial ? Math.random() * width : -30, y: Math.random() * height,
        vx: 4 + Math.random() * 5, vy: Math.random() * 0.25 - 0.125, size: 20 + Math.random() * 45 };
    }
    return { x: Math.random() * width, y: initial ? Math.random() * height : -18,
      vx: -0.8, vy: 8 + Math.random() * 6, size: 10 + Math.random() * 12 };
  }

  function startParticles(effect) {
    resizeCanvas();
    const canvas = el('gululu-vfx-canvas');
    const context = canvas.getContext('2d');
    const count = effect === 'snow' ? 90 : 120;
    state.particles = Array.from({ length: count }, () => (
      makeParticle(effect, canvas.clientWidth, canvas.clientHeight, true)
    ));
    const draw = () => {
      if (!state.effect || !state.prefs.vfx || state.reducedMotion.matches) return;
      const width = canvas.clientWidth;
      const height = canvas.clientHeight;
      context.clearRect(0, 0, width, height);
      state.particles.forEach((particle, index) => {
        particle.x += particle.vx;
        particle.y += particle.vy;
        if (particle.y > height + 30 || particle.x > width + 60 || particle.x < -80) {
          state.particles[index] = makeParticle(effect, width, height, false);
          particle = state.particles[index];
        }
        context.beginPath();
        if (effect === 'snow') {
          context.fillStyle = 'rgba(255,255,255,.82)';
          context.arc(particle.x, particle.y, particle.size, 0, Math.PI * 2);
          context.fill();
        } else {
          context.strokeStyle = effect === 'wind' ? 'rgba(230,245,240,.36)' : 'rgba(170,215,245,.48)';
          context.lineWidth = effect === 'wind' ? 1.4 : 1;
          context.moveTo(particle.x, particle.y);
          context.lineTo(particle.x + particle.size, particle.y + (effect === 'wind' ? 1 : particle.size));
          context.stroke();
        }
      });
      state.animationFrame = requestAnimationFrame(draw);
    };
    state.animationFrame = requestAnimationFrame(draw);
  }

  function stopVfx() {
    cancelAnimationFrame(state.animationFrame);
    clearTimeout(state.effectTimer);
    state.animationFrame = 0;
    state.effectTimer = null;
    state.particles = [];
    const canvas = el('gululu-vfx-canvas');
    if (canvas) canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
    el('reader-root').classList.remove('gululu-vfx-quake', 'gululu-vfx-flash');
    state.effect = '';
  }

  function applyVfx(effect) {
    const desired = state.prefs.vfx && !state.reducedMotion.matches ? effect : '';
    if (desired === state.effect) return;
    stopVfx();
    if (!desired || desired === 'stop') return;
    state.effect = desired;
    const root = el('reader-root');
    if (desired === 'quake') {
      root.classList.add('gululu-vfx-quake');
      state.effectTimer = setTimeout(stopVfx, 4000);
    } else {
      startParticles(desired === 'thunder' ? 'rain' : desired);
      if (desired === 'thunder') {
        root.classList.add('gululu-vfx-flash');
        state.effectTimer = setTimeout(() => root.classList.remove('gululu-vfx-flash'), 900);
      }
    }
  }

  function scanChapter() {
    if (!state.sourceId || !state.doc || !state.doc.body) return;
    const viewport = currentViewport();
    if (!viewport) return;
    const background = desiredBackground(viewport);
    if (background !== state.backgroundUrl) applyBackground(background);
    const floor = activeFloor(viewport);
    applyVfx((floor && floor.dataset.gululuVfx) || '');
    if (state.prefs.autoMusic) {
      state.doc.querySelectorAll('[data-gululu-music-auto="true"]').forEach((cue) => {
        if (!state.playedAuto.has(cue) && isReached(cue, viewport)) {
          state.playedAuto.add(cue);
          playMusic(cue, true);
        }
      });
    }
  }

  function bindChapter(doc) {
    if (!state.sourceId || !doc) {
      state.doc = null;
      return;
    }
    state.doc = doc;
    state.playedAuto = new WeakSet();
    applyBackground('');
    doc.addEventListener('click', (event) => {
      const cue = event.target.closest('[data-gululu-music-url]');
      const stop = event.target.closest('[data-gululu-music-stop]');
      if (cue) { event.preventDefault(); event.stopPropagation(); playMusic(cue, false); }
      else if (stop) { event.preventDefault(); event.stopPropagation(); stopMusic(); }
    });
    doc.addEventListener('keydown', (event) => {
      if (!event.target.closest('[data-gululu-music-stop]') || !['Enter', ' '].includes(event.key)) return;
      event.preventDefault();
      stopMusic();
    });
    doc.addEventListener('mouseover', (event) => {
      const floor = event.target.closest('.gululu-floor');
      if (floor) applyVfx(floor.dataset.gululuVfx || '');
    });
    scanChapter();
  }

  function setBook(book) {
    state.sourceId = Number(book && book.gululu_source_id) || 0;
    state.doc = null;
    closePanel();
    stopMusic({ silent: true, immediate: true });
    applyBackground('');
    stopVfx();
    el('gululu-immersive-btn').classList.add('hidden');
    clearInterval(state.scanTimer);
    state.scanTimer = state.sourceId ? setInterval(scanChapter, 250) : null;
  }

  function togglePanel(force, trigger, restoreFocus) {
    if (!state.sourceId && force !== false) return;
    const wasOpen = state.panelOpen;
    state.panelOpen = typeof force === 'boolean' ? force : !state.panelOpen;
    if (state.panelOpen && trigger) state.returnFocus = trigger;
    el('gululu-immersive-panel').classList.toggle('hidden', !state.panelOpen);
    el('gululu-immersive-btn').classList.toggle('active', state.panelOpen);
    const quick = el('gululu-quick-immersive');
    if (quick) {
      quick.classList.toggle('active', state.panelOpen);
      quick.setAttribute('aria-expanded', String(state.panelOpen));
    }
    if (state.panelOpen) {
      if (window.App && App.setGululuQuickMenu) App.setGululuQuickMenu(false, false);
      if (window.ViewMenu) ViewMenu.close(false);
      if (window.GululuComments && GululuComments.closePanel) GululuComments.closePanel();
      if (window.GululuOverview && GululuOverview.closePanel) GululuOverview.closePanel();
    }
    if (wasOpen && !state.panelOpen && restoreFocus && state.returnFocus) {
      state.returnFocus.focus();
    }
    if (!state.panelOpen) state.returnFocus = null;
  }

  function closePanel(restoreFocus) {
    togglePanel(false, null, !!restoreFocus);
  }

  function applyControls() {
    el('gululu-auto-music-toggle').checked = state.prefs.autoMusic;
    el('gululu-background-toggle').checked = state.prefs.backgrounds;
    el('gululu-vfx-toggle').checked = state.prefs.vfx;
    el('gululu-volume').value = String(Math.round(state.prefs.volume * 100));
    el('gululu-vfx-toggle').disabled = state.reducedMotion.matches;
  }

  document.addEventListener('DOMContentLoaded', () => {
    applyControls();
    el('gululu-immersive-btn').addEventListener('click', (event) => {
      togglePanel(undefined, event.currentTarget, true);
    });
    el('gululu-immersive-close').addEventListener('click', () => closePanel(true));
    el('gululu-stop-music').addEventListener('click', () => stopMusic());
    el('gululu-music-toggle').addEventListener('click', () => toggleMusic());
    el('gululu-music-seek').addEventListener('input', onMusicSeek);
    el('gululu-auto-music-toggle').addEventListener('change', (event) => {
      state.prefs.autoMusic = event.target.checked;
      savePreferences();
      scanChapter();
    });
    el('gululu-background-toggle').addEventListener('change', (event) => {
      state.prefs.backgrounds = event.target.checked;
      savePreferences();
      applyBackground(state.backgroundUrl);
    });
    el('gululu-vfx-toggle').addEventListener('change', (event) => {
      state.prefs.vfx = event.target.checked;
      savePreferences();
      scanChapter();
    });
    el('gululu-volume').addEventListener('input', (event) => {
      state.prefs.volume = Math.max(0, Math.min(1, Number(event.target.value) / 100));
      if (state.audio) state.audio.volume = state.prefs.volume;
      savePreferences();
    });
    state.reducedMotion.addEventListener('change', () => { applyControls(); scanChapter(); });
    window.addEventListener('resize', () => { resizeCanvas(); scanChapter(); });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && state.panelOpen) closePanel(true);
    });
  });

  window.GululuImmersive = {
    setBook,
    syncPreferences,
    onChapterLoaded: bindChapter,
    closePanel,
    togglePanel: (trigger) => togglePanel(undefined, trigger, true),
    close: () => {
      closePanel();
      clearInterval(state.scanTimer);
      state.scanTimer = null;
      state.doc = null;
      state.sourceId = 0;
      el('gululu-immersive-btn').classList.add('hidden');
      stopMusic({ silent: true, immediate: true });
      applyBackground('');
      stopVfx();
    },
    snapshot: () => {
      const cue = state.audioCue;
      const titleNode = cue && cue.querySelector ? cue.querySelector('.gululu-music-title') : null;
      return {
        sourceId: state.sourceId,
        panelOpen: state.panelOpen,
        playing: !!state.audio,
        backgroundUrl: state.backgroundUrl,
        effect: state.effect,
        reducedMotion: state.reducedMotion.matches,
        prefs: { ...state.prefs },
        musicTitle: (titleNode && titleNode.textContent.trim()) || '',
        musicFloor: cue ? floorLabel(cue) : '',
      };
    },
  };
})();
