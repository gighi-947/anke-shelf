  /* ---------- Kotlin-facing API ---------- */
  function bridgeReadyPayload() {
    return { bridgeVersion: BRIDGE_VERSION, capabilities: BRIDGE_CAPABILITIES.slice() };
  }

  function emitReady() {
    callBridge('onReady', JSON.stringify(bridgeReadyPayload()));
  }

  function applyTheme(vars) {
    var root = document.documentElement;
    if (vars && vars.bg) root.style.setProperty('--reader-bg', vars.bg);
    if (vars && vars.fg) root.style.setProperty('--reader-fg', vars.fg);
    if (vars && vars.primary) root.style.setProperty('--reader-primary', vars.primary);
  }

  function applyTypography(style) {
    if (style && style.fontSize) {
      state.fontSize = style.fontSize;
      document.documentElement.style.setProperty('--reader-font-size', style.fontSize + 'px');
    }
    if (style && style.lineHeight) {
      state.lineHeight = style.lineHeight;
      document.documentElement.style.setProperty('--reader-line-height', String(style.lineHeight));
    }
  }

  function loadReaderFont() {
    if (window.__readerFontLoaded__) return;
    window.__readerFontLoaded__ = true;
    var style = document.createElement('style');
    style.textContent =
      '@font-face{font-family:"LXGW WenKai";' +
      'src:url("file:///android_asset/fonts/LXGWWenKai-Regular.ttf") format("truetype");' +
      'font-weight:400;font-display:swap;}';
    document.head.appendChild(style);
    if (document.fonts && document.fonts.load) {
      document.fonts.load('16px "LXGW WenKai"').then(function () {
        requestAnimationFrame(function () { onResize(); });
      }).catch(function () { /* keep system font */ });
    }
  }

  function clearPagedLayout() {
    var el = scrollEl();
    document.documentElement.style.height = '';
    document.body.style.height = '';
    document.body.style.minHeight = '';
    if (el) {
      el.style.height = '';
      el.style.maxWidth = '';
      var spacer = document.getElementById('__dual_spacer__');
      if (spacer) spacer.remove();
    }
  }

  function setMode(paged) {
    if (!document.body) return;
    var el = scrollEl();
    var wasScrolled = !!el && (state.paged ? el.scrollLeft > 1 : window.scrollY > 1);
    var offset = currentOffset();
    if (offset > 0) {
      // 模式切换是跨模式交接：text_offset 共通，两个模式的锚点都更新为当前值。
      state.pagedAnchor = offset;
      state.scrollAnchor = offset;
    }
    state.paged = !!paged && !state.huge;
    // 模式切换交接：旧模式的页码/锚点作废，避免后续恢复/重排跳回旧位置；
    // 遮罩重置，等新模式布局稳定后再放行。
    state.pagedAnchorPage = -1;
    state.pagedAnchorTotal = -1;
    state.settled = false;
    callBridge('onMode', state.paged);
    document.body.classList.toggle('paged', state.paged);
    if (state.paged) forceEagerImages();
    requestAnimationFrame(function () {
      if (state.paged) {
        prepare();
        normalizeTallTables();
        if (offset > 0) gotoOffset(offset);
        else if (wasScrolled && el) {
          var len = (state.textCtx && state.textCtx.text.length) || 0;
          if (len > 0) {
            var ratio = el.scrollLeft / Math.max(1, el.scrollWidth - el.clientWidth);
            gotoOffset(Math.round(ratio * len));
          }
        }
      } else {
        clearPagedLayout();
        if (offset > 0) {
          restoreScrollOffset(offset);
        } else if (wasScrolled) {
          var r = window.scrollY / Math.max(1, document.body.scrollHeight - window.innerHeight);
          window.scrollTo(0, r * Math.max(1, document.body.scrollHeight - window.innerHeight));
        }
      }
      report(false);
      if (!layoutReady()) {
        tryRestoreAfterSettle(state.paged ? state.pagedAnchor : state.scrollAnchor, 0);
      } else {
        setTimeout(markSettled, 100);
      }
    });
  }

  var resizeTimer = null;
  var resizeOffset = 0;
  var resizeScrolled = false;
  var settleTimer = null;

  function layoutReady() {
    if (document.fonts && document.fonts.status === 'loading') return false;
    // 分页模式依赖图片撑起列高，必须等图片；滚动模式图片懒加载，
    // 等图片会拖到 8 秒兜底，只等字体即可。
    if (state.paged) {
      var imgs = document.images;
      for (var i = 0; i < imgs.length; i++) {
        if (!imgs[i].complete) return false;
      }
    }
    return true;
  }

  function markSettled() {
    if (state.settled) return;
    state.settled = true;
    callBridge('onSettled');
  }

  // 字体/图片加载期间多列布局会反复进入中间态（同一 offset 在不同列之间跳），
  // 只在全部就绪后做最终定位；8 秒兜底（网络卡死时也要能恢复）。
  function tryRestoreAfterSettle(offset, deadline) {
    if (settleTimer) clearTimeout(settleTimer);
    var t = deadline || (Date.now() + 8000);
    settleTimer = setTimeout(function () {
      log('[settle] userMoved=' + state.userMoved + ' ready=' + layoutReady());
      if (state.userMoved) {
        // 用户已滚动/翻页：位置由用户掌控，settle 链只标记就绪，
        // 绝不能用初始 offset 把阅读位置拉回/覆盖（9.54 根因）。
        markSettled();
        return;
      }
      if (!layoutReady() && Date.now() < t) {
        tryRestoreAfterSettle(offset, t);
        return;
      }
      if (state.paged) {
        prepare();
        normalizeTallTables();
        restorePagedAnchor(offset);
      } else if (offset > 0) {
        restoreScrollOffset(offset, state.restoreRatio);
        // 滚动模式：字体就绪后的最终位置才是真位置，重采样并落盘，
        // 避免“切换模式后滚动段落记录错位”。
        var so = 0;
        try { so = currentOffsetScroll(); } catch (e) { /* ignore */ }
        if (so > 0) {
          log('[settle-save] so=' + so);
          state.scrollAnchor = so;
          // 滚动保存显式 page=-1：清除追踪器里残留的分页页码（模式隔离）。
          callBridge('saveProgress', state.chapterIndex, so, true, -1, -1, state.scrollRatio);
        }
      }
      report(false);
      markSettled();
    }, 200);
  }

  function onResize() {
    if (!state.paged) return;
    var el = scrollEl();
    var wasScrolled = !!el && el.scrollLeft > 1;
    // 重排锚点必须是稳定值（用户翻页/滚动时更新的 pagedAnchor），
    // 不能取“当前页顶采样”——多次重排时页顶会逐页漂移，越恢复越靠前。
    var offset = state.pagedAnchor > 0 ? state.pagedAnchor : currentOffsetPaged();
    if (offset > 0 && resizeOffset === 0) resizeOffset = offset;
    if (wasScrolled) resizeScrolled = true;
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      if (!layoutReady()) {
        tryRestoreAfterSettle(resizeOffset > 0 ? resizeOffset : state.restoreOffset, 0);
        return;
      }
      prepare();
      normalizeTallTables();
      if (!restorePagedAnchor(resizeOffset > 0 ? resizeOffset : state.restoreOffset)) {
        // 无页码且 offset<=0：按滚动比例兜底（极少见）。
        var len = (state.textCtx && state.textCtx.text.length) || 0;
        if (len > 0) {
          var ratio = el.scrollLeft / Math.max(1, el.scrollWidth - el.clientWidth);
          gotoOffset(Math.round(ratio * len));
        }
      }
      resizeOffset = 0;
      resizeScrolled = false;
      report(false);
    }, 300);
  }

  function setInsets(top, bottom) {
    state.topInset = Math.max(0, top || 0);
    state.bottomInset = Math.max(0, bottom || 0);
    var root = document.documentElement;
    root.style.setProperty('--reader-top-inset', state.topInset + 'px');
    root.style.setProperty('--reader-bottom-inset', state.bottomInset + 'px');
    if (state.paged) onResize();
  }

  function refresh() {
    if (!state.paged) {
      if (state.restorePending) {
        restoreScrollOffset(state.restoreOffset, state.restoreRatio);
        markSettled();
      } else {
        markSettled();
      }
      return;
    }
    // 字体/图片未就绪时重排会把多列布局打回中间态，gotoOffset 会算出错误页
    // （恢复瞬间闪回章首）；全部就绪后 onResize 会按锚点精确定位，
    // 这里只在就绪后做最终兜底重排。
    if (!layoutReady()) return;
    prepare();
    requestAnimationFrame(function () {
      normalizeTallTables();
      var off = state.restorePending ? state.restoreOffset : state.pagedAnchor;
      restorePagedAnchor(off);
      report(false);
      markSettled();
    });
  }

  function init(opts) {
    state.chapterIndex = opts.chapterIndex || 0;
    state.margin = opts.margin || 40;
    state.gap = opts.gap || 28;
    state.pageWidth = opts.pageWidth || 1;
    state.fontSize = opts.fontSize || 18;
    state.lineHeight = opts.lineHeight || 1.8;
    state.dualPage = !!opts.dualPage;
    state.autoDual = opts.autoDual !== false;
    state.topInset = Math.max(0, opts.topInset || 0);
    state.bottomInset = Math.max(0, opts.bottomInset || 0);
    state.restoreOffset = Math.max(0, opts.offset || 0);
    state.restoreRatio = (opts.scrollRatio === undefined || opts.scrollRatio === null) ? -1 : opts.scrollRatio;
    state.scrollRatio = -1;
    state.pagedAnchor = state.restoreOffset;
    state.scrollAnchor = state.restoreOffset;
    state.restorePending = state.restoreOffset > 0;
    state.wasSwitch = !!opts.wasSwitch;
    state.settled = false;
    state.pagedAnchorPage = (opts.page === undefined || opts.page === null) ? -1 : opts.page;
    state.pagedAnchorTotal = (opts.total === undefined || opts.total === null) ? -1 : opts.total;
    if (!document.body) return;
    state.huge = (document.body.textContent || '').length > MAX_PAGED_TEXT;
    state.paged = !!opts.paged && !state.huge;
    callBridge('onMode', state.paged);
    if (state.paged) {
      state.textCtx = TextPos.build(document);
    } else {
      state.textCtx = null;
      setTimeout(function () {
        state.textCtx = TextPos.build(document);
        if (state.restorePending && state.restoreOffset > 0) {
          restoreScrollOffset(state.restoreOffset, state.restoreRatio);
        }
        var o = 0;
        try { o = currentOffset(); } catch (e) { /* ignore */ }
        if (o > 0) {
          log('[save:scroll] ch=' + state.chapterIndex + ' off=' + o);
          state.scrollAnchor = o;
          callBridge('saveProgress', state.chapterIndex, o, true, -1, -1, state.scrollRatio);
        }
        if (!layoutReady()) {
          tryRestoreAfterSettle(state.restoreOffset > 0 ? state.restoreOffset : state.scrollAnchor, 0);
        } else {
          markSettled();
        }
      }, 0);
    }
    document.body.classList.toggle('paged', state.paged);
    if (state.paged) forceEagerImages();
    if (opts.theme) applyTheme(opts.theme);
    applyTypography({ fontSize: state.fontSize, lineHeight: state.lineHeight });
    loadReaderFont();
    var root = document.documentElement;
    root.style.setProperty('--reader-top-inset', state.topInset + 'px');
    root.style.setProperty('--reader-bottom-inset', state.bottomInset + 'px');
    bindImages();
    // 滚动模式底部换章按钮（分页模式下由 CSS 隐藏）。
    var prevBtn = document.getElementById('android-prev-chapter');
    var nextBtn = document.getElementById('android-next-chapter');
    if (prevBtn) {
      prevBtn.addEventListener('click', function () {
        callBridge('requestChapter', -1);
      });
    }
    if (nextBtn) {
      nextBtn.addEventListener('click', function () {
        callBridge('requestChapter', 1);
      });
    }
    // 只拦截章节内链接；图片打开由 Kotlin 长按（openImageAt）触发，单击不放行。
    document.addEventListener('click', function (e) {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[href]') : null;
      if (a) {
        e.preventDefault();
        e.stopPropagation();
      }
    }, true);
    window.addEventListener('resize', function () { onResize(); });
    // debounced scroll progress (scroll mode, same as desktop reader.js 500ms)
    var scrollTimer = null;
    var lastScrollNotify = 0;
    window.addEventListener('scroll', function () {
      // 分页模式的保存只走翻页 saveProgressNow；字体/图片重排引发的
      // window 滚动事件绝不能当成滚动进度保存（会把正确偏移覆盖成页顶采样）。
      if (state.paged) return;
      // 滚动发生即通知 Compose 外壳（250ms 节流）：唤出浮动栏后的“新滚动”才允许
      // 自动收起；不能等 500ms 防抖保存回调——那可能是唤出前滚动的迟到事件，
      // 会把刚唤出的控制条误收（9.59）。
      var now = Date.now();
      if (now - lastScrollNotify > 250) {
        lastScrollNotify = now;
        callBridge('onScrollMoved');
      }
      if (scrollTimer) clearTimeout(scrollTimer);
      scrollTimer = setTimeout(function () {
        scrollTimer = null;
        var o = 0;
        try { o = currentOffset(); } catch (e) { /* ignore */ }
        if (o > 0) {
          state.userMoved = true;
          state.scrollAnchor = o;
          callBridge('saveProgress', state.chapterIndex, o, true, -1, -1, state.scrollRatio);
        }
      }, 500);
    });
    // 不再用 pagehide 兜底保存：销毁时页面 scrollLeft/滚动位置会被重置，
    // 迟到的 pagehide 会用错误 offset 覆盖刚 flush 的正确进度（9.48 根因）。
    // 退出保存统一由 Kotlin dispose 查询 + flush 完成。
    var finish = function () {
      if (state.paged) {
        prepare();
        normalizeTallTables();
        if (!restorePagedAnchor(state.restoreOffset)) gotoPage(0);
        // 换章后立即把新章位置落库（桌面 loadChapter 语义；首次打开不写，
        // 避免中间布局污染已保存的锚点）。
        if (state.wasSwitch) {
          var o = 0;
          try { o = currentOffset(); } catch (e) { /* ignore */ }
          // 章首采样可能落在首楼卡片 padding 上返回 0；此时也应把“已换到本章”
          // 落库（offset=1 即章首），否则退出重进会回到上一章。
          log('[save:switch] ch=' + state.chapterIndex + ' off=' + (o > 0 ? o : 1));
          // 分页换章落库：显式 page=-1,total=-1,ratio=-1，绝不携带滚动比例。
          callBridge('saveProgressNow', state.chapterIndex, o > 0 ? o : 1, true, -1, -1, -1);
        }
      } else {
        if (state.restoreOffset > 0) restoreScrollOffset(state.restoreOffset, state.restoreRatio);
      }
      if (!layoutReady()) {
        tryRestoreAfterSettle(
          state.paged ? state.pagedAnchor : state.scrollAnchor,
          0,
        );
      } else {
        setTimeout(markSettled, 100);
      }
      report(false);
      emitReady();
    };
    requestAnimationFrame(finish);
    // 最终兜底：字体加载完成后 onResize 已负责定位；此定时器仅在
    // 字体加载失败/无 resize 事件时兜底一次。
    setTimeout(refresh, 2000);
  }

  /* long-press image hit test: returns "true" when an image is under (x,y) */
  function openImageAt(x, y) {
    var el = document.elementFromPoint(x, y);
    var img = el && el.closest ? el.closest('img') : null;
    if (img && img.src) {
      // 长按进入预览时清除系统文本选区，避免选中提示文字残留（9.20 记录）。
      var sel = window.getSelection ? window.getSelection() : null;
      if (sel) sel.removeAllRanges();
      callBridge('openImage', img.src);
      return 'true';
    }
    return 'false';
  }

  window.AnkeReader = {
    init: init,
    applyTheme: applyTheme,
    applyTypography: applyTypography,
    setMode: setMode,
    flipPage: flipPage,
    currentOffset: currentOffset,
    currentScrollState: currentScrollState,
    onResize: onResize,
    setInsets: setInsets,
    gotoOffset: gotoOffset,
    openImageAt: openImageAt,
    geometry: geometry,
    shouldAutoDual: shouldAutoDual,
    buildText: TextPos.build,
    bridgeVersion: function () { return BRIDGE_VERSION; },
    bridgeReadyPayload: bridgeReadyPayload,
    emitReady: emitReady,
  };
})();
