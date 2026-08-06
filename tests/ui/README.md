# UI 自动化与诊断脚本

- `runner.py` — 主回归套件（需真实 WebView 窗口）：分页/双页/标注/统计/
  NGA 面板/主题/沉浸式等断言，全 PASS 退出码 0。
- `verify_nga_download.py` — 端到端真实下载验证（需网络 + Cookie）。
- `repro_gui.py` — 下载面板开/关、取消任务、桥接导入的手动复现。
- `repro_theme.py` — 主题/配色相关的手动复现。
- `diag_cancel_import.py` — 取消与导入并发诊断。

运行方式：`python -m tests.ui.<脚本名>`。
