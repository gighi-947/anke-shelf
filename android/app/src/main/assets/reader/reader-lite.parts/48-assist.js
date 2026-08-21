  /* ---------- code highlight (mirrors desktop web/js/highlight.js) ---------- */
  // 高亮 span 带 .syntax，按折叠规则内部无缝，因此不改变 text_offset。
  var CODE_KEYWORDS = {};
  (function () {
    var words = [
      // js/ts
      'function', 'const', 'let', 'var', 'return', 'if', 'else', 'for', 'while', 'do', 'switch',
      'case', 'break', 'continue', 'new', 'class', 'extends', 'super', 'import', 'export', 'default',
      'async', 'await', 'try', 'catch', 'finally', 'throw', 'typeof', 'instanceof', 'in', 'of', 'this',
      'null', 'undefined', 'true', 'false', 'void', 'delete', 'yield', 'static', 'get', 'set', 'from',
      // python
      'def', 'elif', 'pass', 'not', 'and', 'or', 'with', 'as', 'lambda', 'raise', 'except', 'global',
      'nonlocal', 'None', 'self', 'print', 'is', 'assert',
      // c/c++/java
      'int', 'char', 'double', 'float', 'long', 'short', 'unsigned', 'signed', 'struct', 'union',
      'enum', 'typedef', 'sizeof', 'public', 'private', 'protected', 'using', 'namespace', 'template',
      'typename', 'virtual', 'override', 'nullptr', 'bool', 'include',
      // sql
      'SELECT', 'FROM', 'WHERE', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'TABLE', 'JOIN', 'GROUP',
      'ORDER', 'VALUES', 'INNER', 'LEFT', 'RIGHT',
    ];
    for (var i = 0; i < words.length; i++) CODE_KEYWORDS[words[i]] = true;
  })();

  var CODE_TOKEN_RE =
    /(\/\/[^\n]*|\/\*[\s\S]*?\*\/|#[^\n]*|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`|\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b[A-Za-z_$][\w$]*\b|[^\sA-Za-z0-9_$]+)/g;

  function escapeCode(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function highlightCode(code) {
    var html = '';
    var last = 0;
    var m;
    CODE_TOKEN_RE.lastIndex = 0;
    while ((m = CODE_TOKEN_RE.exec(code)) !== null) {
      if (m.index > last) html += escapeCode(code.slice(last, m.index));
      var tok = m[0];
      var cls = '';
      if (tok.indexOf('//') === 0 || tok.indexOf('/*') === 0 || tok.charAt(0) === '#') {
        cls = 'tok-com';
      } else if (tok.charAt(0) === '"' || tok.charAt(0) === "'" || tok.charAt(0) === '`') {
        cls = 'tok-str';
      } else if (/^\d/.test(tok)) {
        cls = 'tok-num';
      } else if (/^[A-Za-z_$]/.test(tok)) {
        if (CODE_KEYWORDS[tok]) cls = 'tok-kw';
        else if (code.charAt(m.index + tok.length) === '(') cls = 'tok-fn';
      } else {
        cls = 'tok-punc';
      }
      html += cls ? '<span class="' + cls + '">' + escapeCode(tok) + '</span>' : escapeCode(tok);
      last = m.index + tok.length;
    }
    if (last < code.length) html += escapeCode(code.slice(last));
    return html;
  }

  /** 给带语言 class 的 pre>code 重建内部 span；返回改动块数（调用方据此重建坐标）。 */
  function highlightCodeBlocks() {
    var blocks = document.querySelectorAll('pre code');
    var changed = 0;
    for (var i = 0; i < blocks.length; i++) {
      var code = blocks[i];
      if (code.classList && code.classList.contains('syntax')) continue;
      var cls = (code.className || '').match(/(?:language-)?([\w+-]+)/);
      if (!cls) continue;
      code.innerHTML = highlightCode(code.textContent || '');
      code.classList.add('syntax');
      changed++;
    }
    return changed;
  }

  /* ---------- auto scroll (mirrors desktop assist.js setAutoScroll) ---------- */
  // 桌面为 200ms 步进 speed*30px；这里换成逐帧推进同等速度（speed*150 px/s），
  // 触屏上更平滑，px/s 与桌面一致。分页模式仍按整页翻页推进。
  var autoState = { raf: 0, timer: 0, speed: 0, lastAt: 0 };

  function stopAutoScroll() {
    if (autoState.raf) {
      cancelAnimationFrame(autoState.raf);
      autoState.raf = 0;
    }
    if (autoState.timer) {
      clearInterval(autoState.timer);
      autoState.timer = 0;
    }
    autoState.speed = 0;
    return true;
  }

  function autoScrollTick(now) {
    autoState.raf = 0;
    if (!autoState.speed) return;
    var dt = autoState.lastAt ? Math.min(200, now - autoState.lastAt) : 16;
    autoState.lastAt = now;
    var max = Math.max(0, document.body.scrollHeight - window.innerHeight);
    if (window.scrollY >= max - 2) {
      stopAutoScroll();
      callBridge('requestChapter', 1);
      return;
    }
    window.scrollBy(0, (autoState.speed * 150 * dt) / 1000);
    autoState.raf = requestAnimationFrame(autoScrollTick);
  }

  /** 开始自动滚动/自动翻页；speed 为桌面同语义倍率（默认 2）。 */
  function startAutoScroll(speed) {
    stopAutoScroll();
    var s = Number(speed);
    autoState.speed = (isFinite(s) && s > 0) ? Math.min(10, s) : 2;
    autoState.lastAt = 0;
    if (state.paged) {
      // 分页：按整页推进，一页停留时间随速度缩短（speed=2 → 约 5s/页）。
      var pageMs = Math.max(1200, Math.round(10000 / autoState.speed));
      autoState.timer = setInterval(function () {
        var m = measure();
        if (m.current >= m.total - 1) {
          stopAutoScroll();
          callBridge('requestChapter', 1);
          return;
        }
        flipPage(1);
      }, pageMs);
    } else {
      autoState.raf = requestAnimationFrame(autoScrollTick);
    }
    return true;
  }

  function isAutoScrolling() {
    return !!(autoState.raf || autoState.timer);
  }
