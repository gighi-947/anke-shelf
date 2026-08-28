"""错误处理纪律守卫（零依赖，标准库 ast）。

背景（见 AGENTS.md §5 失败显式化）：
    禁止 `catch(Exception)` 后静默吞错/降级。`except Exception` 只允许用于
    「转换为显式失败结果」或「记录日志后继续」这两种有交代的写法。

本守卫只抓**真正的静默吞错**：except 体内既没有 raise / return / continue /
break，也没有任何日志或错误上报调用（log.* / logger.* / warnings.warn /
print / on_error 等）。这类代码会让业务失败无声消失——正是历史上
「进度静默丢失」「标注写入失败无提示」的成因。

不抓的（有交代的）：
- 转换为显式结果：`except Exception as e: return RepoResult.Err(...)`
- 记录日志后继续：`except Exception as e: log.warning(...)`
- 带 `# noqa: BLE001` 且体内有上述动作之一

误报兜底：新增合规写法时，除补充本文件的白名单外，更鼓励改成
「raise / return / 记日志」三选一，让代码自己说明意图。
"""
import ast
import re
import unittest
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
SCAN_ROOTS = ["app", "ngapost2md-python"]

# except 体内出现任一名称即视为"有交代"（日志/告警/上报）。
RESPONSIBLE_CALLS = {
    "log", "logger", "logging", "warn", "warning", "error", "exception",
    "debug", "info", "critical", "print", "warnings", "report", "notify",
    "on_error", "onerror", "record", "emit", "trace", "capture_exception",
}

# 可选依赖探测 / 分级降级 / 非关键 UI 增强的固有写法：except 体只做
# "置空 + 继续"，且调用方后续有回退路径或失败不影响核心功能
# （curl_cffi 不可用则回退 urllib、DPI API 不可用则尝试下一级、
# 窗口尺寸记忆失败不影响启动）。这类不是业务失败，不视为吞错。
PROBE_FALLBACK_PATHS = {
    ("app/dpi.py", 24), ("app/dpi.py", 32), ("app/dpi.py", 35), ("app/dpi.py", 37),
    ("app/server.py", 219), ("app/server.py", 228),
    ("app/main.py", 69), ("app/main.py", 80), ("app/main.py", 82),
    ("app/main.py", 304),
    ("app/startup_errors.py", 57),  # 最内层兜底：print 到 stderr 也失败则无可为
    ("app/nga_service.py", 308),
    # ngapost2md-python 是上游 ngapost2md 的 Python 重写版，保持与上游一致，
    # 不为守卫生效而改动第三方重写代码。
    ("ngapost2md-python/ngapost2md/cli.py", 76),
    ("ngapost2md-python/ngapost2md/epub.py", 294),
    ("ngapost2md-python/ngapost2md/nga.py", 313),
}


def _has_responsible_action(body: list[ast.stmt]) -> bool:
    """except 体内是否有"交代"动作：raise / 返回 / 流程控制 / 日志。"""
    for node in body:
        # raise / return / continue / break 都是显式交代
        if isinstance(node, (ast.Raise, ast.Return, ast.Continue, ast.Break)):
            return True
        # 赋值后紧跟 raise/return 的兜底（如 err = ...; return err）
    for node in ast.walk(ast.Module(body=body, type_ignores=[])):
        if isinstance(node, (ast.Raise, ast.Return, ast.Continue, ast.Break)):
            return True
        if isinstance(node, ast.Call):
            func = node.func
            name = ""
            if isinstance(func, ast.Name):
                name = func.id
            elif isinstance(func, ast.Attribute):
                # log.warning / logger.error / _log.error -> 取最左基名
                base = func
                while isinstance(base, ast.Attribute):
                    base = base.value
                name = base.id if isinstance(base, ast.Name) else func.attr
            else:
                name = ""
            # _log / startup_log / logger_nga 等模块级 logger 均算有交代：
            # 只要名称以 log/logger 词根作为前缀或后缀成分出现即可。
            lowered = name.lower()
            if lowered in RESPONSIBLE_CALLS:
                return True
            parts = {p for p in re.split(r"[_\-]", lowered) if p}
            if parts & {"log", "logs", "logger", "logging"}:
                return True
            if any(lowered.endswith(suffix) for suffix in ("_log", "_logger")):
                return True
    return False


