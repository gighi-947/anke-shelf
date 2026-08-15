"""骨碌碌 EPUB 的内嵌阅读样式。"""


GULULU_EPUB_CSS = """body { line-height:1.8; margin:0 1em; }
.book-meta { color:#777; font-size:.9em; margin:0 0 1.2em; }
.chapter-title { font-size:1.5em; margin:.5em 0 1em; }
.gululu-floor { margin:0 0 1.5em; }
.floor-head { border-bottom:1px solid #aaa; font-size:.86em; padding:.35em 0;
  margin:0 0 .8em; break-after:avoid; }
.floor-number { font-weight:700; }
.floor-title { margin-left:.6em; }
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
