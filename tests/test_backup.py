"""统一备份包测试：创建 / 校验 / 篡改 / 恢复 / 不覆盖守卫。"""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from app.backup import BACKUP_FORMAT, create_backup, restore_backup, verify_backup
from app.storage import atomic_write_json


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.data = self.root / "data"
        self.data.mkdir()
        self.paths = {}
        for name in ("shelf", "progress", "settings", "annotations", "statistics"):
            f = self.data / f"{name}.json"
            atomic_write_json(f, {"version": 1, name: name})
            self.paths[name] = f

    def tearDown(self):
        self.tmp.cleanup()

    def _create(self):
        dest = self.root / "backups"
        return create_backup(dest, self.paths, app_version="v1.2.0"), dest

    def test_create_and_verify(self):
        result, dest = self._create()
        self.assertTrue(result["ok"])
        zip_path = Path(result["path"])
        self.assertTrue(zip_path.exists())
        with zipfile.ZipFile(zip_path) as zf:
            names = set(zf.namelist())
            manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
        self.assertIn("manifest.json", names)
        self.assertEqual(manifest["format"], BACKUP_FORMAT)
        self.assertEqual(len(manifest["files"]), 5)
        check = verify_backup(zip_path)
        self.assertTrue(check["ok"], check["errors"])
        self.assertEqual(len(check["files"]), 5)

    def test_tampered_checksum_fails(self):
        result, _ = self._create()
        zip_path = Path(result["path"])
        # 直接修改 manifest：把第一个文件的 sha256 改成错误值
        with zipfile.ZipFile(zip_path, "r") as zf:
            manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
        manifest["files"][0]["sha256"] = "0" * 64
        new_path = zip_path.with_name(zip_path.name + ".new")
        with zipfile.ZipFile(new_path, "w") as zf:
            with zipfile.ZipFile(result["path"], "r") as old:
                for item in old.infolist():
                    data = old.read(item.filename)
                    if item.filename == "manifest.json":
                        data = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
                    zf.writestr(item, data)
        zip_path.unlink()
        new_path.rename(zip_path)
        check = verify_backup(zip_path)
        self.assertFalse(check["ok"])
        self.assertTrue(any("校验和不匹配" in e for e in check["errors"]))

    def test_missing_manifest_fails(self):
        bad = self.root / "bad.zip"
        with zipfile.ZipFile(bad, "w") as zf:
            zf.writestr("shelf.json", "{}")
        check = verify_backup(bad)
        self.assertFalse(check["ok"])
        self.assertIn("缺少 manifest.json", check["errors"])

    def test_restore_requires_overwrite_confirmation(self):
        result, _ = self._create()
        zip_path = Path(result["path"])
        targets = {name: self.root / f"restore-{name}.json" for name in self.paths}
        # 空目标：直接恢复
        r = restore_backup(zip_path, targets, overwrite=False)
        self.assertTrue(r["ok"], r)
        self.assertEqual(len(r["restored"]), 5)
        # 目标已存在且未确认覆盖：拒绝写入
        r2 = restore_backup(zip_path, targets, overwrite=False)
        self.assertFalse(r2["ok"])
        self.assertTrue(r2.get("needs_overwrite"))
        before = targets["shelf"].read_text(encoding="utf-8")
        # 显式覆盖后恢复成功且内容来自备份
        r3 = restore_backup(zip_path, targets, overwrite=True)
        self.assertTrue(r3["ok"], r3)
        after = targets["shelf"].read_text(encoding="utf-8")
        self.assertEqual(before, after)  # 备份内容与首次恢复内容一致


if __name__ == "__main__":
    unittest.main()
