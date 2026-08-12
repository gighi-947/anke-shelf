# 安科书架（AnkeShelf）—— NGA 安科阅读器集成方案

> 状态（2026-08-13 核对）：本文为安卓端立项前的早期集成方案。其后已落地并演进：
> NGA 下载即建原生书容器（`app/native_book.py`）、下载/导出整合页、连载热更新；
> 安卓端在 `android/` 独立重写（不复用本方案中的复用假设）。本方案保留为设计背景。

## 目标

一站式安科阅读体验：输入帖子链接 → 下载 NGA 帖子 → 本地转换为带
**完整 NGA 原版视觉样式**的 EPUB → 在阅读器中直接阅读（分页/滚动、
进度记忆、全文搜索、标注、阅读辅助全部复用现有阅读器能力）。

## 架构（基于两个既有项目）

```
D:\Codex\project1\
├── app\                      ← 现有 EPUB 阅读器后端（扩展）
│   ├── nga_config.py          NGA 凭据/UA 配置（%APPDATA%\AnkeShelf\nga_config.ini）
│   └── nga_service.py         下载服务：单飞 + 进度 + 取消 + 入库
├── web\js\nga_download.js     下载面板（tid/选项/进度/NGA 配置）
├── ngapost2md-python\         ← ngapost2md Python 重写版（增强）
│   └── ngapost2md\
│       ├── nga.py             增加 progress/cancel 回调（兼容原 CLI）
│       └── epub.py            增加图片进度/取消/不含图片模式
└── tests\test_nga_service.py  集成层单测
```

## 数据流

1. UI 面板收集参数（tid、authorid 只看楼主、max_floors、图片模式、
   主题、目录 pid、每章楼层数）→ `nga_start_download`
2. `NgaService` 后台线程：配置加载 → 拉页（进度）→ 格式化 → Markdown
   → EPUB（图片进度/取消）→ 注册到书架（`BookRecord.nga_tid > 0`）
3. 书架卡片显示 NGA 徽章；阅读时 `open_book` 返回 `nga: true`，
   Reader 改用 **NGA 排版覆盖层**（只接管字体/字号/行高与图片自适应，
   不强改颜色/背景/链接色），保留 EPUB 内联的楼层卡片、引用块、
   NGA 标准色、骰子等原版样式。
4. 在线图片模式的 EPUB 依赖章节 CSP 放行 `https:` 图片/媒体
   （`img-src 'self' data: https:`），脚本仍被 `script-src 'none'` 拦截。

## 复用与增强点

- 复用 ngapost2md：`init_from_web/local`（增量续传）、`download`
  （页并发 + 格式化 + Markdown）、`build_epub`（WebP 压缩/明暗主题/
  自定义目录/内联样式）。
- 增强（向后兼容，CLI 行为不变）：
  - `nga.download(progress=, cancel=, no_images=)`
  - `epub.build_epub(progress=, cancel=, no_images=)`
  - `format_html.set_no_images()`

## 验证

- 单测：`python -m unittest discover tests`（120 项，含 NGA 集成层）
- UI harness：`python -m tests.ui.runner`（含 NGA 面板/样式检查）
- 真实下载：小规模在线模式（前 5 楼）验证端到端

## 后续方向

- 并行下载多帖（队列）
- 帖子库管理页（按 tid 分组、删除输出目录）
- 直接渲染 NGA HTML（不经 EPUB）的沉浸式模式
- 图片懒加载/分卷 EPUB 降内存
