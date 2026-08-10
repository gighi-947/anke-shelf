# 代码库阅读地图（Codebase Map）

> 进场顺序：先读 [AGENTS.md](../AGENTS.md) 的纪律，再按本地图选一条链路开始。
> 双端共享同一套数据契约（见 [DATA_CONTRACT.md](DATA_CONTRACT.md)）与
> text_offset 坐标系（章内折叠纯文本字符偏移）。

## 0. 推荐阅读顺序

1. [AGENTS.md](../AGENTS.md)（规则入口）
2. [README.md](../README.md)（产品功能）
3. 本文档第 1 节（应用启动）→ 第 3 节（书架/进度）→ 第 5 节（阅读渲染）
   → 第 6 节（进度保持）→ 其余按需
4. 需要修 bug 时，按链路找到入口文件后，再看对应测试（见第 11 节）

## 1. 应用启动

| 端 | 入口 | 说明 |
| --- | --- | --- |
| Windows | `app/main.py` → `app/server.py` → `app/api/` | 本地 HTTP + API 路由（api 包：registry + 按域 handler）；`app/paths.py` 定位数据目录；`app/dpi.py`/`app/fonts.py` 平台适配 |
| Windows 前端 | `web/index.html` → `web/js/app.js` / `bookshelf.js` / `reader.js` | 单页应用；`bridge.js` 封装 Bridge 调用 |
| Android | `MainActivity.kt` → `AnkeShelfApp.kt` → `ui/AnkeShelfRoot.kt` | 手动 DI（`service/AppContainer.kt`）+ 底部四 Tab 路由 |

## 2. NGA 下载与热更新链路

```text
UI 入口 → 下载器 → HTTP 客户端 → 格式化 → 落盘/追加
```

