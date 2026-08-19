# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：现役开发日志——只保留“当前状态”与“最近流水”。
> 历史记录（全量、按时间轴索引）→ [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)
> 经验教训（分类归纳）→ [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)
> 架构整合路线图 → [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)
> 决策记录（ADR）→ [docs/adr/README.md](docs/adr/README.md)
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件“最近流水”追加记录**
> （日期 + 提交 + 现象/结论）。

## 1. 当前状态（2026-08-19）

- 当前开发基线：`main`；骨碌碌阅读交互改造（悬浮气泡 / 侧边评论 / 段落评论 /
  沉浸总览 / 骰点解锁菜单）已全部合入并发布 v1.4.0；五批接手风险修复已合入；
  P5 批次已启动并完成 P5-A 快赢批、P5-B 裂图修复、P5-D 封面系统、
  P5-E1 Cookie 粘贴解析、P5-E2 Android 应用内登录
  （含 UI 图标规范核查）；多轮架构收敛已完成：
  EventBus→显式回调、API 错误统一到 HTTP/ApiError、reader-lite 状态机
  Step 0–4、TaskManager 统一 NGA/Gululu/Export；
  文档漂移治理已强化
  （AGENTS §5 高漂移清单 + `scripts/check-doc-drift.ps1`）。
  精确提交与远端状态以 `git log` / `git status` 为准。
- 版本线：Windows `v1.4.0`（已发布，AnkeShelf-v1.4.0.zip）；
  Android `android-v1.0.0`（已发布，AnkeShelf-v1.0.0-android.apk）。
- 测试基线（Windows / JS / Android JVM 于 2026-08-19 实跑复核）：
  - Windows Python：`python -m unittest discover tests` = 303 项
    （本机 Python 3.14：4 跳；bundled Python 3.12：全量通过）；
  - JS：`node contracts/tests/textpos.test.js`（15 例）、
    `node contracts/tests/api-contract.test.js`（55 方法一致）、
    `node contracts/tests/api-contract-launch.test.js`（Python 启动失败诊断）、
    `node contracts/tests/bridge-contract.test.js`（桥版本 1）、
    `node contracts/tests/reader-lite-parts.test.js`（6 parts / 37377 字节）、
    `node tests/js/reader-session.test.js`、`node tests/js/nga-cookie.test.js` 均 OK；
  - Android JVM：`gradlew testDebugUnitTest` = 128 过 / 1 跳；DisciplineTest 在岗；
  - Android 真机：ELE-AL00（Android 10）instrumentation 11 / 11 通过；
  - UI 实机 harness：`python -m tests.ui.runner` = 97 项 PASS（需桌面 WebView2）；
  - 骨碌碌正式冒烟 `formal_ui_smoke.js`（桌面 + 430px，含骰点菜单/段落评论/总览/
    抽屉保位断言）全过。
- CI：`windows.yml`、`android.yml`、`nightly.yml`、`contracts.yml`。
- 权威基线：版本线见 README 版本表；测试基线详见 `docs/MAINTENANCE_GUIDE.md` §7；
  待办以 `docs/ARCHITECTURE_ROADMAP.md` 为准（本节为快速快照）。

## 2. 本机环境（Windows 开发机）

- Python：本机 `F:\Users\Administrator\AppData\Local\Python\pythoncore-3.14-64\python.exe`
  （3.14.5，已写入用户 PATH，含 Scripts）；沙箱会话兜底
  `F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`（3.12.13）。
- Node：`F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe`
- Android 构建：`JAVA_HOME=D:\Android\AndroidStudio\jbr`、
  `GRADLE_USER_HOME=F:\Users\Administrator\.gradle`、
  `ANDROID_HOME=D:\Codex\project1\.tools\android-sdk`；命令
  `android\gradlew.bat -p android testDebugUnitTest assembleDebug`。
- adb：`D:\Codex\project1\.tools\android-sdk\platform-tools\adb.exe`
  （需 `HOME/USERPROFILE=F:\Users\Administrator`）。

## 3. 本地不入库/勿打包内容

- `.local/archive/`：历史归档，含真实 NGA uid/cid 备份（gitignore 覆盖，勿入库）；
- `android/keystore/`、`keystore.properties`、`local.properties`：签名与 SDK 配置；
- `ngapost2md-python/config.ini`：本地 NGA 凭据（打包只带 `.example`）；
- `dist/`、`build/`、`.tools/`：构建产物与工具链。

## 4. 最近流水

### 2026-08-20 win：NGA 官方表情图直连与失败降级

- 背景：NGA 官方表情图（img4.nga.178.com/ngabbs/post/smile）经本地代理后仍无法显示。
- 修复：
  - `_rewrite_nga_image_src` 对 `/ngabbs/post/smile/` 表情图不再改写为代理，保持直连；
  - 前端若表情图加载失败，不再显示“图片加载失败”大占位，而是降级为 `(表情名)` 文本表情。
