# AnkeShelf 架构整合路线图（下阶段任务参考）

> 文档日期：2026-08-12（2026-08-18 状态核对）
> 分析基线：`main` / `1ea4c95`（分析时点）；2026-08-13 核对：HEAD 已推进至
> `4810d0c`（docs-only 提交）；2026-08-14 再核对：HEAD 已推进至 `c8f90cf`，
> P0 / P1 已按本路线图落地；随后推进至 `edaf442`（依赖锁定，另含 ADR/治理文档），
> 再推进至 `ad034b8`（Android 桥协议 + 进度回放）、`d697330`（P2 jsoup 清洗）、
> `9e84c4c`（P2 错误模型）与 `cb40cee`（P2 诊断闭环）；随后 `96eb2e7`
> （统一备份包）、`b63809f`（统一 task_id）、`867e7ea`（章节读取失败模型），
> 随后主干推进至 `670cecb`，包含 Android 数据/阅读链路显式失败修复、
> Android CI bundle 路径守卫与 API 契约启动失败诊断；2026-08-15 Windows 骨碌碌
> 适配经 PR #13 以 rebase 方式并入主干，合并基线为 `4b77ded`；2026-08-16
> 骨碌碌阅读交互九轮合入并发布 v1.4.0；2026-08-17 至 2026-08-18 五批接手风险
> 修复合入，DevLog §5 延后项清单与本文档 §2 测试基线/文件行数表已同步真实状态；
> 2026-08-18 文档漂移治理强化：AGENTS §5 高漂移清单 + `scripts/check-doc-drift.ps1`
> （HEAD 以 `git log` 为准）；
> 2026-08-18 P5：用户 issue 转化为开发批次（`d0e184e`），P5-A 快赢批合入（`91b6206`）。
> 各节“状态”注明进度。
> 当前版本：Windows v1.4.0，Android android-v1.0.0
> 来源文档（均在工作区外 H 盘）：
> - `I:\AnkeShelf_Review_Archive\AnkeShelf_Architecture_Improvement_Proposal.md`（架构改进提案）
> - `I:\AnkeShelf_Review_Archive\review1.md`（外部工程评审）
> - `I:\AnkeShelf_Review_Archive\review2.md`（开源参考项目调研）
> - `I:\AnkeShelf_Review_Archive\架构债清理清单.md`（架构债清理清单）
>
> 本文档是四份文档与仓库现状的整合产物，只做方向指引，不含代码改动。
> 后续任务立项时以此为准，逐项补充“成功标准 + 验证方式”后开工。

---

## 1. 总体结论

AnkeShelf 已经跨过“功能原型”阶段，进入“稳定产品 + 持续演进”阶段。
四份文档共识明确：

1. **不重写架构**：双端代码隔离、JSON 契约同构、`text_offset` 唯一坐标、
   Compose 外壳 + WebView 内核、原生书只追加，这些决策已被验证，全部保留。
2. **共享契约，不共享运行时代码**：把 Python/JS/Kotlin 的一致性交给
   `contracts/`、golden fixtures 和 CI 自动验证，而不是让两端互相引用。
3. **守卫先行，拆分后置**：先把已有经验固化为自动化守卫（API 漂移、
   Bridge 协议、构建产物），大文件只在真实变化点渐进拆分。
4. **抽象触发式引入**：ContentSource、SQLite、插件、同步、WorkManager
   都等到出现第二个真实实现或量化瓶颈后再做。
5. **让正确性由类型与调用关系保证**：清理静默 fallback、无意义 null、
   万能 Manager，不以更多运行时防御代码掩盖设计问题。

当前最优先事项不是结构美观，而是两件事：

- 正在影响用户的 Windows 发行包启动崩溃（pythonnet/.NET 加载失败）；
- 把已经证明有效的工程纪律自动化（契约 CI、协议版本、进度回放、
  可复现构建）。

---

## 2. 现状核验（2026-08-12 实测）

### 2.1 版本与测试基线

| 项 | 现状 |
| --- | --- |
| 主干状态 | `main` 持续推进；PR #13（合并基线 `4b77ded`）之后完成骨碌碌阅读交互 v1.4.0、五批接手风险修复与 DevLog 漂移收敛，并进入 P5 批次（`d0e184e` 用户 issue 转化、`91b6206` P5-A 快赢批）（2026-08-18 核对，HEAD 以 `git log` 为准） |
| 当前开发分支 | `main`；Windows 骨碌碌 EPUB、图片三态与追加式增量热更新已完成主干合并 |
| Windows Python 单测 | 300 项（3.14：4 跳；bundled 3.12：全量通过） |
| JS 契约测试 | `textpos` 15 cases + `api-contract` 52 methods + `bridge-contract`（桥版本 1）+ `reader-lite-parts`（6 parts / 37311 字节）+ 启动失败诊断 + `reader-session` OK |
| Android JVM 单测 | 117 过 / 1 跳（2026-08-15 实跑复核） |
| Android 真机测试 | ELE-AL00 instrumentation 11 / 11；滚动/分页/交叉模式/图片章节重进通过 |
| UI 实机 harness | 97 项 PASS（需桌面 WebView2） |
| CI | `windows.yml` / `android.yml` / `nightly.yml` / `contracts.yml` |

