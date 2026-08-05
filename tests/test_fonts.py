"""内置默认字体测试。"""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app.fonts import list_fonts, resolve_font_file


class BundledFontTest(unittest.TestCase):
    def test_bundled_font_resolvable(self):
        p = resolve_font_file("system", "weidqczfkyxk.ttf")
        self.assertIsNotNone(p)
        self.assertTrue(p.is_file())
        self.assertEqual(p.name, "weidqczfkyxk.ttf")

    def test_bundled_font_listed(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch("app.fonts.data_dir", return_value=Path(tmp)):
                fonts = list_fonts()
                hit = [f for f in fonts if f["key"] == "sys:weidqczfkyxk.ttf"]
                self.assertEqual(len(hit), 1)
                self.assertEqual(hit[0]["url"], "/font/system/weidqczfkyxk.ttf")


if __name__ == "__main__":
    unittest.main()