- 验证：`tests.test_server` 36 项 OK（表情直连断言更新）。
### 2026-08-20 win：Gululu 标签同步统一 + 最近阅读标签尺寸对齐

- 背景：Gululu 标签仍在封面上，未与 NGA 标签统一；最近阅读标签视觉偏大。
- 修复：
  - `gululuBadge` 改为 `gululu-tag`，与 `nga-tag` 同尺寸同位置；
  - 网格/列表/最近阅读的 Gululu 标签全部移到封面下方/标题区域；
  - 最近阅读新增 `.recent-tags` 容器，标签与书架页一致（9px 小标签）。
- 验证：JS 语法/契约全绿。
### 2026-08-20 win：统一 NGA 标签位置

- 背景：NGA 标签在网格/列表/最近阅读中位置不一致（封面徽标/标题内/正文 meta）。
- 修复：统一为“封面下方/标题区域”的 `.nga-tag` 小标签；最近阅读不再在封面上显示 NGA 徽标，改为标题下方小标签，meta 只保留作者与进度。
- 验证：JS 语法/契约全绿。
### 2026-08-20 win：修复封面同步误清空与 NGA 标签缺失

- 背景：热更新同步封面时，若新 EPUB 提取不到封面会把已有 cover_rel 清空，导致骨碌碌封面消失；同时 NGA 标签从网格封面移除后没有补到别处。
- 修复：
  - `_register_gululu_book` 与 `sync_cover_from_epub` 仅在提取到新封面时更新 cover_rel；提取不到时保留旧封面缓存；
  - 网格卡片在封面下方新增 `NGA` 小标签，避免默认封面文字的同时保留来源标识。
- 验证：JS 语法/契约全绿；gululu/server 相关 53 项测试 OK。
### 2026-08-20 win：骨碌碌封面本地缓存与热更新同步

- 背景：用户要求骨碌碌网络封面本地化缓存，并在每次热更新时同步重载。
- 现状与增强：
  - 导入/更新时 `build_epub(fetch_cover=True)` 会把网络封面写入 EPUB，注册书架时 `extract_cover` 提取到本地 `covers/`；
  - 热更新重建时已通过 `book_register` 重新提取封面；
  - 本次补充：即使热更新判定“已是最新/无新楼层”，也会调用 `sync_cover_from_epub` 从当前 EPUB 重新提取封面并同步 `cover_rel`。
- 验证：`tests.test_gululu_update`/`test_gululu_service`/`test_server` 53 项 OK。
### 2026-08-20 win：前端始终加载 cover_url 强制使用服务端骰子图

- 背景：服务端已返回骰子 SVG，但前端在 cover_rel 为空时不请求 cover_url，导致旧文字占位仍可能可见。
- 修复：前端不再以 cover_rel 判断是否加载封面图；只要 cover_url 存在就加载。缺失封面时服务端返回骰子 SVG，图片会覆盖任何残余文字占位。
- 验证：JS 语法/契约全绿。
### 2026-08-20 win：缺失封面统一回退服务端骰子 SVG

- 背景：即使前端逻辑正确，旧缓存/旧前端仍可能因 cover_url 请求 404 而显示裂图图标和文字。
- 修复：`server.py` 的 `/cover/<id>` 在找不到封面文件时不再返回 404，而是返回一个纯色无文字的骰子 SVG 占位图。
  这样即使前端使用旧逻辑，也会把骰子图作为真实封面加载，覆盖掉任何文字占位。
- 验证：`tests.test_server` 36 项 OK（含缺失封面回退 SVG 用例）。
### 2026-08-20 win：前端缓存强制刷新

- 背景：用户运行 run_app.py 仍看到旧前端，疑似 WebView 缓存。
- 修复：
  - `web/index.html` 所有 css/js 引用增加 `?v=20260820` 版本参数；
  - `server.py` 对 `index.html` 返回 `Cache-Control: no-cache`，避免入口页被长期缓存；
  - 同步更新本地 `dist/AnkeShelf/_internal/web/index.html`。
- 验证：`tests.test_server` 36 项 OK。
### 2026-08-20 win：NGA 与 Gululu 封面回退逻辑隔离

- 背景：reset_cover 对 NGA 书也尝试重新提取封面，导致 KeyError 日志，且用户要求 NGA 完全不参与封面获取。
- 修复：`reset_cover` 对 `nga_tid > 0` 的书直接清空封面返回，不进入重新提取分支；只有非 NGA（Gululu/EPUB）才尝试恢复内嵌封面。
- 验证：相关 Python 61 项测试 OK。
### 2026-08-20 win/android：默认封面回退机制修正

