"""NGA 表情图源切换回归（2026-10-10）。

img4.nga.178.com 已下线（NXDOMAIN，AliDNS 实测），现役宿主 img4.nga.cn
（http/https 均通）。生成侧 SMILE_BASE 换新宿主；读时 `_normalize_smile_url`
把存量书章节里烤入的历史死域/明文 URL 归一到现役宿主。
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "ngapost2md-python"))

from ngapost2md.format_html import SMILE_BASE, render_content_html

from app.server import _normalize_smile_url


class NgaSmileHostTest(unittest.TestCase):
    def test_render_uses_live_host(self):
        out = render_content_html("[s:ac:blink]", None, lambda u: u)
        self.assertIn("https://img4.nga.cn/ngabbs/post/smile/ac0.png", out)
        self.assertNotIn("nga.178.com", out)
        self.assertTrue(SMILE_BASE.startswith("https://img4.nga.cn"))

    def test_normalize_dead_host(self):
        self.assertEqual(
            "https://img4.nga.cn/ngabbs/post/smile/ac0.png",
            _normalize_smile_url("https://img4.nga.178.com/ngabbs/post/smile/ac0.png"),
        )
        self.assertEqual(
            "https://img4.nga.cn/ngabbs/post/smile/a2_10.png",
            _normalize_smile_url("http://img4.nga.cn/ngabbs/post/smile/a2_10.png"),
        )

    def test_normalize_untouched(self):
        attach = "https://img.nga.178.com/attachments/mon_1.jpg"
        self.assertEqual(attach, _normalize_smile_url(attach))
        self.assertEqual("not a url", _normalize_smile_url("not a url"))
        # 本地 file 方案不参与归一
        local = "file:///local/ngabbs/post/smile/x.png"
        self.assertEqual(local, _normalize_smile_url(local))


if __name__ == "__main__":
    unittest.main()
