"""设置持久化与旧版迁移测试。"""
import json
import tempfile
import unittest
from pathlib import Path

from app.settings import Settings


class SettingsMigrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "settings.json"

    def tearDown(self):
        self.tmp.cleanup()

    def test_new_install_defaults(self):
        s = Settings(self.path)
        s.load()
        self.assertEqual(s.get("pagination"), True)
        self.assertEqual(s.get("dual_page"), False)
        self.assertEqual(s.get("auto_dual"), True)
        self.assertEqual(s.get("custom_font"), "sys:weidqczfkyxk.ttf")
        self.assertEqual(s.get("settings_version"), 2)

    def test_legacy_file_migrated_once(self):
        self.path.write_text(
            json.dumps({"theme": "light", "pagination": False, "custom_font": ""}),
            encoding="utf-8",
        )
        s = Settings(self.path)
        s.load()
        self.assertEqual(s.get("theme"), "light")  # 用户其他偏好保留
        self.assertEqual(s.get("pagination"), True)  # 新默认：分页翻页
        self.assertEqual(s.get("custom_font"), "sys:weidqczfkyxk.ttf")  # 新默认字体
        self.assertEqual(s.get("settings_version"), 2)
        saved = json.loads(self.path.read_text(encoding="utf-8"))
        self.assertEqual(saved["settings_version"], 2)

        # 再次加载不再覆盖用户后续选择
        s2 = Settings(self.path)
        s2.load()
        self.assertEqual(s2.get("settings_version"), 2)


if __name__ == "__main__":
    unittest.main()
