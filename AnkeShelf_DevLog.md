# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：现役开发日志——只保留“当前状态”与“最近流水”。
> 历史记录（全量、按时间轴索引）→ [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)
> 经验教训（分类归纳）→ [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)
> 架构整合路线图 → [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件“最近流水”追加记录**
> （日期 + 提交 + 现象/结论）。

## 1. 当前状态（2026-08-12）

- HEAD：`1ea4c95`（docs: 补记 10.14 会话交接快照并更新日志头部），分支 `main`，
  已推送 GitHub。
- 工作树：含未提交的文档重构（本文件、docs/DEVLOG_ARCHIVE.md、
  docs/LESSONS_LEARNED.md、docs/ARCHITECTURE_ROADMAP.md，以及
  AGENTS.md / GLOSSARY 引用同步）。
- 版本线：Windows `v1.2.0`（已发布，AnkeShelf-v1.2.0.zip）；
  Android `android-v1.0.0`（已发布，AnkeShelf-v1.0.0-android.apk）。
- 测试基线：
  - Windows Python：`python -m unittest discover tests` = 211 项 OK；
  - JS：`node contracts/tests/textpos.test.js`、`node tests/js/reader-session.test.js` OK；
  - Android JVM：`gradlew testDebugUnitTest` = 90 过 / 1 跳；DisciplineTest 在岗；
  - UI 实机 harness：`python -m tests.ui.runner` = 92 项 PASS（需桌面 WebView2）。
- CI：`windows.yml`、`android.yml`、`nightly.yml`；契约 CI 待按路线图新增。

## 2. 本机环境（Windows 开发机）

- Python：`F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`
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

- P0：Windows v1.2.0 启动崩溃（pythonnet/.NET）——用户侧排查“解除文件锁定 /
  安装 .NET Framework 4.8”，项目侧加友好提示与文档；
- P1：契约/API 漂移守卫（contracts CI + 双清单对照）；Android 桥协议版本 +
  进度事件回放；依赖锁定与构建可复现；首批 ADR；
- P2：jsoup 清洗、Android 错误模型/null 清理、Android 诊断闭环；
- P3：大文件渐进拆分、TaskManager 试点、存储恢复能力、开源治理与仓库卫生；
- P4：参考仓库克隆（网络恢复后）；第二书源 / SQLite / 同步 / 插件（需求触发）。
- 细节与延后理由见 [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)。

## 6. 纪律提醒（新会话必守）

- 进场先读 AGENTS.md；历史记录查 docs/DEVLOG_ARCHIVE.md，教训查
  docs/LESSONS_LEARNED.md；
- 改动必补记本文件“最近流水”（日期 + 提交 + 现象/结论）；
- 推送代码/发行版必须用户明确授权；双端共享文件改动先做 Diff 影响检查；
- 进度类改动必须跑“滚动/翻页 → 退出 → 重进”回归；改 JS 后校验 APK 内脚本；
- 发布前跑凭据扫描（Windows：检查 dist 无 config.ini/nga_config；Android：
  `android/scripts/check-release.ps1`）。