> 权威基线：版本线见 README 版本表；测试基线详见 `MAINTENANCE_GUIDE.md` §7；本节为路线图快照。

### 2.2 代码规模热点

| 文件 | 行数 | 关注点 |
| --- | --- | --- |
| `android/.../data/Html5Entities.kt` | 2130 | 机械生成表，正常 |
| `android/.../ui/reader/WebViewChapterView.kt` | 645 | 桥与宿主；本轮已审查模式分流增量 |
| `android/.../ui/shelf/BookshelfScreen.kt` | 611 | 书架页，未拆分 |
| `android/.../data/NativeBook.kt` | 635 | 原生书数据层 |
| `android/.../ui/settings/SettingsScreen.kt` | 560 | 已按 Panel 拆分 |
| `android/.../ui/search/SearchScreen.kt` | 564 | 搜索页，未拆分 |
| `android/.../data/Epub.kt` | 569 | EPUB 数据层 |
| `android/.../ui/reader/native/NativeReaderScreen.kt` | 451 | 外壳已拆 Chrome |
| `android/.../ui/download/DownloadScreen.kt` | 345 | 已拆分面板 |
| `android/.../assets/reader/reader-lite.js` | 1037 | 现役渲染内核（parts 模块化） |
| `web/js/reader.js` | 761 | 核心编排；本轮审查确认仍是单一阅读生命周期，后续只在出现第二个真实调用边界时拆分 |
| `web/js/nga_download.js` | 625 | 已拆 nga-download-panels；骨碌碌逻辑独立在 gululu-download.js |
| `app/gululu_service.py` | 498 | 导入/导出/更新任务状态、取消与事件编排；客户端、评论缓存和增量更新已拆分 |
| `app/gululu_update.py` | 406 | Windows 私有基线、append-only 合并、旧书迁移与可恢复 EPUB 替换 |
| `app/nga_service.py` | 576 | 下载/更新/清理语义集中 |
| `web/js/settings.js` | 177 | 已拆 settings-ui / settings-panels |
| `web/css/reader.css` | 2125 | 样式，暂不处理 |

### 2.3 已确认的架构债

| 债 | 证据 | 影响 |
| --- | --- | --- |
| 静默失败（核心已解决） | `readJsonStore` / `StoreLoadResult` / `RepoResult` / `ChapterReadResult` 已显式化；残余 null 清理按 P2 现状收敛 | 失败原因可区分；残余 null 属低风险收尾 |
| 防御式代码（部分解决） | `Settings.get(key): Any?` 已删除；`BookSession` 5 lambda 为统一只读接口设计保留 | 调用关系已显式化，剩余为设计取舍 |
| 桥协议无版本（已解决） | ready 握手 `{bridgeVersion:1, capabilities}` + `BridgeProtocol.isCompatible`；ProgressModel 纯决策可回放 | 协议错配在运行期显式失败并记诊断 |
| API 人工同步（已解决） | 现状：后端 `_HANDLERS` 与前端 `METHODS`/`bridge.js` MOCKS 共 52 项；`api_manifest()` + `contracts/tests/api-contract.test.js` + `tests/test_api_contract.py` 自动对照，MOCKS 已补齐 | 遗留：业务错误内层 `{ok:false}` 不 reject（前端调用方自查，设计保持） |
| 正则 HTML 清洗（已解决） | `sanitizeReaderBody()` 改为 jsoup DOM 白名单清洗（P2 已完成） | 不可信 HTML 清洗可证明 |
| 依赖不可复现（已解决） | `requirements.in` + 带哈希 lock，CI/PyInstaller 按 lock 安装（P1 已完成） | 同一提交可重复构建 |
| 重复大文件（已解决） | 字体去重为仓库根 `assets/fonts/` canonical 源，双端构建共用（P3 已完成） | Git 体积不再膨胀 |
| 治理文件缺失（已解决） | CONTRIBUTING / SECURITY / Issue·PR 模板 / CODEOWNERS / Dependabot / THIRD_PARTY_NOTICES 已补齐（P3 已完成） | 陌生人可安全参与 |
| 文档膨胀（已治理） | DevLog 历史归档至 `docs/DEVLOG_ARCHIVE.md`，教训收敛至 `docs/LESSONS_LEARNED.md`（P3/10.15 已完成） | 现役日志只留当前状态与最近流水 |
| 编码损坏（已解决） | `web/js/reader.js` 乱码注释已修复（P3 已完成） | 可读性恢复 |

