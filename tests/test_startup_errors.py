"""启动失败友好提示的单元测试（P0）。"""
import unittest

from app.startup_errors import is_runtime_load_error, startup_error_message


class TestStartupErrors(unittest.TestCase):
    def test_detects_pythonnet_loader_error(self):
        self.assertTrue(
            is_runtime_load_error(
                RuntimeError(
                    "Failed to resolve Python.Runtime.Loader.Initialize from "
                    r"...\pythonnet\runtime\Python.Runtime.dll"
                )
            )
        )

    def test_detects_net_framework_error(self):
        self.assertTrue(is_runtime_load_error(RuntimeError("requires .NET Framework 4.8")))

    def test_other_error_not_runtime_load(self):
        self.assertFalse(is_runtime_load_error(ValueError("boom")))

    def test_message_includes_guidance_and_detail(self):
        msg = startup_error_message(
            RuntimeError("Failed to resolve Python.Runtime.Loader.Initialize")
        )
        self.assertIn("解除锁定", msg)
        self.assertIn(".NET Framework 4.8", msg)
        self.assertIn("Python.Runtime.Loader.Initialize", msg)
        self.assertIn(r"%APPDATA%\AnkeShelf\logs\startup.log", msg)

    def test_message_falls_back_for_unknown_error(self):
        msg = startup_error_message(ValueError("boom"))
        self.assertIn("启动时发生错误", msg)
        self.assertIn("boom", msg)


if __name__ == "__main__":
    unittest.main()
