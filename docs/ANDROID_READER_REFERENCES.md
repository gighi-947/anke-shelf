# 安卓阅读器参考项目调研（2026-08）

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
