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
        self.assertEqual(s.get("pagination"), False)  # 默认滚动阅读
        self.assertEqual(s.get("theme_mode"), "")  # 默认跟随 theme
        self.assertEqual(s.get("dual_page"), False)
        self.assertEqual(s.get("auto_dual"), True)
        self.assertEqual(s.get("custom_font"), "sys:weidqczfkyxk.ttf")
        self.assertEqual(s.get("custom_bg"), "")
        self.assertEqual(s.get("custom_primary"), "")
        self.assertEqual(s.get("custom_accent"), "")
        self.assertEqual(s.get("custom_text"), "")
        self.assertEqual(s.get("settings_version"), 3)

    def test_legacy_file_migrated_once(self):
        self.path.write_text(
            json.dumps({"theme": "light", "pagination": False, "custom_font": ""}),
            encoding="utf-8",
        )
        s = Settings(self.path)
        s.load()
        self.assertEqual(s.get("theme"), "light")  # 用户其他偏好保留
        self.assertEqual(s.get("pagination"), False)  # 新默认：滚动阅读
        self.assertEqual(s.get("custom_font"), "sys:weidqczfkyxk.ttf")  # 新默认字体
        self.assertEqual(s.get("settings_version"), 3)
        saved = json.loads(self.path.read_text(encoding="utf-8"))
        self.assertEqual(saved["settings_version"], 3)

        # 再次加载不再覆盖用户后续选择
        s2 = Settings(self.path)
        s2.load()
        self.assertEqual(s2.get("settings_version"), 3)

    def test_v2_file_with_old_paged_default_migrated_to_scroll(self):
        # v1.0.0 发行版默认误为分页；settings_version=2 的旧文件应一次性切到滚动
        self.path.write_text(
            json.dumps({
                "theme": "dark",
                "pagination": True,
                "dual_page": False,
                "auto_dual": True,
                "settings_version": 2,
            }),
            encoding="utf-8",
        )
        s = Settings(self.path)
        s.load()
        self.assertEqual(s.get("pagination"), False)
        self.assertEqual(s.get("settings_version"), 3)


if __name__ == "__main__":
    unittest.main()
