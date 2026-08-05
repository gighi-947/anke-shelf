"""EPUB 解析器 —— 纯标准库实现（zipfile + xml.etree）。

解析流水线（参考 Readest libs/document.ts 的架构思路）：
zip 探测 → DRM 检查 → container.xml → OPF（metadata/manifest/spine）
→ href 归一化 → 目录（EPUB3 nav 优先，NCX 兜底，无则扁平）→ 封面。

容错设计：
- zip 头 3 字节 PK\\x03 即视为 zip（第 4 字节不校验），异常文件头用
  尾部 EOCD 签名 PK\\x05\\x06 兜底（zip 官方定位方式）
- 条目名精确匹配优先，小写不敏感兜底（小写名冲突视为不可用）
- 非 UTF-8 文件名用 surrogateescape → GBK 尽力还原（老中文书常见）
"""
import hashlib
import posixpath
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

try:
    import xml.etree.ElementTree as ET
except ImportError:  # pragma: no cover
    raise

# 命名空间（EPUB 规范）
NS_CNT = "urn:oasis:names:tc:opendocument:xmlns:container"
NS_OPF = "http://www.idpf.org/2007/opf"
NS_DC = "http://purl.org/dc/elements/1.1/"
NS_EPUB = "http://www.idpf.org/2007/ops"
NS_NCX = "http://www.daisy.org/z3986/2005/ncx/"
NS_XHTML = "http://www.w3.org/1999/xhtml"

MIMETYPE_OPF = "application/oebps-package+xml"
MIMETYPE_NCX = "application/x-dtbncx+xml"
MIMETYPE_XHTML = "application/xhtml+xml"

_ZIP_MAGIC = b"PK\x03"
_EOCD_SIG = b"PK\x05\x06"
_EOCD_SEARCH_SIZE = 64 * 1024 + 22  # EOCD 至少 22 字节 + 尾部注释最大 64K


class EpubError(Exception):
    """统一解析异常，message 为面向用户的中文说明。"""


def is_zip_file(path: str) -> bool:
    """判断文件是否为可用 zip（EPUB 即 zip 容器）。

    规则（Readest 同款算法）：
    1. 文件头 3 字节为 PK\\x03 即视为 zip —— 部分非规范写入器会产出
       PK\\x03\\x02 之类的变体字节，不 gate 第 4 字节；
    2. 否则扫描文件尾部 64K+22 字节找 EOCD 签名 PK\\x05\\x06（zip 格式
       官方定位方式 —— 文件头之前的内容允许是任意数据，例如被网盘
       污染的前几个字节）。
    """
    try:
        with open(path, "rb") as f:
            head = f.read(3)
            if head[:2] == _ZIP_MAGIC[:2] and len(head) == 3 and head[2] == 0x03:
                return True
            # 尾部 EOCD 兜底
            f.seek(0, 2)
            size = f.tell()
            if size < 22:
                return False
            n = min(_EOCD_SEARCH_SIZE, size)
            f.seek(size - n)
            tail = f.read(n)
            return tail.find(_EOCD_SIG) != -1
    except OSError:
        return False


@dataclass
class SpineItem:
    """spine 中的一个章节项。href 为归一化后的 zip 内 POSIX 绝对路径。"""

    index: int
    idref: str
    href: str
    linear: bool = True
    media_type: str = ""


@dataclass
class TocEntry:
    """目录项，支持嵌套。href 可含 #fragment。"""

    label: str
    href: str
    spine_index: Optional[int] = None
    children: list = field(default_factory=list)


def _q(local: str, ns: str) -> str:
    """构造带命名空间的标签名。"""
    return f"{{{ns}}}{local}"


def decode_text(data: bytes) -> str:
    """按 BOM → XML 声明 encoding → UTF-8 → GBK 兜底的顺序解码文本。"""
    if data.startswith(b"\xef\xbb\xbf"):
        return data[3:].decode("utf-8", errors="replace")
    # 尝试从 XML 声明中提取编码（GBK/GB2312 老书常见）
    m = re.match(rb'^\s*<\?xml[^>]*encoding=["\']([A-Za-z0-9._-]+)["\']', data[:200])
    if m:
        try:
            return data.decode(m.group(1).decode("ascii"), errors="replace")
        except (LookupError, UnicodeDecodeError):
            pass
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("gbk", errors="replace")


def _recover_name(name: str) -> str:
    """尽力还原 zip 内非 UTF-8 文件名（zipfile 会返回含 surrogate 的 str）。"""
    if any(0xD800 <= ord(c) <= 0xDFFF for c in name):
        try:
            return name.encode("latin-1", "surrogateescape").decode("gbk")
        except (UnicodeDecodeError, UnicodeEncodeError):
            pass
    return name


