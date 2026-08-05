"""高 DPI 感知：Per-Monitor V2，避免 UI 字体模糊。"""
import os


def enable_per_monitor_dpi() -> None:
    """创建窗口前把进程设为按显示器缩放感知。

    pywebview 的 winforms 后端只调用 SetProcessDPIAware()（系统级），
    大屏高缩放时 WebView2 内容会被位图拉伸导致文字发虚。此函数在
    创建窗口前把进程设为按显示器缩放感知，浏览器按真实 DPI 渲染。
    """
    if os.name != "nt":
        return
    try:
        import ctypes

        user32 = ctypes.windll.user32
        try:
            user32.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
            user32.SetProcessDpiAwarenessContext.restype = ctypes.c_bool
            # DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
            if user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4)):
                return
        except Exception:
            pass
        try:
            shcore = ctypes.windll.shcore
            shcore.SetProcessDpiAwareness.argtypes = [ctypes.c_int]
            shcore.SetProcessDpiAwareness.restype = ctypes.c_int
            # PROCESS_PER_MONITOR_DPI_AWARE = 2
            shcore.SetProcessDpiAwareness(2)
        except Exception:
            try:
                user32.SetProcessDPIAware()
            except Exception:
                pass
    except Exception:
        pass
