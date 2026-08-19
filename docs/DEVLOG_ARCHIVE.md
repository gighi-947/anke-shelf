# 安科书架 · 开发日志归档（DEVLOG_ARCHIVE）

> 本文件由 `AnkeShelf_DevLog.md` 于 2026-08-12 全量归档生成，保留原始记录，
> 不丢信息。现役日志（当前状态 + 最近流水）见 `AnkeShelf_DevLog.md`；
> 经验教训分类见 [docs/LESSONS_LEARNED.md](LESSONS_LEARNED.md)；
> 架构路线图见 [docs/ARCHITECTURE_ROADMAP.md](ARCHITECTURE_ROADMAP.md)。

## 时间轴索引（精简）

| 时间 | 阶段 | 关键内容 | 归档位置 |
| --- | --- | --- | --- |
| 2026-08-06 | 项目起源与 v1.0.0 | 两项目合流、功能补齐、前后端分离重构、首版发布 | §3.1–3.3 |
| 2026-08-06 | v1.0.0 → v1.1.0 | 热更新、个性化、统计、沉浸式；v1.1.0 发布 | §3.4–3.5 |
| 2026-08-07 | v1.1.0 → v1.2.0 | 全文检索、设置/下载页重构、v1.2.0 发布、截图与文档 | §3.6–3.8 |
| — | 桌面排障主题库 | 启动/下载/排版/数据/发布 28 条调试记录 | §4 |
| — | 发布 SOP 与开发者偏好 | 提交历史、Release 记录、发布 SOP、长期偏好 | §5–6 |
| 2026-08-08 | Android M0–M3 | 移植规划、下载/热更新/导出、M2/M3 状态 | §7–8、Android 待办/M3 |
| 2026-08-08 | Android M4 与早期迭代 | UI 全量实现、图片/字体/下载页、进度早期实现 | §9.1–9.42 |
| 2026-08-09 | Android 进度十轮（9.43–9.52） | 进度整体重写、真机探针、换章/退出/交叉验证 | §9.43–9.52 |
| 2026-08-09 | 进度复盘与收尾（9.53–9.59） | 十轮教训、模式隔离、滚动比例锚点、浮动栏修复 | §9.53–9.59 |
| 2026-08-09 | Android v1.0.0 发布 | 版本/签名/Release、CI 修复、Harness 纪律落地 | §9.60–9.66 |
| 2026-08-10 | Android 收尾 | Compose UI 测试、正式 Logo、Release 资产替换 | §9.67–9.70 |
| 2026-08-09 → 10 | Windows B0–B8 | 契约目录、golden 测试、UTF-16 统一、api 拆分、ApiClient、ReaderSession、事件/迁移/错误码/任务/安全回归 | §10.1–10.13 |
| 2026-08-10 | 会话交接快照 | 当前状态/本机环境/不入库内容/待办/纪律 | §10.14 |
| 2026-08-12 | 架构整合 | 四份评审文档整合与路线图输出 | §10.15 |
| 2026-08-19 → 20 | 第二次 DevLog 收敛 | 2026-08-19 及此前详细流水 | 文末「2026-08-20 二次归档」 |

---

# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：Windows 桌面端与 Android 安卓端跨平台开发的同步日志与交接文档。
> 最后更新：2026-08-10（Windows v1.2.0；Android android-v1.0.0；契约/架构重构 B0–B8 已完成）
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件追加记录**（日期 + 提交 + 现象/结论）。
> 建议阅读顺序：AGENTS.md → 本文件（先看末尾最新快照与 9.x/10.x 流水）→ README.md
> → docs/CODEBASE_MAP.md → docs/GLOSSARY.md → 按任务查 docs/DATA_CONTRACT.md /
> docs/ARCHITECTURE.md / docs/ANDROID_ARCHITECTURE.md。

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

### 9.30 进度精细到段落/页 + 恢复“内嵌图片”选项（2026-08-08）

- **进度精细恢复（对齐桌面）**：
  - 滚动模式保存由“滚动比例”改为 DOM 锚点 `text_offset`（与桌面 `reader.currentOffset()` 一致：取视口顶部正文，分页/滚动分别采样），采样失败才退回比例；
  - 恢复由“比例换算”改为桌面 `seekToOffset` 同款：`text_offset → plainToPoint → getBoundingClientRect → scrollTo` 锚点，段落级精度；
  - 修复“只回到章开头”：首帧布局/字体未稳定时恢复可能落到第 0 页，新增 `restorePending`，`refresh`/`onResize` 用原始 offset 自动重试，直到当前 offset > 0。
- **恢复“内嵌图片”选项**：下载参数改为 在线 / 内嵌 / 无图 三选；内嵌模式下载楼层 `[img]` 图片到 `filesDir/AnkeShelf/images/<bookId>/`（Referer/Cookie/UA），正文图片改写为 `file:///android_images/<bookId>/<name>`，阅读器拦截并映射本地文件，离线可看图；增量更新同样支持；在线模式仍走远程 + OkHttp 代理。
- 使用说明同步更新图片选项说明。
- 验证：`node --check reader.js`；单测全部通过；`assembleDebug` 成功。
- 提交：`8b3dccc`。

### 9.31 重新审视渲染方案：NGA 排版 EPUB / 大章加载提速（2026-08-08）

- **定位到的卡顿根因**：
  1. 章节文本读取 + 清洗 + 组装（多遍正则）全在主线程执行，NGA 排版大章（数百 KB~MB）直接卡住打开动画；
  2. EPUB 章节 base 固定在 `file:///android_asset/reader/`，章节内相对路径图片全部 404（渲染方案缺陷）；
  3. 每章 `applyAnnotations` 无条件重建 `TextPos`（等于每章第二次全量遍历）；
  4. 超大章滚动模式仍同步 `TextPos.build`，阻塞首帧。
- **修复**：
  1. 章节处理移到 `Dispatchers.Default` 后台线程（读取 + extractReaderParts + sanitize + deferImages + buildReaderHtml），UI 不再冻结；
  2. EPUB 章节改用自定义 base `file:///android_epub/<bookId>/<章节目录>/`，`reader.css/js` 改绝对 asset 路径；图片经 `shouldInterceptRequest` 按章节相对路径从压缩包按需读取（顺带修复 EPUB 图片此前完全不显示）；`BookSession` 增加 `chapterBaseDir` / `readAsset`；
  3. `applyAnnotations` 仅当 DOM 实际变化（移除/添加高亮）才重建 TextPos；
  4. 超大章判断改用 `textContent` 粗估；滚动模式 `TextPos.build` 延后到首帧之后，恢复先用比例兜底、坐标就绪后按 DOM 锚点重定位。
- 验证：`node --check reader.js`；单测全部通过（ReaderHtml 断言同步为绝对资源路径）；`assembleDebug` 成功。
- 提交：`f92a40b`。

### 9.32 进度实现复刻桌面端 + 内置阅读字体修复（2026-08-08）

- **进度**：
  - `ProgressStore.set` 改为桌面式“每次保存立即落盘”：更新内存后立即提交到串行后台线程写盘（桌面是 Python 后台线程同步写，语义一致），去掉 1.5s 防抖窗口；
  - 滚动保存节流 1200ms → 500ms（对齐桌面 scroll 500ms debounce）；
  - 换章竞态修复：bridge 上报以“当前章节”为唯一真源，旧页面延迟到达的上报直接丢弃；`saveNow` 改为让 JS 返回 text_offset，按调用时捕获的章节直接写入内存并落盘（对齐桌面 loadChapter 换章前先存旧章）；
  - 打开/换到新章后立即保存新章位置（滚动模式原本没有 report 保存，补齐桌面 loadChapter/seekToOffset 语义）；退出/退后台仍 flush。
- **内置字体**：LXGW 字体文件一直在 `assets/fonts/`，但 `@font-face` 用相对路径 `../fonts/`，EPUB 章节换成 `file:///android_epub/` base 后解析失败；改为绝对 `file:///android_asset/fonts/LXGWWenKai-Regular.ttf`。
- **排版对齐桌面 OVERRIDE**：body 字体/字号/行高/默认文字色加 `!important` 压过章节自带样式（桌面 BASE/NGA_OVERRIDE 语义；显式彩色字体保留）。
- 验证：`node --check reader.js`；单测全部通过；`assembleDebug` 成功。
- 提交：`c37f881`。

### 9.33 原生 Kotlin 渲染器（替代 WebView 第一步）（2026-08-08）

- **架构**：阅读器切换为 Compose 原生渲染（`ui/reader/native/`），不再用 WebView 拼 HTML；设计约束与坑位清单见 [docs/ANDROID_NATIVE_RENDERER.md](docs/ANDROID_NATIVE_RENDERER.md)（分页几何防右漏/安全区/模式切换/字体/图片/进度/标注/性能均从开发日志回填）。
- **块模型**：`ReaderModel.kt` 把清洗后的章节 XHTML 解析成 DOM → 楼层/段落/标题/引用/骰子/表格/图片/追评，span 携带颜色/粗斜体；同时生成章内折叠纯文本与块偏移（桌面 TextPos 同口径）。
- **渲染**：`NativeChapterView.kt` 滚动模式（Column + verticalScroll + text_offset 进度/恢复）；分页模式用 `TextMeasurer` 按桌面几何（P = min(margin, gap-8)、advance = colW+gap、页高减安全区）切行成页，页首偏移即进度锚点；超大章回退滚动；内置霞鹜文楷 Typeface 缓存；NGA 楼层卡片/引用/骰子/表格原生绘制；显式彩色 span 保留、默认色随主题。
- **阅读页**：`NativeReaderScreen.kt` 顶/底栏、目录弹层、主题/字号/模式切换、亮度遮罩、长按图片原生查看（双击缩放、×/返回关闭、SAF 保存）、进度保存（text_offset + 每次落盘）、退后台 flush。
- **图片**：统一字节回调——EPUB 走压缩包相对路径，NGA 在线图走 OkHttp（Referer/Cookie/UA）。
- **保留**：旧 WebView `ReaderScreen` 暂未删除（作为对照与回退），路由已切到原生。
- 验证：`compileDebugKotlin` / `testDebugUnitTest` / `assembleDebug` 全部通过。
- 待办：选区建标注、双栏渲染、真机视觉对齐调优、移除旧路径。
- 提交：`f5045cd`。

### 9.34 原生渲染器：退出闪退修复 + 排版基础增强（2026-08-08）

- **退出闪退修复（三个高风险点）**：
  1. `ProgressStore` 主线程 `flush()` 与后台 `set()` 写盘并发写同一个 `progress.json.tmp`（可能 FileNotFoundException 崩在主线程）→ 增加 `writeLock` 串行化文件写；
  2. 分页恢复时 `pagerState.pageCount` 尚未同步就 `scrollToPage(idx)` 可能越界崩溃 → 先用 `snapshotFlow` 等页数一致再定位；
  3. 阅读页 `DisposableEffect.onDispose` 的保存/flush 加 `runCatching` 兜底，任何异常不再拖垮应用。
- **排版基础增强**：楼层卡片加 4dp 主题色左边条（+ 边框 + 底色），引用块加 3dp 左边条与底色，段落上下 2dp 间距。
- 验证：`testDebugUnitTest` 与 `assembleDebug` 通过。
- 提交：`5ba3455`。

### 9.35 原生阅读器：排版与交互继续对齐桌面（2026-08-08）

- **排版对齐**：
  - 滚动正文最大宽度 = 46em × pageWidth，居中（对齐桌面 `.chapter-wrap`）；
  - 段落间距约 0.6em（不再挤成一团）；楼层头部加虚线分割线；追评卡片缩进 + 边框 + 底色 + 小字头；
  - 引用块底色随主题（深色不再硬编码浅色底）；骰子金色加粗；分页图片高度上限 60% 页高；
  - 自定义字体接入：设置里的导入字体（fontsDir）/ 系统默认 / 内置霞鹜文楷均原生生效（Typeface 缓存）。
- **交互对齐（移动端已筛选版本）**：
  - 滚动模式：点中间唤出/收起控制条，左右两侧不换章（沿用 WebView 时代决定）；
  - 分页模式：点左/右翻页，点中间唤栏；控制条 3 秒自动收起；
  - 滚动模式底部「上一章 / 下一章」按钮（对齐桌面 chapter-nav-row）；
  - 沉浸式：进入阅读器隐藏系统栏（滑动临时唤出），退出恢复。
- **性能**：滚动进度上报改为 450ms 防抖（不再每帧写盘）。
- 验证：`testDebugUnitTest` 与 `assembleDebug` 通过。
- 提交：`af8f9cd`。

### 9.36 原生阅读器：双栏分页渲染（2026-08-08）

- 对齐桌面 PAGINATION_OVERRIDE：横屏且宽高比合适时（PagedLayout 规则），每屏渲染两列，列宽 = (fw − 2P − G) / 2、间距 G、左右边距 P；正文先填满左列再填右列，整屏两列填满后翻到下一页（翻页步进=2 列）。
- 页面结构改为 `NativePage.columns`（单页=1 列、双页=2 列），进度锚点仍为页首 text_offset；安全区上下留白按页内边距渲染。
- 验证：`testDebugUnitTest` 与 `assembleDebug` 通过。
- 提交：`c446992`。

### 9.37 原生阅读器：分页楼层卡片缺失 + NGA 颜色解析修复（2026-08-08）

- **根因**：
  1. 分页模式 `RenderFrag` 只画了文字行，楼层卡片的边框/左侧主题色条/内边距从未绘制（`frag.borderColor/accentColor` 没被使用）→“分页毫无排版”；
  2. NGA 常见的 `<font color="red">` 颜色属性未解析 → 滚动/分页的显式彩色缺失；
  3. 楼层头字段靠字符串截取，格式稍有出入就解析错。
- **修复**：
  - 分页楼层碎片统一包“边框 + 4dp 主题色条 + 内边距”（对齐桌面 break-inside 碎片化边框），头部虚线保留；
  - `spansOf` 的 span/font 同时支持 `style="color:..."` 与 `color="..."` 属性；
  - 楼层头改为正则解析（楼号/赞/用户名/uid/时间）；`blockOffsets` 改为按块数均匀切分（单调且末位=全文长）。
- 新增解析器单测（楼层/引用/骰子/表格/图片/追评/红蓝颜色），验证通过。
- 验证：全量单测 + `assembleDebug` 通过。
- 提交：`d062a51`。

### 9.38 原生阅读器：class 容错 + 章节 CSS 类颜色解析（2026-08-08）

- **class 匹配容错**：`class="nga-floor "`、多类名、`floor-head` 等带空白/换行的写法现在都能识别（之前精确字符串相等，稍有空格整个楼层就退化成纯文本，是“排版没改善”的候选根因）。
- **CSS 类颜色**：解析章节自带 `<style>` 中的 `.red{color:#ff0000}` 等规则，`<span class="red">` 不再丢色（NGA EPUB 常见写法）；与内联 style、`color` 属性三级优先级一致。
- **解析器单测扩充到 3 条**：楼层/引用/骰子/表格/图片/追评 + 红蓝内联颜色；带空格 class 楼层；CSS 类颜色映射。
- 验证：全量单测 + `assembleDebug` 通过。
- 提交：`582f8be`。

### 9.39 用真实导出 EPUB 验证并修复（2026-08-08）

- 用户提供真实导出 EPUB，抽取 0000/0001 两章为测试样本（`test/resources/native/real*.xhtml`）。
- **验证**：模拟器实测新构建——skyblue 彩色字（1887px）与楼层卡片半透明主题色左边条（85px）均确认渲染；此前“颜色没出来”的根因是楼层正文里直接嵌的 `<span style="color:...">` 走逐子节点转块路径时颜色未继承。
- **修复**：楼层正文连续内联内容（文字 / `<br/>` / 加粗 / 彩色 span）合并成同一段（`<br/>`=换行），块级元素（引用/图片/表格/详情）单独成块——既保住颜色继承，又消除“每行一个段落、行距过散”的简陋排版；真实章节解析测试：20 楼层/27 引用/颜色全在。
- 用户 EPUB（`android/*.epub`）加入 .gitignore，不入库。
- 验证：解析器 5 条测试 + 全量单测 + `assembleDebug` 通过。
- 提交：`6b38680`。

### 9.40 原生阅读器排版完全对齐桌面参考截图（2026-08-08）

- 用户提供三张桌面端目标效果截图（浅/深主题楼层卡片、楼头、引用、骰子），并结合桌面 `ngapost2md-python/ngapost2md/epub.py` 与 `web/js/reader.js` 的**精确 CSS 语义**逐项对齐，不再依赖近似值。
- **楼层卡片**：桌面 `.nga-floor` 无背景色（透明，仅 1px 边框 + 4px 强调色左条 + 2px 圆角），reader.js 覆盖为 padding 10px 12px、margin 10px 0；安卓端同步改为透明底、12/10 内边距、10dp 块间距。
- **楼头**：`0楼` 强调色加粗、其余元数据 muted 灰；补充 ` · pid:N`；分隔线改为 1px **圆点虚线**（DottedDivider，桌面 `border-bottom:1px dotted`），颜色用边框色。
- **引用块**：左条改为**边框色**（桌面 `.nga-quote{border-left:3px solid border}`，之前误用强调色），底色 quoteBg、8/12 内边距、正文 0.95em、quote-author muted；分页模式引用不再退化成纯文本，带底色 + 左条 + 外边距。
- **追评**：按 reader.js `!important` 覆盖去掉左缩进；头行 muted 0.8em、正文 0.92em；解析器排除 comment-head 混入正文（修复头文本重复显示）。
- **骰子**：金色随主题切换（浅色 `#B8860B` / 深色 `#D9B45B`，桌面 dice 值），加粗并留 6px 上下边距。
- **分页碎片化边框重构**：不再对每行整框 `border(1dp)`（会出双重横线），改为按片断绘制边线（cardTop/cardBottom 控制上下边，左右边始终连续），跨页时上一页封口、下一页补顶，卡片边框在楼层内部连续不断裂。
- **HTML 实体解码**：`&#39; &amp; &lt; &gt; &nbsp; &#x...;` 等正确解码（修复 `It's MyGo` 显示成 `It&#39;s MyGo`）；属性值同步解码。
- **其他**：楼层头时间解析去掉尾部多余分隔符（消除 `时间 · · pid`）；新增 2 条单测（实体解码、追评头不混入正文），解析器测试总数 7 条。
- 验证：模拟器实测——滚动与分页模式均确认蓝色卡片条/彩色字/引用底/虚线分隔；像素分析确认分页页有主题蓝（#60A8D8 簇）与大量彩色字；全量单测 + `assembleDebug` 通过。
- 提交：`b788e21`。

### 9.41 实机五图对比 + 链接/删除线补齐 + 阅读中重载修复（2026-08-09）

- 用户提供五张实机整页滚动截图（前三张旧 WebView 版：00:07/00:08/00:09；后两张当前原生版：00:12/00:13）。按要求把五张图**同时**拼成一张带编号长图发给视觉模型做整体对比，再分别把“旧三张”“新两张”拼成高清长图各看一轮；并用像素统计交叉验证（蓝/红像素分布、内容边界、左条区域）。
- **对比结论（可信且可复现的两处差距）**：
  1. 旧 WebView 的 `<a>` 链接编号（#6016 等）是主题蓝，原生版渲染成白色——原生渲染器解析了 `ReaderSpan.link` 但从未使用；
  2. 旧 WebView 的 `<del>/<s>` 是灰色 + 删除线（桌面 `del{color:muted}`），原生版是白色原文无删除线。
- **修复**：`ReaderSpan` 新增 `strike/muted`；`spansOf` 对 del/s 置删除线与 muted；渲染层 `spanColor` 统一实现“链接无显式色→强调色、del 无显式色→muted”，并加 `TextDecoration.LineThrough`；分页 `spansAnn` 同步。新增单测：链接 href 保留、del/s 删除线标记。
- **阅读中突然重载回章节首的根因与修复**：
  1. 章节解析 `LaunchedEffect(chapterIndex, session)` 以整个 session 对象为键——书架刷新/下载完成会重建 `record/session`，导致阅读中重解析章节并回到起点；改为只以 `chapterIndex + session.id` 为键；
  2. 分页模式视图尺寸变化（系统栏/安全区/旋转）会重新分页并按 `initialOffset` 定位，等于跳回章节首；新增 `restoreOffset`（按最近一页的 startOffset 持续更新），重排后恢复到当前页；
  3. 滚动模式 `rememberScrollState()` 跨章节复用旧滚动位置；改为 `remember(doc)` 按章节持有，恢复 effect 以 `doc + initialOffset` 为键；
  4. `savedOffset` 在书架刷新后会重建（可能读到旧值）并改变 `initialOffset`；恢复锚点改为 `remember(chapterIndex, session.id)` 每章只取一次。
- **验证**：模拟器端到端——阅读中 `touch shelf.json` 触发书架刷新，阅读位置不变（前后内容行范围完全一致）；最新构建实机截图确认链接编号为蓝色、删除线为灰色 + 删除线、左蓝条正常；全量单测 + `assembleDebug` 通过。
- 提交：`1133592`。

### 9.42 混合架构重构：WebView 渲染内核 + Compose 外壳（2026-08-09）

背景：原生 Kotlin 渲染器（9.33–9.41）在排版完整度上与旧 WebView 版差距明显（楼层卡片、引用、骰子、删除线、链接色等），反复“像素级对齐”成本高且仍不达预期；用户确认“重写浏览器”不可行后提出：能否保留全部视觉效果的同时，把 WebView 框架削减到最小，并让 Kotlin 与 WebView 做衔接。

结论与取舍：
- **保留 WebView 作为唯一渲染内核**（浏览器排版引擎是 NGA 复杂 HTML/CSS 视觉保真的唯一可行载体）；Compose 外壳接管 UI、控制条、目录、图片查看/保存、进度持久化。
- **JS 从旧 `reader.js`（41KB）精简为 `reader-lite.js`（24KB）**：删除 lightbox、选区/标注、图片单击上报、对照导出等壳层功能；保留分页几何（P=min(margin,gap-8)、advance=colW+gap、双页补偶数列）、TextPos text_offset 映射、主题/字号/安全区、换章滚动、`forceEagerImages`、长表格页内滚动、图片 load 重排、节流进度上报。
- **桥协议收敛为最小集合**：JS 上报 `onReady / saveProgress(chapterIndex, offset) / pageChanged(chapterIndex, page, total, offset) / requestChapter / openImage / log`；Kotlin 下发 `init / applyTheme / applyTypography / setMode / setInsets / gotoOffset / flipPage / openImageAt`。
- **图片统一走 `shouldInterceptRequest`**：EPUB 走 `file:///android_epub/<bookId>/<章目录>/` 按章读压缩包；NGA 在线图走 OkHttp + Referer/Cookie/UA 防盗链；本地嵌入图走 `file:///android_images/`。
- **代码迁移**：`ReaderHtml.kt` 切到 reader-lite.js；`NativeReaderScreen` 正文换成 `WebViewChapterView`（单例复用、换章不重建）；`ReaderModel.kt` 把楼层正文/引用“连续内联内容合并成段”逻辑提取为共用的 `convertInlineContent`（原生渲染器保留为回退与对照）；测试同步改为 reader-lite 断言并新增合并逻辑用例。
- **历史交互修复对照**（换掉 WebView 前踩过的坑，9.20 等条目）：长按图片预览并清选区；点中间唤出控制条保持显示、滚动/翻页自动收起；顶/底栏背景与文字改用阅读器主题色（深色下不再黑字）；换章前先保存旧章 offset（9.32 语义）。
- 验证：`reader-lite.js` 语法检查、单测、`assembleDebug` 通过；已装真机待用户反馈。

### 9.43 进度保存实现整体删除重写（桌面端 + 开源阅读器对照研究，2026-08-09）

用户要求：把进度保存实现全部删掉，先参考桌面端方案，再上 GitHub 看优秀开源阅读器（翻页与滚动都要，尤其滚动）。结论先行：视觉渲染保持混合架构（WebView 内核 + Compose 外壳）不动，**进度持久化整体重写为 Kotlin 侧单一入口 `ChapterProgressTracker`**，JS 只负责上报，不再在两端各存一份状态。

#### 研究结论

- **桌面端（本仓库 `web/js/reader.js` + `app/shelf.py`）**：
  - 唯一坐标是章内折叠纯文本 `text_offset`（`TextPos` 逐字符 DOM↔offset 映射）；
  - 滚动模式 scroll 事件 500ms **debounce** 后保存；分页每翻一页立即 `saveProgress`；
  - `loadChapter` 换章前先同步取旧页 `currentOffset()` 并上报旧章；
  - `seekToOffset` 跳转后立即保存；`ProgressStore` v2 `{chapter_index, text_offset, updated_at}`，每次上报同步 `shelf.touch()` 维护“最近阅读”。
- **epub.js**（flow 本地源码）：滚动监听 `MANAGERS.SCROLLED → reportLocation()`，翻页/显示后 `relocated`；用 CFI + Locations（百分比↔位置）锚定，持久化的是“位置”而非滚动像素。
- **Readest**（GitHub `useProgressAutoSave.ts`，经 jsDelivr 拉取）：独立高频 `readerProgressStore`；`lastSavedLocationRef` 与磁盘位置相同则跳过保存（防旧数据覆盖新数据）；打开书先快照磁盘位置，**初始 relocate 不算用户改动、不覆盖**；防抖 1s + 再延时 500ms；卸载时 flush。
- **Legado**（Ecalose/legado master 稀疏克隆）：进度字段 `Book.durChapterIndex/durChapterPos`（章内字符位置，与 text_offset 同语义）；`setPageIndex()`（滚动模式翻页也走它）→ `durChapterPos = getReadLength(index)` → `saveRead(true)`；换章 → `saveRead()`；`onPause` → `saveRead()`；写库走后台 executor，不阻塞 UI。

#### 旧实现的问题清单（本次删除的根因）

1. **换章保存竞态**：旧 JS 桥用“当前章节号”当唯一真源，`idx != chapter()` 直接丢弃旧章上报——换章前 `evaluateJavascript` 异步保存旧章时，`chapterRef` 已先被改成新章，旧章 offset 被丢弃（“几十秒内跳章节进度不变”主因）。
2. **退出保存丢失**：`DisposableEffect.onDispose` 先 `evaluateJavascript` 保存，随后立即 `removeJavascriptInterface + destroy()`，异步回调根本来不及送达（“阅读进度并未保留”）。
3. **桥线程违规**：`@JavascriptInterface` 跑在 WebView 后台线程，旧 LiteBridge 直接在里面写 Compose state（`lastOffset` 等），应 post 到主线程（老 `ReaderBridge` 有 Handler，重写时丢了）。
4. **滚动保存节流而非防抖**：500ms throttle 在连续滚动中频繁整文件写盘；且“退出前只保存 lastOffset”用的是过期值，会拿旧值覆盖新进度。
5. **只有初始章恢复**：`remember(chapterIndex, session.id)` 只对 `initialChapter` 返回 `savedOffset`，会话内跳章再回来永远回章首。
6. **pageChanged 不带章节号**：旧页延迟事件可能记到新章头上。
7. **主线程写盘**：`BookRepository.saveProgress` 里 `shelf.touch()` 同步整文件写，高频上报时卡 UI（滚动惯性“一顿一顿”候选根因之一）。

#### 新实现

- **`ChapterProgressTracker`（新，JVM 可测）**：按章维护 `lastKnown`（内存最新）与 `saved`（已落盘）双 map；滚动/页面上报 500ms debounce 落盘（Readest 去重思想：与 saved 相同直接跳过）；翻页立即落盘；换章/退出/退后台立即 flush；恢复优先内存、其次磁盘（桌面 loadChapter 语义 + 会话内按章记忆）；相同 (chapter, offset) 去重。
- **JS `reader-lite.js`**：滚动改 500ms debounce（与桌面一致）；`pageChanged` 上报携带 `chapterIndex`；比值回退删除（只信 DOM 采样 offset，采样失败不误存 0）；`pagehide` 仅上报有效 offset；滚动恢复采样点修正为 `scrollY - (18 + topInset + 8)`，与采样点严格对齐。
- **`WebViewChapterView`**：桥回调全部 post 主线程；旧章事件按索引转发（不再丢弃）；换章前先 `evaluateJavascript` 取旧页精确 offset（后台线程 CountDownLatch，最多等 300ms，等价桌面 loadChapter 同步存旧章）再加载新章；`onDispose` 取最终 offset → 上报 → flush → 延迟 200ms 再 destroy。
- **`NativeReaderScreen`**：进度全部走 tracker；恢复锚点 `tracker.restoreOffsetFor(chapter)`；BackHandler/返回按钮/目录跳转都 flush；新增 Lifecycle ON_STOP flush（Legado onPause 语义）；删除旧 `lastOffset`/`onProgress` 参数。
- **`BookRepository.saveProgress`**：`progress.set` 同步更新内存（自带后台落盘），`shelf.touch` 移到仓库后台单线程，主线程零磁盘 I/O。

#### 验证

- 新增 `ChapterProgressTrackerTest` 6 条（恢复优先内存/防抖去重/翻页立即/换章即存/flush 取消 pending/零值忽略）；全量单测 91 通过、1 跳过（NGA 网络用例），`assembleDebug` 通过。
- 编译清理：新增 `lifecycle-runtime-compose` 依赖替换弃用 `LocalLifecycleOwner`；ValueCallback 冗余转换警告消除。
- 待真机验证：滚动暂停后立即退出/换章的位置准确性、分页翻页即时进度、深色主题下退出再进恢复点。

### 9.44 真机三症状根因定位与修复：换章闪退（WebView 线程检查）+ 滚动底部换章失效（2026-08-09）

用户实测 9.43 版本反馈三症状：只有重进应用后第一次进入再退出书籍能记录进度；之后无论跳转多少章节都不记录；滚动模式底部换章不可用；悬浮底栏点换章直接闪退。

#### 根因（logcat 实证）

- **换章闪退**：`WebViewChapterView` 换章前“取旧章 offset”的 `evaluateJavascript` 在 `Dispatchers.Default` 后台线程直接调用，触发 Android WebView 线程检查：
  `A WebView method was called on thread 'DefaultDispatcher-worker-1'. All WebView methods must be called on the same thread.`（`WebView.checkThread` → `evaluateJavascript`），主线程崩溃。三个崩溃堆栈（02:07/02:08/02:09）全部指向 `WebViewChapterView.kt:136`。
- **“只有第一次能记进度”的连锁**：由于任何真实换章（悬浮底栏、目录跳转）都会走到同一条换章路径并闪退，后续章节的进度事件全部发生在崩溃进程中，落不了盘；只有“进入→直接退出”的会话能走通 dispose 保存。闪退修复后该连锁自然解除。
- **滚动模式底部换章不可用**：正文底部 `android-prev-chapter/android-next-chapter` 按钮（`.chapter-nav-row`，分页模式 CSS 隐藏）在旧 `reader.js` 里有绑定，重写成 `reader-lite.js` 时漏掉了绑定，点击无任何回调。

#### 修复

- `WebViewChapterView`：`evaluateJavascript` 改为 `web.post { ... }` 投递到主线程再执行；`CountDownLatch` 仍在 Default 线程等待（主线程空闲，回调可送达，无死锁），超时 300ms 兜底。
- `reader-lite.js`：`init()` 里补回 `android-prev-chapter → requestChapter(-1)`、`android-next-chapter → requestChapter(1)` 绑定（与旧 reader.js 语义一致）。

#### 验证

- 全量单测 + `assembleDebug` 通过；修复版已安装真机。
- 待真机确认：悬浮底栏/目录/滚动底部按钮换章不闪退；换章后进度随退出/跳转正常落盘（多次连续换章后退出再进，恢复点应为最后阅读位置）。

### 9.45 进度“段落/页码级”保存与恢复根因修复（真机探针定位，2026-08-09）

用户反馈：进度仍不能精确到段落，翻页模式连页码都记不住，退出重进永远回章节首。本轮用 adb + 真机探针（`[probe]/[goto]/[report]` 日志 + progress.json 实盘对照）逐项定位，共五个根因：

#### 根因

