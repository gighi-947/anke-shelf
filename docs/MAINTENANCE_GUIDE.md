# AnkeShelf 接手维护手册（Maintenance Guide）

> 文档日期：2026-08-16
> 用途：接手维护者进场参考。**开发规则以 [AGENTS.md](../AGENTS.md) 为最高优先级**，
> 本手册是其补充的代码库地图与操作速查；详细架构见
> [ARCHITECTURE.md](ARCHITECTURE.md) / [ANDROID_ARCHITECTURE.md](ANDROID_ARCHITECTURE.md)，
> 术语对照见 [GLOSSARY.md](GLOSSARY.md)，历史与教训见
> [AnkeShelf_DevLog.md](../AnkeShelf_DevLog.md) / [LESSONS_LEARNED.md](LESSONS_LEARNED.md)。

## 1. 项目定位与版本线

面向**安科读者**的跨平台阅读器：把站点作品（NGA / 骨碌碌）下载到本地 → 转换为
EPUB / 原生书 → 还原安科排版 → 舒适阅读与追更。GNU AGPL-3.0，作者 gighi-947。

| 端 | 当前版本 | 技术栈 | 版本源位置 |
| --- | --- | --- | --- |
| Windows | **v1.5.1** | Python + Web SPA + pywebview 壳 | 多文件散落（`app/__init__.py`、bridge MOCK、版本测试、README 等，升级按清单全量替换） |
| Android | **android-v1.1.0** | Kotlin + Compose + WebView 渲染内核 | **唯一定义**：`android/app/build.gradle.kts` |

- 版本线分离：Windows `vX.Y.Z` + `AnkeShelf-vX.Y.Z.zip`；Android `android-vX.Y.Z` +
  `AnkeShelf-vX.Y.Z-android.apk`；两端 SOP 独立，不混用。
- 当前 HEAD / 分支以 `git log` / `git status` 为准。

## 2. 仓库结构

```
app/                  Windows Python 后端（API 注册 / 存储 / 下载 / 解析 / 导出）
web/                  Windows 前端静态 SPA（阅读器 / 书架 / 设置 / 下载页）
tests/                Python 单测 + UI harness + JS 状态机测试 + 安全/性能回归
ngapost2md-python/    vendored NGA 下载内核（含 LICENSE/NOTICE 合规文件）
android/              独立 Gradle 工程（Kotlin + Compose，仅 :app 模块）
contracts/            双端共享 JSON schema + fixtures + 守卫测试（JS）
assets/fonts/         霞鹜文楷 canonical 源（双端构建共用单一副本）
docs/                 架构 / 契约 / 术语 / 路线图 / ADR / 审查计划
scripts/              release_manifest.py 等发布辅助
.github/workflows/    windows / android / contracts / nightly 四条流水线
```

**双端边界铁律**：Windows 端（`app/`、`web/`、`ngapost2md-python/`、`tests/`）与
Android 端（`android/`）代码绝不互相引用；共享文件仅 README、docs、contracts、
LICENSE、DevLog、.github、assets（字体 canonical 源）。

## 3. Windows 端架构

**三层结构**：`app/main.py` 装配（窗口生命周期、单实例、DPI、事件总线）→
`app/server.py` 本地 HTTP 分发（`/api/<name>` + `/book/<id>/<zip_path>` 读章节）→
`app/api/` 包按域注册 handler。

- **API 契约 = 方法名**：`_HANDLERS` 元组共 **52 个方法**（11 系统 + 4 书架 + 2 阅读 +
  3 搜索 + 7 标注 + 2 统计 + 12 NGA + 7 骨碌碌 + 4 设置）；`api_manifest()` 导出清单供
  前端 / CI 双向对照防漂移；新增 API 必须两处同步：handler + `api-client.js` METHODS。
- 前端链路：`Api.<method>()` → `Bridge.call` → `POST /api/<name>`（启动随机令牌
  X-Anke-Token，sessionStorage；无令牌时提示需要真实后端，不再内置 MOCKS）。
- **存储范式**（`app/storage.py`）：tmp 文件 → 替换前保留 `.bak` → `os.replace` 原子写；
  损坏隔离 `.corrupt-*` 并回退默认；`verify_json_file` 报告可解析性/版本/大小。
- 任务：`TaskManager` 按 lane 单飞（NGA / gululu / export 均已接入）。
- 阅读：章节经 iframe 加载 + 注入排版（不改书源）；`web/js/paged.js` CSS multi-column
  分页（单页 / 自动双页 / 强制双页、双页补偶数列）；`web/js/textpos.js` 与
  `app/text.py` 逐字符对齐。
