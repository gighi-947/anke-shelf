"""应用入口单实例守卫单元测试（不启动 GUI，tasklist/os.kill 用 mock 验证）。"""
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from app.instance_guard import cleanup_stale_instance, release_instance_lock


class TestInstanceGuard(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_creates_lock(self):
        with patch("app.instance_guard._instance_lock_file",
                   return_value=self.root / "instance.lock"):
            cleanup_stale_instance()
            lock = (self.root / "instance.lock").read_text(encoding="utf-8")
            self.assertEqual(int(lock), __import__("os").getpid())
            release_instance_lock()
            self.assertFalse((self.root / "instance.lock").exists())

    def test_ignores_missing_pid(self):
        lock = self.root / "instance.lock"
        lock.write_text("999999999", encoding="utf-8")  # 不存在的 PID
        with patch("app.instance_guard._instance_lock_file", return_value=lock):
            cleanup_stale_instance()
            self.assertEqual(int(lock.read_text(encoding="utf-8")), __import__("os").getpid())

    def test_kills_stale_python(self):
        lock = self.root / "instance.lock"
        lock.write_text("424242", encoding="utf-8")
        killed = []
        with patch("app.instance_guard._instance_lock_file", return_value=lock), \
                patch("app.instance_guard.subprocess.run",
                      return_value=SimpleNamespace(stdout='"python.exe","424242","Console"')), \
                patch("app.instance_guard.os.kill",
                      side_effect=lambda pid, sig: killed.append(pid)):
            cleanup_stale_instance()
        self.assertIn(424242, killed)
        self.assertEqual(int(lock.read_text(encoding="utf-8")), __import__("os").getpid())

    def test_skips_non_python_pid(self):
        lock = self.root / "instance.lock"
        lock.write_text("777777", encoding="utf-8")
        killed = []
        with patch("app.instance_guard._instance_lock_file", return_value=lock), \
                patch("app.instance_guard.subprocess.run",
                      return_value=SimpleNamespace(stdout='"notepad.exe","777777","Console"')), \
                patch("app.instance_guard.os.kill",
                      side_effect=lambda pid, sig: killed.append(pid)):
            cleanup_stale_instance()
        self.assertEqual(killed, [])


if __name__ == "__main__":
    unittest.main()