1. **滚动模式采样用了文档坐标**：`caretRangeFromPoint` 需要视口坐标（0..viewH），旧代码写 `y = window.scrollY + 18 + topInset + 8`。滚动超过约 600px 后 y 就超出视口（探针实证 `scrollY=6267 → y=6401 > vh=780 → null`），保存被跳过；只有刚进书/刚换章（scrollY≈0）能采到一次章节首（offset=16），这就是“永远只记到章节首”的直接原因。
2. **分页 gotoOffset 忘加 scrollLeft**：`rect.left` 是视口坐标，容器横向滚动后内容已左移 `scrollLeft`。列公式少加 `el.scrollLeft`，导致“恢复一次后再次重排”时把锚点算到上一页，来回振荡（探针实证 `rectLeft 749/sl=0 → page2`，`rectLeft 53/sl=696 → page0`，两者其实同列）。
3. **恢复/重排事件也会保存**：`report()` 无条件 `saveProgress`，而字体/图片加载期间多列布局反复进入中间态（同一 offset 在列 0 与列 3 之间跳），中间态的临时页码被写回 progress.json，污染下一次恢复目标（`841→454→…` 恶性循环）。
4. **Kotlin `onPageChanged` 也落盘**：JS 端即使不调 `saveProgress`，`pageChanged` 到 Kotlin 仍走 `onPageTurn` 立即持久化，等于恢复路径的“后门”，继续污染。
5. **150/600ms `refresh()` 抢跑**：字体未加载完成时 `prepare()` 会把多列布局打回中间态并清零 scrollLeft，`gotoOffset` 算错页，出现“恢复瞬间闪回章首”再跳回。

#### 修复

- **滚动采样**：`y = max(8, 18 + topInset + 8)`，固定视口深度，与 `restoreScroll` 的恢复锚点（`-18-topInset-8`）严格对应，滚动到任意位置都能采到正文。
- **列公式**：`col = round((rect.left + el.scrollLeft - er.left - P) / advance)`，恢复幂等、不再振荡。
- **保存只发生在用户动作**：`report(doSave)` 拆分——用户翻页 `report(true)` 才 `saveProgress`；恢复/重排/模式切换/init 均 `report(false)` 只更新 UI。滚动模式沿用防抖保存；换章后新章落库走 `wasSwitch`（JS init 参数，由 Kotlin 判断本次加载是否换章）。
- **`pageChanged` 桥改纯 UI 事件**：去掉 offset 参数，Kotlin 只更新页码指示、不落盘；分页进度保存统一走 `saveProgress`（500ms 防抖 + 退出 flush）。
- **稳定锚点 `anchorOffset`**：只在用户翻页/滚动/切模式时更新；`onResize`/`refresh` 一律恢复到锚点，不再取“当前页顶采样”（多重重排会逐页漂移）。
- **布局稳定门 `layoutReady()`**：字体 + 全部图片就绪后才做最终定位（`tryRestoreAfterSettle`，8 秒兜底）；移除 150/600ms 抢跑刷新，改 2s 兜底。

#### 真机验证（adb 全程驱动）

- 滚动模式：`scrollY 973→1529→2083→2629` 分别采到 `390/589/349/884`，progress.json 实时更新；重进恢复到 390 所在段落。
- 分页模式：翻页 `page1..6` 分别保存 `315/454/709/841/1023/1252`；退出重进 `tracker store=param=1252`，稳定恢复到含 1252 的页（字体/图片重排后不再振荡、不再闪回章首）。
- progress.json 全程未被中间态污染；单测 91 通过 / 1 跳过；`assembleDebug` 通过。

### 9.46 换章落点修复：下一章跳到“章中间”的根因（2026-08-09）

用户反馈：在“第 21~40 楼”第 7 页点下一章，跳到“第 41~60 楼”第 11 页——下一章应从章首开始。

#### 根因

- **`onPageFinished` 闭包捕获了过期的 `initialOffset`**：`AndroidView` 的 factory 只在首次组合时创建 `WebView` 和 `WebViewClient`，闭包里的 `initialOffset` 是**打开书那一刻**的参数值。之后每次换章，Compose 虽然把 `initialOffset` 更新为 0，但 `onPageFinished` 仍用首次捕获的旧值（打开书时的恢复偏移）去初始化新章 → 下一章永远继承上一章的偏移，表现为“跳到下一章中间”且页码随偏移漂移。
- 另外发现：换章后新章“章首”位置没有真正落库——章首采样落在首楼卡片 padding 上返回 0，被 `offset > 0` 守卫跳过，progress.json 仍指向上一章；搜索跳转的 `jumpOffset` 也没有传给阅读器（`savedOffset` 只取书架进度）。

#### 修复

- `WebViewChapterView` 新增 `initialOffsetRef = rememberUpdatedState(initialOffset)`，`onPageFinished` 的 init 用 `initialOffsetRef.value`，换章后新章恢复偏移恒为 0（章首）。
- `NativeReaderScreen` 恢复锚点改为：仅 `chapterIndex == initialChapter`（打开书/搜索跳转）才用追踪器 offset；会话内换章（上一章/下一章/目录/翻页到章界）一律 0——与桌面 `nextChapter/prevChapter/TOC → loadChapter(i, 0)` 语义一致。
- JS 换章保存加兜底：采样为 0（章首 padding）时保存 offset=1，保证“已换到本章章首”落库，退出重进回到新章而非旧章。
- `AnkeShelfRoot`：`savedOffset = jumpOffset ?: savedProgress?.text_offset`，搜索跳转目标真正传入阅读器。

#### 真机验证

- 打开书（恢复历史进度）→ 点下一章 → 新章显示“第 1 / N 页”，正文为该章首楼；progress.json 更新为新章 offset=1；退出重进恢复新章第 1 页。
- 单测 91 通过 / 1 跳过；`assembleDebug` 通过。

### 9.51 滚动模式“段落记录不对”根因：书架 savedOffset 用 record 实例做 key 永不刷新（2026-08-09）

用户反馈滚动模式（尤其滚动/翻页来回切换后）段落记录不准，要求多次测试并对比 offset。连续 3 轮滚动→退出→重进对照：

#### 根因

- **`AnkeShelfRoot.savedProgress = remember(record)` 用 `record` 对象实例做 key**：书架 map 里的 `BookRecord` 是稳定实例，`refresh++` 后 `books/record` 虽重算但 record 身份不变，`remember` 不重算 → 重进阅读器时 `savedOffset` 一直是旧值（实测 store=3069，param=2963；store=3225，param=3146），进度“看起来没恢复”。
- 恢复数学本身一直是精确的（探针：`target=3562 → sampled=3562`）。

#### 修复

- `savedProgress` 改为 `remember(refresh, record?.id)`：书架每次刷新/退出都重读内存进度，param 与 store 一致。
- 顺带确认：滚动保存显式 page=-1 后，进度条目页码正确清空；dispose 查询偶尔返回 0 时由 flush（lastKnown）兜底，不影响正确性。

#### 验证（3 轮真机对照）

| 轮次 | 退出前保存 | tracker init（store / param） | 恢复 target → sampled |
| --- | --- | --- | --- |
| 1 | 3562 | 3562 / 3562 | 3562 → 3562 |
| 2 | 3354 | 3354 / 3354 | 3354 → 3362（行内几字符差，段落级） |
| 3 | 3638 | 3638 / 3638 | 3638 → 3638 |

- 单测 91 通过 / 1 跳过；`assembleDebug` 通过；诊断日志按用户要求保留。

### 9.52 滚动↔分页多轮交叉验证（2026-08-09）

按用户要求做 3 轮“滚动 → 分页 → 滚动”交叉验证，每轮完整闭环（保存→退出→重进），logcat 对照：

| 轮 | 滚动保存 A | 重进（store/param → target → sampled） | 分页重进 UI | 切回滚动保存 A2 | 重进（target → sampled） |
| --- | --- | --- | --- | --- | --- |
| 1 | 3758 | 3758/3758 → 3758 → 3758 | 第 63 / 112 页 | 4069 | 4069 → 4077（行内） |
| 2 | 4069 | 4069/4069 → 4069 → 4077（行内） | 第 65 / 112 页 | 4311 | 4311 → 4311 |
| 3 | 4493 | 4493/4493 → 4493 → 4492/4493 | 第 75 / 112 页 | 4893 | 4893 → 4893 |

- 滚动：`param == store`（9.51 修复后不再落后），恢复 target 与保存值一致，sampled 差值 ≤ 行内几字符（段落级）；
- 分页：UI 页码与保存的 0 基 page_index 精确对应（+1 显示），三轮 UI 页码 63/65/75 随内容推进连续；
- 来回切换后两条链路各自闭环，无跨模式污染。

### 9.53 进度保存功能复盘：十轮迭代的教训与方法论（2026-08-09）

#### 轮次统计（混合架构重构以来，9.43 → 9.52）

| 轮 | 内容 | 用户反馈/触发 |
| --- | --- | --- |
| 9.43 | 进度实现整体删除重写（ChapterProgressTracker） | 用户要求删掉重写，先研究桌面端与 GitHub 阅读器 |
| 9.44 | 换章闪退 + 滚动底部换章失效 | “只有第一次进入再退出能记录，换章闪退” |
| 9.45 | 五个根因：滚动采样坐标系、gotoOffset 漏 scrollLeft、保存时机、UI 桥落盘、refresh 抢跑 | “退出重进只能回章节首，分页不记页码” |
| 9.46 | 换章落点（onPageFinished 过期 initialOffset） | “下一章跳到下一章中间” |
| 9.47 | 加载遮罩 + 滚动布局稳定门 | “加载中操作回章首；滚动定位失效” |
| 9.48 | 分页三个误写入源 + 滚动中线采样 | “分页不稳定，总落后；滚动只到楼层” |
| 9.49 | 图片页采样 + 页码持久化与精确恢复 | “1546 一直不变（其实是没翻页）” |
| 9.50 | 模式隔离重构（锚点/采样/恢复分家） | “两模式隔离没做好，改一边坏一边” |
| 9.51 | savedOffset remember key + 滚动清除页码 | “滚动段落没正确记录（尤其来回切换后）” |
| 9.52 | 3 轮滚动↔分页交叉验证 | “记得最后交叉验证” |

合计：1 次整体重写 + 8 轮问题修复 + 1 轮验证；用户直接反馈“不行/没修复”共 8 次。每一个“小功能”最终涉及：JS 采样/分页几何/布局时序、桥线程与异步事件、Kotlin 记忆键、WebView 生命周期、磁盘持久化。

#### 为什么栽了这么久

1. **进度保存横跨 JS 渲染层与 Kotlin 持久层，边界多、时序多**：桥事件异步、字体/图片布局异步、销毁/换章/后台生命周期异步。任何一处“迟到/中途”事件都可能写错值。最初按桌面端同步模型设计，严重低估了移动端异步时序。
2. **“能写入”≠“写对”**：早期验证只看 progress.json 有内容，没对照“用户实际位置”。1546 卡住就是典型的“每次都在写，但永远写旧锚点”。
3. **采样点依赖渲染布局**：`caretRangeFromPoint` 命中图片/空白会返回邻近文本；字体/图片未稳定时同一 offset 映射到不同页面。文本锚点本身正确，但“采样时机 + 命中元素校验”缺一不可。
4. **共享状态与共享函数跨模式耦合**：一个 `anchorOffset` 两种语义、一个函数里 `if (paged)` 分支——第 9.50 才彻底分家，太晚；此前所有“改一边坏另一边”都源于此。
5. **假验证**：单测 + assembleDebug 通过不代表端到端正确。Compose `remember(record)` 用对象身份做 key 导致 savedOffset 永不刷新，这类问题不实测完全看不见（9.51）。
6. **没有一开始建立可重复的闭环测试**：滚动到 X → 退出 → 重进 → 必须回到 X。前期靠推理猜，后期靠 adb 探针 + 视觉模型对照才收敛——如果第一天就有这个回归脚本，至少能省一半轮次。

#### 沉淀到项目的方法论

- **进度端到端回归脚本**（adb）：滚动/翻页 → 记录保存值 → 退出 → 重进 → 对比 offset/页码；以后每次涉及阅读器的改动必跑，不允许只靠“打开看一眼”。
- **模式隔离清单**：分页/滚动的锚点、采样、恢复、保存各自独立，禁止共享字段；事件处理器入口必须有模式守卫。
- **所有异步写入可溯源**：保存日志带来源标签（flip/scroll/switch/dispose/init/settle），谁写的一查便知；正式发行前保留。
- **Compose 记忆键纪律**：进度类缓存只用“会随数据变化而变化的键”（refresh/数据 id），禁止用“看似稳定实则永不变化”的对象身份。
- **验收前自查清单**：同一位置连续重进 3 次必须一致；切换模式后再重进必须一致；杀进程重开必须一致；图片页/超大章必须覆盖。

### 9.54 滚动“图片停在屏幕中部退出回退”根因：settle 链缺 userMoved 守卫（2026-08-09）

用户反馈：滚动阅读时如果图片停在屏幕中间的采样位置，退出后不保存进度、自动回退到上一次进度。

#### 根因（探针实证）

- 复现日志：5 次滚动已保存 1288，随后 `progress.set off=872`（打开时的初始值）把新进度覆盖；`dispose query o=0`（屏幕中部是图片，采样 null）。
- 真正的元凶不是“图片挡采样”，而是 **`tryRestoreAfterSettle` 没有 `userMoved` 守卫**：字体/图片加载慢时，settle 链一直用启动时捕获的初始 offset 重试，用户滚动后仍执行 `restoreScrollOffset(初始值)` + 重采样保存 → 把刚滚到的位置拉回并覆盖。
- 连带缺陷：滚动 debounce **漏设 `userMoved`**（只有 flipPage 设置），settle 链无从知道用户已滚动。

#### 修复

- `tryRestoreAfterSettle` 回调开头：`if (state.userMoved) { markSettled(); return; }` —— 用户一旦滚动/翻页，settle 链只标记就绪，绝不再恢复/保存。
- 滚动 debounce 设置 `state.userMoved = true`。
- 附带增强：滚动采样扫描范围从“中线下方 120px”扩到整页；全屏图片（扫描仍无文本）时用滚动比例兜底，图片区退出/防抖也能保存近似位置。

#### 验证

- 滚动保存 1442 → 退出 → progress 保持 1442（不再回退）→ 重进 `param=store=1442`，restore target=1442，sampled=1441/1442（行内）。
- 教训（再次踩坑）：中途一次“修复后仍复现”实为旧 APK——`mergeDebugAssets` 被 Gradle 误判 UP-TO-DATE，JS 改动没进包；此后改 JS 一律 `unzip` 校验 APK 内脚本内容再判断。
- 单测 91 通过 / 1 跳过；`assembleDebug` 通过。

### 9.55 桌面端 vs 安卓端：为什么进度保存只在移动端连环翻车（2026-08-09）

用户提问：同样的 text_offset 方案，为什么桌面端几乎没遇到严重问题，安卓端却八轮修复。逐层对比后结论：**不是桌面端代码更好，而是两端环境在五个维度上根本不同，而我们按桌面端模型低估了移动端复杂度**。

#### 五个维度差异

1. **布局确定性**：桌面端系统字体即装即定、图片走本地 HTTP、iframe 一次排版后基本不变；安卓端 26MB 自定义字体异步加载 + NGA 在线图渐进加载 + WebView 复用，同一 text_offset 在字体/图片就绪前后映射到不同 DOM 位置，“中间态布局”长期存在。9.45 布局稳定门、9.47 加载遮罩都在对抗这个移动端特有维度。
2. **坐标系/宿主语义**：`caretRangeFromPoint` 需要视口坐标——桌面 iframe 全高文档使 `scrollTop+8` 天然有效，安卓 WebView 视口只有可见区域，`scrollY+134` 直接超界返回 null；分页 `rect.left+scrollLeft` 同理（body scroller vs div scroller 行为不同）。
3. **桥的同步性**：桌面 pywebview `Bridge.call` 同步——JS 调用即 Python 写盘，事件即保存，无中间状态；安卓 JS→JavaBridge 后台线程→主线程→tracker（防抖/去重/按章内存 map）→异步 IO，时序与线程问题按数量级上升。
4. **生命周期**：桌面端关窗即进程结束，保存时机唯一；安卓端换章/返回/Home/杀进程/WebView 延迟销毁都是写入时机，也都是写错时机（pagehide、dispose 查询、settle 回拉均源于此）。
5. **验证反馈**：桌面改完即测、bug 当场暴露；安卓每轮装 APK + adb + 截图确认，反馈慢、问题积压多轮才暴露。

#### 反省

- 移动端真正的问题不是“环境差”，而是**我们把同步模型套到异步环境，并为性能/体验引入缓存层（防抖、去重、内存 map）放大了复杂度**；桌面端零缓存反而可靠。
- 方法论：跨层状态（JS 渲染层 ↔ Kotlin 持久层）先写“谁在什么时机写、谁能覆盖谁”的写入清单，再动代码；第一天就该有“滚动/翻页 → 退出 → 重进 → 必须一致”的回归脚本。

### 9.56 热更新可选参数：对齐桌面“更新帖子”面板（2026-08-09）

用户反馈：热更新时无法像桌面端那样可选格式。

#### 桌面端对照

- v1.1.0 起桌面“更新帖子”面板（`web/js/nga_download.js` buildUpdateSection）可选：只看楼主 uid、主题（浅/深）、图片模式（在线/嵌入/不含）、每章楼层数、目录楼 pid；默认回填上次设置（`nga_update_defaults`），仅对新增楼层生效。

#### 安卓端差距

- “已下载”页更新按钮此前直接启动（只传 bookId/tid），底层虽会回填最近设置，但用户无法在更新时临时选择格式参数。

#### 实现

- 抽取公共组件 `ui/components/NgaUpdateDialog.kt`（`NgaUpdateDialog` + `launchNgaUpdate`），**书架封面/列表与“已下载”页共用**，消除重复。
- 已下载页与书架页（列表行、网格封面）的更新按钮统一改为弹出参数对话框：预填 `NgaDownloader.defaultsFor`（最近一次下载/更新设置），字段 = 只看楼主 uid、主题（浅/深 FilterChip）、图片模式（在线/内嵌/无图 FilterChip）、每章楼层数；附说明“仅对本次新增楼层生效”。
- 确认后 `launchNgaUpdate` 携带 authorId/theme/perChapter/imageMode 启动前台服务；服务端优先使用传入参数，未传才回填（原有逻辑兼容）。
- UI 规范核对（docs/ANDROID_DESIGN_TOKENS.md）：对话框 `AnkeRadius.large`(16dp)、输入框 medium(12dp)、按钮 small(8dp)、FilterChip pill、间距 AnkeSpacing、颜色全部走 colorScheme；顺手把同文件“删除书籍”对话框统一为 large。

#### 验证

- 单测 91 通过 / 1 跳过；`assembleDebug` 通过；已装真机。待真机确认：更新对话框预填值正确、修改参数后更新仅影响新增楼层。

### 9.47 换章加载遮罩 + 滚动模式章内定位稳定性（2026-08-09）

用户反馈两点：跳转/排版未完成时操作会把位置拉回章节首；滚动模式章内定位又失效、默认回章节首。

#### 根因

- **加载未完成时操作**：换章瞬间旧页已替换、新页尚未恢复完成，此时触摸/翻页会对中间态布局执行 `flipPage`/保存，把位置和进度一起拉回章首。
- **滚动模式恢复不受布局稳定门保护**：9.45 的 `layoutReady()` 只对分页生效（滚动模式图片懒加载，等图片会把恢复拖到 8 秒兜底）；滚动恢复发生在系统字体布局下，LXGW 字体加载后文字重排，恢复锚点漂移；加上用户在未稳定时滚动会提前保存章首偏移，下次重开自然回章首。

#### 修复

- **加载遮罩（屏蔽视野 + 触摸）**：`WebViewChapterView` 在 `!pageReady || !settled` 时覆盖整块阅读区——主题色纯色底 + 居中“加载中…”文字，`pointerInput` 消费全部触摸事件；JS 侧新增 `onSettled` 桥（恢复完成 + 字体/图片就绪后回调一次），Kotlin 侧 5 秒兜底强制放行。换章/重进/模式切换全程看不到中间布局、也不会误触。
- **`layoutReady()` 按模式拆分**：分页等字体+图片；滚动只等字体（图片懒加载不能阻塞），滚动恢复同样纳入 `tryRestoreAfterSettle`，字体就绪后按锚点重新 `restoreScroll`，不再漂移。
- **`markSettled()` 幂等**：finish / 滚动 setTimeout / settle 链 / refresh 兜底都会标记，确保遮罩在正确时机撤下。

#### 真机验证（adb + 截图 + 视觉模型）

- 换章瞬间截图：纯色遮罩 + “加载中…”，顶/底控制条保留；6 秒后遮罩消失，新章“第 101~120 楼”从 101 楼开始，progress.json 落为 chapter 6 offset 16。
- 滚动模式：滚动后保存 offset 895；退出重进恢复显示 82 楼中间内容（非章首），progress.json 未被污染。
- 单测 91 通过 / 1 跳过；`assembleDebug` 通过。

### 9.48 分页进度“滞后/不确定”真凶与修复 + 滚动段落精度（2026-08-09）

用户反馈：分页模式一段时间内只能回到同一个进度、下一次又完全不确定，像缓存赶不上操作；滚动模式正常但精度只到楼层。

#### 分页不稳定的三个写入源（逐一定位）

1. **分页模式下 window 滚动监听器误存**：字体/图片重排引发 `window` 滚动事件，滚动保存监听器没区分模式，把“当前页顶采样”当成滚动进度存盘，覆盖翻页保存的正确偏移（实测 1971 被覆盖成 1396）。
2. **退出时 dispose 查询用页顶采样覆盖锚点**：恢复锚点（如 1546）落在页内，页面重排后“当前页顶采样”是更靠前的值（1396）；每次退出都查询并覆盖，等于每次重进+退出都把进度往回拉一段——这就是“一段时间只回到同一个进度、之后又完全不确定”的直接原因。
3. **pagehide 兜底保存写错值**：WebView 销毁时 scrollLeft/滚动位置被重置，迟到的 pagehide 会用错误 offset 覆盖刚 flush 的正确进度。

#### 修复

- 滚动监听器加 `if (state.paged) return`：分页模式保存只走翻页事件。
- 翻页/换章改用新增桥 `saveProgressNow` → `tracker.onPageTurn` 立即落盘（不再等 500ms 防抖），退出/杀进程也不落后。
- 移除 pagehide 兜底保存；退出保存统一由 Kotlin dispose flush 完成。
- dispose 查询分页模式返回 -1（Kotlin 忽略），直接 flush 已保存锚点；滚动模式保留即时查询（防抖窗口内的新鲜位置）。
- `ChapterProgressTracker.close()`：屏幕销毁后 400ms 延迟关闭，阻止销毁期间迟到事件覆盖正确进度。
- 滚动精度：采样点从“顶部 18+topInset+8”改为**视口中线（45%）+ 向下逐行扫描**，恢复锚点同步到中线；实测从“楼层级”提升到“段落级”（恢复停在“祥子的回答”具体骰子段落，而非楼头）。

#### 验证

- 翻 2 页 → 退出 → progress=1546；重进 12 秒不动 → 退出 → progress 仍 1546（updated_at 不变），不再被改写。
- 滚动模式恢复停在具体段落；分页换章/退出/重进全程 progress.json 未被中间态污染。
- 单测 91 通过 / 1 跳过；`assembleDebug` 通过。

#### 说明

- 按用户要求，诊断日志（JS `[save:flip/scroll/switch]`、`progress.set`）在正式发行版前保留，方便定位问题；发行前再统一移除。

### 9.49 分页进度“卡在旧值”根因（图片页采样）+ 页码随进度保存与精确恢复（2026-08-09）

用户反馈 9.48 未修复；真机深探针证实：**翻页本身正常（scrollLeft 4176→5220、页码 12→15），但连续多页的采样点 (x=42,y=116) 都命中 `IMG.nga-img`**——NGA 大图跨列时 `caretRangeFromPoint` 对图片返回“邻近文本”，每页都返回同一旧锚点 1546，保存自然永远不变。

#### 修复

- **采样跳过图片**：`offsetAtPoint` 改为整页向下扫描（分页）或采样点下方 120px（滚动），命中 `img/video/audio/svg/canvas/picture` 直接跳过，取第一个真正的文本行；翻页保存从此逐页前进（实测 page13/14/15 → offset 1574/1587/1599）。
- **页码随进度持久化**：`ProgressEntry` 新增 `page_index/page_total`（默认 -1，旧数据兼容），翻页 `saveProgressNow` 携带页码，tracker 按章记录并落盘。
- **恢复优先“页码一致直接翻页”**：同章同设置且布局稳定后 `total` 一致时 `gotoPage(savedPage)`，整页图片的页面也能精确恢复；`total` 不一致（布局/图片加载状态变了）再回退 `gotoOffset` 文本锚点。
- **恢复期重排不再覆盖保存页**：`onResize`/`refresh` 在用户未交互前优先 `restoreToSavedPage()`，交互后（`userMoved`）才按锚点定位；`flipPage` 锚点只在采样有效时更新。

#### 真机验证

- 翻 3 页：progress.json 依次 page=13/14/15，offset 1574→1587→1599；退出重进最终稳定 gotoPage(15)。
- 已知剩余边缘：个别场景恢复页与保存页相差 ±1 页（晚到重排/空白页跳过判定），幅度已从“落后很多”收敛到一页内，继续跟踪。
- 单测 91 通过 / 1 跳过；`assembleDebug` 通过。诊断日志按用户要求保留。

### 9.50 阅读模式隔离重构：分页/滚动采样与恢复彻底分家（2026-08-09）

用户指出两种翻页模式代码没有做好隔离，之前反复出现“改一边坏另一边”。逐函数审查 `reader-lite.js` 后确认：

#### 审查发现的耦合点

1. **`anchorOffset` 单一字段被两模式共用**：分页语义=页顶采样、滚动语义=视口中线采样，命名与使用都含糊，任何一处更新都会影响另一模式的恢复决策。
2. **`currentOffset/offsetAtPoint/gotoOffset` 都在函数内部 `if (state.paged)` 分支**：语义交错，历史 bug（滚动采样用了文档坐标、分页 gotoOffset 漏加 scrollLeft）都源于这种写法。
3. **Kotlin 触摸分区用“设置值”判断模式**：JS 侧超大章会静默回退滚动（`state.paged=false`），但 Kotlin 仍按设置值做左/右翻页分区，模式不一致时触摸行为错乱。

#### 隔离重构

- **锚点拆分**：`pagedAnchor + pagedAnchorPage + pagedAnchorTotal`（分页）与 `scrollAnchor`（滚动）完全独立；`setMode` 切换时用 text_offset 交接并同时更新两套锚点。
- **采样拆分**：`currentOffsetPaged()`（页顶+整页扫描）与 `currentOffsetScroll()`（视口中线+120px 扫描），统一走 `scanForText`（跳过图片）；`currentOffset()` 只做分发。
- **恢复拆分**：`restorePagedAnchor(offset)`（页码一致直接翻页，否则按 offset 定位）与 `restoreScrollOffset(offset)`（中线锚点）；`gotoOffset` 变回纯分页定位器。
- **实际模式回传**：JS 在 `init/setMode` 时 `AnkeReaderBridge.onMode(state.paged)`，Kotlin 触摸分区改用 JS 实际模式。
- 事件处理器入口守卫复查：`flipPage/report/onResize/prepare/measure/gotoPage` 仅分页可用；滚动保存仅滚动模式；`restorePagedAnchor` 内部自守卫。

#### 真机验证（隔离后的两条链路各自闭环）

- 滚动：滚动保存 offset 109→278（page 保持 -1）→ 重进恢复章节中间段落，无遮罩；
- 滚动→分页交接 → 翻 3 页保存 page_index=3 → 退出重进/杀进程重开均显示“第 4 / 112 页”（**progress.json 存 0 基 `m.current`，UI 显示 1 基，3 存 4 显为精确一致**，此前报告的“±1 页”是读数口径错误，非缺陷）；
- 分页→滚动交接按 text_offset 继续。
- 单测 91 通过 / 1 跳过；`assembleDebug` 通过。

### 9.57 提交前全面架构梳理与精简（2026-08-09）

用户要求：正式提交前做一次大规模全面架构分析和梳理，然后精简优化；构建出的 APK 先不要装手机。

#### 盘点结论：阅读器存在三套历史实现

逐文件核对引用后确认，仓库里同时存在三条阅读渲染路径，只有一条在跑：

1. **当前主线（使用中）**：`WebViewChapterView.kt`（WebView 渲染内核）+ `NativeReaderScreen.kt`（Compose 外壳）+ `assets/reader/reader-lite.js`（36KB 精简桥）+ `ReaderHtml.kt`。
2. **旧 Kotlin 原生渲染器（死代码）**：`ui/reader/native/NativeChapterView.kt`（1501 行）整文件无任何外部引用，只被自身测试间接覆盖。
3. **旧 WebView 完整阅读器（死代码）**：`ReaderScreen.kt`（1121 行）+ `ReaderBridge.kt` + `ReaderBottomBar.kt`，`AnkeShelfRoot` 只 import 新版 `NativeReaderScreen`，旧三件套无引用；配套 `ReaderModel.kt`（533 行）仅被 `ReaderModelTest.kt` 引用。

另发现两个结构性隐患：

- **Kotlin/JS 分页几何漂移**：Kotlin `PagedLayout` 用“左 padding=margin、右 padding=gap/3”的非对称公式；而 JS 两侧（旧 reader.js 与现役 reader-lite.js）都用左右对称 `P=min(margin, gap-8)`。跨端对照测试 `ReaderPagedCrossTest` 却加载已退役的 `reader.js` 测 `PagedMath`——测试保护的实现不是当前渲染内核，且与 Kotlin 算法不一致（若运行必然失败）。
- **跨端测试与现役 JS 脱节**：`reader.js`（41KB）仅被该对照测试引用，主代码从 `ReaderHtml.kt` 起已全部切到 `reader-lite.js`。

#### 精简与修复

- **归档删除死代码**（先移入 `.local/archive/20260809-dead-code/` 再删，可恢复）：`NativeChapterView.kt`、`ReaderScreen.kt`、`ReaderBridge.kt`、`ReaderBottomBar.kt`、`ReaderModel.kt`、`ReaderModelTest.kt`、`assets/reader/reader.js`。净删除约 4748 行。
- **统一分页几何**：`PagedLayout.geometry` 改为与 reader-lite.js 完全一致的左右对称 `P=min(margin, gap-8)` 公式，并同步更新 `PagedLayoutTest` 期望值。
- **跨端测试改测现役 JS**：`ReaderPagedCrossTest` 改加载 `reader-lite.js`；`reader-lite.js` 的 `geometry(fw,fh,s)` 支持外部注入参数、返回值补 `contentWidth`，并导出 `geometry/shouldAutoDual/buildText` 供 Kotlin 侧对照调用。
- **移除未使用依赖**：`androidx-navigation-compose`（路由实际是自管理 `rememberSaveable`，无 NavHost）、`ui-tooling-preview`（无任何 `@Preview`）。
- **清理编译警告**：`NativeBook.kt` 两处 Json 配置加 `@OptIn(ExperimentalSerializationApi)`；Search/Stats 的 `menuAnchor()` 改为新 API（`ExposedDropdownMenuAnchorType.PrimaryNotEditable`）；Settings 删掉对非空字段的多余 Elvis；两处测试 `classLoader` 加 `!!`。
- **过时注释统一**：ReaderHtml/NativeReaderScreen/AnkeShelfRoot 中“reader.js”“替代 WebView 渲染”“五页路由”等描述改为当前架构（WebView 内核 + Compose 外壳 + 四 Tab）。

#### 验证

- 单测 **77 通过 / 1 跳过**（`assembleDebug` 通过）。测试数比 9.50 的“91 过/1 跳”少，差额来自删除死代码配套的 `ReaderModelTest`（9 个用例，测的 `ReaderModel` 已确认无引用），非回归。
- 校验 APK 内 `assets/reader/reader-lite.js`：含 `geometry/shouldAutoDual/buildText` 导出与 `contentWidth`，无旧 `reader.js` 引用（Gradle 资产增量正常，无 UP-TO-DATE 误判）。
- 按用户要求 APK 未安装到手机；产物位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

#### 当前架构一条主线