- **维护坑**（代码注释实证）：
  - ZipFile 句柄必须先 `close()` 再 LRU 逐出（`book_manager.py`，上限 4 本），否则删书失败；
  - pywebview 自定义 storage_path 会间歇卡死，必须 `private_mode=True`；退出用
    `os._exit(0)` 防非 daemon 线程残留；全屏状态不记 window_size；
  - `text.py` 偏移坐标**包含折叠后的空白字符**，搜索 / 进度 / 标注统一此坐标系；
  - server.py：zip 路径拒绝反斜杠 / `..` / 绝对路径 / 超长；章节 CSP `script-src 'none'`；
    文本解码 UTF-8（GBK 兜底）；图片缺失返回透明 GIF 防布局崩坏；
  - 超大章（MAX_PAGED_TEXT=800000 字符）自动回滚动模式。

## 4. Android 端架构

**手动 DI**（`service/AppContainer.kt`：AppPaths / Shelf / ProgressStore / Settings /
NgaConfig / StatsStore / AnnotationStore / BookRepository / OkHttp）+ 四 Tab 路由
（书架 / 下载 / 搜索 / 设置），阅读器全屏沉浸。

- 阅读 = **Compose 外壳**（`ui/reader/native/NativeReaderScreen.kt`：目录 / 控制条 /
  图片灯箱 / 主题 / 沉浸式 / 统计心跳）+ **WebView 内核**
  （`ui/reader/WebViewChapterView.kt` + 现役 `assets/reader/reader-lite.js`，1038 行）。
- **桥协议**（`BridgeProtocol.VERSION=1`）：JS 上报 `onReady({bridgeVersion,capabilities})` /
  `saveProgress`（滚动 500ms 防抖）/ `saveProgressNow`（翻页 / 换章即时）/
  `pageChanged` / `requestChapter` / `openImage` / `onSettled` / `onMode`；Kotlin 下发
  `init / applyTheme / applyTypography / setMode / flipPage / setInsets / gotoOffset /
  openImageAt / onResize`；版本不兼容显式失败 + 诊断事件。
- **进度保持**（唯一写入口 `ChapterProgressTracker` + 纯决策层 `ProgressModel` 虚拟时钟
  可回放）：滚动 500ms 防抖、翻页即时、换章先取旧章 offset 立即落盘、返回 / 退后台 /
  dispose flush；**模式隔离**铁律——分页显式 `ratio=-1`、滚动显式 `page=-1`；滚动比例
  只在 `currentOffsetScroll()` 写、只在 `restoreScrollOffset()` 读。
- **设计令牌**：`AnkeSpacing`（2/4/8/12/16/24/32dp）、`AnkeRadius`（8/12/16/pill，
  胶囊仅小型操作）、颜色走 `MaterialTheme.colorScheme` / `ankeColors`；
  `DisciplineTest` 结构性守卫（令牌 / 模式隔离 / CI 路径 / 契约缺省 / 桥版本）。
- 构建：`cd android && gradlew.bat testDebugUnitTest assembleDebug`；
  `assembleRelease` 需本地 `keystore.properties`。

## 5. 数据契约（双端共享，见 [DATA_CONTRACT.md](DATA_CONTRACT.md)）

五份 JSON + 原生书 + 备份包，全部 **UTF-8 + 原子写 + 未知字段忽略**。

- **shelf.json（v1）**：`id/path/title` 必填；`author/language/file_mtime/added_at/
  last_read_at` 缺省 `""`；`chapter_count/file_size/nga_tid` 缺省 `0`；`cover_rel` 缺省
  `null`；`progress_pct` 运行时合成不落盘。
- **progress.json（v2）**：`chapter_index` + `text_offset`（章内折叠纯文本坐标，
  **UTF-16 code unit**，0=章首）为两端唯一坐标；安卓扩展 `page_index/page_total/
  scroll_ratio` 缺省 `-1/-1.0`，Windows 读入忽略。
- **settings.json（settings_version=3）**：主题 / 字号 / 行高 / 分页 / 色板等字段并集；
  Windows 独有字段（rsvp_rate、shortcuts、window_size 等）未入 schema。
- **annotations.json（v1）**：highlights（6 色枚举）/ bookmarks，偏移与 text_offset 同系。
- **statistics.json（v1）**：books + global 按天聚合。
- **原生书 ank-native/1**：meta（必填 format/book_id/tid/title/folder_name/per_chapter/
  image_mode/theme/toc_mode/chapters/last_lou）+ floors（pid/lou/timestamp/username/
  user_id/like_num/raw_content/comments 递归）+ chapters（`^chapters/[0-9]{4}\.xhtml$`）；
  **chapters 只追加不重写**（text_offset 稳定前提）、lou 单调、相对路径穿越检查。
