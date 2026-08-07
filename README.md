# 安科书架（AnkeShelf）· NGA 安科阅读器（Windows / Python）

参考 [Readest](https://github.com/readest/readest) 的前后端分离架构实现的
Windows 端轻量化安科阅读器：**Python 侧做数据（解析/存储/服务/搜索/下载），
Web 侧做表现（渲染/交互）**，界面由本机浏览器引擎渲染。

> 当前版本：**v1.2.0**

> 安卓端：独立里程碑开发中，代码位于 [`android/`](android/README.md)，
> 采用 Kotlin + Jetpack Compose 重写（阅读正文使用安卓专用 WebView 渲染页），
> 版本线独立为 `android-vX.Y.Z`。详见 [docs/ANDROID_ARCHITECTURE.md](docs/ANDROID_ARCHITECTURE.md)。

## ✨ NGA 安科一站式阅读

集成 [ngapost2md-python](ngapost2md-python/)（NGA 帖子下载 + EPUB 导出）：

- **面板下载**：顶栏「NGA 下载」→ 粘贴帖子 tid/链接，配置只看楼主（authorid）、
  前 N 楼、图片模式（嵌入/在线/不含）、明暗主题、目录楼 pid、目录用途
  （仅作索引 / 兼作分章）、每章楼层数
- **实时进度**：页下载/格式化/图片处理分阶段进度条，可随时取消；
  取消任务自动清理未完成文件
- **原版视觉**：生成的 EPUB 带完整 NGA 内联样式（楼层卡片、引用块、骰子、
  28 种标准色、`[color]` 标签），阅读器对 NGA 书只接管排版、不强改颜色
- **增量更新**：重复下载同一帖子自动续传新楼层；「全量重下」可清缓存重来
- **连载热更新**：书架中 NGA 徽章卡片提供「检查更新」按钮，只拉取新增楼层
  并追加到本地书库，更新时维持上一次下载设置（楼主、主题等），进度与标注稳定
- **下载 / 导出整合页**：下载、更新、导出、配置四个标签页，任务运行中标签
  带状态点；导出可选 EPUB / Markdown 并自选文件夹，文件名默认使用安科标题
- **NGA 登录配置**：下载面板内维护 Cookie（ngaPassportUid/ngaPassportCid/UA）；
  仓库只提交 `config.ini.example` 占位模板，不包含任何真实凭据。本地开发时
  可复制模板为 `ngapost2md-python\config.ini` 填写 Cookie，首次运行自动导入

## 🔑 获取 NGA Cookie（ngaPassportUid / ngaPassportCid）

下载需要登录权限的帖子（“只看楼主”、在线图片、隐藏内容等）时，需要把浏览器中
已登录 NGA 的 Cookie 填进应用。获取步骤如下：

1. 在电脑浏览器（推荐 Edge 或 Chrome）中打开 <https://bbs.nga.cn> 并登录你的账号。
2. 按 `F12` 打开开发者工具。
3. 切换到「应用程序」标签页（Edge/Chrome 中文版；Firefox 为「存储」）。
4. 左侧展开「Cookie」，点击 `https://bbs.nga.cn`。
5. 在右侧列表找到 `ngaPassportUid`：值是一串数字（你的 NGA 用户 ID），
   双击「值」列复制。
6. 找到 `ngaPassportCid`：值是一串较长的字母数字（登录会话凭证），双击复制。
7. 回到安科书架 → 「NGA 下载」→「配置」标签页，把 uid、cid 粘贴到对应输入框；
   User-Agent 可点「默认填入」，或从浏览器开发者工具 →「网络」→ 任意 NGA 请求
   的请求头里复制。
8. 点击「保存配置」即可开始下载。

注意事项：

- 两个 Cookie 会随登录状态过期；若下载时提示需要登录或图片无法加载，
  按上述步骤重新复制一次即可。
- uid/cid 只保存在本机 `%APPDATA%\AnkeShelf\nga_config.ini`，不会上传到任何
  服务器；仓库与发行版均不含真实凭据。需要清理时，在「配置」页点击
  「清除已保存配置」。
- 不要把 uid/cid 发给他人或公开发布，避免账号被盗用。

## 特性

- 📖 阅读 EPUB（自实现解析：container → OPF → spine → 目录，纯标准库）
- 📑 **分页渲染**（Foliate 式 CSS multi-column 横向翻页）+ 整章滚动；
  默认滚动阅读（一章到底，不分页）；翻页方式支持滚动、**自动双页**
  （横屏宽窗自动左右双页，flow/epub.js 同款 Auto spread）、单页分页、
  强制横屏双页，按整页跨翻页；双页模式自动补偶数列，末屏完整可达
- 🧱 **NGA 特殊排版适配**：分页模式下楼层允许跨页拆分；超过一页高度的
  长表格（含 rowspan/colspan）自动收纳为页内滚动容器，不再把内容
  撑出页面边界导致错位
- 🎨 **标注系统**：选中高亮（6 色）、笔记、书签、侧栏列表跳转、导出 Markdown/JSON
- 🎛️ **个性化配色**：设置 → 外观提供 9 套预设色板（默认/羊皮纸/夜间/Solarized/Nord/
  护眼绿/墨蓝等，参考 Readest / flow / Koodo Reader），也可分别自定义背景色、
  主题色、强调色与文字颜色（空值=跟随主题；文字颜色只作用于默认黑/白文字，
  NGA 彩色字体保留原色）；切换即时生效并带阅读页实时预览卡片
- 🧭 **主题模式**：支持浅色 / 羊皮纸 / 深色 / **跟随系统**（随 Windows 深浅色
  自动切换）；阅读页顶栏按钮仍可快速循环主题
- 🗂️ **设置页与下载页 Tab 化**：设置页按外观 / 阅读 / 辅助 / 快捷键 / 统计 / 数据
  分栏，选项带简短说明，内容居中展示；NGA 下载页分为下载 / 更新 / 导出 / 配置
  四页，任务运行中标签页带状态点，下载前自动校验 tid 与每章楼层数
- 🔍 **独立全文检索页**（顶栏按钮 / `Ctrl+F`）：中文无需分词、子串匹配，
  text_offset 精确定位跳转；支持**按章分组折叠、每章续取更多、大小写敏感、
  全词匹配、每书搜索历史**。高频关键词按“每章限量”返回，靠后章节
  不会被前面章节挤掉（例如整本书搜角色名仍能看到最后几楼的命中）
- 🧭 **阅读辅助**：阅读标尺、逐段阅读、速读 RSVP、自动滚动、亮度调节、阅读时长统计、代码高亮
- 📚 本地书架：导入、封面、**最近阅读横条**、**网格/列表双视图**、
  按最近阅读/书名/作者/添加时间排序、进度百分比、删除
- 🖼️ **图片点击放大**：阅读页点图全屏预览，滚轮缩放（0.5x~5x）、双击 1:1
- ⌨️ **快捷键帮助（?）**：按 `?` 或顶栏「?」按钮查看当前全部快捷键；
  `Ctrl+F` 阅读页唤起全文搜索、书架页聚焦搜索框；`Esc` 依次关闭弹窗/菜单/侧栏；
  点击页面中央可随时切换顶栏与底栏的显示
- 🎨 深色为主的可视化界面（对齐 Readest 视觉）：浮动顶栏、侧栏 tab、
  底部状态栏、内嵌 SVG 图标；UI 设计令牌思路参考 daisyUI
- 💾 精确记忆阅读位置（章节 + 纯文本字符偏移，字号/窗口/模式变化不丢失）
- ♻️ NGA 连载热更新：只增量拉取新楼层并追加到原生书容器，
  不重复下载旧内容，进度与标注保持稳定
- ⌨️ 键盘翻页/翻章（← / →，可在设置页自定义）、滚轮翻页、触屏滑动
- 🖥️ **沉浸式阅读**：顶栏全屏按钮或 F11 切换软件全屏；Esc 或返回书架自动退出，退出时恢复窗口尺寸
- 🛡 安全：本地 HTTP 仅回环监听、随机启动令牌校验、zip 路径穿越防护、章节 CSP + base 注入

## 界面预览

实机演示截图（v1.2.0）：

![书架主页](docs/screenshots/bookshelf.png)
书架：网格视图、最近阅读与 NGA 下载入口

![阅读页：楼层卡片与骰子](docs/screenshots/reader-floor.png)
阅读页：楼层卡片、引用块与骰子结果

![全文检索](docs/screenshots/fulltext-search.png)
全文检索：按章分组折叠、每章限量续取

![阅读页：人物设定楼](docs/screenshots/reader-character.png)
阅读页：人物设定楼与掷骰结果

![NGA 下载面板](docs/screenshots/nga-download.png)
NGA 下载：下载配置与任务控制

![阅读统计](docs/screenshots/statistics.png)
阅读统计：全部书目汇总与最近阅读时长

![阅读页：楼层正文](docs/screenshots/reader-floor-2.png)
阅读页：楼层正文展示

## 设计参考与开源致谢

本项目开发过程中参考了多个优秀的开源项目。**“思路借鉴”指参考其设计、
交互与架构后独立实现；凡直接对照算法、几何公式或数据结构的地方，
源码注释中均已标注出处。** 逐项说明如下：

| 项目 | 许可证 | 借鉴内容 |
|---|---|---|
| [Readest](https://github.com/readest/readest) | AGPL-3.0 | 前后端分离架构、EPUB 解析流水线、主题设计令牌与深浅色变量推导、阅读器浮动顶栏/底栏/侧栏、最近阅读横条、按书保存搜索历史、点击页面中央切换栏显隐等交互 |
| [flow](https://github.com/pacexy/flow) | AGPL-3.0 | 自动双页（Auto spread）、CSS multi-column 分页几何、双页补偶数列、按章分组折叠的搜索交互、主题源色与色板思路 |
| [epub.js](https://github.com/futurepress/epub.js) | BSD-2-Clause | 分页列几何（列宽/沟槽计算）、Auto spread、forceEvenPages 补空列思路 |
| [Foliate](https://github.com/johnfactotum/foliate) | GPL-3.0-or-later | CSS multi-column 分页排版思路、大小写敏感/全词匹配搜索选项（仅参考交互与思路，未复制代码） |
| [Koodo Reader](https://github.com/koodo-reader/koodo-reader) | AGPL-3.0 | 设置页/下载页 Tab 导航、圆形色板选择、选项说明文字、搜索结果分页展示与点击跳转 |
| [KOReader](https://github.com/koreader/koreader) | AGPL-3.0 | 主题插件预设色板、日/夜自定义配色的设计思路 |
| [daisyUI](https://github.com/saadeghi/daisyui) | MIT | 以 CSS 变量组织设计令牌的 UI 思路（未使用其组件代码） |
| [ngapost2md](https://github.com/ludoux/ngapost2md) | 以原仓库 LICENSE 为准 | 内置 `ngapost2md-python/` 为本项目维护的 Python 重写版，下载流程、数据模型与格式规则与其 Go 版等价；表情与匿名映射表由 Go 源码提取 |
| [霞鹜文楷 LXGW WenKai](https://github.com/lxgw/LxgwWenKai) | SIL OFL 1.1 | 内置默认阅读字体，许可证全文见 `web/fonts/OFL.txt` |

补充说明：

- 本项目自身代码以 [GNU AGPL-3.0](LICENSE) 开源。
- 对 Readest、flow、Koodo Reader、KOReader、daisyUI 等，主要借鉴设计思路，
  代码为独立实现；分页几何等算法为对照公式后独立实现，源码中相关位置
  均留有“参考 XX”注释。
- `ngapost2md` 的 Go 原版仓库未在本项目内附带其许可证声明，故此处不代其
  声明协议；如需以原版内核再分发，请以该仓库 LICENSE 为准。本项目随附的
  Python 重写版由本项目维护，许可证同本项目（AGPL-3.0）。

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
python -m tests.make_test_epub :: 生成测试样本到 tests\sample\
python -m unittest discover tests :: 174 项单测（含 NGA 集成层）
python -m tests.ui.runner       :: UI 自动化验证（需桌面会话，含 JS/Python 差分）
python -m tests.ui.verify_nga_real :: 真实 NGA 书端到端验证（需网络，可选）
```

> pywebview 6.2.1 + Python 3.14 下 winforms 后台线程会打印无障碍/COM 错误日志
> （不影响功能）。程序入口已内置日志过滤器静默（`app/main.py` 的
> `_silence_pywebview_noise`），控制台保持干净。

## 打包为 exe

```bat
pip install pyinstaller
build.bat                      :: 输出 dist\AnkeShelf\AnkeShelf.exe（目录版）
```

打包完成后，将 README.md、LICENSE、OFL.txt、使用说明.txt 复制进
`dist\AnkeShelf\`，再压缩为目录版发行包。

## 架构

前后端分离：Python 只提供本地 HTTP 服务与数据存储，界面由内置浏览器渲染；
章节通过 iframe 加载，阅读器注入排版而不改动书源。详细说明见
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 目录结构

```
app/                  Python 服务层（EPUB 解析/书架/搜索/标注/NGA 下载/HTTP API）
web/                  前端单页应用（书架 + 阅读器，iframe 渲染章节并注入主题/排版）
ngapost2md-python/    NGA 帖子下载与转换内核（EPUB/Markdown 生成，config.ini 不入库）
tests/                单元测试与 UI 自动化验证
docs/                 架构说明与历史规划
run_app.py            程序入口（PyInstaller 发行入口）
ankeshelf.spec        PyInstaller 打包配置
build.bat             一键打包脚本（输出 dist\AnkeShelf\AnkeShelf.exe）
requirements.txt      依赖清单
使用说明.txt           发行版内附的使用说明（打包时复制进发行包）
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
web\js\paged.js     分页渲染核心（CSS multi-column + 翻页通道）
web\js\fullsearch.js 独立全文检索页（按章分组/续取/大小写/全词/历史）
web\js\annotations.js 标注系统（选中工具栏/mark 注入/笔记/书签/侧栏）
web\js\assist.js    阅读辅助（标尺/逐段/RSVP/自动滚动/亮度）
web\js\highlight.js 零依赖代码高亮
web\js\stats.js     阅读统计前端
web\js\nga_download.js NGA 下载/导出面板
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