- 背景：用户指出 Gululu“恢复默认封面”后仍显示文本封面和“图片未加载”，并蔓延到 NGA 无封面书。
- 根因：
  - Gululu 无封面时前端仍走文字占位分支，只有 NGA 走骰子；
  - `reset_cover` 只删除封面，不会重新提取 EPUB 内嵌原始封面。
- 修复：
  - 前端所有封面占位（网格/列表/最近阅读/书籍管理）对 NGA 和 Gululu 统一使用骰子占位，不再生成文字；
  - `reset_cover` 删除自定义封面后尝试从书籍重新提取内嵌封面；提取不到则保持无封面，前端回退骰子；
  - NGA 原生书无内嵌封面，重置后自然保持骰子占位。
- 验证：JS 语法/契约全绿；Python 303 项 OK（4 跳）。
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

## 5. 待办与延后项

> 2026-08-18 核对：本节已与代码实际状态同步（此前 P1/P2 多项已落地但未更新，
> 属文档漂移）。细节与延后理由见 [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)。

**已完成（不再列入待办）：**

- P0：发行包启动崩溃——项目侧友好提示 + 文档（`b6fba10`）；章节读取失败模型
  `ChapterReadResult`（`867e7ea`）。
- P1：契约/API 漂移守卫（`c8f90cf`）、Android 桥协议版本 + 进度事件回放
  （`ad034b8`，`ProgressModel` 纯函数 + 7 份 fixtures）、依赖锁定与构建可复现
  （`edaf442`，带哈希 lock + APK SHA-256 比对）、首批 ADR（`docs/adr/` 5 份）。
- P2：jsoup 白名单清洗 `sanitizeReaderBody()`（`d697330`）、Android 错误模型
  `RepoResult`/`ChapterReadResult`/`StoreLoadResult` + review3 残余 null 收敛
  （`9e84c4c`）、Android 诊断闭环 `LogEvents`/`Diagnostics` + task_id 全链路
  （`cb40cee`、`b63809f`）。
- P3（已完成部分）：`reader-lite.js` parts 模块化、`SettingsScreen`/
  `DownloadScreen`/`NativeReaderScreen`/前端 `settings.js`/`nga_download.js`
  拆分；Windows 存储恢复（`isolate_corrupt` + `.bak` +
  `verify_data_integrity`）；统一备份包 `ank-backup/1` 双端落地；
  开源治理（CONTRIBUTING/SECURITY/模板/CODEOWNERS/Dependabot/
  THIRD_PARTY_NOTICES/字体去重/乱码修复）。
- P4：参考仓库 5/8 已克隆并产出 `docs/REFERENCE_MATRIX.md`。

**真实待办（按优先级）：**

- P5（当前批次，2026-08-18 用户 issue）：A 快赢、B NGA 裂图修复、
  D 封面系统、E1 Cookie 粘贴解析已完成；剩余/暂缓：
  - E2 Android 应用内 WebView 登录已完成；Windows E2 二级窗待后续
  - C 滚动到底自动翻章（暂不实施，按用户要求；进度类必跑回归）
  - F NGA 楼中楼评论（暂不实施，按用户要求；最大件）
  子项明细见 ROADMAP §3 P5。
- P3：Android 数据完整性校验入口——Android 已有 `isolateCorrupt` 但缺
  `verify_data_integrity` 等价 API 与设置页入口（Windows 已有完整实现可参照）。
- P3（保持延后，等真实痛点）：`BookshelfScreen.kt`/`SearchScreen.kt`/
  `reader.js` 等剩余大文件拆分——路线图原则"只在出现第二个真实调用边界时拆"。
- P3（保持延后）：Android NGA 下载迁入统一 `TaskManager`——路线图明确推迟。
- P4（保持延后）：参考仓库剩余 3 个（readest/Kavita/LibraReader）需网络通道；
  第二书源 / SQLite / 同步 / 插件——等第二个真实实现或量化瓶颈触发。

## 6. 纪律提醒（新会话必守）

- 进场先读 AGENTS.md；历史记录查 docs/DEVLOG_ARCHIVE.md，教训查
  docs/LESSONS_LEARNED.md；
- 改动必补记本文件“最近流水”（日期 + 提交 + 现象/结论）；
- 推送代码/发行版必须用户明确授权；双端共享文件改动先做 Diff 影响检查；
- 改动涉及 HEAD/版本线/测试基线/CI 清单时，收尾做文档漂移检查，同步非归档文档到
  实际状态（README 重点核对版本表与系统要求；归档 DEVLOG_ARCHIVE 不改写）；
- 进度类改动必须跑“滚动/翻页 → 退出 → 重进”回归；改 JS 后校验 APK 内脚本；
- 发布前跑凭据扫描（Windows：检查 dist 无 config.ini/nga_config；Android：
  `android/scripts/check-release.ps1`）。
