"""NGA 骰子（ROLL）详细骰点折叠回归（2026-08-26）。

NGA 骰子原始 HTML：<div class='dice'><b>ROLL : d2</b>=d2(2)=<b>2</b></div>
此前丢弃详细骰点（d2(2)），只渲染结果。修复后改为 details 折叠：
默认收起详细骰点，点击 summary 展开（对齐 NGA 折叠交互，双端 HTML 一致）。
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "ngapost2md-python"))

from ngapost2md.format_html import render_content_html


class NgaDiceTest(unittest.TestCase):
    def _render(self, raw: str) -> str:
        return render_content_html(raw, None, lambda u: u)

    def test_single_dice_folds_detail(self):
        raw = "<div class='dice'><b>ROLL : d2</b>=d2(2)=<b>2</b></div>"
        out = self._render(raw)
        # 用 details 折叠，summary 显示结果
        self.assertIn('<details class="nga-dice"', out)
        self.assertIn("<summary", out)
        self.assertIn("ROLL : d2= <b>2</b>", out)
        # 详细骰点进折叠内容
        self.assertIn("d2(2)", out)
        # 原始 dice div 不残留
        self.assertNotIn("class='dice'", out)

    def test_multi_dice_folds_detail(self):
        raw = (
            "<div class='dice'><b>ROLL : 4d10</b>"
            "=d10(9)+d10(10)+d10(3)+d10(4)=<b>26</b></div>"
        )
        out = self._render(raw)
        self.assertIn("ROLL : 4d10= <b>26</b>", out)
        self.assertIn("d10(9)+d10(10)+d10(3)+d10(4)", out)

    def test_detail_enters_coordinates(self):
        """详细骰点进坐标：不加 data-textpos-exclude（与 NGA 折叠一致，
        提取器与 JS TextPos 同源提取，搜索索引与渲染一致）。"""
        raw = "<div class='dice'><b>ROLL : d2</b>=d2(2)=<b>2</b></div>"
        out = self._render(raw)
        self.assertNotIn("data-textpos-exclude", out)


if __name__ == "__main__":
    unittest.main()
