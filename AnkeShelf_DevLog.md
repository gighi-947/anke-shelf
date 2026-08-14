# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：现役开发日志——只保留“当前状态”与“最近流水”。
> 历史记录（全量、按时间轴索引）→ [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)
> 经验教训（分类归纳）→ [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)
> 架构整合路线图 → [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)
> 决策记录（ADR）→ [docs/adr/README.md](docs/adr/README.md)
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件“最近流水”追加记录**
> （日期 + 提交 + 现象/结论）。

## 1. 当前状态（2026-08-14）

- 功能基线 HEAD：`b63809f`（android: 统一 task_id）；此前功能提交
  （P0 / P1 / P2 / P3 / P4 首批）均已推送 `origin/main`。
- 推送状态：与 `origin/main` 同步；工作树干净。
- 版本线：Windows `v1.2.0`（已发布，AnkeShelf-v1.2.0.zip）；
  Android `android-v1.0.0`（已发布，AnkeShelf-v1.0.0-android.apk）。
- 测试基线（Windows / JS / Android 均于 2026-08-14 实跑复核）：
  - Windows Python：`python -m unittest discover tests` = 229 项 OK
    （本机 Python 3.14 与沙箱 3.12 双环境）；
  - JS：`node contracts/tests/textpos.test.js`（15 例）、
    `node contracts/tests/api-contract.test.js`（40 方法一致）、
    `node contracts/tests/bridge-contract.test.js`（桥版本 1）、
    `node tests/js/reader-session.test.js` 均 OK；
  - Android JVM：`gradlew testDebugUnitTest` = 109 过 / 1 跳；DisciplineTest 在岗；
  - UI 实机 harness：`python -m tests.ui.runner` = 92 项 PASS（需桌面 WebView2）。
- CI：`windows.yml`、`android.yml`、`nightly.yml`、`contracts.yml`。

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

- 来源：`H:\AnkeShelf_Architecture_Improvement_Proposal.md`、`H:\review1.md`、
  `H:\review2.md`、`H:\架构债清理清单.md`。
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

- P0：已完成（项目侧友好提示 + 文档，`b6fba10`；未重新打包发行版）。
- P1：契约/API 漂移守卫已完成（`c8f90cf`）；剩余——Android 桥协议版本 +
  进度事件回放、依赖锁定与构建可复现、首批 ADR；
- P2：jsoup 清洗、Android 错误模型/null 清理、Android 诊断闭环；
- P3：大文件渐进拆分、TaskManager 试点、存储恢复能力、开源治理与仓库卫生；
- P4：参考仓库克隆（网络恢复后）；第二书源 / SQLite / 同步 / 插件（需求触发）。
- 细节与延后理由见 [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)。

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
