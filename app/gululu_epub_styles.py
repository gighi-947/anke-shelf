"""骨碌碌 EPUB 的内嵌阅读样式。"""


GULULU_EPUB_CSS = """body { line-height:1.8; margin:0 1em; }
.book-meta { color:#777; font-size:.9em; margin:0 0 1.2em; }
.chapter-title { font-size:1.5em; margin:.5em 0 1em; }
.gululu-floor { border:1px solid #e0e0e0; border-left:4px solid #6f8d87;
  box-sizing:border-box; padding:12px 14px; margin:14px 0; border-radius:2px; }
.floor-head { align-items:baseline; border-bottom:1px dotted #e0e0e0; color:#888;
  display:flex; font-size:.82em; gap:.55em; padding-bottom:6px; margin-bottom:8px;
  break-after:avoid; }
.floor-number { color:#6f8d87; font-weight:700; }
.floor-title { flex:1; min-width:0; overflow-wrap:anywhere; }
.floor-content p { margin:.65em 0; overflow-wrap:anywhere; }
.empty-paragraph { min-height:.6em; margin:.15em 0 !important; }
.gululu-image { margin:.8em 0; text-align:center; break-inside:avoid; }
.gululu-image img { max-width:100%; height:auto; object-fit:contain; }
.avatar-image img { max-width:min(100%, 24em); }
.gululu-music-row { break-inside:avoid; }
.gululu-music-cue { display:inline-flex; align-items:center; gap:.55em; max-width:100%;
  border:1px solid #aaa; border-radius:6px; background:transparent; color:inherit;
  padding:.45em .65em; cursor:pointer; font:inherit; text-align:left; }
.gululu-music-kind { color:#777; font-size:.78em; white-space:nowrap; }
.gululu-music-title { overflow-wrap:anywhere; }
.gululu-music-cue.playing { border-color:currentColor; }
.gululu-music-stop { display:inline-block; cursor:pointer; font-size:.75em; padding:.25em; }
.gululu-immersive-marker { display:block; height:0; overflow:hidden; }
.gululu-directive-error { border:1px dashed #aaa; color:#777; padding:.5em; }
.gululu-fold { border-left:3px solid #aaa; margin:.8em 0; padding:.2em 0 .2em .8em; }
.gululu-fold summary { cursor:pointer; font-weight:700; break-after:avoid; }
.gululu-dice-value, .gululu-dice-suffix { border-radius:3px; cursor:pointer; }
.gululu-dice-value { padding:0 .12em; }
.gululu-dice-value:focus-visible { outline:2px solid currentColor; outline-offset:2px; }
.gululu-dice-value.masked, .gululu-dice-suffix.masked {
  background:currentColor; color:transparent; text-shadow:none; user-select:none;
}
.gululu-dice-value.revealed, .gululu-dice-suffix.revealed { animation:g-dice-reveal .28s ease-out; }
.gululu-fog-block.gululu-fog-hidden { display:none; }
@keyframes g-dice-reveal { from { opacity:.2; } to { opacity:1; } }
.gululu-secret-cue, .gululu-clue-cue { border:1px solid #aaa; border-radius:6px;
  background:transparent; color:inherit; cursor:pointer; font:inherit; margin:.25em 0;
  max-width:100%; overflow-wrap:anywhere; padding:.45em .65em; }
.gululu-clue-cue { border-style:dashed; }
.gululu-jump-floor { display:inline-block; margin:.35em 0; }
.gululu-assistant-quote { display:block; border-left:3px solid #8aa09a; color:inherit;
  margin:.8em 0; padding:.45em .8em; text-decoration:none; }
.gululu-assistant-quote:hover { background:rgba(127,127,127,.08); }
.gululu-sensitive-unavailable { border:1px dashed #aaa; color:#777; padding:.5em; }
.gululu-comments { border-top:1px solid #bbb; margin:1em 0 0; padding-top:.5em; }
.gululu-comments > summary { cursor:pointer; font-weight:700; }
.gululu-comment-list { margin:.6em 0 0; }
.gululu-comment { border-left:2px solid #bbb; margin:.65em 0; padding:.15em 0 .15em .75em; }
.gululu-comment-head { align-items:baseline; display:flex; flex-wrap:wrap; gap:.5em; }
.gululu-comment-head > span { color:#777; font-size:.78em; }
.gululu-comment-text { margin:.25em 0; overflow-wrap:anywhere; }
.gululu-comment-replies { margin:.4em 0 0 .65em; }
.gululu-comment-reply { font-size:.92em; }
.comment-reply-user { color:#777; }
.unsupported-node, .image-omitted, .image-unavailable {
  border:1px dashed #aaa; color:#777; padding:.5em;
}
del { opacity:.72; }
"""
