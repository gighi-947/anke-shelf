# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：现役开发日志——只保留“当前状态”与“最近流水”。
> 历史记录（全量、按时间轴索引）→ [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)
> 经验教训（分类归纳）→ [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)
> 架构整合路线图 → [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)
> 决策记录（ADR）→ [docs/adr/README.md](docs/adr/README.md)
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件“最近流水”追加记录**
> （日期 + 提交 + 现象/结论）。

## 1. 当前状态（2026-08-14）

- 功能基线 HEAD：`ad034b8`（android: P1 阅读桥协议版本握手 + 进度事件回放）；
  此前功能提交（P0 / P1 契约守卫 / ADR / 治理文档 / reader.js 修复 / 依赖锁定）
  均已推送 `origin/main`。
- 推送状态：`ad034b8` 待推送；工作树干净。
- 版本线：Windows `v1.2.0`（已发布，AnkeShelf-v1.2.0.zip）；
  Android `android-v1.0.0`（已发布，AnkeShelf-v1.0.0-android.apk）。
- 测试基线（Windows/JS 于 2026-08-14 实跑复核；Android 沿用 2026-08-10 本地报告）：
  - Windows Python：`python -m unittest discover tests` = 218 项 OK
    （本机 Python 3.14 与沙箱 3.12 双环境）；
  - JS：`node contracts/tests/textpos.test.js`（15 例）、
    `node contracts/tests/api-contract.test.js`（40 方法一致）、
    `node contracts/tests/bridge-contract.test.js`（桥版本 1）、
    `node tests/js/reader-session.test.js` 均 OK；
  - Android JVM：`gradlew testDebugUnitTest` = 95 过 / 1 跳；DisciplineTest 在岗；
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
