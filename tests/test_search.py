"""全文搜索单元测试。"""
import unittest
from pathlib import Path

from app.epub import EpubBook
from app.search import SearchService
from app.text import extract_dom_text as extract_plain_text

SAMPLE_DIR = Path(__file__).parent / "sample"


class TestExtractPlainText(unittest.TestCase):
    def test_strips_tags(self):
        self.assertEqual(extract_plain_text("<p>你好</p><p>世界</p>"), "你好 世界")

    def test_removes_script_style(self):
        html = "<p>正文</p><script>var x=1;</script><style>p{color:red}</style><p>结尾</p>"
        self.assertNotIn("var", extract_plain_text(html))
        self.assertIn("正文", extract_plain_text(html))

    def test_unescape_entities(self):
        self.assertEqual(extract_plain_text("<p>a&amp;b &lt;c&gt;</p>"), "a&b <c>")

    def test_collapse_whitespace(self):
        self.assertEqual(extract_plain_text("<p>a</p>\n\n  <p>b</p>"), "a b")


class TestSearchService(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.book = EpubBook(str(SAMPLE_DIR / "sample_nav3.epub")).open()
        cls.svc = SearchService()
        cls.svc.ensure_index(cls.book)

    @classmethod
    def tearDownClass(cls):
        cls.book.close()

    def test_ready(self):
        self.assertTrue(self.svc.is_ready(self.book.id))

    def test_not_ready_unknown_book(self):
        self.assertFalse(self.svc.is_ready("0" * 32))

    def test_search_unindexed_returns_none(self):
        self.assertIsNone(self.svc.search("0" * 32, "引力波"))

    def test_chinese_substring(self):
        results = self.svc.search(self.book.id, "引力波")
        self.assertTrue(results)
        total = sum(len(r["hits"]) for r in results)
        self.assertGreaterEqual(total, 5)  # 每章都有
        # 每个结果都有定位信息
        for r in results:
            self.assertIn("chapter_index", r)
            self.assertIn("text_len", r)
            self.assertIn("offset", r["hits"][0])

    def test_english_case_insensitive(self):
        results = self.svc.search(self.book.id, "QUICK BROWN")
        self.assertTrue(results)
        snippet = results[0]["hits"][0]["snippet"]
        self.assertIn("quick brown", snippet.lower())

    def test_empty_query(self):
        self.assertEqual(self.svc.search(self.book.id, "   "), [])

    def test_no_match(self):
        self.assertEqual(self.svc.search(self.book.id, "绝不存在的词xyz"), [])

    def test_hit_limit(self):
        results = self.svc.search(self.book.id, "e", max_hits=3)
        total = sum(len(r["hits"]) for r in results)
        self.assertEqual(total, 3)


class TestDrop(unittest.TestCase):
    """索引清理（独立类：不破坏 TestSearchService 的共享索引）。"""

    @classmethod
    def setUpClass(cls):
        cls.book = EpubBook(str(SAMPLE_DIR / "sample_nav3.epub")).open()
        cls.svc = SearchService()
        cls.svc.ensure_index(cls.book)

    @classmethod
    def tearDownClass(cls):
        cls.book.close()

    def test_drop(self):
        self.svc.drop(self.book.id)
        self.assertIsNone(self.svc.search(self.book.id, "引力波"))
        self.assertFalse(self.svc.is_ready(self.book.id))


if __name__ == "__main__":
    unittest.main()
