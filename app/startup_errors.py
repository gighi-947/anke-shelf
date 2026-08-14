"""启动期友好错误提示（P0）。

发行版启动崩溃的已知元凶是 pythonnet/.NET 运行时加载失败
（`Failed to resolve Python.Runtime.Loader.Initialize`）。pywebview 自己都
起不来时，无法用 pywebview 弹窗，这里用 Win32 MessageBox 兜底，给出用户
可执行的修复指引。文本生成逻辑保持纯函数，便于单测。
"""


def is_runtime_load_error(exc: BaseException) -> bool:
    """判断是否属于 pythonnet/.NET 运行时加载失败。"""
    msg = str(exc).lower()
    return any(
        key in msg
        for key in (
            "python.runtime",
            "loader.initialize",
            "pythonnet",
            ".net framework",
            ".net runtime",
        )
    )


def startup_error_message(exc: BaseException) -> str:
    """生成面向用户的启动失败提示文案（含诊断信息与日志位置）。"""
    detail = str(exc).strip() or exc.__class__.__name__
    reason = (
        "加载 .NET / pythonnet 运行时组件失败"
        if is_runtime_load_error(exc)
        else "启动时发生错误"
    )
    return (
        "安科书架无法启动\n\n"
        f"{reason}。\n\n"
        "请依次尝试：\n"
        "1. 若从网上下载的压缩包解压运行，先对该 zip 文件右键 → 属性 → "
        "勾选“解除锁定”，再重新解压运行。\n"
        "2. 安装或修复 .NET Framework 4.8（或更高版本）后重启本程序。\n"
        f"\n诊断信息：{detail}\n"
        r"日志位置：%APPDATA%\AnkeShelf\logs\startup.log"
    )


def show_startup_error(exc: BaseException) -> None:
    """弹启动失败提示；MessageBox 也不可用时退回 stderr/日志。"""
    text = startup_error_message(exc)
    try:
        import ctypes

        ctypes.windll.user32.MessageBoxW(0, text, "安科书架启动失败", 0x10)
    except Exception:
        try:
            import sys

            print(text, file=sys.stderr)
        except Exception:
            pass
