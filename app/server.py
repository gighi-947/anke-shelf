"""本地 HTTP 服务器：服务前端静态资源与 zip 内书籍资源。

路由表：
  /                        302 → /index.html
  /index.html /css/* /js/* web/ 静态资源（max-age=3600）
  /book/<book_id>/<zip_path...>  zip 内按 POSIX 路径读字节（no-store）
  /cover/<book_id>         封面缓存文件（covers/<id>.<ext>），缺失 404
  /font/<kind>/<name>       自定义字体文件
  /img/<book_id>?u=<url>    NGA 图床图片代理（Referer/Cookie 防盗链，白名单）
  POST /api/<name>          JSON API（X-Anke-Token 校验），前端唯一业务入口

安全要点：
- 仅监听 127.0.0.1，随机端口（端口 0 由系统分配）
- /api/* 需要每次启动随机生成的令牌，防止其他网页调用本地服务
- unquote 解码后再校验；zip_path 拒绝反斜杠 / .. 段 / 绝对路径 / 超长
- 只允许命中 zip 条目名集合（精确 → 小写兜底），绝不拼到文件系统路径
- /img/* 只代理 NGA 图床白名单域名，禁任意 URL；未注册 book 返回 404
- 文本资源统一解码后以 UTF-8 输出；章节响应加 CSP 与 nosniff
- 图片缺失返回 1×1 透明 GIF，防章节内布局崩坏
"""
import base64
import http.server
import json
import logging
import posixpath
import re
import secrets
import threading
import urllib.error
import urllib.parse
import urllib.request
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Optional

from . import __version__
from .book_manager import BookManager
from .epub import decode_text
from .errors import ApiError
from .fonts import resolve_font_file
from .nga_config import DEFAULT_UA, load_nga_config

# 1×1 透明 GIF
_TRANSPARENT_GIF = base64.b64decode(
    "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
)

# 无封面时的平面骰子占位图（SVG，纯色无文字，按主题自适应）
def _dice_cover_svg(theme: str = "dark") -> str:
    t = (theme or "").lower()
    if t == "light":
        bg, fg = "#ffffff", "#171717"
    elif t == "sepia":
        bg, fg = "#f1e8d0", "#5b4636"
    else:
        bg, fg = "#222222", "#e0e0e0"
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="300" height="420" viewBox="0 0 300 420">
  <rect width="300" height="420" fill="{bg}"/>
  <rect x="90" y="150" width="120" height="120" rx="16" fill="none" stroke="{fg}" stroke-width="6"/>
  <circle cx="120" cy="180" r="9" fill="{fg}"/>
  <circle cx="180" cy="180" r="9" fill="{fg}"/>
  <circle cx="120" cy="240" r="9" fill="{fg}"/>
  <circle cx="180" cy="240" r="9" fill="{fg}"/>
  <circle cx="150" cy="210" r="9" fill="{fg}"/>
