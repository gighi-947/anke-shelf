"""原生增量书容器测试：写入/读取/追加/重载/EPUB 重建。"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT / "ngapost2md-python"))

from ngapost2md.models import Floor, Tiezi  # noqa: E402

from app.book_manager import BookManager  # noqa: E402
from app.native_book import (  # noqa: E402
    NativeBook,
    append_container,
    is_native_dir,
    load_floors,
    load_meta,
    native_dir_for,
    rebuild_epub_for_native,
    write_container,
)


def _floor(lou: int, pid: int, text: str) -> Floor:
    return Floor(
        lou=lou, pid=pid, timestamp=0, username="u", user_id=1,
        like_num=0, content=text, raw_content=f"<p>{text}</p>",
    )


class NativeBookTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.patcher = patch("app.native_book.nga_library_dir", return_value=self.root)
        self.patcher.start()

    def tearDown(self):
        self.patcher.stop()
        self.tmp.cleanup()

    def _tiezi(self, n_floors: int) -> Tiezi:
        floors = [_floor(1, 0, "main")]
        for lou in range(2, n_floors + 1):
            floors.append(_floor(lou, 1000 + lou, f"floor-{lou}"))
        return Tiezi(
            tid=123, author_id=0, title="标题", username="作者",
            folder_name="123", floors=floors,
            created_time="2026-01-01T00:00:00+08:00",
            updated_time="2026-01-01T00:00:00+08:00",
        )

    def test_write_read_append(self):
        tiezi = self._tiezi(25)
        native_dir = write_container("123", tiezi, tiezi.floors, 20, "online", "light", "bookid123")
        self.assertTrue(is_native_dir(native_dir))
        meta = load_meta(native_dir)
        self.assertEqual(len(meta["chapters"]), 3)  # 主楼 + 20 楼 + 4 楼
        self.assertEqual(meta["last_lou"], 25)

        book = NativeBook(str(native_dir)).open()
        self.assertEqual(book.id, "bookid123")
        self.assertEqual(len(book.chapters), 3)
        self.assertIn("floor-25", book.chapter_text(2))
        old_ch2 = book.chapter_text(2)[:200]

        # 追加 26~30：应填入最后一个章节（余 16 空位）
        new_floors = [_floor(lou, 2000 + lou, f"floor-{lou}") for lou in range(26, 31)]
        count = append_container("123", new_floors, 20, "online", "light", "bookid123")
        self.assertEqual(count, 5)
        meta = load_meta(native_dir)
        self.assertEqual(len(meta["chapters"]), 3)
        self.assertEqual(meta["chapters"][-1]["floor_count"], 9)
        self.assertEqual(len(load_floors(native_dir)), 30)

        # 追加 31~60：填满后开新章
        new_floors2 = [_floor(lou, 3000 + lou, f"floor-{lou}") for lou in range(31, 61)]
        count2 = append_container("123", new_floors2, 20, "online", "light", "bookid123")
        self.assertEqual(count2, 30)
        meta = load_meta(native_dir)
        self.assertEqual(len(meta["chapters"]), 4)
        self.assertEqual(meta["chapters"][-1]["floor_count"], 19)

        book2 = NativeBook(str(native_dir)).open()
        self.assertTrue(book2.chapter_text(2).startswith(old_ch2))
        self.assertIn("floor-60", book2.chapter_text(3))

    def test_append_when_floor_content_contains_body_marker(self):
        """回归：楼层正文含字面量 `</body>` 时，追加内容只能插入一次。

        背景：原实现用 text.replace("</body>", html + "</body>")，而 str.replace
        替换【所有】匹配。安科作品常在正文贴 HTML 示例（[code] 块），
        渲染后字面量 `</body>` 会留在章节里（render_content_html 不整体转义
        正文），导致追加时新楼层被插入到每一处，章节内容重复、DOM 错乱，
        并连带破坏 text_offset 坐标（影响阅读进度）。
        """
        # 正文中含字面量 </body>：真实闭合标签仍是最后一处（正文在其之前）
        marker = "<" + "/body>"
        tiezi = self._tiezi(3)
        tiezi.floors[-1].raw_content = f"<p>代码示例：{marker}</p>"

        native_dir = write_container(
            "123", tiezi, tiezi.floors, 20, "online", "light", "bookid123"
        )
        chapter_file = native_dir / "chapters" / "0001.xhtml"
        self.assertEqual(
            2,
            chapter_file.read_text(encoding="utf-8").count(marker),
            "前置条件：初始章节应含 2 处 marker（正文 1 处 + 真实闭合 1 处）",
        )

        new_floors = [_floor(4, 4004, "floor-4")]
        append_container("123", new_floors, 20, "online", "light", "bookid123")

        text = chapter_file.read_text(encoding="utf-8")
        self.assertEqual(
            1,
            text.count("floor-4"),
            "新楼层内容被重复插入（replace 命中多处）——应只插入真实闭合处一次",
        )
        # 追加内容必须位于真实闭合标签之前，而不是正文中间
        self.assertLess(
            text.index("floor-4"),
            text.rindex(marker),
            "追加内容应插在真实闭合标签之前",
        )

    def test_append_refuses_when_chapter_has_no_body_marker(self):
        """回归：章节文件找不到 </body> 时必须显式失败，不能静默丢楼层。

        背景：原实现 replace 无匹配时静默返回原文本，紧随其后却无条件推进
        floor_count / last_lou。结果是 meta 声称有这些楼、章节里却没有，
        且因 existing_pids 去重，后续更新不会再补——不可逆的静默丢失。
        """
        tiezi = self._tiezi(3)
        native_dir = write_container(
            "123", tiezi, tiezi.floors, 20, "online", "light", "bookid123"
        )
        # 模拟章节文件损坏/缺失闭合标记
        chapter_file = native_dir / "chapters" / "0001.xhtml"
        chapter_file.write_text("<html><body>no closing marker", encoding="utf-8")

        meta_before = load_meta(native_dir)
        floors_before = len(load_floors(native_dir))

        with self.assertRaises(ValueError):
            append_container(
                "123", [_floor(4, 4004, "floor-4")], 20, "online", "light", "bookid123"
            )

        # 失败必须留下"未推进"的状态：meta 与 floors 都不得被改写
        meta_after = load_meta(native_dir)
        self.assertEqual(len(load_floors(native_dir)), floors_before, "失败后不应写入 floors")
        self.assertEqual(
            meta_after["last_lou"], meta_before["last_lou"], "失败后不应推进 last_lou"
        )
        self.assertEqual(
            meta_after["chapters"][-1]["floor_count"],
            meta_before["chapters"][-1]["floor_count"],
            "失败后不应推进 floor_count（否则 meta 与章节内容不一致）",
        )

    def test_book_manager_registers_native(self):
        tiezi = self._tiezi(3)
        native_dir = write_container("123", tiezi, tiezi.floors, 20, "online", "light", "bookid123")
        books = BookManager()
        book = books.register(str(native_dir))
        self.assertEqual(book.id, "bookid123")
        self.assertEqual(len(book.chapters), 2)
        books.close_all()

    def test_rebuild_epub(self):
        tiezi = self._tiezi(3)
        write_container("123", tiezi, tiezi.floors, 20, "online", "light", "bookid123")
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            epub_path = rebuild_epub_for_native("123")
        self.assertTrue(epub_path.is_file())
        self.assertEqual(epub_path.name, "post.epub")

    def test_write_container_toc_split(self):
        tiezi = self._tiezi(30)
        toc_chapters = [
            {"title": "第一卷", "lead": [("起点", 1002)], "days": []},
            {"title": "第二卷", "lead": [("起点", 1015)], "days": []},
        ]
        native_dir = write_container(
            "123", tiezi, tiezi.floors, 20, "online", "light", "bookid123",
            toc_chapters=toc_chapters, toc_mode="split",
        )
        meta = load_meta(native_dir)
        titles = [c["title"] for c in meta["chapters"]]
        self.assertEqual(titles[0], "序章 · 主楼")
        self.assertEqual(titles[1], "第一卷")
        self.assertEqual(titles[2], "第二卷")
        self.assertEqual(meta["chapters"][1]["first_lou"], 2)
        self.assertEqual(meta["chapters"][1]["last_lou"], 14)
        self.assertEqual(meta["chapters"][2]["first_lou"], 15)
        self.assertEqual(meta["chapters"][2]["last_lou"], 30)
        self.assertEqual(meta["toc_mode"], "split")
        self.assertEqual(meta["toc"][0]["title"], "第一卷")
        self.assertEqual(meta["toc"][0]["entries"][0][1], 1002)


if __name__ == "__main__":
    unittest.main()