---

## 3. 优先级路线图

### P0：修复当前用户报错（发行包启动崩溃）

> 状态（2026-08-14）：项目侧“友好提示 + 文档”已落地（`app/startup_errors.py`、
> `app/main.py` 捕获 `RuntimeError`、README / 使用说明补充 .NET 4.8 与“解除锁定”）；
> 用户已自行修复本机环境，本轮未重新打包 / 替换 v1.2.0 发行资产。

- 现象：`Failed to resolve Python.Runtime.Loader.Initialize from
  ...\pythonnet\runtime\Python.Runtime.dll`，pywebview winforms 两次加载
  （netfx + coreclr）均失败。
- 本地已排除：发行包内 DLL 存在且与 site-packages SHA-256 一致
  （`648E6B41…4907`），`pythonnet/runtime` 99 个文件全量打包，本机
  .NET Framework 4.8.1 下 `import webview.platforms.winforms` 成功。
- 最可能原因（按顺序排查）：
  1. 下载的 zip 带 Mark-of-the-Web，DLL 被系统阻止加载（PyInstaller#7412
     有同类案例：解除锁定后恢复）；
  2. 用户机器 .NET Framework 缺失或过旧（pythonnet 3.x 需 4.7.2+，
     pythonnet#2459 即 .NET 4.5.1 环境报同款错误）。
- 改动位点：
  - `app/main.py`：在 `webview.start` 前捕获 `RuntimeError`，弹友好提示
    （安装 .NET Framework 4.8 / 解除文件锁定）；
  - `README.md`、`使用说明.txt`：写明 .NET Framework 4.8 要求与
    “右键 zip → 属性 → 解除锁定”步骤；
  - `ankeshelf.spec` / `windows.yml`：打包后自检 pythonnet 运行时完整；
  - 视情况重打并替换 v1.2.0 发行资产。
- 预期目标：用户可启动；无法启动时得到明确、可执行的修复指引。

### P0（新）：章节读取失败模型（BookSession 契约收紧）

> 来源：第二轮架构债审查（2026-08-14，`I:\AnkeShelf_Review_Archive\ARCHITECTURE_DEBT_REVIEW_20260814.md`）。
> 状态（2026-08-14）：已完成（`867e7ea`）——`ChapterReadResult` 显式结果 +
> 阅读页错误分支；111 过 / 1 跳，`assembleDebug` 通过。

- 现状：`BookSession.chapterText(index): String?`（`service/AppContainer.kt`）；
  `Epub.chapterText` / `NativeBook.chapterText` 的 `readFile` 把越界、文件
  损坏、IO、权限全部折叠成 null，调用方无法区分失败原因，UI 只能当空白页处理。
- 成功标准：
  1. 章节读取返回显式结果（`Success(text)` / `NotFound` / `Corrupt` /
     `Io(detail)`），null 仅保留“资源可能不存在”语义（封面/资产等）；
  2. 阅读页对三类失败分别可处理：越界回退目录、损坏提示修复、IO 允许重试，
     不静默渲染空白页；
  3. 现有 109 项 JVM 测试保持通过，新增失败路径用例先红后绿。
- 验证方式：`gradlew testDebugUnitTest` 全绿；打开损坏 EPUB 与缺失章节文件，
  确认 UI 有明确错误而非空白。
- 不改动：共享 JSON 数据契约与桥协议；仓库层 `RepoResult` / 存储层
  `StoreLoadResult` 已同类，不重复改造。

### P1：契约与 API 漂移守卫

> 状态（2026-08-14）：已落地——`contracts.yml`、`app/api/__init__.py::api_manifest()`、
> `contracts/tests/api-contract.test.js`、`tests/test_api_contract.py`，并补齐
> `bridge.js` 缺失的 3 个 MOCK。

- 新增 `.github/workflows/contracts.yml`（独立于 `android.yml`，不扩大其触发范围）；
- `app/api/__init__.py` 或 `registry.py` 导出方法清单；
- 新增 Node 对照测试：加载 `web/js/api-client.js`，与 Python 清单逐项比对
  方法名与参数个数；
- 补齐 `web/js/bridge.js` MOCKS（当前缺 `export_diagnostics`、
  `get_chapter_plaintext`）；
- `tests/test_contracts.py` 扩展 schema/fixture 校验。
- 预期目标：任何一端新增/改名 API 未同步时，CI 直接失败。

### P1：Android 阅读桥协议版本 + 进度事件回放

