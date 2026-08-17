# 安卓阅读器参考项目调研（2026-08）

> **状态（2026-08-16 核对）：调研与实施记录，非现役规范。** 其中分页落地
> （reader-lite.js、双页判定、ReaderPagedCrossTest）已实现并保持现役；
> 后续参考仓库研究见 [REFERENCE_MATRIX.md](REFERENCE_MATRIX.md)（P4）。

## 背景

桌面端 AnkeShelf 的 EPUB 解析与阅读器参考了 **Readest**（`app/epub.py` 注释与
`web/js/paged.js` 均注明借鉴其思路），正文渲染走 WebView/CSS multi-column +
`text_offset`（DOM 坐标 ↔ 纯文本坐标）。安卓端正文同样用 WebView，但交互与
排版必须按移动端习惯重做，因此调研了以下项目。

## 1. Readest（桌面端直接参考源，且有安卓版）

- 仓库：`github.com/readest/readest`（Tauri v2 + Web 前端）
- 平台：Windows / macOS / Linux / Android / iOS（Android 由 Tauri gen/android 生成）
- 阅读器实现：`<iframe>` 直接载入章节 XHTML，CSS multi-column 分页
  （`column-width/column-gap/column-fill:auto`，`scrollLeft` 翻页），
  `text_offset` 通过 DOM Range + 纯文本坐标双向映射（对应桌面
  `web/js/textpos.js`）。
- 对安卓的借鉴点：
  - 分页几何：精确列宽、双页补偶数列、横屏自动双页（桌面 `paged.js` 已有，移植语义）；
  - `text_offset` 定位：`plainToPoint`/`pointToPlain` 双向映射；
  - 主题 CSS 变量注入、切主题不重载页面；
  - 工具栏/目录抽屉的移动端布局。

## 2. Legado / 开源阅读（安卓阅读器交互标杆）

- 仓库：`github.com/gedoor/legado`
- 语言/架构：Kotlin（XML View 为主），支持 TXT/EPUB/网络源，规则化解析。
- 对安卓的借鉴点（交互模式，非代码）：
  - 点按分区：左/右 1/3 翻页（或上下章），中间唤出/隐藏工具栏；
  - 底部设置栏：字号、行距、主题、翻页模式；
  - 章节列表抽屉 + 当前章高亮；
  - 多种翻页模式（覆盖/仿真/滑动/滚动）。

## 3. Quill（Compose + Readium SDK）

- 仓库：`github.com/MohammadAliUstad/Quill`
- 技术栈：Kotlin + Jetpack Compose + Readium SDK + Material 3 Expressive。
- 借鉴点：Compose 阅读器页面组织、Readium 作为未来可选引擎（当前保持自研解析，
  不引入依赖）。

## 4. FlowReader（离线优先 + Clean Architecture）

- 仓库：`github.com/HuZaiGong/flowreader`
- 技术栈：Jetpack Compose，支持 EPUB/TXT/PDF/Markdown，Clean Architecture + MVVM。
- 借鉴点：数据层/仓库分层、书架与阅读器状态管理。

## 5. 其他可参考

- `github.com/Aatricks/Emaki`：轻小说/漫画/EPUB，Compose。
- `github.com/dmzz-yyhyy/LightNovelReader`：Compose 多数据源轻小说阅读器。
- `github.com/Aryan-Raj3112/episteme`：KMP + Compose 多端阅读器。

## 落地结论

- 安卓端继续自研 WebView 渲染壳（不引入 Readium/epub.js 依赖），
  章节内容用自建 HTML 壳承载（`ReaderHtml.kt`），规避 WebView 对
  XML 声明文档渲染空白的问题。
- 交互按 Legado 分区点按实现：左/右翻章、中间唤出/隐藏顶底栏；
  目录侧栏 + 底部工具栏（字号/主题/翻章）已在 M2 阅读器落地。
- 分页与 `text_offset` 精确映射按桌面 `web/js/paged.js`、`textpos.js`
  移植到 `assets/reader/`（M4 完成），颜色规则/标注/统计同步移植。

## UI 设计对照（2026-08 补充）

| 项目 | 顶栏 | 底栏/进度 | 侧栏 | 点按 |
| --- | --- | --- | --- | --- |
| Readest | 返回、书名、TOC、书签、设置 | 页脚、可选的进度条（Ribbon） | TOC/标注/AI 切换抽屉，移动端可滑动关闭 | 点页面中间唤出/隐藏工具栏 |
| Legado | 沉浸式，菜单唤出 | 4 圆钮（搜索/自动翻页/净化/夜间）、长按更多 | 章节列表、点击区域可自定义 | 左/右/中三区可配置 |
| 安卓端现状（本次落地） | 返回、章名、目录 | 上一章/A−/主题/A+/下一章；翻页时显示页码 | 目录侧栏滑入 | 左 1/3 上一章、右 1/3 下一章、中间唤出/隐藏 |

后续按 Readest 补齐：底部细进度条（Ribbon）、书签快捷钮、设置抽屉
（字号/行距/主题/翻页模式集中一处）、沉浸式隐藏系统栏。

## 分页模式落地（2026-08）

- `assets/reader/reader.css + reader-lite.js`（`reader.js` 已于 9.57 删除）已实现 CSS multi-column 分页：
  scrollLeft 按 advance 翻页、双页补偶数列、超大章（>800000 字符）自动回退滚动；
  text_offset 双向映射（TextPos）与桌面 `textpos.js` 语义对齐，并附
  androidTest 跨端对照（`ReaderPagedCrossTest`：PagedMath/TextPos vs Kotlin）。
- 双页模式按设备屏幕比例自动判定（`PagedLayout.shouldAutoDual`）：
  仅横屏且宽 >= 800px；宽高比 <1.2（过方）或 >2.6（超宽屏）不自动双页；
  双页列宽 <300px 回退单页；内容宽受 `page_width * 46em` 上限约束。
- 阅读器底栏已加页码指示（第 X / Y 页）、细进度 Ribbon、滚动/分页切换；
  点按左/右 1/3 翻页（章首/章尾自动切章）、横滑翻页；主题/字号实时应用不重载。
