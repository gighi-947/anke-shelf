"""骨碌碌专版阅读器调试服务器。

从仓库根目录运行：
    python -m tests.gululu_reader_debug.server --source 66905
"""
from __future__ import annotations

import argparse
import http.server
import json
import posixpath
import urllib.parse
from pathlib import Path
from typing import Optional

from app.epub import EpubBook, EpubError, decode_text
from app.gululu_epub import download_epub, parse_book_id
from app.server import _CSP, _TEXT_EXTS, _mime_for, _safe_zip_path


DEBUG_ROOT = Path(__file__).resolve().parent
WEB_ROOT = DEBUG_ROOT / "web"
WORKSPACE = DEBUG_ROOT / "workspace"


class DebugLibrary:
    """调试服务器当前打开的单本 EPUB。"""

    def __init__(self, epub_path: str | Path) -> None:
        self.path = Path(epub_path).resolve()
        self.book = EpubBook(str(self.path)).open()

    def metadata(self) -> dict:
        return {
            "title": self.book.title,
            "author": self.book.author,
            "language": self.book.language,
            "source_file": self.path.name,
            "chapters": [
                {
                    "index": index,
                    "title": self.book.chapter_title(index),
                    "href": chapter.href,
                }
                for index, chapter in enumerate(self.book.chapters)
            ],
        }

    def close(self) -> None:
        self.book.close()


class DebugHandler(http.server.BaseHTTPRequestHandler):
    server_version = "GululuReaderDebug/1"
    library: DebugLibrary
    web_root: Path = WEB_ROOT

    def _send_bytes(
        self,
        data: bytes,
        mime: str,
        *,
        code: int = 200,
        cache: str = "no-store",
        headers: Optional[dict[str, str]] = None,
    ) -> None:
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", cache)
        self.send_header("X-Content-Type-Options", "nosniff")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _send_json(self, payload: dict, code: int = 200) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._send_bytes(data, "application/json; charset=utf-8", code=code)

    def _send_error(self, code: int, message: str) -> None:
        self._send_json({"ok": False, "error": message}, code=code)

    def do_HEAD(self) -> None:
        self.do_GET()

    def do_GET(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        try:
            path = urllib.parse.unquote(parsed.path, errors="strict")
        except UnicodeError:
            self._send_error(400, "bad path")
            return
        if path == "/":
            self.send_response(302)
            self.send_header("Location", "/index.html")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif path == "/api/book":
            self._send_json({"ok": True, "book": self.library.metadata()})
        elif path.startswith("/book/"):
            self._serve_book(path[len("/book/"):])
        elif path == "/favicon.ico":
            self._send_bytes(b"", "image/x-icon", code=204)
        else:
            self._serve_static(path)

    def _serve_static(self, path: str) -> None:
        base = self.web_root.resolve()
        target = (base / path.lstrip("/")).resolve()
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
        self._send_bytes(
            data,
            _mime_for(target.name),
            cache="no-cache",
            headers={
                "Content-Security-Policy": (
                    "default-src 'self'; img-src 'self' data:; "
                    "style-src 'self'; script-src 'self'; frame-src 'self'; "
                    "object-src 'none'; base-uri 'none'"
                )
            },
        )

    def _serve_book(self, raw_path: str) -> None:
        safe = _safe_zip_path(raw_path)
        if safe is None:
            self._send_error(400, "bad book path")
            return
        data = self.library.book.read_file(safe)
        if data is None:
            self._send_error(404, "resource not found")
            return
        mime = _mime_for(safe)
        ext = posixpath.splitext(safe)[1].lower()
        if ext in _TEXT_EXTS:
            data = decode_text(data).encode("utf-8")
            mime = mime.split(";", 1)[0] + "; charset=utf-8"
        if ext in {".xhtml", ".html", ".htm"}:
            mime = "text/html; charset=utf-8"
        self._send_bytes(
            data,
            mime,
            headers={"Content-Security-Policy": _CSP},
        )

    def log_message(self, fmt: str, *args) -> None:
        print(f"[gululu-debug] {self.address_string()} {fmt % args}")


def create_server(
    epub_path: str | Path,
    *,
    host: str = "127.0.0.1",
    port: int = 8877,
) -> tuple[http.server.ThreadingHTTPServer, DebugLibrary]:
    library = DebugLibrary(epub_path)

    class BoundHandler(DebugHandler):
        pass

    BoundHandler.library = library
    BoundHandler.web_root = WEB_ROOT
    try:
        server = http.server.ThreadingHTTPServer((host, port), BoundHandler)
    except Exception:
        library.close()
        raise
    server.daemon_threads = True
    return server, library


def _resolve_epub(args: argparse.Namespace) -> Path:
    if args.source:
        book_id = parse_book_id(args.source)
        target = WORKSPACE / f"gululu-{book_id}.epub"
        if args.refresh or not target.exists():
            print(f"正在生成测试书：{target}")
            download_epub(book_id, target)
        return target
    target = Path(args.epub).resolve() if args.epub else WORKSPACE / "gululu-66905.epub"
    if not target.is_file():
        raise FileNotFoundError(
            f"未找到测试 EPUB：{target}；请使用 --source 66905 生成，或用 --epub 指定文件"
        )
    return target


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="启动骨碌碌专版阅读器调试区")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--source", help="骨碌碌书籍 ID 或公开书籍链接")
    source.add_argument("--epub", help="本地 EPUB 路径")
    parser.add_argument("--refresh", action="store_true", help="重新获取 --source 指定书籍")
    parser.add_argument("--port", type=int, default=8877, help="本地端口（默认 8877）")
    parser.add_argument("--check", action="store_true", help="解析书籍并打印摘要后退出")
    args = parser.parse_args(argv)

    try:
        epub_path = _resolve_epub(args)
        server, library = create_server(epub_path, port=args.port)
    except (FileNotFoundError, ValueError, EpubError, OSError) as exc:
        parser.error(str(exc))
    try:
        meta = library.metadata()
        if args.check:
            print(json.dumps(meta, ensure_ascii=False, indent=2))
            return 0
        address, port = server.server_address
        print(f"骨碌碌专版阅读器：{meta['title']}（{len(meta['chapters'])} 章）")
        print(f"调试地址：http://{address}:{port}/")
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n调试服务器已停止")
    finally:
        server.server_close()
        library.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
