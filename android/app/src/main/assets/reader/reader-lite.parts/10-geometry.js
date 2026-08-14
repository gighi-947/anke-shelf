  /* ---------- paging geometry (mirrors desktop paged.js) ---------- */
  function contentWidth(fw, pageWidth, fontSize) {
    var maxW = Math.round(46 * (pageWidth || 1) * (fontSize || 18));
    return Math.max(120, Math.min(fw, maxW));
  }

  function shouldAutoDual(fw, fh) {
    if (fw < 800 || fw <= fh) return false;
    var aspect = fw / fh;
    return aspect >= 1.2 && aspect <= 2.6;
  }

  function isDual(paged, dualPage, autoDual, fw, fh) {
    if (!paged) return false;
    if (dualPage) return true;
    if (autoDual === false) return false;
    return shouldAutoDual(fw, fh);
  }

  function geometry(fw, fh, s) {
    var st = s || state;
    var dual = isDual(st.paged, st.dualPage, st.autoDual, fw, fh);
    var M = clamp(st.margin || 40, 8, 160);
    var G = clamp(st.gap || 28, 8, 120);
    var P = Math.max(4, Math.min(M, G - 8));
    var colW = dual
      ? Math.max(120, (fw - 2 * P - G) / 2)
      : Math.max(120, fw - 2 * P);
    if (dual && colW < 300) {
      dual = false;
      colW = Math.max(120, fw - 2 * P);
    }
    return {
      dual: dual,
      colW: colW,
      advance: colW + G,
      margin: P,
      paddingRight: P,
      gap: G,
      contentWidth: fw,
    };
  }

