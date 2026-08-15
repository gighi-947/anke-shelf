/**
 * 零依赖代码高亮：正则 tokenizer 重建 pre>code 内部 span。
 * 高亮 span 不改变纯文本（TextPos 对 .syntax 内节点透明化），
 * 因此不破坏 text_offset 与 Python 端的一致性。
 */
(function () {
  'use strict';

  const KEYWORDS = new Set([
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
    'typename', 'virtual', 'override', 'nullptr', 'bool', 'include', 'return',
    // sql
    'SELECT', 'FROM', 'WHERE', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'TABLE', 'JOIN', 'GROUP',
    'ORDER', 'VALUES', 'INNER', 'LEFT', 'RIGHT',
  ]);

  const TOKEN_RE =
    /(\/\/[^\n]*|\/\*[\s\S]*?\*\/|#[^\n]*|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`|\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b[A-Za-z_$][\w$]*\b|[^\sA-Za-z0-9_$]+)/g;

  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function highlight(code) {
    let html = '';
    let last = 0;
    let m;
    TOKEN_RE.lastIndex = 0;
    while ((m = TOKEN_RE.exec(code)) !== null) {
      if (m.index > last) html += esc(code.slice(last, m.index));
      const tok = m[0];
      let cls = '';
      if (tok.startsWith('//') || tok.startsWith('/*') || tok.startsWith('#')) cls = 'tok-com';
      else if (tok.startsWith('"') || tok.startsWith("'") || tok.startsWith('`')) cls = 'tok-str';
      else if (/^\d/.test(tok)) cls = 'tok-num';
      else if (/^[A-Za-z_$]/.test(tok)) {
        if (KEYWORDS.has(tok)) cls = 'tok-kw';
        else if (code[m.index + tok.length] === '(') cls = 'tok-fn';
      } else {
        cls = 'tok-punc';
      }
      html += cls ? `<span class="${cls}">${esc(tok)}</span>` : esc(tok);
      last = m.index + tok.length;
    }
    if (last < code.length) html += esc(code.slice(last));
    return html;
  }

  window.CodeHighlight = {
    highlightBlocks(doc) {
      let changed = 0;
      doc.querySelectorAll('pre code').forEach((code) => {
        const cls = (code.className || '').match(/(?:language-)?([\w+-]+)/);
        if (!cls) return;
        code.innerHTML = highlight(code.textContent);
        code.classList.add('syntax');
        changed += 1;
      });
      return changed;
    },
  };
})();
