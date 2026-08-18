"""HTTP 服务器单元测试：路由、字节一致性、路径穿越防护。"""
import json
import tempfile
import unittest
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from unittest import mock
from urllib.parse import quote

from app.book_manager import BookManager
from app.server import _inject_base, _rewrite_nga_image_src, start_server

SAMPLE_DIR = Path(__file__).parent / "sample"


class _FakeApi:
    """最小 API 桩：验证 JSON 路由、令牌校验与异常包装。"""

    def get_settings(self):
        return {"mode": "test"}

    def echo(self, *args, **kwargs):
        return {"args": args, "kwargs": kwargs}

    def boom(self):
        raise ValueError("boom")


def _samples() -> dict[str, Path]:
    return {p.stem.removeprefix("sample_"): p for p in SAMPLE_DIR.glob("*.epub")}


class ServerTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.covers = Path(cls.tmp.name) / "covers"
        cls.covers.mkdir()
        cls.books = BookManager()
        cls.book = cls.books.register(str(_samples()["nav3"]))
        cls.api = _FakeApi()
        cls.token = "test-token-123"
        cls.port = start_server(
            Path(__file__).parent.parent / "web",
            cls.books,
            cls.covers,
            api=cls.api,
            token=cls.token,
        )
        cls.base = f"http://127.0.0.1:{cls.port}"
        cover = cls.book.get_cover_bytes()
        (cls.covers / f"{cls.book.id}.png").write_bytes(cover)

    @classmethod
    def tearDownClass(cls):
        cls.books.close_all()
        cls.tmp.cleanup()

    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None  # 不跟随重定向，暴露 302 原始状态

    def get(self, path: str):
        opener = urllib.request.build_opener(self._NoRedirect())
        req = urllib.request.Request(self.base + path)
        try:
            with opener.open(req, timeout=5) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            code, headers, body = e.code, e.headers, e.read()
            e.close()
            return code, headers, body

    def post(self, path: str, body=None, token=None):
        data = json.dumps(body or {}).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        if token is not None:
            req.add_header("X-Anke-Token", token)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read()
            e.close()
            try:
                parsed = json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError:
                parsed = {}
            return e.code, parsed

    # ---------- 静态资源 ----------

    def test_root_redirects(self):
        status, headers, _ = self.get("/")
        self.assertEqual(status, 302)
        self.assertEqual(headers["Location"], "/index.html")

    def test_index_served(self):
        status, _, body = self.get("/index.html")
        self.assertEqual(status, 200)
        self.assertIn("EPUB".encode(), body)

    # ---------- JSON API ----------

    def test_api_requires_token(self):
        status, _ = self.post("/api/get_settings", token=None)
        self.assertEqual(status, 401)

    def test_api_query_token_compat(self):
        # 启动 URL 兼容入口（?token=...）：query token 仍被接受
        status, data = self.post(f"/api/get_settings?token={self.token}", token=None)
        self.assertEqual(status, 200)
        self.assertTrue(data["ok"])

    def test_api_wrong_token_rejected(self):
        status, _ = self.post("/api/get_settings", token="wrong-token")
        self.assertEqual(status, 401)
        status, _ = self.post("/api/get_settings?token=wrong-token", token=None)
        self.assertEqual(status, 401)

    def test_api_get_settings(self):
        status, data = self.post("/api/get_settings", token=self.token)
        self.assertEqual(status, 200)
        self.assertTrue(data["ok"])
        self.assertEqual(data["data"], {"mode": "test"})

    def test_api_unknown_method(self):
        status, _ = self.post("/api/no_such_method", token=self.token)
        self.assertEqual(status, 404)

    def test_api_private_method_rejected(self):
        status, _ = self.post("/api/_pick_paths", token=self.token)
        self.assertEqual(status, 404)

    def test_api_exception_wrapped(self):
        status, data = self.post("/api/boom", token=self.token)
        self.assertEqual(status, 500)
        self.assertFalse(data["ok"])
        self.assertIn("boom", data["error"])

    def test_api_args_and_kwargs(self):
        status, data = self.post(
            "/api/echo", {"args": [1, "x"], "kwargs": {"k": 3}}, token=self.token
        )
        self.assertEqual(status, 200)
        self.assertEqual(data["data"], {"args": [1, "x"], "kwargs": {"k": 3}})

    def test_static_bad_path(self):
        status, _, _ = self.get("/css/..%2f..%2fsecret.txt")
        self.assertEqual(status, 400)

    # ---------- 书籍资源 ----------

    def test_chapter_bytes_match_zip(self):
        status, headers, body = self.get(f"/book/{self.book.id}/OEBPS/ch01.xhtml")
        self.assertEqual(status, 200)
        self.assertIn("text/html", headers["Content-Type"])
        with zipfile.ZipFile(self.book.path) as zf:
            raw = zf.read("OEBPS/ch01.xhtml").decode("utf-8")
        html = body.decode("utf-8")
        # 注入的 base 移除后与原字节一致（自闭合写法）
        base = f'<base href="/book/{self.book.id}/OEBPS/"/>'
        self.assertIn(base, html)
        self.assertEqual(html.replace(base, ""), raw)
        # 章节响应带 CSP
        self.assertIn("Content-Security-Policy", headers)

    def test_image_mime(self):
        status, headers, body = self.get(f"/book/{self.book.id}/OEBPS/images/pic.png")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/png")
        self.assertTrue(body.startswith(b"\x89PNG"))

    def test_missing_image_placeholder(self):
        status, headers, body = self.get(f"/book/{self.book.id}/OEBPS/images/none.png")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/gif")  # 透明占位图

    def test_missing_resource_404(self):
        status, _, _ = self.get(f"/book/{self.book.id}/OEBPS/nope/missing.css")
        self.assertEqual(status, 404)

    # ---------- 路径穿越防护 ----------

    def test_unknown_book_id(self):
        status, _, _ = self.get(f"/book/{'0' * 32}/OEBPS/ch01.xhtml")
        self.assertEqual(status, 404)

    def test_bad_book_id_format(self):
        status, _, _ = self.get("/book/not-a-md5/OEBPS/ch01.xhtml")
        self.assertEqual(status, 400)

    def test_dotdot_percent_encoded(self):
        status, _, _ = self.get(
            f"/book/{self.book.id}/OEBPS/..%2f..%2f..%2fWindows/win.ini"
        )
        self.assertEqual(status, 400)

    def test_dotdot_raw(self):
        status, _, _ = self.get(f"/book/{self.book.id}/OEBPS/../../../../Windows/win.ini")
        self.assertEqual(status, 400)

    def test_backslash_rejected(self):
        status, _, _ = self.get(f"/book/{self.book.id}/OEBPS%5C..%5C..%5Cwin.ini")
        self.assertEqual(status, 400)

    def test_absolute_zip_path_rejected(self):
        status, _, _ = self.get(f"/book/{self.book.id}//etc/passwd")
        self.assertEqual(status, 400)

    def test_double_encoded_rejected(self):
        status, _, _ = self.get(f"/book/{self.book.id}/OEBPS/%252e%252e/secret")
        self.assertEqual(status, 404)  # %25 解码为 %，条目不存在 → 404

    # ---------- base 注入 ----------

    def test_chapter_has_injected_base(self):
        status, _, body = self.get(f"/book/{self.book.id}/OEBPS/ch01.xhtml")
        self.assertEqual(status, 200)
        html = body.decode("utf-8")
        # base 指向章节目录：/book/<id>/OEBPS/（自闭合，XHTML 兼容）
        self.assertIn(f'<base href="/book/{self.book.id}/OEBPS/"/>', html)

    def test_base_not_injected_into_css(self):
        status, _, body = self.get(f"/book/{self.book.id}/OEBPS/css/style.css")
        self.assertEqual(status, 200)
        self.assertNotIn(b"<base", body)

    def test_inject_base_keeps_existing_base(self):
        html = b"<html><head><base href=\"http://x/\"></head><body></body></html>"
        self.assertIn(b'<base href="http://x/"', _inject_base(html, "/book/a/"))
        self.assertNotIn(b'<base href="/book/a/"', _inject_base(html, "/book/a/"))

    def test_inject_base_no_head(self):
        html = b"<p>bare</p>"
        out = _inject_base(html, "/book/a/")
        self.assertIn(b'<base href="/book/a/"/>', out)

    # ---------- 封面 ----------

    def test_cover_served(self):
        status, headers, body = self.get(f"/cover/{self.book.id}")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/png")
        self.assertTrue(body.startswith(b"\x89PNG"))

    def test_cover_missing_404(self):
        status, _, _ = self.get(f"/cover/{'1' * 32}")
        self.assertEqual(status, 404)

    # ---------- NGA 图片代理 ----------

    def test_img_proxy_rejects_bad_book(self):
        url = quote("https://img4.nga.178.com/ngabbs/post/smile/abc.png", safe="")
        status, _, _ = self.get(f"/img/{'0' * 32}?u={url}")
        self.assertEqual(status, 404)

    def test_img_proxy_rejects_non_nga_url(self):
        url = quote("https://evil.example/x.png", safe="")
        status, _, _ = self.get(f"/img/{self.book.id}?u={url}")
        self.assertEqual(status, 400)

    def test_img_proxy_rejects_missing_u(self):
        status, _, _ = self.get(f"/img/{self.book.id}")
        self.assertEqual(status, 400)

    @mock.patch("app.server._fetch_url", return_value=(b"\x89PNG", "image/png"))
    def test_img_proxy_forwards_with_headers(self, fetch):
        original = "https://img4.nga.178.com/ngabbs/post/smile/abc.png"
        status, headers, body = self.get(f"/img/{self.book.id}?u={quote(original, safe='')}")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/png")
        self.assertEqual(body, b"\x89PNG")
        self.assertEqual(fetch.call_count, 1)
        fetched_url, fetch_headers = fetch.call_args.args
        self.assertEqual(fetched_url, original)
        self.assertIn("Referer", fetch_headers)
        self.assertEqual(fetch_headers["Referer"], "https://bbs.nga.cn/")
        self.assertIn("Cookie", fetch_headers)

    def test_img_proxy_failure_returns_502(self):
        with mock.patch("app.server._fetch_url", side_effect=OSError("boom")):
            url = quote("https://img4.nga.178.com/ngabbs/post/smile/abc.png", safe="")
            status, _, _ = self.get(f"/img/{self.book.id}?u={url}")
        self.assertEqual(status, 502)

    def test_rewrite_nga_image_src(self):
        html = (
            '<img src="https://img4.nga.178.com/ngabbs/post/smile/abc.png">'
            '<img src="https://example.com/x.png">'
            '<img src="https://img.nga.cn/attachments/mon_1.jpg">'
        )
        out = _rewrite_nga_image_src(html, self.book.id)
        self.assertIn(f'/img/{self.book.id}?u=', out)
        self.assertNotIn('src="https://img4.nga.178.com', out)
        self.assertNotIn('src="https://img.nga.cn', out)
        self.assertIn('src="https://example.com/x.png"', out)
        # 代理 URL 完整保留原始地址
        encoded = quote("https://img4.nga.178.com/ngabbs/post/smile/abc.png", safe="")
        self.assertIn(f'/img/{self.book.id}?u={encoded}', out)


if __name__ == "__main__":
    unittest.main()
