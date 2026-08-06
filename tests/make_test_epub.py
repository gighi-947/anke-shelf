"""生成测试用最小合法 EPUB 样本（纯标准库，无外部图片依赖）。

用法：python -m tests.make_test_epub [输出目录]
四种模式：
- nav3    标准 EPUB3（nav + NCX 双目录、中文+英文、图片、外链 CSS、封面）
- ncx2    仅 NCX 的 EPUB2（测目录兜底）
- corrupt 文件头字节被污染但尾部 EOCD 完整（测 zip 探测兜底）
- case    OPF href 与实际条目大小写不一致（测小写映射）
- bad     彻底损坏的文件（测拒绝）
"""
import struct
import sys
import zlib
from pathlib import Path

import zipfile

# ---------------------------------------------------------------- PNG 生成

def _png_chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def make_png(width: int, height: int, rgb: tuple) -> bytes:
    """生成纯色 PNG（RGB, 8bit, 逐行）。"""
    row = b"\x00" + bytes(rgb) * width
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", zlib.compress(row * height))
        + _png_chunk(b"IEND", b"")
    )


# 封面 100x140 蓝紫，正文插图 240x160 蓝
COVER_PNG = make_png(100, 140, (90, 90, 200))
PIC_PNG = make_png(240, 160, (70, 140, 210))

# ---------------------------------------------------------------- 内容模板

TOC_NAV_XHTML = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>目录</title></head>
<body>
<nav epub:type="toc" id="toc">
  <h1>目录</h1>
  <ol>
    <li><a href="ch01.xhtml">第一章 起航</a>
      <ol>
        <li><a href="ch01.xhtml#sec1">1.1 引力波的发现</a></li>
        <li><a href="ch01.xhtml#sec2">1.2 The quick brown fox</a></li>
      </ol>
    </li>
    <li><a href="ch02.xhtml">第二章 深海</a></li>
    <li><a href="ch03.xhtml">第三章 黎明</a></li>
    <li><a href="ch04.xhtml">第四章 归途</a></li>
    <li><a href="ch05.xhtml">第五章 尾声</a></li>
  </ol>
</nav>
</body>
</html>
"""

TOC_NCX = """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:test:book"/>
    <meta name="dtb:depth" content="2"/>
  </head>
  <docTitle><text>测试书</text></docTitle>
  <navMap>
    <navPoint id="np1" playOrder="1">
      <navLabel><text>第一章 起航</text></navLabel>
      <content src="ch01.xhtml"/>
      <navPoint id="np1-1" playOrder="2">
        <navLabel><text>1.1 引力波的发现</text></navLabel>
        <content src="ch01.xhtml#sec1"/>
      </navPoint>
    </navPoint>
    <navPoint id="np2" playOrder="3">
      <navLabel><text>第二章 深海</text></navLabel>
      <content src="ch02.xhtml"/>
    </navPoint>
    <navPoint id="np3" playOrder="4">
      <navLabel><text>第三章 黎明</text></navLabel>
      <content src="ch03.xhtml"/>
    </navPoint>
  </navMap>
</ncx>
"""

CSS = """body { font-family: serif; }
p { text-indent: 2em; margin: 0.6em 0; }
.quote { font-style: italic; }
img { max-width: 90%; }
"""


def _chapter(n: int, title: str, extra_body: str = "") -> str:
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>{title}</title>
  <link rel="stylesheet" type="text/css" href="css/style.css"/>
</head>
<body>
  <h1>{title}</h1>
  <p>这是第 {n} 章的内容。引力波在宇宙中传播，The quick brown fox jumps over the lazy dog。</p>
  <p>中文段落用于搜索测试：引力波探测器在清晨捕捉到了信号。</p>
  {extra_body}
</body>
</html>
"""


def _opf(book_id: str, title: str, author: str, manifest: str, spine: str, guide: str = "") -> str:
    return f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">{book_id}</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:creator>{author}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
    {guide}
  </metadata>
  <manifest>
    {manifest}
  </manifest>
  <spine toc="ncx">
    {spine}
  </spine>
</package>
"""


def _container() -> str:
    return """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""


# ---------------------------------------------------------------- 四种样本

def _write_book(zf: zipfile.ZipFile, chapters: list[tuple[str, str, str]]) -> None:
    """chapters: [(文件名, 标题, 额外 body)]"""
    zf.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    zf.writestr("META-INF/container.xml", _container())
    for fname, title, extra in chapters:
        zf.writestr(f"OEBPS/{fname}", _chapter(len(chapters), title, extra))