- **备份包 ank-backup/1**：manifest + 五份 JSON + SHA-256；导入先只读验证，失败不写。

**新增字段四步流程**：① 默认值向后兼容（缺省=旧行为）→ ② 同步契约文档 / schema →
③ 对端读入忽略未知字段不得崩溃 → ④ 坐标类字段保持章内折叠纯文本 UTF-16 坐标系。

## 6. 开发纪律（AGENTS.md + LESSONS_LEARNED 精华）

1. **双端边界**：两端代码绝不互引；android CI 触发仅 `android/**`、`assets/**`。
2. 提交前缀 `win:` / `android:` / `docs:`；功能分支 `win/<feature>`、`android/<feature>`。
3. **失败显式化**：禁 null 折叠业务失败，统一 sealed 结果族（`RepoResult` /
   `StoreLoadResult` / `ChapterReadResult` / `VerifyResult` / `GululuBuildResult`）；
   `catch (e: Exception)` 只允许转为显式失败结果；`runCatching` 仅日志 / 缺省语义边界。
4. **调试五步循环**：复现 → 最小化 → 写"谁在何时写"清单 → 插桩验证 → 修复 + 回归。
5. **红→绿→保留回归**：先写复现失败测试再修，禁止"改完再补测试"或只靠肉眼验证。
6. 进度类改动必跑「滚动 / 翻页 → 退出 → 重进」3 次一致 + 滚动↔分页交叉切换 + 图片页。
7. 改 `reader-lite.js`：重跑 bundle（parts 字节级校验）并**解包确认 APK 内已更新**
   （Gradle 曾误判 UP-TO-DATE）。
8. 改文本规则：先改 `text-cases.json`（红）再改实现（绿），同步 SPEC + DATA_CONTRACT + DevLog。
9. 涉及 HEAD / 版本线 / 测试基线 / CI 清单 / 文件行数 / 待办状态的改动，收尾跑**文档漂移检查**
   （可先 `scripts/check-doc-drift.ps1` 生成快照，再按 AGENTS.md §5 高漂移清单核对）；改动必补记 DevLog。
10. 新增/修改动画遵守 `docs/ANIMATION_STANDARDS.md`（只动 transform/opacity、UI ≤300ms、
    `prefers-reduced-motion`、禁 `transition:all`、悬停配 `(hover:hover)`）；阅读器动画不得影响 text_offset。

## 7. 测试体系与基线

| 范围 | 基线（2026-08-20 实测） | 命令 / 位置 |
| --- | --- | --- |
| Windows Python | 319 项（3.14：全过；bundled 3.12 全过） | `python -m unittest discover tests` |
| JS 契约 | textpos 15 例、api-contract 59 方法、launch 诊断、bridge v1（能力含 annotation·assist）、parts 8/50881B、reader-lite-textpos 跨端折叠 12 例、reader-session、nga-cookie OK | `node contracts/tests/*.test.js` + `node tests/js/*.test.js` |
| Android JVM | 140 项（139 过 / 1 跳）+ DisciplineTest 8 项 | `cd android && gradlew.bat testDebugUnitTest` |
| 真机 | instrumentation 11/11（ELE-AL00） | adb instrument |
| UI harness | 97 项 PASS（需桌面 WebView2，CI 无头跳过） | `python -m tests.ui.runner` |
| 安全回归 | ZIP 炸弹上限 / 穿越拒绝 / CSP `script-src 'none'` | `tests/security/` |
| 性能基准 | 提取 ≈1.1ms/章、开读 3.2ms、搜索 7.0ms | `tests/performance/bench.py` + nightly |

> **测试基线文档权威**：本节是测试基线的唯一文档事实源；DevLog §1、ARCHITECTURE_ROADMAP §2.1 等处的计数仅为快照/指针，以本节为准。

守卫矩阵：api-contract（handler↔client↔MOCK）、textpos（逐字符对齐）、
bridge-contract（桥版本）、reader-lite-parts（字节级防拆分漂移）、
test_contracts.py（文本期望 + 原生书 fixture + JSON Schema）、
contracts/fixtures/progress/01~07（进度事件序列，Android ProgressModel 消费）。

## 8. CI 四条流水线（.github/workflows/）

- **windows.yml**：触达 app / web / ngapost2md-python / tests / contracts / assets；
  Python 3.12/3.13/3.14 矩阵（fail-fast: false）→ JS 守卫 → unittest → 仅 3.12
  PyInstaller 打包 + release manifest + SHA-256。
