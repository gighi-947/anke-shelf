"""骨碌碌专版阅读器调试服务器回归测试。"""
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from tests.gululu_reader_debug.server import create_server
from tests.make_test_epub import build_nav3


class DebugReaderServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.epub = Path(self.tmp.name) / "sample.epub"
        build_nav3(self.epub)
        self.server, self.library = create_server(self.epub, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.base = f"http://{host}:{port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.library.close()
        self.tmp.cleanup()

    def test_metadata_exposes_spine_titles(self):
        with urllib.request.urlopen(self.base + "/api/book", timeout=2) as response:
            payload = json.load(response)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["book"]["title"], "测试书：引力波之旅")
        self.assertEqual(len(payload["book"]["chapters"]), 5)
        self.assertEqual(payload["book"]["chapters"][0]["title"], "第一章 起航")

    def test_chapter_response_is_utf8_html_with_csp(self):
        request = urllib.request.Request(self.base + "/book/OEBPS/ch01.xhtml")
        with urllib.request.urlopen(request, timeout=2) as response:
            html = response.read().decode("utf-8")
            self.assertEqual(response.headers.get_content_type(), "text/html")
            self.assertIn("script-src 'none'", response.headers["Content-Security-Policy"])
        self.assertIn("第一章 起航", html)

    def test_rejects_book_path_traversal(self):
        request = urllib.request.Request(self.base + "/book/..%2Fsecret.txt")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(caught.exception.code, 400)
        caught.exception.close()


if __name__ == "__main__":
    unittest.main()
