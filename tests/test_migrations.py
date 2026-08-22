"""统一迁移框架（B6）：run_migrations 步骤序列与设置迁移。"""
import tempfile
import unittest
from pathlib import Path

from app.migrations import run_migrations
from app.settings import Settings


class MigrationsTest(unittest.TestCase):
    def test_steps_run_in_order(self):
        data = run_migrations(
            {"version": 0, "x": 1},
            {
                1: lambda d: {**d, "x": 2, "version": 1},
                2: lambda d: {**d, "x": 3, "version": 2},
            },
            current_version=2,
        )
        self.assertEqual(data, {"version": 2, "x": 3})

    def test_missing_step_raises(self):
        with self.assertRaises(ValueError):
            run_migrations({"version": 0}, {2: lambda d: d}, current_version=2)

    def test_settings_legacy_migrates_to_v3(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "settings.json"
            f.write_text(
                '{"settings_version": 1, "pagination": true, "theme": "dark"}',
                encoding="utf-8",
            )
            s = Settings(f)
            s.load()
            self.assertEqual(s.get("settings_version"), 3)
            self.assertFalse(s.get("pagination"))
            self.assertEqual(s.get("custom_font"), "sys:weidqczfkyxk.woff2")
            disk = f.read_text(encoding="utf-8")
            self.assertIn('"settings_version": 3', disk)


if __name__ == "__main__":
    unittest.main()
