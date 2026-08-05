"""文本坐标系统单元测试：extract_dom_text 与浏览器 DOM 派生文本对齐。"""
import unittest
from pathlib import Path

from app.epub import EpubBook
from app.text import extract_dom_text

SAMPLE_DIR = Path(__file__).parent / "sample"


class TestExtractDomText(unittest.TestCase):
    def test_plain_text(self):
        self.assertEqual(extract_dom_text("hello"), "hello")

    def test_tags_become_spaces(self):
        self.assertEqual(extract_dom_text("<p>你好</p><p>世界</p>"), "你好 世界")

    def test_removes_script_style(self):
        html = "<p>正文</p><script>var x=1;</script><style>p{color:red}</style><p>结尾</p>"
        self.assertEqual(extract_dom_text(html), "正文 结尾")

    def test_unescape_entities(self):
        self.assertEqual(extract_dom_text("<p>a&amp;b &lt;c&gt;</p>"), "a&b <c>")

    def test_collapse_whitespace(self):
        self.assertEqual(extract_dom_text("<p>a</p>\n\n  <p>b</p>"), "a b")

    def test_nested_inline_no_extra_space(self):
        # <p>a<b>bc</b>d</p> → 文本节点 a/bc/d，两两相邻 → a bc d
        self.assertEqual(extract_dom_text("<p>a<b>bc</b>d</p>"), "a bc d")

    def test_void_elements(self):
        self.assertEqual(extract_dom_text("<p>a<br/>b</p>"), "a b")
        self.assertEqual(extract_dom_text("<p>a<br>b</p>"), "a b")

    def test_img_no_text(self):
        self.assertEqual(extract_dom_text("<p>a<img src='x'/>b</p>"), "a b")

    def test_empty(self):
        self.assertEqual(extract_dom_text(""), "")
        self.assertEqual(extract_dom_text("<div></div>"), "")

    def test_leading_trailing_trim(self):
        self.assertEqual(extract_dom_text("  \n  <p>x</p>\n  "), "x")

    def test_sample_chapter_stable(self):
        # 样本章节：提取结果稳定且含代码块内容
        book = EpubBook(str(SAMPLE_DIR / "sample_nav3.epub")).open()
        try:
            text = extract_dom_text(book.chapter_text(2))
            self.assertIn("黎明前的第 1 个观测窗口", text)
            self.assertIn("def detect(signal, noise)", text)  # 代码块未被剥离
            self.assertIn("信噪比", text)
        finally:
            book.close()

    def test_same_as_plaintext_extractor(self):
        # search 模块的 extract_plain_text 与此处同源
        from app.text import extract_dom_text as extract_plain_text

        html = "<p>引力波探测</p><script>x</script><style>y</style><p>&amp; 数据</p>"
        self.assertEqual(extract_dom_text(html), extract_plain_text(html))


if __name__ == "__main__":
    unittest.main()