> 状态（2026-08-14）：已落地——ready 握手携带 `{bridgeVersion: 1, capabilities}`，
> Kotlin `BridgeProtocol` 校验版本；`ChapterProgressTracker` 决策层下沉为纯函数
> `ProgressModel`（虚拟时钟），`contracts/fixtures/progress/` 7 份事件序列夹具由
> Kotlin 回放、JS 桥测试校验握手。后续审查已把 Scroll / PageTurn /
> PagedAnchor 的互斥字段收进事件类型，并在 close 释放调度线程；真机回归保留。

- `WebViewChapterView.kt` / `reader-lite.js`：ready 握手增加
  `{bridge_version: 1, capabilities: [...]}`，版本不兼容时明确失败并记诊断；
- 新增字段优先使用单个结构化 JSON payload，不再扩大多参数桥方法；
- `ChapterProgressTracker` 提取纯决策层（输入旧状态+事件 → 新状态+Persist）；
- 新建 `contracts/fixtures/progress/*.json` 事件序列夹具，覆盖
  9.43–9.59 全部典型故障：滚动 500ms 防抖、翻页即时、模式交叉切换、
  全图页比例、换章/退出/退后台连发、dispose 迟到事件、连续重进三次。
- 同一夹具由 Kotlin tracker 测试与 JS 桥测试共同消费，真机 harness 保留。
- 预期目标：历史进度故障由单测稳定复现，不依赖真实时间和 Handler。

### P1：依赖锁定与构建可复现

> 状态（2026-08-14）：Windows 依赖锁定已完成（requirements.in / requirements-build.in
> + 带哈希的 lock，CI 用 lock 安装，PyInstaller 移出运行依赖；锁以 3.12 为基线、
> 实测 3.14 可安装）；Android 侧 `check-release.ps1` 已增加 APK 内 reader-lite.js
> SHA-256 比对，`scripts/check-toolchain.ps1` 校验 JDK 17+ 与 SDK 并接入 android/README。
> 发布资产摘要已补（`scripts/release_manifest.py` + windows.yml sidecar）；
> Android release 摘要已接入 VERSIONING 发布清单。

- `requirements.txt` 拆为 `requirements.in`（人类维护）+ lock 文件
  （或 `pyproject.toml` + uv/pip-tools），CI 与 PyInstaller 使用 lock；
- `pyinstaller` 等构建依赖移出运行依赖清单；
- `scripts/check-release.ps1` 增加 APK 内 `reader-lite.js` SHA-256 与源码一致
  校验（消除 Gradle UP-TO-DATE 误判）；
- 本地构建脚本显式检查 Java 17 工具链；`--warning-mode all` 定位 Gradle
  弃用项；
- 发布资产附 SHA-256 / commit / 数据契约版本 / 构建环境摘要。
- 预期目标：同一提交可重复构建，产物可信。

### P1：首批 ADR 与文档治理

> 状态（2026-08-14）：`docs/adr/` 首批 5 份 + 索引已补录，DevLog 头部已加 ADR 链接；
> `CHANGELOG.md` 拆分未做（留待真实发布节奏需求触发）。

- 新增 `docs/adr/`，首批补录：双端代码隔离与共享契约边界、Compose + WebView
  阅读架构、`text_offset` UTF-16 code unit、原生书只追加、JSON 为权威存储；
- 每份 ADR 只含：背景、决策、替代方案、后果、状态；
- DevLog 头部增加索引，或拆分 `CHANGELOG.md`（用户可见变化）与调试案例集。
- 预期目标：长期决策有据可查，DevLog 只承担时间线与案例职责。

### P2：Android HTML 清洗改 jsoup allowlist

> 状态（2026-08-14）：已完成——`sanitizeReaderBody()` 改为 jsoup DOM 白名单清洗，
> 保留 NGA 排版标签与内联样式，`ReaderHtmlTest` 新增畸形 HTML/实体编码 javascript 用例。

- `ui/reader/ReaderHtml.kt` 的 `sanitizeReaderBody()` 改为 DOM 级清洗 +
  allowlist（项目已有 jsoup 依赖）；
- 同步 `ReaderHtmlTest.kt` 与安全用例，保留 NGA 排版标签/内联样式能力。
- 预期目标：对不可信 HTML 的清洗可证明，不再依赖正则“尽力而为”。

### P2：错误模型与 null 清理（架构债清单 Phase 1）

