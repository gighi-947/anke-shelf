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
        data = self.svc.search(self.book.id, "引力波")
        results = data["results"]
        self.assertTrue(results)
        total = sum(len(r["hits"]) for r in results)
        self.assertGreaterEqual(total, 5)  # 每章都有
        self.assertEqual(data["hit_chapters"], len(results))
        self.assertEqual(
            data["total_hits"],
            sum(r["chapter_hits"] for r in results),
        )
        # 每个结果都有定位信息
        for r in results:
            self.assertIn("chapter_index", r)
            self.assertIn("text_len", r)
            self.assertIn("chapter_hits", r)
            self.assertIn("more", r)
            self.assertIn("offset", r["hits"][0])

    def test_english_case_insensitive(self):
        data = self.svc.search(self.book.id, "QUICK BROWN")
        self.assertTrue(data["results"])
        snippet = data["results"][0]["hits"][0]["snippet"]
        self.assertIn("quick brown", snippet.lower())

    def test_empty_query(self):
        data = self.svc.search(self.book.id, "   ")
        self.assertEqual(data["results"], [])
        self.assertEqual(data["total_hits"], 0)
        self.assertEqual(data["hit_chapters"], 0)

    def test_no_match(self):
        data = self.svc.search(self.book.id, "绝不存在的词xyz")
        self.assertEqual(data["results"], [])
        self.assertEqual(data["total_hits"], 0)
        self.assertEqual(data["hit_chapters"], 0)
        self.assertGreater(data["total_chapters"], 0)

    def test_per_chapter_limit(self):
        data = self.svc.search(self.book.id, "e", per_chapter=3)
        for r in data["results"]:
            self.assertLessEqual(len(r["hits"]), 3)
        # 总命中数不因每章限量而丢失
        self.assertEqual(data["total_hits"], sum(r["chapter_hits"] for r in data["results"]))

    def test_case_sensitive(self):
        book = _FakeBook(["Quick QUICK quick"], ["t"])
        svc = SearchService()
        svc.ensure_index(book)
        ci = svc.search(book.id, "QUICK", case_sensitive=False)
        cs = svc.search(book.id, "QUICK", case_sensitive=True)
        self.assertEqual(ci["total_hits"], 3)
        self.assertEqual(cs["total_hits"], 1)
        self.assertEqual(cs["results"][0]["hits"][0]["offset"], 6)

    def test_whole_word(self):
        book = _FakeBook(["cat catalog scat cat"], ["t"])
        svc = SearchService()
        svc.ensure_index(book)
        sub = svc.search(book.id, "cat", whole_word=False)
        ww = svc.search(book.id, "cat", whole_word=True)
        self.assertEqual(sub["total_hits"], 4)
        self.assertEqual(ww["total_hits"], 2)
        offsets = [h["offset"] for h in ww["results"][0]["hits"]]
        self.assertEqual(offsets, [0, 17])


class _FakeChapter:
    def __init__(self, index, href):
        self.index = index
        self.href = href


class _FakeBook:
    """与 EpubBook 的 ensure_index 所需接口兼容的最小替身。"""

    def __init__(self, texts, titles):
        self._texts = texts
        self._titles = titles
        self.id = "f" * 32
        self.chapters = [_FakeChapter(i, f"ch{i}.xhtml") for i in range(len(texts))]

    def chapter_text(self, index):
        return f"<p>{self._texts[index]}</p>"

    def chapter_title(self, index):
        return self._titles[index]


class TestHighFrequencySearch(unittest.TestCase):
    """高频关键词不得挤掉靠后章节的结果（用户反馈：只显示到 170 楼）。"""

    @classmethod
    def setUpClass(cls):
        # 第一章 30 处命中，后两章各 1 处 —— 旧实现会把 100 条上限占满，
        # 后两章完全不可见；新实现每章限量，靠后章节仍出现。
        cls.book = _FakeBook(
            ["x丰川祥子丰川祥子丰川祥子丰川祥子丰川祥子丰川祥子" * 5,
             "中间章节丰川祥子",
             "尾部章节丰川祥子"],
            ["第一章", "第二章", "第三章"],
        )
        cls.svc = SearchService()
        cls.svc.ensure_index(cls.book)

    def test_later_chapters_not_starved(self):
        data = self.svc.search(self.book.id, "丰川祥子", per_chapter=2)
        self.assertEqual([r["chapter_index"] for r in data["results"]], [0, 1, 2])
        self.assertEqual(data["hit_chapters"], 3)
        self.assertEqual(data["total_hits"], 32)
        first = data["results"][0]
        self.assertEqual(len(first["hits"]), 2)
        self.assertEqual(first["chapter_hits"], 30)
        self.assertTrue(first["more"])

    def test_search_more_returns_remaining(self):
        data = self.svc.search(self.book.id, "丰川祥子", per_chapter=2)
        first = data["results"][0]
        collected = list(first["hits"])
        last_offset = collected[-1]["offset"]
        pages = 1
        while True:
            page = self.svc.search_more(
                self.book.id, "丰川祥子", 0, last_offset, per_chapter=2
            )
            collected.extend(page["hits"])
            pages += 1
            if not page["more"]:
                break
            last_offset = page["hits"][-1]["offset"]
        # 逐页取完：正好 30 条
        self.assertEqual(len(collected), 30)
        self.assertGreater(pages, 2)
        # 最后一页恰好取完剩余两条且 more=False
        self.assertEqual(len(page["hits"]), 2)
        self.assertFalse(page["more"])

    def test_search_more_exhausted_chapter(self):
        data = self.svc.search(self.book.id, "丰川祥子", per_chapter=2)
        ch2 = data["results"][2]
        more = self.svc.search_more(
            self.book.id, "丰川祥子", 2, ch2["hits"][-1]["offset"]
        )
        self.assertEqual(more["hits"], [])
        self.assertFalse(more["more"])


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
