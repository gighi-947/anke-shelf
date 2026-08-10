"""诊断导出（B6）：打包内容与凭据隔离。"""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from app.diagnostics import build_diagnostics


class DiagnosticsTest(unittest.TestCase):
    def test_build_packages_safe_files_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "data"
            (root / "logs").mkdir(parents=True)
            (root / "logs" / "startup.log").write_text("hello\n", encoding="utf-8")
            (root / "settings.json").write_text(
                json.dumps({"settings_version": 3, "theme": "dark"}),
                encoding="utf-8",
            )
            (root / "nga_config.ini").write_text("ngaPassportCid=secret\n", encoding="utf-8")
            dest = Path(tmp) / "out"
            dest.mkdir()
            zip_path = build_diagnostics(dest, data_root=root)
            self.assertTrue(zip_path.is_file())
            with zipfile.ZipFile(zip_path) as z:
                names = z.namelist()
                self.assertIn("version.txt", names)
                self.assertIn("settings.json", names)
                self.assertIn("logs/startup.log", names)
                self.assertNotIn("nga_config.ini", names)
                self.assertNotIn("config.ini", names)


if __name__ == "__main__":
    unittest.main()
