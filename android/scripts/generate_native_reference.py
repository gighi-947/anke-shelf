"""生成原生书容器参考 JSON（供安卓 JVM 单测差分）。

用法（在仓库根目录执行）：
    python android/scripts/generate_native_reference.py

输出到 android/app/src/test/resources/reference/native/：
    write25/book/{meta.json,floors.json}        初始写入（25 楼，每章 20 楼）
    toc_split/book/{meta.json,floors.json}      目录分章（30 楼 + 两个目录章节）
    append60/book/{meta.json,floors.json}       初始写入后追加 26~60 楼

场景与 tests/test_native_book.py 保持一致；meta/floors 由桌面端
app/native_book.py 原样生成，安卓端解析/写入结果必须与之同构。
"""

import sys
from pathlib import Path
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT))
sys.path.insert(0, str(PROJECT / "ngapost2md-python"))

from ngapost2md.models import Floor, Tiezi  # noqa: E402
from app.native_book import append_container, write_container  # noqa: E402

OUT = PROJECT / "android" / "app" / "src" / "test" / "resources" / "reference" / "native"


def floor(lou: int, pid: int, text: str) -> Floor:
    return Floor(
        lou=lou, pid=pid, timestamp=0, username="u", user_id=1,
        like_num=0, content=text, raw_content=f"<p>{text}</p>",
    )


def tiezi(n_floors: int) -> Tiezi:
    floors = [floor(1, 0, "main")]
    for lou in range(2, n_floors + 1):
        floors.append(floor(lou, 1000 + lou, f"floor-{lou}"))
    return Tiezi(
        tid=123, author_id=0, title="标题", username="作者",
        folder_name="123", floors=floors,
        created_time="2026-01-01T00:00:00+08:00",
        updated_time="2026-01-01T00:00:00+08:00",
    )


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    with patch("app.native_book.nga_library_dir", return_value=OUT):
        # 1) 初始写入：主楼 + 20 楼 + 4 楼 = 3 章
        t25 = tiezi(25)
        write_container("write25", t25, t25.floors, 20, "online", "light", "bookid123")

        # 2) 目录分章：主楼 / 第一卷(2~14) / 第二卷(15~30)
        t30 = tiezi(30)
        toc_chapters = [
            {"title": "第一卷", "lead": [("起点", 1002)], "days": []},
            {"title": "第二卷", "lead": [("起点", 1015)], "days": []},
        ]
        write_container(
            "toc_split", t30, t30.floors, 20, "online", "light", "bookid123",
            toc_chapters=toc_chapters, toc_mode="split",
        )

        # 3) 追加：25 楼初始 → 26~30 填满末章 → 31~60 开新章（第 4 章）
        t25b = tiezi(25)
        write_container("append60", t25b, t25b.floors, 20, "online", "light", "bookid123")
        new1 = [floor(lou, 2000 + lou, f"floor-{lou}") for lou in range(26, 31)]
        new2 = [floor(lou, 3000 + lou, f"floor-{lou}") for lou in range(31, 61)]
        append_container("append60", new1, 20, "online", "light", "bookid123")
        append_container("append60", new2, 20, "online", "light", "bookid123")

    for p in sorted(OUT.rglob("*.json")):
        print(p.relative_to(PROJECT))


if __name__ == "__main__":
    main()
