# Android 全量对齐 Windows 规划（ANDROID_PARITY_PLAN）

> 目标：把 Android 端功能逐项对齐 Windows 端。本文件是该长跑任务的**唯一进度基线**
> （批次状态随实施更新）；总待办仍以
> [ARCHITECTURE_ROADMAP.md](ARCHITECTURE_ROADMAP.md) 为仓库级基线，本文件是其下
> "Android 对齐"专项的展开。
>
> 对齐事实源（Windows）：`app/api/__init__.py` 的 `_HANDLERS`（59 方法）、
> `app/settings.py` 的 `DEFAULTS`、`web/js/` 模块清单、
> [GULULU_REFERENCE_MATRIX.md](GULULU_REFERENCE_MATRIX.md)。
> 核对日期：2026-08-20（Windows v1.5.1 / Android android-v1.1.0）。

## 1. 已对齐（不再重复投入）

| 领域 | Android 现状 | 证据 |
| --- | --- | --- |
| 书架 | 网格/列表、排序（recent/name/added）、SAF 导入、删除、重命名、自定义封面/恢复默认、书名括号隐藏 | `ui/shelf/BookshelfScreen.kt`、`service/AppContainer.kt` |
| 阅读核心 | 滚动/分页/双页/自动双页、字号/行高/边距/列间隙、主题 dark·light·sepia·system + 自定义四色、亮度遮罩、图片查看与保存、章节导航、沉浸系统栏 | `ui/reader/**`、`assets/reader/reader-lite.js` |
| 进度保持 | `text_offset` + `page_index/page_total/scroll_ratio`，`ChapterProgressTracker` 唯一写入口，`ProgressModel` 纯决策可回放 | `ui/reader/ProgressModel.kt` + `contracts/fixtures/progress/` |
| 全文搜索 | 惰性索引、每章 50 + 续取、大小写/全词、搜索历史、跳转 | `data/SearchIndex.kt` |
| 统计 | 5 秒心跳、时长/翻页/每日聚合 | `data/Stats.kt`、`ui/stats/StatsScreen.kt` |
| NGA | 下载（tid/authorid/maxFloors/imageMode/theme/perChapter/全量重下）、热更新追加、断点 `download.json`、前台服务通知、应用内登录 + Cookie 粘贴、EPUB/Markdown 导出（SAF）、书籍管理 | `service/NgaDownloader.kt`、`ui/download/**` |
| 数据与恢复 | 五存储同构 JSON + 原子写 + 损坏隔离、`ank-backup/1` 备份、诊断包、自定义字体导入 | `data/Storage.kt`、`data/Backup.kt`、`service/Diagnostics.kt` |

## 2. 差距矩阵（本次对齐范围）

