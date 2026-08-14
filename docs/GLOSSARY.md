# 术语表（Glossary）

> 共享语言：领域词与代码概念的一一映射。Agent 与开发者统一使用本表词汇，
> 不造同义词。未收录的新词请先查代码/DevLog，再决定是否补录。

## 1. 领域术语（阅读场景）

| 术语 | 含义 | 代码/文件对应 |
| --- | --- | --- |
| 安科 | NGA 等揭示板上的互动创作（读者投票/掷骰影响剧情） | 产品定位，无专门代码 |
| 安价 | 读者指定选项/决定剧情走向 | 同上 |
| 楼层 / 楼 | NGA 帖子里的单条回复；0 楼=主楼 | Windows `app/native_book.py`；Android `data/NativeBook.kt`（`NativeFloor.lou`） |
| 只看楼主 | 只下载/显示指定 uid 作者的楼层 | 下载参数 `authorId/uid`（Windows `nga_download.js`、Android `NgaUpdateDialog`） |
| 引用（楼中楼） | 回复里嵌套引用其他楼层 | `NativeFloor.comments` / Windows floors.json comments |
| 骰子 | 帖内掷骰排版块（NGA 特色） | NGA 排版还原，渲染层（reader-lite.js / paged.js） |
| tid | NGA 帖子 ID | 下载参数；`BookRecord.nga_tid` |
| pid | NGA 楼层/回复 ID | `NativeFloor.pid`、搜索/标注引用 |
| 热更新 / 增量更新 | 只拉新增楼层追加，不重下旧内容 | Windows `app/native_book.py`；Android `NgaDownloader`（append 模式） |
| 原生书 | NGA 帖子的运行时容器（meta+floors+chapters） | Windows `app/native_book.py`；Android `data/NativeBook.kt` |
| 每章楼层数 | 按多少楼切一章 | 下载参数 `per_chapter`；`NativeMeta.per_chapter` |
| 目录楼 | 帖内目录楼层 pid（可仅索引/兼分章） | Windows 下载参数；`NativeMeta.toc/toc_mode` |
| 图片模式 | 在线 / 内嵌 / 无图 | `image_mode`（NativeMeta、下载参数） |
| 主题（下载） | 浅色 / 深色 | `NativeMeta.theme`（仅影响下载转换） |
| 阅读主题 | 深色 / 浅色 / 羊皮纸 | 设置 `theme`；Android `ui/theme/Theme.kt` |
| Cookie | ngaPassportUid / ngaPassportCid | Windows `app/nga_config.py`；Android `data/NgaConfig.kt`（本机私有） |

## 2. 数据与进度概念

| 术语 | 含义 | 代码对应 |
| --- | --- | --- |
| text_offset | 章内折叠纯文本字符偏移（唯一坐标，0 基） | Windows `app/text.py` / `web/js/textpos.js`；Android `data/Text.kt` / reader-lite.js `TextPos` |
| Position | 阅读位置值对象（chapter_index + text_offset，UTF-16 code unit） | `app/domain.py`；Android 侧对应 ProgressEntry |
| BookRevision | 书籍内容版本标识（native:<tid>:<last_lou>:<updated_time> / epub:<size>:<mtime>） | `app/domain.py`；搜索索引按它自动失效 |
| EventBus / book_updated | 进程内领域事件（NGA 下载/热更新后通知缓存失效） | `app/events.py`；订阅在 `app/main.py` |
| ErrorCode / api_error | 结构化 API 错误码（message 不变，新增 ok/error_code） | `app/errors.py` |
| run_migrations | 统一数据迁移框架（load → migrate → validate → 原子写） | `app/migrations.py`；Settings v<3 迁移已接入 |
| build_diagnostics | 诊断包导出（版本/平台/日志/脱敏设置，不含凭据） | `app/diagnostics.py`；设置 → 数据「导出诊断信息」 |
| TaskManager / TaskStatus | 按 lane 单飞的任务基础设施（导出已接入，NGA 暂未迁移） | `app/tasks.py` |
| log_event | 统一日志字段（component event key=value） | `app/logutil.py`；nga/search 已接入 |
| tests/security / bench.py | EPUB 安全回归（CSP/穿越/ZIP 炸弹）与性能基准（baseline.json） | `tests/security/`、`tests/performance/`；nightly CI 执行 |
| TextPos | DOM↔纯文本逐字符映射 | 同上；跨端对照测试 `ReaderPagedCrossTest` |
| scroll_ratio | 滚动模式整屏图片时的滚动比例锚点（0..1；-1=文本锚点） | Android `ProgressEntry.scroll_ratio`；reader-lite.js `state.scrollRatio` |
| page_index / page_total | 分页模式页码/总页数（0 基；-1=无） | Android `ProgressEntry` 扩展字段 |
| ProgressEntry / ProgressStore | 进度条目 / 进度存储（原子写） | Windows `app/shelf.py`；Android `data/Shelf.kt` |
| BookRecord / ShelfFile | 书架记录 / 书架文件 | 两端 `shelf.json` 同构 |
| settings_version | 设置迁移版本（只增不删） | `app/settings.py`；Android `data/Settings.kt` |
| 原子写 | 临时文件 + rename，避免半写状态 | `app/storage.py`；Android `data/Storage.kt` |
| 损坏隔离 / 完整性校验 | 载入损坏即隔离 `.corrupt-*`；原子写前保留 `.bak`；`verify_data_integrity` 报告可解析性/版本/大小 | `app/storage.py` + `/api/verify_data_integrity` |
| 统一备份包（ank-backup/1） | zip：manifest + 五份 JSON + SHA-256；导入前只验证不覆盖 | Windows `app/backup.py` + `/api/backup_*`；Android `data/Backup.kt` |

