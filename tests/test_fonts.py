"""内置默认字体测试。"""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app.fonts import list_fonts, resolve_font_file


class BundledFontTest(unittest.TestCase):
    def test_bundled_font_resolvable(self):
        p = resolve_font_file("system", "weidqczfkyxk.woff2")
        self.assertIsNotNone(p)
        self.assertTrue(p.is_file())
        # canonical 化后：逻辑名解析到单一源 LXGWWenKai-Regular.woff2（WOFF2 无损压缩）
        self.assertEqual(p.name, "LXGWWenKai-Regular.woff2")

    def test_bundled_font_listed(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch("app.fonts.data_dir", return_value=Path(tmp)):
                fonts = list_fonts()
                hit = [f for f in fonts if f["key"] == "sys:weidqczfkyxk.woff2"]
                self.assertEqual(len(hit), 1)
                self.assertEqual(hit[0]["url"], "/font/system/weidqczfkyxk.woff2")

    def test_legacy_ttf_name_still_resolves(self):
        """升级兼容：旧设置里的 sys:weidqczfkyxk.ttf 继续解析到 WOFF2 源。"""
        p = resolve_font_file("system", "weidqczfkyxk.ttf")
        self.assertIsNotNone(p)
        self.assertEqual(p.name, "LXGWWenKai-Regular.woff2")


if __name__ == "__main__":
    unittest.main()
