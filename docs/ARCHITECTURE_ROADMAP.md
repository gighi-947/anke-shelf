# AnkeShelf 架构整合路线图（下阶段任务参考）

> 文档日期：2026-08-24（状态核对）
> 当前版本：Windows v1.7.0，Android android-v1.4.0（精确 HEAD 以 `git log` 为准）
> 来源文档：`I:\AnkeShelf_Review_Archive` 下架构改进提案 / review1 / review2 / 架构债清理清单
>
> 本文档是四份外部文档与仓库现状的整合产物，只做方向指引，不含代码改动。
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

当前优先级：

1. 阅读链路性能 A3（分页二分去滚动位移）：可行性已评估（中风险，
   需完整“退出重进 ×3”进度回归），按需立项；
2. 字体子集化（26MB→2-4MB 的进一步收益）：需先拍板生僻字回退系统
   字体的视觉权衡，WOFF2 无损方案已先行落地；
3. P5-C/F 等待用户放行后再实施；P4 参考仓库剩余研究（3/8）按需补齐；
4. 其余事项按“触发式引入”原则延后，不提前动工。

---

## 2. 现状核验（2026-08-24 测试基线复核；代码规模沿用 2026-08-22）

### 2.1 版本与测试基线

| 项 | 现状 |
| --- | --- |
| 主干状态 | `main` 持续推进；v1.6.0 / android-v1.3.0 发布后完成 2026-08-22 防御性编程审查清理批（进度错误出口 / store 损坏显式化 / ApiContext 必填 / 恢复锚点单点 / 双端死表面删除）与性能专项（A1 翻页单次采样 / A2 空白页判定 / 字体 WOFF2 -61%），已发布 v1.7.0 / android-v1.4.0 修复 NGA 楼层卡片边框/卡底在旧 WebView 与 Windows 端不可见的问题（HEAD 以 `git log` 为准） |
| 当前开发分支 | `main`；Windows 骨碌碌 EPUB、图片三态与追加式增量热更新已完成主干合并 |
| Windows Python 单测 | 342 项（2026-08-24 实跑全过） |
| JS 契约测试 | `textpos` 15 cases + `api-contract` 67 methods + `bridge-contract`（桥版本 1 / 能力含 annotation·assist·gululu）+ `reader-lite-parts`（9 parts / 动态字节校验）+ `reader-lite-textpos`（跨端折叠 12 例）+ 启动失败诊断 + `reader-save`（进度写入唯一出口）+ `reader-session` + `nga-cookie` OK |
| Android JVM 单测 | 238 项（237 过 / 1 跳；2026-08-25 实跑复核） |
| Android 真机测试 | ELE-AL00 instrumentation 11 / 11；滚动/分页/交叉模式/图片章节重进通过 |
| UI 实机 harness | 97 项 PASS（需桌面 WebView2） |
| CI | `windows.yml` / `android.yml` / `nightly.yml` / `contracts.yml` |

> 权威基线：版本线见 README 版本表；测试基线详见 `MAINTENANCE_GUIDE.md` §7；本节为路线图快照。

### 2.2 代码规模热点

| 文件 | 行数 | 关注点 |
| --- | --- | --- |
| `android/.../data/Html5Entities.kt` | 2130 | 机械生成表，正常 |
| `android/.../ui/reader/WebViewChapterView.kt` | 895 | 桥与宿主；已审查并补充失败日志、资源拦截 |
| `android/.../ui/shelf/BookshelfScreen.kt` | 793 | 书架页（含 store 损坏横幅），未拆分 |
| `android/.../data/NativeBook.kt` | 632 | 原生书数据层 |
| `android/.../ui/settings/SettingsScreen.kt` | 564 | 已按 Panel 拆分 |
| `android/.../ui/search/SearchScreen.kt` | 565 | 搜索页，未拆分 |
| `android/.../data/Epub.kt` | 574 | EPUB 数据层 |
| `android/.../ui/reader/native/NativeReaderScreen.kt` | 1116 | 外壳已拆 Chrome / Gululu；恢复锚点单点 RestoreAnchor |
| `android/.../ui/download/DownloadScreen.kt` | 352 | 已拆分面板 |
| `android/.../assets/reader/reader-lite.js` | 1862 | 现役渲染内核（parts 9 模块；状态机 Step 0–4 + 跨端折叠/骨碌碌能力；2026-08-22 清理死表面） |
| `web/js/reader.js` | 766 | 核心编排；进度写入经 reader-save.js 唯一出口 |
| `web/js/nga_download.js` | 719 | 已拆 nga-download-panels；骨碌碌逻辑独立在 gululu-download.js |
| `app/gululu_service.py` | 488 | 导入/导出/更新任务状态、取消与事件编排；已去重启动/任务包装 |
| `app/gululu_update.py` | 429 | Windows 私有基线、append-only 合并、旧书迁移与可恢复 EPUB 替换 |
| `app/nga_service.py` | 701 | 下载/更新/清理语义集中；已迁入 TaskManager |
| `web/js/settings.js` | 184 | 已拆 settings-ui / settings-panels |
| `web/css/reader.css` | 2219 | 样式，暂不处理 |

