# 参考项目研究矩阵（P4）

> 日期：2026-08-14；本地浅克隆目录：`H:\AnkeShelfReferences`。
> 目的：为长期设计决策找外部案例支撑，**不照搬技术栈**。
> 配套：路线图 P4；长期关注「text_offset → 多锚点 Locator」的演进。

## 1. 克隆清单与固定版本

| 仓库 | 本地 commit | 日期 | 说明 |
| --- | --- | --- | --- |
| koreader | `74f37d1` | 2026-08-11 | 阅读领域模型 / 进度持久化 |
| koreader-sync-server | `597e064` | 2026-05-16 | 跨设备进度同步 API |
| thorium-reader | `3c001e8` | 2026-08-11 | EPUB 标准（Readium2 Locator / LCP） |
| foliate-js | `78914ae` | 2026-05-01 | Web 渲染边界 / CFI / progression |
| calibre | `4597980` | 2026-08-11 | 书库 / Book Identity |
| readest | 未克隆 | — | 跨端架构与进度防抖去重（早期已研究，见归档 9.43 与 README 致谢） |
| Kavita | 未克隆 | — | 本地书库服务器（DB + API + Web 阅读器） |
| LibreraReader | 未克隆 | — | Android 阅读体验 |

## 2. 分仓库研究

### 2.1 KOReader（阅读领域模型）

- 滚动与分页**各自独立持久化**：滚动保存 `percent_finished + last_xpointer`；
  分页保存 `last_page + page_positions + percent_finished`（`readerrolling.lua` /
  `readerpaging.lua` 的 `onSaveSettings`）。
- 精确锚点用文档原生 XPointer；`percent_finished`（0..1）作可移植摘要；
  旧的 `last_percent` 已弃用。

对 AnkeShelf：模式隔离（分页页码 / 滚动锚点 / 比例各自独立）与项目铁律一致；
`text_offset`（章内精确）↔ `page_index/scroll_ratio`（模式锚点）↔ `progress_pct`
（摘要）已有对应结构，无需照搬 XPointer。

### 2.2 koreader-sync-server（同步 API）

- 每个文档一条 Redis hash：`document / percentage / progress（设备侧不透明串） /
  device / device_id / timestamp`；`device_id` 用于区分“最后写入的设备”，避免回声覆盖。

对 AnkeShelf：跨端可移植性靠 `percentage` + 设备侧 opaque blob 与稳定 `document` id。
若未来做同步，需要的是「稳定 book_id + 可移植定位（text_offset 或 progression +
文本上下文）」，不能直接搬 opaque blob 方案。

### 2.3 Readium / Thorium（EPUB 标准与 Locator）

Readium2 的 Locator 结构（`src/common/models/locator.ts`）：

```ts
{
  href: string;                    // 资源（章节）定位
  title?: string;
  text?: { before?, highlight?, after? };  // 文本上下文，兜底
  locations: {
    cfi?: string;                  // 结构锚点（可选项）
    cssSelector?: string;
    position?: number;             // spine 顺序位
    progression?: number;          // 资源内 0..1
    rangeInfo?: ...;
  };
  type?: "last-reading-location" | "bookmark";
}
```

对 AnkeShelf：这正是路线图 P4 说的**多锚点 Locator**——资源 href + 结构锚点
（CFI/selector）+ 数值锚点（position/progression）+ 文本上下文兜底。
建议未来把单一 `text_offset` 演进为 Locator 结构（见 §4），CFI 仅作可选项；
不引入 Readium SDK 运行时依赖。

### 2.4 foliate-js（Web 渲染边界）

- `progress.js`：`SectionProgress` 按“线性章节大小 + 章内比例”合成**全书 progression**，
  并提供 location（按固定 sizePerLoc）与预计阅读时间；`TOCProgress` 把 DOM range
  归到最近的目录项。
- `epubcfi.js`：CFI 作为精确定位；`view/paginator/overlayer` 分层渲染。

对 AnkeShelf：全书 progression 的合成方式可参考（当前只存 chapter_index/text_offset，
没有全书比例）；若要加“全书进度/同步”，补一个派生 progression 即可，向后兼容。
WebView 分层边界与项目一致，不引入。

### 2.5 calibre（书库 / Book Identity）

- 书库 = 目录 + `metadata.db` 索引；**书是一等实体**（稳定 id），
  目录布局 `作者/书名 (id)/`，同书多格式文件共存，元数据有 OPF sidecar。

对 AnkeShelf：`book_id` 与路径解耦、书架 JSON 为权威存储（ADR-0005）已覆盖同类需求；
不照搬 metadata.db（SQLite 仍按路线图延后）。

## 3. 汇总矩阵

| 仓库 | 领域 | 核心机制 | 结论 |
| --- | --- | --- | --- |
| koreader | 阅读领域模型 | XPointer + percent_finished；模式独立持久化 | 参考：保留模式隔离与“精确锚点 + 摘要”双轨 |
| koreader-sync-server | 同步 API | document/percentage/device_id/timestamp | 暂缓：同步需稳定 book_id + 可移植 Locator |
| thorium-reader | EPUB 标准 | Readium2 Locator（href/text/locations） | 采用思路：未来 Locator 结构演进 |
| foliate-js | Web 渲染 | CFI + 全书 progression + TOCProgress | 参考：补全书 progression 派生值 |
| calibre | 书库 | Book Identity + metadata.db | 不采用：JSON 权威存储决策保持 |
| readest | 跨端架构 | 进度防抖/去重/flush | 已吸收（9.43），克隆待补 |
| Kavita / LibreraReader | 服务边界 / Android UX | 待克隆后补充 | 待办 |

## 4. 长期关注：定位器（Locator）演进

现状：`text_offset`（章内折叠纯文本、UTF-16 偏移）是唯一 canonical 坐标。
参考结论：业界主流（Readium、KOReader、foliate）都是「结构锚点 + 数值锚点 +
文本上下文」的多锚点 Locator，单一偏移不是终点。

建议演进方向（**不急着改**，先固化为设计共识）：

- 定义 `Locator { chapter_index, text_offset, ratio?, scroll_ratio?, text_before?,
  text_after? }`；`text_offset` 仍是章内权威；
- 新增“全书 progression”作为可移植派生值（用于同步/摘要），不替代现有字段；
- 跨端/同步恢复用 `book_id + chapter_index + text_offset + 文本上下文` 兜底；
- 任何演进遵守 DATA_CONTRACT 的向后兼容流程（默认值 + 对端忽略未知字段）。

需要时另立 ADR-0006 固化该决策。

## 5. 下一步

- 补齐 readest / Kavita / LibreraReader 克隆后补矩阵行；
- 不引入 Readium SDK / CFI 运行时依赖；
- 定位器演进按真实需求（同步、跨端导入）触发，不提前实现。
