"""Windows 窗口壳状态回归测试。"""
import unittest

from app import main as app_main


class _Event:
    def __init__(self):
        self.handlers = []

    def __iadd__(self, handler):
        self.handlers.append(handler)
        return self

    def fire(self):
        for handler in self.handlers:
            handler()


class _Events:
    def __init__(self):
        self.maximized = _Event()
        self.restored = _Event()


class _Window:
    def __init__(self):
        self.events = _Events()
        self.calls = []

    def toggle_fullscreen(self):
        self.calls.append("toggle")

    def maximize(self):
        self.calls.append("maximize")


class TestWindowFullscreenState(unittest.TestCase):
    def test_exit_restores_previously_maximized_window(self):
        window = _Window()
        toggle = app_main._make_window_fullscreen_toggle(window)
        window.events.maximized.fire()

        toggle(True)
        toggle(False)

        self.assertEqual(window.calls, ["toggle", "toggle", "maximize"])

    def test_exit_keeps_previously_normal_window_normal(self):
        window = _Window()
        toggle = app_main._make_window_fullscreen_toggle(window)

        toggle(True)
        toggle(False)

        self.assertEqual(window.calls, ["toggle", "toggle"])


if __name__ == "__main__":
    unittest.main()
