"""Gululu incremental-update baseline and merge invariants."""
import json
import tempfile
import unittest
from pathlib import Path

from app.gululu_epub import GululuIndex, GululuSnapshot
from app.gululu_update import (
    BaselineInvalid,
    BaselineMissing,
    BaselineOk,
    GululuUpdateConflict,
    load_baseline,
    merge_incremental_snapshot,
    plan_incremental_update,
    write_baseline,
)


def _snapshot() -> GululuSnapshot:
    return GululuSnapshot(
        detail={"bookId": 12, "name": "测试"},
        floor_index=[
            {"floorId": 101, "floorNum": 1},
            {"floorId": 102, "floorNum": 2},
        ],
        chapter_index=[],
        floors=[
            {"id": 101, "floorNum": 1, "paragraphContents": []},
            {"id": 102, "floorNum": 2, "paragraphContents": []},
        ],
    )


class GululuUpdateBaselineTest(unittest.TestCase):
    def test_load_result_distinguishes_missing_valid_and_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "snapshot.json"
            self.assertIsInstance(load_baseline(path, 12), BaselineMissing)

            write_baseline(path, 12, _snapshot(), "embedded")
            loaded = load_baseline(path, 12)
            self.assertIsInstance(loaded, BaselineOk)
            self.assertEqual(loaded.image_mode, "embedded")
            self.assertEqual([item["id"] for item in loaded.snapshot.floors], [101, 102])

            path.write_text("{broken", encoding="utf-8")
            invalid = load_baseline(path, 12)
            self.assertIsInstance(invalid, BaselineInvalid)
            self.assertIn("JSON", invalid.error)

    def test_plan_fetches_only_appended_floors_and_merges_in_remote_order(self):
        baseline = BaselineOk(_snapshot(), "online")
        remote = GululuIndex(
            detail={"bookId": 12, "name": "测试新版"},
            floor_index=[
                {"floorId": 101, "floorNum": 1},
                {"floorId": 102, "floorNum": 2},
                {"floorId": 103, "floorNum": 3},
            ],
            chapter_index=[{"floor": 3, "title": "新章"}],
        )

        plan = plan_incremental_update(baseline, remote)
        self.assertEqual(plan.new_floor_ids, (103,))
        merged = merge_incremental_snapshot(
            baseline,
            remote,
            [{"id": 103, "floorNum": 3, "paragraphContents": []}],
        )
        self.assertEqual(merged.detail["name"], "测试新版")
        self.assertEqual([item["id"] for item in merged.floors], [101, 102, 103])

    def test_reordered_or_removed_existing_floor_is_explicit_conflict(self):
        baseline = BaselineOk(_snapshot(), "online")
        remote = GululuIndex(
            detail={"bookId": 12, "name": "测试"},
            floor_index=[
                {"floorId": 102, "floorNum": 1},
                {"floorId": 101, "floorNum": 2},
            ],
            chapter_index=[],
        )

        with self.assertRaisesRegex(GululuUpdateConflict, "完整重新导入"):
            plan_incremental_update(baseline, remote)


if __name__ == "__main__":
    unittest.main()
