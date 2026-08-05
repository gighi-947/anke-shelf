# 安科书架（AnkeShelf）· NGA 安科阅读器（Windows / Python）

参考开源项目 [Readest](https://github.com/readest/readest) 的架构思想（数据与表现分离）实现的
Windows 端轻量化安科阅读器：**Python 侧做数据（解析/存储/服务/搜索/下载），Web 侧做表现（渲染/交互）**。

> 当前版本：**v1.0.0**

## ✨ NGA 安科一站式阅读

集成 [ngapost2md-python](ngapost2md-python/)（NGA 帖子下载 + EPUB 导出）：

- **面板下载**：顶栏「NGA 下载」→ 粘贴帖子 tid/链接，配置只看楼主（authorid）、
  前 N 楼、图片模式（嵌入/在线/不含）、明暗主题、目录楼 pid、每章楼层数
- **实时进度**：页下载/格式化/图片处理分阶段进度条，可随时取消
- **原版视觉**：生成的 EPUB 带完整 NGA 内联样式（楼层卡片、引用块、骰子、
  28 种标准色、`[color]` 标签），阅读器对 NGA 书只接管排版、不强改颜色
- **增量更新**：重复下载同一帖子自动续传新楼层；「全量重下」可清缓存重来
- **NGA 登录配置**：下载面板内维护 Cookie（ngaPassportUid/ngaPassportCid/UA）；
  仓库只提交 `config.ini.example` 占位模板，不包含任何真实凭据。本地开发时
  可复制模板为 `ngapost2md-python\config.ini` 填写 Cookie，首次运行自动导入

## 特性

- 📖 阅读 EPUB（自实现解析：container → OPF → spine → 目录，纯标准库）
- 📑 **分页渲染**（foliate 式 CSS multi-column 横向翻页）+ 整章滚动；
  翻页方式支持滚动、**自动双页**（横屏宽窗自动左右双页，flow/epub.js 同款
  Auto spread）、单页分页、强制横屏双页，按整页跨翻页
- 🧱 **NGA 特殊排版适配**：分页模式下楼层允许跨页拆分；超过一页高度的
  长表格（含 rowspan/colspan）自动收纳为页内滚动容器，不再把内容
  撑出页面边界导致错位
- 🎨 **标注系统**：选中高亮（6 色）、笔记、书签、侧栏列表跳转、导出 Markdown/JSON
- 🔍 全文搜索（中文无需分词，子串匹配），text_offset 精确定位跳转
- 🧭 **阅读辅助**：阅读标尺、逐段阅读、速读 RSVP、自动滚动、亮度调节、阅读时长统计、代码高亮
- 📚 本地书架：导入、封面、**最近阅读横条**、**网格/列表双视图**、
  按最近阅读/书名/作者/添加时间排序、进度百分比、删除
- 🖼️ **图片点击放大**：阅读页点图全屏预览，滚轮缩放（0.5x~5x）、双击 1:1
- ⌨️ **快捷键帮助（?）**：按 `?` 或顶栏「?」按钮查看当前全部快捷键；
  `Ctrl+F` 阅读页唤起全文搜索、书架页聚焦搜索框；`Esc` 依次关闭弹窗/菜单/侧栏；
  点击页面中央可随时切换顶栏与底栏的显示
- 🎨 daisyUI 风格 GUI（深色为主，对齐 Readest 视觉）：浮动顶栏、侧栏 tab、底部状态栏、内嵌 SVG 图标
- 💾 精确记忆阅读位置（章节 + 纯文本字符偏移，字号/窗口/模式变化不丢失）
- ♻️ NGA 连载热更新：只增量拉取新楼层并追加到原生书容器，
  不重复下载旧内容，进度与标注保持稳定
- ⌨️ 键盘翻页/翻章（← / →，可在设置页自定义）、滚轮翻页、边缘热区、触屏滑动
- 🛡 安全：本地 HTTP 仅回环监听、随机启动令牌校验、zip 路径穿越防护、章节 CSP + base 注入

## 字体与开源许可

- 内置默认字体为 [霞鹜文楷 LXGW WenKai](https://github.com/lxgw/LxgwWenKai)
  （SIL Open Font License 1.1，许可证全文见 `web/fonts/OFL.txt`）。
- 本项目代码以 [GNU AGPL-3.0](LICENSE) 开源；UI 设计参考了
  [Readest](https://github.com/readest/readest)（同为 AGPL-3.0）的设计思路，代码为独立实现。
- 内置 NGA 转换内核 `ngapost2md-python/` 为独立 Python 重写版，
  对应 Go 原版 [ludoux/ngapost2md](https://github.com/ludoux/ngapost2md) 为 MIT 协议。

## 运行要求

- Windows 10/11（需系统 Edge WebView2 Runtime，Win11 自带）
- Python 3.10+，依赖见 requirements.txt（pywebview 仅作窗口壳，业务走本地 HTTP）

## 安装与运行

```bat
pip install -r requirements.txt
python -m app.main
```

## 测试

```bat
python -m app.make_test_epub   :: 生成测试样本到 tests\sample\
python -m unittest discover tests
python -m tests.ui.runner       :: UI 自动化验证（需桌面会话，含 JS/Python 差分）
```

## 打包为 exe

```bat
pip install pyinstaller
build.bat                      :: 输出 dist\AnkeShelf\AnkeShelf.exe（目录版）
```

## 架构

## 目录结构

```
app/                  Python 服务层（EPUB 解析/书架/搜索/标注/NGA 下载/HTTP API）
web/                  前端单页应用（书架 + 阅读器，iframe 渲染章节并注入主题/排版）
ngapost2md-python/    NGA 帖子下载与转换内核（EPUB/Markdown 生成，config.ini 不入库）
tests/                单元测试与 UI 自动化验证
run_app.py            程序入口（PyInstaller 发行入口）
ankeshelf.spec        PyInstaller 打包配置
build.bat             一键打包脚本（输出 dist\AnkeShelf\AnkeShelf.exe）
requirements.txt      依赖清单
```

```
app\epub.py         EPUB 解析（zip 探测/OPF/spine/nav/NCX/封面/编码容错）
app\text.py         章节纯文本提取（与前端 TextPos 逐字符对齐的坐标规则）
app\server.py       本地 HTTP 服务器（静态资源 + zip 内资源 + JSON API 路由 + 令牌校验 + CSP）
app\shelf.py        书架与进度持久化（progress 用 text_offset，原子写）
app\annotations.py  标注存储（高亮/书签 CRUD + Markdown/JSON 导出）
app\search.py       全文搜索（惰性内存索引，偏移与坐标系统一致）
app\stats.py        阅读统计存储
app\api.py          本地 HTTP API 服务层（前后端分离，JS 唯一调用入口）
app\dialogs.py      原生文件对话框（服务端触发，前端无需桥接）
app\main.py         装配与窗口生命周期（pywebview 仅窗口壳）
app\nga_config.py   NGA 凭据配置管理（%APPDATA%\AnkeShelf\nga_config.ini）
app\nga_service.py  NGA 下载服务（单飞/进度/取消/入库书架）
web\js\textpos.js   文本坐标系统（text_offset 定位，进度/标注/搜索共用）
web\js\paged.js     分页渲染核心（CSS multi-column + 翻页五通道）
web\js\annotations.js 标注系统（选中工具栏/mark 注入/笔记/书签/侧栏）
web\js\assist.js    阅读辅助（标尺/逐段/RSVP/自动滚动/亮度）
web\js\highlight.js 零依赖代码高亮
web\js\stats.js     阅读统计前端
web\js\nga_download.js NGA 下载面板
web\               前端单页应用（书架 + 阅读视图，iframe 渲染 + 覆盖层）
```

数据目录：`%APPDATA%\AnkeShelf\`（shelf.json / progress.json / settings.json /
annotations.json / statistics.json / covers/ / nga_config.ini / nga_library/）
旧版数据目录 `%APPDATA%\EpubReader\` 会在新版首次启动时自动迁移到
`%APPDATA%\AnkeShelf\`（书架、进度、标注、NGA 配置都会保留）。

## 渲染机制

章节通过 iframe 加载（服务器注入 `<base href>` 指向章节目录并下发 CSP
`script-src 'none'`），章节内图片/CSS/字体/相对链接天然正确解析，脚本被
CSP 与服务器双重拦截。覆盖样式注入 iframe 文档实现主题与字号控制；
分页模式对 body 应用 CSS multi-column，`scrollLeft` 每 `列宽+沟槽` 一页。

**text_offset 坐标系统**：所有定位（进度/标注/搜索）基于「章节折叠纯文本
字符偏移」，JS 端（textpos.js）与 Python 端（app/text.py）逐字符对齐
（UI harness 差分验证），字号/窗口/分页滚动切换后仍可精确恢复。

## 已知限制

- 竖排（vertical-rl）书籍渲染不完美
- EPUB 内写死 px 的标题不随字号缩放（em 字号正常缩放）
- 高亮 mark 边界可能引入 ±2 字符的微小偏移（仅影响高亮区域的搜索定位）
- 非良构 HTML 下 JS/Python 文本提取可能分歧（进度/标注自洽，仅搜索精确落点受影响）
- NGA 在线图片模式依赖网络与 NGA Cookie；章节 CSP 已放行 `https:` 图片/媒体
  （脚本仍被 `script-src 'none'` 拦截）

## 测试

```bat
python -m unittest discover tests        :: 152 项单测（含 NGA 集成层）
python -m tests.ui.runner                :: UI 自动化验证（含 NGA 面板/原版样式）
python -m tests.ui.verify_nga_download   :: 真实下载端到端验证（需网络 + Cookie）
```

> pywebview 6.2.1 + Python 3.14 下 winforms 后台线程会打印无障碍/COM 错误日志
> （不影响功能）。程序入口已内置日志过滤器静默（`app/main.py` 的
> `_silence_pywebview_noise`），控制台保持干净。
