"""EPUB 安全回归（B8）：目录穿越、脚本内容、CSP、ZIP 炸弹防护。"""
import tempfile
import unittest
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from app.book_manager import BookManager
from app.epub import EpubBook, EpubError
from app.server import start_server

PROJECT = Path(__file__).resolve().parent.parent.parent
SAMPLE = PROJECT / "tests" / "sample" / "sample_nav3.epub"


def _minimal_epub(path: Path, extra_entries: dict[str, bytes] | None = None) -> None:
    """构造最小可解析 EPUB（container + OPF + 1 章），可附加任意 zip 条目。"""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(
            "META-INF/container.xml",
            b'<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">'
            b'<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
            b"</rootfiles></container>",
        )
        z.writestr(
            "OEBPS/content.opf",
            b'<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">'
            b'<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>t</dc:title></metadata>'
            b'<manifest><item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>'
            b'<spine><itemref idref="c1"/></spine></package>',
        )
        z.writestr(
            "OEBPS/chapter.xhtml",
            b'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head>'
            b"<body><p>ok</p></body></html>",
        )
        for name, data in (extra_entries or {}).items():
            z.writestr(name, data)


class EpubZipBombTest(unittest.TestCase):
    def test_too_many_entries_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "many.epub"
            _minimal_epub(p)  # 4 个条目
            with self.assertRaises(EpubError):
                EpubBook(str(p), max_entries=2).open()

    def test_total_bytes_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "big.epub"
            _minimal_epub(p)
            with self.assertRaises(EpubError):
                EpubBook(str(p), max_total_bytes=8).open()

    def test_normal_epub_passes_default_limits(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "ok.epub"
            _minimal_epub(p)
            book = EpubBook(str(p)).open()
            self.assertEqual(len(book.chapters), 1)
            book.close()

    def test_traversal_entry_is_zip_content_not_host_fs(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "evil.epub"
            _minimal_epub(p, {"OEBPS/../../secret.txt": b"zip-entry"})
            book = EpubBook(str(p)).open()
            self.assertEqual(book.read_file("OEBPS/../../secret.txt"), b"zip-entry")
            # 仅能读到 zip 条目内容；宿主文件系统不受影响
            self.assertFalse((Path(tmp).parent / "secret.txt").exists())
            book.close()

    def test_script_content_extracted_as_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "script.epub"
            _minimal_epub(
                p,
                {
                    "OEBPS/chapter.xhtml": (
                        '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
                        "<script>alert(1)</script><p>正文</p></body></html>"
                    ).encode("utf-8")
                },
            )
            book = EpubBook(str(p)).open()
            from app.text import extract_dom_text

            self.assertEqual(extract_dom_text(book.chapter_text(0)), "正文")
            book.close()


class EpubSecurityServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.covers = Path(cls.tmp.name) / "covers"
        cls.covers.mkdir()
        cls.books = BookManager()
        cls.book = cls.books.register(str(SAMPLE))
        cls.port = start_server(
            PROJECT / "web",
            cls.books,
            cls.covers,
            api=object(),
            token="security-test-token",
        )
        cls.base = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls):
        cls.books.close_all()
        cls.tmp.cleanup()

    def get(self, path: str):
        req = urllib.request.Request(self.base + path)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            code, headers, body = e.code, e.headers, e.read()
            e.close()
            return code, headers, body

    def test_chapter_response_has_csp(self):
        href = self.book.chapters[0].href
        code, headers, _ = self.get(f"/book/{self.book.id}/{href}")
        self.assertEqual(code, 200)
        csp = headers.get("Content-Security-Policy") or ""
        self.assertIn("script-src 'none'", csp)
        self.assertIn("object-src 'none'", csp)

    def test_traversal_requests_rejected(self):
        for suffix in (
            "/OEBPS/../../secret.txt",
            "/OEBPS/..%2f..%2fsecret.txt",
            "/../meta.json",
            "/OEBPS/%252e%252e/secret",
        ):
            code, _, _ = self.get(f"/book/{self.book.id}{suffix}")
            self.assertIn(code, (400, 404), suffix)


if __name__ == "__main__":
    unittest.main()
