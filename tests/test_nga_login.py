"""NGA 应用内登录控制器单元测试（用假 pywebview 窗口，不启动 WebView2）。"""
import unittest
from unittest.mock import patch

from app.nga_login import NgaLoginController, parse_nga_cookie_text


class _FakeEvents:
    def __init__(self):
        self.closed = _FakeClosedEvent()


class _FakeClosedEvent:
    def __init__(self):
        self._handlers = []
        self._closed = False

    def is_set(self):
        return self._closed

    def set(self):
        self._closed = True
        for fn in self._handlers:
            fn()

    def __iadd__(self, fn):
        self._handlers.append(fn)
        return self


class FakeWindow:
    def __init__(self, url="https://bbs.nga.cn/", cookies=None):
        self.url = url
        self.cookies = cookies or []
        self.events = _FakeEvents()
        self.shown = 0
        self.destroyed = 0
        self.cleared = 0

    def show(self):
        self.shown += 1

    def destroy(self):
        self.destroyed += 1
        self.events.closed.set()

    def clear_cookies(self):
        self.cleared += 1

    def get_current_url(self):
        return self.url

    def get_cookies(self):
        return self.cookies


def _morsel(key, value):
    return {key: type("M", (), {"key": key, "value": value})()}


class TestParseNgaCookieText(unittest.TestCase):
    def test_extracts_uid_cid(self):
        parsed = parse_nga_cookie_text("ngaPassportUid=123; ngaPassportCid=abc; x=1")
        self.assertEqual(parsed, {"uid": "123", "cid": "abc"})

    def test_missing_returns_empty(self):
        self.assertEqual(parse_nga_cookie_text("a=b"), {"uid": "", "cid": ""})


class TestNgaLoginController(unittest.TestCase):
    def _status_patch(self):
        return patch("app.nga_login.load_nga_config", return_value={"configured": False})

    def test_start_creates_window(self):
        ctl = NgaLoginController()
        win = FakeWindow()
        with patch("webview.create_window", return_value=win) as create, \
                self._status_patch():
            st = ctl.start()
        self.assertEqual(st["state"], "waiting")
        self.assertTrue(st["open"])
        self.assertEqual(win.shown, 1)
        self.assertTrue(create.called)

    def test_start_reuses_open_window(self):
        ctl = NgaLoginController()
        win = FakeWindow()
        with patch("webview.create_window", return_value=win), self._status_patch():
            ctl.start()
            st = ctl.start()
        self.assertEqual(win.shown, 2)
        self.assertEqual(st["state"], "waiting")

    def test_extract_saves_and_destroys(self):
        ctl = NgaLoginController()
        win = FakeWindow(cookies=[_morsel("ngaPassportUid", "111"), _morsel("ngaPassportCid", "222")])
        with patch("webview.create_window", return_value=win), \
                patch("app.nga_login.save_nga_config") as save, \
                self._status_patch():
            ctl.start()
            st = ctl.extract()
        save.assert_called_once_with({"uid": "111", "cid": "222"})
        self.assertEqual(st["state"], "done")
        self.assertEqual(win.destroyed, 1)
        self.assertEqual(win.cleared, 1)

    def test_extract_missing_cookie_keeps_window(self):
        ctl = NgaLoginController()
        win = FakeWindow(cookies=[_morsel("other", "1")])
        with patch("webview.create_window", return_value=win), \
                patch("app.nga_login.save_nga_config") as save, \
                self._status_patch():
            ctl.start()
            st = ctl.extract()
        save.assert_not_called()
        self.assertEqual(st["state"], "error")
        self.assertIn("未检测到完整登录 Cookie", st["error"])
        self.assertEqual(win.destroyed, 0)

    def test_extract_rejects_non_nga_host(self):
        ctl = NgaLoginController()
        win = FakeWindow(url="https://evil.example.com/")
        with patch("webview.create_window", return_value=win), \
                patch("app.nga_login.save_nga_config") as save, \
                self._status_patch():
            ctl.start()
            st = ctl.extract()
        save.assert_not_called()
        self.assertEqual(st["state"], "error")
        self.assertIn("仅允许在 bbs.nga.cn", st["error"])

    def test_cancel_destroys(self):
        ctl = NgaLoginController()
        win = FakeWindow()
        with patch("webview.create_window", return_value=win), self._status_patch():
            ctl.start()
            st = ctl.cancel()
        self.assertEqual(st["state"], "cancelled")
        self.assertEqual(win.destroyed, 1)

    def test_user_close_marks_cancelled(self):
        ctl = NgaLoginController()
        win = FakeWindow()
        with patch("webview.create_window", return_value=win), self._status_patch():
            ctl.start()
            win.events.closed.set()  # 用户点 X
            st = ctl.status()
        self.assertEqual(st["state"], "cancelled")
        self.assertFalse(st["open"])


if __name__ == "__main__":
    unittest.main()