> 状态（2026-08-14）：核心项已完成——`readJsonStore` / `StoreLoadResult`
> （Missing / Corrupt / IoError 显式区分，各 store 回退默认并记日志）；
> `BookRepository` 的 openSession / importEpub / registerNativeDir /
> registerEpubFile 改返回 `RepoResult`（NotFound / Corrupt / Io），
> UI 用 when 展示 Domain 错误；删除无生产调用方的 `Settings.get(key): Any?`，
> 统一走类型化 `getAll()`。后续审查已补损坏 JSON `.corrupt-*` 隔离、
> 搜索索引章节读取失败分支与进度写盘失败诊断；`registerNativeDir` 的元数据
> 读取失败已与格式损坏分流并通过真机权限回归；残余 null 收敛保持现状。

- `data/Storage.kt` 的 `readJsonOrNull` 拆为显式 `Result`/sealed 错误；
- `service/BookRepository.kt` 的 `openSession/importEpub/registerNativeDir`
  返回显式失败类型（损坏/IO/不存在）；
- `data/Settings.kt`、`data/NgaConfig.kt`、`data/Annotations.kt` 的
  null/异常路径收敛；确有必要的降级（如 `ATOMIC_MOVE` 回退）保留但记日志；
- `Settings.get(key): Any?` 评估替换为类型化访问或按域查询。
- 预期目标：调用方不再猜测失败原因，UI 只负责展示 Domain 定义好的错误。

### P2：可观测性与诊断闭环

> 状态（2026-08-14）：核心项已完成——`LogEvents` 结构化事件环形缓冲
> （component event key=value，book_id 短哈希）、`Diagnostics.report / collect`
> （应用/系统/WebView/桥版本、数据文件版本与大小、最近事件、任务状态，不含凭据与正文），
> 设置页新增「导出诊断信息」；bridge / search / nga 关键节点接入结构化事件。
> task_id 全链路统一已落地（`b63809f`）：下载 / 更新 / 导出 / 索引事件与
> 诊断包均带 task_id，取消 / 失败事件同样携带，可按 task_id 串联一次任务。

- Android 对齐 Windows 诊断包：应用/系统/WebView 版本、脱敏设置、最近有限条
  结构化日志、数据文件版本与大小、桥版本、最近任务状态；绝不包含 Cookie、
  书籍正文与签名信息；
- 下载/更新/导出/索引任务统一 `task_id` 贯穿 UI、服务、日志、诊断包；
- 共享事件命名与字段：`component/event/task_id/book_id_hash/chapter_index/
  mode/source/duration_ms/result/error_code`。
- 预期目标：真机问题可由用户直接提供可分析证据。

### P3：大型文件渐进拆分

> 状态（2026-08-14）：`reader-lite.js` 已完成模块化——`reader-lite.parts/` 6 个模块
> （core / geometry / textpos / paging / layout / api）+ 合并脚本 + 字节级一致性守卫，
> parts 不入 APK；`SettingsScreen.kt`（1369 → 575 行）与 `DownloadScreen.kt`
> （982 → 349 行）、`NativeReaderScreen.kt`（624 → 448 行，Chrome 组件拆出）已拆。
> Windows 前端拆分完成：`settings.js`（758 → 179 行 + settings-ui / settings-panels）
> 与 `nga_download.js`（870 → 622 行 + nga-download-panels），UI harness 92 项全绿。

- `SettingsScreen.kt`：路由装配 + 各 Panel 独立文件 + 状态模型独立；
- `DownloadScreen.kt`：表单/任务状态/已下载列表/进度独立；
- `NativeReaderScreen.kt`：按会话生命周期、控制条与抽屉、图片查看器、
  进度事件适配、章节加载拆分；
- `reader-lite.js`：按 geometry/textpos/report/restore 拆模块，构建期合并为
  现役单文件，APK 内 SHA 校验继续生效；
- Windows 前端 `nga_download.js` / `settings.js`：按真实痛点拆 section。
- 预期目标：拆分前后 UI 行为与 API 不变；后续改动局部化。

### P3：任务语义统一试点

> 状态（2026-08-14）：导出服务已接入 `TaskManager`（lane=export）——单飞占位、进度经
> `on_progress`、取消经 cancel 标志与 `TaskCancelled`、失败/完成映射到既有 status 字典；
> 新增 `/api/export_cancel` 与导出页「取消导出」按钮；`TaskManager.start` 对同任务
> 重入幂等。NGA 已于 2026-08-19 迁入 `TaskManager`（lane=network:nga），
> 双端任务基础设施统一。

- 选 `app/export_service.py` 或 `app/search.py` 接入 `TaskManager`；
- 统一任务状态字段：`id/lane/state/stage/current/total/message/started_at/
  updated_at/cancellable/error_code`；
- 验证开始/进度/取消/失败/完成/清理语义后，NGA 已迁移（2026-08-19）。
- 预期目标：任务取消无半成品，错误与状态跨功能一致。

### P3：存储恢复能力（保留 JSON）

