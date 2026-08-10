# 安科书架 · 架构说明

## 总体结构

```
run_app.py            入口（PyInstaller 目标脚本）
app/                  Python 后端
  main.py             窗口生命周期、服务装配、单实例、DPI
  server.py           本地 HTTP 服务（/api/<name> 分发 + /book/ 读取书内容）
  api/                前端唯一业务入口（api/ 包：registry + 按域 handler，方法名即接口名）
  settings.py         用户设置持久化（默认值 + 旧版迁移）
  shelf.py            书架/进度存储（原子 JSON）
  stats.py            阅读统计（全局 + 每书 + 按天）
  annotations.py      标注/书签
  search.py           全文搜索索引（按需构建；按章限量 + 续取 search_more）
  epub.py             自实现 EPUB 解析（container → OPF → spine → 目录）
  native_book.py      原生增量书容器（meta.json + floors.json + chapters/）
  nga_service.py      NGA 下载/热更新服务（单飞任务 + 状态轮询）
  export_service.py   导出（EPUB/Markdown 复制 + 原生书重建 EPUB）
  fonts.py / dpi.py / instance_guard.py / dialogs.py / storage.py / paths.py
web/                  前端（纯静态，前后端分离）
  index.html + css/   书架/阅读器/设置/下载导出页
  js/
    bridge.js         fetch 封装（令牌鉴权 + 超时 + 调试 mock）
    app.js            应用状态、视图切换、顶底栏自动隐藏
    reader.js         章节渲染（滚动/分页切换、进度、快捷键、交互）
    paged.js          CSS multi-column 分页核心（单页/自动双页/强制双页）
    nga_download.js   下载/导出/更新整合页
    settings.js       独立设置页
    stats.js / sidebar.js / toc.js / annotations.js / fullsearch.js
    bookshelf.js      书架网格/列表
    theme.js          主题与自定义配色
    textpos.js        DOM 文本坐标 ↔ text_offset
    util.js           公共小工具（快捷键展示、时长/日期格式化）
ngapost2md-python/    上游下载器（vendored，已做少量集成补丁）
tests/                单元测试 + UI 自动化
docs/                 架构与规划文档
```

## 数据流

- 前端所有业务调用走 `Bridge.call(name, ...args)` → `POST /api/<name>`（本地随机令牌）。
- 书内容：`/book/<book_id>/<zip_path>`（EPUB zip 内路径）或原生书目录读取，
  章节由 iframe 加载（同源，可注入样式与交互）。
- 阅读进度：统一 `text_offset`（纯文本字符偏移），滚动/分页模式都可精确恢复。
- NGA 下载：下载完成立即构建原生书容器并注册书架；热更新只拉新页、追加新楼层。
- 统计：前端 5 秒心跳 + 页面切换上报，后端按天聚合。

## 关键约定

- 设置项一律先加 `app/settings.py` 的 `DEFAULTS`；需要旧数据迁移时递增 `settings_version`。
- API 方法签名即接口契约：`Api.<name>(*args)`，返回可 JSON 序列化的 dict。
- NGA 配色：阅读器只接管默认黑/白文字（`--reader-fg`），带显式颜色的字体保持原样。
- 主题体系：`theme_mode` 支持 `system / light / sepia / dark`（空串=跟随 `theme`），
  预设色板是前端常量（`theme.js` 的 `PALETTES`），持久化仍只存
  `custom_bg / custom_text / custom_primary / custom_accent` 四色。
- 分页几何与 flow/epub.js 对齐：border-box、精确列宽、双页补偶数列。
- 发行版打包：`python -m PyInstaller --noconfirm --clean ankeshelf.spec`，
  然后把 README/LICENSE/OFL/使用说明复制进 `dist\AnkeShelf`，再压成目录版 zip。

## 测试

- 单元测试：`python -m unittest discover tests`
- UI 自动化（需桌面会话）：`python -m tests.ui.runner`
- 测试样本生成：`python -m tests.make_test_epub`