| ID | 差距 | Windows 实现 | Android 现状（证据） | 批次 |
| --- | --- | --- | --- | --- |
| G1 | 阅读器内标注交互：选中文本 → 高亮 6 色/笔记、书签增删、章内高亮渲染、标注抽屉跳转 | `web/js/annotations.js` + `highlight.js` + `sidebar.js` | ✅ 批 1 已实现（`ui/reader/native/NativeReaderAnnotations.kt` + `reader-lite.parts/45-annotation.js`） | 1 |
| G2 | 嵌套目录（多级 + 当前项高亮） | `web/js/toc.js` | ✅ 批 1 已实现（`ui/reader/TocNode.kt` + `BookSession.tocNodes()`） | 1 |
| G3 | 进度滑块跳转（拖动跳全书比例） | `#progress-slider` + `Reader.jumpToFraction` | ✅ 批 1 已实现（`ReaderBottomBar` Slider，松手才跳转） | 1 |
| G4 | 阅读辅助三件套：自动滚动、RSVP 速读、阅读标尺 | `web/js/assist.js` | ✅ 批 2 已实现（`reader-lite.parts/48-assist.js` 自动滚动 + `ui/reader/RsvpTokenizer.kt` / `NativeReaderAssist.kt`）；标尺按触屏改为可拖动横线 | 2 |
| G5 | 代码块高亮 | `web/js/highlight.js` | ✅ 批 2 已实现（`48-assist.js` 同款 tokenizer + `reader.css` 同色 `.syntax`） | 2 |
| G6 | 按书字体覆盖 | `book_fonts` + reader 解析 | ✅ 批 2 已实现（`buildReaderHtml(bookId)` + 阅读辅助面板字体选择） | 2 |
| G7 | 进度百分比精度（章号 + 章内比例） | `app/api/common.py:progress_pct()` | ✅ 批 2 已实现（`BookRepository.progressPercent`：分页用 page_index/page_total，滚动用 scroll_ratio） | 2 |
| G8 | NGA 下载参数：`page_limit`、`toc_pid`、`toc_mode=split`（按目录楼分章） | `_build_cfg` + `write_container(toc_mode)` | ⏳ 批 3 待做（`NgaDownloadParams` 缺三项；`NativeBook.meta.toc_mode` 有字段但 writer 只走 index） | 3 |
| G9 | 数据完整性校验入口 | `verify_data_integrity` + 设置页 | ✅ 批 3 已实现（`data/Storage.kt:verifyJsonFile/verifyDataIntegrity` + 设置页「校验数据完整性」） | 3 |
| G10 | `settings.json` 缺 `gululu_immersive` 字段 → Android 回写会丢失 Windows 该设置 | `DEFAULTS["gululu_immersive"]`、契约 §4 已列 | ✅ 批 3 已修（`GululuImmersivePrefs` 字段 + 往返回归测试） | 3 |
| G11 | 书架按作者排序 | `shelf_sort=author` | ✅ 批 3 已实现（`author` 排序 + `title` 别名兼容桌面取值） | 3 |
| **G20** | **骨碌碌全链路（最大件，Android 0 命中）** | `app/gululu_*.py`（12 模块）+ `web/js/gululu-*.js`（8 模块） | 完全缺失 | 4–9 |
| G20.1 | 来源识别 + 公开 API 客户端（detail / floor·index-list / opus·chapter-index / floor·content-by-ids，`platform:1`，20 条一批，缺失楼层显式失败） | `gululu_source.py` / `gululu_client.py` | — | 4 |
| G20.2 | 富文本 AST → XHTML（marks 安全色、paragraph id、heading 降级、image HTTPS、hardBreak、collapsibleBlock） | `gululu_ast.py` | — | 4 |
| G20.3 | 图片三态 online/embedded/none（HTTPS 位图、并发、25MB 上限、签名识别、失败占位 + 计数） | `gululu_images.py` | — | 4 |
| G20.4 | 全能助手协议：折叠 / 引用（同书锚点 + 跨书 URL）/ 骰点稳定分组 / 迷雾锁 / 秘密密文 / 线索 / jumpFloor / sensitive | `gululu_assistant.py` | — | 5 |
| G20.5 | 沉浸指令：音乐·自动音乐·停止、氛围背景（跨章继承）、六类视效，仅无凭据 HTTPS | `gululu_immersive.py` | — | 5 |
| G20.6 | 评论：分页 + 子回复 + 公开字段、5 分钟缓存 + 离线回退、EPUB 评论块 | `gululu_comments.py` / `gululu_comment_service.py` | — | 5 |
| G20.7 | EPUB3 生成：章节分组（作者章节标记 / 20 楼兜底）、`floor-<id>` 锚点、楼层卡片、封面、CSS | `gululu_epub.py` | — | 6 |
| G20.8 | 导入任务：前台服务 + 进度/取消、`.part` 原子替换、注册书架 | `gululu_service.py` | — | 6 |
| G20.9 | 热更新：`snapshot.json` 基线、append-only 前缀不变量、旧书一次性迁移、失败回滚保 `book_id` | `gululu_update.py` | — | 7 |
| G20.10 | 宿主层交互 A：评论抽屉 + 段落评论徽标联动 + 只读弹幕 | `gululu-comments.js` | — | 8 |
| G20.11 | 宿主层交互 B：骰点揭示（单组/整楼/接下来 10 组）+ 音效、迷雾渐显、秘密弹窗 + 线索收集、解锁重置 | `gululu-assistant-reader.js` / `gululu-secrets.js` | — | 9 |
| G20.12 | 宿主层交互 C：音乐播放器、氛围背景、Canvas 视效、沉浸总览 | `gululu-immersive.js` / `gululu-overview.js` | — | 9 |

## 3. 批次计划

每批统一交付物：实现 + 单测（红→绿）+ `gradlew testDebugUnitTest assembleDebug` 通过 +
`DisciplineTest` 保持通过 + DevLog 流水补记 + 受影响文档同步。

| 批 | 主题 | 范围 | 关键成功标准 |
| --- | --- | --- | --- |
| 1 | 阅读器交互对齐 | G1 G2 G3 + `TextPos` 注入节点规则补齐 | 选中→高亮/笔记/书签可用并持久；重进章节高亮复现且 `text_offset` 不漂移；嵌套目录可展开跳转；滑块跳转后进度一致 |
| 2 | 阅读辅助与显示精度 | G4 G5 G6 G7 | 自动滚动/RSVP/标尺可开关并生效；代码块着色；按书字体生效；书架百分比含章内比例 |
| 3 | 下载参数与数据完整性 | G8 G9 G10 G11 | split 目录分章产物与 Windows 同构；设置页可校验五文件；`gululu_immersive` 往返不丢；作者排序可用 |
| 4 | 骨碌碌数据层 | G20.1 G20.2 G20.3 | 客户端契约单测通过；AST → XHTML 与 Windows 输出逐字符一致（新增跨端 golden）；三态图片行为一致 |
| 5 | 骨碌碌协议层 | G20.4 G20.5 G20.6 | 助手/沉浸/评论标记与 Windows 产物同构；秘密 AES 解密与 Windows 同结果 |
| 6 | 骨碌碌导入 | G20.7 G20.8 | Android 导入产物可被 Windows 直接打开；`.part` 原子替换 + 取消不留残档 |
| 7 | 骨碌碌热更新 | G20.9 | append-only 违例显式失败；无新增不重建；失败回滚保住进度/标注关联 |
| 8 | 骨碌碌阅读交互 A | G20.10 | 当前章评论按需加载 + 缓存/离线回退；段落徽标 ↔ 面板联动；弹幕开关；正文 DOM 与 `text_offset` 不变 |
| 9 | 骨碌碌阅读交互 B/C | G20.11 G20.12 | 骰点/迷雾/秘密/线索/音乐/背景/视效/总览可用；返回书架统一清理 |
| 10 | 收尾与发布 | 契约/纪律测试/文档/版本线 | 契约与 `DisciplineTest` 覆盖新语义；文档漂移扫描通过；发布 `android-v1.2.0` |