> 状态（2026-08-14）：Windows 侧已落地——`load_json_file` 损坏即隔离为
> `.corrupt-<时间戳>`，原子写前保留 `.bak` 最近有效副本，新增
> `/api/verify_data_integrity` 与设置页「验证数据完整性」；统一备份包
> `ank-backup/1` 双端已落地（Windows `app/backup.py` + Android `data/Backup.kt`，
> 创建/只读验证/显式确认后恢复，同格式可互认）。

- 启动 schema/version 校验；损坏文件隔离为 `.corrupt-<timestamp>`；
- 保留最近一次有效副本；设置页提供“验证数据完整性”；
- 统一备份包（manifest + schema version + 校验和）；导入前只验证不覆盖。
- 预期目标：单文件损坏可恢复、可回滚；SQLite 仅在监测到量化瓶颈后评估。

### P3：开源治理与仓库卫生

> 状态（2026-08-14）：治理文档已落地（CONTRIBUTING / SECURITY / Issue·PR 模板 /
> CODEOWNERS / THIRD_PARTY_NOTICES），`web/js/reader.js` 乱码注释已修复，
> dependabot 与 `CHANGELOG.md` 已补；字体去重已完成
> （canonical 源 `assets/fonts/`，双端构建共用单一副本）。

- 新增 `CONTRIBUTING.md` / `SECURITY.md` / Issue/PR 模板 / `CODEOWNERS` /
  `dependabot.yml`（或 Renovate）；
- 新增 `THIRD_PARTY_NOTICES.md`：明确 `ngapost2md-python` 的 origin、
  commit/tag、许可证、重写与数据提取范围；
- 字体去重：`web/fonts/weidqczfkyxk.ttf` 与安卓同名字体改为单一 canonical
  源，构建阶段复制到两端资源目录（或评估 Git LFS）；
- 修复 `web/js/reader.js` 8 处乱码注释。
- 预期目标：陌生人可贡献、供应链可审计、仓库体积不再膨胀。

### P4：参考项目研究（review2 落地）

> 状态（2026-08-14）：5/8 仓库已克隆并研究（koreader / koreader-sync-server /
> thorium-reader / foliate-js / calibre），产出 `docs/REFERENCE_MATRIX.md`；
> readest（早期已研究）/ Kavita / LibreraReader 待克隆补行。

- 网络恢复后浅克隆 8 个参考仓库到 `H:\AnkeShelfReferences`：
  koreader、koreader-sync-server、readest、calibre、thorium-reader、
  foliate-js、Kavita、LibreraReader（当前命令环境断网，待用户提供通道）；
- 产出参考矩阵文档，重点吸收：阅读领域模型（KOReader）、跨端架构
  （Readest）、书库/Book Identity（calibre）、EPUB 标准（Thorium/Readium）、
  Web 渲染边界（foliate-js）、本地服务边界（Kavita）、Android 阅读体验
  （Librera）。
- 长期关注：`text_offset` 不应过早认定为唯一 canonical locator；未来可能
  演进为多锚点 Locator（href + progression + text context + offset +
  optional CFI）；API 设计不假设 caller 永远是同进程 WebView。
- 预期目标：长期设计决策有外部案例支撑，不照搬技术栈。

### P5：用户反馈批次（2026-08-18 issue，六项）

> 来源：用户 issue（书架 3 条 / NGA 4 条 / 骨碌碌 1 条）。全部条目已于
> 2026-08-18 对照代码核实现状，按"快赢 → 致命修复 → 体验 → 大件"排序。
> 产品原则（issue 第 3 条，非代码）：用户可见功能 Android 先行或双端同步交付。

#### P5-A：快赢批（骨碌碌链接提取 + 书名显示 + Windows 重命名）✅ 已完成（2026-08-18）

> 三个子项均已实施并验证：Windows 68 项 + Android JVM + JS 契约 53 方法全绿。

1. **骨碌碌粘贴文本自动提取链接**：现状 `gululu_source.py` 只 fullmatch 纯
   URL/ID，粘贴"点击链接阅读：https://…"报错。改动：前后端都支持从任意
   文本提取首个骨碌碌链接/ID（前端 `parseBookId`、后端 fullmatch→search 并
   校验唯一命中）。成功标准：带前缀文本直接可导入。
   验证：`tests/test_gululu_service.py` 新用例（红→绿）。
   文件：`app/gululu_source.py`、`web/js/gululu-download.js`。仅 Windows 端。
2. **书名前缀隐藏（显示层）**：安科标题普遍带【安科】等前缀，网格/最近阅读
   ellipsis 截断把真名挤掉。改动：新设置 `hide_title_brackets`（默认关，走
   契约流程更新 DATA_CONTRACT §4），书架网格/最近阅读显示层过滤首个
   `【…】` 段；不改存储书名（重命名/导出不受影响）。双端。
   文件：`app/settings.py`、`web/js/bookshelf.js`/`app.js`、Android
   `Settings.kt` + `BookshelfScreen.kt`、`docs/DATA_CONTRACT.md`。
   验证：过滤函数单测（纯逻辑）+ UI harness。