### 2.3 已确认的架构债

| 债 | 证据 | 影响 |
| --- | --- | --- |
| 静默失败（已收敛） | 存储层全链路显式化：六 store Corrupt/IoError 经 `loadGuarded` 报告 issue（书架横幅）+ IoError 写保护；Web 进度/标注写入统一错误出口（ProgressSaver）；ApiContext 服务必填（31 处守卫删除） | 失败用户可见、不覆盖原文件；残余 null 属低风险收尾 |
| 防御式代码（大部分解决） | `Settings.get(key): Any?` 已删除；2026-08-22 审查清理批删除双端死表面与重复设防（恢复锚点单点化、死导出/尸体代码）；`BookSession` lambda 为两个真实实现的设计取舍保留 | 调用关系已显式化，剩余为设计取舍 |
| 桥协议无版本（已解决） | ready 握手 `{bridgeVersion:1, capabilities}` + `BridgeProtocol.isCompatible`；ProgressModel 纯决策可回放 | 协议错配在运行期显式失败并记诊断 |
| API 人工同步（已解决） | 后端 `_HANDLERS` 与前端 `METHODS` 自动对照；`bridge.js` MOCKS 已移除，错误统一走 `ApiError` + HTTP | API 错误不再依赖前端内层 ok 判断 |
| 正则 HTML 清洗（已解决） | `sanitizeReaderBody()` 改为 jsoup DOM 白名单清洗（P2 已完成） | 不可信 HTML 清洗可证明 |
| 依赖不可复现（已解决） | `requirements.in` + 带哈希 lock，CI/PyInstaller 按 lock 安装（P1 已完成） | 同一提交可重复构建 |
| 重复大文件（已解决） | 字体去重为仓库根 `assets/fonts/` canonical 源，双端构建共用（P3 已完成） | Git 体积不再膨胀 |
| 治理文件缺失（已解决） | CONTRIBUTING / SECURITY / Issue·PR 模板 / CODEOWNERS / Dependabot / THIRD_PARTY_NOTICES 已补齐（P3 已完成） | 陌生人可安全参与 |
| 文档膨胀（已治理） | DevLog 历史归档至 `docs/DEVLOG_ARCHIVE.md`，教训收敛至 `docs/LESSONS_LEARNED.md`（P3/10.15 已完成） | 现役日志只留当前状态与最近流水 |
| 编码损坏（已解决） | `web/js/reader.js` 乱码注释已修复（P3 已完成） | 可读性恢复 |

---

## 3. 路线图与待办（2026-08-20 整理）

### 3.1 已关闭批次

| 批次 | 结果 | 状态 |
| --- | --- | --- |
| P0 发行包启动崩溃 | 友好提示 + README/使用说明 .NET 4.8 与解除锁定 | ✅ 项目侧已落地 |
| P0 章节读取失败模型 | `ChapterReadResult`（Success/NotFound/Corrupt/Io） | ✅ 已完成 |
| P1 契约与 API 漂移守卫 | `contracts.yml` + `api_manifest()` + Node/Python 双向对照；MOCKS 已移除 | ✅ 已落地 |
| P1 Android 桥协议版本 + 进度回放 | ready 握手 `{bridgeVersion:1, capabilities}` + `ProgressModel` 纯函数 + 7 份 fixtures | ✅ 已落地 |
| P1 依赖锁定与构建可复现 | `requirements.in` + 哈希 lock；APK 内 reader-lite.js SHA-256 守卫 | ✅ 已落地 |
| P1 首批 ADR 与文档治理 | `docs/adr/` 5 份；CHANGELOG 拆分未做（列入延后） | ✅ 主体完成 |
| P2 Android HTML 清洗 | jsoup DOM allowlist 清洗 | ✅ 已完成 |
| P2 错误模型与 null 清理 | `StoreLoadResult` / `RepoResult` / 损坏 JSON 隔离；残余 null 收敛 | ✅ 核心完成 |
| P2 可观测性与诊断闭环 | `LogEvents` + `Diagnostics` + task_id 全链路 | ✅ 核心完成 |
| P3 大型文件渐进拆分 | reader-lite parts、Settings/Download/NativeReader、Windows settings/nga_download | ✅ 已按痛点完成 |
| P3 任务语义统一试点 | TaskManager 已覆盖 export + NGA | ✅ 已完成 |
| P3 存储恢复能力 | Windows 损坏隔离 + `.bak` + 验证数据完整性；统一备份包 `ank-backup/1` 双端 | ✅ Windows 完成；Android 校验入口见 3.3 |
| P3 开源治理与仓库卫生 | 治理文档、字体去重、乱码修复、THIRD_PARTY_NOTICES | ✅ 已完成 |
| P4 参考项目研究 | 5/8 已克隆并产出 `docs/REFERENCE_MATRIX.md` | ◐ 部分完成，剩余见 3.3 |
| P5-A 快赢批 | 骨碌碌链接提取 + 书名前缀隐藏 + Windows 重命名 | ✅ 已完成 |
| P5-B NGA 裂图修复 | `/img` 代理 + 失败占位 | ✅ 已完成 |
| P5-D 封面系统 | 骨碌碌封面 + 双端自定义封面/恢复默认 | ✅ 已完成 |
| P5-E NGA 凭据傻瓜化 | E1 Cookie 粘贴解析 + E2 应用内登录（Android WebView + Windows pywebview 二级窗） | ✅ 双端完成，真机验证见 3.3 |

