# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：现役开发日志——只保留“当前状态”与“最近流水”。
> 历史记录（全量、按时间轴索引）→ [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)
> 经验教训（分类归纳）→ [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)
> 架构整合路线图 → [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)
> 决策记录（ADR）→ [docs/adr/README.md](docs/adr/README.md)
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件“最近流水”追加记录**
> （日期 + 提交 + 现象/结论）。

## 1. 当前状态（2026-08-20）

- 当前开发基线：`main`；骨碌碌阅读交互改造（悬浮气泡 / 侧边评论 / 段落评论 /
  沉浸总览 / 骰点解锁菜单）已全部合入并发布 v1.5.1；五批接手风险修复已合入；
  P5 批次已启动并完成 P5-A 快赢批、P5-B 裂图修复、P5-D 封面系统、
  P5-E1 Cookie 粘贴解析、P5-E2 双端应用内登录（Android WebView +
  Windows pywebview 二级窗）、NGA 主题自适应
  （含 UI 图标规范核查）；多轮架构收敛已完成：
  EventBus→显式回调、API 错误统一到 HTTP/ApiError、reader-lite 状态机
  Step 0–4、TaskManager 统一 NGA/Gululu/Export；
  文档漂移治理已强化
  （AGENTS §5 高漂移清单 + `scripts/check-doc-drift.ps1`）。
  精确提交与远端状态以 `git log` / `git status` 为准。
- 版本线：Windows `v1.5.1`（已发布，AnkeShelf-v1.5.1.zip）；
  Android `android-v1.1.0`（已发布，AnkeShelf-v1.1.0-android.apk）。
- 测试基线（Windows / JS / Android JVM 于 2026-08-20 实跑复核）：
  - Windows Python：`python -m unittest discover tests` = 318 项
    （本机 Python 3.14：4 跳；bundled Python 3.12：全量通过）；
  - JS：`node contracts/tests/textpos.test.js`（15 例）、
    `node contracts/tests/api-contract.test.js`（59 方法一致）、
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

### 2026-08-20 release：Windows v1.5.1 / Android v1.1.0

- 版本：Windows `v1.5.1`；Android `android-v1.1.0`（versionCode 2）。
- 产物：`dist/AnkeShelf-v1.5.1.zip`、`dist/AnkeShelf-v1.1.0-android.apk`。
- 内容：P5-D 封面系统、P5-E1/E2 NGA 凭据傻瓜化、NGA 主题自适应、
  默认封面随主题/色板自适应、NGA 官方表情图直连与文字降级、
  骨碌碌封面本地缓存与热更新同步、书籍管理页、更多管理二级菜单等。
- 验证：Windows 318 项、JS 契约全绿（59 方法一致）、Android JVM 128/1、Android assembleRelease 成功。
### 2026-08-20 win：P5-E2 Windows 应用内 NGA 登录（pywebview 二级窗）

- 背景：P5-E2 Android 先行已完成；Windows 端仍需从 F12/Cookie 粘贴配置，
  本次补齐 pywebview 二级窗登录。
- 改动：
  - 新增 `app/nga_login.py`：惰性创建 NGA 登录二级窗（固定 bbs.nga.cn），
    从 WebView2 Cookie 提取 `ngaPassportUid`/`ngaPassportCid` 保存到
    `nga_config.ini`，成功后关窗并清理 WebView Cookie；
  - API 新增 `nga_login_start/status/extract/cancel`；
  - 下载面板「配置」页新增“在应用内登录”按钮与提取/取消操作，轮询登录窗状态；
  - 主窗口关闭时先销毁登录二级窗，避免其阻止应用退出。
- 验证：`python -m unittest discover tests` = 318 项 OK（4 跳）；
  `node contracts/tests/api-contract.test.js` 59 方法一致；JS 语法全绿。
### 2026-08-20 win/android：移除 NGA 下载深/浅色选择

- 背景：主题已自适应，NGA 下载不再需要深/浅色选项。
- 改动：
  - Windows 下载/更新表单移除“主题”下拉框与 theme 参数；
  - Android 下载页移除“主题”FilterChip 与 Intent 的 theme extra；
  - UI harness 移除对已删除下拉框的断言。
- 验证：JS 语法 OK；Android `compileDebugKotlin` BUILD SUCCESSFUL。
### 2026-08-20 win/android：NGA 楼层/引用/评论框随主题自适应

- 背景：NGA 下载时把浅/深主题色写死进章节 HTML，读者切换主题后会出现文字被遮挡。
- 修复：
  - Windows `native_book._css` 改为自适应 CSS，用 `--reader-bg/--reader-fg/--reader-accent` 和 `color-mix` 覆盖楼层/引用/评论内联样式；
  - Web 阅读器在 iframe 根节点注入这三个 CSS 变量；
  - Android `reader.css` 增加 `.nga-quote/.nga-comment/.floor-head/.comment-head/.nga-dice` 的自适应覆盖。
- 效果：不再需要按浅/深模式分别下载；阅读器当前主题自动决定对比度。
- 验证：Python native/server 相关 42 项 OK；JS 语法 OK；Android `assembleDebug` BUILD SUCCESSFUL。
### 2026-08-20 win：默认深色主题映射夜间色板

- 背景：默认 dark 主题在 PALETTES 中没有对应 id，导致封面取到浅色背景。
- 修复：`effectiveCoverColors` 将 `dark` 映射到 `night` 色板，浅色映射到 `default-light`。
- 验证：JS 语法检查 OK。
### 2026-08-20 win：默认封面适配全部预设/自定义色板 + 即时刷新

- 背景：预设色板多套未适配，且主题切换后封面需退出重进才生效。
- 修复：
  - `Theme.coverUrl` 改为直接传递当前有效 `bg`/`fg` 十六进制色值，服务端按实际色板生成骰子 SVG；
  - 主题切换/系统主题变化/设置页色板修改后立即 `Shelf.render()`，无需重启。
- 验证：`tests.test_server` 38 项 OK（含自定义色值用例）。
### 2026-08-20 win：默认封面骰子图随主题自适应

- 背景：默认封面 SVG 固定深色，浅色/羊皮纸主题下不协调。
- 修复：
  - `Theme.coverUrl` 在封面 URL 上附加 `theme=light|sepia|dark`；
  - `server.py` 缺失封面时按主题生成对应背景/线条颜色的骰子 SVG。
- 验证：`tests.test_server` 37 项 OK（含主题参数用例）。
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

> 更早详细流水已归档到 [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)（2026-08-20 二次归档）。

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
  - E2 双端应用内登录已完成（Android WebView + Windows pywebview 二级窗）
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