3. **Windows 书架重命名**（对齐 Android 已有 `renameBook`）：新 API
   `book_rename` + 书架右键/长按菜单；原生书同步 meta.json title（语义对照
   Android `NativeBookWriter.renameTitle`）。仅 Windows 端。
   文件：`app/api/library.py`、`app/shelf.py`、`app/native_book.py`、
   `web/js/bookshelf.js`。验证：API 单测 + 重命名后重开书标题一致。

#### P5-B：NGA 图片裂图修复（用户标"致命"）

- 现状（已核实根因）：Windows 在线图片模式 iframe 直连图床，NGA 图床防盗链
  需 Referer/Cookie → 403 裂图；book 内缺失图仅返回 1×1 透明 GIF（几乎
  不可见）。Android 已有图片代理（`shouldInterceptRequest` + NGA 头 + OkHttp
  缓存）但失败无可见占位。
- 改动：
  1. Windows `server.py` 新增 `/img/<book_id>?u=<url>` 代理路由：book_id
     须已注册、URL 必须命中 NGA 图床域名白名单（显式集合，禁任意 URL），
     转发带 `Referer: https://bbs.nga.cn/` 与已存 Cookie；`_serve_book`
     输出章节时把白名单图源重写为代理 URL（重写属性不影响 TextPos 文本
     节点，坐标安全）；
  2. 双端加载失败占位：`onerror` 替换为明确"图片加载失败"占位卡（Windows
     reader-image / Android reader-lite.js 既有 error 监听扩展）。
- 成功标准：在线模式 NGA 帖图 Windows 端正常加载；失败显示占位而非裂图。
- 验证：`test_server.py` 代理用例（头注入/白名单拒绝/未注册 book 404/
  路径穿越拒绝）；Android 改 JS 后校验 APK 内 SHA-256；真机实图抽查。
- Diff 影响：`server.py` 路由表变更同步 `docs/ARCHITECTURE.md`；Android
  reader-lite parts 重打包。

#### P5-C：滚动到底自动翻章（连续阅读）

- 现状：双端滚动/分页到章尾即停。改动：新设置 `auto_chapter_turn`（默认
  关），滚动模式距底 ≤48px 且停留 ≥800ms 触发 `nextChapter`（沿用现有
  loadChapter 语义：先存旧章精确 offset）。**不新增进度写入口**，仍走
  `ChapterProgressTracker` / 桌面 `saveProgress`。
- 铁律：必跑"滚动→退出→重进"回归 + 连续重进 3 次；进度 fixtures 不变。
- 文件：`web/js/reader.js`、`assets/reader/reader-lite.parts/`（30-paging/
  40-layout）重打包、双端 Settings 契约扩展。
- 验证：UI harness 新用例（到底触发换章、退出重进位置一致）；Android 真机。

#### P5-D：封面系统（骨碌碌封面 + 自定义封面）

- 现状（已核实）：`gululu_epub.py` 不生成封面；双端 `cover_rel` 机制已有
  （extract_cover→covers/），Android 书架已显示封面文件，但无自定义入口。
- 改动：
  1. 骨碌碌导入生成封面：优先 fetch_index 响应封面字段（如有），否则取首章
     首个 HTTPS 位图下载为封面，写入 EPUB3 cover 资源 → 现有 extract_cover
     自动生效；失败显式记日志不阻断导入；
  2. 自定义封面（双端）：书架管理菜单"设置封面"→ 选图（大小/格式校验，
     失败显式提示）→ 复制到 `covers/<id>.<ext>` 更新 `cover_rel`（无契约
     变更）+ "恢复默认"。
- 文件：`app/gululu_epub.py`/`gululu_service.py`/`api/library.py`、
  `web/js/bookshelf.js`、Android `BookRepository`/`BookManagement`/
  `Shelf.kt`。
- 验证：`test_gululu_epub` 封面用例、Android `ShelfTest` 封面更新用例。

#### P5-E：NGA 凭据傻瓜化（分级）

- E1（低成本，随 P5-A 并行）：粘贴完整 Cookie 字符串自动解析——用户整段
  复制 F12 Cookie（或含 uid/cid 的任意文本），粘贴后自动提取
  `ngaPassportUid`/`ngaPassportCid` 填入两栏。双端。
  文件：`web/js/nga_download.js`、Android 登录配置面板。验证：解析函数单测。