## 3. Android 代码组件

| 术语 | 含义 |
| --- | --- |
| AppContainer | 手动 DI 容器（`service/AppContainer.kt`） |
| AnkeShelfRoot | 四 Tab 路由外壳（书架/下载/搜索/设置） |
| NativeReaderScreen | 阅读页 Compose 外壳（目录/控制条/图片/主题） |
| WebViewChapterView | WebView 渲染内核宿主（桥/换章捕获/dispose 查询） |
| reader-lite.js | 安卓现役渲染桥（分页几何/TextPos/滚动比例/事件上报） |
| PagedLayout | Kotlin 侧分页几何（与 reader-lite.js 对照，防漂移） |
| ChapterProgressTracker | 进度保存唯一入口（内存双 map + 防抖/立即/flush） |
| NgaDownloader / NgaDownloadService | 下载逻辑 / 前台服务 |
| NgaClient / NgaHttp | OkHttp 客户端 / 统一 NGA 请求头（Referer/Cookie/UA） |
| NgaFormatHtml / NgaSmileMap | NGA HTML 清洗 / 表情与匿名映射 |
| EpubExporter / NgaExport | EPUB 自写导出 / 导出编排（EPUB/Markdown + SAF） |
| SearchIndex / SearchScreen | 惰性内存索引 / 搜索页 |
| Annotations.kt | 高亮/笔记/书签存储 |
| Stats.kt / StatsScreen | 阅读统计存储 / 页面 |
| SettingsScreen / GuideScreen | 设置六 Tab / 内置使用说明 |
| NgaUpdateDialog | 更新参数公共弹窗（书架+已下载共用） |
| AnkeSpacing / AnkeRadius / ankeshelfColors | 设计令牌（间距/圆角/颜色） |
| DisciplineTest | 纪律测试（UI 令牌/模式隔离/CI 配置/契约） |
| StoreLoadResult / readJsonStore | JSON 载入显式结果（Missing / Corrupt / IoError），失败回退默认并记日志 |
| BookRepoError / RepoResult | 书籍仓库显式失败模型（NotFound / Corrupt / Io）与结果封装 |
| ChapterReadResult | 章节读取显式结果（Success / NotFound / Corrupt / Io），替代 `chapterText(): String?` 的 null 折叠 | `data/ChapterReadResult.kt`；Epub / NativeBook / BookSession 共用 |
| LogEvents / Diagnostics | 结构化诊断事件环形缓冲 / 脱敏诊断报告（设置页「导出诊断信息」） |
| task_id | 单次下载/更新/导出/索引任务的贯穿标识：事件与诊断包均可按它串联（`NgaServiceStatus.taskId`；导出/索引按任务生成） |

## 4. Windows 代码组件

| 术语 | 含义 |
| --- | --- |
| server.py / api/ | 本地 HTTP 服务与 API 路由（app/api/：registry + 按域 handler） |
| bridge.js | 前端 Bridge 调用封装 |
| api-client.js | 前端 API 客户端：UI 统一走 Api.<method>()，不再直接调 Bridge |
| reader-session.js / reader-utils.js | 阅读会话状态（章节/位置/脏标记）与阅读器常量、字体工具（B4 拆分） |
| domain.py / Book Protocol | 轻量领域模型：Position 值对象与书籍统一接口（EpubBook/NativeBook 均满足） | 
| reader.js / paged.js / textpos.js | 阅读内核 / 分页几何 / 坐标映射 |
| nga_service.py / ngapost2md-python | NGA 下载服务 / 下载转换内核 |
| native_book.py | 原生书容器（meta/floors/chapters） |
| export_service.py | EPUB 导出 |
| search.py / fullsearch.js | 全文搜索（后端索引 / 前端页） |
| annotations.py / stats.py / settings.py | 标注 / 统计 / 设置存储 |
| settings.js（settings-ui / settings-panels） | 独立设置页：共享工具与面板行构建器（拆分后） |
| nga_download.js（nga-download-panels） | NGA 下载/导出页：主逻辑与四个面板构建器（拆分后） |

## 5. 工程与流程

| 术语 | 含义 |
| --- | --- |
| AGENTS.md | 开发规则入口（进场先读） |
| CODEBASE_MAP.md | 双端链路阅读地图 |
| DATA_CONTRACT.md | 双端 JSON 数据契约 |
| AnkeShelf_DevLog.md | 现役开发日志（当前状态 + 最近流水）；历史归档见 docs/DEVLOG_ARCHIVE.md，教训见 docs/LESSONS_LEARNED.md |
| VERSIONING.md | 安卓版本/签名/发布 SOP |
| android-vX.Y.Z | 安卓独立版本线（Windows 用 vX.Y.Z） |
| check-release.ps1 | 发布前凭据扫描 |
| 纪律测试 | DisciplineTest（结构性边界检查） |