## 4. 全局红线（每批必须守住）

1. **`text_offset` 不漂移**：任何 DOM 注入（高亮 `mark.hl-mark`、代码高亮 `span.syntax`、
   骨碌碌徽标/占位）必须遵守 `contracts/text` 的折叠规则；注入元素内部文本
   **不产生相邻文本块分隔空格**，注入后重建坐标。
2. **进度写入唯一入口**：只能经 `ChapterProgressTracker`；分页/滚动字段互不共用
   （分页显式 `ratio=-1`、滚动显式 `page=-1/total=-1`）。
3. **双端边界**：Android 只改 `android/`；共享文件（`docs/`、`contracts/`、DevLog、
   `README.md`）改动必须做 Diff 影响检查。
4. **显式失败**：新增仓库/网络/解析路径一律走 `RepoResult` / `StoreLoadResult` 同款
   sealed 结果；禁止 `catch(Exception)` 后静默降级。
5. **设计令牌**：间距只用 `AnkeSpacing`、圆角只用 `AnkeRadius`、颜色走
   `MaterialTheme.colorScheme` / `ankeColors`。
6. **契约同步**：新增持久化字段必须默认值向后兼容 + 更新
   [DATA_CONTRACT.md](DATA_CONTRACT.md) + 对端忽略未知字段；端私有 sidecar
   （如骨碌碌 `snapshot.json`、解锁/线索状态）明确标注不入双端契约。
7. **桥协议**：`reader-lite.js` 能力扩展走 `BRIDGE_CAPABILITIES` 追加（`VERSION` 不变），
   改 JS 后必须重跑 `node scripts/bundle-reader-lite.js` 并校验 APK 内资源已更新。
8. **回归必测**：任何触及阅读器的批次都要跑"滚动/翻页 → 退出 → 重进位置一致"
   与"连续重进 3 次一致"。

## 5. 端私有存储决策（不入双端契约）

| 数据 | Windows 落点 | Android 落点 |
| --- | --- | --- |
| 骰点解锁状态（按 bookId，上限 3000 → 裁剪 2000） | 前端 `localStorage` | `gululu_unlocks.json`（端私有） |
| 秘密线索（按 bookId + title） | 前端 `localStorage` | `gululu_clues.json`（端私有） |
| 骨碌碌更新基线 | `gululu_library/<id>/snapshot.json` | 同名同结构（端私有） |
| 沉浸偏好 | `settings.json:gululu_immersive` + `localStorage` | `settings.json:gululu_immersive`（入契约，G10 补齐） |

## 6. 进度追踪

| 批 | 状态 | 提交 / 备注 |
| --- | --- | --- |
| 1 阅读器交互对齐 | ✅ 代码完成，待真机复核 | `ef54a4c`；G1/G2/G3 + `TocTree` + 跨端折叠契约；Android JVM 135 项、APK 内 reader-lite 45559B 校验通过；真机待验「长按选中 → 高亮/笔记/书签 → 退出重进一致」 |
| 2 阅读辅助与显示精度 | ✅ 代码完成，待真机复核 | G4/G5/G6/G7；Android JVM 140 项、APK 内 reader-lite 50881B + reader.css 校验通过；真机待验自动滚动/速读/标尺与按书字体 |
| 3 下载参数与数据完整性 | 🚧 G9/G10/G11 已完成；G8（NGA split 目录分章 / page_limit / toc_pid）待做 | Android JVM 144 项；`gululu_immersive` 往返回归测试在岗 |
| 4 骨碌碌数据层 | ⏳ 未开始 | — |
| 5 骨碌碌协议层 | ⏳ 未开始 | — |
| 6 骨碌碌导入 | ⏳ 未开始 | — |
| 7 骨碌碌热更新 | ⏳ 未开始 | — |
| 8 骨碌碌阅读交互 A | ⏳ 未开始 | — |
| 9 骨碌碌阅读交互 B/C | ⏳ 未开始 | — |
| 10 收尾与发布 | ⏳ 未开始 | — |
