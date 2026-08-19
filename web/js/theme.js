/**
 * 主题与字号管理。
 *
 * 参考 Readest / flow / Koodo Reader 的主题体系：
 * - theme_mode 支持跟随系统（system）与固定模式（light/sepia/dark）；
 * - 预置色板（PALETTES）提供“背景 + 文字 + 主题色 + 强调色”的组合；
 * - 自定义背景色会自动派生 base-200/base-300 等层次色，避免界面“同色无层次”；
 * - 自定义文字色只作用于默认黑/白文字，NGA 帖子中的彩色字体不受影响
 *   （内联彩色由 reader.js 的 remapNgaDefaultColors 区分处理）。
 *
 * 关键机制：CSS 变量定义在 :root（style.css 三组主题），可穿透 shadow DOM；
 * 字号通过 #reader-root 的内联 style 设置变量，shadow 内覆盖样式引用之。
 */
(function () {
  'use strict';

  const THEMES = ['light', 'sepia', 'dark'];

  /** 预置色板：名称 + 背景/文字/主题色/强调色（供设置页选择与实时预览）。 */
  const PALETTES = [
    { id: 'default-light', name: '默认浅色', bg: '#ffffff', text: '#171717', primary: '#0066cc', accent: '#0066cc' },
    { id: 'sepia', name: '羊皮纸', bg: '#f1e8d0', text: '#5b4636', primary: '#008b8b', accent: '#008b8b' },
    { id: 'night', name: '夜间', bg: '#222222', text: '#e0e0e0', primary: '#77bbee', accent: '#77bbee' },
    { id: 'solarized-light', name: 'Solarized 浅', bg: '#fdf6e3', text: '#657b83', primary: '#268bd2', accent: '#2aa198' },
    { id: 'solarized-dark', name: 'Solarized 深', bg: '#002b36', text: '#93a1a1', primary: '#268bd2', accent: '#2aa198' },
    { id: 'nord-light', name: 'Nord 浅', bg: '#eceff4', text: '#2e3440', primary: '#5e81ac', accent: '#88c0d0' },
    { id: 'nord-dark', name: 'Nord 深', bg: '#2e3440', text: '#eceff4', primary: '#88c0d0', accent: '#81a1c1' },
    { id: 'green-eye', name: '护眼绿', bg: '#dcedd8', text: '#234a2b', primary: '#2f855a', accent: '#38a169' },
    { id: 'ink-blue', name: '墨蓝', bg: '#1e293b', text: '#e2e8f0', primary: '#60a5fa', accent: '#38bdf8' },
  ];

  /** '#rgb'/'#rrggbb' → [r,g,b]；非法输入返回 null。 */
  function hexToRgb(hex) {
    if (!hex) return null;
    let h = String(hex).trim().replace(/^#/, '');
    if (h.length === 3) h = h.split('').map((c) => c + c).join('');
    if (!/^[0-9a-fA-F]{6}$/.test(h)) return null;
    const n = parseInt(h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  function toHex(r, g, b) {
    const c = (v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0');
    return '#' + c(r) + c(g) + c(b);
  }

  /** 相对亮度（0~1），用于判断深浅与前景对比。 */
  function luminance(hex) {
    const rgb = hexToRgb(hex);
    if (!rgb) return 0.5;
    const f = (v) => {
      const s = v / 255;
      return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]);
  }

  /** 背景色深浅感知地“加亮/压暗”一定比例，用于派生界面层次色。 */
  function shade(hex, pct) {
    const rgb = hexToRgb(hex);
    if (!rgb) return hex;
    const target = luminance(hex) > 0.5 ? 0 : 255; // 浅底→压暗，深底→加亮
    return toHex(
      rgb[0] + (target - rgb[0]) * pct,
      rgb[1] + (target - rgb[1]) * pct,
      rgb[2] + (target - rgb[2]) * pct,
    );
  }

  /** 根据主题色亮度选择可读的前景色（白/深色）。 */
  function contentColor(hex) {
    return luminance(hex) > 0.55 ? '#171717' : '#ffffff';
  }

  function isDark(settings) {
    return resolveTheme(settings) === 'dark';
  }

  /** 解析实际生效的主题：system → 跟随系统深浅色；固定模式 → 模式本身；否则 theme。 */
  function effectiveCoverColors(settings) {
    const s = settings || {};
    const resolved = resolveTheme(s);
    const paletteId = resolved === 'dark' ? 'night' : resolved === 'light' ? 'default-light' : resolved;
    const palette = PALETTES.find((p) => p.id === paletteId) || PALETTES[0];
    return {
      bg: s.custom_bg || palette.bg,
      fg: s.custom_text || palette.text,
    };
  }

  function coverUrl(url) {
    if (!url) return url;
    const colors = effectiveCoverColors(window.App ? App.state.settings : {});
    const sep = url.includes('?') ? '&' : '?';
    return url + sep + 'bg=' + encodeURIComponent(colors.bg) + '&fg=' + encodeURIComponent(colors.fg);
  }

  function resolveTheme(settings) {
    const s = settings || {};
    const mode = s.theme_mode || '';
    if (mode === 'system') {
      return (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)
        ? 'dark'
        : 'light';
    }
    if (THEMES.includes(mode)) return mode;
    return THEMES.includes(s.theme) ? s.theme : 'light';
  }

  window.Theme = {
    THEMES,
    PALETTES,
    hexToRgb,
    isDark,
    resolveTheme,
    coverUrl,
    effectiveCoverColors,

    /** 应用主题到 <html data-theme>，并把用户自定义颜色叠加覆盖到 CSS 变量上。
     *  custom 为空串的项保持“跟随主题”；文字色还会派生 muted/border 等半透明变量。
     *  theme_mode=system 时忽略传入的 theme，按系统深浅色解析。 */
    applyTheme(theme, custom) {
      const c = custom || {};
      const resolved = resolveTheme(c);
      document.documentElement.dataset.theme = resolved;
      const rs = document.documentElement.style;

      const bg = c.custom_bg || null;
      if (bg) {
        rs.setProperty('--reader-bg', bg);
        rs.setProperty('--base-100', bg);
        // 参考 Readest 的明暗面生成：由背景色派生卡片/工具栏层次，避免同色无层次
        rs.setProperty('--base-200', shade(bg, 0.035));
        rs.setProperty('--base-300', shade(bg, 0.07));
      } else {
        ['--reader-bg', '--base-100', '--base-200', '--base-300']
          .forEach((k) => rs.setProperty(k, null));
      }

      const primary = c.custom_primary || null;
      if (primary) {
        rs.setProperty('--primary', primary);
        rs.setProperty('--secondary', primary);
        rs.setProperty('--primary-content', contentColor(primary));
        rs.setProperty('--secondary-content', contentColor(primary));
      } else {
        ['--primary', '--secondary', '--primary-content', '--secondary-content']
          .forEach((k) => rs.setProperty(k, null));
      }
      const accent = c.custom_accent || primary;
      if (accent) {
        rs.setProperty('--accent', accent);
        rs.setProperty('--accent-content', contentColor(accent));
      } else {
        ['--accent', '--accent-content'].forEach((k) => rs.setProperty(k, null));
      }

      const rgb = hexToRgb(c.custom_text);
      if (rgb) {
        rs.setProperty('--reader-fg', c.custom_text);
        rs.setProperty('--base-content', c.custom_text);
        rs.setProperty('--muted', `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, 0.55)`);
        rs.setProperty('--neutral-content', `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, 0.55)`);
        rs.setProperty('--border', `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, 0.14)`);
      } else {
        ['--reader-fg', '--base-content', '--muted', '--neutral-content', '--border']
          .forEach((k) => rs.setProperty(k, null));
      }
    },

    /** 直接按完整设置对象应用主题（含跟随系统解析）。 */
    applySettings(settings) {
      this.applyTheme(settings.theme, settings);
    },

    /** 应用字号/行高到阅读器根节点（穿透 shadow 的变量） */
    applyReaderPrefs(fontSize, lineHeight) {
      const root = document.getElementById('reader-root');
      if (!root) return;
      root.style.setProperty('--reader-font-size', fontSize + 'px');
      root.style.setProperty('--reader-line-height', String(lineHeight));
    },

    /** 主题循环切换：light → sepia → dark → light */
    nextTheme(current) {
      const i = THEMES.indexOf(current);
      return THEMES[(i + 1) % THEMES.length];
    },
  };
})();
