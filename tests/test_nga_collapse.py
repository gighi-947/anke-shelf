"""折叠块 foldBox → details 转换契约（双端共享夹具）。

夹具见 contracts/fixtures/nga-format/collapse-cases.json；Android 侧由
NgaFormatHtmlTest 消费同一文件。历史缺陷：内容捕获的非贪婪 `(.+?)</span></div>`
在折叠内容含 `</span></div>` 紧邻（NGA 对 [size]×[/align] 的渲染）时截断，
只收进开头一行（2026-10-10 用户反馈实图复现）。
"""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "ngapost2md-python"))

from ngapost2md.format_html import render_content_html

FIXTURE = (
    Path(__file__).resolve().parents[1]
    / "contracts" / "fixtures" / "nga-format" / "collapse-cases.json"
)


class NgaCollapseContractTest(unittest.TestCase):
    def test_collapse_cases(self):
        cases = json.loads(FIXTURE.read_text(encoding="utf-8"))["cases"]
        self.assertGreaterEqual(len(cases), 5)
        for case in cases:
            with self.subTest(case=case["name"]):
                out = render_content_html(case["input"], None, lambda u: u)
                self.assertEqual(case["expected"], out)


if __name__ == "__main__":
    unittest.main()