- **android.yml**：仅 `android/**`、`assets/**` 与自身触发；JDK17 → `node --check`
  reader-lite.js → bundle parts 校验 → testDebugUnitTest → assembleDebug。
- **contracts.yml**：contracts / app/api / api-client.js / bridge.js 变更触发；
  jsonschema → api-contract(+launch) → textpos → Python 契约测试。
- **nightly.yml**：cron `0 20 * * *`（北京 04:00）+ 手动；全量单测（含安全回归）+
  性能基准上传 baseline.json。

## 9. 构建与发布流程

- **Windows**：`pip install -r requirements-build.lock` → `build.bat`
  （PyInstaller **onedir**，spec 入口 `run_app.py`，datas 含 web / canonical 字体 /
  config.ini.example / ngapost2md LICENSE·NOTICE）→ 压 zip →
  `scripts/release_manifest.py` 生成 `.release.txt` + `.zip.sha256` → GitHub Release
  （**资产名纯 ASCII**，中文名会被 PowerShell→gh 破坏成 `-.zip`）；发布前扫 dist 无
  `config.ini` 真实凭据。
- **Android**：`assembleRelease` → `scripts/check-release.ps1`（凭据扫描 + APK 内
  reader-lite.js / 字体 SHA-256 与源码比对）→ `git tag android-vX.Y.Z` →
  `gh release create` → 摘要 sidecar。
- 依赖：`requirements.in` / `requirements-build.in` 人工维护，pip-tools 生成带哈希
  `.lock`（3.12 基线，3.14 实测可装）；CI 与打包均按 lock 安装。

## 10. 当前状态（2026-08-20 快照）

- 基线 `main`（HEAD 以 `git log` 为准）；`win/gululu-reader-interaction` 已并入主干；
  最近主线为骨碌碌适配（v1.3.0 / v1.5.1）、五批接手风险修复、P5 批次（P5-A/B/D/E1/E2 双端）、NGA 主题自适应与深浅色下载选项移除与
  多轮架构收敛（ApiError / TaskManager / reader-lite 状态机 / MOCKS 移除）；
  文档漂移治理已强化（AGENTS §5 高漂移清单 + `scripts/check-doc-drift.ps1`）。
- 版本线：Windows v1.5.1、Android android-v1.1.0，均已发布。
- 待办与延后项见 DevLog §5 与 [ARCHITECTURE_ROADMAP.md](ARCHITECTURE_ROADMAP.md)
  （真实待办：P5-E2 已完成双端；P5-C 自动翻章与 P5-F 楼中楼暂不实施，
  以及 Android 数据完整性校验入口；其余大文件拆分 /
  P4 参考仓库 3/8 待补：readest / Kavita / LibreraReader，均保持延后）。

## 11. 已知问题与风险

1. 版本号散落多处（Windows），升级需按清单全量替换，防漏改。
2. 项目无第二 owner：CODEOWNERS / branch protection 暂缓，待有第二人后开启。
3. 阅读进度坐标一致性是历史踩坑最多的领域（9.43–9.59 十轮），改动必走回归。
4. 本机 DSH 侧边卡片 git 插件补丁（git 绝对路径探测）在 DSH 更新 / 重装后丢失，需重打
   （非仓库内容，仅本机环境备注）。

## 12. 维护任务速查

| 任务 | 必须同步的位置 |
| --- | --- |
| 加设置项 | `app/settings.py` DEFAULTS + `settings_version` 迁移 + 前端设置页 + 文档 |
| 加 API | handler + `api-client.js` METHODS + `bridge.js` MOCK（三处同步，守卫自动对照） |
| 改契约字段 | 四步流程（§5），跨端 Diff 影响检查（Windows / Android / CI / 文档） |
| 改阅读器 | JS + Kotlin 双实现一致（ReaderPagedCrossTest 保护）+ 重 bundle + APK 校验 + 进度回归 |
| 改文本规则 | 先改 `text-cases.json`（红）→ 实现（绿）→ 同步 SPEC / DATA_CONTRACT / DevLog |
| 发新版本 | §9 双端 SOP 独立；收尾文档漂移检查 + 补记 DevLog「当前状态」 |
| 文档漂移检查 | `scripts/check-doc-drift.ps1` 生成快照 + AGENTS.md §5 高漂移清单逐项核对 |
| 新增/移动文档 | 同步 `docs/README.md` 索引；历史文档移入 `docs/archive/`（只读不改写） |

---

*本手册为接手维护参考，若与 AGENTS.md / 现役代码冲突，以 AGENTS.md 与代码为准。*
