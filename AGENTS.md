# AnkeShelf 开发规则（Agent 进场先读）

> 本文件是所有开发会话的**进场入口**。改动前先读本节；详细背景见
> [AnkeShelf_DevLog.md](AnkeShelf_DevLog.md)（变更流水，最新编号在顶部/末尾）、
> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（Windows）、
> [docs/ANDROID_ARCHITECTURE.md](docs/ANDROID_ARCHITECTURE.md)（Android）。

## 1. 双端边界（最高优先级）

- Windows 端：`app/`（Python）、`web/`（前端）、`ngapost2md-python/`、`tests/`。
- Android 端：`android/`（独立 Gradle 工程，Kotlin + Compose）。
- 共享文件仅：`README.md`、`docs/`、`LICENSE`、`AnkeShelf_DevLog.md`、`.github/`。
- **两端代码绝不互相引用**；android 构建只发生在 `android/` 内。
- CI：`.github/workflows/android.yml` 仅 `android/**` 与自身变更时触发，不得扩大范围。
- 版本线分离：Windows `vX.Y.Z` + `AnkeShelf-vX.Y.Z.zip`；Android `android-vX.Y.Z` +
  `AnkeShelf-vX.Y.Z-android.apk`。唯一安卓版本定义在
  `android/app/build.gradle.kts`。

## 2. 提交纪律

- 提交前缀：`android:` / `win:` / `docs:`；功能分支 `android/<feature>`、`win/<feature>`。
- 单主干 `main`：功能分支验证后合并 main，再打标签发布。
- 每次改动必须补记 [AnkeShelf_DevLog.md](AnkeShelf_DevLog.md)（9.x 节，含日期/现象/结论）。

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

## 5. 数据契约（docs/DATA_CONTRACT.md）

- 两端 JSON schema 同构：shelf / progress / settings / annotations / statistics /
  原生书 meta+floors+chapters；原子写（临时文件 + rename）。
- 任何一端扩展字段必须：默认值向后兼容 + 同步更新契约文档 + 两端读兼容。
- 进度条目：`text_offset` 为正坐标；`page_index/page_total/scroll_ratio` 为
  安卓扩展（缺省 -1），Windows 端忽略未知字段。

## 6. 测试纪律

- 单测必须守真实合同：跨端对照测试加载现役 `reader-lite.js`（不是退役副本）。
- 禁止假绿：不写只验证 mock/非空/happy path 的用例；不跳过关键路径。
- 结构性纪律测试在 `DisciplineTest.kt`（UI 令牌、阅读器模式隔离、CI 配置），
  改动相关代码后必须保持通过。
- 发布前跑 `android/scripts/check-release.ps1`（凭据扫描）。

## 7. 常用命令

```bat
cd android
gradlew.bat testDebugUnitTest assembleDebug
gradlew.bat assembleRelease                      :: 需本地 keystore.properties
powershell -ExecutionPolicy Bypass -File android/scripts/check-release.ps1 -ApkPath android/app/build/outputs/apk/release/app-release.apk
```

Windows 端：`python -m unittest discover tests`、`python -m tests.make_test_epub`。
