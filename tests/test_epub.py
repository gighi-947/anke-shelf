"""EPUB 解析器单元测试。"""
import tempfile
import unittest
import zipfile
from pathlib import Path

from app.epub import EpubBook, EpubError, is_zip_file
from app.make_test_epub import build_all

SAMPLE_DIR = Path(__file__).parent / "sample"


def _samples() -> dict[str, Path]:
    return {p.stem.removeprefix("sample_"): p for p in SAMPLE_DIR.glob("*.epub")}


class TestIsZipFile(unittest.TestCase):
    def setUp(self):
        self.samples = _samples()

    def test_normal_zip(self):
        self.assertTrue(is_zip_file(str(self.samples["nav3"])))

    def test_corrupt_head_still_zip(self):
        # 文件头被污染但 EOCD 完整 → EOCD 兜底判定为 zip
        self.assertTrue(is_zip_file(str(self.samples["corrupt"])))

    def test_bad_file_rejected(self):
        self.assertFalse(is_zip_file(str(self.samples["bad"])))

    def test_missing_file(self):
        self.assertFalse(is_zip_file(str(Path(tempfile.gettempdir()) / "no_such_file.xyz")))


class TestParseNav3(unittest.TestCase):
    """标准 EPUB3：双目录 + 图片 + CSS + 封面。"""

    @classmethod
    def setUpClass(cls):
        cls.book = EpubBook(str(_samples()["nav3"])).open()

    @classmethod
    def tearDownClass(cls):
        cls.book.close()

    def test_metadata(self):
        self.assertEqual(self.book.title, "测试书：引力波之旅")
        self.assertEqual(self.book.author, "测试作者")
        self.assertEqual(self.book.language, "zh-CN")

    def test_spine(self):
        self.assertEqual(len(self.book.chapters), 5)
        self.assertEqual(self.book.chapters[0].href, "OEBPS/ch01.xhtml")
        self.assertTrue(all(c.linear for c in self.book.chapters))

    def test_toc_nav_preferred(self):
        # nav 优先：第一章带子目录
        self.assertEqual(self.book.toc[0].label, "第一章 起航")
        self.assertEqual(len(self.book.toc[0].children), 2)
        self.assertEqual(self.book.toc[0].children[0].label, "1.1 引力波的发现")

    def test_toc_nested_not_duplicated(self):
        # 嵌套子目录只出现在父级 children，不应被重复解析到顶层
        self.assertEqual(
            [e.label for e in self.book.toc],
            ["第一章 起航", "第二章 深海", "第三章 黎明", "第四章 归途", "第五章 尾声"],
        )
        self.assertEqual(len(self.book.toc), 5)

    def test_toc_spine_mapping(self):
        self.assertEqual(self.book.toc_spine_index("OEBPS/ch01.xhtml"), 0)
        self.assertEqual(self.book.toc_spine_index("OEBPS/ch05.xhtml"), 4)
        self.assertIsNone(self.book.toc_spine_index("OEBPS/no_such.xhtml"))

    def test_cover(self):
        self.assertIsNotNone(self.book.cover_href)
        self.assertEqual(self.book.cover_href, "OEBPS/cover.png")
        data = self.book.get_cover_bytes()
        self.assertIsNotNone(data)
        self.assertTrue(data.startswith(b"\x89PNG"))

    def test_read_file(self):
        data = self.book.read_file("OEBPS/images/pic.png")
        self.assertIsNotNone(data)
        # 与 zip 内原始字节一致
        with zipfile.ZipFile(self.book.path) as zf:
            self.assertEqual(data, zf.read("OEBPS/images/pic.png"))
        self.assertIsNone(self.book.read_file("OEBPS/nope/missing.png"))

    def test_chapter_text(self):
        text = self.book.chapter_text(0)
        self.assertIsNotNone(text)
        self.assertIn("引力波", text)
        # 外链 CSS 路径保留原样
        self.assertIn("css/style.css", text)

    def test_chapter_title(self):
        self.assertEqual(self.book.chapter_title(0), "第一章 起航")


class TestParseNcx2(unittest.TestCase):
    """EPUB2 仅 NCX：目录兜底路径。"""

    @classmethod
    def setUpClass(cls):
        cls.book = EpubBook(str(_samples()["ncx2"])).open()

    @classmethod
    def tearDownClass(cls):
        cls.book.close()

    def test_metadata(self):
        self.assertEqual(self.book.title, "旧式测试书")
        self.assertEqual(self.book.author, "老作者")

    def test_spine(self):
        self.assertEqual(len(self.book.chapters), 4)

    def test_toc_ncx(self):
        self.assertEqual(self.book.toc[0].label, "第一章 起航")
        self.assertEqual(len(self.book.toc[0].children), 1)
        self.assertEqual(self.book.toc[0].children[0].label, "1.1 引力波的发现")
        self.assertEqual(self.book.toc[1].label, "第二章 深海")

    def test_toc_map(self):
        self.assertEqual(self.book.toc_map.get("OEBPS/ch01.xhtml"), "第一章 起航")


class TestParseCorrupt(unittest.TestCase):
    """头污染书：解析应正常（zipfile 靠中央目录）。"""

    @classmethod
    def setUpClass(cls):
        cls.book = EpubBook(str(_samples()["corrupt"])).open()

    @classmethod
    def tearDownClass(cls):
        cls.book.close()

    def test_parse_ok(self):
        self.assertEqual(len(self.book.chapters), 5)
        self.assertEqual(self.book.title, "测试书：引力波之旅")


class TestParseCase(unittest.TestCase):
    """href 大小写错配：小写映射兜底。"""

    @classmethod
    def setUpClass(cls):
        cls.book = EpubBook(str(_samples()["case"])).open()

    @classmethod
    def tearDownClass(cls):
        cls.book.close()

    def test_spine_lowercase_fallback(self):
        self.assertEqual(len(self.book.chapters), 1)
        # 实际条目是 ChapterOne.XHTML，OPF 写 chapterone.xhtml
        data = self.book.read_file(self.book.chapters[0].href)
        self.assertIsNotNone(data)
        self.assertIn("大小写测试", data.decode("utf-8", errors="replace"))


class TestParseErrors(unittest.TestCase):
    def test_bad_file(self):
        with self.assertRaises(EpubError):
            EpubBook(str(_samples()["bad"])).open()

    def test_nonexistent(self):
        with self.assertRaises(EpubError):
            EpubBook(str(SAMPLE_DIR / "nope.epub")).open()

    def test_drm_epub(self):
        # 构造一个带 encryption.xml 的假 DRM 书
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "drm.epub"
            import zipfile

            with zipfile.ZipFile(p, "w") as zf:
                zf.writestr("mimetype", "application/epub+zip")
                zf.writestr("META-INF/encryption.xml", "<encryption/>")
            with self.assertRaises(EpubError) as ctx:
                EpubBook(str(p)).open()
            self.assertIn("DRM", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