def build_nav3(path: Path) -> None:
    """标准 EPUB3：nav + NCX 双目录 + 图片 + 外链 CSS + 封面。"""
    zf = zipfile.ZipFile(path, "w")
    zf.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    zf.writestr("META-INF/container.xml", _container())
    zf.writestr("OEBPS/css/style.css", CSS)
    zf.writestr("OEBPS/images/pic.png", PIC_PNG)
    zf.writestr("OEBPS/cover.png", COVER_PNG)
    # 长章：多页内容 + 代码块（分页/标注/偏移测试用）
    long_chapter = "".join(
        f"<p>黎明前的第 {i} 个观测窗口，探测器记录下了稳定的信号序列。"
        "The quick brown fox jumps over the lazy dog。引力波源位于遥远的星系中心。"
        "数据分析需要反复比对噪声模型，信号与噪声的比值决定了最终置信度。</p>"
        for i in range(1, 40)
    ) + (
        "<pre><code class=\"language-python\">def detect(signal, noise):\n"
        "    \"\"\"返回信噪比。\"\"\"\n"
        "    return max(signal) / (noise.mean() + 1e-9)\n"
        "\n"
        "if __name__ == '__main__':\n"
        "    print(detect([0.1, 0.9], [0.01, 0.02]))</code></pre>"
    )
    chapters = [
        ("ch01.xhtml", "第一章 起航", '<img src="images/pic.png" alt="插图"/><p class="quote">引力波（引力波）是时空涟漪。</p>'),
        ("ch02.xhtml", "第二章 深海", ""),
        ("ch03.xhtml", "第三章 黎明", long_chapter),
        ("ch04.xhtml", "第四章 归途", ""),
        ("ch05.xhtml", "第五章 尾声", ""),
    ]
    for i, (fname, title, extra) in enumerate(chapters, 1):
        zf.writestr(f"OEBPS/{fname}", _chapter(i, title, extra))
    zf.writestr("OEBPS/nav.xhtml", TOC_NAV_XHTML)
    zf.writestr("OEBPS/toc.ncx", TOC_NCX)
    zf.writestr(
        "OEBPS/content.opf",
        _opf(
            "urn:test:nav3",
            "测试书：引力波之旅",
            "测试作者",
            manifest=(
                '<item id="css" href="css/style.css" media-type="text/css"/>'
                '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
                '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
                '<item id="pic" href="images/pic.png" media-type="image/png"/>'
                '<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>'
                + "".join(
                    f'<item id="ch{i}" href="ch{i:02d}.xhtml" media-type="application/xhtml+xml"/>'
                    for i in range(1, 6)
                )
            ),
            spine="".join(
                f'<itemref idref="ch{i}"/>' for i in range(1, 6)
            ),
            guide='<meta property="cover-image" content="cover"/>',
        ),
    )
    zf.close()


def build_ncx2(path: Path) -> None:
    """EPUB2：仅 NCX 目录（无 nav），测兜底路径。"""
    zf = zipfile.ZipFile(path, "w")
    zf.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    zf.writestr("META-INF/container.xml", _container())
    zf.writestr("OEBPS/toc.ncx", TOC_NCX)
    chapters = [
        ("c1.xhtml", "第一章 起航", ""),
        ("c2.xhtml", "第二章 深海", ""),
        ("c3.xhtml", "第三章 黎明", ""),
        ("c4.xhtml", "第四章 归途", ""),
    ]
    for i, (fname, title, extra) in enumerate(chapters, 1):
        zf.writestr(f"OEBPS/{fname}", _chapter(i, title, extra))
    zf.writestr(
        "OEBPS/content.opf",
        _opf(
            "urn:test:ncx2",
            "旧式测试书",
            "老作者",
            manifest=(
                '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
                + "".join(
                    f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>'
                    for i in range(1, 5)
                )
            ),
            spine="".join(f'<itemref idref="c{i}"/>' for i in range(1, 5)),
        ),
    )
    zf.close()


def build_corrupt(path: Path) -> None:
    """正常书但文件头 4 字节被污染（模拟网盘/传输损坏），EOCD 完整。"""
    build_nav3(path)
    with open(path, "r+b") as f:
        f.seek(0)
        f.write(b"\x00\x01\x02\x03")


def build_case(path: Path) -> None:
    """OPF href 与 zip 条目名大小写不一致（Windows 大小写错配的野书）。"""
    zf = zipfile.ZipFile(path, "w")
    zf.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    zf.writestr("META-INF/container.xml", _container())
    # 条目名全小写，OPF 里写大小写混合
    zf.writestr("OEBPS/ChapterOne.XHTML", _chapter(1, "大小写测试", ""))
    zf.writestr(
        "OEBPS/content.opf",
        _opf(
            "urn:test:case",
            "大小写测试书",
            "",
            manifest='<item id="c1" href="chapterone.xhtml" media-type="application/xhtml+xml"/>',
            spine='<itemref idref="c1"/>',
        ),
    )
    zf.close()


def build_bad(path: Path) -> None:
    """彻底损坏的文件（随机字节），测拒绝。"""
    with open(path, "wb") as f:
        f.write(bytes(range(256)) * 8)


def build_all(out_dir: Path) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    files = {
        "sample_nav3.epub": build_nav3,
        "sample_ncx2.epub": build_ncx2,
        "sample_corrupt.epub": build_corrupt,
        "sample_case.epub": build_case,
        "sample_bad.epub": build_bad,
    }
    paths = []
    for name, fn in files.items():
        p = out_dir / name
        fn(p)
        paths.append(p)
    return paths


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("tests/sample")
    for p in build_all(out):
        print(f"生成: {p} ({p.stat().st_size} 字节)")
