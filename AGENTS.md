# AnkeShelf 开发规则（Agent 进场先读）

> 本文件是所有开发会话的**进场入口**。改动前先读本节；详细背景见
> [AnkeShelf_DevLog.md](AnkeShelf_DevLog.md)（现役日志：当前状态 + 最近流水；
> 历史归档见 [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)，
> 教训见 [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)）、
> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（Windows）、
> [docs/ANDROID_ARCHITECTURE.md](docs/ANDROID_ARCHITECTURE.md)（Android）、
> [docs/CODEBASE_MAP.md](docs/CODEBASE_MAP.md)（双端链路入口与阅读顺序）、
> [docs/GLOSSARY.md](docs/GLOSSARY.md)（术语表：领域词 ↔ 代码概念）。

## 1. 双端边界（最高优先级）

- Windows 端：`app/`（Python）、`web/`（前端）、`ngapost2md-python/`、`tests/`。
- Android 端：`android/`（独立 Gradle 工程，Kotlin + Compose）。
- 共享文件仅：`README.md`、`docs/`、`contracts/`、`LICENSE`、`AnkeShelf_DevLog.md`、
  `.github/`、`assets/`（内置字体 canonical 源，双端构建共用）。
- **两端代码绝不互相引用**；android 构建只发生在 `android/` 内。
- CI：`.github/workflows/android.yml` 仅 `android/**`、`assets/**`（字体 canonical 源）
  与自身变更时触发，不得扩大到另一端代码目录。
- 版本线分离：Windows `vX.Y.Z` + `AnkeShelf-vX.Y.Z.zip`；Android `android-vX.Y.Z` +
  `AnkeShelf-vX.Y.Z-android.apk`。唯一安卓版本定义在
  `android/app/build.gradle.kts`。

## 2. 提交纪律

- 提交前缀：`android:` / `win:` / `docs:`；功能分支 `android/<feature>`、`win/<feature>`。
- 单主干 `main`：功能分支验证后合并 main，再打标签发布。
- 每次改动必须补记 [AnkeShelf_DevLog.md](AnkeShelf_DevLog.md)（最近流水，
  含日期/现象/结论；详细历史归档见 [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)）。

## 3. 阅读器与进度保持铁律（踩坑十轮沉淀，9.43-9.59）

架构：Compose 外壳（`ui/reader/native/NativeReaderScreen.kt`）+
WebView 渲染内核（`ui/reader/WebViewChapterView.kt` + `assets/reader/reader-lite.js`）。
进度写入唯一入口：`ChapterProgressTracker`。

- **模式隔离**：分页（page_index/page_total）与滚动（scroll_ratio）字段互不共用；
  分页保存显式 `ratio=-1`；滚动比例只在 `currentOffsetScroll()` 写、只在滚动恢复读。
- **写入清单**：滚动 500ms debounce、翻页即时、换章/退出/退后台 flush；
  dispose 查询滚动才采用 o/r，分页只 flush 锚点（9.48）。
- **采样语义**：滚动锚点=屏幕中线文本（约 45% 视口）；整屏图片时按滚动比例近似。
- **恢复**：滚动比例 ∈ [0,1] 按比例滚动；否则 text_offset 文本锚点。
- **回归必测**：滚动/翻页 → 退出 → 重进位置一致；连续重进 3 次一致；
  滚动↔分页交叉切换；图片页覆盖。
- **改 JS 后必须校验** APK 内 `assets/reader/reader-lite.js` 已更新
  （Gradle 曾误判 UP-TO-DATE，解包校验内容）。
- 诊断日志（`[save:...]`、`progress.set` 等）正式发行前保留，便于定位。

## 4. UI 设计令牌（docs/ANDROID_DESIGN_TOKENS.md）

- 间距只用 `AnkeSpacing`（2/4/8/12/16/24/32dp）；禁止 padding/spacedBy 魔法值。
- 圆角按角色复用 `AnkeRadius`（small 8 / medium 12 / large 16）；胶囊只给小型操作。
- 颜色走 `MaterialTheme.colorScheme` / `MaterialTheme.ankeColors`；
  NGA 显式彩色字与一次性图表细节除外。
- 组件新增圆角/大间距一律引用令牌，不新增一次性值。

## 5. 工作方式（Karpathy 四原则 + Diff 影响检查）

- **先想后写**：不确定就显式说假设并提问，不要默默选一个解释往下跑；
  发现更简单方案或矛盾时直接指出（本项目的进度保持教训 9.53 就是反面教材）。
- **简单优先**：只做被要求的，不建一次性抽象/投机配置；能少写就少写，
  删死代码要确认引用（参考 9.57 架构精简）。
- **失败显式化**：禁止用 null 折叠业务失败（`chapterText(): String?` 同时
  表达越界/损坏/IO/权限就是反例）；失败走明确结果类型（仓库层 `RepoResult` /
  存储层 `StoreLoadResult` / 备份 `VerifyResult` 同款 sealed 结构）。禁止
  `catch(Exception)` 后静默吞错/降级；`catch (e: Exception)` 只允许用于转换
  为显式失败结果（如 `RepoResult.Err(BookRepoError.Io(...))`）；`runCatching`
  只允许用于日志、可选资源等“缺省语义”边界，且对外保留错误上下文。
- **规模与抽象门槛**：单文件超过 500 行必须审查（机械生成表豁免）；新增依赖
  必须说明模块归属，UI 减少直接访问 AppContainer；新抽象必须先回答“是否已有
  至少两个真实实现”，没有就先用普通函数 / 简单类 / 明确接口。
- **外科手术式改动**：只动任务涉及的代码；不顺手“改进”相邻代码/注释；
  发现无关死代码时报告，不删除。双端共享文件（README/docs/DevLog/契约）尤其如此。