- E2（中成本，Android 先行）：应用内 WebView 打开 NGA 登录页，登录后从
  CookieManager 提取 uid/cid 一键保存。安全边界：仅登录用途、URI 固定
  bbs.nga.cn、拿到凭据即关窗，不加载任意页面；Windows 可用 pywebview
  二级窗。验证：真机手工 + 不落任何凭据到日志。
- 成功标准：小白不接触 F12 完成配置。

#### P5-F：NGA 楼中楼评论（最大件，最后做）

- 现状（已核实）：数据管道全通——`Floor.comments` 递归结构、
  `analyze_floors` 已解析页响应自带 comments、floors.json/native_book
  序列化保留；但 Windows `format_html` 有 `comment_bg` 主题色却未渲染、
  Android `NgaFormatHtml` 未处理；服务层无"收集评论"参数。
- 改动：
  1. 下载参数新增 `collect_comments`（默认关，记录入 meta.json，走
     NATIVE_BOOK_FORMAT 字段扩展流程）；开启时逐楼拉取楼中楼写入 comments
     （限速 + 进度反馈，大帖请求量在任务状态中显式呈现）；
  2. 渲染：楼层卡片底部内嵌 `<details>` 折叠楼中楼（默认收起）；
  3. **text_offset 红线**：评论只随楼层首次写入章节时一并写入；热更新只对
     新楼层收集；已下载楼层永不回填评论（破坏章节前缀稳定）。
- 风险：评论文本进入正文 DOM 会计入 text_offset——只要同章内容永不改写即
  稳定（不变量 1 保持）；"只看楼主 + 收评论"语义在 UI 文案说明。
- 验证：契约 fixture 扩展（带 comments 的 floors）；双端渲染单测；
  进度回归（含评论章节滚动/翻页→退出→重进）。

---

## 4. 推荐执行顺序

1. **第一批（低风险高收益，可直接开工）**：P0 发行包崩溃 → P1 契约/API
   守卫 → P1 依赖锁定 → P1 首批 ADR。
2. **第二批（正确性工程化）**：P1 阅读桥协议版本 + 进度事件回放
   （风险中、收益最高，独立专项，严格红→绿→回归）。
3. **第三批（安全与错误模型）**：P2 jsoup 清洗 → P2 错误模型/null 清理 →
   P2 诊断闭环。
4. **第四批（模型收紧，审查 P0）**：BookSession 章节读取失败模型
   （严格红→绿→回归）。
5. **之后（随变化点）**：P3 拆分与 TaskManager 试点、存储恢复、开源治理；
   P4 等网络与真实需求触发。
6. **当前批次（P5 用户反馈，2026-08-18 起）**：A 快赢 → B 裂图修复（用户
   标致命）→ C 自动翻章 → D 封面 → E1 Cookie 粘贴（可与 A 并行）→
   F 楼中楼；E2 WebView 登录随 Android 版本排期。进度类改动（C/F）必跑
   "滚动/翻页 → 退出 → 重进"回归。

---

## 5. 明确不做 / 延后

- 不迁移 Flutter / React Native / Kotlin Multiplatform；
- 不让 Android 复用 Windows `web/`；
- 不重写现役阅读内核；
- 不把 Kotlin `PagedLayout` 发展成第二套生产分页器；
- 不立即把 AppContainer 拆成 BookModule / ReaderModule / DownloadModule /
  SettingsModule（以“新依赖必须有模块归属”纪律替代，出现真实膨胀再拆）；
- 不为拆文件引入 Hilt/Koin、前端框架或复杂分层模板；
- 不立即把全部 JSON 迁移 SQLite；
- 不提前实现 ContentSource / 第二书源抽象、插件系统、跨端同步；
- 不删除现有诊断日志，除非已有等价诊断手段；
- 不把性能基准的单次波动作为 PR 硬门禁。

---

## 6. 每项任务通用验收清单

- 是否只影响任务声明的端和模块；
- 是否改变共享 JSON、文本坐标或 Bridge 协议（是则先补默认值/迁移/另一端
  读兼容，并更新 `DATA_CONTRACT.md`）；
- 是否存在新的进度写入口（必须仍只走 `ChapterProgressTracker` / 桌面
  `saveProgress`）；
- 是否引入第二套生产事实来源；
- 是否先有失败测试或可回放复现，再修复（红→绿→保留回归）；
- 是否更新 DevLog（日期 + 提交 + 现象/结论）；
- 是否通过相应端单测、DisciplineTest、契约测试；改动渲染后校验 APK 内
  `reader-lite.js` SHA-256；
- 是否需要 UI harness / 模拟器 / 真机；进度类必跑“滚动/翻页 → 退出 →
  重进”回归；
- 是否检查发布包实际资产、扫描凭据、说明回滚方式。