Compose 外壳（书架/下载/搜索/设置/统计 + 阅读页 UI）→ `WebViewChapterView` 渲染内核（reader-lite.js：分页/滚动/楼层样式/text_offset）→ `ChapterProgressTracker` 进度落盘；公共组件（NgaUpdateDialog/ActionIcon/BookManagementOverlay）供书架与已下载页复用；Kotlin `PagedLayout` 作为 JS 几何的对照参考实现，由跨端测试持续保护。

### 9.58 滚动“整页都是图片”进度失效修复 + 滚动比例锚点（2026-08-09）

用户反馈：滚动阅读时如果当前屏幕都是图片，进度保持会直接失效；要求如实说明“退出时应尽量让文字保持在屏幕正中间（即进度采样位置）”，重写使用指南，并强调与分页模式做好隔离、先参考开发日志。

#### 日志对照（避免重蹈覆辙）

- 9.43：曾删除比例回退，只信 DOM 采样 offset；
- 9.54：图片停在屏幕中部退出回退时，加了“保存端”滚动比例兜底，但**恢复端仍是文本锚点定位**；
- 本轮真凶：保存的是“文本比例”`round(ratio * len)`，恢复却按文本点定位——图片占据的高度破坏“文本比例↔滚动比例”的线性映射，全屏图片页退出后重进必然错位，感知为“进度失效”。

#### 修复：滚动比例成为一等锚点（滚动模式专属）

- `ProgressEntry` 新增 `scroll_ratio: Double = -1.0`（-1 = text_offset 文本锚点；0..1 = 全图页滚动比例；旧数据/桌面数据缺省 -1，向后兼容）。
- JS `reader-lite.js`：
  - `state.scrollRatio` 只在 `currentOffsetScroll()` 维护：采样到文本 → -1；全屏无文本 → 实际滚动比例；
  - 新增 `AnkeReader.currentScrollState()`，一次返回 `{o, r, p}`（offset / ratio / 实际模式），供 Kotlin 换章与退出查询，避免两次 evaluateJavascript 之间状态漂移；
  - `restoreScrollOffset(offset, ratio)`：ratio∈[0,1] 时按滚动比例恢复，否则按文本锚点定位；恢复链路（init/settle/refresh/finish）统一携带 `restoreRatio`；
  - 分页保存显式 `ratio=-1`（翻页 `saveProgressNow`、换章落库），分页路径绝不读写滚动比例。
- Kotlin：
  - `ChapterProgressTracker` 新增 `lastRatio / restoreRatioFor`，保存去重改为 “offset 与 ratio 都相同才跳过”（offset 相同但比例不同必须重新落盘）；
  - `WebViewChapterView` 换章捕获与 dispose 查询改用 `currentScrollState()`：分页（p=true）时 dispose 忽略 o（保留 9.48 的 flush 语义），滚动（p=false）才采用 o/r；
  - `WebViewChapterView` 桥 `saveProgress/saveProgressNow` 增加第 6 参 ratio，JS 全部调用点已统一 6 参（避免 JSInterface 缺参异常）。
- 模式隔离清单（对照 9.50/9.53）：滚动比例字段只有滚动模式读写；分页模式永远传 -1；分页恢复（restorePagedAnchor/gotoOffset）不碰 ratio；`setMode` 从分页切滚动用文本锚点交接（ratio=-1），不用持久化的 restoreRatio；`page_index/page_total` 仍只属于分页。

#### 使用指南重写（assets/guide/usage.txt）

- 新增“四、进度保存（请务必了解）”章节，如实说明：分页按页码+段落记录；滚动以屏幕正中间的正文文字为锚点，退出前尽量让文字停在屏幕中间；当前屏全是图片时按滚动比例近似记录，重进会回到附近区域而非章节开头；加载未稳定时退出可能保留上一次位置。
- 同步更新图片长按放大/保存、下载参数、返回键、主题等章节描述。

#### 验证

- 单测 **81 通过 / 1 跳过**（新增：tracker 滚动比例保存/恢复、offset 相同 ratio 不同必须落盘、比例清除；ProgressStore ratio 往返与 clamp）。
- `assembleDebug` + `compileDebugAndroidTestKotlin` 通过；解包校验 APK：reader-lite.js 含 `currentScrollState` 导出、滚动 6 参保存、分页显式 -1；usage.txt 含“屏幕正中间 / 滚动比例”说明。
- 未安装手机；待真机回归：全屏图片页滚动 → 退出 → 重进位置一致性；图片页 ↔ 文本页 ↔ 分页模式交叉切换；连续重进 3 次一致。

### 9.59 滚动条未消失时唤出浮动栏被强制收起（2026-08-09）

用户反馈：滚动模式右侧滚动条还在显示时，点屏幕中间唤出顶/底浮动栏，会被强制收起。

#### 根因

- 收起逻辑放在 `onProgress`（滚动 500ms 防抖保存回调）里：`!pagination && barsHeld → 收起`。
- 防抖定时器由“唤出前的滚动”启动，用户停止滚动后立刻唤出时，定时器尚未到期；唤出（barsHeld=true）后防抖回调才到达，把刚唤出的控制条误收。
- 滚动条仍在显示恰好是“滚动刚停止、防抖未触发”的窗口，与现象吻合。

#### 修复（滚动发生即通知，而不是防抖保存时判定）