### 3.2 暂不实施（按用户要求）

#### P5-C：滚动到底自动翻章（连续阅读）
- 现状：双端滚动/分页到章尾即停。
- 若实施：新设置 `auto_chapter_turn`（默认关）；滚动距底 ≤48px 且停留 ≥800ms
  触发 `nextChapter`（沿用现有 loadChapter 语义：先存旧章精确 offset）。
  **不新增进度写入口**，仍走 `ChapterProgressTracker` / 桌面 `saveProgress`。
- 铁律：必跑“滚动→退出→重进”回归 + 连续重进 3 次；进度 fixtures 不变。
- 文件：`web/js/reader.js`、`assets/reader/reader-lite.parts/`（重打包）、
  双端 Settings 契约扩展。验证：UI harness 新用例 + Android 真机。

#### P5-F：NGA 楼中楼评论（最大件）
- 现状：数据管道已通（`Floor.comments` 解析与序列化保留），但下载不收集、
  渲染不展示。
- 若实施：
  1. 下载参数新增 `collect_comments`（默认关，记录入 meta.json）；
  2. 开启时逐楼拉取楼中楼写入 comments（限速 + 进度反馈）；
  3. 楼层卡片底部内嵌 `<details>` 折叠评论（默认收起）；
  4. **text_offset 红线**：评论只随楼层首次写入；热更新只对新楼层收集；
     已下载楼层永不回填评论。
- 验证：契约 fixture 扩展（带 comments 的 floors）；双端渲染单测；
  进度回归（含评论章节滚动/翻页→退出→重进）。

### 3.3 真实待办（按优先级）

1. **Android 全量对齐真机验证**：批 1–9 代码已全部落地并发布
   `android-v1.2.0`；剩余真机手工验证清单见
   [ANDROID_PARITY_PLAN.md](ANDROID_PARITY_PLAN.md) 批 1–9 备注
   （标注交互、阅读辅助、目录楼分章、联网导入/更新、骰点/评论/沉浸联动等）。
2. **P5-E2 真机手工验证**：Windows 端「安科下载 → 配置 → 在应用内登录」
   实机走一遍；Android 端登录弹窗真机验证。
3. **P4 参考仓库剩余 3 个**：`readest` / `Kavita` / `LibreraReader` 待克隆
   并补 `docs/REFERENCE_MATRIX.md`（需网络通道）。

### 3.4 保持延后（触发条件出现前不动工）

- **P3 剩余大文件拆分**：`BookshelfScreen.kt` / `SearchScreen.kt` /
  `reader.js` 等——等真实变化点或量化瓶颈。
- **P3 Android NGA 下载迁入统一 TaskManager**——路线图明确推迟。
- **P1 CHANGELOG 拆分**——等真实发布节奏需求触发。
- **ContentSource / 第二书源抽象**：当前 NGA 与骨碌碌是两套独立链路，
  无重复读写边界，暂不抽统一接口；等第三个源或第二个真实消费者出现。
- **SQLite / 插件 / 跨端同步 / WorkManager 等**：等第二真实实现或量化瓶颈。

## 4. 推荐执行顺序

1. Android 全量对齐专项按 [ANDROID_PARITY_PLAN.md](ANDROID_PARITY_PLAN.md) 批次推进
   （当前：批 3 剩余 G8 → 批 4 骨碌碌数据层）；
2. P5-E2 双端真机手工验证；
3. 用户放行后，P5-C 先于 P5-F（C 小、F 大，且都属进度类改动，必跑回归）；
4. P4 剩余参考仓库——有网络通道时补研究；
5. 其余保持延后，直到触发条件出现。

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
