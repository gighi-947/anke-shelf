# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：Windows 桌面端与 Android 安卓端跨平台开发的同步日志与交接文档。
> 最后更新：2026-08-08（桌面 v1.2.0；安卓分支 android/m1-data-layer，M4 进行中）
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件追加记录**（日期 + 提交 + 现象/结论）。
> 建议阅读顺序：本文件 → README.md → docs/ARCHITECTURE.md → docs/ANDROID_UI_PLAN.md → docs/NGA_READER_PLAN.md

---

## 1. 项目概览

- **项目名**：安科书架（AnkeShelf）
- **定位**：Windows 端轻量化 NGA 安科阅读器，提供“NGA 帖子下载 → 本地 EPUB/原生书转换 → 阅读 → 热更新 → 全文检索 → 标注 → 导出”的一站式体验，最大程度还原 NGA 原版排版与字体颜色。
- **架构**：前后端分离。Python 侧负责数据（EPUB 自解析、书架/进度/标注/统计、全文搜索索引、NGA 下载与热更新、本地 HTTP API）；Web 侧为纯静态单页应用（渲染/交互）；pywebview 仅作窗口壳，业务全部走本地 HTTP（随机启动令牌鉴权）。
- **仓库**：https://github.com/gighi-947/anke-shelf （分支 `main`）
- **许可证**：GNU AGPL-3.0；内置字体霞鹜文楷 LXGW WenKai（SIL OFL 1.1）
- **当前版本**：v1.2.0（2026-08-07 发布）
- **“安科”来源**：源自日本揭示板“安価”（あんか），NGA 上的互动创作形式——读者通过指定选项、掷骰等方式影响剧情走向，长篇安科常以“楼层”为单位连载。

---

## 2. 关键架构决策（安卓移植时必须继承的约束）

### 2.1 前后端分离、数据与表现解耦

- 前端所有业务调用统一走 `Bridge.call(name, ...args)` → `POST /api/<name>`，本地回环 HTTP + 每次启动随机生成的令牌（URL query + 请求头双携带）。
- `app/api.py` 的方法名即接口契约：`Api.<name>(*args)`，返回值必须可 JSON 序列化。
- 章节内容通过 iframe 加载（同源），服务器注入 `<base href>` 并下发 CSP `script-src 'none'`；阅读器注入排版覆盖样式但不改动书源。
- **对安卓的意义**：`web/` 整目录是平台无关的纯静态资产，理论上可直接复用；后端服务层需要在新平台重新落地（或迁移到别的语言），但接口契约可以原样保留。

### 2.2 自解析 EPUB + 原生书容器

- `app/epub.py`：纯标准库自实现 EPUB 解析（container → OPF → spine → nav/NCX），不依赖 python epub 库。
- `app/native_book.py`：NGA 下载完成后立即构建原生书容器（`meta.json + floors.json + chapters/`），并注册到书架。
- 热更新只增量拉取新楼层、追加到原生书容器，不重复下载旧内容；进度与标注基于稳定坐标，不受更新影响。
- **对安卓的意义**：这套“自解析 + 原生容器 + 增量更新”的设计绕开了 EPUB 静态文件的限制，是项目最大特色，移植时应优先保留语义。

### 2.3 text_offset 统一坐标系统

- 所有定位（阅读进度、标注、搜索落点）基于“章节折叠纯文本字符偏移”。
- JS 端 `web/js/textpos.js` 与 Python 端 `app/text.py` 逐字符对齐，并有 UI harness 差分验证。
- 字号、窗口尺寸、分页/滚动模式切换后仍可精确恢复位置。

### 2.4 NGA 排版与颜色铁律

- 阅读器只接管**默认黑/白文字**（`--reader-fg`），随主题深浅切换；**带显式颜色设定的字体一律保留原色**（NGA 28 种标准色、`[color]` 标签、骰子、楼层卡片、引用块等原版样式不破坏）。
- 这是“NGA 安科的精髓”，曾因深浅色切换把彩色字一并转黑/白而引发严重问题，后续明确为硬性规则。

### 2.5 分页几何

- 分页模式使用 CSS multi-column，`scrollLeft` 每“列宽 + 沟槽”一页。
- 单页分页 / 自动双页（横屏宽窗自动左右双页）/ 强制横屏双页；双页自动补偶数列（epub.js forceEvenPages 思路），末屏完整可达。
- 列几何与 flow / epub.js 对齐：border-box、精确列宽、补偶数列。
- NGA 特殊排版适配：楼层允许跨页拆分；超过一页高度的长表格（含 rowspan/colspan）自动收纳为页内滚动容器，避免内容撑出页面边界导致错位。
- **滚动模式语义**：明确“一章到底、不分页”，滚动阅读只有“章”的概念，没有上一页/下一页；单章包含楼层数由用户在下载时设定。这一约定同时解决了滚动模式大片空白的问题。

### 2.6 主题引擎

- `theme_mode` 支持 `system / light / sepia / dark`（空串=跟随 `theme`）。
- 预设色板是前端常量（`web/js/theme.js` 的 `PALETTES`，9 套：默认、羊皮纸、夜间、Solarized、Nord、护眼绿、墨蓝等）；持久化只存 `custom_bg / custom_text / custom_primary / custom_accent` 四色（空值=跟随主题）。
- 颜色仅作用于默认黑/白文字；深浅色切换时默认字体颜色必须同步（深色→白字、浅色→黑字），防止“黑底黑字”不可读。

### 2.7 全文搜索

- `app/search.py`：惰性内存索引，`text_offset` 与坐标系统一致。
- 前端 `web/js/fullsearch.js`：独立整页，按章分组折叠、每章限量（默认先显示 50 条）+ 续取更多、大小写敏感、全词匹配、每书搜索历史（最多 10 条）。
- 高频关键词按“每章限量”返回，靠后章节不会被前面章节挤掉——这是针对“搜角色名只出到 170 楼”问题的专门设计，移植时必须保留。

### 2.8 数据目录与迁移

