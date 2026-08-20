"""NGA 应用内登录（Windows 二级窗）。

用 pywebview 打开一个独立的 NGA 登录窗口（固定 bbs.nga.cn），用户登录后
从 WebView2 Cookie 中提取 ngaPassportUid / ngaPassportCid，保存到本机
nga_config.ini。仅登录用途：只加载 bbs.nga.cn，拿到凭据即关闭窗口。
"""
import logging
import re
import threading
from urllib.parse import urlsplit

from .nga_config import load_nga_config, save_nga_config

log = logging.getLogger("app.nga_login")

NGA_LOGIN_URL = "https://bbs.nga.cn/"
NGA_LOGIN_HOST = "bbs.nga.cn"


def _extract_cookie_value(text: str, name: str) -> str:
    m = re.search(
        r"" + re.escape(name) + r"""\s*=\s*["']?([^;"'\s]+)""",
        text or "",
        re.IGNORECASE,
    )
    return m.group(1).strip() if m else ""


def parse_nga_cookie_text(text: str) -> dict:
    """从完整 Cookie 头 / 任意文本中提取 uid/cid（与 web/js/nga-cookie.js 对齐）。"""
    raw = text or ""
    return {
        "uid": _extract_cookie_value(raw, "ngaPassportUid"),
        "cid": _extract_cookie_value(raw, "ngaPassportCid"),
    }


def _cookies_to_text(cookies) -> str:
    parts = []
    for cookie in cookies or []:
        if isinstance(cookie, str):
            parts.append(cookie)
            continue
        for key, morsel in cookie.items():
            parts.append(f"{key}={morsel.value}")
    return "; ".join(parts)


class NgaLoginController:
    """惰性创建/销毁 NGA 登录二级窗，并提供状态机供 API 层查询。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._window = None
        self._state = "idle"  # idle | waiting | done | cancelled | error
        self._error = ""
        self._shutting_down = False

    # ---------- 生命周期 ----------

    def shutdown(self) -> None:
        """主窗口关闭前调用：确保登录二级窗不阻止应用退出。"""
        with self._lock:
            self._shutting_down = True
            window = self._window
        if window is not None:
            try:
                if not window.events.closed.is_set():
                    window.destroy()
            except Exception:
                log.exception("关闭 NGA 登录窗口失败")

    def _on_closed(self) -> None:
        with self._lock:
            if self._shutting_down:
                return
            if self._state == "waiting":
                self._state = "cancelled"
                self._error = "登录窗口已关闭"
            self._window = None
        log.info("NGA 登录窗口已关闭（state=%s）", self._state)

    # ---------- 状态 ----------

    def status(self) -> dict:
        with self._lock:
            state = self._state
            error = self._error
            window_alive = self._window is not None and not self._window.events.closed.is_set()
        return self._make_status(state, error, window_alive)

    def _make_status(self, state: str, error: str, window_alive: bool) -> dict:
        return {
            "state": state,
            "open": window_alive,
            "configured": bool(load_nga_config().get("configured")),
            "error": error,
        }

    # ---------- 操作 ----------

    def start(self) -> dict:
        with self._lock:
            if self._shutting_down:
                return self._make_status("error", "应用正在退出", False)
            window = self._window
            if window is not None and not window.events.closed.is_set():
                self._state = "waiting"
                self._error = ""
                try:
                    window.show()
                except Exception:
                    log.exception("显示 NGA 登录窗口失败")
                return self._make_status(self._state, self._error, True)

        try:
            import webview

            window = webview.create_window(
                "NGA 登录 · 安科书架",
                NGA_LOGIN_URL,
                width=480,
                height=760,
                min_size=(360, 480),
                on_top=True,
                text_select=True,
            )
        except Exception as exc:
            log.exception("创建 NGA 登录窗口失败")
            with self._lock:
                self._state = "error"
                self._error = f"创建登录窗口失败：{exc}"
                self._window = None
                state, error = self._state, self._error
            return self._make_status(state, error, False)

        if window is None:
            with self._lock:
                self._state = "error"
                self._error = "创建登录窗口失败：pywebview 返回空窗口"
                self._window = None
                state, error = self._state, self._error
            return self._make_status(state, error, False)

        with self._lock:
            self._window = window
            self._state = "waiting"
            self._error = ""
        window.events.closed += self._on_closed
        try:
            window.show()
        except Exception:
            log.exception("显示 NGA 登录窗口失败")
        return self.status()

    def extract(self) -> dict:
        with self._lock:
            window = self._window
        if window is None or window.events.closed.is_set():
            with self._lock:
                self._state = "error"
                self._error = "登录窗口未打开"
                self._window = None
                state, error = self._state, self._error
            return self._make_status(state, error, False)

        try:
            current_url = window.get_current_url() or ""
            host = urlsplit(current_url).hostname or ""
            if host != NGA_LOGIN_HOST:
                with self._lock:
                    self._state = "error"
                    self._error = f"仅允许在 bbs.nga.cn 提取凭据（当前：{host or '未知'}）"
                    state, error = self._state, self._error
                return self._make_status(state, error, True)

            cookie_text = _cookies_to_text(window.get_cookies())
            parsed = parse_nga_cookie_text(cookie_text)
        except Exception as exc:
            log.exception("读取 NGA 登录 Cookie 失败")
            with self._lock:
                self._state = "error"
                self._error = f"读取登录 Cookie 失败：{exc}"
                state, error = self._state, self._error
            return self._make_status(state, error, True)

        if not parsed["uid"] or not parsed["cid"]:
            with self._lock:
                self._state = "error"
                self._error = "未检测到完整登录 Cookie，请先在窗口中登录后重试"
                state, error = self._state, self._error
            return self._make_status(state, error, True)

        try:
            save_nga_config({"uid": parsed["uid"], "cid": parsed["cid"]})
        except Exception as exc:
            log.exception("保存 NGA 登录配置失败")
            with self._lock:
                self._state = "error"
                self._error = f"保存登录配置失败：{exc}"
                state, error = self._state, self._error
            return self._make_status(state, error, True)

        try:
            window.clear_cookies()
        except Exception:
            log.exception("清理登录窗口 Cookie 失败（不影响已保存配置）")

        try:
            window.destroy()
        except Exception:
            log.exception("关闭登录窗口失败（不影响已保存配置）")

        with self._lock:
            self._state = "done"
            self._error = ""
            self._window = None
        return self.status()

    def cancel(self) -> dict:
        with self._lock:
            window = self._window
            self._window = None
            self._state = "cancelled"
            self._error = ""
        if window is not None and not window.events.closed.is_set():
            try:
                window.clear_cookies()
            except Exception:
                log.exception("清理登录窗口 Cookie 失败")
            try:
                window.destroy()
            except Exception:
                log.exception("关闭登录窗口失败")
        return self.status()
