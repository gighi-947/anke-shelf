# Android 端提交前代码审查与精简

日期：2026-08-08
范围：`android/app/src/main` 全部 Kotlin 与 `assets/reader` 前端资源；以“正式提交前全面梳理”为目标，只做低风险重构。

> 状态（2026-08-13 核对）：本报告为 2026-08-08 时点的审查记录。其后 9.57 精简中，
> 第 1/4 项涉及的旧 WebView 三件套（ReaderBridge / ReaderBottomBar / ReaderScreen）
> 与 `assets/reader/reader.js` 已作为死代码归档删除；现役内核为
> `WebViewChapterView.kt` + `reader-lite.js`。

## 审查结论

整体分层清晰（ui / data / service / reader），手动 DI（`AppContainer`）轻量，无全局可变单例滥用。主要问题是：单体大文件、重复的 NGA 请求头、少量死代码/死资产、硬编码路径与个别编译器警告。

## 本次已实施

1. **拆分阅读器大文件**（`ui/reader/`）：
   - `ReaderBridge.kt`：`PageInfo` + WebView JS 桥独立成文件，`ReaderScreen` 不再承载桥接细节；
   - `ReaderBottomBar.kt`：底部控制条（含主题循环常量）独立成 `BoxScope` 组合，同时保留“进度更新只重组子树”的优化；
   - `ReaderScreen` 由约 1220 行降至约 1020 行，专注组合、触摸与 WebView 编排。
2. **统一 NGA 请求头**（`service/NgaHttp.kt`）：新增 `Request.Builder.ngaHeaders(...)`（Referer/Cookie/UA），阅读器图片代理、保存/兜底下载与 `NgaClient` 接口请求共用，消除三处重复构建。
3. **删除 NgaClient 旧摘要接口**：移除生产代码不再使用的 `NgaPageSummary` / `NgaFloorHead` / `fetchPage` / `parseResponse`（约 60 行）；`NgaClientTest` 改为用完整解析 `parsePageFull` / `fetchPageFull` 覆盖同一 golden fixture。
4. **删除阅读器 JS 死代码**（`reader.js`）：
   - 从未被 Kotlin 调用的 `markPosition` / `restorePosition` / `savedPosOffset` / `positionMarked`（旧系统栏动画方案残留）及其导出；
   - 未使用导出 `openImage`、`isImageOpen`、`gotoOffset`、`refresh`、`measure`、`isPaged`、`isHuge`（内部函数保留）。
5. **删除死资产**：`assets/reader/reader.html`（M0 占位页，无引用且内容乱码）。
6. **路径常量**：`AppPaths` 增加 `logsDir`；崩溃日志、清空数据、阅读器字体目录不再硬编码 `"AnkeShelf"`。
7. **编译器警告清理**：`resp.body?.xxx()` 无意义安全调用、`body == null` 恒假分支、`Icons.Filled/Outlined.LibraryBooks` 弃用图标（改 AutoMirrored）、测试里可空 `ClassLoader`。

## 保留与后续方向（有意不做，避免风险）

- `SettingsScreen`（约 1300 行）与 `BookshelfScreen`：内部已按区块组织，拆分收益低于回归风险，留待设置页重构时一并做。
- `content-visibility`：见 [ANDROID_PERFORMANCE_REVIEW.md](ANDROID_PERFORMANCE_REVIEW.md)，会破坏滚动进度恢复，暂不启用。
- `BookRepository.chapterPlainLength` 仅测试使用：保留为公开工具，成本极低。
- `NgaDownloadState.image_mode` 仍兼容 `"embedded"` 旧值：已下载书可能残留，保留读取兼容。

## 验证

- `node --check reader.js` 通过；无残留 `markPosition` 等符号引用。
- `testDebugUnitTest` 全部通过（含重写的 NgaClient golden 用例与新增懒加载/进度测试）；`assembleDebug` 成功，无新增警告。