- 用户数据：`%APPDATA%\AnkeShelf\`（shelf.json / progress.json / settings.json / annotations.json / statistics.json / covers/ / nga_config.ini / nga_library/）。
- 旧版 `%APPDATA%\EpubReader\` 首次启动自动迁移（书架、进度、标注、NGA 配置全部保留）。
- 设置默认值集中在 `app/settings.py` 的 `DEFAULTS`；需要旧数据迁移时递增 `settings_version`（这是数据迁移版本号，**不是产品版本号**，升级产品版本时不要动它）。

### 2.9 安全模型

- 本地 HTTP 仅回环监听、随机启动令牌校验、zip 路径穿越防护、章节 CSP + base 注入、脚本双重拦截。
- NGA 凭据只存本机 `nga_config.ini`；仓库只提交 `config.ini.example` 占位模板；发行包必须无任何真实凭据。

---

## 3. 开发时间线（详尽）

### 3.1 起源：两个既有项目合流

项目由两个先前对话中的项目复制合并而来：

1. **01_2026-08-04_修复pywebview噪声与功能实现.md**：EPUB 阅读器底子，含 pywebview 噪声过滤、书架/进度/标注/统计等功能。
2. **02_2026-08-04_NGA样式内联解决.md**：NGA 帖子下载与样式内联方案（ngapost2md Python 重写版，楼层卡片/引用块/骰子/28 种标准色内联进 EPUB）。

合并方向：做 NGA 安科阅读器——集成 NGA 帖子下载、本地 EPUB 转换、完整 NGA 视觉样式、一站式阅读体验。

### 3.2 v1.0.0 之前：功能补齐、UI 复刻与稳定化

早期用户反馈与处理（按时间大致顺序）：

- 下载面板取消任务后无法退出页面、导入书籍崩溃、面板中间空白按钮崩溃 → 面板状态机与桥接错误处理修复。
- UI 简陋 → 明确要求复刻 Readest 发行版现有 UI 设计（深色为主、浮动顶栏/底栏/侧栏、最近阅读横条等）。
- 前端操作卡死频发 → 全面稳定性排查。
- 打包为带 exe 与全部依赖的发行版（PyInstaller 目录版）。
- 支持自定义字体（书籍/全局）、排版设置实时生效（曾出现调 20 号字仍显示 19 号、行高不变）、自由设定阅读页宽度。
- 更多翻页方式（滚动 / 自动双页 / 单页分页 / 强制横屏双页）。
- 更详细的阅读统计页面；单页阅读滚动条被遮挡问题。
- 阅读界面顶栏/底栏固定显示按钮。
- **发行版残留开发者本人 NGA 登录配置**（首次出现）→ 建立打包前凭据检查意识。
- 严重卡死：窗口一打开无论任何操作（甚至移动窗口）都未响应；等待 5~10 秒再操作可降低卡死率 → 初始化时序问题，窗口在界面完全准备好后才出现，避免启动期操作。
- 内存占用超过 3GB、加载极慢 → 前后端分离重构的直接动因。
- 参考 05_2026-07-31_v1.3.0版本更新完成.md 项目的前端实现，评估“本机浏览器渲染 GUI”是否优于 webview → 用户拍板**直接开始前后端分离重构**（pywebview 只做窗口壳，业务走本地 HTTP）。

重构后继续修复：

- 退出重进后已导入书籍无法加载（提示“书籍未加载，请重新导入”）→ 书架/进度持久化修复。
- NGA 下载卡片页按住左键划出卡片区域即退出下载界面 → 拖拽事件边界处理。
- 发行版再次忘记删除 NGA 个人登录配置 → 强化发布检查。
- 字号过大超出页面显示范围；未做页面空白识别，单页出现大量空白 → 排版与空白识别。
- 自定义热键（翻页、换章等）；顶栏切换主题后视图/排版界面显示不同步 → 主题状态统一。
- 新增导出选项（NGA 下载帖子按 EPUB / Markdown 选择格式并自选文件夹）。
- 新增“卸载并完全清除用户数据”功能。
- 将除字体、字号、页面宽度外的设置移出阅读界面，单独做设置页。
- 滚动条与顶/底栏冲突（滚到底部自动唤出底栏遮挡滚动条）→ 交互重写。
- 中途取消任务自动删除未完成文件。
- User-Agent 可默认填入。
- 重复下载提示“已有下载任务”但无法确认状态 → 单独做**下载/导出整合页**（下载状态查看、导出格式选择、导出状态查看）。
- 深浅色切换出现“黑底黑字”不可读 → 明确颜色铁律（见 2.4），并先分析 EPUB 字体颜色实现，避免误伤彩色字。
- 页面空白部分识别失效 → 最终以“滚动模式=分章不分页”语义解决大片空白问题。
- README 加入发行版，并在对话中提供简要项目介绍。
- 在线图片改为默认下载配置；滚动阅读改为默认翻页方式；用户提供的字体（后确认为霞鹜文楷）改为默认字体。
- DPI 缩放问题（大屏幕 UI 字体模糊）→ 浏览器缩放锁定 100%，重启恢复。
- 热更新功能探索：既然软件是自解析而非标准 epub 库，可绕过 EPUB 固有限制做动态热更新 + 更激进还原 NGA 风格，同时保留通用 EPUB 导出。
- 项目改名：中文名“安科书架”，英文名由开发者决定（联网搜索安科定义后定名 AnkeShelf）。
- 第一次全面代码质量分析、架构精简与优化；整理项目目录结构准备托管 GitHub。
- 检查个人 NGA 登录配置残留；旧数据迁移。
- 横屏双翻页模式；研究 Readest UI 与交互后实施第一批借鉴。
- WebView 实际渲染大于窗口、页面错位（切换翻页模式尤其明显）→ 缩放/布局修复。
- 横屏单/双翻页显示问题（参考 flow 阅读器；NGA 特殊排版导致双页文字单行过长、页面出界、拼接错乱）→ 分页几何重构。
- 压缩包统一为“目录版”命名（zip 内含 `AnkeShelf\` 顶层目录）。
- 字体确认为 LXGW WenKai 后补开源协议；项目切换为 AGPL-3.0。

### 3.3 v1.0.0（2026-08-06 发布）

提交：`0e66d23 Set project version to v1.0.0`（此前还有 Initial commit、两个许可证提交）。

Release 说明要点（原文摘录）：

- Windows 端轻量安科阅读器：NGA 帖子一站式下载、热更新、本地 EPUB 转换与完整 NGA 视觉样式还原。
- NGA 帖子下载（Cookie 配置、只看楼主、楼层范围、图片嵌入/在线）。
- 连载热更新：只增量拉取新楼层，进度与标注保持稳定。
- 完整 NGA 排版还原：楼层卡片、引用、骰子、彩色字、长表格页内滚动。
- 阅读模式：滚动 / 分页 / 自动双页 / 强制横屏双页。
- 全文搜索、标注（高亮/笔记/书签）、阅读统计、自定义字体与排版。
- 快捷键帮助、图片点击放大、最近阅读横条、网格/列表书架视图。
- 数据自动迁移：旧版 `%APPDATA%\EpubReader` 首次启动自动迁入 AnkeShelf。

发布资产：`AnkeShelf-v1.0.0.zip`。

### 3.4 v1.0.0 → v1.1.0：热更新、个性化、统计、沉浸式

- “完成后打开”bug：下载完成并打开后，再点 NGA 下载会直接跳转到已下载的安科，无法下载/导出新书 → 修复残留完成状态误判（提交 `cdfdf8a`）。
- 热更新未维持原主题设置（深色主题更新时变浅色）→ 新增可点击的独立“更新帖子”按钮，更新时可改下载设置（只看楼主、主题、图片、分章等），仅对新增楼层生效；默认保留上一次设置（如上次填写的楼主 uid 默认回填）。
- 热更新逻辑改进：首次下载即构建原生书，热更新从一开始就是纯增量，不再整帖重下（此阶段用户明确：**在明确要求前不要上传代码和发行版**，但本地仍要打包）。
- 阅读统计重做：默认显示全部书目全部时间；详情卡片可查看全部书目明细，也可选择具体书目；阅读界面章节侧栏增加统计入口；小屏幕自适应。
- 滚动阅读作为默认翻页方式未生效 → 检查并一次性迁移旧设置。
- 横屏双翻页超界、页面拼接错乱持续修复（继续参考 flow）。
- 底部上一章/下一章占用空间过多 → 单/双翻页模式下默认隐藏，不占阅读空间。
- 移除“点击翻页区域”（有时翻页有时换章，易混淆），翻页只通过明确控件触发。
- 顶/底栏自动隐藏逻辑在单/双翻页下重写；悬浮翻页按钮改为竖直长条形，减少遮挡正文。
- 滚动模式边缘唤出（鼠标进入上/下 54px 边缘带）横向限制为书籍实际显示区同宽、纵向不变；滚动模式下滚轮立即收起顶/底栏。
- 点击翻页/换章后立即隐藏顶/底栏；顶栏收起时二级菜单卡片同步收起；顶/底栏固定按钮失效问题修复。
- 设置增加更多个性化：自定义背景色、主题色、强调色、文字颜色（仅作用于默认黑/白文字），并处理原有主题设置按钮。
- 导出文件名默认使用安科名称（自动清理非法字符）。
- 沉浸式阅读模式（软件全屏）：顶栏按钮或 F11；返回书架自动退出；进入时弹窗提醒“按 esc/f11 退出”；退出恢复窗口尺寸。
- 第二次全面架构梳理与精简，整理项目文件夹；全面更新版本号为 v1.1.0，提交并发布。
- 发布后撰写 v1.1.0 更新说明的 NGA 帖子（简洁严肃风格）。

### 3.5 v1.1.0（2026-08-06 发布）

提交：`cb87a35 v1.1.0: 滚动默认与分页重构、原生书首下即建、热更新设置化、统计/配色/沉浸式全屏等`。

Release 说明要点（原文摘录）：

- 阅读体验：滚动阅读改为默认（旧设置一次性迁移），滚动模式明确“一章到底、不分页”；单/双翻页重构，对齐 flow/epub.js 列几何，修复横屏双页超界与拼接错乱，双页自动补偶数列；顶/底栏自动隐藏重写（分页模式下边缘唤出生效、翻页/换章后立即收栏、二级菜单同步收起）；移除点击边缘翻页区域；悬浮翻页按钮改竖直长条；固定顶/底栏不再被正文点击“破防”。
- NGA 下载与热更新：首次下载即构建原生书，热更新纯增量；独立“更新帖子”面板（仅对新增楼层生效，默认保留上次设置）；热更新正确继承原帖主题；目录用途可选手动选择（仅作索引 / 兼作分章）；导出文件名默认使用安科标题。
- 个性化与统计：自定义背景色/主题色/强调色/文字颜色（空值跟随主题；文字色仅作用于默认黑/白字体）；阅读统计重做（默认全部书籍汇总、按书目查看详情、阅读侧栏统计入口）；沉浸式阅读（软件全屏）。
- 工程：第二次架构精简、合并前端重复工具函数、清理未使用导入、移除过时打包依赖；新增 docs/ARCHITECTURE.md 与 tests/ui/README.md；使用说明源文件纳入仓库。

发布资产：`AnkeShelf-v1.1.0.zip`。

### 3.6 v1.1.0 → v1.2.0：全文检索、设置/下载页重构

- 深色模式排版渲染短暂白屏闪眼、快速翻页出现渲染错误/排版未加载 → 尝试预加载前后两章（随后因问题较多**回退**，教训：预加载收益不及引入的问题，改用其他手段规避）。
- 全文搜索重大缺陷：关键词出现次数过多时只显示较前结果（如夜见翔的安科搜“丰川祥子”只到 170 楼）→ 调研 Readest、flow 等开源项目的关键词检索实现，完成独立全文检索页：按章分组折叠、每章限量 + 续取更多、大小写敏感、全词匹配、每书搜索历史。
- 主题色等个性化设定参考更多开源工程改进；设置页/下载页使用逻辑也参考开源工程优化。
- 有统一搜索后，移除阅读器侧栏底部搜索按钮；设置页/下载页详细内容居中展示，更协调。
- 更新文档（ARCHITECTURE 文件清单同步移除 search.js）。

### 3.7 v1.2.0（2026-08-07 发布）

提交：`3adf1c2 v1.2.0: 全文检索、主题个性化、设置/下载页重构与 README 开源致谢`。

Release 说明要点：

- 独立全文检索页：按章分组折叠、每章续取更多、大小写敏感/全词匹配、每书搜索历史；高频关键词按每章限量返回，靠后章节不会被前面结果挤掉。
- 个性化主题：9 套预设色板，可分别自定义背景色、主题色、强调色与文字颜色（仅作用于默认黑/白文字，NGA 彩色字体保留原色）；支持跟随系统深浅色。
- 设置页重构：外观 / 阅读 / 辅助 / 快捷键 / 统计 / 数据分栏 Tab，内容居中展示，选项带简短说明。
- 下载 / 导出整合页：下载、更新、导出、配置四页，任务运行中标签带状态点，导出支持 EPUB / Markdown 并自选文件夹。
- 阅读统计默认汇总全部书目，可进入详情查看各书详细数据。
- 移除阅读器侧栏旧搜索入口，统一使用独立全文检索页。
- README 重写，如实补充开源项目借鉴致谢（Readest、flow、epub.js、Foliate、Koodo Reader、KOReader、daisyUI、ngapost2md、LXGW WenKai）。
- 发行版内置 README、LICENSE、OFL 与使用说明。

发布资产：`AnkeShelf-v1.2.0.zip`。

### 3.8 v1.2.0 发布后：文档与截图

- 用户指出上传的 zip 必须带版本号 → 资产名定为 `AnkeShelf-v1.2.0.zip`；随后明确**不要中文名**（此前中文资产名在传输链路中被破坏成 `-.zip`）。
- README 与使用说明增加详细的 `ngaPassportUid / ngaPassportCid` 获取教程（F12 → 应用程序/存储 → Cookie → bbs.nga.cn → 复制 uid/cid；UA 默认填入或从网络面板复制；过期重取；凭据仅存本机、可清除、勿公开）。
- 7 张实机演示截图上传仓库 `docs/screenshots/`，README 新增“界面预览”章节；用户要求**截图不进发行目录**（提交 `a852727`）。

---

## 4. 调试与排障记录（按主题）

> 每条尽量给出“现象 → 处理/结论”。标注 ⚠️ 的条目对安卓开发尤其有参考价值。

### 4.1 启动与稳定性

1. ⚠️ **启动后立即操作必然卡死（未响应）**：窗口刚加载就移动/点击，卡死率极高；等待 5~10 秒后操作正常。处理：窗口在界面完全准备好后才出现；启动期间避免重复双击或强杀进程。教训：**首屏初始化时序是这种“壳 + 本地服务”应用最容易踩的坑**。
2. ⚠️ **内存占用 >3GB、加载极慢**：旧架构（webview 直接承载全部业务）过重。处理：前后端分离重构，业务走本地 HTTP、前端轻量化。教训：**保持轻量是硬性要求**，安卓端同样要控制常驻内存。
3. **WebView 渲染区域大于窗口、页面错位**：切换翻页模式时尤其明显，需要拖动滚动条居中。处理：锁定浏览器缩放为 100%，重启恢复；分页几何精确化。
4. **DPI 缩放导致 UI 字体模糊**（大屏幕预览）：处理：锁定缩放比例；安卓端注意 WebView 的 `textZoom`/viewport 处理。
5. **单实例与锁文件**：`instance.lock` 残留会导致启动异常，使用说明中给出删除锁文件重试的办法。

### 4.2 下载 / 导出 / 热更新

6. **取消下载任务后无法退出页面、导入书籍崩溃、面板空白按钮崩溃**：面板状态机与桥接错误处理不健壮。处理：整合页重构，任务状态轮询 + 取消清理。
7. **下载卡片页按住左键划出卡片区域即退出页面**：事件边界问题。处理：限制拖拽/指针事件的作用范围。
8. **重复下载提示“已有下载任务”但无法确认状态**：处理：单独做下载/导出整合页（下载/更新/导出/配置四 Tab + 任务状态点）。
9. **中途取消任务残留未完成文件**：处理：取消后自动清理未完成文件。
10. **“完成后打开”后再次点 NGA 下载跳转到已下载书**：残留完成状态被当作新完成事件（提交 `cdfdf8a` 修复）。教训：**异步任务完成事件要有版本/时序校验**。
11. ⚠️ **热更新要先整帖重下才能生效**：旧逻辑下第一次下载未构建原生书。处理：首次下载即构建原生书容器，热更新从第一次起就是纯增量；更新面板只影响新增楼层，默认保留上次设置。
12. **热更新未继承原帖主题**（深色书更新后变浅色）：处理：更新配置默认回填上次设置（楼主 uid、主题、图片模式等）。
13. **导出文件名**：默认使用安科标题（非法字符自动清理，无标题用 tid）。

### 4.3 阅读与排版

14. **字号设置无效**（调到 20 仍显示 19、行高不变）：设置未实时作用于渲染层。处理：排版设置实时生效；字号/行高/页面宽度可自由设置。
15. **字号过大超出页面范围、单页大量空白**：处理：空白识别 + 分页几何；最终以“滚动模式=一章到底、不分页”消除滚动模式下的大片空白；长表格收纳为页内滚动容器。
16. ⚠️ **深浅色切换“黑底黑字”**：默认字体颜色未随主题切换；且曾误将彩色字一并转黑/白。处理：确立颜色铁律——深色页显示白字、浅色页显示黑字，**仅对默认黑/白字体生效，带额外颜色设定的字体一律不动**；改动前先分析 EPUB 字体颜色实现。
17. **深色模式排版渲染短暂白屏、快速翻页渲染错误**：尝试预加载前后章后问题增多，**回退预加载**。教训：⚠️ 预加载方案要谨慎评估性能与状态同步成本；白屏应从“先渲染骨架/保持上一帧”方向解决。
18. **横屏双页超界与拼接错乱**：NGA 特殊排版文字单行过长导致页面出界。处理：参考 flow/epub.js 的列几何（border-box、精确列宽、补偶数列），楼层允许跨页拆分，长表格页内滚动。
19. **单页阅读滚动条被遮挡、滚动条与顶/底栏冲突**：处理：交互重写；滚动模式边缘唤出区域横向限制为书籍内容宽度，滚轮滚动立即收栏。
20. **“点击翻页区域”功能混乱**（有时翻页有时换章）：处理：移除该功能，翻页只走明确控件；底部上一章/下一章在单/双翻页时默认隐藏。
21. **顶/底栏自动隐藏失效、固定按钮失效、二级菜单不收起**：处理：分页模式边缘唤出生效；翻页/换章后立即隐藏；顶栏收起时二级菜单同步收起；固定模式不再被正文点击解除。

### 4.4 数据与功能

22. **退出重进后已导入书籍无法加载**：处理：书架/进度持久化修复，进度用 text_offset 原子写入。
23. **全文搜索漏掉靠后章节结果**（搜“丰川祥子”只到 170 楼）：处理：按章限量（50 条/章）+ 续取更多 + 按章分组折叠；高频词靠后章节不会被挤掉。
24. **阅读统计卡片不显示书名、小屏幕超出显示范围**：处理：默认全部书籍汇总 + 详情按书目查看 + 侧栏统计入口 + 自适应。
25. **发行版多次残留个人 NGA 登录配置**：处理：`config.ini` 加入 gitignore；PyInstaller 只打包 `config.ini.example`；每次压包前扫描 dist 确认无 `settings.json / nga_config.ini / config.ini` 与真实凭据。⚠️ **发布前凭据扫描应成为固定步骤**。

### 4.5 发布与工具链

26. ⚠️ **GitHub Release 中文资产名损坏**（`安科书架-目录版.zip` 上传后变成 `-.zip`）：PowerShell → gh 的中文参数/文件名在传输中被破坏，REST PATCH 改名同样失败。处理：用户拍板资产名用纯 ASCII：`AnkeShelf-v1.2.0.zip`；上传用 `Invoke-RestMethod`/`curl` 直连 `uploads.github.com`（URL 编码 ASCII 名）可稳定成功。教训：**自动发布脚本里不要依赖中文文件名/参数经过 PowerShell 管道**。
27. **压缩包条目分隔符**：本机 PowerShell 5.1 的 `Compress-Archive` 与 .NET Framework `ZipFile.CreateFromDirectory` 都会生成反斜杠分隔的条目（`AnkeShelf\...`），与既有发行包一致，Windows 下可正常解压；打包时注意保留 `AnkeShelf\` 顶层目录（目录版结构）。
28. **历史 Release 说明读取乱码**：是控制台/管道转码问题（PowerShell → Python 管道），GitHub 上实际存储完好；查询时用 `curl -o` 落盘原始 JSON 最可靠。

---

## 5. 推送与发布记录

### 5.1 提交历史（main）

| 提交 | 日期 | 说明 |
|---|---|---|
| `cf48780` | 2026-08-06 | Initial commit: 安科书架 AnkeShelf - NGA 安科阅读器 |
| `66712d2` | 2026-08-06 | Add MIT license and LXGW WenKai OFL 1.1 license |
| `58979d1` | 2026-08-06 | Switch project license to GNU AGPL-3.0 |
| `0e66d23` | 2026-08-06 | Set project version to v1.0.0 |
| `cdfdf8a` | 2026-08-06 | fix: 下载面板打开时不再把残留完成状态当作新完成事件重复跳转阅读器 |
| `cb87a35` | 2026-08-06 | v1.1.0: 滚动默认与分页重构、原生书首下即建、热更新设置化、统计/配色/沉浸式全屏等 |
| `3adf1c2` | 2026-08-07 | v1.2.0: 全文检索、主题个性化、设置/下载页重构与 README 开源致谢 |
| `a852727` | 2026-08-07 | docs: 补充 NGA Cookie 获取详细说明与界面实机截图 |

> 说明：git 仓库从“安科书架”初版起即保留以上历史；更早的两个源项目对话内容未以 commit 形式进入本仓库。

### 5.2 Release 记录

| 版本 | 发布时间（UTC） | 资产 | 要点 |
|---|---|---|---|
| v1.0.0 | 2026-08-05T19:40Z | AnkeShelf-v1.0.0.zip | 首发：下载/热更新/EPUB 转换/NGA 原版排版/多种翻页/搜索/标注/统计/数据迁移 |
| v1.1.0 | 2026-08-06T11:13Z | AnkeShelf-v1.1.0.zip | 滚动默认、单/双翻页重构、原生书首次即建、纯增量热更新、主题自定义、统计重做、沉浸式全屏 |
| v1.2.0 | 2026-08-07T07:06Z | AnkeShelf-v1.2.0.zip | 独立全文检索、9 套色板+自定义配色、设置/下载页 Tab 化、README 开源致谢 |

Release 地址：https://github.com/gighi-947/anke-shelf/releases

### 5.3 发布 SOP（已验证）

1. 全量回归：`python -m unittest discover tests`（当前 174 项）+ `node --check web/js/*.js`。
2. 全面替换版本号：`app/__init__.py`、`web/js/bridge.js`（mock `get_version`）、`tests/test_api_service.py`、`使用说明.txt`、`README.md`、`ngapost2md-python/ngapost2md/__init__.py`；`settings_version` 不要动。
3. 打包：`python -m PyInstaller --noconfirm --clean ankeshelf.spec`。
4. 复制 `README.md / LICENSE / OFL.txt / 使用说明.txt` 到 `dist\AnkeShelf\`。
5. **凭据扫描**：确认 dist 内没有 `config.ini`（仅 `.example`）、`settings.json`、`nga_config.ini`、日志或真实凭据。
6. 压目录版 zip：zip 内含 `AnkeShelf\` 顶层目录，命名 `AnkeShelf-vX.Y.Z.zip`（纯 ASCII，带版本号）。
7. `git tag -a vX.Y.Z -m ...` + `git push origin main --tags`。
8. `gh release create vX.Y.Z <zip> --title ... --notes ...`；若中文名/说明不可靠，用 `curl`/`Invoke-RestMethod` 直连 `uploads.github.com` 上传。
9. 用 REST API 核验资产名与大小。

---

## 6. 开发者个人偏好

> 这是长期协作中反复出现的偏好，新对话应默认遵守，除非用户另行明确。

### 6.1 命名与语言

- 全中文沟通；产品/文档使用中文命名（安科书架、使用说明.txt、目录版）。
- 英文名 AnkeShelf；版本号语义化 `vX.Y.Z`。
- 压缩包命名要带版本号，且**不要中文名**（GitHub 资产名用 `AnkeShelf-vX.Y.Z.zip`；本地可保留中文“目录版”命名习惯，但以最新明确指示为准）。

### 6.2 版本与发布纪律

- 重要功能/修复完成后升版本并发布 Release；版本号所有引用位置一次性更新。
- **推送纪律**：用户未明确授权前，代码与发行版不得上传 GitHub（曾明确说过“在我明确要求之前，不要将代码和发行版上传，但完成改动后在本地仍然要打包发行版”）；用户说“推送/发布”时才推。
- 发行版必须包含 README、LICENSE、OFL 与使用说明。

### 6.3 开源与致谢

- 项目自身用 GNU AGPL-3.0；内置字体霞鹜文楷（SIL OFL 1.1）随包带 OFL.txt。
- 借鉴开源项目必须**如实承认**：README 的“设计参考与开源致谢”逐项列出 Readest、flow、epub.js、Foliate、Koodo Reader、KOReader、daisyUI、ngapost2md、LXGW WenKai 及其许可证与借鉴内容；源码注释保留“参考 XX”标注。

### 6.4 UI / UX

- 对齐 Readest 视觉：深色为主、浮动顶栏/底栏/侧栏、简洁严肃；拒绝简陋 UI。
- 设置页/下载页详细内容**居中**展示；选项带简短说明。
- 交互要明确、防误触：翻页只通过明确控件；顶/底栏自动隐藏要可预期；悬浮按钮不要遮挡正文。
- 沉浸式阅读（软件全屏）是重要功能，进入有提示、退出要彻底。

### 6.5 性能与稳定性（硬性要求）

- 轻量：内存占用、加载速度是验收项（曾因 >3GB 内存被否）。
- 启动后不能有“必须等几秒才能操作”的卡死窗口期；任何操作都不应让窗口未响应。
- 深色模式不能闪白屏；快速翻页不能渲染错乱；页面不能出界/错位。

### 6.6 NGA 排版与颜色原则

- 完整还原 NGA 原版视觉（楼层卡片、引用、骰子、28 种标准色、`[color]` 标签）。
- **彩色字体保留原色**，只有默认黑/白字体随主题深浅切换；这是“NGA 安科的精髓”，改动前必须先分析颜色实现。
- 长表格要页内滚动、楼层允许跨页拆分；滚动模式=一章到底不分页。

### 6.7 数据与隐私

- 用户数据（含 NGA Cookie）只存本机；仓库与发行版**绝不允许残留开发者/用户凭据**；提供“清除已保存配置”与“卸载并清除数据”。
- 旧数据目录要自动迁移（EpubReader → AnkeShelf）。
- 凭据获取说明要手把手（README + 使用说明都写详细步骤）。

### 6.8 文档与测试

- README/使用说明要详细、诚实（包括开源致谢与已知限制）。
- 实机演示截图可以进仓库（docs/screenshots）供 README 展示，但**不进发行目录**。
- 重视自动化验证：174 项单测 + UI harness + 真实 NGA 书端到端脚本（tests/ui/verify_nga_real.py）。
- 写 NGA 宣传帖/更新帖时语言简洁严肃。

---

## 7. 当前工作区与工程状态

- 分支：`main`；HEAD：`a852727`；工作树除本交接文档外干净。
- 版本：v1.2.0；发行资产 `dist\AnkeShelf-v1.2.0.zip`（已上传 GitHub Release v1.2.0）。
- 版本号位置清单（升级时全量替换）：`app/__init__.py`、`web/js/bridge.js`、`tests/test_api_service.py`、`使用说明.txt`、`README.md`、`ngapost2md-python/ngapost2md/__init__.py`。
- 测试命令：
  - `python -m unittest discover tests`（174 项）
  - `python -m tests.make_test_epub`（生成测试样本）
  - `python -m tests.ui.runner`（UI 自动化，需桌面会话）
  - `python -m tests.ui.verify_nga_real`（真实 NGA 书端到端，需网络）
- 打包命令：`python -m PyInstaller --noconfirm --clean ankeshelf.spec` + 复制文档 + 压目录版 zip。
- 开发机工具路径（本机）：
  - Python：`F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`
  - Node：`F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe`
- 发行包内置 Python 3.12（dist 内 `python312.dll`）；README 中记录了 pywebview 6.2.1 + Python 3.14 下 winforms 后台线程打印无障碍/COM 日志的已知噪音（已由 `app/main.py` 的 `_silence_pywebview_noise` 过滤）。
- 目录结构：见 README「目录结构」与 docs/ARCHITECTURE.md；当前文件（本交接文档）位于项目根目录。

---

## 8. 安卓版本开发交接要点

### 8.1 可直接复用的资产

- **整个 `web/` 前端**：纯静态、平台无关（HTML/CSS/JS），书架/阅读器/设置/下载导出页/全文检索/主题引擎均可直接复用或微调。
- **交互与算法语义**：text_offset 坐标、按章限量的全文检索、主题色板与“仅默认黑/白文字变色”规则、滚动=分章不分页、双页补偶数列、NGA 长表格页内滚动、楼层跨页拆分。
- **NGA 下载与格式化内核**：`ngapost2md-python/` 的抓取/格式化/内联样式逻辑（若安卓端继续用 Python，可整体随带；若换语言，则需重写等价逻辑）。
- **文档与界面预览**：README、使用说明、docs/screenshots 可直接作为产品说明素材。

### 8.2 需要重写/移植的部分

- Python 后端服务层（`app/`）：本地 HTTP API、存储、搜索索引、下载/热更新服务需在安卓运行环境重新落地（方案待定：如 Chaquopy 带 Python、Kotlin 重写、或 Rust/Go 重写等）。
- 窗口壳：pywebview → Android WebView（System WebView）；本地 HTTP 回环与令牌鉴权模型可保留，也可考虑 JS Bridge 直连。
- 文件对话框、全屏、DPI/缩放、系统集成（通知、分享、导出到自选目录）等平台能力。
- 数据目录与迁移：`%APPDATA%\AnkeShelf` → 安卓应用私有目录/媒体目录；旧版迁移逻辑安卓版一般不需要，但桌面数据若需跨端导入要考虑。
- 打包与发布：APK/AAB 签名、渠道包；GitHub Release 资产命名沿用 `AnkeShelf-vX.Y.Z.apk/aab`（纯 ASCII + 版本号）。

### 8.3 桌面端踩坑对照表（安卓端务必注意）

| 桌面端问题 | 安卓端对照 |
|---|---|
| 启动后立即操作卡死 | 首屏初始化完成前禁止交互；WebView 与本地服务就绪时序要严格 |
| 内存 >3GB、加载慢 | 控制常驻内存；索引惰性构建；图片懒加载；避免整书一次载入 |
| DPI 缩放模糊/错位 | viewport、`textZoom`、devicePixelRatio 处理 |
| 深浅色切换黑底黑字/白屏 | 颜色铁律 + 保持上一帧/骨架屏，预加载需谨慎（曾回退） |
| 横屏双页超界错位 | 移动端横屏 viewport 变化更频繁，分页几何要响应式重算 |
| 滚动条/浮动栏冲突 | 触摸/滚轮事件的栏显隐策略要独立设计 |
| 全文搜索漏后章 | 必须保留“每章限量 + 续取” |
| 热更新整帖重下 | 必须保留原生书容器 + 纯增量语义 |
| 凭据残留发行包 | 安卓包同样不能含任何 Cookie/凭据；上架前扫描 |
| 中文文件名损坏 | 发布脚本/资产名一律 ASCII；中文走 URL 编码也曾在传输链路上损坏 |

### 8.4 遗留方向与未决问题（来自 docs/NGA_READER_PLAN.md 与历史对话）

- 并行下载多帖（队列）。
- 帖子库管理页（按 tid 分组、删除输出目录）。
- 直接渲染 NGA HTML（不经 EPUB）的沉浸式模式。
- 图片懒加载 / 分卷 EPUB 降内存。
- 安卓版项目命名、图标、商店文案、隐私政策（NGA Cookie 本机存储需向用户说明）均未定。

---

*本文件由 Codex 依据仓库历史、README/架构文档与历次对话记录整理；如与用户最新指示冲突，以用户最新指示为准。*

## Android 待办（M2 遗留，先记录后解决）

- **加载速度有待改进**：阅读器章节加载/WebView 渲染首屏耗时偏长（含内置字体 LXGW 26MB 与 NGA 排版），后续做懒加载/预加载评估（曾因预加载引入状态问题而回退，需谨慎）。
- **未完全加载时换章可能失效**：章节内容尚未渲染完成时切换章节，偶发停留在旧章/空白，待定位（可能与 onPageFinished/字体 ready 时序有关）。
- **分页与沉浸式**：已实现 CSS 多栏分页、右缘安全余量、异形屏安全区（自动/手动调节）、控制条纯浮层不重排、系统栏进入即隐藏。
- **M3 进展（2026-08-08）**：NGA 下载链路端到端可用——app_api 拉页、BBcode→HTML 转换（format_html + smile_map 移植）、原生书首建 + 增量追加、前台服务 + 通知 + 取消清理、下载页（配置/下载/更新）。待办：增量拉页断点完善、导出 EPUB/Markdown（SAF）、真机多机型验证、Service exported 保持 false。

## 会话结束状态（2026-08-08 暂停）

- 分支：`android/m1-data-layer`，HEAD=`4dd65f1`（未推送 GitHub）。
- 工作区干净；仅本文件（开发记录）未跟踪，不入库。
- 模拟器 Pixel_7_API_36 已冷启动验证过 M3：原生书 `nga_library/41989465/book`（真实下载前 100 楼）在模拟器应用内，shelf.json 已注册（nga_tid=41989465）。
- 下次继续点：
  1. 导出 EPUB/Markdown（SAF 自选目录，参考桌面 app/epub.py + export_service.py）；
  2. 下载页已下载列表与更新入口完善；
  3. 增量拉页断点细节（download.json 与 process.ini 语义核对）；
  4. 真机验证前台服务通知权限（Android 13+ POST_NOTIFICATIONS）；
  5. M2 遗留待办：加载速度、未完全加载时换章失效。
- 常用命令见上方 M3/构建说明：Gradle 需 JAVA_HOME=D:\Android\AndroidStudio\jbr；模拟器冷启动用 android.exe emulator start --cold Pixel_7_API_36。

## M3 完成状态（2026-08-08）

M3 已按桌面方案全部落地并实测通过：
- 下载/取消/前台进度：NgaDownloadService + 通知 + 取消清理新建半成品（实测新 tid 全量下载中途取消无残留）。
- 原生书：NativeBookWriter 首建 + BBcode→HTML（format_html/smile_map 移植），5 楼小书端到端可阅读。
- 纯增量热更新：download.json 断点续拉、appendContainer 按 pid 去重、update 幂等实测通过；同 tid 重下默认走增量（桌面语义），UI“重新下载”= fullRedownload。
- 导出：EPUB（自写 ZIP/OPF）+ Markdown（BBcode→MD 简化版），SAF 保存，文件名带安科原标题，已实测。
- NGA 配置：私有 ini（uid/cid/ua），下载页内配置区。
- 凭据扫描：APK 无 nga_config/config.ini 残留，通过。
- 顺手修复：阅读器系统返回键（BackHandler 返回书架）。

后续（M4 候选）：全文搜索、标注、统计、设置全 Tab、图片查看；M2 遗留（加载速度、换章失效）仍待办。

## M2 遗留已解决（2026-08-08）

- 加载速度：26MB LXGW 静态 @font-face 会阻塞 WebView onPageFinished（实测 2.6s → 0.4~0.6s，换章 1.95s → 0.2s）。改为 JS 动态注入 @font-face + fonts.load，首屏先用系统字体渲染，字体就绪后 onResize 重排保位。
- 未完全加载时换章失效：根因是 AndroidView.update 闭包在重组前被调用，捕获旧章节 HTML（rawLen 已变但 wrapperHtml 未变）。改为 LaunchedEffect(chapterIndex) 在重组后主动构建并加载；并加加载令牌（loadSeqRef + view.tag）过滤过期 onPageFinished；saveNow 增加 pageReady 保护避免中断期 JS 报错。快速连点 5 次最终章节正确。
- 保留 init ms 与 font ready ms 日志便于后续调优。

---

## 9. 安卓端开发日志（M0 → M4，2026-08-08）

> 本节为安卓端从工程搭建到 M4 进行中的完整记录，按时间正序；后续改动持续追加到 9.6/9.7。

### 9.1 里程碑一览

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 工程骨架：Compose 单模块、版本目录、CI、模拟器 Pixel 7 API 36、README 安卓章节 | ✅ 完成 |
| M1 | 数据层移植：EPUB 解析/text_offset/设置迁移/书架/进度/标注/统计 + 原生书容器 + 对照单测 | ✅ 完成（38 项单测） |
| M2 | 阅读 MVP：SAF 导入、书架、WebView 阅读器（滚动/分页）、进度恢复、主题切换、异形屏适配 | ✅ 完成（遗留已清） |
| M3 | NGA：下载/取消/前台进度、原生书、纯增量热更新、导出 EPUB/MD（SAF）、配置页 | ✅ 完成 |
| M4 | 全文搜索、统计、设置全 Tab、标注、图片放大、沉浸式 | 🔄 进行中（搜索/统计/设置 UI 已完成，标注与图片放大待做） |
| M5 | 维护 SOP：VERSIONING.md、check-release.ps1、发布清单 | ⏳ 未开始 |

### 9.2 安卓提交历史（按时间正序）

| 提交 | 说明 |
|---|---|
| `7c5bf6c` | M0 工程骨架（Compose 外壳/阅读器占位/CI/文档） |
| `9549a73` | M1 数据层移植（EPUB/text_offset/设置/书架/进度/标注/统计 + 38 项单测） |
| `2b005e5` | M1 原生书容器（meta/floors/chapters + 增量追加）与 NGA 连通性 spike |
| `3710d51` | M2 阅读 MVP（SAF 导入/书架网格/WebView 阅读器/进度/主题/设置） |
| `e68b87f` | 修复 EPUB 真机解析与阅读器渲染；自建 HTML 壳 + 点按交互 + 目录侧栏；参考调研 |
| `f63e27d` | 阅读器分页模式（CSS 多栏/text_offset/双页比例判定/页码指示） |
| `b936673` | 修复分页右缘漏页与模式按钮语义；拦截章节内链接导航 |
| `cefc996` | 分页按实际渲染列宽对齐，消除右缘漏页与逐屏累积偏移 |
| `4da9e16` | 分页几何按 flow/epub.js 列宽对齐，彻底消除右缘漏页与逐屏偏移 |
| `d7e18d6` | 分页长行/大字防横向溢出（任意断行 + 超宽表格滚动容器） |
| `8bc4ba8` | 分页右缘增加约 19px 安全余量，消除大字/亚像素下的下一页泄漏 |
| `fe3832e` | 阅读器沉浸式自动隐藏系统栏与控制条；异形屏安全区分级避让 |
| `f530699` | 顶部安全区支持自动/手动调节，自动模式沉浸式安全区减半 |
| `79ea76b` | 控制条改为纯浮层不触发重排；系统栏进入即隐藏保持，阅读位置稳定 |
| `4dd65f1` | M3 NGA 下载与转换（app_api 拉页/BBcode→HTML/原生书首建/前台服务/下载页） |
| `b8ae5cd` | M3 SAF 导出 EPUB/Markdown（自写 ZIP/OPF，文件名带安科原标题） |
| `b0c27b6` | M3 收尾：同 tid 默认增量更新/断点修正/更新默认参数回填/列表更新入口/阅读器返回键 |
| `687b11e` | 修复 M2 遗留：字体动态加载首屏提速 6 倍；章节加载改 LaunchedEffect 消除换章竞态 |
| `843b383` | M4 前置：UI 方案对齐桌面样式并落地 Material 3（docs/ANDROID_UI_PLAN.md） |
| `0da7a49` | M4 UI：设置六 Tab/搜索/统计页 + Material 图标导航 + 主题层重构 + 统计心跳接入 |
| `3a60295` | 设置页改一二级菜单与平板主从布局；修复说明文字竖排 |
| `a743bc4` | 对齐顶部安全区并加粗主题色页头 |
| `18f849b` | 滚动阅读底部换章按钮，侧边点击不再误触换章 |
| `7e908b0` | 阅读器控制条手动唤出保持/滚动收起；顶栏标题颜色自适应；主页面页头加深竖条 |
| `0f589b5` | 分页几何左右等距居中，翻页左缘露出列间距而非上一页内容 |

### 9.3 M4 前置调研与设计（2026-08-08）

- 通读桌面端 `web/js/settings.js`、`fullsearch.js`、`stats.js` 与 `web/css/reader.css`（设置 6 Tab、搜索按章 50 条/续取、统计卡片 + 7 天柱状图）。
- 通过阿里云 npm 镜像拉取非官方参考 `starlight-theme-md3` v0.2.1 源码（解包至 `.tools/starlight-theme-md3/`），采纳其 token-first、tonal surface、状态层 + 涟漪、route 动效取向；同时参考 M3 官方 Search / App Bar / Motion 文档。
- 产出 [docs/ANDROID_UI_PLAN.md](docs/ANDROID_UI_PLAN.md)：设置/搜索/统计三页的组件级设计、M3 颜色/形状/字体/图标/动效落地细则、实施顺序与验收。

### 9.4 M4 UI 实现详情（2026-08-08）

- **主题层重构**（`ui/theme/Theme.kt`）：移植桌面 9 套 PALETTES + 四自定义色；`theme_mode=system` 且未自定义时 API 31+ 走动态取色；阅读器与 Compose UI 同源（readerTheme 支持自定义四色与系统深浅）。
- **Material 图标导航**：底部五 Tab 全部图标化（LibraryBooks/FileDownload/Search/Settings/Insights），选中态实心 + secondaryContainer 指示器；页面切换 180–220ms 滑动淡入动效（Compose 1.11 自动遵循系统动画时长缩放）。
- **全文搜索**：`data/SearchIndex.kt` 严格移植桌面 `search.py`（惰性内存索引、每章限 50 + searchMore 续取、大小写敏感、全词匹配、重叠命中与 str.count 语义一致）；搜索历史每书 ≤10 存 `search_history.json`；搜索页 M3 胶囊搜索框 + 书选择器 + FilterChip + 按章分组折叠 + 关键词高亮 + 命中跳转阅读器（text_offset）。实测“MYGO”命中 327 处 / 130 章。
- **阅读统计**：8 张统计卡（累计/今日/近 7 天/会话/平均/翻页/连续/最近）+ 最近 7 天柱状图（点按显示时长）+ 按书筛选卡片；修复 `StatsStore` 全局汇总漏记 sessions/last_read_at/pages_flipped；阅读器接入 5 秒心跳 + 翻页计数（实测 10 秒/会话 1/最近阅读 8月8日）。
- **设置页**：先做桌面同款六 Tab，后按移动端习惯改为一二级菜单（手机：分组列表 → 详情；宽度 ≥600dp：左侧 NavigationRail + 右侧详情面板，矮屏导航可滚动）。

### 9.5 后续 UI 迭代与修复（2026-08-08）

1. **设置页一二级菜单 + 平板主从布局**（`3a60295`）：手机端仿系统设置分组列表（图标 + 摘要 + 箭头），点入二级详情；平板/横屏左侧导航右侧详情。同时修复说明文字竖排——根因是滑块 `weight(1f)` 抢占整行宽度，把说明列挤成一字一行；改为滑块固定宽度 + 控件限宽 220dp。
2. **顶部安全区对齐**（`a743bc4`）：根 Scaffold 与页面内 Scaffold 各加一次状态栏 inset 导致双重安全区；根层改 `WindowInsets(0)`，下载页补自己的 `statusBarsPadding()`。实测书架页头 y=356 → 220。
3. **滚动模式换章**（`18f849b`）：左右点击不再换章（防误触）；内容底部加“上一章/下一章”按钮（对齐桌面 `chapter-nav-row`，分页模式隐藏）。
4. **页头样式**（`a743bc4`/`7e908b0`）：底部 Tab 初始页页头加粗 + 主题色；后按用户补充新增共享组件 `PageHeaderTitle`（标题左侧 3dp 加深竖条，比主题色向黑混 18%），应用于书架/下载/搜索/设置/统计；阅读器顶栏章节标题同样带竖条并显式使用 `onSurface`（修复深色背景黑字）。
5. **阅读器控制条**（`7e908b0`）：点中间唤出后保持显示（不再 3 秒自动收回）；分页滑动翻页立即收起；滚动模式文档滚动一段距离后收起（复用节流 saveProgress 回调，避免新增 JS 桥的时序坑）。
6. **分页几何居中**（`0f589b5`）：左右 padding 由“左 40/右 9”改为等距 `P = min(margin, gap−8)`（默认 20dp），文字块水平居中；因 `P ≤ gap−8`，翻页后左缘露出的是列间距（空白）而非上一页末尾约 12px 内容；右缘仍保留约 8dp 安全余量，不复发“漏下一页”。

### 9.6 安卓端调试排障记录（按主题）

1. **首屏/换章慢（M2 遗留）**：26MB LXGW 静态 @font-face 阻塞 onPageFinished（首屏 2.6s）。改为 JS 动态注入 @font-face + `fonts.load()`，首屏先用系统字体、就绪后 `onResize()` 重排保位（0.4~0.6s；换章 1.95s → 0.2s）。
2. **换章偶发失效（M2 遗留）**：`AndroidView.update` 闭包在重组前被调用，捕获旧章节 HTML。改为 `LaunchedEffect(chapterIndex, session)` 内主动构建并 `loadDataWithBaseURL`；`loadSeqRef + view.tag` 过滤过期 `onPageFinished`；`saveNow` 加 `pageReady` 保护。快速连点 5 次通过。
3. **分页右缘漏下一页（M2 系列）**：先后用“按实际渲染列宽对齐”“任意断行”“右缘 19px 安全余量”逐步收敛；最后“左缘露上一页”由等距 padding 修复（见 9.5.6）。
4. **控制条唤出不稳定（模拟器表象）**：曾误判为逻辑问题，实际是模拟器横竖屏切换与 uiautomator 转储在 WebView 上不稳定（节点不全/偶发 null root）；以 logcat 日志为准验证通过。
5. **滚动收起最初不生效**：注入 JS 中 `if` 被并到中文注释同一行，整段被注释吞掉；拆行后恢复。教训：**Kotlin 多行字符串内嵌 JS 时，注释必须独占一行**。
6. **模拟器 WebView 程序化 scrollLeft 后偶发右半屏重绘滞后**（截图右半空白）：判断为模拟器表面重绘假象；真机未复现，继续留意。
7. **NGA 原生书（tid 41989465）在模拟器点开无反应（EPUB 正常）**：怀疑设备端原生书数据/路径问题，待单独排查（可能与多次重装/数据变更有关）。

### 9.7 当前状态与待办（2026-08-08）

- 分支：`android/m1-data-layer`；HEAD=`0f589b5`（未推送 GitHub）。
- 工作区干净；本开发日志已入库（旧 `开发记录与个人偏好.md` 已更名为 `AnkeShelf_DevLog.md`）。
- 模拟器 Pixel_7_API_36 运行中，已装最新 debug APK；测试设置当前为“分页模式 + 深色主题”（调试时改的，可还原）。
- M4 剩余：标注（高亮/笔记/书签/导出）与阅读器选区交互、图片点击放大、辅助四项（标尺/逐段/速读/滚读）真正接入阅读器、设置统计/数据 Tab 打磨。
- 其他待办：NGA 原生书打不开排查；M5 维护 SOP。

### 9.8 安卓构建/调试常用命令（本机）

```powershell
# 构建 + 单测
$env:JAVA_HOME='D:\Android\AndroidStudio\jbr'
$env:GRADLE_USER_HOME='F:\Users\Administrator\.gradle'
$env:ANDROID_HOME='D:\Codex\project1\.tools\android-sdk'
$env:TMP='D:\Codex\project1\.tools\tmp'; $env:TEMP=$env:TMP
& D:\Codex\project1\android\gradlew.bat -p D:\Codex\project1\android testDebugUnitTest assembleDebug

# adb（需要提权；HOME/USERPROFILE 指向 F:\Users\Administrator）
$env:HOME='F:\Users\Administrator'; $env:USERPROFILE='F:\Users\Administrator'
$env:ANDROID_USER_HOME='F:\Users\Administrator\.android'
$adb='D:\Codex\project1\.tools\android-sdk\platform-tools\adb.exe'
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb logcat -s AnkeShelf:D
```

### 9.9 后续记录模板

每次改动/调试完成后，在 9.6/9.7 或新增小节追加：

```text
### YYYY-MM-DD 主题
- 改动/现象：…
- 提交：`xxxxxxx`（如已提交）
- 结论/验证：…
- 待办/注意：…
```

### 9.10 设计令牌与组件规范（2026-08-08）

- 目标：当前阶段最后的 UI 打磨 + 为下一阶段组件确立规范；新增 `ui/theme/Tokens.kt` 与规范文档 [docs/ANDROID_DESIGN_TOKENS.md](docs/ANDROID_DESIGN_TOKENS.md)。
- 圆角盘点：将散落的 8/12/28/50/1.5/3dp 收敛为 `AnkeRadius`：small=8（按钮/分段/列表行/命中行/封面）、medium=12（卡片/输入框/下拉/色板卡）、large=16（对话框/底部弹层）、pill=全圆角（仅 FilterChip/历史 chip/徽标/色点）。`AnkeShapes` 已接入 `MaterialTheme(shapes=…)`。
- 间距盘点：归一到 `AnkeSpacing`（xxs=2/xs=4/sm=8/md=12/lg=16/xl=24/xxl=32），清掉 6/10/14/20dp 等零散值（组件尺寸与一次性图表细节除外）。
- 颜色语义：`MaterialTheme.ankeColors` 提供桌面 PALETTES 语义映射（bg→background/surface、text→onSurface、primary、accent→secondary、error）；组件禁止硬编码色值（NGA 显式彩色字与一次性高亮/图表细节除外）。
- 组件改造：主按钮与 SegmentedButton 由 M3 默认胶囊改为 small；搜索输入框/下拉、下载与设置输入框统一 medium；搜索历史 chip/徽标保留 pill。
- 主题同步检查（浅色 default-light）：书架背景 #ffffff、页头 #0066cc、封面底 #efefef；设置卡片 #f4f4f4、选中态 primaryContainer、描边 outlineVariant；阅读器背景 #ffffff。搜索页因 NGA 书数据问题未出结果（输入未生效），chip/结果卡颜色由同一 scheme 驱动，代码已对齐。
- 提交：`062308a`。

### 9.11 下载页分组重构与底栏精简（2026-08-08）

- 下载页（NGA 下载）参照设置页做法重构：手机端一级分组菜单（登录配置 / 下载·更新 / 已下载，各带摘要），点入二级详情；平板/横屏（≥600dp）左侧 NavigationRail + 右侧详情面板；各级页头都带返回键（一级返回书架、二级返回一级）。输入框/按钮/卡片沿用设计令牌（medium 圆角、small 按钮、surfaceContainerLow 卡片）。
- 底栏精简：移除“统计”Tab（5→4：书架/下载/搜索/设置）；统计入口仅保留“设置 → 统计 → 详情”，统计页返回键回到设置页。
- 验证（模拟器）：底栏四项；下载一级/二级导航与返回箭头；设置→统计→详情→返回设置链路全部正常。
- 提交：`01767de`。

### 9.12 下载参数“主题/图片”排版修复（2026-08-08）

- 现象：NGA 下载 → 下载/更新 → 下载参数中，主题与图片的 4 个单选选项被硬塞进一行（8 个元素），窄屏下溢出错乱。
- 处理：参照设置页“翻页方式”的 FilterChip 做法，拆成“主题（浅色/深色）”与“图片（在线/无图）”两个独立分组，每组标签 + FlowRow chips；沿用设计令牌（chip=pill 小型标签）。
- 验证（模拟器）：主题、图片各占一组，chips 独立成行、无溢出。
- 提交：`05fa5b1`。

### 9.13 图片查看器与标注（高亮/笔记/书签/导出）（2026-08-08）

- **图片点击放大**：在阅读器 WebView 内实现查看器（暗色全屏遮罩、双击缩放、双指捏合、放大后拖动平移、点击空白/×/系统返回键关闭）；新增 JS 桥 `setImageLightbox`，Kotlin BackHandler 在查看器打开时优先关闭而非退出阅读器。
- **标注链路**：
  - JS：选区变化（300ms 防抖）经 `TextPos.rangeToOffsets` 上报 `onSelection(chapterIndex, start, end, text)`；`applyAnnotations` 按 text_offset 用 6 色 `<mark>` 渲染高亮；点击高亮上报 `onHighlightTap(id)`。
  - Compose：选区操作条（6 色圆点 / 书签 / 笔记 / 关闭）；点已有高亮弹层支持改色、笔记、删除；笔记用对话框保存。
  - 数据：沿用 M1 的 `AnnotationStore`（annotations.json），text_offset 坐标与进度/搜索一致。
  - 导出：设置 → 数据 新增「导出标注」，按书导出 Markdown / JSON（SAF 自选位置）。
- **排障记录**：
  1. `ReaderBridge` 方法 `onSelection` 与构造参数同名，方法体内 `main.post { onSelection(...) }` 递归调用自身，主线程被 post 洪泛（曾表现为 ANR/日志缺失）。处理：回调参数改名 `onSelectionCb / onHighlightTapCb`。
  2. 高亮包装会替换文本节点，旧 `textCtx` 引用失效导致再次选区返回 null；`applyAnnotations` 末尾重建 `state.textCtx = TextPos.build(document)`。
  3. adb 长按选词在模拟器 WebView 上不可靠，验证时用临时程序化选区 hook 打通全链路，验证后已移除。
- 验证（模拟器）：程序化选区 → 操作条出现 → 加黄色高亮（annotations.json 落库 + 页面渲染黄色 mark）→ 点高亮弹层 → 笔记保存成功；图片查看器打开（全屏暗色遮罩）确认；设置-数据导出标注入口确认；清理测试数据后无自动操作条。
- 提交：`8344fad`。

### 9.14 删除辅助功能、新增界面字号、书架列表视图（2026-08-08）

- **删除辅助阅读功能**：移动端用处不大，设置页移除「辅助」分组（标尺/逐段/速读/滚读），设置分组 6→5（外观/阅读/操作/统计/数据）；`SettingsData` 中 `show_ruler` 等字段保留以兼容旧数据，不再暴露 UI。
- **界面字号**：设置 → 外观 → 界面新增「界面字号」滑块（0.85–1.25，步进 0.05）；新增设置字段 `ui_font_scale`（默认 1.0）；`AnkeShelfTheme` 通过 `LocalDensity.fontScale` 在系统缩放之上叠加应用内倍率，阅读器 WebView（CSS px）不受影响。
- **书架列表视图**：对齐桌面 `shelf_view` 设置，书架顶栏新增网格/列表切换图标；列表行 = 封面缩略图 + 书名/作者/进度；切换持久化到 settings.json。
- 验证（模拟器）：设置一级菜单 5 组且无辅助；界面字号 1.25 下文字明显放大；列表视图切换后布局正确、`shelf_view:"list"` 落库。
- 提交：`41b6fa8`。

### 9.15 书架排序/页头精简 + M4 验收（2026-08-08）

- 按用户要求：不做“最近阅读横条”（占空间），改为书架顶栏排序按钮（按最近阅读/导入时间/名称，复用 `shelf_sort` 设置并持久化）；页头“导入”改为“+”图标按钮，删除页头“设置”按钮（设置仍可从底栏进入）。
- 排障记录：一度怀疑增量编译产物丢失新字符串，实际是 Compose 编译器把菜单文案提升到 `ComposableSingletons$BookshelfScreenKt` 类 + 多 dex 分布，且 uiautomator 转储旧文件误导；代码与产物均正常，纯排查弯路。
- **M4 验收**（[docs/ANDROID_M4_ACCEPTANCE.md](docs/ANDROID_M4_ACCEPTANCE.md)）：
  - 桌面 v1.2.0 特性对照表：除“自定义字体导入”“桌面→安卓数据迁移”外全部对齐；辅助阅读与最近阅读横条按用户决策移除/替代。
  - 冷启动 TotalTime 4563ms；阅读器 TOTAL PSS 182MB（<300MB ✅）；快速翻页 10 次无错误/崩溃。
- 提交：`d1e1074`。

### 9.16 图片长按放大/加载/退出 + 自定义字体导入（2026-08-08）

- **图片放大交互**：
  - 打开方式由“点击”改为“长按约 450ms”：Kotlin 触摸层计时，命中图片时调 JS `openImageAt(x,y)` 并 `cancelLongPress()`（避免系统长按菜单）；文字长按放行给文本选择，不影响标注。
  - 加载：WebView 显式允许混合内容（file:// 壳加载 https 图）、正文与查看器图片设 `referrerPolicy='no-referrer'`、UA 用 NGA 默认，规避防盗链。
  - 退出：单击图片也关闭（260ms 计时区分双击缩放）、点空白/×/系统返回关闭。
  - 说明：模拟器无法解析 img.nga.cn（DNS/网络环境问题，非代码），图片加载需在真机/可用网络环境验证。
- **自定义字体导入**：
  - 设置 → 阅读 → 正文字体：内置霞鹜文楷 / 系统默认 / 已导入字体（FilterChip 单选）+ “导入字体…”（SAF 选 .ttf/.otf，复制到 `filesDir/AnkeShelf/fonts/`）。
  - 阅读器：`buildReaderHtml` 注入 `@font-face url(file:///android_fonts/<name>)`；WebView `shouldInterceptRequest` 映射到应用私有字体目录（不开启 file access）。
  - `custom_font` 语义：空/`sys:*`=内置，`system`=系统默认，其他=导入文件名（桌面旧值兼容）。
  - 验证：设置选择 lxgw-test.ttf 落库；打开阅读器渲染正常、无字体加载错误；JVM 单测新增字体注入两条。
- 提交：`f67e050`。

### 9.17 M5 维护 SOP（2026-08-08）

- 完善 [android/VERSIONING.md](android/VERSIONING.md)：安卓独立版本线（0.1.0→1.0.0，首个正式发布建议直接 android-v1.0.0）、标签 `android-vX.Y.Z`、资产 `AnkeShelf-vX.Y.Z-android.apk`、签名（keystore 生成/keystore.properties/build.gradle 配置示例）、发布检查清单（回归→版本号→assembleRelease→凭据扫描→tag→gh release→REST 核验）、凭据红线、中文标题经管道乱码的注意事项；与桌面 SOP 并列不混用。
- 完善 [android/scripts/check-release.ps1](android/scripts/check-release.ps1)：输出 APK 大小与 SHA256；内容扫描限定文本类条目（.ini/.properties/.xml/.json/.txt/.md/.html/.js/.css），避免二进制 dex 误报。
- 排障：脚本首跑把 dex 中的代码字段名（`ngaPassportUid=` 模板字符串）误判为真实凭据 → 内容扫描只针对文本条目后，对当前 debug APK 实测 `RESULT: PASS`（SHA256 输出）。
- 提交：`ebaca20`。

### 9.18 下载后书架自动刷新 / 图片查看器触摸与退出修复 / 封面更新导出按钮（2026-08-08）

- **下载后书架刷新**：Root 每秒轮询 `NgaServiceStatus`，服务状态进入 done/error/cancelled 且发生变化时自动 `refresh++`，下载完成后书架立即出现新书（此前只在点击下载按钮时刷新一次）。
- **图片查看器触摸/退出修复**：根因是查看器 DOM 的 touch 事件 `preventDefault` 吞掉了 click，导致单击关闭/双击缩放全部失效、看起来“触摸无响应且退不出”。
  - 关闭/缩放改由 Kotlin 触摸层驱动：查看器打开时单击 → `AnkeReader.onViewerTap()`（JS 300ms 双击判定：双击缩放、单击关闭）；查看器内拖动不再触发翻页；
  - 系统返回键关闭、点空白/×关闭保留；
  - 移除 DOM touch 的 preventDefault（CSS `touch-action:none` 已防滚动），平移/捏合不受影响。
- **封面更新/导出按钮**：网格书架封面右上角悬浮圆形图标（28dp 按钮 + 16dp 图标，surface 92% 底）：NGA 书显示「更新」「导出」，EPUB 书显示「导出」；导出菜单支持 EPUB / Markdown（NGA 书），EPUB 书导出原文件副本（SAF）。
- **列表视图同款入口**：列表行不在封面上叠按钮，改在行尾放同样的 28dp 圆形「更新」「导出」图标（EPUB 书只有「导出」），与网格封面一致。
- 验证：模拟器网格书架封面按钮出现（content-desc 更新/导出）；编译与单测通过；查看器触摸修复与下载刷新逻辑已实现（模拟器网络/无图章节限制下未端到端复测）。
- 提交：`67d11a3`。

### 9.19 安全检查（2026-08-08）

- 全量审计 Android 端与仓库级安全（报告见 [docs/ANDROID_SECURITY_REVIEW.md](docs/ANDROID_SECURITY_REVIEW.md)）。
- 修复（中危）：章节 HTML 脚本注入——`extractReaderParts` 增加 `sanitizeReaderBody` 清洗（删除 script/iframe/object/embed/base/form/meta refresh/on* 事件/javascript: 链接，保留 NGA 内联样式），并补单测。
- CSP 尝试后放弃：WebView file:// 下 `'self'` 不匹配 asset 子资源（实测 reader.css 被拦），改以输入清洗 + 导航拦截为主。
- 核查通过：权限最小化、allowBackup=false、Service exported=false + dataSync 类型、NGA 仅 https、凭据仅存私有目录、git 历史无敏感文件、CI 无密钥、EPUB 解压无越界、调试开关仅 debug。
- 提醒：`D:\Codex\project1\.local\archive\` 下存在含真实 NGA uid/cid 的本地存档（被 gitignore 覆盖、未入库），建议清理。
- 提交：`340843d`。

### 9.20 下载入口、图片查看器防误触、书架下载后刷新根因修复（2026-08-08）

- **下载入口**：空书架新增「从 NGA 下载」按钮（与「导入 EPUB」并列）；书架右上角「+」改为菜单：导入 EPUB / 从 NGA 下载。
- **图片显示与查看器**：
  - 图片代理：`shouldInterceptRequest` 对 img.nga.cn 用 OkHttp 补 Referer（https://bbs.nga.cn/）、Cookie（uid/cid）、UA 后返回，规避防盗链（模拟器 DNS 无法解析 img.nga.cn，需真机验证）；
  - 长按进入预览时清除系统文本选区，避免选中提示文字；
  - 单击图片不再退出（防误触），双击缩放、点空白/提示/×/系统返回键关闭。
- **下载后书架刷新（根因修复）**：NgaDownloadService 原先创建独立 `Shelf` 实例（内存与 UI 不同步），改为与 UI 共享 `AnkeShelfApp.container`；Root 再叠加 shelf.json mtime 轮询（变化则 `shelf.load()` + 刷新），双保险。
- 验证（模拟器）：空书架双按钮、右上角导入菜单；编译与单测通过。
- 提交：`b98f5ef`。

### 9.21 图片查看器去误触+保存按钮、目录弹层收窄与点击外部关闭（2026-08-08）

- **明确保留“在线图片”做法**：撤销此前 embedded/本地化方向的未提交实验（`NativeBook.kt` / `NgaDownloader.kt` / `DownloadScreen.kt` 恢复 HEAD，仅阅读器保留 OkHttp 代理），下载参数维持「在线 / 无图」；正文与查看器图片仍走在线加载 + Referer/Cookie/UA 代理（真机网络可用时有效）。
- **查看器退出与保存**：
  - 单击图片不退出、双击缩放、双指捏合、拖动平移保留；**点空白/提示文字不再关闭**（此前实现导致误触退出），关闭只走 ×、保存按钮、系统返回键。
  - 新增「保存」按钮（查看器右上角 × 左侧胶囊小按钮）：JS 调 `AnkeReaderBridge.saveImage(src)` → Kotlin 用 SAF `CreateDocument("image/*")` 自选保存位置（免存储权限）；在线图走 OkHttp（Referer/Cookie/UA 与正文代理一致），`file://` 直接复制；保存成功/失败 Toast 提示；文件名取 URL 最后一段并清洗，缺扩展名补 `.jpg`。
- **目录弹层**：面板宽度 300dp → `min(280dp, 82% 屏宽)`，不再占满整屏；新增全屏半透明遮罩，点击面板外任意区域关闭；系统返回键顺序调整为：先关目录 → 再关图片查看器 → 最后退出阅读器。
- 验证：`node --check reader.js` 通过；`testDebugUnitTest` + `assembleDebug` 通过（SAF 保存与查看器手势需真机/NGA 网络验证）。
- 提交：`7ebaa87`。

### 9.22 图片预览兜底修复 + 翻页→滚动模式换章按钮错位修复（2026-08-08）

- **图片预览不显示（保存正常）**：
  - 根因：WebView 在线图片代理原先只覆盖 `img.nga.cn`，真实 NGA 图床还包含 `img.nga.178.com` / `img4.nga.178.com` / `ngabbs.com`（表情与部分附件），这些请求未带 Referer/Cookie 被防盗链拦截；保存按钮走 OkHttp 同链路所以正常。
  - 修复：`shouldInterceptRequest` 代理范围扩大到 `img.nga.cn`、`*.nga.178.com`、`ngabbs.com`（仅 http(s)）；`NgaFormatHtml` 对 `//` 协议相对 URL 补 `https:`，避免 file:// 壳下解析成 `file://img...` 加载失败；查看器增加桥接兜底——lightbox `onerror` 时调 `AnkeReaderBridge.loadImage(src)`，Kotlin 用与保存相同的 OkHttp 链路取图并以 base64 data URL 回填；保存按钮改用 `imageViewerState.src`（原 URL），data URL 回填后保存仍指向原图。
- **翻页→滚动模式换章按钮错位**：
  - 根因：分页布局 `prepare()` 在 `html/body/#paged-scroll` 上写内联高度（视口像素）与列宽，切滚动模式只移除 `body.paged` 类，内联样式残留 → 正文被固定在视口高度盒子里溢出，把底部 `.chapter-nav-row` 按钮压进正文中间；换章重建 DOM 后自然恢复。
  - 修复：`setMode(false)` 增加 `clearPagedLayout()`，清空内联高度/minHeight/maxWidth 并移除双页 spacer，再恢复滚动位置。
- 验证：`node --check reader.js`；新增 `NgaFormatHtmlTest`（协议相对 / `./` 前缀 / 缩略图后缀 / imgSrc 回调 4 条）；`testDebugUnitTest`（67 条）+ `assembleDebug` 通过。
- 提交：`05cd9a9`。

### 9.23 滚动惯性卡顿专项：进度后台防抖落盘等七项优化（2026-08-08）

- **现象**：滚动模式手指离开屏幕后的惯性阶段周期性“一顿一顿”。
- **根因与优化**（详见 [docs/ANDROID_PERFORMANCE_REVIEW.md](docs/ANDROID_PERFORMANCE_REVIEW.md)）：
  1. 主因：进度约 1.2s/次上报 → `ProgressStore.set()` 每次同步原子写盘（主线程磁盘 I/O）。改为内存更新 + 单线程调度器 1.5s 合并落盘，新增 `flush()`，`saveNow` JS 回调完成与退出阅读器时立即 flush。
  2. 正文图片全部立即加载/解码：渲染期注入 `loading="lazy" decoding="async"`（覆盖已下载书），分页模式进入时 `forceEagerImages()` 保持翻页即见图。
  3. 图片代理加 OkHttp 64MB 磁盘缓存，回看/翻页往返不重复下载。
  4. 代理/保存共用 `remember` 的 NGA 配置快照，去掉每请求读 ini。
  5. 图片 load/error 改 document 捕获阶段事件委托，不再每图挂两个监听器。
  6. 底部控制条抽成 `BoxScope.ReaderBottomBar`，进度/页码更新只重组该子树，不再整屏重组。
  7. 滚动上报加 0.2% 变化阈值，顶部/底部与微小抖动不再调桥。
- **权衡**：未启用 `content-visibility`（屏外尺寸估算会破坏滚动进度保存/恢复），作为后续方向记录。
- 验证：`node --check reader.js`；单测 70 条通过（新增懒加载注入 3 条、进度 flush 适配）；`assembleDebug` 通过。
- 提交：`ec8a01a`。

### 9.24 提交前全面代码审查与精简（2026-08-08）

- **拆分**：`ReaderBridge`（含 `PageInfo`）与 `ReaderBottomBar`（含主题循环）从 `ReaderScreen.kt` 拆出，阅读器文件约 1220 → 约 1020 行，职责更单一。
- **去重**：新增 `service/NgaHttp.kt` 的 `Request.Builder.ngaHeaders()`，阅读器图片代理、保存/兜底下载、`NgaClient` 请求统一使用（Referer/Cookie/UA 三处重复消除）。
- **删死代码**：
  - `NgaClient` 移除生产未用的旧摘要接口（`NgaPageSummary`/`fetchPage`/`parseResponse` 等），测试改用 `parsePageFull`/`fetchPageFull` 覆盖同一 fixture；
  - `reader.js` 移除从未调用的 `markPosition`/`restorePosition` 及 `openImage`/`isImageOpen`/`measure` 等未使用导出；
  - 删除无引用且乱码的 M0 占位页 `assets/reader/reader.html`。
- **路径常量**：`AppPaths.logsDir`；崩溃日志、清空数据、字体目录不再硬编码 `"AnkeShelf"`。
- **警告清理**：无意义安全调用、恒假空判断、弃用 `LibraryBooks` 图标（AutoMirrored）、测试可空 ClassLoader。
- 完整审查记录见 [docs/ANDROID_CODE_REVIEW.md](docs/ANDROID_CODE_REVIEW.md)。
- 验证：`node --check reader.js`；单测全部通过；`assembleDebug` 成功且无新增警告。
- 提交：`ae98f8d`。

### 9.25 内置使用说明 + 首次启动空书架提醒（2026-08-08）

- **使用说明随包内置**：新增 `assets/guide/usage.txt`（书架/导入、NGA 下载与更新、阅读器操作、设置、隐私数据、小贴士），由 `GuideScreen` 滚动展示（页头返回键）。
- **设置入口**：设置页新增「帮助」Tab（手机一级菜单与平板 NavigationRail 同步），内含「使用说明」行；`AnkeShelfRoot` 新增 `guide` 路由，从设置或书架进入后返回原页面。
- **首次启动提醒**：书架为空且未看过说明时，空态顶部显示「新用户？」提醒卡（查看使用说明 / 关闭提醒）；已查看或关闭后写 `SharedPreferences("guide").seen`，不再出现；「清除全部数据」会一并重置该标记。
- 验证：单测全部通过；`assembleDebug` 成功（顺带清理书架页两个弃用图标警告）。
- 提交：`c0700c5`。

### 9.26 书籍管理、原生书热更新修复、系统返回键（2026-08-08）

- **书籍管理**：书架网格/列表长按封面弹出管理（重命名 / 删除）；重命名同步书架记录与原生书 `meta.json`（导出文件名随之更新），删除原生书整目录（含 `download.json` 断点与封面文件），均有二次确认。
- **“已下载”页**：按钮图标化（28dp 圆形 `ActionIcon`：更新 / 导出 / 删除），书名最多显示 2（列表）/3（网格）行；沿用书架的网格/列表两种视图并与 `shelf_view` 设置联动；长按同样可管理（重命名/删除）。
- **热更新修复（根因）**：`updateFolder` 原在“页数未变”时直接返回“已是最新”，把落在最后一页的新楼层（新 pid）丢弃；改为始终处理重拉页并按 pid 增量追加，页数不变也能抓到新帖。
- **完成提示**：前台服务结束后保留一条非持续通知（已更新 X 楼 / 已是最新 / 失败 / 已取消）；应用在前台时 Root 轮询到终态会 Toast 提示。
- **系统返回/侧滑返回**：`AnkeShelfRoot` 增加 `BackHandler`：下载/搜索/设置 → 书架、统计 → 设置、使用说明 → 来源页；书架根页保留系统默认退出，阅读器仍由 `ReaderScreen` 处理（保存进度回书架），不再从子页面直接退出应用。
- 验证：单测全部通过；`assembleDebug` 成功，无新增警告。
- 提交：`42f9b4d`。

### 9.27 返回键层级修复（2026-08-08）

- 现象：上一轮加入 Root `BackHandler` 后，任何界面侧滑/系统返回都直接回书架（设置与下载的二级详情跳过了它们的一级菜单）。
- 修复：设置与下载页各加内部 `BackHandler(enabled = group != null)`，二级详情先回一级菜单；一级与统计/使用说明等页面仍由 Root 处理（下载/搜索/设置一级 → 书架，统计 → 设置，说明 → 来源页，阅读器 → 书架并保存进度）。
- 提交：`9609080`。

### 9.28 重写内置使用说明（2026-08-08）

- 按当前功能全面重写 `assets/guide/usage.txt`：新增书籍管理（长按重命名/删除）、已下载页网格/列表双视图与更新/导出/删除图标按钮、更新完成通知（已更新 X 楼/已是最新/失败）、系统返回键层级说明；设置 → 帮助可随时查看，空书架首启提醒同步受益。
- 验证：单测与 `assembleDebug` 通过。
- 提交：`300bc38`。

### 9.29 阅读进度保留修复 + 对齐桌面保存策略（2026-08-08）

- **根因 1（章节号不恢复）**：从书架打开书籍时 `onOpen` 固定 `chapter = 0`，保存的章节号从未用于恢复（只有搜索结果跳转会带章节）。修复：打开时从 `progressOf` 恢复 `chapter_index`（夹取到章节范围），`text_offset` 同步传入，同一章内续读。
- **根因 2（策略缺半截）**：桌面 `api.save_progress` 每次上报还会 `shelf.touch()` 更新 `last_read_at`（60s 节流落盘）；安卓 `Shelf.touch` 早已实现但从未被调用，“按最近阅读”排序一直失效。修复：`BookRepository.saveProgress` 追加 `shelf.touch(bookId)`。
- **持久化兜底**：`MainActivity.onStop` 立即 `progress.flush()`（按 Home 退后台也不丢防抖窗口内最后一次位置）；新增单测验证 `set()` 后台防抖 1.5s 后文件真实落盘。
- **与桌面策略对照**：`progress.json` v2 `{chapter_index, text_offset, updated_at}`、翻页/切章/滚动节流/退出时上报、打开按 chapter + text_offset 恢复——均已对齐（安卓侧写盘改为后台防抖是性能适配，字段与语义一致）。
- 验证：单测全部通过；`assembleDebug` 成功。
- 提交：`b9f2a67`。