- JS `reader-lite.js`：滚动事件（滚动模式，250ms 节流）即时调用新桥 `AnkeReaderBridge.onScrollMoved()`；防抖保存只负责进度，不再承担 UI 收起职责。
- Kotlin：`WebViewReaderCallbacks` 新增 `onScrollMoved`；`NativeReaderScreen` 中“唤出后发生新滚动才自动收起”（`!pagination && barsHeld → 收起”），删除 `onProgress` 里的旧收起逻辑。
- 语义恢复为“点中间唤出默认保持，滚动/翻页一段距离后自动收起”：唤出前的迟到滚动事件不会再误收控制条；唤出后真正开始滚动仍会即时收起（250ms 节流，性能无感）。

#### 验证

- 单测全绿、`assembleDebug` 通过；解包校验 APK 内 JS 含 `onScrollMoved` 与 250ms 节流。
- 已安装真机；待确认：滚动停止后滚动条显示期间唤出浮动栏保持不收起；唤出后滚动立即收起；分页模式不受影响（JS 侧滚动事件分页直接 return）。

### 9.60 项目文件夹整理（2026-08-09）

用户要求整理项目文件夹。盘点后处理如下（全部可恢复/归档，未动任何源码与跟踪文件）：

- **JVM 崩溃日志**（根目录 `hs_err_pid14100.log`/`replay_pid14100.log`、`android/` 下 `hs_err_pid7772.log`/`replay_pid7772.log`，共约 12MB）→ 移入 `.local/archive/crash-logs/`。
- **调试截图**（`android/screenshots/`，35.8MB，此前为未跟踪目录）→ 移入 `.local/archive/screenshots/`，`git status` 不再显示该未跟踪目录。
- **测试书籍**（`android/` 根目录的 MYGO/HBR 导出 EPUB，6.2MB）→ 移入 `.local/archive/test-books/`（保留测试素材，不再占用 android 根目录；`.gitignore` 的 `android/*.epub` 规则保留以防将来再放）。
- **重复压缩包** `.local/flow-ref.zip`（与已解压的 `.local/flow-ref/flow-main/` 内容重复，可从 GitHub 重新获取）→ 删除。

未动：`dist/`（发布产物 AnkeShelf-v1.2.0.zip）、`build/`（PyInstaller 中间产物）、`.tools/`（Android SDK 等工具链）、`.local/` 其余参考源码（flow/legado 对照参考）、`backup/`（APK 备份）。如需进一步释放空间，可再评估删除 `build/` 与历史构建归档 `.local/archive/build/`。

### 9.61 安卓 v1.0.0 发布准备（2026-08-09）

用户要求：确定版本号、重写整个 README、准备发布，并提供 9 张实机演示截图。

#### 版本号

- 按 `android/VERSIONING.md` 既定版本线，首个 Release 直接 **`android-v1.0.0`**：
  `versionCode=1`、`versionName=1.0.0`（`build.gradle.kts`，唯一版本定义位置）。

#### 实机截图与 README 重写

- 9 张实机截图经视觉模型逐张识别归档（书架 / NGA 下载入口 / 下载参数 / 更新弹窗 /
  全文检索 / 设置 / 阅读深色 / 阅读浅色 / NGA 公告帖），复制到
  `docs/screenshots/android/`（两张阅读页大图压缩为 JPG，其余 PNG）。
- `README.md` 整体重写为双端结构：平台与版本一览（Windows v1.2.0 / Android v1.0.0）、
  分平台特性、双端截图、安装与 Cookie 配置、测试、架构、版本与发布、致谢与许可证；
  保留原有桌面端全部信息与开源致谢。

#### 签名与 Release 构建

- 首次生成正式签名密钥 `android/keystore/ankeshelf-release.jks`（RSA 2048，
  有效期 3650 天），密码写入 `android/keystore.properties`（两者均不入库，
  `.gitignore` 已忽略）；`build.gradle.kts` 启用 release `signingConfig`。
- `assembleRelease`（R8 minify + shrinkResources + lintVital）构建成功：
  `app-release.apk` 16,393,764 字节，`versionName=1.0.0`、`versionCode=1`、
  minSdk 26 / targetSdk 36、应用名「安科书架」。
- 发布安全检查 `check-release.ps1`：**PASS**（无凭据 / config.ini / keystore /
  local.properties 泄漏）；SHA256
  `3F1B212FD6CD966B4FF47599A782C155468F2E31B216CAA5A4961028C4FF7475`。
- 资产按 SOP 命名复制为 `dist/AnkeShelf-v1.0.0-android.apk`。

#### 待用户授权步骤（未执行）

- `git push`（当前分支 `android/m1-data-layer` 未推送）与打标签 `android-v1.0.0`；
- `gh release create android-v1.0.0 ...`（本机 gh CLI 配置访问受限，需先修复或改用
  REST API 直连）；Release 标题「安科书架 Android v1.0.0」。

### 9.62 安卓 v1.0.0 发布完成（2026-08-09）

- **gh 修复**：沙箱权限导致 `gh` 读不到配置；提权后确认 gh 2.97.0 已登录
  `gighi-947`。推送因 token 缺少 `workflow` scope 被拒，`gh auth refresh -s workflow`
  设备码授权完成（浏览器未自动弹出，用户手动打开 `github.com/login/device`
  输入一次性代码；oauth 轮询曾因网络抖动超时一次，重试成功）。
- **网络排查**：桌面端当时就是直连 `git push origin main --tags`（DevLog 5.3 SOP），
  无代理；本次直连间歇性被重置（ls-remote 可过、push 偶发 `Recv failure`），
  重试后成功，未引入代理。
- **推送**：`android/m1-data-layer` 分支 + `android-backup-20260809`、
  `android-v1.0.0` 标签已推送 GitHub。
- **Release**：`gh release create android-v1.0.0` 成功，
  标题「安科书架 Android v1.0.0」，说明文件 UTF-8 直读（无 PowerShell 管道转码），
  资产 `AnkeShelf-v1.0.0-android.apk`（16,393,764 字节）经 REST API 核验与本地一致。
- Release 地址：https://github.com/gighi-947/anke-shelf/releases/tag/android-v1.0.0

### 9.63 GitHub Actions Android CI 修复（2026-08-09）

GitHub 邮件通知 Android CI 在 main 上失败，共修三轮：

1. **`./gradlew: Permission denied`**：Windows 下 `core.filemode=false`，gradlew 可执行位未入库；
   `git update-index --chmod=+x android/gradlew` 修复。
2. **阿里云镜像 502**：settings.gradle.kts 镜像优先，海外 runner 访问 `maven.aliyun.com` 返回 502；
   按 `GITHUB_ACTIONS` 环境变量切换：CI 直接用 google()/mavenCentral()/gradlePluginPortal()，
   本机（中国大陆）保持阿里云优先。踩坑：`pluginManagement` 闭包内不可见脚本级 val，
   需在块内直接 `System.getenv(...)` 判断。
3. **`Cannot convert 'null' to File`**：`keystore.properties` 不入库，CI 上缺失时
   `rootProject.file(null)` 配置崩溃；release signingConfig 改为缺文件时留空
   （debug 构建不受影响，本地无签名文件验证通过）。

顺手升级 `actions/setup-java` v4 → v5（v4 已弃用）。GitHub 直连间歇性断连
（curl/git 均超时），等待约 1 分钟后恢复，重试推送成功。

最终：main 与 android/m1-data-layer 两个分支的 Android CI 均 **success**
（单测 + assembleDebug + 上传 debug 产物）。

### 9.64 Harness Engineering 落地：规则入口 / 数据契约 / 纪律测试（2026-08-09）

参照《From Vibe Coding to Harness Engineering》复盘，按“规则入口 → 数据契约 →
纪律测试 → 测试防假绿审计”落地：

#### AGENTS.md（开发规则入口）

- 新增根目录 [AGENTS.md](AGENTS.md)：进场先读页，汇总双端边界、提交纪律、
  进度保持铁律（模式隔离/写入清单/采样语义/回归必测）、UI 令牌、数据契约、
  测试纪律与常用命令，避免新会话翻 1400 行日志才找到约束。

#### docs/DATA_CONTRACT.md（双端数据契约）

- 新增 [DATA_CONTRACT.md](docs/DATA_CONTRACT.md)：shelf / progress / settings /
  annotations / statistics / 原生书 meta+floors+chapters 全字段表；
  明确 `page_index/page_total/scroll_ratio` 为安卓扩展（缺省 -1）与兼容规则；
  新增字段四步流程（向后兼容默认值 + 更新文档 + 对端忽略未知字段 + 坐标一致）。

#### DisciplineTest.kt（纪律测试，JVM 单测 5 条）

- **UI 令牌**：非 theme 组件禁止 `RoundedCornerShape` ≥8dp（必须走 AnkeRadius）；
  padding/spacedBy 禁止魔法间距（必须走 AnkeSpacing）。
- **阅读器模式隔离**：分页 `saveProgressNow` 必须显式 `ratio=-1`；滚动防抖保存
  必须携带 `state.scrollRatio`；reader-lite.js 必须保留跨端对照导出
  （currentScrollState/geometry/shouldAutoDual/buildText）。
- **CI 配置**：android.yml 仅 `android/**` 触发、不得用弃用 @v4 动作、
  必须 setup-java@v5 与 JS 语法检查。
- **数据契约**：ProgressEntry 扩展字段缺省 -1/-1.0 防回归。
- 首跑抓出两处真实违规并修正：SettingsScreen 颜色选择器 `padding(horizontal=20.dp)`
  → `AnkeSpacing.xl`；色板预览 `spacedBy(3.dp)` → `AnkeSpacing.xs`。

#### CI 强化与测试审计

- `actions/checkout` / `upload-artifact` 升 v5（避开 Node20 弃用）；新增
  `node --check` 校验 reader-lite.js 语法。
- 审计现有 19 个测试文件：全部含有效断言（6~43 个/文件），无空断言；仅
  NgaClientTest 1 条网络用例跳过（有明确原因）；ReaderPagedCrossTest 在
  androidTest（需模拟器），CI 以 JVM 纪律测试 + node --check 兜底 JS 侧。
- 本地单测 86 通过 / 1 跳过；`assembleDebug` 通过；待 CI 绿后提交完成。

### 9.65 借鉴 Karpathy 准则与代码库地图（2026-08-09）

用户提供两个参考仓库：
[Understand-Anything](https://github.com/Egonex-AI/Understand-Anything)
（代码库知识图谱 / diff 影响分析 / onboarding 导览）与
[andrej-karpathy-skills](https://github.com/multica-ai/andrej-karpathy-skills)
（Think First / Simplicity / Surgical / Goal-Driven 四原则）。

#### 落地内容

- **AGENTS.md 新增“5. 工作方式”**：Karpathy 四原则 + Diff 影响检查。
  - 先想后写：显式假设、困惑就提问（对照 9.53 进度十轮教训）；
  - 简单优先：不建一次性抽象，删死代码先确认引用（对照 9.57）；
  - 外科手术式改动：只动任务涉及的代码，不顺手改相邻内容，
    双端共享文件（README/docs/DevLog/契约）尤其严格；
  - 目标驱动：任务先写成功标准 + 验证方式；
  - Diff 影响检查：动共享文件或契约字段时列出受影响端逐项核对。
- **新增 docs/CODEBASE_MAP.md**：双端核心链路阅读地图——应用启动、
  NGA 下载/热更新、书架与进度、EPUB 解析导出、阅读渲染、进度保持、
  全文搜索、标注、统计、设置主题、测试验证入口；每条链路列两端入口文件
  与关键职责，并标注“进场阅读顺序”与渲染双实现一致性的红线。
- 后续小节编号顺延（数据契约→6、测试纪律→7、常用命令→8）。

#### 验证

- 纯文档改动，不涉及代码；未跑构建（无影响）。待提交推送。

### 9.66 借鉴 mattpocock/skills：共享语言与调试方法论（2026-08-09）

用户提供 [mattpocock/skills](https://github.com/mattpocock/skills) 参考。
该仓库以四个失败模式组织可组合 skill：需求对齐（grill）、共享语言
（CONTEXT.md/ADR）、反馈环（tdd/diagnosing-bugs）、防屎山（架构体检）。

#### 落地内容

- **新增 docs/GLOSSARY.md（共享语言/术语表）**：
  领域术语（安科/安价/楼层/只看楼主/引用/骰子/tid/pid/热更新/原生书/图片模式/
  Cookie）↔ 代码概念（text_offset/TextPos/scroll_ratio/ProgressEntry/
  reader-lite.js/ChapterProgressTracker/NgaDownloader 等）↔ 双端文件，
  五节：领域术语、数据与进度概念、Android 组件、Windows 组件、工程与流程。
  Agent 与开发者统一词汇，禁止自造同义词。
- **AGENTS.md 工作方式节补充三条**：
  - 修复先写复现测试（红→绿→保留回归），禁止改完再补测试；
  - 调试五步循环：复现 → 最小化 → 假设（先写“谁在什么时机写、谁能覆盖谁”）
    → 插桩验证 → 修复+回归；每步未通过不得进入下一步；
  - 共享语言：术语不确定先查 GLOSSARY.md。
- 进场先读列表加入 GLOSSARY.md。

#### 验证

- 纯文档改动，不涉及代码；待提交推送。

### 9.67 补充 Compose UI 测试（root / screen / drawer，2026-08-10）

用户提出测试覆盖需求：Android UI 测试此前仅 3 个仪器测试
（ReaderPagedCrossTest），需补充 Compose UI 测试（root/screen/drawer）。

#### 改动

- **AppContainer 支持注入数据目录**：新增 `dataDir` 参数（默认
  `filesDir/AnkeShelf` 不变）；UI 测试用 cacheDir 下唯一临时目录构造容器，
  完全不触碰真机/应用真实书架与设置数据。
- **新增 3 个仪器测试文件（androidTest/ui/）**：
  - `RootNavigationTest`（root）：默认书架页 + 空书架状态（“书架为空 /
    导入 EPUB / 从 NGA 下载”）；底部四 Tab 切换（书架 → 下载 → 搜索 →
    设置 → 回书架）。
  - `DownloadScreenTest`（screen + drawer/二级详情）：一级三入口
    （登录配置 / 下载·更新 / 已下载）；点“登录配置”进二级（ngaPassportUid /
    ngaPassportCid 输入框），返回键回一级。
  - `SettingsScreenTest`（screen + drawer/二级详情）：一级六项
    （外观/阅读/操作/统计/数据/帮助）；“外观”二级含“主题”并可返回；
    “阅读”二级含“正文字体”。
- 编译注意：Compose 2026.05 BOM 的 ui-test 顶层无 `assertExists`，
  改用 `assertIsDisplayed`；`AnkeShelfTheme` 需传 `settings: SettingsData`。

#### 验证

- `compileDebugAndroidTestKotlin` 通过；`testDebugUnitTest` 86 过/1 跳 +
  `assembleDebug` 通过（AppContainer 改动无回归）。
- **真机执行 7/7 通过**（华为 ELE-AL00，`am instrument` 跑三个新测试类，
  OK (7 tests)）。

#### 真机执行要点（签名与 R8 三连坑）

- 真机已装正式签名 v1.0.0，`connectedDebugAndroidTest` 直接
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；Android 10+ 还要求测试包与目标
  同签名。
- 尝试 `testBuildType=release`：release androidTest 触发 R8，先缺
  `javax.lang.model.element.Modifier`（加 `-dontwarn` 解决），随后缺
  `androidx.tracing.Trace`（运行器在 app 进程引用，release app 裁剪，
  proguard 加 keep 解决），再缺 `kotlin.LazyKt`（逐个 keep 不可持续）。
- **最终方案（临时，非入库）**：debug 构建类型临时挂 release 签名
  （无 R8，运行器依赖完整）→ 构建 debug app + debug androidTest →
  用 apksigner 以 release 密钥重签测试 APK → 覆盖安装（签名一致不丢数据）
  → `am instrument` 运行。跑完恢复 build.gradle.kts。
- `ui-test.manifest` 必须保持 `debugImplementation`（测试 Activity 合并进
  app 包；误放 androidTestImplementation 会导致 Intent 解析到测试包）。
- `proguard-rules.pro` 保留 `androidx.tracing.Trace` keep（正式包支持
  仪器测试的兜底，无害）。

### 9.68 正式 Logo 确定与集成（2026-08-10）

用户经多轮提示词迭代（骰子 + 书、克制配色、三点阵、书页骰子结果、
贴页排版），最终确定即梦生成的一版为正式 logo：打开的米白书页 +
蓝灰底座 + 中央金色圆角方块带三点骰子阵，无文字、无外部水印。

#### 集成

- 原图归档为 [docs/logo/ankeshelf-logo.png](docs/logo/ankeshelf-logo.png)
  （1024x1024，PNG）。
- Android 图标：从原图生成 mipmap-mdpi..xxxhdpi 的
  `ic_launcher.png` / `ic_launcher_round.png`（48/72/96/144/192px），
  替换原占位矢量图标；删除 `mipmap-anydpi-v26/` 两个自适应 XML 与
  `drawable/ic_launcher_foreground.xml`，清理 `colors.xml` 中不再使用的
  `ic_launcher_background`；Manifest 引用不变。
- README 顶部加入居中 logo。

#### 验证

- `assembleDebug` 通过；图标资源随包生效。

### 9.69 带新 Logo 的 Release 重新打包与资产替换（2026-08-10）

用户要求：重新打包带新 Logo 的 release 并提交；并反馈 main 分支 README
没有新 logo。

#### README 核对（实为缓存）

- 远程 main（41e208c）的 README 已包含
  `<img src="docs/logo/ankeshelf-logo.png">`，`docs/logo/ankeshelf-logo.png`
  （761,633 字节）也在远程；GitHub 页面缓存/图片 CDN 未刷新导致误判，
  刷新即可。

#### Release 重新打包

- `assembleRelease` 重新构建（含新 logo 位图，AAPT2 压缩为
  `res/9w.png` 等密度图标，160/240/320/480/640 齐全）。
- 新包 `dist/AnkeShelf-v1.0.0-android.apk`：16,525,920 字节，
  versionName 1.0.0 / versionCode 1；SHA256
  `7451250B7F4F28AFC7B1CDF82B51A79A88E80A70862B92B7DE3FED41191C80C9`；
  凭据扫描 PASS。
- 替换 GitHub Release `android-v1.0.0` 资产：删除旧 APK
  （16,393,764 字节）→ 上传新 APK（16,525,920 字节）→ REST API 核验一致；
  Release 说明 SHA256 同步更新。

### 9.70 Logo 源图更正（换无水印版）并重新发布（2026-08-10）

用户更正：上一版即梦原图实际带水印，更换为无水印版（1731x1731，
视觉模型与像素扫描双重确认右下角无“即梦AI”标记，仅书封边缘阴影）。

#### 重新集成

- 从无水印原图重新生成五个密度 mipmap（ic_launcher / round）与
  `docs/logo/ankeshelf-logo.png`（1024，894,171 字节）。
- `assembleRelease` 重新打包：`dist/AnkeShelf-v1.0.0-android.apk`
  16,538,184 字节，SHA256 `5CD70CA0…D52F4D0`，凭据扫描 PASS。
- 替换 GitHub Release 资产（删除 16,525,920 字节旧包 → 上传新包 →
  REST API 核验 16,538,184 字节一致）；Release 说明 SHA256 同步。

---

## 10. Windows 端开发日志（2026-08-09 起）

> 本节约 9.x（安卓端日志）之后新增，用于记录 Windows 端与双端工程改动；
> 记录格式沿用 9.x（日期 + 提交 + 现象/结论）。

### 10.1 Windows GitHub Actions CI：unittest + PyInstaller 打包（2026-08-09）

- **背景**：CI 覆盖此前仅 Android（`.github/workflows/android.yml`），Windows 端无 CI；
  用户提出补齐 Windows 端 GitHub Actions（unittest + 打包）。
- **改动**：新增 `.github/workflows/windows.yml`：
  - 触发：push / pull_request，路径过滤仅限 Windows 相关（`app/**`、`web/**`、
    `ngapost2md-python/**`、`tests/**`、`requirements.txt`、`ankeshelf.spec`、
    `run_app.py`、本工作流文件）；另有 `workflow_dispatch` 手动触发。
  - 运行环境：`windows-latest` + Python 3.12（与发行包内置运行时一致）+ pip cache。
  - 步骤：`pip install -r requirements.txt` → `node --check web/js/*.js`
    （pwsh 循环逐文件）→ `python -m unittest discover tests`（174 项）→
    PyInstaller 目录版打包 → 复制 README/LICENSE/OFL/使用说明 到 `dist\AnkeShelf` →
    按 `app.__version__` 压成 `AnkeShelf-vX.Y.Z.zip` → `upload-artifact@v5` 上传。
  - 边界：不扩大 android.yml 的触发范围；Windows 工作流只响应 Windows 相关路径。
- **验证**：本机按 CI 步骤全量跑通——JS 语法检查 OK、174 项单测 OK、
  `Build complete!`、生成 `AnkeShelf-v1.2.0.zip`（44,738,303 字节）。
- **待办/注意**：尚未推送（按纪律需用户明确授权）；推送后观察 GitHub Actions 首次运行；
  后续可考虑 README 加 CI badge（本轮未做，避免范围外改动）。

### 10.2 Windows CI 首次运行失败修复：instance_guard 中文 print 在 cp1252 下崩溃（2026-08-09）

- **现象**：Windows CI（GitHub Actions `windows-latest`，英文环境）在 Unit tests 步骤失败；
  `test_kills_stale_python` 触发 `UnicodeEncodeError: 'charmap' codec can't encode characters`。
- **根因**：`app/instance_guard.py` 启动清理残留进程时直接 `print(中文诊断)`；
  本机中文控制台（cp936）正常，CI 英文环境 stdout 为 cp1252，无法编码中文直接抛异常。
- **修复**：新增 `_safe_print()`——正常打印；stdout 编码不支持时用
  `encode(enc, "replace").decode(enc, "replace")` 降级为可编码字符，不崩溃。
- **验证**：`PYTHONIOENCODING=cp1252 python -m unittest tests.test_main_guard`
  修复前复现同款 `UnicodeEncodeError`（红）→ 修复后 4/4 OK（绿）；
  全量 `python -m unittest discover tests` 174 项 OK。
- **验证（已完成）**：推送后 Windows CI 重跑 **success**（15:48 UTC，
  含 unittest 174 项 + PyInstaller 目录版打包 + 目录版 zip 上传）；
  `android/m1-data-layer` 已快进同步至 `5fcbd97`，与 main 一致。

### 10.3 双端契约 B0：contracts/ 目录、NativeBook 格式规范、文本规范化规范（2026-08-10）

- **背景**：收到第三方 review（`saas-nexus-1786343013653.md`，四轮：结构分析 /
  调用链 / 分层建议 / Architecture 2.0），核心建议为“不做大重写，把已正确的
  边界契约化、可测试化”；用户批准按 B0（契约与文档先行，零业务代码改动）开始。
- **改动**：
  - 新增 `contracts/`：README（使用规则与版本）、JSON Schema
    （native-book meta/floors、progress、annotations、settings）；
  - `contracts/text/text-cases.json`：14 条文本规范化用例（basic/br/block/
    script_style/collapse/nbsp/entities/fragment/comment/cdata/display_none/
    ruby/unicode/astral），`expected` 以 Windows Python 实现为准；
  - `contracts/fixtures/native-book/basic-nga/`：最小原生书 fixture
    （meta/floors/chapters + expected_plaintext）；
  - 新增 `docs/NATIVE_BOOK_FORMAT.md`（ank-native/1 规范：目录布局、六大
    invariant、字段表、分组规则、安全与迁移）与
    `docs/TEXT_NORMALIZATION_SPEC.md`（9 条权威规则 + 4 项已知分歧清单）；
  - 修正 `android/README.md` 过期里程碑（M2 → 指向根 README 的 v1.0.0）；
    `AGENTS.md` 共享文件清单加入 `contracts/`；`DATA_CONTRACT.md` 增加指向。
- **验证**：Python 权威实现与 14 条用例、fixture 期望纯文本逐条一致；全部
  contracts JSON 可解析（本机无 jsonschema，schema 校验留到 B1 CI）。
- **发现并记录的已知分歧（B1 暴露、B2 统一）**：
  1. Kotlin `Regex("\\s+")` 为 ASCII 空白，NBSP 不折叠（Python/JS 会）；
  2. Kotlin 命名实体表为常用子集（Python 为完整 HTML 实体表）；
  3. CDATA：Python/JS 视为 bogus comment 无文本，Kotlin 会输出内容；
  4. 星形字符（emoji）偏移计数：Python 按码点、JS/Kotlin 按 UTF-16 code unit。
- **待办**：B1 跨端 golden tests（Python / Windows JS / Android Kotlin+JS 读同一
  fixtures）+ schema 校验接入 CI；B2 统一上述四项分歧语义。

### 10.4 B1 跨端契约 golden tests（2026-08-10）

- **目标**：把 `contracts/` fixtures 接入三端测试，让 text_offset / 文本折叠
  契约漂移显性化（红→绿，先暴露后统一）。
- **改动**：
  - `contracts/text/text-cases.json` 增至 15 条（新增 `entity_subset`：
    `&thinsp;` 三端行为不同），同步更新 TEXT_NORMALIZATION_SPEC 分歧清单；
  - `web/js/textpos.js`：把折叠核心提取为纯函数 `foldItems` 并导出
    （`module.exports` + `window` 守卫），浏览器行为不变，Node 可直接加载；
  - 新增 `contracts/tests/textpos.test.js`（Node，无 npm 依赖）：折叠语义、
    15 条用例结构自洽、astral UTF-16 已知分歧断言；
  - 新增 `tests/test_contracts.py`（8 条）：text-cases 权威匹配、fixture
    期望纯文本、NativeBook 读取与路径穿越、JSON Schema 校验
    （`jsonschema` 加入 requirements.txt）；
  - 扩展 `tests/ui/runner.py`：真实 WebView 内运行全部 text-cases
    （新增 `contract_text_cases` / `contract_js_points` /
    `contract_js_astral_utf16` 三项断言）；
  - Android 新增 `ContractTextTest` + `ContractNativeBookTest`
    （读取仓库根 `contracts/`；4 个已知分歧用例断言当前行为，B2 翻转）；
  - `.github/workflows/windows.yml`：`contracts/**` 纳入触发路径，
    新增 Node 契约测试步骤。
- **验证**：
  - Node：`textpos contract OK: 15 cases`；
  - Python：182 项全绿（新增 8 项）；
  - Android：90 tests completed / 1 skipped（ContractTextTest 与
    ContractNativeBookTest 均通过）；
  - UI harness 扩展未实跑（需桌面 WebView2 会话，留待本地验证）。
- **待办**：B2 统一四项分歧（NBSP 空白定义、实体表、CDATA 解析、
  UTF-16 offset 计数语义）；UI harness 实跑确认三项新断言。

### 10.5 实机验证暴露并修复：textpos 对“注释分隔的文本节点”误插空格（2026-08-10）

- **现象**：`python -m tests.ui.runner` 实机跑出 `contract_text_cases FAIL` /
  `contract_js_points FAIL`；其余 90 项全 PASS。失败详情：
  `text comment: got='a b' expected='ab'`。
- **根因**：浏览器 DOM 不合并注释两侧的文本节点（`<p>a<!-- c -->b</p>` 产生
  “a”与“b”两个 Text 节点），textpos.js 按“相邻文本节点间一个空格”折叠 → `a b`；
  而 Python/Kotlin 解析器与视觉渲染都是 `ab`。这是 B1 实机测试抓到的真实
  跨端漂移（此前 Python 差分通过只是因为样本书没有注释分隔文本）。
- **修复**：`web/js/textpos.js` 的 `build()` 增加
  `separatedByCommentOnly(prev.node, node)` 判定——两个文本节点之间只有注释
  时给后一项标记 `noSep`，`foldItems` 不再插入分隔；坐标映射（mapRaw/ranges）
  保持逐节点不变。`foldItems` 契约新增 `noSep` 字段，Node 单测补充断言；
  TEXT_NORMALIZATION_SPEC 第 4 条明确“注释分隔的相邻文本节点之间不插空格”。
- **验证**：`node contracts/tests/textpos.test.js` OK（15 cases）；
  UI 实机 harness 全绿 **exit=0，92 项 PASS**（`contract_text_cases` /
  `contract_js_points` / `contract_js_astral_utf16` 均 PASS）。
- **附带**：runner.py 增加契约失败明细输出（用例 id + got/expected），
  以后 FAIL 可直接定位，不再盲猜。

### 10.6 B2：文本分歧统一（UTF-16 canonical）+ Position / Book Protocol（2026-08-10）

#### B2a 文本分歧统一

- **canonical 变更**：`text_offset` 按 **UTF-16 code unit** 计数（与 DOM/JS/Kotlin
  字符串索引一致；emoji 等星形字符占 2）；Python 内部按码点扫描，对外输出换算。
- **Windows Python**：`app/text.py` 新增 `utf16_len / utf16_index /
  cp_index_from_utf16`；`app/search.py` 对外返回的 `offset/text_len` 统一 UTF-16
  （顺带修复“命中点在 emoji 之后时 JS 跳转/高亮偏差”的潜在 bug）。
- **Android Kotlin**：`data/Text.kt` 空白折叠改为 Unicode 空白类（含 NBSP、
  U+2000–U+3000 空白族）；CDATA 与 Python/JS 一致不产生文本；命名实体改用
  完整 HTML5 表（新增 `Html5Entities.kt`，由 Python `html.entities.html5`
  机械生成 2125 条，替代 44 条子集）。
- **契约与测试**：`text-cases.json` 的 astral 用例 offset 2→3（canonical=UTF-16）；
  TEXT_NORMALIZATION_SPEC §2.9/§4 更新（4 项分歧标记已统一，残余 FEFF 记录在案）；
  DATA_CONTRACT 注明 UTF-16 计数语义；Python/Node/Kotlin/UI harness 全部同步。
- **验证**：Python 182 项 OK；Node 15 cases OK；Android 90 过/1 跳；
  UI harness exit=0（92 项 PASS，含三项契约断言）。

#### B2b Position + Book Protocol

- 新增 `app/domain.py`：`Position`（frozen dataclass：chapter_index + text_offset）
  与 `Book`（runtime_checkable Protocol：id/title/author/open/close/read_file/
  chapter_text/chapter_title/get_cover_bytes，EpubBook 与 NativeBook 均满足）。
- 最小接入（不改磁盘格式与 `/api/<name>` 协议）：
  `ProgressStore.position/set_position`；`Api.save_progress` 改用 `Position`；
  `BookManager` 缓存与返回类型改为 `Book`。
- 新增 `tests/test_domain.py` 5 条（Position 不可变/往返、两种 Book 满足协议、
  BookManager 注册返回 Book）。
- 验证：Python 全量 **187 项 OK**。
- **待办**：B3（拆 api.py + 前端 ApiClient）按 review 顺序推进。

### 10.7 B3a：拆 api.py → app/api/ 包（registry + 按域 handler，外部协议零变化）（2026-08-10）

- **目标**：消除 Api 门面继续膨胀的隐患；`/api/<name>`、`Api(...)` 构造签名、
  server.py 分发全部不变。
- **改动**：
  - 新增 `app/api/` 包：`registry.py`（ApiRegistry：register + `__getattr__`
    兼容 server 的 `getattr(api, name, None)` 分发）、`common.py`
    （ApiContext + 共享辅助）、按域 handler 模块：system / library / reader /
    search_api / annotation_api / stats_api / nga_api / settings_api；
  - `app/api/__init__.py`：`Api(ApiRegistry)` 构造时把全部 handler 绑定
    ApiContext 并注册（方法名即契约，未注册名 AttributeError → 404）；
  - 删除单文件 `app/api.py`（482 行拆为 10 个小模块）；
  - 文档同步：ARCHITECTURE / CODEBASE_MAP / GLOSSARY 的 api 引用改为 `app/api/`。
- **验证**：Python 187 项 OK；UI 实机 harness exit=0（92 项 PASS，无 FAIL）。
- **待办**：B3b 前端 ApiClient（UI 不再直接调 Bridge.call，收口到 api-client.js）。

### 10.8 B3b：前端 ApiClient（api-client.js，UI 不再直接调 Bridge）（2026-08-10）

- **改动**：
  - 新增 `web/js/api-client.js`：39 个后端 handler 的 camelCase 客户端
    （`Api.openBook(...)` / `Api.saveProgress(...)` 等，参数原样透传 Bridge）；
    index.html 在 bridge.js 之后加载；
  - 机械迁移 9 个前端文件共 73 处 `Bridge.call('snake', ...)` →
    `Api.camel(...)`（annotations/app/bookshelf/fullsearch/nga_download/
    reader/settings/stats/view-menu）；
  - 迁移脚本首轮漏掉方法名后的逗号（`Api.x(, arg)`），补修正 pass 后
    `node --check` 全部通过、`Bridge.call` 仅存在于 bridge.js 与 api-client.js。
- **验证**：`node --check web/js/*.js` OK；UI 实机 harness exit=0（92 项 PASS）。
- **待办**：B4（拆 reader.js：ReaderSession 先行）按 review 顺序推进。

### 10.9 B4：ReaderSession 引入 + reader.js 拆分（utils/session/navigation/help/image）（2026-08-10）

- **改动**（reader.js 873 行 → 核心约 560 行）：
  - 新增 `web/js/reader-utils.js`：CSS 覆盖层常量（BASE/NGA/PAGINATION）、
    快捷键帮助文案、字体解析（activeFontKey/fontFaceCss/resolveFamily）；
  - 新增 `web/js/reader-session.js`：`ReaderSession`（bookId/chapterIndex/
    textOffset/mode/startedAt/dirty/lastSaved + enterChapter/setPosition/
    markSaved/elapsedSeconds），Node 可直接加载；
  - 新增 `reader-navigation.js`（prev/nextChapter、pageOrChapter）、
    `reader-help.js`（showShortcuts/closeShortcuts）、`reader-image.js`
    （openImage/closeImage，lightboxScale 收归模块内），均在 reader.js 之后
    `Object.assign` 回 `window.Reader`；
  - reader.js 删除上述常量/工具/方法，核心保留 loadChapter/currentOffset/
    saveProgress/applyMode/applyOverrides/updateProgressUI/seekToOffset/
    jumpToFraction/onKeyDown/toggleChrome 等编排；接入 `ensureSession()`，
    loadChapter 记 `enterChapter`、saveProgress 记 `setPosition + markSaved`、
    applyLayout 记 `mode`；
  - index.html 脚本顺序：utils/session 在 reader.js 前，navigation/help/image
    在 reader.js 后；
  - 新增 `tests/js/reader-session.test.js`（Node，无 npm），Windows CI
    的契约 JS 步骤同时跑 textpos + reader-session。
- **验证**：`node --check web/js/*.js` OK；两个 Node 测试 OK；
  UI 实机 harness exit=0（92 项 PASS，含 help_modal/lightbox/翻页/换章/
  进度链路）。
- **待办**：B4 剩余（controller/position/chapter-loader 进一步拆分）暂缓，
  避免一次性大改；后续按“哪里开始疼先拆哪里”推进。

### 10.10 B5：BookRevision + 轻量领域事件 + Repository 接口（2026-08-10）

- **改动**（Windows Python）：
  - `app/domain.py`：新增 `book_revision(book)`（NativeBook =
    `native:<tid>:<last_lou>:<updated_time>`，EPUB = `epub:<size>:<mtime>`；
    热更新/替换文件后必变）；新增 `ProgressRepository` / `ShelfRepository`
    runtime_checkable Protocol（ProgressStore / Shelf 已满足）；
  - 新增 `app/events.py`：进程内 `EventBus`（on/emit，订阅方异常不影响主流程）；
  - `app/search.py`：索引携带 `revision`，`ensure_index` 同版本复用、版本变化
    重建；新增 `refresh_if_stale(book)`；
  - `app/nga_service.py`：首次下载注册与热更新完成后 `emit("book_updated")`；
  - `app/main.py`：订阅 `book_updated` → 若书在 BookManager 缓存中则按
    revision 刷新搜索索引（惰性重建，修复“热更新后旧索引残留”隐患）。
- **验证**：Python 全量 **194 项 OK**（新增 events 3 条、BookRevision/协议 4 条、
  搜索 revision 1 条）；UI 实机 harness exit=0（92 项 PASS）。
- **待办**：B6（统一 Migration + 结构化 API 错误码 + 诊断导出/日志）按 review
  顺序推进；TaskManager/ContentSource 仍按计划推迟到第二书源需求出现时。

### 10.11 B6：统一 Migration + 结构化 API 错误码 + 诊断导出（2026-08-10）

- **统一迁移框架**：新增 `app/migrations.py`（`run_migrations`：逐步迁移 +
  版本校验）；`Settings.load` 的旧版（v<3）迁移改走框架（行为不变：滚动阅读 +
  内置默认字体 + 落盘），新增 3 条迁移单测。
- **结构化 API 错误码**：新增 `app/errors.py`（`ErrorCode` + `api_error`，
  message 不变、新增 `ok/error_code`，向后兼容）；应用到 open_book
  （BOOK_NOT_FOUND/BOOK_INVALID）、标注/导出/NGA/全屏的“服务不可用”
  （SERVICE_UNAVAILABLE）、标注不存在/参数非法（ANNOTATION_INVALID）、
  诊断导出失败（EXPORT_FAILED/STORAGE_ERROR）；新增 error_code 断言测试。
- **诊断导出**：新增 `app/diagnostics.py`（zip：version.txt + 脱敏 settings.json
  + logs/*.log；**绝不打包 nga_config.ini / config.ini**）；新增
  `/api/export_diagnostics`（原生选目录）与设置 → 数据页「导出诊断信息」按钮；
  新增打包内容与凭据隔离单测。
- **验证**：Python 全量 **199 项 OK**（+5）；`node --check` OK；
  UI 实机 harness exit=0（92 项 PASS）。
- **待办**：B7（TaskManager 抽象 / 统一日志字段）按 review 顺序推进；
  ContentSource 仍留到第二书源需求。

### 10.12 B7：TaskManager 抽象 + 统一日志字段（2026-08-10）

- **任务基础设施**：新增 `app/tasks.py`（`TaskStatus` / `TaskProgress` /
  `TaskCancelled` / `TaskManager`）：按 lane 单飞（同 lane 同时只允许一个任务，
  不同 lane 可并行），取消由任务方检查标志；**NgaService 暂不迁移**
  （保持其自身单飞锁，语义一致），模块供大文件导入/导出/索引重建等新任务接入。
- **统一日志字段**：新增 `app/logutil.py` 的 `log_event(logger, component,
  event, **fields)`（输出 `component event key=value`，None 字段跳过）；
  接入 NGA 下载完成（`nga download_done`）、热更新完成（`nga update_done`）、
  搜索索引构建（`search index_built`，含 chapters/duration_ms）。
- **验证**：Python 全量 **204 项 OK**（+5：tasks 4、logutil 1）；
  UI 实机 harness exit=0（92 项 PASS）。
- **待办**：ContentSource / 第二书源抽象仍留到需求出现；review 的
  P0/P1 主要项（契约、领域模型、Api/Reader 拆分、事件/缓存失效、迁移/错误码/
  诊断）已全部落地。

### 10.13 B8：测试补齐——EPUB 安全回归 + ZIP 炸弹防护、性能基准、nightly CI（2026-08-10）

- **EPUB 安全回归**：新增 `tests/security/`（7 条）：最小恶意 EPUB 构造器 +
  条目数/解压体积超限拒绝、穿越条目仅限 zip 内容（不触宿主 FS）、script 内容
  文本提取隔离、章节响应 CSP（script-src/object-src 'none'）、服务器穿越请求
  400/404。
- **ZIP 炸弹防护**：`app/epub.py` 的 `EpubBook` 新增可配置上限
  （默认 max_entries=50000、max_total_bytes=8GiB，best-effort），超限抛
  `EpubError`；正常书不受影响（NGA 嵌入图大书在限内）。
- **性能基准**：新增 `tests/performance/bench.py`（`python -m
  tests.performance.bench`），度量章节文本提取、原生书打开读取、搜索索引构建+
  查询（耗时 + tracemalloc 峰值），输出 `baseline.json`（已提交初值）。
- **CI 分层**：新增 `.github/workflows/nightly.yml`（UTC 20:00 定时 +
  workflow_dispatch）：全量单测（含安全回归）+ 性能基准 + 上传 baseline artifact；
  真实 NGA 网络测试仍不进 PR/nightly。
- **验证**：Python 全量 **211 项 OK**（+7 安全回归）；UI 实机 harness exit=0
  （92 项 PASS）；bench 本地生成 baseline.json 成功。
- **待办**：性能阈值告警（如 TextPos 构建 +60% 发 warning）待积累几轮 nightly
  数据后再定；安全防护可继续补单 entry 压缩比上限。

### 10.14 会话交接快照（2026-08-10，切换 agent 工具前）

> 本节为“更换 agent 工具/对话上下文丢失”时的**最新状态入口**。
> 新会话按日志头部建议顺序进场后，直接以本节为准核对现状。

#### 当前状态

- HEAD：`f56fb01`（win: B8 测试补齐…），分支 `main`，**已推送 GitHub**，
  工作树干净（`git status` 无输出）。
- 版本线：Windows v1.2.0（已发布，资产 AnkeShelf-v1.2.0.zip）；
  Android android-v1.0.0（已发布，资产 AnkeShelf-v1.0.0-android.apk）。
- 测试现状：
  - Windows Python：`python -m unittest discover tests` = **211 项 OK**
    （含契约、安全、迁移、事件、领域模型）；
  - JS：`node contracts/tests/textpos.test.js`、`node tests/js/reader-session.test.js`
    均 OK；
  - Android JVM：`gradlew testDebugUnitTest` = 90 过 / 1 跳；
  - UI 实机 harness：`python -m tests.ui.runner` = **92 项 PASS**（需桌面 WebView2）。
- CI：`windows.yml`（PR：单测+JS 契约+PyInstaller 打包）、`android.yml`（PR）、
  `nightly.yml`（UTC 20:00：全量单测+性能基准）。

#### 本机环境（Windows 开发机）

- Python：`F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`
- Node：`F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe`
- Android 构建（见 9.8）：`JAVA_HOME=D:\Android\AndroidStudio\jbr`、
  `GRADLE_USER_HOME=F:\Users\Administrator\.gradle`、
  `ANDROID_HOME=D:\Codex\project1\.tools\android-sdk`；Gradle 命令：
  `android\gradlew.bat -p android testDebugUnitTest assembleDebug`。
- adb：`D:\Codex\project1\.tools\android-sdk\platform-tools\adb.exe`
  （需 `HOME/USERPROFILE=F:\Users\Administrator` 等环境，见 9.8）。

#### 本地不入库/勿打包内容

- `.local/archive/`：历史归档，含真实 NGA uid/cid 备份（gitignore 覆盖，勿入库）；
- `android/keystore/`、`keystore.properties`、`local.properties`：签名与 SDK 配置，勿入库；
- `ngapost2md-python/config.ini`：本地 NGA 凭据（gitignore，打包只带 .example）；
- `dist/`、`build/`、`.tools/`：构建产物与工具链，不入库。

#### 待办与延后项

- ContentSource / 第二书源抽象：等真实需求出现再做（review P2）；
- 性能阈值告警（TextPos 构建 +60% 等）：等 nightly 积累几轮数据；
- EPUB 单 entry 压缩比上限：可继续补；
- B4 剩余 reader 拆分（controller/position/chapter-loader）：按“哪里疼拆哪里”推进；
- NGA 更新帖模板：`docs/nga-post-template.bbcode`（新版本发布时套用）。

#### 纪律提醒（新会话必守）

- 进场先读 AGENTS.md；改动必补记本日志（含日期/提交/现象/结论）；
- 推送代码/发行版必须用户明确授权；双端共享文件改动先做 Diff 影响检查；
- 进度类改动必须跑“滚动/翻页 → 退出 → 重进”回归；改 JS 后校验 APK 内脚本；
- 发布前跑凭据扫描（Windows：检查 dist 无 config.ini/nga_config；Android：
  `android/scripts/check-release.ps1`）。

### 10.15 整合四份架构评审文档并输出路线图（2026-08-12）

- **背景**：用户提供四份评审/规划文档（`H:\AnkeShelf_Architecture_Improvement_Proposal.md`、
  `H:\review1.md`、`H:\review2.md`、`H:\架构债清理清单.md`），要求在充分阅读
  仓库全部代码后统一整合，明确下阶段操作方向、改动位点、轻重缓急与预期目标；
  同时明确暂不改代码。
- **调研**：通读四份文档；逐项核验仓库断言（大文件行数、字体 SHA-256 重复、
  requirements 无 lock、治理文件缺失、ReaderHtml 正则清洗、API 双清单无对照、
  bridge MOCKS 漂移、reader.js 8 处乱码注释等）；债务扫描得到 Kotlin
  `catch(Exception)` 32 处、`return null` 28 处、Python `except Exception`
  26 处等基线数据。
- **产出**：新增 `docs/ARCHITECTURE_ROADMAP.md`，作为下阶段任务统一参考。
  路线：P0 修复发行包启动崩溃（pythonnet/.NET）→ P1 契约/API 守卫、桥协议
  版本 + 进度回放、依赖锁定、首批 ADR → P2 jsoup 清洗、错误模型、诊断闭环 →
  P3 按变化点拆分、TaskManager 试点、存储恢复、开源治理 → P4 参考仓库研究与
  触发式扩展。
- **验证**：纯文档改动，未跑构建；工作树当前包含本文件与
  `docs/ARCHITECTURE_ROADMAP.md` 两处未提交改动。
- **待办**：发行包崩溃需用户侧配合排查（解除文件锁定 / 安装 .NET Framework
  4.8）；参考仓库克隆仍受命令环境断网阻塞。

---

## 2026-08-20 二次归档（2026-08-19 及此前流水）

### 2026-08-19 win：NGA 封面文字再次清理 + 最近阅读显示作者进度

- 背景：用户反馈书架页封面仍有文字；最近阅读栏 NGA 书缺少作者和进度。
- 修复：
  - NGA 封面占位不再依赖 `cover_rel` 判断，只要书是 NGA 就使用骰子占位；
    若存在真实封面则图片覆盖在骰子上，无封面时保证零文字。
  - 顶部最近阅读栏的 meta 改为“作者 · NGA · 进度%”。
- 验证：JS 语法/契约全绿。
### 2026-08-19 win：NGA 默认封面彻底移除文字

- 背景：骰子占位已生效，但网格封面上的 NGA 文字徽标仍出现在默认封面。
- 修复：网格封面仅在有真实封面时显示 NGA 徽标；无封面默认图只保留骰子图标。
- 验证：`node --check web/js/bookshelf.js` OK。
### 2026-08-19 win/android：第三轮问题复核与修正

- 背景：用户确认菜单仍跳动/卡片飞走、方括号仍只剥一个、默认封面仍显示文字。
- 更多菜单：
  - 根因：菜单作为封面卡片的子元素，卡片有 transform，导致 `position: fixed` 相对卡片而非视口；
  - 修复：菜单改为挂到 `document.body` 上再 `fixed` 定位，彻底避开 transform 容器；
  - 关闭时从 body 移除菜单，避免残留。
- 方括号前缀：
  - Web/Android 正则改为一次性匹配连续多个括号前缀的重复组，不再依赖 `g` 的 `^` 多次匹配。
- NGA 默认封面：
  - 根因：API 始终返回 `cover_url`，前端无法区分“无封面”；
  - 修复：`record_to_dict` 增加 `cover_rel` 字段，前端改用 `cover_rel` 判断是否有真实封面；
  - Web 网格/列表/最近阅读与书籍管理页均改为无封面时显示骰子图标，且不再请求不存在的封面图。
- 验证：JS 语法/契约全绿；Python 303 项 OK（4 跳）；Android `compileDebugKotlin` BUILD SUCCESSFUL。
### 2026-08-19 win/android：第三轮 UI/细节反馈修复

- 背景：用户反馈更多菜单弹出位置跳动、方括号前缀未全部隐藏、NGA 默认封面文字、
  书籍管理缺搜索/导出/列表风格。
- 更多菜单：
  - 弹出前先以 `visibility:hidden` 测量，再设置 fixed 坐标并显示，消除“先跳一下”的问题。
- 方括号前缀：
  - Android 由 `replaceFirst` 改为 `replace`，与 Web 一致地剥离开头所有 `[...]` / `【...】` 前缀。
- NGA 默认封面：
  - Web 网格/列表/最近阅读与 Android 书架/已下载列表的 NGA 无封面占位改为平面骰子图标，
    不再显示书名/作者文字。
- 书籍管理设置页：
  - 支持按书名/作者搜索；
  - NGA/骨碌碌书籍增加“导出”按钮；
  - 行样式改为书架列表视图风格（封面缩略图 + 标题/作者 + 操作按钮组）。
- 验证：JS 语法/契约全绿；Android `compileDebugKotlin` BUILD SUCCESSFUL。
### 2026-08-19 win/android：第二轮 UI/持久化反馈修复

- 背景：用户继续反馈更多菜单越界、骨碌碌缺更新/导出、骨碌碌沉浸偏好不持久、
  最近阅读缺 NGA 标签、书籍管理样式简陋。
- 更多菜单：
  - “更多管理”菜单打开时改为 `position: fixed` 并做视口边缘夹紧，避免左/右/下越界。
- 骨碌碌操作：
  - 网格与列表的书架操作补上骨碌碌“检查更新”直通按钮；
  - 骨碌碌“导出帖子（含评论 EPUB）”加入网格二级菜单与列表操作。
- 骨碌碌沉浸偏好持久化：
  - 新增 `settings.gululu_immersive`（autoMusic/backgrounds/vfx/volume）默认值；
  - `gululu-immersive.js` 改为通过 `Api.saveSettings` 持久化，并在 App 设置加载后同步，
    解决随机端口下 localStorage 随 origin 丢失导致的“关闭自动音乐后重开又生效”。
- 最近阅读：
  - 顶部最近阅读卡片补 NGA 标签。
- 书籍管理样式：
  - 设置页“书籍管理”行改为卡片式 `.sp-book-row`，标题省略、按钮组固定右侧。
- 验证：JS 语法/契约全绿；Python 303 项 OK（4 跳）。
### 2026-08-19 win/android：NGA 在线图片代理修复 + 书架交互收敛

- 背景：用户反馈 NGA 在线图片完全无法显示、网格封面操作按钮超界、隐藏安科前缀不识别 []、
  下载完成后书架刷新慢。
- NGA 在线图片：
  - 根因：Python urllib 请求 img.nga.cn 被 TencentEdgeOne 返回 567 拦截页，curl 可正常 200；
  - 修复：`app/server.py::_fetch_url` 优先调用系统 `curl` 拉图，失败回退 urllib；
  - 验证：真实 NGA 图片 curl 代理返回 `image/png`；`tests.test_server` 36 项全绿。
- 书架网格交互：
  - 网格封面仅保留“更新”直通按钮，其余（导出/重命名/封面/恢复封面/移除）收进“更多管理”二级菜单；
  - 新增 `.book-menu` 样式与全局点击关闭。
- 隐藏安科前缀：
  - Web/Android 的 `hide_title_brackets` 正则从只识别 `【】` 扩展为同时识别 `[]`，
    并连续剥离开头多个括号前缀。
- 设置页：
  - 新增“书籍管理”标签页，可集中重命名/设置封面/恢复封面/移除书架书籍。
- 书架刷新：
  - NGA/Gululu 下载/更新完成后立即调用 `Shelf.render()`；关闭下载面板时也刷新书架。
- 验证：JS 语法/契约全绿；Android `compileDebugKotlin` BUILD SUCCESSFUL。

### 2026-08-19 android：P5-E2 应用内 NGA 登录（Android 先行）

- 背景：P5-E2 目标是连 F12/Cookie 都不需要；Android 先行，Windows 后续再做。
- 改动：
  - 新增 `NgaLoginDialog.kt`：应用内 WebView 打开 `https://bbs.nga.cn/`，
    仅允许 bbs.nga.cn 域，用户登录后点“完成并提取”从 `CookieManager` 读取
    Cookie 并解析 uid/cid 回填配置页；
  - `ConfigPanel` 新增“浏览器登录”按钮与弹窗；不落日志、不保存 WebView 会话。
- 验证：Android `compileDebugKotlin` 与 `testDebugUnitTest` 均 BUILD SUCCESSFUL；
  真机手工登录流程待后续验证。

### 2026-08-19 win/android：P5-E1 Cookie 粘贴自动解析

- 背景：P5-E1 目标是让小白不接触 F12 也能配置 NGA；P5-C/F 按用户要求暂不实施。
- Windows/Web：
  - 新增 `web/js/nga-cookie.js` 纯函数 `parseNgaCookieText`，从任意文本/完整 Cookie
    头提取 `ngaPassportUid` / `ngaPassportCid`（大小写不敏感、支持引号值）；
  - NGA 配置页新增“完整 Cookie（自动解析）”文本框，粘贴时自动填入 uid/cid 两栏；
  - `tests/js/nga-cookie.test.js` 覆盖完整头/带说明文本/大小写/引号/缺失；
  - `windows.yml` 增加该 JS 测试。
- Android：
  - `NgaConfig.kt` 新增同语义 `parseNgaCookieText` 纯函数 + `NgaCookieParts`；
  - `ConfigPanel` 新增“完整 Cookie（自动解析）”多行输入，输入时自动填充 uid/cid；
  - 新增 `NgaCookieParserTest` 5 个用例。
- 验证：JS 语法/契约/nga-cookie 测试全绿；Android `testDebugUnitTest --rerun-tasks`
  BUILD SUCCESSFUL（128 过 / 1 跳）。

### 2026-08-19 docs/android：P5 状态文档同步 + 管理菜单间距微调

- 文档同步：DevLog §1/§5、ROADMAP 顶部核对块/§2.1/§3 P5-C/E/F 状态/§4 执行顺序、
  MAINTENANCE §10 更新为“P5-A/B/D 已完成，剩余 C/E1/E2/F”。
- Android：`BookManagementOverlay` 管理行图标与文字间距从 `AnkeSpacing.md` 改为
  `AnkeSpacing.sm`，对齐设计令牌“图标与文字间距 = sm”。
- 验证：Android `compileDebugKotlin` 通过；doc-drift 扫描待跑。

### 2026-08-19 win/android：UI 图标规范核查与修正

- 背景：P5-D 新增封面操作与 Android 原生阅读器悬浮栏被指出不符合既有 UI 图标规范
  （新操作应为 `Icons.icon` / Material `Icons.Filled.*` 图标按钮，而不是裸文本按钮/Unicode 符号）。
- Windows/Web：
  - 书架“设置封面 / 恢复默认封面”改为 `image` / `undo` 图标按钮；
  - 新增 `undo`、`minus` 图标；`rename-btn` 补上与 `export-btn` 一致的圆形图标按钮样式；
  - 阅读器上一章/下一章按钮箭头改用 SVG 图标；
  - 图片灯箱关闭按钮、RSVP 控制、排版字号/行高步进按钮由 Unicode 符号改为 `Icons.icon`。
- Android：
  - `BookManagementOverlay` 恢复默认封面由 `Refresh` 改为语义更准确的 `Restore`；
  - `NativeReaderChrome` 顶/底悬浮栏由 `TextButton` 文本操作改为 `IconButton` +
    Material 图标（返回/目录/上一章/字号/主题/下一章/分页切换）；
  - 顺带将 NativeReaderChrome 中 4/8/12dp 裸间距替换为 `AnkeSpacing.xs/xxs/sm/md`。
- 验证：`node --check` 全绿；JS 契约/reader-lite 全绿；
  Android `gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。

### 2026-08-19 win/android：P5-D 封面系统（骨碌碌封面 + 自定义封面）

- 背景：P5-D 需求为骨碌碌导入生成封面，并支持双端自定义封面/恢复默认。
- Windows：
  - `gululu_epub.py` 新增 `fetch_cover` 参数：读取 `detail.cover.picUrl`，
    下载并写入 EPUB3 cover；失败记日志不阻断导入；导入/导出/更新链路均开启；
  - `Shelf.set_custom_cover/reset_cover`：复制用户图片到 `covers/<id>.<ext>`
    或删除并清空 `cover_rel`；
  - 新增 API `set_cover` / `reset_cover`；`dialogs.py` 增加图片选择；
  - `bookshelf.js` 书架操作增加“封面 / 恢复”按钮。
- Android：
  - `BookRepository.setCustomCover/resetCover`；
  - `BookManagementOverlay` 增加“设置封面 / 恢复默认封面”入口；
  - `BookshelfScreen` / `DownloadLibraryPanels` 接入 SAF 选图并刷新。
- 测试：新增 gululu cover 用例、Windows Shelf 自定义封面用例、Android reset cover 用例；
  Windows Python 303 项 OK（4 跳）；JS 契约 55 方法一致；Android JVM 123 过 / 1 跳。
- 文档：ROADMAP P5-D 标记完成，测试基线同步。

### 2026-08-19 docs：全面文档漂移扫描与同步

- 运行 `scripts/check-doc-drift.ps1 -RunTests`（脚本输出因 PowerShell stderr 中断，
  但测试已单独验证通过）。
- 发现并修复：
  - DevLog §1：测试复核日期 08-18→08-19；`api-contract` 52→53 方法；
  - ROADMAP 头部：补充 2026-08-19 架构收敛状态；
  - ROADMAP §2.1：主干状态补充收敛完成、Android JVM 复核日期更新为 08-19；
  - ROADMAP §2.2：代码规模热点行数同步到当前实际值；
  - MAINTENANCE_GUIDE §10：快照日期与主线描述更新；
  - 其余高漂移文档（README 版本表、MAINTENANCE §7、GLOSSARY、contracts README、
    docs/README）经核对无新增漂移。
- 验证：Windows Python 300 项 OK（4 跳）；JS 契约 53 方法一致。

### 2026-08-19 android：SettingsMiscPanels 备份操作失败增加日志

- 背景：设置页备份创建/验证/恢复的 `runCatching` 失败后只弹 toast，无日志。
- 改动：三处 `runCatching` 增加 `onFailure { Log.w(...) }`，行为不变。
- 验证：Android `gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。

### 2026-08-19 android：reader-lite 收敛 currentOffsetScroll 安全兜底

- 背景：`requestSettle` 内仍有一处 `try { currentOffsetScroll() } catch { }`，
  与已有的 `currentOffsetSafe` 模式不一致。
- 改动：新增 `currentOffsetScrollSafe()`，替换该处重复 try/catch；
  rebundle 为 6 parts / 37377 字节。
- 验证：JS 守卫全绿；Android `gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。

### 2026-08-19 android：SettingsPatch 改用 JSON 合并，消除 30 字段 copy 样板

- 背景：`SettingsPatch` 的 `update()` 手写 30 个 `?: data.x` 字段复制，属于
  “机械噪音”样板。
- 改动：
  - `SettingsPatch` 标记 `@Serializable`；
  - `Settings.update()` 改为：当前 `SettingsData` 与 patch 序列化为 JSON 对象，
    过滤 `JsonNull` 后合并再反序列化；
  - 行为不变：只有非空 patch 字段覆盖。
- 验证：Android `gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。

### 2026-08-19 win：移除 bridge.js 内置 50 个调试 MOCKS

- 背景：`bridge.js` 为“浏览器直开调试”内置了 50 个假方法，形成第三份 API 清单，
  是原提示词中“名义调试、实际漂移面”的残留。
- 改动：
  - `web/js/bridge.js` 删除 `MOCKS` 对象；无令牌时明确提示需要真实后端；
  - `tests/test_api_contract.py` 移除 MOCKS 覆盖测试，保留后端↔api-client 双向对照；
  - 文档同步：`contracts/README.md`、`MAINTENANCE_GUIDE.md`、`ARCHITECTURE_ROADMAP.md`
    移除 MOCKS 要求。
- 验证：`node --check web/js/bridge.js` 通过；`api-contract.test.js` 53 方法一致；
  Windows Python 300 项 OK（4 跳）。

### 2026-08-19 win：get_chapter_plaintext 不再折叠失败为空串

- 背景：`get_chapter_plaintext` 把书未加载、章节读取失败统一折叠为空串，
  属于“失败语义被隐藏”的残留。
- 改动：书未加载抛 `ApiError(BOOK_NOT_FOUND)`；章节读取失败抛
  `ApiError(BOOK_INVALID)`；不再返回 `""` 掩盖原因。
- 验证：Windows Python 301 项 OK（4 跳）。

### 2026-08-19 android：数据层 catch-all 增加可见日志（第二批：Epub/NativeBook/WebView）

- 背景：第一批已给 Shelf/Settings/NgaConfig/ContentResolver 加日志；本批继续覆盖
  EPUB、原生书与 WebView 桥中的静默 catch/runCatching。
- 改动（行为不变，仅让失败可观测）：
  - `Epub.kt`：资源读取失败、容器关闭失败记 warning；
  - `NativeBook.kt`：资源读取失败记 warning；
  - `WebViewChapterView.kt`：两处 `currentScrollState` JSON 解析失败、
    NGA 图片代理失败记 warning。
- 验证：Android `gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。

### 2026-08-19 android：数据层 catch-all 增加可见日志（第一批）

- 背景：Android 数据层多处 `catch (_: Exception)` 静默吞错，符合原提示词
  “名义安全、实际掩盖问题”的模式。
- 改动（行为不变，仅让失败可观测）：
  - `ContentResolver.queryDisplayName`：查询失败记 warning；
  - `Shelf.remove`：删除封面失败记 warning；
  - `Shelf.touch`：解析 last_read_at 失败记 warning；
  - `Shelf.extractCover`：提取封面失败记 warning；
  - `Settings.load`：读取失败/版本解析失败记 warning；
  - `NgaConfig.readIni`：读取失败记 warning。
- 验证：Android `gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。
- 下一步：继续清理 Epub/NativeBook/WebViewChapterView 等 catch-all。

### 2026-08-19 win：移除 server 对 ok:false 返回 dict 的兜底转换

- 背景：服务层已全部改为抛 ApiError，server 的“handler 返回 ok:false 转 400”分支成为死代码。
- 改动：
  - `server.py` 删除 dict 兜底转换，成功响应直接 `{"ok":true,"data":result}`；
  - `test_server.py` 删除 `business_error` 假方法与对应用例；
  - 结构化非错误结果（backup `errors[]` / `needs_overwrite`）仍正常作为 data 返回。
- 验证：Windows Python 301 项 OK（4 跳）。
- 说明：现在 API 错误只有一条路径——`ApiError` 异常 → server 捕获 → HTTP 错误。

### 2026-08-19 win：服务层错误改为抛 ApiError，彻底消除 ok:false 返回 dict

- 背景：handler 层已迁移到 ApiError，但 NGA / Gululu / Export 服务层仍返回
  `{"ok": false, "error": ...}` dict；本轮统一为异常。
- 改动：
  - `NgaService`：start / update_book / update_defaults 错误改为 `raise ApiError`；
  - `GululuService`：start / start_export / start_update / cancel 错误改为
    `raise ApiError`（用户取消导出仍以“已取消导出”消息抛出，前端 catch 静默处理）；
  - `ExportService`：start / open_dest / cancel 错误改为 `raise ApiError`；
  - `GululuCommentService`：入参校验错误改为 `raise ApiError`；
  - `system_api.open_data_dir` 错误改为 `raise ApiError`；
  - `backup.py` 的 `{ok:false, errors[]}` 保留为结构化非错误结果，不迁移。
  - 相关测试同步改为 `assertRaises(ApiError)`。
- 验证：Windows Python 302 项 OK（4 跳）。
- 说明：现在 `app/` 下仅 `backup.py` 仍返回 `ok:false`，且是有意保留的结构化校验结果。

### 2026-08-19 win：NGA 服务迁入 TaskManager，统一任务基础设施

- 背景：此前 NGA 使用自持 `_lock + _cancel + _status`，Gululu/Export 已用
  `TaskManager`，同一问题两套实现。用户决定迁移 NGA，统一基础设施。
- 改动：
  - `NgaService` 新增 `LANE = "network:nga"` 与 `task_manager` 注入；
  - `start` / `update_book` 改用 `TaskManager.start` + `_begin_task` + 线程 lambda；
  - 新增 `_run_managed_task`，与 Gululu/Export 对齐状态映射；
  - `_download` / `_update_core` 改为接收 `cancelled: Callable[[], bool]`，
    不再依赖 `self._cancel`；
  - `cancel()` 改为调用 `TaskManager.cancel(current_task)`；
  - `tests/test_nga_service.py` 同步更新（取消/线程捕获）。
- 文档：ROADMAP §P3 / GLOSSARY / MAINTENANCE_GUIDE 同步为“NGA 已接入 TaskManager”。
- 验证：`tests.test_nga_service` 23 项 OK；Windows Python 全量 302 项 OK（4 跳）。

### 2026-08-19 win：handler 层迁移到 ApiError 异常

- 背景：上一步已把业务错误统一到 HTTP 边界；本轮把 API handler 里的
  `return api_error(...)` 改为 `raise ApiError(...)`，让“错误”成为控制流的一部分。
- 改动：
  - `app/errors.py`：`api_error()` 替换为 `ApiError` 异常类（code/message/status）；
  - `app/server.py`：捕获 `ApiError` 并按 `status` 返回；
  - `app/api/*.py`：`nga_api` / `gululu_api` / `system_api` / `annotation_api` /
    `library` 全部改为 `raise ApiError(...)`；
  - `tests/test_api_service.py`：直接调用 handler 的错误断言改为 `assertRaises(ApiError)`。
- 验证：Windows Python 302 项 OK（4 跳）。
- 说明：服务层返回的 `{ok:false,error}` dict 仍由 server 边界兜底转换；
  后续可再逐步把服务层也改为抛 `ApiError`，但当前已消除 handler 层“返回错误 dict”的写法。

### 2026-08-19 win：API 业务错误统一到 HTTP 边界，移除 bridge 特判

- 背景：此前 bridge 根据 `payload.errors / needs_overwrite` 决定是否 reject，
  属于前端运行时特判；本轮把“业务错误”统一收敛到 server HTTP 边界。
- 改动：
  - `server.py`：handler 返回 `{ok:false,error}` 且无 `errors[] / needs_overwrite`
    时转 HTTP 400；结构化非错误结果（备份校验/覆盖确认）仍作为 200 data 返回；
  - `bridge.js`：删除 `payload.ok === false` 特判，成功响应直接返回 `data.data`；
  - `test_server.py`：新增 `business_error → 400` 与
    `structured_result → 200 data` 两条用例。
- 验证：Windows Python 302 项 OK（4 跳）；JS 守卫全绿。
- 说明：这是“彻底统一 API 错误模型”的第一步；后续可把 handler 内散落的
  `return {"ok": False, ...}` 逐步改为抛出统一 `ApiError`。

### 2026-08-19 android：reader-lite 状态机 Step 4——DisciplineTest 守卫状态机结构

- 背景：状态机收敛到一定程度后，把关键结构固化为纪律测试，防止后续回退。
- 改动：`DisciplineTest.kt` 新增测试，守卫：
  - `phase` 字段存在、`markSettled` 以 `phase === 'ready'` 判断；
  - `requestSettle` / `scheduleResize` 单一入口存在；
  - `onResize` 只转发 `scheduleResize`；
  - `refresh` 分页路径并入 `requestSettle`；
  - 禁止 `state.settled` / `resizeScrolled` 复活。
- 验证：Android `gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL。
- 下一步：reader-lite 状态机主要步骤已完成；可回到全局清理 API 错误模型 / TaskManager。

### 2026-08-19 android：reader-lite 状态机 Step 3——phase 取代 settled、删除死分支

- 背景：Step 2 后继续清理状态机冗余，让 `phase` 真正成为“已就绪”事实源。
- 改动：
  - 删除 `state.settled`，`markSettled` 改为 `if (state.phase === 'ready') return`；
  - 删除从未读取的 `state.resizeScrolled` 及对应写入；
  - `refresh()` 分页路径统一走 `requestSettle`，移除独立 rAF 分支；
  - reader-lite 体积由 37922 → 37311 字节（净减少）。
- 验证：JS 守卫全绿；Android `gradlew testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL；
  设计文档更新 Step 3 完成。

### 2026-08-19 android：reader-lite 状态机 Step 2——resize 防抖收敛到 scheduleResize

- 背景：Step 1 收敛 settle 链后，继续收敛 resize 的散落状态与定时器。
- 改动：
  - `resizeOffset` / `resizeScrolled` 从模块级变量移入 `state`；
  - 新增 `scheduleResize()` 单一防抖入口，`onResize()` 仅作转发；
  - 所有图片加载/错误、窗口 resize、insets 变更统一走 `onResize → scheduleResize`；
  - 与 `requestSettle` 的交互保持单一入口。
- 验证：JS 守卫全绿（parts 6/37922B）；Android `gradlew testDebugUnitTest --rerun-tasks`
  BUILD SUCCESSFUL；设计文档更新 Step 2 完成。
- 下一步：Step 3 清理死分支/重复路径。

### 2026-08-19 android：reader-lite 状态机 Step 1——settle 链收敛到 requestSettle

- 背景：Step 0 基线通过后，开始收敛最核心的 settle 链。
- 改动：
  - `tryRestoreAfterSettle` 重命名为 `requestSettle(offset, deadline)`；
  - 内部改为 `settleTick` 单一定时器重试，不再递归调用自身；
  - `setMode` / `onResize` / 滚动初始化 / `finish` 统一走 `requestSettle`；
  - `refresh` 在分页未就绪时并入 `requestSettle`（就绪路径保留原 rAF 流程）；
  - 保留 8s 兜底与 `userMoved` 不覆盖用户位置语义。
- 验证：JS 守卫全绿（parts 6/37664B）；Android `gradlew testDebugUnitTest --rerun-tasks`
  BUILD SUCCESSFUL；设计文档更新 Step 1 完成。
- 下一步：Step 2 收敛 resize（`scheduleResize` 单一入口）。

### 2026-08-19 android：reader-lite 状态机 Step 0——加入 phase 字段与转换日志

- 背景：设计草案获批后，先做“零行为变化”的 Step 0，为后续状态机收敛建立可观测基线。
- 改动：
  - `state` 新增 `phase`（bootstrapping / restoring / ready）；
  - `init` / `setMode` / 滚动初始化 / `tryRestoreAfterSettle` / `markSettled`
    记录 phase 转换日志；`markSettled` 将 phase 置为 ready；
  - 不改变任何进度保存、桥协议或恢复逻辑。
- 验证：JS 守卫全绿（parts 6/37492B）；Android `gradlew testDebugUnitTest` BUILD SUCCESSFUL；
  文档字节基线同步。
- 下一步：Step 1 收敛 settle 链（`requestSettle` 单定时器）。

### 2026-08-19 docs：reader-lite 状态机收敛设计草案

- 背景：开始 reader-lite.js 收敛前，先固化设计文档与不变量清单，避免直接改
  高风险进度代码。
- 产出：`docs/READER_LITE_STATE_MACHINE.md`，包含显式阶段
  （bootstrapping / restoring / ready）、转换表草案、进度保持不变量、
  小步实施步骤与验证/回滚方案；同步登记到 `docs/README.md` 文档索引。
- 状态：草案待评审；未改 reader-lite 运行时代码。

### 2026-08-19 android：架构收敛第七轮——reader-lite 集中 currentOffset 安全兜底

- 背景：`reader-lite.js` 中 5 处 `try { o = currentOffset(); } catch { }` 重复同一
  防御逻辑，噪音大且容易漏改。
- 改动：新增 `currentOffsetSafe()` 统一捕获并回退 0，5 处调用点全部改为直接调用；
  rebundle 为 6 parts / 37044 字节。
- 验证：`reader-lite-parts` / `bridge-contract` / `textpos` / `reader-session` 全绿；
  文档字节基线同步更新。

### 2026-08-19 win：架构收敛第六轮——reader.js 移除“必然存在模块”的防御包装

- 背景：`index.html` 固定加载全部前端模块，但 `reader.js` 仍到处 `if (window.X)` /
  `try { ... } catch { }`，把“模块必然存在”当成“可能不存在”，产生大量噪音。
- 改动：`web/js/reader.js` 删除围绕 `CodeHighlight` / `Annotations` / `Gululu*` /
  `FullSearch` / `Stats` / `ViewMenu` / `SettingsPage` / `Paged` 的窗口存在性检查与
  冗余 try-catch；`toggleBookmarkAtCurrent` 删除“模块未加载”死分支。
- 验证：`node --check web/js/reader.js` 通过；`reader-lite-parts` / `bridge-contract` /
  `textpos` / `reader-session` JS 守卫全绿。

### 2026-08-19 android：架构收敛第五轮——ATOMIC_MOVE 回退不再静默

- 背景：`data/Storage.kt::moveReplace` 原子移动失败时静默回退普通替换，掩盖文件系统异常。
- 改动：回退前记 `warning`（含原因），行为不变。
- 验证：`android\gradlew.bat -p android testDebugUnitTest --tests "...data.StorageTest"`
  BUILD SUCCESSFUL。

### 2026-08-19 win：架构收敛第四轮——Settings 类型异常不再静默丢弃

- 背景：Windows `Settings.load/update` 对类型不匹配的字段直接忽略，没有任何日志，
  属于“名义安全、实际掩盖配置损坏”的静默分支。
- 改动：`app/settings.py` 对每个被忽略的字段记 `warning`（字段名 + 实际类型），
  行为不变（仍用默认值），但问题可观测。
- 验证：`tests.test_settings` 3 项 OK。

### 2026-08-19 win：架构收敛第三轮——GululuService 启动/任务包装去重

- 背景：第二轮统一前端错误路径后，继续收敛 Windows 后端控制流碎片：`GululuService`
  三个 start 方法复制同一份状态初始化，三个 `_run_*_task` 只是转发 lambda。
- 改动：
  - 新增 `_begin_task(...)` 私有方法，`start` / `start_export` / `start_update`
    共用任务状态初始化；
  - 删除 `_run_task` / `_run_export_task` / `_run_update_task` 三个转发方法，
    thread target 直接内联 `_run_managed_task`；
  - 线程创建显式传 `args=()`，兼容测试中的 `_ImmediateThread` 构造约束。
- 验证：`tests.test_gululu_service` 14 项 OK；Windows Python 全量 300 项 OK（4 跳）。

### 2026-08-19 win：架构收敛第二轮——统一前端业务错误路径（bridge reject 内层 ok:false）

- 背景：第一轮已统一 HTTP 层错误响应；本轮把“handler 返回 `{ok:false,error}` 但 HTTP 仍 200”
  的业务错误也统一为 `Bridge.call` reject，消除前端散落的 `if (!r.ok)` 分支。
- 改动：
  - `web/js/bridge.js`：`callHttp` 对 `data.data.ok === false` 且无
    `errors[]` / `needs_overwrite` 的结果统一 `throw`；`{errors:[...]}` 与
    `{needs_overwrite:true}` 仍原样返回（备份校验/覆盖确认不是异常）；
  - `web/js/nga_download.js`：`ngaStartDownload` / `exportStart` / `ngaUpdateBook`
    去掉 `!r.ok` 分支，错误统一走 catch；“已有任务”的轮询恢复逻辑移入 catch；
  - `web/js/gululu-download.js`：`gululuStartImport` / `gululuStartUpdate` /
    `gululuStartExport` 同样收敛；用户取消文件夹选择按“已取消”静默返回；
  - `web/js/app.js`：`showReader` 删除已死的 `data.error` 分支；
  - `web/js/gululu-comments.js`：删除已死的 `response.ok === false` 分支。
- 验证：`node --check` 五个文件通过；`reader-lite-parts` / `bridge-contract` /
  `textpos` / `reader-session` JS 守卫通过；`tests.test_api_contract` +
  `tests.test_server` 38 项 OK。
- 注意：本轮只改前端错误路径，后端 API 方法清单未变；`api-contract.test.js`
  仍需在可 spawn Python 的 CI 环境跑。

### 2026-08-18 win/android/docs：架构审查后第一轮收敛（EventBus→显式回调 / API 400 语义 / reader-lite 冗余 try-catch）

- 背景：对全仓做 vibe-coding 式防御/过度抽象审查后，先清理三处低风险高噪音问题；
  P5 功能批次（C/D/E/F）尚未开始，本提交不改变用户可见功能。
- Windows：
  - 删除 `app/events.py` 的 `EventBus`：全仓只有 `book_updated` 一个订阅点，
    改为 `NgaService` / `GululuService` 构造参数 `on_book_updated` 显式注入，
    `main.py` 装配；同步删除 `tests/test_events.py`，更新 `docs/GLOSSARY.md`；
  - `server.py` 错误响应统一带 `{ok:false,error}`；handler 抛 `TypeError/ValueError`
    时返回 400（业务校验/入参错误）而非 500；`api/reader.py::save_progress` 不再
    静默吞掉非法入参，改由 HTTP 边界显式 400；
  - `test_server.py`：`boom` 改用 `RuntimeError` 保持 500 语义，新增 `bad_request`
    400 用例。
- Android：
  - `reader-lite.parts` 删除 6 处 `try { log(...) } catch { }`——`log()` 内部已由
    `callBridge` 兜底，外层 try-catch 是纯噪音；重新 bundle 为 6 parts / 37178 字节。
- 测试：Windows Python 300 项 OK（4 跳）；`reader-lite-parts` / `bridge-contract`
  JS 守卫通过；`api-contract` 启动依赖 Python 子进程，沙箱内无法跑，留待常规 CI。
- 注意：Android 发行前需重新打包并校验 APK 内 `reader-lite.js` SHA-256。

### 2026-08-18 win/android：P5-B NGA 表情/图片裂图修复（代理 + 失败占位）

- 背景：用户反馈 NGA 平台表情图片显示错误（裂图）。根因：Windows 在线模式
  iframe 直连图床，NGA 防盗链需 Referer/Cookie → 403。
- Windows：
  - `server.py` 新增 `/img/<book_id>?u=<url>` 代理路由：NGA 图床域名白名单
    （`nga.178.com` / `nga.cn` / `ngabbs.com`，显式集合）、带 Referer 与已存
    Cookie、未注册 book 404、非白名单 400、拉取失败 502；
  - 章节输出时 `_rewrite_nga_image_src` 把 NGA 图床 `src/poster` 重写为本地代理
    （只改属性，不影响 text_offset）；
  - `reader.js` 图片 error 监听由“隐藏”改为替换为 `data-textpos-exclude` 占位卡，
    `reader.css` 加 `.img-error-placeholder` 样式。
- Android：
  - `reader-lite.parts/20-textpos.js` 的 `isSkipNode` 增加
    `data-textpos-exclude` 祖先跳过（与桌面对齐）；
  - `40-layout.js` error 监听把失败图片替换为无文本节点占位（文案走 CSS
    `::after`），`reader.css` 加占位样式；
  - 重新 bundle（6 parts / 37375 字节），parts/bridge 契约通过。
- 测试：`test_server.py` 新增 6 例（白名单拒绝/404/缺 u/头注入/502/重写）；
  Windows Python 302 项 OK（4 跳）；Android JVM BUILD SUCCESSFUL；JS 契约全绿。
- 文档：`docs/ARCHITECTURE.md` 路由表与数据流补 `/img/` 代理说明。
- 注意：改 JS 后 APK 内 `assets/reader/reader-lite.js` 需重新打包并校验
  SHA-256（本机已 bundle，发行前按 check-release 校验）。

### 2026-08-18 docs：P5 批次后文档漂移扫描与同步

- 扫描发现 P5-A 合入后测试计数 287→296、ROADMAP §2.2 行数表 / §2.1 主干状态 /
  DevLog §1 / MAINTENANCE_GUIDE §7/§10 未同步；按 AGENTS §5 清单修复。
- 同步：DevLog §1、ROADMAP 顶部核对块 / §2.1 / §2.2、MAINTENANCE_GUIDE §7/§10；
  新增 `.zcode/` 到 `.gitignore`（本地工具产物）。
- 验证：Windows Python 296 项 OK（4 跳）；漂移脚本复跑一致。

### 2026-08-18 win/android：P5-A 快赢批实施（骨碌碌链接提取 / 书名前缀隐藏 / Windows 书架重命名）

- 子项 1（骨碌碌链接提取）：`app/gululu_source.py` 新增 `extract_book_id`，
  用 `re.finditer` 从任意文本提取首个骨碌碌链接/裸 ID，多个链接时报
  ValueError；`parse_book_id` 保持不变（仍作 client 层严格二次校验）。
  `gululu_service.py` 三处 `parse_book_id(source)` 改为 `extract_book_id`；
  `web/js/gululu-download.js` `parseBookId` 改为 search 模式。测试 6 例
  （带前缀提取/多链接拒绝/无链接拒绝）全绿。
- 子项 2（书名前缀隐藏）：新设置 `hide_title_brackets`（默认关，走契约流程）。
  Windows `settings.py` DEFAULTS + `bookshelf.js` `displayTitle` helper
  替换 9 处显示用 `book.title`（搜索 `dataset.title` 与删除确认保留原名）；
  Android `Settings.kt` 三处 + `BookshelfScreen.kt` 两个 Composable 加
  `hideBrackets` 参数替换 5 处显示用引用（导出文件名 `safeExportName` 保留
  原名）；`SettingsReadingPanels.kt` "界面"小节加 Switch 开关；DATA_CONTRACT
  §4 表格同步。剥离规则：`^【[^】]*】` 仅剥首个【…】段。无需 bump
  settings_version。Android `compileDebugKotlin` + `testDebugUnitTest` 全绿。
- 子项 3（Windows 书架重命名，对齐 Android `renameBook`）：`native_book.py`
  新增 `rename_title`；`api/library.py` 新增 `rename_book` handler（EPUB 仅
  改书架记录，原生书目录额外写 meta.json 容错，空标题/同名不写盘）；
  `api/__init__.py` 注册 + `api-client.js` METHODS + `bridge.js` MOCKS +
  `bookshelf.js` 重命名按钮（prompt 弹窗）+ `icons.js` 补 edit 图标。
  测试 3 例（重命名成功/空标题同名 noop/书不存在）全绿；JS 契约 53 方法一致。
- 验证：Windows 68 项 OK、JS 契约全过、Android JVM 全绿。

### 2026-08-18 docs：用户 issue 转化为 P5 开发批次（ROADMAP 扩展）

- 背景：收到用户反馈 issue（书架 3 条：封面缺失/书名前缀挤占/手机版优先；
  NGA 4 条：凭据门槛/图片裂图"致命"/滚动自动翻章/楼中楼；骨碌碌 1 条：
  链接前缀）。全部条目先对照代码核实现状，再按项目纪律（成功标准 + 验证
  方式 + 涉及文件 + Diff 影响检查）转化为 ROADMAP §3 P5 批次（六个子项
  A–F），并更新 §4 执行顺序（P5 为当前批次）。
- 核实结论（关键事实）：
  - 骨碌碌 EPUB 不生成封面（`gululu_epub.py` 无 cover）；双端 `cover_rel`
    机制已有但无自定义入口；Android 已有重命名、Windows 无；
  - Windows 无图片代理：在线图片模式 iframe 直连 NGA 图床缺 Referer →
    403 裂图（根因）；Android 已有代理但失败无可见占位；
  - NGA 楼中楼数据管道全通（`Floor.comments` 递归 / `analyze_floors` 已解析
    响应自带 comments / floors.json 已存），但双端渲染均未画、服务层无收集
    参数；
  - 骨碌碌 URL 解析仅接受纯 URL/ID，带"点击链接阅读："前缀报错；
  - 双端均无滚动到底自动翻章。
- 排序：A 快赢（链接提取/书名前缀隐藏/Windows 重命名）→ B 裂图修复
  （用户标致命，Windows 代理 + 双端失败占位）→ C 自动翻章（涉进度铁律，
  必跑回归）→ D 封面系统 → E1 Cookie 粘贴解析（与 A 并行）→ F 楼中楼
  （最大件，text_offset 红线：评论只随楼层首次写入，永不回填）。
- 产品原则采纳：用户可见功能 Android 先行或双端同步交付（issue 第 3 条）。
- 未改代码；新增 `hide_title_brackets` / `auto_chapter_turn` / `collect_comments`
  设置与 meta 字段均列为"改动时走契约流程"，本条不触碰契约。

### 2026-08-18 infra：剩余 Dependabot PR 全部按类型合并完成

- 按依赖类型继续合并剩余 7 个 Dependabot PR：
  - pip：#4 setuptools、#8 pyinstaller、#10 pillow；
  - Android Gradle：#2 androidx.test.ext:junit、#3 kotlinx-serialization-json、
    #6 activity-compose、#11 okhttp。
- 处理：#8 首次 rebase 后 Python 3.14 出现 `test_api_requires_token` 瞬时
  `ConnectionAbortedError`，重跑后通过（与依赖升级无关，属 CI socket flake）。
- 结果：所有 Dependabot 依赖升级 PR 已全部 squash 合并，open PR 列表为空。

### 2026-08-18 infra：Dependabot CI 修复与按类型分批合并

- 背景：Dependabot PR #1 / #5 / #7 / #9 / #12 出现 CI 失败。
- 处理：
  - #1 / #5 / #7 / #12（GitHub Actions）：失败原因为 Dependabot 分支基于旧版
    `android.yml`（`node android/scripts/...` 双路径），`@dependabot rebase` 后修复；
  - #9（org.jsoup → 1.23.1）：真实解析行为变更，代码修复（见下一条）后 rebase 通过；
  - 按依赖类型分批 squash 合并：GitHub Actions（#1 / #5 / #7 / #12）、
    Android Gradle（#9）。
- 验证：合并后各 PR 的 build / contract-guard / test-and-package 全绿。
- 剩余未合并 Dependabot PR：#2 / #3 / #4 / #6 / #8 / #10 / #11（待确认 CI 后按类型处理）。

### 2026-08-18 android：适配 jsoup 1.23.1 自闭合 script 解析行为（Dependabot #9 CI 修复）

- 现象：Dependabot PR #9（org.jsoup 1.19.1 → 1.23.1）Android CI 失败，
  `ReaderHtmlTest.sanitizeKeepsContentAfterSelfClosingScript` 红。
- 定位：HTML5 中 `<script>` 非 void 元素，`<script .../>` 会被 jsoup 1.23.1
  按未闭合开始标签解析并吞掉后续正文；旧版 jsoup 视作自闭合、保留后续内容。
- 修复：`ReaderHtml.sanitizeReaderBody` 清洗前先把 `<script .../>` 归一化为
  `<script></script>`，避免后续正文被误删；现有回归用例在 1.19.1 / 1.23.1 下均绿。
- 验证：临时升 1.23.1 复现红 → 修复后绿 → 还原版本至 1.19.1；全量 Android JVM
  BUILD SUCCESSFUL。
- 依赖升级本体由 PR #9 承载，已请求 rebase。

### 2026-08-18 docs：落实动效审查标准（ANIMATION_STANDARDS）

- 背景：评估 `react-bits` 分析报告后，决定先落实其 `review-animations` 的动效质量
  规则（纯规则、零依赖、零许可风险），暂不移植其组件代码。
- 处理：
  - 新增 `docs/ANIMATION_STANDARDS.md`：硬性规则（只动 transform/opacity、UI ≤300ms、
    支持 prefers-reduced-motion、禁 transition:all、禁无理由 scale(0)/ease-in、
    悬停配 `(hover:hover)`）、阅读器 text_offset 专项、循环/背景动画暂停与停止、
    新增/修改动效检查清单、来源与许可说明；
  - `AGENTS.md` §5 新增「动效纪律」条目，漂移清单纳入 `docs/ANIMATION_STANDARDS.md`；
  - `MAINTENANCE_GUIDE` §6 补第 10 条动效纪律；
  - `docs/README.md` 索引补登记该现役文档。
- 验证：纯文档改动；`scripts/check-doc-drift.ps1` 复跑；未跑构建。
- 后续：如需移植具体效果（如 Aurora 背景），按文档 §5 走「重写实现 + THIRD_PARTY_NOTICES 登记」流程。

### 2026-08-18 docs：文档层级与重叠治理（归档历史文档 + 文档索引 + REVIEW_ACTION_PLAN 指针 + 权威事实源）

- 背景：审查仓库层级与文档重叠，确认 `docs/` 平铺且历史快照与现役规范混放、
  REVIEW_ACTION_PLAN 与 ROADMAP 待办重复、版本/测试基线多源复述。
- 处理：
  - 新建 `docs/archive/`，移入 8 份历史 Android/规划文档
    （ANDROID_CODE_REVIEW / PERFORMANCE_REVIEW / SECURITY_REVIEW / UI_PLAN /
    NATIVE_RENDERER / READER_REFERENCES / M4_ACCEPTANCE / NGA_READER_PLAN）
    及旧 REVIEW_ACTION_PLAN 全量；
  - 新增 `docs/README.md` 文档索引（现役 / 日志 / ADR / 归档分类 + 职责 + 维护约定）；
  - `docs/REVIEW_ACTION_PLAN.md` 改为**指针文档**，P0–P4 唯一基线收敛到
    `ARCHITECTURE_ROADMAP.md`；
  - 事实源收敛：README 版本表 = 版本线文档权威、MAINTENANCE_GUIDE §7 = 测试基线
    文档权威、ROADMAP = 待办唯一基线；DevLog §1 / ROADMAP §2.1 补权威指针；
  - 同步引用链接：README / SECURITY / LESSONS_LEARNED / ANDROID_DESIGN_TOKENS /
    DevLog 指向 `docs/archive/`；
  - `AGENTS.md` 漂移清单纳入 `docs/README.md` 与 `docs/archive/` 归档纪律；
  - `MAINTENANCE_GUIDE §12` 补「新增/移动文档」速查行。
- 验证：`git mv` 移动 9 文件、引用链接更新；`scripts/check-doc-drift.ps1` 复跑；
  纯文档改动，未跑构建。

### 2026-08-18 docs/win：文档漂移治理强化（显式检查清单 + 半自动扫描脚本）

- 背景：08-18 维护者补做「全仓库非归档文档漂移扫描与修复」后，复盘发现现有治理
  规则（AGENTS.md §5）粒度太粗、纯人工、无自动化，是漂移反复出现的根因。
- 处理：
  - `AGENTS.md` §5「文档漂移检查」扩为**高漂移检查清单**，显式列出
    DevLog §1/§4/§5、ARCHITECTURE_ROADMAP 顶部核对块与 §2.1/§2.2/§2.3/§3、
    MAINTENANCE_GUIDE §1/§7/§10/§11、README/CHANGELOG/SECURITY/使用说明/
    VERSIONING/contracts README/CODEBASE_MAP/GLOSSARY/DATA_CONTRACT/
    ANDROID_ARCHITECTURE/nga-post-template.bbcode；
  - 新增 `scripts/check-doc-drift.ps1`：输出 HEAD/分支/工作区/CI 清单 +
    高漂移文档快照（DevLog §1、ROADMAP §2.1/§2.2、MAINTENANCE_GUIDE §7），
    可选 `-RunTests` 实跑测试计数，供收尾人工核对；
  - `CONTRIBUTING.md`：提交/PR 清单补「已跑文档漂移扫描」项，并指向脚本。
- 验证：`powershell -ExecutionPolicy Bypass -File scripts/check-doc-drift.ps1`
  正常运行输出快照；纯文档/脚本改动，未跑构建。
- 推送前自检：发现并同步三处新引入漂移——MAINTENANCE_GUIDE §6/§10/§12 补引用新脚本、
  DevLog §1 当前状态补记治理强化、ARCHITECTURE_ROADMAP 顶部核对块补记本次推进；
  随后 `check-doc-drift.ps1` 复跑输出与仓库状态一致。
- 收敛递归：`AGENTS.md §5` 增加「治理固定点（停止递归）」条款——清单与脚本是检查
  固定点，不为它们编写元文档；`check-doc-drift.ps1` 新增 `Governance wiring check`，
  自动检查 AGENTS / CONTRIBUTING / MAINTENANCE_GUIDE 是否仍引用本脚本。

### 2026-08-18 docs：全仓库非归档文档漂移扫描与修复

- 背景：上一条修了 DevLog §5 延后项清单漂移后，按 AGENTS.md「文档漂移检查」纪律
  对全部非归档文档做一次系统扫描，用 `git rev-parse HEAD`、最新测试计数、
  `wc -l` 文件行数、`.github/workflows/*.yml` 清单逐一对照文档声明。
- 实跑测试获取真值：Windows Python 287 项（3.14：1 error + 4 跳，error 为
  `test_main_guard` 平台敏感项；bundled 3.12 全过）；JS 契约 textpos 15 /
  api-contract 52 / bridge v1 / reader-lite-parts 6 parts·36917B 均过。
- 修复的漂移项：
  - DevLog §1 当前状态：日期 08-16→08-18；测试计数 283→287；「待发布 v1.4.0」
    →「已发布」；补记五批接手风险修复已合入。
  - ARCHITECTURE_ROADMAP §2.1：Windows Python 280→287；主干状态 `4b77ded`→
    补记 v1.4.0 发布与五批修复；JS 契约补 `bridge-contract` +
    `reader-lite-parts` 两条。
  - ARCHITECTURE_ROADMAP §2.2：文件行数表全量更新（WebViewChapterView 605→645、
    NativeBook 562→635、BookshelfScreen 591→604、Epub 512→569、SettingsScreen
    550→564、SearchScreen 541→565、NativeReaderScreen 428→476、reader-lite.js
    977→1038、reader.js 655→761、nga_service.py 524→586、reader.css 1683→2125 等）。
  - ARCHITECTURE_ROADMAP 顶部核对块：补 2026-08-16 v1.4.0 发布与 2026-08-17/18
    五批修复的推进记录（历史核对链保留不改写）。
  - MAINTENANCE_GUIDE §7：测试基线 283→287、日期 08-15→08-18；
    §10 当前状态：补五批修复、真实待办收敛。
- 确认无漂移的项：README 版本表/系统要求、CHANGELOG、SECURITY、使用说明.txt、
  contracts/README、DATA_CONTRACT、ANDROID_ARCHITECTURE、CODEBASE_MAP、
  VERSIONING、ADR、AGENTS（均用 `vX.Y.Z` 变量形式）、GLOSSARY、
  NATIVE_BOOK_FORMAT、TEXT_NORMALIZATION_SPEC、ANDROID_DESIGN_TOKENS。
- 归档不改写：REVIEW_ACTION_PLAN 的「111 过 / 230 项」是 `ab3c6d8` 时点快照、
  ANDROID_CODE_REVIEW/PERFORMANCE_REVIEW 已有 2026-08-13 核对标注，均属历史
  审查记录，保留原文（2026-08-18 起统一存放于 `docs/archive/`）。

### 2026-08-18 docs：DevLog 第 5 节延后项清单同步真实状态（文档漂移修复）

- 背景：核对 DevLog 第 5 节"待办与延后项"与路线图 §3、代码实际状态，发现 P1/P2
  多项已落地但仍标"剩余"，属文档漂移，会误导接手者高估工作量。
- 核对结论（均经代码验证）：
  - P1 三项"剩余"（桥协议+进度回放、依赖锁定、首批 ADR）**全部已完成**；
  - P2 三项（jsoup 清洗、Android 错误模型、诊断闭环）**全部已完成**；
  - P3 中大文件拆分/存储恢复/开源治理**大部分已完成**；
  - 真实待办仅剩：Android 数据完整性校验入口（Windows 已有，Android 缺）；
    其余大文件拆分 / Android NGA 迁 TaskManager / P4 保持延后。
- 处理：重写第 5 节为"已完成"与"真实待办"两组，归档已完成项，标注延后理由。
  未改代码、未改数据契约。

### 2026-08-16 win/docs：骨碌碌阅读交互第九轮（评论排序折叠 / 重置拆分 / 面板外观统一）

- 评论排序与折叠：段落评论组按**正文段落先后次序**排列（`paragraphOrder`），同段落内
  评论按时间**新在前**；楼层评论（无段落）置后；段落评论组默认 **`<details>` 折叠**，
  手动点击 summary 或从正文徽标点击展开对应组。
- 评论入口：徽标仅跳过迷雾未解锁块（`.gululu-fog-block.gululu-fog-hidden`），折叠
  details 内段落也挂徽标（展开后可见可点）。
- 重置拆分：更多菜单重置选项拆为**重置本章骰点 / 重置全书骰点 / 重置本章秘密线索 /
  重置全书秘密线索**（assistant 新增 `resetChapterDice/resetAllDice`，secrets 新增
  `resetChapterSecrets/resetAllSecrets`）。
- 解锁位置保持：`revealGroups`（含 revealAll/整楼揭示）后 `restoreReadingPosition`
  （rAF 后 seekToOffset 当前 offset），避免迷雾显隐导致布局变化后进度错位。
- 外观统一：评论抽屉加圆角（16px 左侧）与 `gululu-pop-in` 动画，与其他悬浮面板一致；
  背景切图淡入补强制 reflow（`void layer.offsetWidth`），确保 opacity 过渡生效。
- 设置持久化核查：`app/settings.py` load/save 原子写 + 类型校验，前端各面板
  `Api.saveSettings` 链路完整；未发现丢失问题（如有具体场景需复现）。
- 验证：formal 冒烟（含段落组折叠断言/逐楼评论）桌面+430px 全过；verify-63299 /
  verify-comments / verify-toast 全过。未改 Android、双端 JSON 契约。

### 2026-08-17 win/android/docs：正式接手深潜风险修复第一批（搜索/诊断/任务/前端 secrets/Android 一致性/文档漂移）

- 背景：全面交接调研后接手，按风险优先级处理第一批问题；全部先加复现用例再修复。
- Windows 后端：
  - 搜索统计口径：`search._count_hits` 改用与 `_iter_hits` 一致的可重叠扫描计数，
    修复 `"aaaa"` 查 `"aa"` 时 `chapter_hits/total_hits` 少计（`tests/test_search.py`
    新增 2 例：重叠计数、emoji 后 `search_more` 不重复返回）；
  - `search_more` 代理对边界：`after_offset` 先换算码点索引再 +1，避免上次命中为
    emoji 时 +1 落在代理对中间被拉回同一字符；
  - 诊断脱敏：`app/diagnostics.py` 新增递归敏感键打码（cookie/passport/password/
    secret/token/credential/session/auth/cid/uid/key/salt），settings.json 不再原样打包
    （`tests/test_diagnostics.py` 新增 1 例）；
  - 任务失败详情：`app/tasks.py` 新增 `error(task_id)` 保留最近一次异常，
    新接入方不再只能拿到 FAILED 状态（`tests/test_tasks.py` 新增 1 例）。
- Windows 前端：
  - `gululu-secrets.js`：线索映射改用 null-prototype 防 `__proto__` 标题被原型 setter
    吞掉；`clueFor` 改 hasOwnProperty；章节 click 委托改为换章先解绑/同文档去重，
    避免监听叠加（新增 `tests/js/gululu-secrets.test.js` 3 例，Node 桩环境）。
- Android：
  - 图片 URL 归一化统一：`NgaFormatHtml.normalizeImageUrl` 成为唯一权威
    （剥 `.thumb/.medium` + 解析 `./` 与 `//`），`NgaDownloader` 改为委托同一函数，
    修复 embedded 模式下载文件名与渲染查找不一致（`NgaFormatHtmlTest` 新增 2 例）；
  - WebView 在线图片代理响应流：返回流包 `FilterInputStream`，流关闭时同步释放
    OkHttp Response，避免连接泄漏；
  - `Settings.load` 损坏 JSON 不再静默回默认：调用 `isolateCorrupt` 隔离原文件并记日志，
    与 `readJsonStore` 风格一致（`SettingsTest` 新增 1 例）。
- 文档漂移：
  - `docs/ANDROID_ARCHITECTURE.md`：字体路径改为仓库根 canonical 源经 Gradle 并入；
  - `docs/ARCHITECTURE_ROADMAP.md` 2.3：API 人工同步债标记为已解决（52 方法 + 自动对照）；
  - `docs/DATA_CONTRACT.md`：`custom_font` / `shortcuts` 补 Android 实际缺省值说明。
- 验证：Windows Python 287 项 OK（4 跳，含新增 4 例）；Android JVM BUILD SUCCESSFUL；
  Node JS（secrets 3 例 / reader-session / textpos 15 / api-contract 52 / bridge v1 /
  reader-lite parts 6）全绿。
- 未处理/延后（记入风险清单）：Stats `sessions` 语义双端均为“每 60s 上报计 1 次”，
  属既有共享行为，需产品决策后再统一；`native_book.append_container` 跨文件非事务、
  `instance_guard` PID 复用误杀面、bridge 内层 ok 不 reject、超时不取消 fetch、
  Android mixed content 安全面、EPUB 导出本地图缺失等留待后续批次。
- 提交：分 `win:` / `android:` / `docs:` 三个本地提交，未推送。

### 2026-08-17 win：正式接手深潜风险修复第二批（桥超时取消 fetch / 后台索引失败日志）

- `web/js/bridge.js`：HTTP 桥超时改为 `AbortController` 真正取消底层 fetch
  （此前超时只 reject、请求仍悬空），错误文案保持 `Bridge call <name> timed out after <ms>ms`；
  `withTimeout` 封装移除，MOCKS 路径不变。
- `app/api/common.py`：`spawn_index` 后台建索引失败从静默 `pass` 改为 `log.exception`，
  保留失败上下文，不再吞错。
- 验证：Python 定向 31 项 OK；`node --check web/js/bridge.js` + JS 契约
  （secrets / reader-session / textpos 15 / api-contract 52 / bridge v1 / parts 6）全绿。
- 未处理/延后同上批清单（bridge 内层 ok 不 reject 仍为设计保持，超时取消已补）。

### 2026-08-17 android：正式接手深潜风险修复第三批（EPUB 导出内嵌图片入包 / 下载图片失败日志）

- `EpubExporter`：新增 `imagesDir` 参数，embedded 图片随导出 EPUB 入包
  （`EPUB/images/<name>` + OPF manifest `<item>`）；章节内
  `file:///android_images/<bookId>/<name>` 引用改写为相对 `images/<name>`，
  外部阅读器不再丢图（`EpubExporterTest` 新增 1 例）。
- `NgaExport`：`epubBytes` 透传 `imagesDir`，新增 `imagesDirFor(context, bookId)`
  定位下载器同一落盘目录；书架与已下载页两处导出调用同步传入。
- `NgaDownloader.downloadImages`：单图下载失败从静默 `runCatching` 改为
  `LogEvents.event("nga","image_download_failed",...)`，保留诊断痕迹；
  仍不中断整本下载（本地缺图回退在线 URL 语义不变）。
- 验证：Android JVM 全量 BUILD SUCCESSFUL（含 EpubExporter 新用例）。

### 2026-08-17 docs：接手风险修复第四批（ARCHITECTURE_ROADMAP 架构债表状态同步）

- `docs/ARCHITECTURE_ROADMAP.md` 2.3 已确认架构债表按 P1–P3 实际完成情况补状态：
  桥协议无版本 / 正则 HTML 清洗 / 依赖不可复现 / 重复大文件 / 治理文件缺失 /
  编码损坏 / 文档膨胀均标记已解决，静默失败与防御式代码标记核心已解决/部分解决，
  避免接手者把历史快照当现状误读。
- 验证：纯文档改动，未跑构建。

### 2026-08-17 android：接手风险修复第五批（重复实现收敛：NgaConfig 原子写 / queryDisplayName）

- `NgaConfig`：移除私有 `atomicWrite` 弱化实现（renameTo 兜底），统一复用
  `Storage.atomicWriteText`（临时文件 + ATOMIC_MOVE + 回退），凭据文件写入一致性对齐。
- `queryDisplayName`：AppContainer / SettingsScreen / SettingsReadingPanels 三处重复
  实现收敛为 `data/ContentResolver.kt` 共享 internal 函数，消除 SAF 文件名查询漂移。
- 验证：Android JVM 全量 BUILD SUCCESSFUL。

### 2026-08-16 win/docs：骨碌碌阅读交互第八轮（评论排序 / inline 移除 / 活跃区实时 / 悬浮层级统一）

- 评论排序：评论楼层按章节 `floorIds` 顺序排列（API 分批返回顺序不稳定）；
  楼层内评论按 `created_at` 时间升序（段落分组内同样有序）。
- 移除楼末折叠（inline）显示模式：删除评论面板「显示」下拉、ViewMenu「评论显示」选项
  与相关 JS 绑定；评论统一为侧边面板展示（此前 panel 与 inline 可能同时出现）。
- 活跃中实时增强：`GululuImmersive.snapshot()` 增加 `musicTitle/musicFloor`；
  总览「活跃中」区块显示**具体歌名 + 楼层**、当前氛围背景**缩略图预览**，
  面板打开期间每秒自动刷新（关闭停止）。
- 悬浮层级与动画统一（重新梳理）：
  - 层级盘点：正文层 0/1/12 → 快捷轨 43 → 评论抽屉 44 → 沉浸气泡 45 → 总览气泡 46 →
    音乐 toast 49 → view-menu 210 → 模态 300 → 灯箱 500；面板打开覆盖快捷轨（子页面
    语义），互斥网络保证同一时刻单一弹层；
  - 动画统一：`gululu-pop-in`（淡入+上浮 180ms）应用于 immersive / overview /
    dice 菜单 / 更多菜单 / 设置面板，全部悬浮入口打开动画一致；快捷按钮加
    `:active` 按压缩放反馈；
  - 实测三个面板动画均生效（`animation-name: gululu-pop-in`）。
- 验证：formal 冒烟（桌面+430px）+ verify-63299 + verify-comments + verify-toast 全过。
  未改 Android、双端 JSON 契约。

### 2026-08-16 win/docs：骨碌碌阅读交互第七轮（零漂移 + 骰点菜单 + 总览精简 + 动效）

- 评论/进度零漂移（63299 复现）：
  - 视口采样可能落在**空白文本节点**（段落间空白，无渲染盒子 rect=0）→
    `seekToOffset`/`restoreOffset` 用 `skipBlankPoint`（按 textCtx.ranges 顺序跳过
    纯空白，找下一个可见文本）定位；移除把位置拉回开头的错误兜底；
  - 滚动模式评论抽屉开合**保持 scrollTop 像素**（正文宽度变化必然重排，不做 offset
    定位——采样点 x=列中线与字符 x 不一致会逐次累积漂移）。63299 反复开关 5 次
    offset/scrollTop 完全恒定（1141/1000）；分页模式仍走
    `beginViewportResize`+`onResize`（146483→146483）。
- 骰点遮罩内评论：`.gululu-fog-hidden { display:none }` 使隐藏段落 rect=0；
  评论徽标**只挂当前可见段落**（`getClientRects().length` 检查），跳转目标不可见时
  退化为定位楼层开头（符合"只加载已展开段落"语义）。
- 骰点解锁气泡：一级 reveal-dice 按钮改为弹出气泡菜单——**解锁下一组**（新增
  `GululuAssistantReader.revealNextOne`）与**解锁本章全部**（`revealAll`），纳入互斥网络。
- 总览精简：**折叠不再纳入沉浸内容**（仅保留秘密）；音乐条目**按 cue 去重**
  （自动+手动同元素只记一次，标注 kind）。
- 动效：悬浮气泡（immersive/overview/dice）打开加 `gululu-pop-in` 淡入上浮 160ms；
  正文图片加载完成淡入（`.gululu-img-loaded` + 0.3s fade），图片间过渡不再生硬。
- 验证：formal 冒烟（含骰点菜单/全部解锁用例）桌面+430px 全过；repro-drift 零漂移；
  verify-comments（锚定 gap 8.3px/重复稳定）+ verify-63299（4 项）+ verify-toast 全过。
  未改 Android、双端 JSON 契约。

### 2026-08-16 win/docs：骨碌碌阅读交互第五轮（评论锚定坐标系修复 + 骰点一键解锁 + 总览活跃高亮）

- 评论锚定终极修复（63299 插桩定位）：
  - 跨 iframe 的 `getBoundingClientRect` 对 iframe 内元素返回 **iframe 内容坐标**
    （frameTop 变化时 charTop 恒定），`Reader.seekToOffset` / `restoreOffset` 原先
    误当宿主视口坐标直接赋 scrollTop → 定位偏差且随重复点击累积。修复：换算加
    `frame.getBoundingClientRect().top + scrollTop`（结果与当前滚动无关，绝对定位）。
    63299 实测首个可见字符 gap 0.32px，重复点击 5 次 scrollTop 恒定。
  - `paragraphOffset` 跳过段落前导折叠空白（纯空白文本节点无渲染盒子，rect=0 会把
    位置拉回开头）；去掉 `seekToOffset(0)` 错误兜底。
- 段落评论显示确认：63299 真实 API 评论 paragraphId（77733141 等）与正文段落
  `data-paragraph-id` 一一对应（14 条评论含楼层级 0）；面板段落分组与正文徽标
  （9 个）端到端验证通过。
- 音乐控件 UI 规范：`.gululu-music-progress-row` / `.gululu-music-toggle` 样式
  对齐现有 `.gululu-control-row` 系列（min-height/边框/accent-color）。
- 一键解锁本章全部骰点：`GululuAssistantReader.revealAll()` + 总览「阅读解锁」区
  「一次性解锁本章全部骰点（N）」按钮。
- 总览活跃高亮：`scan` 读 `GululuImmersive.snapshot()`（playing/backgroundUrl/effect），
  顶部「活跃中」摘要区块 + 播放中音乐条目 / 显示中背景缩略图 / 生效视效条目高亮
  （`.gululu-overview-active`，primary 色）。
- 滚动空白：长章节（63299 第 2 章 338145px）滚动到底 `reachedEnd=true`、
  iframeH=docH 精确无裁剪；若用户仍见空白需提供具体书/页复现。
- 验证：formal 冒烟 + verify-comments（锚定 gap 0.32px/重复稳定）+ verify-63299
  （骰点/折叠/总览/分页保位）+ verify-toast（气泡/停止/滚动底）全过；Windows Python
  283 项 OK。未改 Android、双端 JSON 契约。

### 2026-08-16 win/docs：骨碌碌阅读交互第四轮（评论锚定稳定 / 音乐气泡 / 滚动空白）

- 评论跳转锚定（63299 真实书插桩复现）：
  - `togglePanel` 只在面板开合状态**实际变化**时调 `setReaderShrink`（已开再打开不再
    重复定位，消除二次重排漂移）；
  - 段落评论组聚焦从 `scrollIntoView` 改为**显式面板列表滚动**
    （`list.scrollTop = group.offsetTop - list.offsetTop`，scrollIntoView 会波及正文
    滚动容器）。修复后真实鼠标重复点击 5 次 `scrollTop` 完全稳定。
- 音乐顶栏气泡：`showMusicToast` 已接入但被 `scanChapter` 250ms 轮询杀掉——63299 的
  自动音乐与手动音乐标记在同一元素，手动点击后轮询触发 `playMusic(auto)` 走进
  **同曲切停**分支；修复：同曲切停仅手动点击生效（`automatic` 时跳过）。toast
  （歌名 + 楼层）稳定显示，停止隐藏。
- 滚动模式底部空白：内容高度变化（徽标/评论注入）后 iframe 高度未重算，底部内容被
  iframe 裁剪；`Reader.applyLayout` 滚动分支补 `syncHeight()`（对齐 Android 既有
  经验：滚动模式一章到底 + 高度同步）。63299 第 2 章 338145px 内容滚动到底
  `reachedEnd=true`、iframeH=docH。
- UI 规范：总览视图 tab 胶囊改圆角 8px；音乐控件/进度条/顶栏气泡统一用现有 CSS 变量。
- 验证：formal 冒烟 + verify-63299（骰点/折叠/总览/分页保位）+ verify-toast（气泡/
  停止/长章节滚动底）全过；Windows Python 283 项 OK。未改 Android、双端 JSON 契约。

### 2026-08-16 win/docs：骨碌碌阅读交互第三轮（跳转保位修复 + 总览双视图 + 音乐增强）

- 跳转/保位：
  - `Reader.seekToOffset` 定位后保存**目标 offset**（`saveProgress(preciseOffset)`），
    避免滚动/重排未稳定时重新采样视口中线导致进度乱跳；
  - 分页模式评论抽屉开合改用 `Paged.beginViewportResize`（变化前冻结页首锚点）+
    `onResize`（120ms 延迟内部恢复），移除手动 `seekToOffset`（与 onResize 竞争导致
    漂移）。真实书 63299 复现 146483→0 漂移，修复后 146483→146483 精确保持。
- 总览双视图：`gululu-overview.js` 重写——「按楼层」（一楼聚合音乐/背景/视效/骰点/
  折叠/秘密）与「按类型」（音乐/背景/视效/阅读解锁/内容结构）两种视图切换；
  骰点组**聚合为统计**（不再逐个列组）；音乐条目显示**歌名**（`.gululu-music-title`）；
  每项标注所在楼层并支持点击跳转正文。
- 沉浸增强：背景切换加淡入过渡（opacity transition，避免硬切）；音乐面板新增
  **进度条**（timeupdate 同步 + 可拖动 seek）与**播放/暂停键**；播放时顶栏显示
  **音乐气泡**（歌名 + 楼层）。
- 重置分开：更多菜单「重置阅读解锁」拆为「重置骰点揭示」与「重置秘密线索」两个入口。
- 参考书 63299：用新转换器重新生成（4 章，骰点 824 / 折叠 52 / 自动音乐 38 / 背景 45 /
  段落 7050），注册到用户书架；`formal_server` 新增 `--book` 参数加载外部 EPUB；
  新增 `workspace/verify-63299.js` 专项验证（骰点遮罩 26/26、折叠 0 open、总览双视图、
  分页保位）。
- 验证：formal 冒烟（含总览双视图/音乐歌名断言）桌面+430px 全过；verify-63299 4 项全过；
  Windows Python 283 项 OK；JS 契约 6/6；Android JVM 117 过/1 跳；UI harness 97 PASS。
  未改 Android、双端 JSON 契约。

### 2026-08-16 win/docs：骨碌碌阅读交互第二轮（六问题修复 + 沉浸内容总览）

- 问题与修复（均先复现/定位再改）：
  1. 评论展开进度跳回开头——`Reader.seekToOffset` 滚动定位公式 bug：`scrollTop = rect.top`
     （视口坐标）在已有滚动位置时会把位置拉回顶部；改为 `rect.top + 当前 scrollTop`
     （章节加载时 scrollTop=0 行为不变）。复现脚本实测 scrollTop 156 开合抽屉前后一致。
  2. 目录悬浮按钮不能收回——`gululu-quick-toc` handler 只处理"未打开时打开"，
     补 `Sidebar.isOpen()` 时 `Sidebar.close()` 分支。
  3. 段落评论增强——点击面板段落评论经 `TextPos.rangeToOffsets` 求段落起点 →
     `Reader.seekToOffset` 跳转正文并高亮段落；点击正文徽标 → 面板聚焦段落组并高亮组内
     首条评论（`.gululu-comment-focus`）。
  4. 折叠内容未默认折叠——`gululu_ast.py` 的 `collapsibleBlock` 显式写 `open="open"`
     导致默认展开；去掉 open（浏览器 details 默认折叠），同步
     `tests/test_gululu_epub.py` 断言。
  5. 沉浸内容总览——新增 `web/js/gululu-overview.js` 与 `#gululu-overview-panel`：
     分类展示本章音乐（自动/手动）、氛围背景（缩略图预览）、动态视效、阅读解锁
     （骰点组进度条 + 解锁数）、折叠/秘密摘要；条目点击跳转正文对应标记并高亮；
     快捷轨新增总览按钮，纳入互斥网络（comments/immersive/settings/overview 互斥）。
  6. 骰点隐藏未生效——定位为用户真实书（32203/66905）为旧版转换（无
     `data-gululu-*` 协议标记），骰点/折叠从未转换；基础骰点遮罩/揭示/迷雾
     （formal 测试书）冒烟验证正常。旧书需"检查更新"或重导后启用。
- 验证：`formal_ui_smoke.js` 新增总览用例（分类区块/骰点进度/跳转）+ 抽屉开合保位
  断言，桌面与 430px 全过；Windows Python 283 项 OK（4 跳）；JS 契约 4 项全绿。
  未改 Android、CI、双端 JSON 契约。

### 2026-08-16 win/docs：骨碌碌阅读交互改造（悬浮气泡助手 + 侧边评论 + 段落级评论）

- 参考：官方阅读页 `/chat/48856`（`CommentBlock` 侧边抽屉、`RichTextParagraph_inlineCommentNumber`
  段落行内评论数）+ ScriptCat 5355 V3.94 源码 + 既有 `docs/GULULU_REFERENCE_MATRIX.md`；
  数据链确认：评论 API `paragraphId`（如 "p1"）↔ 楼层段落 `attrs.id` ↔ 正文
  `<p data-paragraph-id>`（转换器 `gululu_ast.py` 已写入，测试书 12177 处）。
- 沉浸助手气泡：`gululu-immersive-panel` 由右侧全高抽屉改为右下角悬浮气泡卡片
  （right 60px / bottom 50px、宽 280px、圆角 16px，与快捷轨同层，不遮正文）；
  窄屏（≤520px）改底部抽屉式（全宽、上圆角、max-height 82vh）。
- 评论侧边展开：评论抽屉打开时 `#reader-root.gululu-comments-open` 让正文
  `chapter-scroll` 让位 `min(380px,88vw)`（与阅读界面同层级，非覆盖浮层）；
  开合后经 applyLayout + `seekToOffset` 恢复阅读位置（进度保持铁律）；窄屏不让位。
- 段落级评论：load 后向有段落评论的正文段落注入行内评论数徽标
  （`.gululu-paragraph-comment-badge`，带 `data-textpos-exclude`，不进坐标）；
  面板评论按 `paragraph_id` 分组渲染（段落评论组 + 楼层评论组）；点击正文徽标 →
  面板聚焦该段评论组并高亮正文段落；点击面板段落评论 → 正文段落临时高亮。
- 验证：`formal_ui_smoke.js` 新增段落徽标/分组/双向联动/坐标不变/抽屉开合保位断言，
  桌面 + 430px 全过（0 失败）；Windows Python 283 项 OK（4 跳）；JS 契约
  textpos 15 / parts 6 / bridge v1 / reader-session 全绿。未改 Android、CI、
  双端 JSON 契约与后端（`comment_to_public` 早已透传 `paragraph_id`）。

### 2026-08-16 docs：文档治理评估与整理（冗余/漂移清理）

- 评估：4 组并行盘点 docs/ 与入口文档（入口治理 / 架构契约 / Android / 审查规划），
  结论——文档分层总体健康（入口 / 现役规范 / 契约 / 日志三层 / 归档 / 审查记录），
  无过度冗余；主要问题为事实多源重复（版本表 7 处、命令 5 处、纪律 4 处，当前一致）
  与少量漂移。
- 整理（外科手术式，仅动问题点）：
  - `使用说明.txt`：版本 v1.2.0 → v1.3.1；第 40 行过时描述“暂不包含离线图片和
    骨碌碌热更新”更新为图片三态 + 检查更新现状；
  - `docs/DATA_CONTRACT.md` §7：加权威指针（原生书字段明细/不变量以
    NATIVE_BOOK_FORMAT.md 为唯一权威，本节为摘要），消除字段级重复；
  - `AGENTS.md` 文档漂移检查清单补 `使用说明.txt`、`nga-post-template.bbcode`
    （此前未纳入，使用说明漂移即治理盲点所致）；
  - `docs/archive/ANDROID_UI_PLAN.md`、`docs/archive/ANDROID_READER_REFERENCES.md`：补“状态”
    标注（M4 已验收/调研记录，非现役规范），与其余历史文档状态纪律对齐。
- 评估结论（不整理项）：ADR 五份为“为何决定”重申（职责不同，保留）；ARCHITECTURE
  与 CODEBASE_MAP 粒度不同（保留）；REVIEW_ACTION_PLAN 与 ARCHITECTURE_ROADMAP
  待办重复列项（建议后续以路线图 P0–P4 为主基线，整改计划改指针）；历史审查文档
  按纪律保留不删。
- 验证：纯文档改动，未跑构建；`git status` 见修改清单。

### 2026-08-16 docs：落盘接手维护手册 + 修正 SECURITY.md 版本漂移

- 处理：新增 `docs/MAINTENANCE_GUIDE.md`（接手维护者进场参考：双端架构、数据契约、
  测试/CI 基线、构建发布流程、开发纪律、已知风险、维护任务速查）；修正
  `SECURITY.md` 支持版本表 Windows 行 v1.2.0 → v1.3.1（Android 行不变，仍为
  android-v1.0.0）。
- 验证：纯文档改动，未跑构建；`git status` 仅 SECURITY.md 修改 + MAINTENANCE_GUIDE.md
  新增（+ DevLog 本条）。

### 2026-08-15 win/docs：Windows v1.3.1 正式发布

- 版本：Windows 版本源、前端调试 MOCK、版本测试与现役文档由 v1.3.0 更新为 v1.3.1；
  Android 版本线、代码与发行资产不变。
- 内容：发布骨碌碌专版一级阅读入口、全能助手 V3.94 Reader 协议、逐楼评论双模式、章节
  加载遮罩、来源隔离，以及分页沉浸保位和引用锚点修复。
- 门禁：Windows Python 283 项、API 52 方法、TextPos 15 例、桥合同、reader-lite parts、
  reader-session、JS 语法与两套 Playwright 已通过；正式包包含 release manifest 与
  SHA-256 校验文件。

### 2026-08-15 win/docs：全能助手 V3.94 Reader 功能矩阵与一级阅读入口

- 对照 ScriptCat 5355 的 V3.94 源码与骨碌碌公开阅读页，建立
  `docs/GULULU_REFERENCE_MATRIX.md`：Reader 路径逐项映射，Editor 生成协议全部列入读取
  合同，Chat/点赞/收藏/分享/举报等站点写路径明确为只读阅读器不适用，不再静默遗漏。
- 信息架构调整：目录、评论、书签、音乐与氛围、揭示骰点、阅读设置改为右下角一级入口；
  更多仅保留全屏与单书阅读解锁重置。浮动设置新增骰点遮罩、迷雾、折叠、点击音效；
  自动音乐按参考助手默认开启，自动播放受限时等待下一次用户交互恢复。
- Reader 协议补齐：导入期把骰点结果/后缀和后续迷雾转换为稳定语义节点，运行时支持
  单组、Alt 整楼、接下来 10 组揭示、本机按书持久化和 Web Audio 点击音效；文本形式
  `<引用 id floor>` 同书转 EPUB 锚点、跨书转公开网页。折叠、秘密、线索、音乐、背景、
  视效继续复用现有安全转换；换书/NGA 来源清理不串扰。
- 红绿验证：骰点/迷雾与文本引用单测先失败后转绿；正式 Playwright 桌面/430px 通过，
  连续两组骰点批量揭示后章节重载仍保留，交互前后正文长度均为 176；自动音乐、背景、
  雨效、秘密、逐楼评论、楼末折叠和 NGA 隔离通过，分页全屏往返仍回第 3 页 /
  `text_offset=1943`；同书引用在滚动与分页模式均落到目标楼层且保存非零锚点。系统
  Python 3.14 为 283 项通过（4 跳），bundled Python 3.12 为 283 项全过；API 52 方法、
  TextPos 15 例、桥合同、reader-lite parts、reader-session、JS 语法与两套 Playwright
  均通过。Android、CI、共享 JSON 契约和版本号未修改。

### 2026-08-15 win/docs：正式快捷操作轨、集中设置与章节加载遮罩

- 正式骨碌碌阅读页补齐右下角常驻快捷操作轨：评论、阅读设置与更多操作在顶栏收起后
  仍可使用；更多菜单提供目录、音乐与氛围、全屏。目录继续复用侧栏，设置继续复用
  `ViewMenu`，不另建重复页面；骨碌碌设置区集中主题、评论显示方式与弹幕，并保留字体、
  字号、行高、翻页方式和页面宽度。评论抽屉、设置面板、音乐面板、更多菜单互斥，Esc
  关闭后焦点回到触发按钮；旧顶栏评论/音乐入口不再重复显示，NGA 阅读页不出现专版入口。
- 参照 Android `WebViewChapterView` 的排版稳定语义，Windows 换章时以阅读主题背景覆盖
  正文并设置 `aria-busy`；滚动模式等待字体，分页模式同时等待首轮图片，完成进度恢复后
  撤下，5 秒兜底放行。遮罩期间拦截正文与翻页误触，不阻塞顶栏和专版快捷操作。
- 红绿验证：正式 smoke 先分别失败于缺少快捷操作轨、缺少加载遮罩，再实现转绿。两套
  Playwright 的 1440px / 430px 场景通过，视觉复核确认设置面板不越界、抽屉不遮挡章末
  导航；分页全屏往返仍为第 3 页 / `text_offset=1943`，NGA 来源隔离继续通过。Windows
  Python 3.14 为 281 项通过（4 跳），bundled Python 3.12 为 281 项全过；API 52 方法、
  TextPos 15 例、桥合同、reader-lite parts 与 reader-session 均通过。Android、CI、版本号
  与共享 JSON 契约未修改。

### 2026-08-15 win/docs：骨碌碌楼层对齐 NGA 卡片结构 + 分页沉浸保位

- 楼层样式：骨碌碌 EPUB 与正式阅读器改用 NGA 同类的 1px 细边框、4px 左强调线、
  2px 圆角、点状楼头分隔和紧凑内边距；骨碌碌楼号/标题/评论入口仍保留。强调线不照搬
  NGA 蓝色，导出 EPUB 使用低饱和灰绿，正式阅读器按当前主题混合强调色与正文色；分页
  允许楼层跨列拆分。专版调试壳同步同一几何结构，NGA 自身样式未修改。
- 分页保位：复现出宿主窗口缩放先把多栏 `scrollLeft` 清零、随后 `ResizeObserver`
  错把首屏写成重排锚点。全屏切换前显式保存会话位置并冻结 `text_offset`，连续尺寸事件
  改为 120ms 去抖且不覆盖首个有效锚点；退出时读取会话最后一次显式保存位置，沉浸中
  翻页仍会更新该位置，避免即时进出发生反向漂移。
- 红绿验证：楼层样式合同先失败后通过；长章节浏览器用例修复前由第 3 页
  `text_offset=1943` 归零，首轮修复虽不归零但漂到第 2 页，再收紧到原尺寸恢复后仍为
  第 3 页 / `1943`。Windows Python 281 项通过（4 跳），API 合同 52 方法、TextPos
  15 例、Python 契约 11 项通过；两套 Playwright 桌面/430px 回归及 NGA 隔离继续通过。

### 2026-08-15 win/docs：骨碌碌专版交互、逐楼评论双模式与来源隔离

- 交互调试壳：底部大工具栏收敛为翻页/位置，目录改为按需侧栏；右下角新增评论、设置、
  更多操作常驻入口，设置集中承载主题、模式、字号、行距、版心、楼末评论与弹幕。评论按
  章节或指定楼层使用底部抽屉打开，设置/评论互斥，Esc 关闭并回收焦点；正文按骨碌碌
  楼号、标题、评论数恢复楼层卡片层级；后续细化为 NGA 几何结构与 AnkeShelf 主题配色。
- 正式评论：章节内所有楼层 ID 批量在线加载，每楼头部可打开只显示该楼的侧边面板；
  显示模式可切到楼末折叠。动态按钮与评论统一标记 `data-textpos-exclude`，`TextPos`
  重建也会跳过整棵注入子树，避免评论数量和展开状态改变 `text_offset`。
- 来源边界：托管路径和含评论导出文件在 Windows `get_shelf` 运行时派生来源 ID，书架
  网格、列表和最近阅读封面显示“骨碌碌”标签，不扩展共享 `shelf.json`。沉浸章节绑定
  增加来源硬门槛；切到带同名 DOM 的 NGA 测试书后，评论/沉浸入口、楼层按钮、音乐、
  秘密和背景均不触发。Android、CI、共享 JSON 契约与 API 方法表不变。
- 验证：先以多楼层评论、楼末坐标隔离、来源标签和 NGA 伪标记写红测，再实现转绿。
  Windows Python 281 项 OK（4 跳）；API 合同 52 方法、TextPos 15 例和 Python 契约
  11 项通过。两套 Playwright 在 1440px / 430px 下通过：第二个楼层评论内容命中、
  注入前后正文坐标一致、宿主/正文无横向溢出、NGA 隔离状态 sourceId=0。

### 2026-08-15 win/docs：Windows v1.3.0 正式发布

- 版本：Windows 版本源、浏览器调试 MOCK、版本测试与现役文档由 v1.2.0 更新为
  v1.3.0；Android 版本线与代码不变。
- 内容：发布 PR #13 已并入主干的骨碌碌公开书籍导入、在线评论与含评论导出、全能助手
  内容、沉浸效果、图片三态和追加式增量更新；同时包含分页裁剪与沉浸退出保位修复。
- 本地门禁：系统 Python 3.14 为 280 项通过（4 跳），bundled Python 3.12 为 280 项
  全过；前端 JS 语法、全部 Node 契约与 `reader-session` 通过，WebView2 UI harness
  97 项通过。
- 资产：正式包名 `AnkeShelf-v1.3.0.zip`；从同一版本提交的主干 CI 干净构建产物发布，
  同步保留 release manifest 与 SHA-256 校验文件，不修改 Android 标签或发行资产。

### 2026-08-15 win/docs：PR #13 骨碌碌适配并入主干

- 合并门槛：提交 `e3fc194` 的两轮 Contracts CI 均通过；Windows CI 在 Python
  3.12/3.13/3.14 的两轮矩阵均通过，3.12 PyInstaller 打包与产物上传成功。
- 合并：PR #13 以 rebase 方式并入 `main`，GitHub 合并基线为 `4b77ded`；本地
  `main` 已通过 `git pull --ff-only` 同步。远端功能分支暂保留，未打标签、未发布新版。
- 漂移检查：Windows Python 基线 280 项、API 合同 52 方法、四个 workflow 与当前仓库
  一致；README 版本仍为 Windows v1.2.0 / Android v1.0.0，无需修改版本线。

### 2026-08-15 win/contracts/docs：PR #13 契约清单最小依赖修复

- 现象：PR #13 的 Windows 3.12/3.13/3.14 测试与 PyInstaller 打包全部通过，
  Contracts CI 只安装 `jsonschema`，加载 `app.api.api_manifest` 时却因服务类型和秘密
  解密器的顶层导入继续加载 `httpx` / `cryptography`，在产品依赖不完整的契约环境失败。
- 修复：`GululuService` 仅在 `TYPE_CHECKING` 下导入；秘密解密器延迟到实际调用
  `gululu_decrypt_secret` 时导入。新增子进程回归测试，主动屏蔽 `httpx` 与
  `cryptography`，验证 API 清单仍可独立加载；产品运行与解密行为不变。
- 验证：修复前新测试稳定失败，修复后 API/契约定向 19 项通过（4 跳）、Node API
  合同与启动失败诊断通过；Windows 全量基线更新为 280 项。

### 2026-08-15 win/docs：骨碌碌追加式增量热更新

- 范围：Windows 骨碌碌面板新增“检查更新”，不修改 Android、CI、共享 JSON 契约；
  `gululu_start_update` 加入 Python / JS API 合同，方法数由 51 增至 52。
- 实现：公开客户端拆出目录与正文独立请求；首次导入在
  `gululu_library/<bookId>/snapshot.json` 保存 Windows 私有基线。后续完整读取书籍详情、
  楼层索引和章节索引，只对旧楼层 ID 的严格后缀调用正文接口；无新增且图片模式未变时
  返回“已是最新”，不重建 EPUB。远端旧楼删除、重排或替换明确报冲突并要求完整重导。
  旧版 EPUB 首次检查从 `floor-*` 锚点核对远端历史并一次性建基线。EPUB 替换前关闭现有
  缓存并留临时备份，登记失败恢复旧文件；路径派生书籍 ID 不变，因此进度与标注继续关联。
- 结构：公开 API 客户端、在线评论缓存、增量计划/合并/替换分别落入
  `gululu_client.py`、`gululu_comment_service.py`、`gululu_update.py`；
  `gululu_service.py` 收敛至 498 行，保留任务状态、取消与事件编排。
- 验证：修复前红测确认“双重登记失败会丢失原错误”，修复后同时保留替换与恢复上下文；
  系统 Python 3.14 与 bundled Python 3.12 均 280 项 OK（3.14 跳过 4），全部 Node 合同、
  `reader-session` 与 WebView2 UI harness 97 项通过。真实书 `63299` 的 48 楼临时基线二次
  检查只请求 3 个索引接口，正文接口 0 次、`rebuild=False`；本机 `32203` 旧 EPUB 的
  2299 个楼层与远端严格一致，验证旧书迁移前提。真实用户书架未写入。

### 2026-08-15 win/docs：骨碌碌正文图片在线、内嵌与不含三态

- 范围：继续待适配清单中的离线图片，仅修改 Windows；Android、CI、共享 JSON 契约、
  API 方法表均不变。导入与含评论导出新增“在线图片 / 内嵌图片 / 不含图片”选择，默认
  保持在线，避免现有用户在未选择时得到体积显著增大的 EPUB。
- 实现：新增 `gululu_images.py`，递归收集并去重正文图片 URL；内嵌模式只接受 HTTPS，
  6 路并发下载、单图限制 25 MB，并以文件签名识别 JPEG/PNG/GIF/WebP/AVIF。成功资源
  写入 EPUB `images/`，失败资源转正文明确占位；结构化构建结果把成功/失败数传到任务
  状态和完成提示，不静默回退在线。音乐、背景与视效媒体继续保持在线。
- 测试：先补内嵌参数、服务传递和失败占位红测，再实现转绿；补“不含图片不发请求”
  业务不变量。系统 Python 3.14 与 bundled Python 3.12 均 270 项 OK（3.14 跳过 4），
  Node 合同全部通过，WebView2 UI harness 97 项 PASS。真实书 `63299` 检出 1556 个唯一
  正文图片 URL，抽取首张 291278 字节 WebP 完成真实 HTTPS 下载与格式校验。

### 2026-08-15 win/docs：骨碌碌全能助手秘密、真实书排版与图片首屏提速

- 范围：继续待适配清单，支持全能助手文本折叠、秘密与线索；参考公开脚本确认协议为
  `<秘密>[名称]CryptoJS AES 密文</秘密>` / `<发现秘密>[名称]密码</发现秘密>`，未保存
  第三方源码。不修改 Android、CI 或双端 JSON 契约。
- 转换/API：新增 `gululu_assistant.py`，把折叠、秘密、线索及已知
  `jumpFloorComponent` / `sensitive` 节点转成安全 XHTML；兼容 CryptoJS/OpenSSL
  salted AES，AES 实现使用锁定的 `cryptography==49.0.0`。新增
  `gululu_decrypt_secret`，API 合同由 50 增至 51；第三方声明已同步。
- 阅读器：新增 `gululu-secrets.js`。线索按 `bookId + title` 存本机，秘密点击时才向
  Python 解密，明文以 `textContent` 显示在 iframe 外弹窗，不写回正文 DOM。430px
  底部弹窗、Esc/遮罩关闭、换书/返回书架清理均纳入正式 Playwright。
- 真实书修复：`63299` 的音乐/特效指令含 U+200B 零宽边界，现会先归一化再匹配；
  EbookLib 空沉浸 `span` 自闭合在 `text/html` 下吞掉后续正文，改为带无文本坐标的
  `wbr` 子节点。骨碌碌显式近黑/近白字随阅读主题映射，彩色字保留；楼层增加 0.5em
  字形安全余量，修复自定义字体下 366/369 的窄屏横向溢出。
- 加载性能：生成图片使用原生 lazy + async decode，滚动模式不再等待全章图片；图片
  高度同步按动画帧合并，无代码高亮/标注时不重复构建 TextPos。1345 图延迟基准中，
  请求数 `1347 -> 9`、换章 `3618ms -> 2602ms`；剩余耗时主要是 39 楼超大 DOM 的解析
  与首轮布局，保留作者章节边界，未擅自拆章。
- 验证：真实 `63299` 为 48 楼、3 阅读章、52 折叠、38 自动音乐、8 停止音乐、45
  背景、1 清除背景、1 特效、2 个可点击跳楼链接、1 敏感节点、1557 图，未知节点与原始协议泄漏
  均为 0，XHTML 全可解析；`66905` 为 109 楼/20 章，`32203` 为 2299 楼/115 回退章。
  Python 3.14 与 bundled 3.12 均 267 项 OK；Node API 51 方法及全部合同通过；WebView2
  harness 97 项 PASS。正式 Playwright 确认秘密明文正确且正文长度 `123 -> 123`，
  430px 宿主 430/430、正文 366/366，控制台业务错误为 0。

### 2026-08-15 win/docs：骨碌碌无作者章节时按楼层自动分章

- 现象：部分骨碌碌作者未设置章节，公开章节接口会返回成功响应但 `data: null`；转换器
  原先将其判为格式错误，空章节列表也会把所有楼层放入一个超大 EPUB 章节。
- 处理：`null` 章节数据现在明确映射为空作者章节；没有任何有效作者章节标记时，从
  1 楼开始按 NGA 默认粒度每 20 楼生成一个章节，标题使用楼层范围。骨碌碌没有主楼
  概念，因此 1 楼不单独成章；存在有效作者章节标记时保持原有分章与标题。
- 验证：新增 `null` 章节合同与 42 楼边界回归测试；Windows Python 全量 257 项 OK
  （4 跳）。真实测试书 `32203` 的章节接口返回 `data: null`，2299 楼成功生成 115 章，
  首章“第 1~20 楼”、末章“第 2281~2299 楼”，紧凑 EPUB 约 2.78 MB。

### 2026-08-15 win/docs：骨碌碌音乐、氛围背景与动态视效

- 范围：实现待适配清单第 2 项“音乐与背景特效”，仅修改 Windows；Android、CI、
  双端 JSON 契约和 API 方法表不变。参考公开用户脚本确认这些能力来自作者写入正文的
  文本指令，而非骨碌碌 API 独立字段；未保存第三方脚本源码。
- 转换：新增 `gululu_ast.py` / `gululu_immersive.py`，识别 `<音乐>`、`<自动音乐>`、
  `<停止音乐>`、`<背景>`、`<移除背景>` 与 `<特效:...>`，将其转换为 EPUB `data-*`
  语义标记。外链只接受无凭据 HTTPS；未知特效、无效链接和未闭合背景指令显示明确
  占位。跨章节背景通过零高度初始标记保持，`gululu_epub.py` 为 484 行。
- 阅读器：新增宿主层播放器、氛围背景层、Canvas 雨/雪/风/雷与 CSS 震动效果，以及
  音量、自动音乐、背景和视效控制面板。手动音乐随正文按钮播放，自动音乐默认关闭；
  背景/视效默认开启，系统“减少动态效果”开启时抑制动画。返回书架会停止音乐、清空
  背景/视效并终止扫描器，所有运行时层均位于 iframe 外，不改正文 DOM。
- 验证：本机 Python 3.14 与 bundled Python 3.12 均为 255 项 OK（3.14 跳过 4）；
  API 50 方法、全部 Node 契约和 WebView2 UI harness 94 项通过。正式阅读器 Playwright
  冒烟确认音乐播放/停止、背景命中、雨效 Canvas 约 5 千个可见像素、评论/弹幕共存、
  返回书架清理完成；正文长度在效果前后保持 `45 -> 45`，1440px / 430px 截图无
  溢出，窄屏 `scrollWidth=430`，控制台业务错误为 0。

### 2026-08-15 win/docs：骨碌碌评论第二阶段（默认在线 + 可选完整导出）

- 范围：书架 EPUB 默认不再嵌入评论；评论改为 Windows 正式阅读器按当前章节懒加载，
  同时保留“导出含评论 EPUB”作为跨阅读器、自包含快照。不修改 Android、CI 或双端
  JSON 契约。
- 后端：EPUB 解析器保留 `dc:identifier` / `dc:source`，以 `gululu-<bookId>` 识别
  来源；评论 API 按楼层分批读取，只向前端/缓存保留昵称、时间、点赞、段落 ID、回复
  对象和子回复。Windows sidecar 缓存有效期 5 分钟，联网失败时显式返回最近缓存，
  无缓存则返回明确失败。普通导入 `include_comments=False`；完整导出重新拉取全量评论，
  原子生成 `gululu-<bookId>-comments.epub`，不替换书架副本。
- 前端：新增正式阅读器宿主层评论面板、刷新命令和弹幕开关；切章只提取 iframe 中的
  `floor-*` 锚点，面板打开或弹幕开启时才请求，超长章节每 50 个楼层分批。评论以
  `textContent` 渲染，面板和弹幕均位于 iframe 外，不参与分页、搜索或 `text_offset`。
- 验证：Windows Python 252 项 OK；API 50 方法一致。正式阅读器 Playwright 冒烟使用
  无评论 EPUB，确认内嵌评论 0、在线评论/回复 4、打开面板前后正文文本长度均为 39、
  弹幕与强制刷新正常；1440px / 430px 截图无文字遮挡，窄屏 `scrollWidth=430`，
  JavaScript 控制台业务错误为 0。真实书 `66905` 的紧凑导入约 28.5 秒完成 20 章生成
  与临时书架注册，章节内评论标记为 0；旧的含评论调试 EPUB 仍通过 20 章 / 评论 /
  弹幕回归。
  `gululu_epub.py` 提取来源解析后为 488 行。

### 2026-08-15 win/docs：骨碌碌公开评论与专版只读弹幕

- 范围：先实现待适配清单第 1 项“评论/弹幕”。标准 EPUB 继续作为跨端兼容边界；
  评论数据写入 XHTML，弹幕仅由 Windows 专版调试壳层呈现，不修改双端 JSON 契约，
  不接入登录、发言、点赞等站点写操作。
- 处理：匿名客户端增加作品评论、楼层评论与子回复分页抓取，使用
  `/reader/opus/comment/page`、`/reader/opus/comment/page-children` 和 `platform: 1`；
  任务进度新增 `comments` 阶段。EPUB 保留昵称、时间、点赞数、段落 ID、回复对象与
  子回复，内容转义后写入可折叠评论区；正文段落同步保留 `data-paragraph-id`。专版
  调试阅读器新增独立“评论”与“弹幕”开关，弹幕层位于 iframe 外，不改变正文坐标。
- 验证：真实测试书 `66905` 生成 20 章、110 个评论块，公开 API 实际返回 2928 条
  评论/回复（其中子回复 311 条），约 19 秒完成转换；浏览器冒烟检查确认首章评论、
  弹幕投射、3 页分页、切换第二章和 430px 窄屏无横向溢出，控制台错误为 0。
  评论 API 契约、子回复、服务透传、HTML 转义与段落锚点回归测试均通过。容量评估：
  成品 EPUB 约 560 KB；评论 XHTML 原始约 1.15 MB，压缩后估算增加约 158 KB。

### 2026-08-15 win/docs：建立骨碌碌专版阅读器独立调试区

- 现象：骨碌碌 EPUB 导入链路已能端到端运行，后续专版阅读器排版实验需要与正式
  `app/` / `web/` 代码及 Android 工程隔离，且生成的 EPUB、解包目录、截图和日志
  不应进入版本库。
- 处理：新增 `tests/gululu_reader_debug/` 作为 Windows 测试边界内的独立调试区；
  调试服务器复用现役 EPUB 解析器与骨碌碌转换器，专用 Web 阅读壳层支持目录、
  滚动/分页、字号、行距、版心与三种主题；`workspace/` 默认忽略生成书籍和日志。
  可选 Playwright 冒烟检查覆盖桌面/窄屏、正文、切章和分页状态；README 明确实验
  迁移、固件复用、凭据与跨端边界规则。未修改正式构建、CI 或数据契约。
- 验证：新增服务器元数据、章节 CSP/UTF-8/base 注入与路径穿越回归测试；Windows
  Python 247 项 OK（4 跳），工作区忽略规则可由 Git 正确识别，`git diff --check`
  通过。
- 排版修复：真实书浏览器冒烟检查发现 EbookLib 自动写出的章节样式链接为
  `style/main.css`，从 `chapters/` 解析后命中不存在的路径；先补“章节样式 href 必须
  解析到 ZIP 实际条目”失败测试，再改为显式 `../style/main.css`。调试服务器同时移除
  被 CSP `base-uri 'none'` 拒绝且不必要的 `<base>` 注入，浏览器控制台错误归零。

### 2026-08-15 win/docs：骨碌碌标准 EPUB 适配首阶段（排版与导入）

- 范围：未来预留 Android 适配，但首阶段只做 Windows；不复用 NGA tid/pid、
  不修改 `ank-native/1` 或双端 JSON 契约，以标准 EPUB 作为兼容边界。
- 处理：新增骨碌碌匿名阅读 API 客户端（`platform: 1`，楼层分批获取）、公开书籍
  URL/ID 解析、递归 AST → XHTML 渲染和 EPUB3 打包；支持段落、标题、在线图片、
  折叠块、粗体/斜体/删除线/显式文字颜色，未知节点显示占位而不静默丢失；按站点
  `chapterIndex` 的起始楼层分章。新增 `GululuService` 接入 `TaskManager`，支持
  单飞、进度、取消、`.part` 原子替换和自动入架；下载页新增默认“骨碌碌”标签，
  与 NGA / 更新 / 导出 / 配置并列，完成后可直接打开。
- 测试：先补固件与服务红测再实现转绿；Windows Python 247 项 OK
  （4 跳）。真实测试书 `66905` 生成 20 章 / 20 项目录，包含 3435 个有效在线图片
  引用、14 个折叠块；6 个原站无效图片地址显示明确占位，20 个章节 XHTML 全部
  可按 XML 解析，后台服务真实端到端完成 EPUB 落盘、书架注册和回读；API
  48 方法一致，WebView2 UI harness 94 项全部通过（含骨碌碌面板/桥接）。
### 2026-08-15 win/docs：分页边缘裁剪与沉浸模式保位/窗口状态修复

- 现象：CSS 多栏分页的默认阅读边距 40px 大于列间距 28px，相邻列会提前 12px 进入
  iframe 视口，导致页边缘漏出相邻页文字；正文显式 `nowrap` 时会跨列溢出。进入或
  退出沉浸模式触发连续重排，页首锚点在宽版面被吸入第 0 页；pywebview 6.2.1 的
  WinForms 后端退出全屏时固定写入 `Normal`，不会恢复进入前的最大化状态。
- 处理：分页正文按左右阅读边距增加 paint clipping，并强制普通文本容器恢复可换行；
  窗口重排改用页面约 45% 高度处的视觉锚点，连续 ResizeObserver 合并后再按统一布局
  坐标恢复；全屏 API 显式传递进入/退出状态，宿主记录最大化事件并在退出后按原状态
  恢复。正常进度保存仍使用既有页首 `text_offset`，数据契约未变。
- 验证：四项修复均先补红测；Python 3.14 / bundled 3.12 全量 232 项 OK（3.14 跳 4），
  API 45 方法与全部 Node 契约通过；WebView2 UI 95 项全部 PASS，其中新增分页重排保位、
  边缘裁剪和超长单行换行三项。Android、CI 与双端 JSON 契约未修改。

### 2026-08-15 android/contracts/docs：CI 路径修复 + API 契约启动诊断

- 现象：`android.yml` 已将 `run` 工作目录设为 `android/`，bundle 校验仍调用
  `android/scripts/bundle-reader-lite.js`，实际解析为不存在的 `android/android/scripts/`，
  Android CI 会在单测前失败；API 契约守卫的 `spawnSync` 无法启动 Python 时只打印
  空的 stderr/stdout，表现为无原因退出 1。
- 处理：`DisciplineTest` 增加 CI bundle 相对路径守卫，workflow 改用
  `node scripts/bundle-reader-lite.js`；API 契约守卫显式输出 `spawnSync.error` 的错误码、
  消息与 Python 路径，新增端到端失败诊断测试并接入 `contracts.yml`；同步代码基线与
  路线图基线。代码提交：`0e44ae0`（Android CI）与 `f108eda`（Contracts 诊断）。
- 验证：两项修复均先补红测再转绿；bundle 6 个 parts / 36917 字节一致；API 45 方法
  一致且启动失败诊断用例通过；Windows Python 230 项 OK（4 跳）；Android JVM
  117 过 / 1 跳，`testDebugUnitTest assembleDebug` 44 个任务成功；`git diff --check` 通过。

### 2026-08-14 android：原生书错误分类 + 真机阅读进度回归

- 现象：`NativeBook.open()` 把元数据读取失败和 JSON 解码失败统一包装为
  `EpubError`，`registerNativeDir()` 又把所有异常统一返回 `Corrupt`，真实 IO /
  权限失败因此被误报为格式损坏；阅读器进度修复此前仅有 JVM / JS 验证。
- 处理：元数据读取异常原样上抛，仅将读取成功后的解码异常转换为 `EpubError`；
  `registerNativeDir()` 与 EPUB / openSession 链路对齐，明确分为
  `EpubError -> Corrupt`、其他读取异常 `-> Io`；新增真机 chmod 000 回归测试，
  保留损坏 JSON 仍返回 `Corrupt` 的 JVM 合同测试。
- 验证：设备测试先红（不可读 `meta.json` 实际得到 `Err(Corrupt)`）后绿；
  instrumentation 11 / 11 通过。使用设备已有原生书实测滚动保存/退出/连续重进
  3 次、单页分页保存与重进、分页 -> 滚动字段隔离、含图片章节保存与重进，坐标
  均稳定；覆盖安装全程使用同证书 `adb install -r`，未卸载/清数据。回归前后均为
  544 个原生书文件、532 个章节、4 本书，四份 `meta.json` 哈希逐项一致；
  `testDebugUnitTest assembleDebug assembleRelease --rerun-tasks` 96 个任务全执行成功，
  `check-release.ps1` 通过（APK 内 reader-lite / 字体哈希与源码一致，无可疑条目）。

### 2026-08-14 android：数据/阅读链路显式失败修复（审查接管首批）

- 现象：Android `StoreLoadResult` 虽区分损坏/IO，但损坏 JSON 仍留在权威路径，
  后续保存可直接覆盖；搜索用 `textOrEmpty()` 把章节读取失败伪装成空正文；
  进度退出写盘异常被 `runCatching` 静默吞掉；`ProgressEvent` 仍允许滚动携带
  page/total、分页携带 ratio；tracker 私有调度线程关闭时未释放。
- 处理：`readJsonStore` 解析失败先隔离为 `.corrupt-*`；搜索构建仅在所有章节
  成功读取后进入 Ready，失败返回明确章节与原因并记录 `index_failed`；
  `ProgressStore.flush()` 返回显式 `Result`，后台/生命周期失败统一记录诊断事件；
  滚动/分页/分页换章锚点拆成互斥事件类型，换章按 WebView 实际模式分流；
  `ChapterProgressTracker.close()` 关闭 scheduler；删除仅测试使用且重新折叠失败的
  `ChapterReadResult.textOrEmpty()` / `RepoResult.getOrNull()` / `chapterPlainLength()`，
  并补 DisciplineTest 类型守卫。
- 验证：先补红测（旧代码缺 `flush` 结果/搜索 error，模式污染与 scheduler 未关闭）
  再修绿；Android JVM 117 过 / 1 跳，`testDebugUnitTest assembleDebug
  --rerun-tasks` 44 个任务全执行且成功；Python 3.14 全量 230 项 OK（4 跳）；
  API 45 方法、textpos 15 cases、bridge、reader-lite parts、reader-session 全绿。

### 2026-08-14 docs：审查材料归档（AnkeShelf_Review_Archive → I: 根目录）

- 处理：H: 根目录散落的审查文档与早期仓库包（review1/2/3、两轮审查报告、
  复审报告、架构提案、架构债清单、anke_shelf-repo-2026-08-10.zip）与 4 个
  旧 review zip 统一移入 `I:\AnkeShelf_Review_Archive\`（先归档至 H: 后移至
  I: 根目录）；H: 根目录只保留最新源码包
  `AnkeShelf-review-20260814-1bb79ec.zip`。仓库内 16 处 `H:\` 引用同步改为
  I: 归档路径（`H:\AnkeShelfReferences` 不变）。
- 验证：纯文档/外部文件整理，未跑构建。

### 2026-08-14 win/android：重构批落地（D1 backup helper / D5 参数归一化 / D3 callBridge）

- D1：`system_api.py` 抽 `_pick_and_call`（选择 → 取消 → 执行 → 异常映射），
  backup_create / verify / restore 各剩 1-2 行业务调用。
- D5：`nga_service.py` 新增 `_param_int` 归一化 helper，消除 8 处
  `max(0, int(...))` 模板；枚举校验（image_mode / theme / toc_mode）两端
  语义不同，保持原位不动（保守子集，避免行为变化）。
- D3：`reader-lite.parts` 新增 `callBridge(name, ...)` helper，压平 15+ 处
  `try { AnkeReaderBridge.xxx(); } catch (e) {}` 模板（含多行 try 块）；
  bundle 后 `reader-lite.js` 36917 字节；DisciplineTest 两处结构断言匹配串
  同步更新（Gradle 对 assets 误判 UP-TO-DATE，`--rerun-tasks` 暴露后修复）。
- 验证：Python 230 项 OK；JS parts / reader-session / bridge-contract /
  textpos / api-contract 全绿；JVM 111 过 / 1 跳（强制重跑）；
  `assembleDebug` 通过；check-release PASS（APK 内 JS / 字体 SHA 一致）。

### 2026-08-14 win/android：行为批落地（B3 静默吞错 / B4 fullscreen / C3 显式失败）

- 处理（按 review3 计划 §6）：
  - B3：`nga_service.py` 热更新注册失败、`library.py` 解析失败沿用旧记录、
    `system_api.py` 卸载清理脚本失败均加 warning/error 日志；Android
    `removeBook` 返回删除结果，三处调用点失败 Toast + `LogEvents` 事件；
  - B4：`ApiContext.fullscreen` 正式字段 + `Api.fullscreen` property，
    替换 `_fullscreen` 动态属性——顺带修复 `main.py` 用 `getattr(api,
    "_fullscreen")` 永远读 False 的 bug（全屏中退出会误记全屏分辨率）；
  - C3：`AnkeShelfRoot` 打开书籍失败 Toast + 回书架；`SearchScreen`
    打开失败显示明确状态；两个 UI 的 `RepoResult.getOrNull` 调用清零。
- 验证：Python 230 项 OK（+fullscreen 翻转断言）；Android JVM 111 过 /
  1 跳；`assembleDebug` 通过；check-release PASS（字体/JS SHA 一致）。

### 2026-08-14 win/android/docs：review3 核验 + 快批清理（死代码/噪音）

- 背景：第三视角审查（`I:\AnkeShelf_Review_Archive\review3.md`，防御性编码 / 复杂度位置），15 项建议。
- 核验：B2（0 偏移）与数据契约冲突驳回（`0 = 无进度`）；A 的“假绿”指控
  证据不足；D4/D5 数量夸大；其余主张基本成立（详见计划 §6）。
- 快批已落地：删 `ProgressRepository / ShelfRepository` Protocol（C1）、
  `BookRepoError.Permission`（C2）、`epub.py` try-import（C4）、
  `system_api.py` unreachable return（C6）、`record_to_dict` 的
  `progress_pct` 占位（B5，`import_books` 显式组装最终值）、
  `system_api.py` 函数内局部 import 顶部收敛（D2）。
- 待办：行为批 B3 / B4 / C3、重构批 D1 / D5 / D3（见计划 §6）。
- 验证：Python 230 项 OK（-1：删除 Protocol 测试）；Android JVM
  111 过 / 1 跳。

### 2026-08-14 win/docs：复审（v2）小瑕疵落地（NOTICE 打包 + 字体 SHA 校验）

- 背景：reviewer 对 `bae2fc2` 复审（评级 A / A- / A+ / A- / B+，13 项
  100% 决策闭环），提出 4 项小瑕疵。
- 处理：
  - `ankeshelf.spec` datas 补 `ngapost2md-python/LICENSE` / `NOTICE`
    （AGPL 分发合规）；
  - `android/scripts/check-release.ps1` 扩展 APK 内
    `assets/fonts/LXGWWenKai-Regular.ttf` SHA-256 与 canonical 源比对
    （复用 reader-lite.js 模板，防 Gradle UP-TO-DATE 误判）；
  - action plan 批次 C 拆分：C1（CODEOWNERS）暂缓，C2（branch protection
    status checks）可立即开启；
  - 多窗口 token 流转记入计划（当前单窗口不阻塞）。
- 验证：`check-release.ps1` 对 debug APK 实测 PASS（字体 SHA 匹配）；
  Python 231 项 OK 不受影响。

### 2026-08-14 win/android/docs：字体去重（E6 / P3，canonical 源 assets/fonts）

- 处理：双端重复的 LXGW WenKai 字体（SHA-256 相同，各 24.8MB）收敛为单一
  canonical 源 `assets/fonts/LXGWWenKai-Regular.ttf` + `OFL.txt`（两份 OFL
  内容一致）；Windows `fonts.py` 增加 canonical fallback（开发模式读仓库根，
  打包经 spec datas 进 `_MEIPASS/assets/fonts`；逻辑文件名
  weidqczfkyxk.ttf 与默认设置 key 不变）；spec 与 windows.yml 打包引用改
  canonical；Android `build.gradle.kts` assets srcDir 并入 `../../assets`
  并删除 android 副本；两个 workflow 路径过滤加 `assets/**`；AGENTS.md
  共享文件清单与 CI 纪律同步。
- 验证：Python 全量测试、UI harness（字体服务走 canonical 源）、
  `assembleDebug` + 解包 APK 校验 TTF SHA-256 与 canonical 一致。

### 2026-08-14 win/docs：批次 E 收尾（README 新人导航 + CI Python 矩阵）

- E3（P2.4）：README 顶部新增「仓库布局（新人导航）」；`使用说明.txt`
  不改名（项目既有中文命名纪律，发行包对中文用户友好），治理文档不移动
  （GitHub 根目录约定），仅做缓解。
- E4（P3.3）：windows.yml 测试矩阵 3.12 / 3.13 / 3.14（fail-fast: false；
  打包与 manifest 仅 3.12 跑，避免多版本 artifact 冲突）。
- E5 维持延后（无 Linux/macOS 需求）；E6 字体去重维持路线图 P3 待办。
- 验证：workflow 结构检查通过；纯文档/CI 配置改动，未跑构建。

### 2026-08-14 docs：批次 E 首项（CONTRIBUTING 入口提示 + 对外贡献门槛）

- 处理（按 REVIEW_ACTION_PLAN）：CONTRIBUTING.md 顶部加醒目
  “开发前必读 AGENTS.md”；外部贡献者只需在 PR 写明改动摘要与验证，
  DevLog 流水由维护者合并时代写（降低 P2.3 门槛）。
- 批次 C 标记暂缓：项目无第二 owner，CODEOWNERS 拆分与 branch protection
  待有第二人后开启。
- 验证：纯文档改动，未跑构建。

### 2026-08-14 win/docs：批次 B 合规补齐（P1.4 vendored LICENSE + NOTICE）

- 处理（按 REVIEW_ACTION_PLAN）：`ngapost2md-python/` 补上游 MIT LICENSE
  （Copyright 2020-2026 Lu Chang）与 NOTICE（上游 ludoux/ngapost2md，
  commit `e3b94346c805`，2026-05-30，分支 neo）；`__version__` 改独立线
  `0.1.0-ankeshelf`（不再与主项目 v1.2.0 混淆）；README 补「许可证与上游」；
  THIRD_PARTY_NOTICES 移除“commit 待钉”待办并指向 LICENSE / NOTICE。
- 验证：`python -m ngapost2md --version` 输出 `v0.1.0-ankeshelf`；
  主测试 231 项 OK（不受影响）。

### 2026-08-14 win/android/docs：批次 A 快修（token 安全 / CI 权限 / 模板 / 漂移）

- 处理（按 REVIEW_ACTION_PLAN）：
  - A1（P1.2 + P1.3）：`app/server.py` `_authorized` 改用 `secrets.compare_digest`
    （header 与 query 双路径）；`web/js/bridge.js` 启动 token 落 sessionStorage
    后立即 `history.replaceState` 抹掉地址栏 query，刷新从 sessionStorage 恢复；
  - A2（P1.5）：4 个 workflow 顶层加 `permissions: contents: read`；
  - A3（P3.4）：bug 模板顶部加安全报告重定向；
  - A4（P3.1）：requirements-build.lock 头部说明 `--allow-unsafe` 原因；
  - A5（P2.5）：路线图 §2.2 代码规模表按当前行数全量更新
    （reader.js 乱码已修复；SettingsScreen/DownloadScreen 等行数为拆分后现值）。
- 验证：Python 231 项 OK（+2 token 回归：query 兼容、错误 token 拒绝）；
  UI harness 92 PASS / 0 FAIL；JS 契约全绿；workflow 结构检查通过。

### 2026-08-14 docs：两轮审查总结 + 整改计划（REVIEW_ACTION_PLAN）

- 背景：reviewer 相继产出架构债审查（ARCHITECTURE_DEBT_REVIEW_20260814）
  与仓库评审（AnkeShelf_Review_20260814，评级工程 A- / 安全 A / 合规 B）。
- 处理：逐条核验两轮共 20+ 项主张（行号/代码/PyPI/GitHub 证据），
  产出 `docs/REVIEW_ACTION_PLAN.md`：已落地整改 3 笔（63b6994 / 867e7ea /
  ab3c6d8）+ 待办批次 A（token/CI/模板快修）~ E（低优先/延后）+ 驳回记录
  （P3.2 ebooklib 0.20 为 PyPI 最新版；P2.5 reader.js 乱码已修复）。
- 验证：纯文档改动，未跑构建。

### 2026-08-14 android：章节读取失败模型（ChapterReadResult，替代 null 折叠）

- 背景：第二轮架构债审查 P0 立项（BookSession 契约 + 核心数据层 null）。
- 处理：新增 `data/ChapterReadResult.kt`（Success / NotFound / Corrupt / Io +
  textOrEmpty）；`Epub.chapterText` / `NativeBook.chapterText` / `BookSession`
  返回显式结果，越界与缺失条目 → NotFound、容器关闭/权限 → Io、解码失败 →
  Corrupt；SearchIndex 与 `chapterPlainLength` 用 textOrEmpty 保持空串缺省；
  阅读页 `htmlState` 改为 `ChapterUiState`（Html / Error），失败显示明确错误
  而非“正在加载”或空白页。
- 验证：先写失败语义测试（红：旧 API 编译失败）→ 实现 → 全量 111 过 / 1 跳
  （+2：Epub / NativeBook 失败语义用例）；`assembleDebug` 通过。

### 2026-08-14 docs：第二轮架构债审查评估（P0 立项 + 纪律固化）

- 背景：reviewer 对 `AnkeShelf-review-20260814` 产出
  `I:\AnkeShelf_Review_Archive\ARCHITECTURE_DEBT_REVIEW_20260814.md`（P0×2 / P1×2 / P2×2）。
- 核验结论：字面 `catch(Exception)` 为零，带变量 `catch (e: Exception)` 47 处
  抽查均为显式结果转换（EpubError / RepoResult / StoreLoadResult /
  VerifyResult）、缺省语义（配置/颜色/日期解析）或注明降级（编码兜底、
  原子写回退），未见静默吞错；抽象控制与测试方向与现状一致；
  P0「BookSession 契约 + 核心数据层 null」成立且是同一问题——
  `BookSession.chapterText(): String?` 与 `Epub/NativeBook.readFile` 把越界、
  损坏、IO、权限折叠成 null；P1「Silent fallback」已大幅缓解（备份验证已走
  `VerifyResult(false, 具体错误)`，残余折叠归入 P0）；P1「AppContainer
  膨胀」为预防性建议，暂缓拆分只收规则；P2「UI 大文件」部分成立（>500 行
  剩 BookshelfScreen / SearchScreen / WebViewChapterView 等）。
- 动作：路线图新立 P0「章节读取失败模型」（BookSession → ChapterReadResult）；
  AGENTS.md 固化纪律（失败显式化、规模与抽象门槛、业务不变量测试）。
- 验证：纯文档改动，未跑构建。

### 2026-08-14 docs：总体完成状况核验（api-contract 基线 40 → 45）

- 处理：对照仓库现状逐项核验里程碑产物与测试基线，修正 DevLog「当前状态」
  与路线图 §2.1 中过期的 `api-contract 40 方法`（统一备份包新增 3 个 API 后
  实为 45 方法）；确认治理文件（CONTRIBUTING / SECURITY / CODEOWNERS /
  ISSUE_TEMPLATE / PR 模板）与 reader.js 注释编码损坏均已修复。
- 验证：Windows Python 229 项 OK；JS textpos 15 例、api-contract 45 方法、
  bridge v1、reader-lite parts 6 模块、reader-session 均 OK；Android 109 过 / 1 跳。

### 2026-08-14 android：统一 task_id（下载/更新/导出/索引 + 诊断包）

- 处理：`NgaServiceStatus.taskId` / `NgaDownloader.taskId` 贯穿下载与更新；
  导出（书架 / 已下载两处）与搜索索引事件均带 `task_id`；诊断报告回显
  当前任务 `task_id`（空则显示 `-`）；取消 / 失败事件同样携带 `task_id`，
  便于跨 UI / 服务 / 日志 / 诊断包串联一次任务。
- 验证：`testDebugUnitTest` 109 过 / 1 跳；`assembleDebug` 通过。

### 2026-08-14 android：统一备份包（ank-backup/1，与 Windows 同格式）

- 处理：新增 `data/Backup.kt`——`createBackupZip / verifyBackupZip / restoreBackupZip`
  （manifest + 五份 JSON + SHA-256；导入前只读验证，目标已有数据需显式确认覆盖）；
  设置页「数据」新增 备份数据 / 验证备份包 / 导入备份（SAF，覆盖前 AlertDialog
  二次确认）。与 Windows `app/backup.py` 同格式，备份包可跨端互认。
- 验证：`testDebugUnitTest` 109 过 / 1 跳（+3 备份用例：创建校验、篡改失败、
  覆盖守卫）；`assembleDebug` 通过。

### 2026-08-14 win：统一备份包（ank-backup/1）

- 处理：新增 `app/backup.py`——`create_backup`（zip：manifest + 五份 JSON + SHA-256）、
  `verify_backup`（只读：清单 / 校验和 / 可解析性 / 版本字段）、`restore_backup`
  （先验证；目标已存在且未显式覆盖时返回 needs_overwrite 不写盘）；新增
  `/api/backup_create | backup_verify | backup_restore`（backup 文件选择器）与设置页
  「备份数据 / 验证备份包 / 导入备份」按钮（导入覆盖前二次确认）；DATA_CONTRACT 补 §8。
- 验证：Python 229 项 OK（+4 备份用例）；api-contract 45 方法一致；
  UI harness 92 项 PASS / 0 FAIL。

### 2026-08-14 docs：P4 参考仓库研究（首批 5/8）

- 处理：研究 `H:\AnkeShelfReferences` 下已克隆的 5 个仓库（koreader /
  koreader-sync-server / thorium-reader / foliate-js / calibre），产出
  `docs/REFERENCE_MATRIX.md`：克隆 commit 固定、分仓库结论、汇总矩阵，
  以及「text_offset → 多锚点 Locator」演进方向。
- 结论：模式隔离与“精确锚点 + 摘要”双轨继续；同步需稳定 book_id + 可移植
  Locator；未来 Locator 结构参考 Readium2；不引入 Readium SDK / CFI 运行时依赖。
- 验证：纯文档，未跑构建。

### 2026-08-14 android：发布资产摘要接入 Android SOP

- 处理：`android/VERSIONING.md` 发布清单新增“生成发布摘要”步骤——用仓库根
  `scripts/release_manifest.py` 对 `dist/AnkeShelf-vX.Y.Z-android.apk` 生成
  `.release.txt`（版本/commit/数据契约版本/构建环境/APK SHA-256）与
  `.apk.sha256` sidecar，随 Release 上传。
- 验证：对 `dist/AnkeShelf-v1.0.0-android.apk` 实测生成正确。

### 2026-08-14 win：Windows 前端拆分收官——nga_download.js

- 处理：`nga_download.js` 从 870 行降至 622 行——下载/更新/导出/配置四个面板
  构建器拆到 `nga-download-panels.js`；共享 `section/fmtBtn/field/input/numInput/
  select/checkbox/val/check` 与面板引用的任务函数经 `window.NgaPage` 暴露，
  index.html 按 nga_download.js → nga-download-panels.js 顺序加载。
- 踩坑：面板文件最初在加载期解构 `window.NgaPage`（主文件末尾才赋值）导致整页
  测试脚本中断，先跑 harness 抓到 87 FAIL，调换加载顺序后恢复。
- 验证：node --check 全过；UI harness **92 项 PASS / 0 FAIL**。

### 2026-08-14 win：Windows 前端拆分第一刀——settings.js

- 处理：`settings.js` 从 758 行降至 179 行——共享常量与 `section/row/btn` 拆到
  `settings-ui.js`（`window.SettingsUI`），外观/阅读/辅助/快捷键/统计/数据面板
  行构建器拆到 `settings-panels.js`（`window.SettingsPanels`）；index.html 按
  settings-ui → settings-panels → settings 顺序加载。
- 验证：node --check 全过；UI harness **92 项 PASS / 0 FAIL**（拆分前后一致）。

### 2026-08-14 android：P3 NativeReaderScreen Chrome 拆分

- 处理：`NativeReaderScreen.kt` 从 624 行降至 448 行——新增 `NativeReaderChrome.kt`：
  亮度遮罩 / 顶栏 / 底栏 / 目录抽屉 / 图片查看 / 图片组件（BoxScope 扩展，内部
  THEME_CYCLE + themeColor）；外壳只保留状态、进度写入、WebView 装配与生命周期。
  进度/生命周期逻辑零改动，UI 块以回调参数上提。
- 验证：`testDebugUnitTest` 106 过 / 1 跳（行为零变化）；`assembleDebug` 通过。

### 2026-08-14 android：P3 DownloadScreen 大屏拆分

- 处理：`DownloadScreen.kt` 从 982 行降至 349 行——登录配置 + 下载/更新拆到
  `DownloadPanels.kt`，已下载列表/卡片/导出拆到 `DownloadLibraryPanels.kt`
  （同包 internal；通用 DownloadList / DownloadSection 改 internal）。
- 验证：`testDebugUnitTest` 106 过 / 1 跳（行为零变化）；`assembleDebug` 通过。

### 2026-08-14 android：P3 SettingsScreen 大屏拆分

- 处理：`SettingsScreen.kt` 从 1369 行降至 575 行——外观 / 阅读 / 操作·统计·数据·帮助
  面板机械拆到 `SettingsAppearancePanels.kt` / `SettingsReadingPanels.kt` /
  `SettingsMiscPanels.kt`（同包 internal，保留完整 import 块；共享常量与
  SettingsList / SettingsSection / SettingsRow / queryDisplayName 改 internal）。
- 验证：`testDebugUnitTest` 106 过 / 1 跳（行为零变化）；`assembleDebug` 通过。

### 2026-08-14 win：发布资产摘要（release_manifest + CI sidecar）

- 处理：新增 `scripts/release_manifest.py`——输出版本 / commit / 数据契约版本
  （progress schema const）/ Python / 平台 / 构建时间，并对 zip / apk 计算 SHA-256
  与大小；windows.yml 在打包后生成 `AnkeShelf-vX.Y.Z.release.txt` 与
  `.zip.sha256` sidecar 并一并上传。
- 验证：Python 225 项 OK（+3）；对 `dist/AnkeShelf-v1.2.0.zip` 实测输出正确。

### 2026-08-14 win：P3 导出服务接入 TaskManager（试点）

- 处理：`ExportService` 单飞/进度/取消改由 `TaskManager(lanes={"export": 1})` 承载——
  `start` 原子占 lane、线程内经 `run` 执行、进度走 `on_progress`、取消走 cancel 标志并在
  step 上报时抛 `TaskCancelled`；`TaskManager.start` 增加同任务重入幂等；新增 `cancel()`
  与 `/api/export_cancel`、导出页「取消导出」按钮（运行中可用）。
- 验证：Python 222 项 OK（+1 取消用例，导出 7 项全绿）；api-contract 42 方法一致；
  `node --check` 通过。

### 2026-08-14 win：P3 存储恢复能力（损坏隔离 + 备份 + 完整性校验）

- 处理：`app/storage.py` 新增 `backup_previous`（原子写前保留 .bak）、
  `isolate_corrupt` / `load_json_file`（损坏即隔离 .corrupt-* 并回退默认）、
  `verify_json_file`（可解析性/大小/版本号，不读内容值）；shelf / progress / settings /
  annotations / stats 五个 store 载入统一走 `load_json_file`；新增
  `/api/verify_data_integrity`（system_api + registry + ApiClient + MOCK）与设置页
  「验证数据完整性」按钮。
- 验证：Python 221 项 OK（+3 存储用例）；api-contract 41 方法一致；node 检查通过。

### 2026-08-14 android：本地构建 Java 工具链检查

- 处理：新增 `android/scripts/check-toolchain.ps1`——定位 JAVA_HOME/PATH 的 java，
  解析大版本并强制 ≥17，打印 SDK 位置（ANDROID_HOME 或仓库 `.tools/android-sdk`）；
  android/README「本地构建」增加校验步骤。脚本输出保持 ASCII，避免 PS 5.1 编码坑；
  java 版本输出经 `cmd /c` 合并 stderr，规避 `$ErrorActionPreference='Stop'` 误抛。
- 验证：JDK 25（jbr）→ PASS（exit 0）；无效 JAVA_HOME → FAIL（exit 1）。

### 2026-08-14 android：check-release.ps1 增加 APK 内 reader-lite.js SHA 校验

- 现象：Gradle 曾误判资产 UP-TO-DATE 导致旧 `reader-lite.js` 入包，此前只能手工解包确认。
- 处理：`check-release.ps1` 在凭据扫描后提取 APK 内 `assets/reader/reader-lite.js`
  计算 SHA-256 并与源码比对，不一致即 FAIL；脚本内新增文案保持 ASCII
  （PowerShell 5.1 对无 BOM UTF-8 中文按 ANSI 误读会破坏解析）。
- 验证：正常 debug APK → PASS 且双端哈希一致；最小篡改 zip → FAIL（exit 1）。

### 2026-08-14 android：P3 reader-lite.js 模块化拆分

- 处理：现役渲染内核按功能边界切成 `reader-lite.parts/` 6 个模块（00-core /
  10-geometry / 20-textpos / 30-paging / 40-layout / 50-api）；新增
  `android/scripts/bundle-reader-lite.js`（--write 重生成 / 无参字节级校验）与
  `contracts/tests/reader-lite-parts.test.js` 一致性守卫；android.yml 增加校验步骤；
  `androidResources.ignoreAssetsPattern` 使 parts 不进 APK。
- 验证：parts 拼接与现役文件字节一致（37,327 字节）；node 校验 + bridge-contract 通过；
  `testDebugUnitTest` 106 过 / 1 跳；`assembleDebug` 通过，APK 内无 parts、
  `reader-lite.js` 在。

### 2026-08-14 docs：P3 开源治理收尾（dependabot + CHANGELOG）

- 处理：新增 `.github/dependabot.yml`（pip / gradle / github-actions 每周更新，
  依赖锁定后启用）；新增用户可见 `CHANGELOG.md`（Windows v1.0.0–v1.2.0、
  Android android-v1.0.0），README 增加入口；路线图 §2.1 的“HEAD”行改为“功能基线”，
  避免 docs 提交反复改动哈希。
- 验证：纯配置/文档改动，未跑构建。

### 2026-08-14 android：P2 可观测性与诊断闭环

- 处理：新增 `LogEvents`（结构化事件环形缓冲，component event key=value，
  book_id 短哈希）与 `Diagnostics`（`report` 纯函数 + `collect` 设备采集：应用/系统/
  WebView/桥版本、数据文件版本与大小、最近 50 条事件、最近任务状态，脱敏不含凭据
  与正文）；设置页「数据」新增“导出诊断信息”（SAF 存 txt）；bridge 握手异常、
  搜索索引构建、NGA 下载/更新完成接入结构化事件。
- 验证：`testDebugUnitTest` 106 过 / 1 跳（+4 诊断/脱敏/环形缓冲用例）；
  `assembleDebug` 通过。

### 2026-08-14 android：P2 错误模型与 null 清理

- 现象：`readJsonOrNull` 全吞异常、失败原因不可区分；仓库方法返回 null，调用方只能猜；
  `Settings.get(key): Any?` 已无生产调用方。
- 处理：新增 `StoreLoadResult`（Ok / Missing / Corrupt / IoError）与 `readJsonStore`，
  五个 store 载入显式区分失败并回退默认 + `logWarn`；新增 `BookRepoError`
  （NotFound / Corrupt / Io / Permission）与 `RepoResult`，`openSession / importEpub /
  registerNativeDir / registerEpubFile` 改返回显式结果——书架导入失败 Toast 展示
  Domain 错误、下载登记失败转为 NgaHttpException；删除 `Settings.get(key)`，
  测试改走类型化 `getAll()`。
- 验证：`testDebugUnitTest` 102 过 / 1 跳（+3 仓库错误分类用例）；`assembleDebug` 通过。

### 2026-08-14 android：P2 章节 HTML 清洗改 jsoup DOM 白名单

- 现象：`sanitizeReaderBody()` 为正则“尽力而为”，畸形写法可绕过或误删后续正文
  （`<script src/>` 吞掉同行内容、实体编码 `javascript:` href 不被识别）。
- 处理：改为 jsoup DOM 级清洗——危险标签集直接移除、非白名单标签解包、属性按标签
  白名单 + 事件属性（on*）与 javascript:/vbscript:/data:text/html 链接剔除；
  关闭 prettyPrint 避免块级元素被插入换行。新增 4 条安全用例（自闭合 script、
  实体编码 javascript、表单/元数据移除、NGA 排版保留），旧用例按 jsoup 规范化调整。
- 验证：ReaderHtmlTest 13 条全绿；全量 `testDebugUnitTest` 99 过 / 1 跳；
  `assembleDebug` 通过。

### 2026-08-14 android：P1 阅读桥协议版本握手 + 进度事件回放

- 现象：桥协议无版本握手、`saveProgress` 多位置参数；进度语义散在 tracker 与真实
  调度器里，历史故障（9.43–9.59）无法离线回放复现。
- 处理：`reader-lite.js` ready 握手改结构化 payload `{bridgeVersion:1, capabilities}`
  （新增 `bridgeVersion/bridgeReadyPayload/emitReady` 导出）；新增 `BridgeProtocol.kt`
  解析校验，不兼容时 `onBridgeVersionMismatch` + 诊断日志；新增纯决策层
  `ProgressModel.kt`（旧状态+事件→新状态+落盘，虚拟时钟），`ChapterProgressTracker`
  改为委托模型、仅保留真实调度器与落盘；新增 `contracts/fixtures/progress/` 7 份
  事件序列夹具（防抖/翻页即时/模式隔离/比例锚点/换章 flush/dispose 迟到/连续重进），
  `ProgressModelTest` 回放、`bridge-contract.test.js`（Node）校验握手；DisciplineTest
  增加桥版本纪律。
- 验证：`testDebugUnitTest` 95 过 / 1 跳（+5）；`assembleDebug` 通过并解包确认 APK 内
  reader-lite.js 含 bridgeVersion/emitReady；Node bridge-contract OK；真实时间回归
  `ChapterProgressTrackerTest` 9 条保持通过。

### 2026-08-14 win：依赖锁定 + 本机 Python 3.14 PATH

- 处理：`requirements.txt` 拆为 `requirements.in` / `requirements-build.in`（人工维护），
  经 pip-tools 生成带哈希的 `requirements.lock` / `requirements-build.lock`；PyInstaller
  移入构建锁；windows.yml / nightly.yml 改为按 lock 安装；README / CONTRIBUTING 同步。
- 本机环境：Python 3.14.5（pythoncore-3.14-64）已写入用户 PATH；锁以 CI/发行版 3.12
  为基线生成，实测在 3.14 虚拟环境可安装、全量 218 项单测通过。
- 验证：3.14 全新 venv 安装 lock → imports OK → `unittest discover tests` 218 项 OK。

### 2026-08-14 docs：开源治理文档落地

- 处理：新增 CONTRIBUTING.md / SECURITY.md / Issue·PR 模板 / CODEOWNERS /
  THIRD_PARTY_NOTICES.md，README 增加参与与安全入口；路线图 P3 标记部分完成
  （字体去重、dependabot、CHANGELOG 拆分等待办）。
- 验证：纯文档改动，未跑构建。

### 2026-08-14 win：reader.js 乱码注释修复

- 修复：`web/js/reader.js` 8 处乱码注释——5 处从父提交 `cb87a35` 找回原文
  （含用户可见 Toast 文案“本章内容较大，已自动切换为滚动阅读”），
  3 处为 v1.2.0 重构新增、按代码语义重建。
- 验证：`node --check` 通过，无 `??` 残留；未改运行逻辑。

### 2026-08-14 P1：首批 ADR 补录

- 处理：新增 `docs/adr/README.md`（索引 + 状态约定）与 0001–0005 五份 ADR
  （双端边界、Compose+WebView、text_offset UTF-16、原生书只追加、JSON 权威存储）；
  DevLog 头部增加 ADR 链接；路线图“首批 ADR”标记完成。
- 验证：纯文档改动，未跑构建；内容与 ARCHITECTURE / DATA_CONTRACT /
  NATIVE_BOOK_FORMAT / TEXT_NORMALIZATION_SPEC 交叉核对一致。

### 2026-08-14 纪律：文档漂移检查写入 AGENTS.md

- 现象：文档漂移此前靠一次性手工排查，缺常驻纪律约束。
- 处理：AGENTS.md「工作方式」新增“文档漂移检查”条目（README 重点核对版本表与系统要求），
  “常用命令”补契约/API 守卫命令；DevLog「纪律提醒」同步该条。
- 验证：纯文档改动，未跑构建。

### 2026-08-14 文档漂移检查与同步（本次提交）

- 现象：P0 / P1 落地后，DevLog“当前状态”与路线图仍停留在 `4810d0c`、211 项、
  “契约 CI 待新增”等旧描述。
- 处理：同步功能基线 `c8f90cf`、Python 218 项、JS 契约清单（含 api-contract）、
  CI 含 `contracts.yml`；标记 P0 / P1 已完成状态。
- 验证：API / 契约守卫实跑全绿（api-contract 40 方法、textpos 15 例、
  test_api_contract + test_contracts 共 10 项 OK）。

### 2026-08-14 P1：契约/API 漂移守卫落地

- 现象：后端 `_HANDLERS` 与前端 `api-client.js` METHODS、`bridge.js` MOCKS 无自动对照；
  MOCKS 实际缺 `export_diagnostics`、`get_chapter_plaintext`、`search_more` 三个方法。
- 处理：`app/api/__init__.py` 新增 `api_manifest()`；`api-client.js` 支持 Node 加载；
  `bridge.js` 补齐 3 个 MOCK；新增 `contracts/tests/api-contract.test.js`（Node 双向比对）
  与 `tests/test_api_contract.py`（后端↔前端↔MOCKS 覆盖）；新增
  `.github/workflows/contracts.yml`（独立触发，未扩大 android.yml）。
- 验证：Python 全量 218 项 OK（+2）；Node api-contract 40 方法一致、textpos 15 例 OK；
  `node --check` 通过。

### 2026-08-14 P0：发行包启动失败友好提示与文档

- 现象：pythonnet/.NET 加载失败（`Failed to resolve Python.Runtime.Loader.Initialize`）
  导致发行版启动崩溃；用户已自行修复本机环境，本轮仅补项目侧提示与文档。
- 处理：新增 `app/startup_errors.py`（运行时加载失败判定 + 友好指引文案 +
  MessageBox 兜底）；`app/main.py` 在 `webview.start` 捕获 `RuntimeError` 后弹窗、
  记日志并退出码 1；README 与 使用说明 补充 .NET Framework 4.8 要求与“解除锁定”；
  新增 `tests/test_startup_errors.py`。
- 验证：`python -m unittest discover tests` 全量通过（新增 5 条启动错误用例）；
  Node 契约测试不受影响。纯 Windows 端代码 + 双端共享文档，未改契约/Android/CI，
  未重新打包发行版。

### 2026-08-13 文档状态同步（本次提交）

- 现象：`4810d0c` 提交后本文件“当前状态”仍停留在 `1ea4c95` 与“含未提交重构”
  的旧描述；多份非归档文档存在过期状态（路线图基线 HEAD、VERSIONING 发布记录
  SHA256 与“未推送”、M4 验收遗留项、原生渲染器/旧 reader.js 引用、
  ARCHITECTURE 前端文件清单等）。
- 处理：全量通读仓库文档后，最小化同步非归档文档到当前 HEAD / 版本线 / 测试基线；
  历史文档（M4 验收、代码/性能/安全审查、原生渲染器、NGA 集成方案）加“状态”
  说明而不改写历史结论。纯文档改动，未改代码、契约与 CI。
- 验证：`python -m unittest discover tests` 211 项 OK；Node 契约 15 例 +
  reader-session OK；`git status` 仅文档文件变更。

### 2026-08-12 DevLog 重构：归档 + 教训分类 + 现役日志瘦身

- 现象：DevLog 约 18 万字符，新会话定位成本上升；四份架构评审建议把
  历史/教训/规范分离。
- 处理：
  - 原日志全量归档至 `docs/DEVLOG_ARCHIVE.md`（保留原始记录 + 时间轴索引）；
  - 新增 `docs/LESSONS_LEARNED.md`（9 类教训：调试/进度/渲染/契约/发布/
    安全/网络/Android/纪律）；
  - 本文件精简为“当前状态 + 本机环境 + 不入库内容 + 最近流水 + 待办 + 纪律”；
  - `AGENTS.md`、`docs/GLOSSARY.md` 引用同步。
- 验证：纯文档改动；归档为原文件全量复制（181,179 字节），未丢内容；未跑构建。

### 2026-08-12 整合四份架构评审文档并输出路线图

- 来源：`I:\AnkeShelf_Review_Archive\AnkeShelf_Architecture_Improvement_Proposal.md`、`I:\AnkeShelf_Review_Archive\review1.md`、
  `I:\AnkeShelf_Review_Archive\review2.md`、`I:\AnkeShelf_Review_Archive\架构债清理清单.md`。
- 产出：`docs/ARCHITECTURE_ROADMAP.md`（P0 发行包崩溃 → P1 契约/API 守卫、
  桥协议版本 + 进度回放、依赖锁定、首批 ADR → P2 jsoup 清洗、错误模型、
  诊断闭环 → P3 拆分/任务试点/存储恢复/开源治理 → P4 参考仓库与触发式扩展）。
- 验证：纯文档改动；未跑构建。

### 2026-08-10 会话交接快照（原 10.14）

- 内容已并入上文“当前状态 / 本机环境 / 待办 / 纪律”，原文见归档 §10.14。

### 2026-08-10 B8：测试补齐（安全回归 + ZIP 炸弹 + 性能基准 + nightly CI）

- 新增 `tests/security/` 7 条、`tests/performance/bench.py` + `baseline.json`、
  `.github/workflows/nightly.yml`；EPUB 上限防护；Python 211 项 OK。详见归档 §10.13。

### 2026-08-10 B7：TaskManager 抽象 + 统一日志字段

- `app/tasks.py`（按 lane 单飞）、`app/logutil.py`（component event key=value）；
  NGA 暂不迁移。详见归档 §10.12。

> 更早记录见 [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md) 时间轴索引。