- **目标驱动**：每个任务先写“成功标准 + 验证方式”，再动手；
  进度类改动必须跑“滚动/翻页 → 退出 → 重进”回归。
- **修复先写复现测试**：修 bug 先写能复现它的失败测试（红），再修复到通过（绿），
  并保留为回归测试；禁止“改完再补测试”或只靠肉眼验证。
- **调试五步循环**（对照 9.53/9.54 十轮教训）：
  ① 复现（能稳定复现才算开始）→ ② 最小化（缩小到最小触发条件）→
  ③ 假设（先写“谁在什么时机写、谁能覆盖谁”的写入清单再猜）→
  ④ 插桩/日志验证假设（诊断日志保留到发行前）→ ⑤ 修复 + 回归测试；
  每一步未通过不得进入下一步。
- **共享语言**：术语不确定时先查 [docs/GLOSSARY.md](docs/GLOSSARY.md)，
  不要自造同义词；领域词（楼层/引用/骰子/只看楼主）与代码概念
  （text_offset/scroll_ratio/原生书）一一对应。
- **动效纪律**：新增/修改动画遵守 [docs/ANIMATION_STANDARDS.md](docs/ANIMATION_STANDARDS.md)
  （只动 transform/opacity、UI 动效 ≤300ms、支持 prefers-reduced-motion、
  禁 transition:all、悬停配 (hover:hover)）；阅读器动画不得影响 text_offset。
- **Diff 影响检查**：改动涉及共享文件或数据契约字段时，先列出受影响端
  （Windows / Android / CI / 文档），逐项核对后再提交。
- **文档漂移检查**：任何改动收尾（尤其涉及 HEAD / 版本线 / 测试基线 / CI 清单 /
  文件行数 / 待办状态时）必须跑一次漂移扫描：用
  `git rev-parse HEAD`、最新测试计数、`.github/workflows/*.yml` 清单逐一对照
  下列非归档文档声明；可先用
  `powershell -ExecutionPolicy Bypass -File scripts/check-doc-drift.ps1`
  生成高漂移快照，再人工核对。`docs/DEVLOG_ARCHIVE.md`、`docs/archive/` 等归档只保留历史，不改写。
  **高漂移检查清单（非归档文档）**：
  - `AnkeShelf_DevLog.md`：§1 当前状态（日期 / HEAD / 版本线 / 测试计数 /
    发布状态）、§4 最近流水（本次改动必补记）；待办与延后项以
    `docs/ARCHITECTURE_ROADMAP.md` 为准（已完成项不得继续标“剩余”）；
  - `docs/ARCHITECTURE_ROADMAP.md`：顶部核对块（HEAD 推进链）、§2.1 版本与
    测试基线表、§2.2 代码规模热点行数表、§2.3 架构债表状态、§3 各 P 项状态；
  - `docs/MAINTENANCE_GUIDE.md`：§1 版本线、§7 测试体系与基线、§10 当前状态、
    §11 已知问题与风险；
  - `docs/ANIMATION_STANDARDS.md`：动效审查标准（现役；新增/修改动画必须遵守）；
  - `docs/README.md`：文档索引（现役/历史分类；新增文档必须同步本索引）；
  - `README.md`：版本表、系统要求、功能清单、测试命令；
  - `CHANGELOG.md`、`SECURITY.md`、`使用说明.txt`、`VERSIONING.md`、
    `contracts/README.md`、`docs/CODEBASE_MAP.md`、`docs/GLOSSARY.md`、
    `docs/DATA_CONTRACT.md`、`docs/ANDROID_ARCHITECTURE.md`、
    `nga-post-template.bbcode`。
  **治理固定点（停止递归）**：本清单与 `scripts/check-doc-drift.ps1` 是漂移检查的
  固定点；不为它们编写元文档，不递归治理。新增高漂移文档时改本清单与脚本即可，
  活状态字段照常同步。

## 6. 数据契约（docs/DATA_CONTRACT.md）

- 两端 JSON schema 同构：shelf / progress / settings / annotations / statistics /
  原生书 meta+floors+chapters；原子写（临时文件 + rename）。
- 任何一端扩展字段必须：默认值向后兼容 + 同步更新契约文档 + 两端读兼容。
- 进度条目：`text_offset` 为正坐标；`page_index/page_total/scroll_ratio` 为
  安卓扩展（缺省 -1），Windows 端忽略未知字段。

## 7. 测试纪律

- 单测必须守真实合同：跨端对照测试加载现役 `reader-lite.js`（不是退役副本）。
- 禁止假绿：不写只验证 mock/非空/happy path 的用例；不跳过关键路径。
- 业务不变量优先：少写“异常输入不会崩”型用例；重点覆盖阅读位置一致性
  （保存 offset → 重开 → 恢复一致）与数据一致性（修改 → 保存 → 重载状态一致）。
- 结构性纪律测试在 `DisciplineTest.kt`（UI 令牌、阅读器模式隔离、CI 配置），
  改动相关代码后必须保持通过。
- 发布前跑 `android/scripts/check-release.ps1`（凭据扫描）。

## 8. 常用命令

```bat
cd android
gradlew.bat testDebugUnitTest assembleDebug
gradlew.bat assembleRelease                      :: 需本地 keystore.properties
powershell -ExecutionPolicy Bypass -File android/scripts/check-release.ps1 -ApkPath android/app/build/outputs/apk/release/app-release.apk
```

Windows 端：`python -m unittest discover tests`、`python -m tests.make_test_epub`。

契约/API 守卫：`node contracts/tests/api-contract.test.js`、
`node contracts/tests/textpos.test.js`、
`python -m unittest tests.test_api_contract tests.test_contracts`。
