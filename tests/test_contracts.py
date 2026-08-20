"""双端契约 B1 —— Windows 侧 golden 验证。

覆盖：contracts/text/text-cases.json（权威纯文本）、
contracts/fixtures/native-book/basic-nga（期望纯文本 + NativeBook 读取）、
contracts/*.schema.json（JSON Schema 校验，需 jsonschema）。
"""
import json
import unittest
from pathlib import Path

from app.native_book import NativeBook
from app.text import cp_index_from_utf16, extract_dom_text

PROJECT = Path(__file__).resolve().parent.parent
CONTRACTS = PROJECT / "contracts"


def _load(rel: str):
    return json.loads((CONTRACTS / rel).read_text(encoding="utf-8"))


class TextCasesTest(unittest.TestCase):
    def test_all_cases_match_canonical(self):
        cases = _load("text/text-cases.json")["cases"]
        self.assertGreaterEqual(len(cases), 15)
        for c in cases:
            with self.subTest(case=c["id"]):
                self.assertEqual(extract_dom_text(c["html"]), c["expected"])

    def test_points_consistent(self):
        cases = _load("text/text-cases.json")["cases"]
        for c in cases:
            for p in c.get("points", []):
                with self.subTest(case=c["id"], quote=p["quote"]):
                    cp = cp_index_from_utf16(c["expected"], p["offset"])
                    self.assertEqual(
                        c["expected"][cp:cp + len(p["quote"])],
                        p["quote"],
                    )


class NativeBookFixtureTest(unittest.TestCase):
    FIXTURE = CONTRACTS / "fixtures" / "native-book" / "basic-nga"

    def test_plaintext_matches_expected(self):
        expected = _load("fixtures/native-book/basic-nga/expected_plaintext.json")
        for name, want in expected.items():
            html = (self.FIXTURE / "chapters" / name).read_text(encoding="utf-8")
            self.assertEqual(extract_dom_text(html), want)

    def test_native_book_readable(self):
        book = NativeBook(str(self.FIXTURE)).open()
        self.assertEqual(book.id, "fixture-basic-nga-0001")
        self.assertEqual(book.title, "测试安科：契约样本")
        self.assertEqual(len(book.chapters), 3)
        self.assertIn("第一楼正文", book.chapter_text(1))
        self.assertIsNone(book.read_file("../meta.json"))  # 路径穿越拒绝
        book.close()


class SchemaTest(unittest.TestCase):
    def setUp(self):
        try:
            import jsonschema
        except ImportError:
            self.skipTest("jsonschema 未安装")
        self.jsonschema = jsonschema

    def test_native_book_fixture_validates(self):
        meta_schema = _load("native-book/meta.schema.json")
        floors_schema = _load("native-book/floors.schema.json")
        meta = _load("fixtures/native-book/basic-nga/meta.json")
        floors = _load("fixtures/native-book/basic-nga/floors.json")
        self.jsonschema.validate(meta, meta_schema)
        self.jsonschema.validate(floors, floors_schema)

    def test_progress_schema(self):
        schema = _load("progress/progress.schema.json")
        self.jsonschema.validate(
            {
                "version": 2,
                "progress": {
                    "b1": {"chapter_index": 3, "text_offset": 100, "updated_at": "2026-08-10T00:00:00Z"}
                },
            },
            schema,
        )
        self.jsonschema.validate(
            {
                "version": 2,
                "progress": {
                    "b1": {"chapter_index": 0, "text_offset": 0, "page_index": 4, "page_total": 9, "scroll_ratio": -1.0}
                },
            },
            schema,
        )

    def test_annotations_schema(self):
        schema = _load("annotation/annotations.schema.json")
        self.jsonschema.validate(
            {
                "version": 1,
                "books": {
                    "b1": {
                        "highlights": [
                            {"id": "h1", "chapter_index": 0, "start_offset": 1,
                             "end_offset": 5, "text": "x", "color": "yellow"}
                        ],
                        "bookmarks": [
                            {"id": "m1", "chapter_index": 0, "offset": 3, "text": "y"}
                        ],
                    }
                },
            },
            schema,
        )

    def test_settings_schema(self):
        schema = _load("settings/settings.schema.json")
        self.jsonschema.validate({"settings_version": 3, "theme": "dark"}, schema)


class NgaTocFixtureTest(unittest.TestCase):
    """NGA 目录楼解析 + split 分章的双端 golden 对照（Android 侧同夹具见 NgaTocParserTest）。"""

    FIXTURE = CONTRACTS / "fixtures" / "nga-toc"

    def setUp(self):
        import sys

        from app.nga_service import _nga_root

        root = _nga_root().as_posix()
        if root not in sys.path:
            sys.path.insert(0, root)

    def _expected(self):
        return json.loads((self.FIXTURE / "expected-toc.json").read_text(encoding="utf-8"))

    def test_parse_toc_matches_fixture(self):
        from ngapost2md.toc import parse_toc

        from app.native_book import _serialize_toc

        content = (self.FIXTURE / "toc-floor.html").read_text(encoding="utf-8")
        serialized = _serialize_toc(parse_toc(content))
        want = self._expected()["chapters"]
        # 无条目的折叠块：Windows _serialize_toc 会保留空 entries，契约要求两端都丢弃
        serialized = [c for c in serialized if c["entries"]]
        self.assertEqual(len(serialized), len(want))
        for got, exp in zip(serialized, want):
            self.assertEqual(got["title"], exp["title"])
            self.assertEqual(got["entries"], [[t, p] for t, p in exp["entries"]])

    def test_split_grouping_matches_fixture(self):
        from ngapost2md.toc import parse_toc

        from app.native_book import _group_floors_by_toc

        class _Floor:
            def __init__(self, pid, lou):
                self.pid = pid
                self.lou = lou

        grouping = self._expected()["split_grouping"]
        floors = [_Floor(f["pid"], f["lou"]) for f in grouping["floors"]]
        toc_chapters = parse_toc((self.FIXTURE / "toc-floor.html").read_text(encoding="utf-8"))
        grouped = _group_floors_by_toc(floors, toc_chapters)
        got = [
            {
                "title": title,
                "first_lou": group[0].lou,
                "last_lou": group[-1].lou,
                "floor_count": len(group),
            }
            for title, group in grouped
        ]
        self.assertEqual(got, grouping["expected"])


class GululuAstFixtureTest(unittest.TestCase):
    """骨碌碌 AST → XHTML 的双端 golden 对照（Android 侧同夹具见 GululuAstTest）。"""

    FIXTURE = CONTRACTS / "fixtures" / "gululu" / "ast-cases.json"

    def test_render_ast_matches_fixture(self):
        from app.gululu_ast import render_ast

        cases = json.loads(self.FIXTURE.read_text(encoding="utf-8"))["cases"]
        self.assertGreaterEqual(len(cases), 15)
        for case in cases:
            with self.subTest(case=case["id"]):
                mode = case.get("image_mode", "online")
                mapping = case.get("image_map", {})
                if mode == "none":
                    resolver = lambda url: ""  # noqa: E731
                elif mode == "embedded":
                    resolver = lambda url, m=mapping: m.get(url, "")  # noqa: E731
                else:
                    resolver = lambda url: url  # noqa: E731
                got = render_ast(case["nodes"], image_resolver=resolver)
                self.assertEqual(got, case["expected"])


if __name__ == "__main__":
    unittest.main()