| 端 | 文件 | 关键职责 |
| --- | --- | --- |
| Windows | `web/js/nga_download.js` → `app/nga_service.py` → `ngapost2md-python/ngapost2md/` | 面板、分阶段任务、下载内核（`client.py`/`nga.py`/`format.py`/`format_html.py`/`toc.py`） |
| Windows | `app/native_book.py` | 原生书容器：meta.json + floors.json + chapters/*.xhtml，纯增量追加 |
| Android | `ui/download/DownloadScreen.kt` → `service/NgaDownloader.kt` / `NgaDownloadService.kt` | 参数表单、前台服务、进度通知、取消清理 |
| Android | `service/NgaClient.kt` / `NgaHttp.kt` | OkHttp + CookieJar + UA + Referer（图片代理统一入口） |
| Android | `data/NgaFormatHtml.kt` / `NgaSmileMap.kt` | NGA HTML 清洗、表情/匿名映射 |
| Android | `data/NativeBook.kt` | 原生书读写（meta/floors/chapters，热更新增量） |

更新参数对话框：`ui/components/NgaUpdateDialog.kt`（书架与已下载页共用）。

## 3. 书架与进度存储

| 端 | 文件 | 说明 |
| --- | --- | --- |
| Windows | `app/shelf.py` | `Shelf`（shelf.json）+ `ProgressStore`（progress.json，原子写） |
| Android | `data/Shelf.kt` | `BookRecord` / `ShelfFile` / `ProgressEntry` / `ProgressStore`（同 schema） |
| Android | `service/AppContainer.kt` | `BookRepository`：注册、重命名、删除、保存进度（后台 IO） |
| 两端 UI | `web/js/bookshelf.js` / `ui/shelf/BookshelfScreen.kt` | 网格/列表、排序、长按管理、封面更新/导出 |

## 4. EPUB 解析与导出

| 端 | 解析 | 导出 |
| --- | --- | --- |
| Windows | `app/epub.py`（container → OPF → spine → nav/NCX） | `app/export_service.py` + `app/native_book.py`（重建 EPUB） |
| Android | `data/Epub.kt` | `data/EpubExporter.kt`（自写 ZIP/OPF）+ `service/NgaExport.kt`（EPUB/Markdown，SAF 保存） |
| Android | — | `data/NgaMarkdown.kt`（楼层 → Markdown） |

## 5. 阅读渲染（正文）

```text
Compose 外壳 → WebView 渲染内核 → CSS/JS 排版 → text_offset
```

| 端 | 文件 | 说明 |
| --- | --- | --- |
| Windows | `web/js/reader.js` / `paged.js` / `textpos.js` / `web/css/*` | iframe 章节渲染、分页/滚动、TextPos 坐标 |
| Android | `ui/reader/native/NativeReaderScreen.kt` | Compose 外壳：目录、控制条、图片查看、主题、沉浸式 |
| Android | `ui/reader/WebViewChapterView.kt` | WebView 内核宿主：桥、换章捕获、dispose 查询、在线图片代理 |
| Android | `assets/reader/reader-lite.js` | **现役**渲染桥：分页几何、TextPos、滚动比例、事件上报 |
| Android | `ui/reader/ReaderHtml.kt` / `PagedLayout.kt` | HTML 组装；Kotlin 侧分页几何（与 JS 对照） |

> ⚠️ 改渲染必须同时维护 JS 与 Kotlin 双实现的一致性（`ReaderPagedCrossTest` +
> `DisciplineTest` 保护），并校验 APK 内 JS 已更新。

## 6. 进度保持（重点链路，见 AGENTS.md 第 3 节）

| 端 | 文件 | 关键函数 |
| --- | --- | --- |
| Windows | `web/js/reader.js` → `app/shelf.py` | `currentOffset()` / `saveProgress()`；`ProgressStore.set` |
| Android | `ui/reader/ChapterProgressTracker.kt` | 进度唯一写入入口：lastKnown/saved 双 map、防抖/立即/flush |
| Android | `assets/reader/reader-lite.js` | `currentOffsetScroll/currentOffsetPaged/currentScrollState/restoreScrollOffset` |
| Android | `data/Shelf.kt` | `ProgressEntry`（text_offset + page_index/page_total/scroll_ratio） |

坐标系：`data/Text.kt`（`TextExtractor`）与 `web/js/textpos.js`、`reader-lite.js` 的
`TextPos` 逐字符对齐。

## 7. 全文搜索

| 端 | 文件 | 说明 |
| --- | --- | --- |
| Windows | `app/search.py` + `web/js/fullsearch.js` | 惰性内存索引、每章限量、text_offset 跳转 |
| Android | `data/SearchIndex.kt` + `ui/search/SearchScreen.kt` | 同样语义（每章 50 条 + 续取、大小写/全词） |

## 8. 标注

| 端 | 文件 | 说明 |
| --- | --- | --- |
| Windows | `app/annotations.py` + `web/js/annotations.js` / `highlight.js` | 高亮 6 色/笔记/书签/导出 |
| Android | `data/Annotations.kt`（存储）+ `NativeReaderScreen`（交互）+ `ui/components/BookManagement.kt`（导出入口） | 同语义 |

## 9. 统计

| 端 | 文件 | 说明 |
| --- | --- | --- |
| Windows | `app/stats.py` + `web/js/stats.js` | 时长/会话/翻页/每日 |
| Android | `data/Stats.kt` + `ui/stats/StatsScreen.kt` | 5 秒心跳 + 页面切换统计 |

## 10. 设置与主题

| 端 | 文件 | 说明 |
| --- | --- | --- |
| Windows | `app/settings.py` + `web/js/settings.js` / `theme.js` | settings_version 迁移、色板、阅读辅助 |
| Android | `data/Settings.kt` + `ui/settings/SettingsScreen.kt` / `GuideScreen.kt` | 六 Tab；`ui/theme/Theme.kt` + `Tokens.kt`（设计令牌，见 [ANDROID_DESIGN_TOKENS.md](ANDROID_DESIGN_TOKENS.md)） |

## 11. 测试与验证入口

| 范围 | 位置/命令 |
| --- | --- |
| Android JVM 单测 | `android/app/src/test/java/...`；`gradlew testDebugUnitTest` |
| Android 纪律测试 | `DisciplineTest.kt`（UI 令牌/模式隔离/CI 配置/契约） |
| Android 跨端对照 | `androidTest/.../ReaderPagedCrossTest.kt`（需设备） |
| Android 构建 | `gradlew assembleDebug` / `assembleRelease`（需本地 keystore） |
| Windows 单测 | `python -m unittest discover tests` |
| 凭据扫描 | `android/scripts/check-release.ps1` |
| 发布 | `android/VERSIONING.md`（安卓）、DevLog 5.3（Windows） |