def _norm_href(href: str, base_dir: str) -> str:
    """把 OPF/目录中的 href 归一化为 zip 内 POSIX 绝对路径。

    - 相对路径 → 相对 base_dir 解析
    - 以 / 开头（野书）→ 剥掉前导斜杠
    - 带 #fragment → 切掉（返回纯路径）
    """
    href = href.split("#", 1)[0]
    if not href:
        return ""
    href = href.lstrip("/")
    href = href.replace("\\", "/")  # 野书可能用反斜杠
    return posixpath.normpath(posixpath.join(base_dir, href))


class EpubBook:
    """一本已解析的电子书。线程安全只读；多个读者线程可共享。"""

    def __init__(self, path: str):
        self.path = os_path = str(path)
        self.id = hashlib.md5(os_path.encode("utf-8")).hexdigest()
        self.title = ""
        self.author = ""
        self.language = ""
        self.publisher = ""
        self.description = ""
        self.isbn = ""
        self.chapters: list[SpineItem] = []
        self.toc: list[TocEntry] = []
        self.toc_map: dict[str, str] = {}  # href(无 fragment) → 目录标题
        self.cover_href: Optional[str] = None  # 归一化 zip 路径
        self._zip: Optional[zipfile.ZipFile] = None
        self._entries: dict[str, zipfile.ZipInfo] = {}
        self._entries_lower: dict[str, Optional[zipfile.ZipInfo]] = {}

    # ---------- 解析流水线 ----------

    def open(self) -> "EpubBook":
        if not is_zip_file(self.path):
            raise EpubError("不是有效的 EPUB 文件（无法识别为 zip 容器）")
        try:
            self._zip = zipfile.ZipFile(self.path)
        except (zipfile.BadZipFile, OSError) as e:
            raise EpubError(f"损坏的 EPUB 文件：{e}") from e

        try:
            # 0. 条目名映射（最先建立，供后续所有读取使用）
            self._build_entry_map()

            # DRM 检查
            if self._has_entry("META-INF/encryption.xml"):
                raise EpubError("此文件受 DRM 加密保护，无法阅读")

            # 1. container.xml → OPF 路径
            opf_path = self._find_opf()
            if not opf_path:
                raise EpubError("损坏的 EPUB：缺少 container.xml 或 OPF 包文件")

            # 2. 解析 OPF
            opf_bytes = self.read_file(opf_path)
            if opf_bytes is None:
                raise EpubError("损坏的 EPUB：无法读取 OPF 文件")
            root = self._parse_xml(decode_text(opf_bytes), "OPF 文件")
            self._parse_metadata(root, opf_path)
            self._parse_manifest_spine(root, opf_path)

            # 3. 目录
            self._parse_toc(root, opf_path)

            # 4. 封面
            self._find_cover(root, opf_path)
            return self
        except EpubError:
            self.close()
            raise
        except Exception as e:
            self.close()
            raise EpubError(f"解析 EPUB 失败：{e}") from e

    def _parse_xml(self, text: str, what: str) -> ET.Element:
        try:
            return ET.fromstring(text)
        except ET.ParseError as e:
            raise EpubError(f"解析失败（{what}）：{e}") from e

    def _find_opf(self) -> Optional[str]:
        cnt = self.read_file("META-INF/container.xml")
        if cnt is None:
            return None
        root = self._parse_xml(decode_text(cnt), "container.xml")
        for rf in root.iter(_q("rootfile", NS_CNT)):
            if rf.get("media-type") == MIMETYPE_OPF:
                p = rf.get("full-path") or ""
                return _norm_href(p, "")
        return None

    def _parse_metadata(self, root: ET.Element, opf_path: str) -> None:
        md = root.find(_q("metadata", NS_OPF))
        if md is None:
            return
        title = md.find(_q("title", NS_DC))
        creator = md.find(_q("creator", NS_DC))
        lang = md.find(_q("language", NS_DC))
        pub = md.find(_q("publisher", NS_DC))
        desc = md.find(_q("description", NS_DC))
        ident = md.find(_q("identifier", NS_DC))
        self.title = (title.text or "").strip() if title is not None else ""
        self.author = (creator.text or "").strip() if creator is not None else ""
        self.language = (lang.text or "").strip() if lang is not None else ""
        self.publisher = (pub.text or "").strip() if pub is not None else ""
        self.description = (desc.text or "").strip() if desc is not None else ""
        if ident is not None and ident.text:
            t = ident.get("{%s}scheme" % NS_OPF, "").upper()
            if t in ("ISBN", "URI", ""):
                self.isbn = ident.text.strip()
        if not self.title:
            self.title = Path(self.path).stem

    def _parse_manifest_spine(self, root: ET.Element, opf_path: str) -> None:
        opf_dir = posixpath.dirname(opf_path)
        manifest = root.find(_q("manifest", NS_OPF))
        spine = root.find(_q("spine", NS_OPF))
        if manifest is None or spine is None:
            raise EpubError("损坏的 EPUB：缺少 manifest 或 spine")
        # manifest: id → (href, media-type)
        items: dict[str, tuple[str, str]] = {}
        nav_href: Optional[str] = None
        for it in manifest.iter(_q("item", NS_OPF)):
            iid = it.get("id", "")
            href = it.get("href", "")
            mime = it.get("media-type", "")
            if not iid or not href:
                continue
            nh = _norm_href(href, opf_dir)
            items[iid] = (nh, mime)
            props = it.get("properties", "").split()
            if "nav" in props and nav_href is None:
                nav_href = nh
        # spine: 保序章节列表
        idx = 0
        seen_hrefs: set[str] = set()
        for ir in spine.iter(_q("itemref", NS_OPF)):
            idref = ir.get("idref", "")
            item = items.get(idref)
            if item is None:
                continue
            href, mime = item
            if not href or href in seen_hrefs:
                continue
            self.chapters.append(
                SpineItem(
                    index=idx,
                    idref=idref,
                    href=href,
                    linear=(ir.get("linear", "yes").lower() != "no"),
                    media_type=mime,
                )
            )
            idx += 1
            seen_hrefs.add(href)
        # 记录 nav 条目（目录解析用）
        self._nav_href = nav_href
        self._manifest_items = items

    def _parse_toc(self, root: ET.Element, opf_path: str) -> None:
        """目录：EPUB3 nav 优先 → EPUB2 NCX 兜底 → 扁平章节列表。"""
        opf_dir = posixpath.dirname(opf_path)
        entries: Optional[list[TocEntry]] = None

        # EPUB3 nav：取 properties="nav" 条目，或 xhtml 且文件名含 nav
        nav_href = getattr(self, "_nav_href", None)
        if nav_href is None:
            for href, mime in self._manifest_items.values():
                base = posixpath.basename(href).lower()
                if mime == MIMETYPE_XHTML and "nav" in base:
                    nav_href = href
                    break
        if nav_href:
            data = self.read_file(nav_href)
            if data is not None:
                nav_root = self._parse_xml(decode_text(data), "nav 文档")
                nav = nav_root.find(f".//{_q('nav', NS_XHTML)}[@{{{NS_EPUB}}}type='toc']")
                if nav is not None:
                    nav_ol = nav.find(_q("ol", NS_XHTML))
                    if nav_ol is not None:
                        entries = self._parse_nav_ol(nav_ol, posixpath.dirname(nav_href))

        # NCX 兜底
        if entries is None:
            ncx_href = None
            for href, mime in self._manifest_items.values():
                if mime == MIMETYPE_NCX:
                    ncx_href = href
                    break
            if ncx_href is not None:
                data = self.read_file(ncx_href)
                if data is not None:
                    ncx_root = self._parse_xml(decode_text(data), "NCX 文件")
                    nm = ncx_root.find(_q("navMap", NS_NCX))
                    if nm is not None:
                        entries = self._parse_ncx_points(nm, posixpath.dirname(ncx_href))

        # 都没有 → 扁平目录
        if entries is None:
            entries = [
                TocEntry(
                    label=self.toc_map.get(c.href) or f"第 {i + 1} 章",
                    href=c.href,
                    spine_index=i,
                )
                for i, c in enumerate(self.chapters)
            ]

        self.toc = entries
        # 建立 href → 目录标题映射
        def walk(es: list[TocEntry]) -> None:
            for e in es:
                self.toc_map.setdefault(e.href.split("#", 1)[0], e.label)
                walk(e.children)

        walk(entries)

    def _parse_nav_ol(self, ol: ET.Element, base_dir: str) -> list[TocEntry]:
        out: list[TocEntry] = []
        # 只取直接子 li；嵌套 ol 由递归处理，避免深层条目被重复解析。
        for li in ol.findall(_q("li", NS_XHTML)):
            a = li.find(_q("a", NS_XHTML))
            if a is None:
                # 无链接的 li：尝试递归其内部 ol
                sub_ol = li.find(_q("ol", NS_XHTML))
                if sub_ol is not None:
                    out.extend(self._parse_nav_ol(sub_ol, base_dir))
                continue
            href = _norm_href(a.get("href", ""), base_dir)
            label = "".join(a.itertext()).strip() or "(无标题)"
            child_ol = li.find(_q("ol", NS_XHTML))
            entry = TocEntry(label=label, href=href or "#", spine_index=None)
            if child_ol is not None:
                entry.children = self._parse_nav_ol(child_ol, base_dir)
            out.append(entry)
        return out

    def _parse_ncx_points(self, parent: ET.Element, base_dir: str) -> list[TocEntry]:
        """递归解析 NCX navPoint。findall 只匹配直接子元素，天然分层。"""
        out: list[TocEntry] = []
        for np in parent.findall(_q("navPoint", NS_NCX)):
            nl = np.find(f"{_q('navLabel', NS_NCX)}/{_q('text', NS_NCX)}")
            content = np.find(_q("content", NS_NCX))
            href = _norm_href(content.get("src", "") if content is not None else "", base_dir)
            label = (nl.text or "").strip() if nl is not None else ""
            entry = TocEntry(label=label or "(无标题)", href=href or "#", spine_index=None)
            children = np.findall(_q("navPoint", NS_NCX))
            if children:
                entry.children = self._parse_ncx_points(np, base_dir)
            out.append(entry)
        return out

    # ---------- 封面 ----------

    def _find_cover(self, root: ET.Element, opf_path: str) -> None:
        opf_dir = posixpath.dirname(opf_path)
        md = root.find(_q("metadata", NS_OPF))
        if md is not None:
            # EPUB2: meta[name="cover"] content 是 manifest id
            for meta in md.iter(_q("meta", NS_OPF)):
                if meta.get("name") == "cover" and meta.get("content"):
                    href, _ = self._manifest_items.get(meta.get("content"), ("", ""))
                    if href:
                        self.cover_href = href
                        return
            # EPUB3: meta[property="cover-image"] content 是 id 或 href
            for meta in md.iter(_q("meta", NS_OPF)):
                if meta.get("property", "").lower() in ("cover-image", "cover"):
                    val = meta.get("content", "")
                    if val in self._manifest_items:
                        self.cover_href = self._manifest_items[val][0]
                        return
                    # 直接给 href 的野书
                    nh = _norm_href(val, opf_dir)
                    if nh and self._has_entry(nh):
                        self.cover_href = nh
                        return
        # guide/reference[type="cover"]
        guide = root.find(_q("guide", NS_OPF))
        if guide is not None:
            for ref in guide.iter(_q("reference", NS_OPF)):
                if ref.get("type") == "cover":
                    nh = _norm_href(ref.get("href", ""), opf_dir)
                    if nh and self._has_entry(nh):
                        self.cover_href = nh
                        return
        # 兜底：manifest 中基名以 cover 开头的图片
        for href, mime in self._manifest_items.values():
            if mime.startswith("image/") and posixpath.basename(href).lower().startswith("cover"):
                self.cover_href = href
                return

    def get_cover_bytes(self) -> Optional[bytes]:
        if not self.cover_href:
            return None
        return self.read_file(self.cover_href)

    # ---------- 资源读取 ----------

    def _build_entry_map(self) -> None:
        """精确名 + 小写不敏感双映射（复刻 Readest lowercaseMap 语义）。"""
        self._entries = {}
        self._entries_lower = {}
        for info in self._zip.infolist():
            name = _recover_name(info.filename)
            self._entries[name] = info
            low = name.lower()
            existing = self._entries_lower.get(low)
            if existing is not None and existing.filename != info.filename:
                # 小写名冲突：标记为不可用（None），宁可 404
                self._entries_lower[low] = None
            else:
                self._entries_lower[low] = info

    def _has_entry(self, zip_path: str) -> bool:
        return zip_path in self._entries or zip_path.lower() in self._entries_lower

    def read_file(self, zip_path: str) -> Optional[bytes]:
        """读取 zip 内文件字节。精确名优先，小写兜底；不存在/已关闭返回 None。"""
        info = self._entries.get(zip_path) or self._entries_lower.get(zip_path.lower())
        if info is None or self._zip is None:
            return None
        try:
            return self._zip.read(info)
        except (zipfile.BadZipFile, RuntimeError, KeyError):
            return None

    def chapter_text(self, index: int) -> Optional[str]:
        """返回章节解码后的 HTML 文本（供搜索等用途）。"""
        if not 0 <= index < len(self.chapters):
            return None
        data = self.read_file(self.chapters[index].href)
        if data is None:
            return None
        return decode_text(data)

    def chapter_title(self, index: int) -> str:
        """章节显示标题：目录映射优先，兜底『第 N 章』。"""
        if not 0 <= index < len(self.chapters):
            return ""
        t = self.toc_map.get(self.chapters[index].href)
        return t or f"第 {index + 1} 章"

    def toc_spine_index(self, href: str) -> Optional[int]:
        """目录 href → spine 索引（无 fragment 比较）。"""
        h = href.split("#", 1)[0]
        for i, c in enumerate(self.chapters):
            if c.href == h:
                return i
        return None

    def close(self) -> None:
        """释放 zip 文件句柄（Windows 下删除文件前必须先关闭）。"""
        if self._zip is not None:
            try:
                self._zip.close()
            except OSError:
                pass
            self._zip = None