</svg>
"""

# 章节响应 CSP：禁止脚本；允许 https 图片/媒体（NGA 在线图片模式 EPUB 需要，
# 图片/音视频不会执行脚本，风险可控）
_CSP = (
    "default-src 'none'; "
    "img-src 'self' data: https:; "
    "style-src 'self' 'unsafe-inline'; "
    "font-src 'self' data:; "
    "media-src 'self' data: https:; "
    "script-src 'none'; object-src 'none'; base-uri 'none'"
)

_MIME_MAP = {
    ".xhtml": "application/xhtml+xml; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".htm": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript",
    ".json": "application/json",
    ".xml": "application/xml; charset=utf-8",
    ".ncx": "application/x-dtbncx+xml; charset=utf-8",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".avif": "image/avif",
    ".svg": "image/svg+xml",
    ".woff": "font/woff",
    ".woff2": "font/woff2",
    ".ttf": "font/ttf",
    ".otf": "font/otf",
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".mp4": "video/mp4",
    ".webm": "video/webm",
}

# 文本类扩展名：解码转 UTF-8 后输出
_TEXT_EXTS = {".xhtml", ".html", ".htm", ".css", ".xml", ".ncx", ".svg", ".js", ".json"}

# book_id 为 md5 十六进制
_BOOK_ID_RE = re.compile(r"^[0-9a-f]{32}$")

_MAX_ZIP_PATH = 1024
_MAX_API_BODY = 8 * 1024 * 1024
_API_TOKEN_HEADER = "X-Anke-Token"


def _mime_for(name: str) -> str:
    ext = posixpath.splitext(name)[1].lower()
    return _MIME_MAP.get(ext, "application/octet-stream")


def _inject_base(html: bytes, base_href: str) -> bytes:
    """在章节 HTML 的 <head>（无则 <html> 开头）注入 <base href>。

    已有 <base> 的章节不重复注入（保留原书的 base）。
    """
    if b"<base" in html.lower()[:2048]:
        return html
    # 自闭合写法：HTML 与 XHTML/XML 解析器均接受
    head_insert = f'<base href="{base_href}"/>'.encode("utf-8")
    marker = b"<head"
    idx = html.lower().find(marker)
    if idx != -1:
        # 定位到 <head...> 的 '>' 之后
        gt = html.find(b">", idx)
        if gt != -1:
            return html[: gt + 1] + head_insert + html[gt + 1 :]
    marker2 = b"<html"
    idx2 = html.lower().find(marker2)
    if idx2 != -1:
        gt2 = html.find(b">", idx2)
        if gt2 != -1:
            return html[: gt2 + 1] + head_insert + html[gt2 + 1 :]
    # 兜底：XML 声明/DOCTYPE 之后插入
    for decl in (b"<?xml", b"<!DOCTYPE"):
        i = html.lower().find(decl)
        if i != -1:
            gt = html.find(b">", i)
            if gt != -1:
                return html[: gt + 1] + head_insert + html[gt + 1 :]
    return head_insert + html


def _safe_zip_path(zip_path: str) -> Optional[str]:
    """校验并归一化 zip 内路径。非法返回 None。"""
    if not zip_path or len(zip_path) > _MAX_ZIP_PATH:
        return None
    if "\\" in zip_path:  # zip 规范只允许 /
        return None
    if zip_path.startswith("/"):  # 绝对路径拒绝
        return None
    norm = posixpath.normpath(zip_path)
    if norm.startswith("..") or "/.." in norm or ".." in norm.split("/"):
        return None
    return norm


# NGA 图床白名单（域名后缀匹配；表情图 img4.nga.178.com 等均覆盖）
_NGA_IMAGE_HOST_SUFFIXES = ("nga.178.com", "nga.cn", "ngabbs.com")
_IMG_ATTR_RE = re.compile(r'(?i)\b(src|poster)=(["\'])(https?://[^"\']+)\2')


def _is_nga_image_url(url: str) -> bool:
    """仅接受 http/https 且主机命中 NGA 图床白名单的 URL。"""
    try:
        p = urllib.parse.urlparse(url)
    except ValueError:
        return False
    if p.scheme not in ("http", "https") or not p.hostname:
        return False
    host = p.hostname.lower()
    return any(host == s or host.endswith("." + s) for s in _NGA_IMAGE_HOST_SUFFIXES)


def _rewrite_nga_image_src(html: str, book_id: str) -> str:
    """把章节 HTML 中 NGA 图床图片的 src/poster 改写为本地 /img 代理 URL。

    只改属性值，不产生/删除文本节点，text_offset 保持稳定。
    """
    def repl(m):
        attr, quote, url = m.group(1), m.group(2), m.group(3)
        if not _is_nga_image_url(url):
            return m.group(0)
        # 官方表情图走直连；若失败由前端降级为文字表情。
        if "/ngabbs/post/smile/" in url:
            return m.group(0)
        proxy = f"/img/{book_id}?u={urllib.parse.quote(url, safe='')}"
        return f"{attr}={quote}{proxy}{quote}"
    return _IMG_ATTR_RE.sub(repl, html)


def _fetch_url(url: str, headers: dict) -> tuple[bytes, str]:
    """代理拉取图片；返回 (字节, mime)。供测试替换。

    优先使用系统 curl：NGA 图床的 TencentEdgeOne 会对 Python urllib 的
    TLS 指纹返回 567 拦截页，而 curl 可正常拿到图片。curl 不可用时回退 urllib。
    """
    curl = shutil.which("curl")
    if curl:
        tmp = tempfile.NamedTemporaryFile(delete=False)
        tmp_path = tmp.name
        tmp.close()
        cmd = [
            curl,
            "-sS",
            "--fail",
            "--max-time",
            "15",
            "-A",
            headers.get("User-Agent", ""),
            "-e",
            headers.get("Referer", ""),
            "-H",
            f"Cookie: {headers.get('Cookie', '')}",
            "-o",
            tmp_path,
            "-w",
            "%{content_type}",
            url,
        ]
        try:
            proc = subprocess.run(cmd, capture_output=True, timeout=20)
            if proc.returncode == 0:
                data = Path(tmp_path).read_bytes()
                mime = proc.stdout.decode("ascii", "replace").strip()
                return data, mime or "application/octet-stream"
        except (OSError, subprocess.SubprocessError):
            pass
        finally:
            try:
                Path(tmp_path).unlink(missing_ok=True)
            except OSError:
                pass

    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        mime = resp.headers.get_content_type() or "image/jpeg"
        return resp.read(), mime


class EpubHandler(http.server.BaseHTTPRequestHandler):
    server_version = f"AnkeShelf/{__version__}"

    # 由 start_server 注入
    web_dir: Path = None
    books: BookManager = None
    covers_dir: Path = None
    api: object = None
    token: str = None

    # ---------- 响应工具 ----------

    def _send_bytes(
        self, data: bytes, mime: str, cache: str = "no-store", extra_headers: dict = None
    ) -> None:
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", cache)
        self.send_header("X-Content-Type-Options", "nosniff")
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _send_error(self, code: int, msg: str = "") -> None:
        self._send_json({"ok": False, "error": msg}, code=code)

    def _send_json(self, data, code: int = 200) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_request(self, code="-", size="-") -> None:
        # 精简日志：成功请求不记录，只留错误（WARNING 级），避免刷屏
        if isinstance(code, int) and code < 400:
            return
        super().log_request(code, size)

    # ---------- 路由 ----------

    def _authorized(self, parsed) -> bool:
        if not self.token:
            return True  # 未启用令牌（仅本地开发/测试）
        header = self.headers.get(_API_TOKEN_HEADER)
        if header is not None and secrets.compare_digest(header, self.token):
            return True
        query = urllib.parse.parse_qs(parsed.query)
        qtoken = query.get("token")
        return (
            qtoken is not None
            and len(qtoken) == 1
            and secrets.compare_digest(qtoken[0], self.token)
        )

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = urllib.parse.unquote(parsed.path)
        if not path.startswith("/api/"):
            self._send_error(404, "not found")
            return
        if not self._authorized(parsed):
            self._send_error(401, "unauthorized")
            return
        name = path[len("/api/"):]
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length > _MAX_API_BODY:
                self._send_error(413, "body too large")
                return
            raw = self.rfile.read(length) if length else b"{}"
            payload = json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            self._send_error(400, "bad request")
            return
        fn = getattr(self.api or object(), name, None)
        if fn is None or name.startswith("_"):
            self._send_error(404, "unknown api")
            return
        try:
            result = fn(*payload.get("args") or [], **(payload.get("kwargs") or {}))
        except ApiError as e:
            # 业务错误：handler 主动抛出，按错误码/状态返回
            self._send_error(e.status, e.message)
            return
        except (TypeError, ValueError) as e:
            # 业务校验/入参错误：显式 400，不让调用方把“参数错”当成服务器故障
            self._send_error(400, str(e) or "bad arguments")
            return
        except Exception as e:
            logging.getLogger("app.api").exception("API %s failed", name)
            self._send_json({"ok": False, "error": str(e)}, code=500)
            return
        # 错误已统一由 ApiError 抛出；成功响应直接包 data。
        self._send_json({"ok": True, "data": result})

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = urllib.parse.unquote(parsed.path)
        if path == "" or path == "/":
            self._redirect_index()
            return
        if path == "/favicon.ico":
            self.send_response(204)  # 无图标，静默
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path.startswith("/book/"):
            self._serve_book(path[len("/book/") :])
        elif path.startswith("/img/"):
            self._serve_img(path[len("/img/") :])
        elif path.startswith("/cover/"):
            self._serve_cover(path[len("/cover/") :])
        elif path.startswith("/font/"):
            self._serve_font(path[len("/font/") :])
        elif path.startswith(("/css/", "/js/", "/index.html")):
            self._serve_static(path)
        else:
            self._send_error(404, "not found")

    def _redirect_index(self) -> None:
        self.send_response(302)
        self.send_header("Location", "/index.html")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _serve_static(self, path: str) -> None:
        rel = path.lstrip("/")
        base = self.web_dir.resolve()
        target = (base / rel).resolve()
        try:
            target.relative_to(base)
        except ValueError:
            self._send_error(400, "bad path")
            return
        if not target.is_file():
            self._send_error(404, "not found")
            return
        try:
            data = target.read_bytes()
        except OSError:
            self._send_error(404, "not found")
            return
        cache = "no-cache" if target.name == "index.html" else "max-age=3600"
        self._send_bytes(data, _mime_for(target.name), cache=cache)

    def _serve_book(self, rest: str) -> None:
        # rest 形如 "<book_id>/<zip_path...>"，zip_path 保留原始空段语义
        if "/" not in rest:
            book_id, zip_path = rest, ""
        else:
            book_id, zip_path = rest.split("/", 1)
        if not _BOOK_ID_RE.match(book_id):
            self._send_error(400, "bad book id")
            return
        if not self.books.has(book_id):
            self._send_error(404, "book not opened")
            return
        safe = _safe_zip_path(zip_path)
        if safe is None:
            self._send_error(400, "bad zip path")
            return
        book = self.books.open(book_id)
        data = book.read_file(safe)
        if data is None:
            mime = _mime_for(safe)
            if mime.startswith("image/"):
                # 图片缺失返回透明占位图，防布局崩坏
                self._send_bytes(_TRANSPARENT_GIF, "image/gif")
                return
            self._send_error(404, "resource not found")
            return
        mime = _mime_for(safe)
        ext = posixpath.splitext(safe)[1].lower()
        if ext in _TEXT_EXTS:
            # 文本资源统一转 UTF-8（BOM/声明编码/GBK 兜底）
            text = decode_text(data)
            # NGA 表情/图床图片改写为本地代理，规避防盗链 403 裂图
            text = _rewrite_nga_image_src(text, book_id)
            data = text.encode("utf-8")
            mime = mime.split(";")[0] + "; charset=utf-8"
        if ext in (".xhtml", ".html", ".htm"):
            # 注入 <base href>：指向章节所在目录，章节内相对路径
            # （图片/CSS/字体/CSS url()）在 iframe 文档中天然正确解析。
            # 章节一律按 text/html 返回：XHTML(application/xhtml+xml)
            # 下 base 元素在 WebView2 中不可靠，HTML 解析模式确定生效，
            # 且对 XHTML 章节内容宽容兼容
            base_dir = posixpath.dirname(safe)
            base = f"/book/{book_id}/" + (base_dir + "/" if base_dir else "")
            data = _inject_base(data, base)
            mime = "text/html; charset=utf-8"
        self._send_bytes(data, mime, extra_headers={"Content-Security-Policy": _CSP})

    def _serve_img(self, rest: str) -> None:
        """NGA 图床图片代理：/img/<book_id>?u=<url>。

        仅代理白名单 NGA 图床，带 Referer 与已存 Cookie，规避防盗链 403。
        """
        book_id = rest.split("?", 1)[0]
        if not _BOOK_ID_RE.match(book_id):
            self._send_error(400, "bad book id")
            return
        if not self.books.has(book_id):
            self._send_error(404, "book not opened")
            return
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        urls = query.get("u")
        if not urls or len(urls) != 1 or not urls[0]:
            self._send_error(400, "missing u")
            return
        url = urls[0]
        if not _is_nga_image_url(url):
            self._send_error(400, "url not allowed")
            return
        cfg = load_nga_config()
        cookie = f"ngaPassportUid={cfg['uid']}; ngaPassportCid={cfg['cid']}"
        headers = {
            "Referer": "https://bbs.nga.cn/",
            "User-Agent": cfg.get("ua") or DEFAULT_UA,
            "Cookie": cookie,
        }
        try:
            data, mime = _fetch_url(url, headers)
        except Exception:
            logging.getLogger("app.server").exception("图片代理失败：%s", url)
            self._send_error(502, "image proxy failed")
            return
        self._send_bytes(data, mime)

    def _serve_cover(self, rest: str) -> None:
        if not _BOOK_ID_RE.match(rest):
            self._send_error(400, "bad cover id")
            return
        book_id = rest
        # 只允许按目录前缀 glob 匹配，id 格式已限制为 md5，杜绝穿越
        for f in self.covers_dir.glob(f"{book_id}.*"):
            try:
                data = f.read_bytes()
            except OSError:
                continue
            self._send_bytes(data, _mime_for(f.name), cache="max-age=86400")
            return
        # 无封面统一回退平面骰子占位图，按前端主题参数自适应颜色。
        theme = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("theme", [""])[0]
        self._send_bytes(
            _dice_cover_svg(theme).encode("utf-8"),
            "image/svg+xml",
            cache="max-age=86400",
        )

    def _serve_font(self, rest: str) -> None:
        kind, _, name = rest.partition("/")
        font_file = resolve_font_file(kind, name)
        if font_file is None:
            self._send_error(404, "no font")
            return
        try:
            data = font_file.read_bytes()
        except OSError:
            self._send_error(404, "no font")
            return
        self._send_bytes(data, _mime_for(font_file.name), cache="max-age=86400")


def start_server(
    web_dir: Path,
    books: BookManager,
    covers_dir: Path,
    api: object = None,
    token: str = None,
    port: int = 0,
) -> int:
    """Start the local HTTP server (daemon thread) and return the listening port."""
    EpubHandler.web_dir = web_dir
    EpubHandler.books = books
    EpubHandler.covers_dir = covers_dir
    EpubHandler.api = api
    EpubHandler.token = token

    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), EpubHandler)
    server.daemon_threads = True
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return port
