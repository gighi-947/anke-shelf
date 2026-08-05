"""路径与旧版数据目录迁移测试。"""
import os
import tempfile
import unittest
from pathlib import Path

from app.paths import APP_DIR_NAME, ensure_data_dir, migrate_legacy_data


class PathsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self._root = Path(self._tmp.name)
        self._old_appdata = os.environ.get("APPDATA")
        os.environ["APPDATA"] = str(self._root)

    def tearDown(self):
        if self._old_appdata is None:
            os.environ.pop("APPDATA", None)
        else:
            os.environ["APPDATA"] = self._old_appdata
        self._tmp.cleanup()

    def test_default_dir_name(self):
        self.assertEqual(APP_DIR_NAME, "AnkeShelf")

    def test_migrate_renames_old_dir(self):
        old = self._root / "EpubReader"
        old.mkdir()
        (old / "shelf.json").write_text('{"books": []}', encoding="utf-8")

        migrate_legacy_data()

        self.assertFalse(old.exists())
        new = self._root / "AnkeShelf"
        self.assertTrue(new.exists())
        self.assertEqual(
            (new / "shelf.json").read_text(encoding="utf-8"),
            '{"books": []}',
        )

    def test_migrate_skips_when_new_has_data(self):
        old = self._root / "EpubReader"
        old.mkdir()
        (old / "shelf.json").write_text('{"books": ["old"]}', encoding="utf-8")
        new = self._root / "AnkeShelf"
        new.mkdir()
        (new / "shelf.json").write_text('{"books": ["new"]}', encoding="utf-8")

        migrate_legacy_data()

        self.assertTrue(old.exists())
        self.assertEqual(
            (new / "shelf.json").read_text(encoding="utf-8"),
            '{"books": ["new"]}',
        )

    def test_ensure_data_dir_creates_new_dir(self):
        ensure_data_dir()
        self.assertTrue((self._root / "AnkeShelf" / "covers").is_dir())


if __name__ == "__main__":
    unittest.main()