def _is_bare_pass(body: list[ast.stmt]) -> bool:
    """except 体只有 pass / Ellipsis（'...'）。"""
    return all(
        isinstance(n, ast.Pass)
        or (isinstance(n, ast.Expr) and isinstance(n.value, ast.Constant)
            and n.value.value is Ellipsis)
        for n in body
    )


def find_silent_handlers() -> list[tuple[str, int, str]]:
    offenders: list[tuple[str, int, str]] = []
    for root in SCAN_ROOTS:
        for path in sorted((PROJECT / root).rglob("*.py")):
            try:
                tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            except (SyntaxError, UnicodeDecodeError):
                continue
            rel = path.relative_to(PROJECT).as_posix()  # 统一 / 分隔（Windows 是 \）
            for handler in (n for n in ast.walk(tree) if isinstance(n, ast.ExceptHandler)):
                if handler.type is None:
                    continue  # 裸 except 由 lint 管，本守卫聚焦 Exception
                type_name = ""
                if isinstance(handler.type, ast.Name):
                    type_name = handler.type.id
                elif isinstance(handler.type, ast.Attribute):
                    type_name = handler.type.attr
                if type_name not in {"Exception", "BaseException"}:
                    continue
                if (rel, handler.lineno) in PROBE_FALLBACK_PATHS:
                    continue
                if _is_bare_pass(handler.body):
                    offenders.append((rel, handler.lineno, "except 体为空（pass）"))
                    continue
                if not _has_responsible_action(handler.body):
                    offenders.append((rel, handler.lineno, "except Exception 后无任何交代"))
    return offenders


class ErrorHandlingDisciplineTest(unittest.TestCase):
    def test_no_silent_exception_swallowing(self):
        offenders = find_silent_handlers()
        self.assertEqual(
            [],
            offenders,
            "发现静默吞错（except Exception 后既不 raise/return 也不记日志）。\n"
            "按 AGENTS.md §5，业务失败必须显式化：转换为显式结果、记日志或重新抛出。\n"
            + "\n".join(f"  {f}:{line}  {why}" for f, line, why in offenders),
        )

    def test_scanner_detects_known_patterns(self):
        """守卫自检：确保检测逻辑本身有效（防止重构后变成永真断言）。"""
        bad = "try:\n    f()\nexcept Exception:\n    pass\n"
        tree = ast.parse(bad)
        handler = next(n for n in ast.walk(tree) if isinstance(n, ast.ExceptHandler))
        self.assertTrue(_is_bare_pass(handler.body), "空 except 应被识别")

        bad2 = "try:\n    f()\nexcept Exception:\n    x = 1\n"
        h2 = next(n for n in ast.walk(ast.parse(bad2)) if isinstance(n, ast.ExceptHandler))
        self.assertFalse(_has_responsible_action(h2.body), "无交代except应被识别")

        good = "try:\n    f()\nexcept Exception as e:\n    log.warning('%s', e)\n"
        h3 = next(n for n in ast.walk(ast.parse(good)) if isinstance(n, ast.ExceptHandler))
        self.assertTrue(_has_responsible_action(h3.body), "记日志的 except 应被放行")

        good2 = "try:\n    f()\nexcept Exception as e:\n    return Err(e)\n"
        h4 = next(n for n in ast.walk(ast.parse(good2)) if isinstance(n, ast.ExceptHandler))
        self.assertTrue(_has_responsible_action(h4.body), "转显式结果的 except 应被放行")


if __name__ == "__main__":
    unittest.main()
