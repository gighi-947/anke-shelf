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

    def _fixture(self):
        return json.loads(self.FIXTURE.read_text(encoding="utf-8"))

    def test_render_ast_matches_fixture(self):
        from app.gululu_ast import render_ast

        cases = self._fixture()["cases"]
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

    def test_floor_pipeline_matches_fixture(self):
        """完整楼层管线（沉浸 → 助手 → 骰点/迷雾 → 渲染）的双端期望。"""
        from app.gululu_assistant import prepare_reader_experience_nodes
        from app.gululu_ast import render_ast
        from app.gululu_immersive import prepare_immersive_floor

        cases = self._fixture()["floor_cases"]
        self.assertGreaterEqual(len(cases), 12)
        for case in cases:
            with self.subTest(case=case["id"]):
                immersive = prepare_immersive_floor(case["nodes"])
                jump_map = case.get("jump_map", {})
                html = render_ast(
                    prepare_reader_experience_nodes(immersive.nodes, case["floor_id"]),
                    image_resolver=lambda url: url,
                    jump_floor_resolver=lambda floor, m=jump_map: m.get(str(floor), ""),
                    source_book_id=case["source_book_id"],
                )
                self.assertEqual(html, case["expected"])
                self.assertEqual(immersive.vfx, case.get("expected_vfx", ""))
                if "expected_background" in case:
                    self.assertEqual(immersive.background_update, case["expected_background"])
                else:
                    self.assertIsNone(immersive.background_update)


    def test_comment_cases_match_fixture(self):
        """评论公开字段与 EPUB 评论块渲染的双端期望。"""
        from app.gululu_comments import comment_to_public, render_comment_block

        cases = self._fixture()["comment_cases"]
        self.assertGreaterEqual(len(cases), 4)
        for case in cases:
            with self.subTest(case=case["id"]):
                html = render_comment_block(
                    case["comments"],
                    label=case["label"],
                    opus=case["opus"],
                )
                self.assertEqual(html, case["expected_html"])
                public = [comment_to_public(item) for item in case["comments"]]
                self.assertEqual(public, case["expected_public"])


class GululuEpubFixtureTest(unittest.TestCase):
    """骨碌碌 EPUB 章节分组与单楼 HTML 的双端 golden（Android 侧见 GululuEpubTest）。"""

    FIXTURE = CONTRACTS / "fixtures" / "gululu" / "ast-cases.json"

    def _fixture(self):
        return json.loads(self.FIXTURE.read_text(encoding="utf-8"))

    def test_chapter_groups_match_fixture(self):
        from app.gululu_epub import _chapter_groups

        cases = self._fixture()["epub_group_cases"]
        self.assertGreaterEqual(len(cases), 3)
        for case in cases:
            with self.subTest(case=case["id"]):
                groups = _chapter_groups(
                    case["floor_index"],
                    case["chapter_index"],
                    case["floors"],
                )
                got = [
                    {"title": title, "floor_nums": [item["floorNum"] for item, _ in items]}
                    for title, items in groups
                ]
                self.assertEqual(got, case["expected_groups"])

    def test_floor_html_matches_fixture(self):
        from app.gululu_epub import _floor_html
        from app.gululu_immersive import prepare_immersive_floor

        cases = self._fixture()["epub_floor_cases"]
        self.assertGreaterEqual(len(cases), 2)
        for case in cases:
            with self.subTest(case=case["id"]):
                immersive = prepare_immersive_floor(case["floor"]["paragraphContents"])
                html = _floor_html(
                    case["index_item"],
                    case["floor"],
                    case["comments"],
                    immersive,
                    lambda url: url,
                    lambda floor: "",
                    case["source_book_id"],
                )
                self.assertEqual(html, case["expected_html"])


class NativeAppendFixtureTest(unittest.TestCase):
    """原生书增量追加的双端 golden 对照（Android 侧同夹具见 NativeAppendFixtureTest）。

    夹具为 contracts/fixtures/native-book/append-cases.json，期望值是【正确行为】
    的权威定义。重点覆盖"楼层正文含字面量 </body>"这类边界输入——历史上双端
    都用 replace 定位插入点，会把新楼层插到每一处匹配，造成内容重复并破坏
    text_offset 坐标；两端曾同时存在该缺陷而互不察觉，因为契约此前只覆盖
    数据格式与 text_offset 计算，未覆盖"章节追加算法"。
    """

    FIXTURE_REL = "fixtures/native-book/append-cases.json"

    def setUp(self):
        import sys
        import tempfile
        from unittest.mock import patch

        sys.path.insert(0, str(PROJECT / "ngapost2md-python"))
        self.tmp = tempfile.TemporaryDirectory()
        self.patcher = patch(
            "app.native_book.nga_library_dir", return_value=Path(self.tmp.name)
        )
        self.patcher.start()

    def tearDown(self):
        self.patcher.stop()
        self.tmp.cleanup()

    def _floor(self, d):
        from ngapost2md.models import Floor

        return Floor(
            lou=d["lou"], pid=d["pid"], timestamp=0, username="u", user_id=1,
            like_num=0, content="", raw_content=d["raw_content"],
        )

    def test_append_matches_fixture(self):
        from ngapost2md.models import Tiezi

        from app.native_book import (
            append_container,
            load_meta,
            native_dir_for,
            write_container,
        )

        data = _load(self.FIXTURE_REL)
        cases = data["cases"]
        defaults = data.get("defaults", {})
        self.assertGreaterEqual(len(cases), 5)
        for c in cases:
            with self.subTest(case=c["name"]):
                per_chapter = c["per_chapter"]
                image_mode = c.get("image_mode", defaults.get("image_mode", "online"))
                theme = c.get("theme", defaults.get("theme", "light"))
                folder = "case"

                initial = [self._floor(f) for f in c["initial"]]
                tiezi = Tiezi(
                    tid=1, author_id=0, title="t", username="a",
                    folder_name=folder, floors=initial, max_lou=-1,
                )
                write_container(
                    folder, tiezi, initial, per_chapter, image_mode, theme, "bookid123"
                )

                new_floors = [self._floor(f) for f in c["append"]]
                got = append_container(
                    folder, new_floors, per_chapter, image_mode, theme, "bookid123"
                )

                exp = c["expected"]
                self.assertEqual(exp["appended_count"], got, "追加数不符")

                native_dir = native_dir_for(folder)
                meta = load_meta(native_dir)
                self.assertEqual(exp["chapter_count"], len(meta["chapters"]))
                self.assertEqual(
                    exp["last_chapter_floor_count"], meta["chapters"][-1]["floor_count"]
                )
                self.assertEqual(
                    exp["last_chapter_last_lou"], meta["chapters"][-1]["last_lou"]
                )

                text = (native_dir / exp["chapter_file"]).read_text(encoding="utf-8")
                probe = exp["probe"]
                self.assertEqual(
                    exp["probe_count"],
                    text.count(probe),
                    f"探针 {probe} 出现次数不符——重复插入或未插入都会体现在这里",
                )
                self.assertEqual(exp["body_marker_count"], text.count("</body>"))
                if exp["probe_before_last_body_marker"]:
                    self.assertLess(
                        text.index(probe),
                        text.rindex("</body>"),
                        "追加内容必须落在真实闭合标签之前",
                    )


if __name__ == "__main__":
    unittest.main()
