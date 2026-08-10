# 文本规范化规范（TEXT_NORMALIZATION_SPEC）

> 用途：规定「章节 HTML → 折叠纯文本 → text_offset」的**唯一权威语义**，
> 供 Windows Python（`app/text.py`）、Windows JS（`web/js/textpos.js`）、
> Android Kotlin（`data/Text.kt`）与 Android JS（`assets/reader/reader-lite.js`）
> 对齐；配套用例见 [contracts/text/text-cases.json](../contracts/text/text-cases.json)。

## 1. 输入与输出

- 输入：章节 XHTML 字符串（UTF-8 解码后）。
- 输出：折叠后的纯文本 `T`；`text_offset` 是 `T` 内 0 基字符偏移。
- 搜索、进度、标注、跳转统一使用该坐标；snippet 等展示层可另行美化，不影响坐标。

## 2. 权威规则（canonical）

1. **body 范围**：仅提取 `<body>` 内文本；无 `<body>` 的片段退化为提取全部
   （与浏览器自动补 body 的语义一致）。
2. **跳过 script/style**：`<script>` 与 `<style>` 及其嵌套内容不计入。
3. **标签边界 = 一个空格**：每个开始/结束/自闭合标签视为相邻文本块之间的
   一个分隔空格（与浏览器“相邻文本节点之间一个空格”等价）。
4. **注释与 CDATA**：注释不产生文本也不产生分隔（被注释分隔的相邻文本节点
   之间同样不插空格，与视觉渲染一致）；CDATA 在 HTML 解析下视为 bogus comment，
   不产生文本（当前 Kotlin `TextExtractor` 会输出 CDATA 内容，属已知分歧，见 §4）。
5. **实体解码**：按 HTML 实体规则解码——命名实体与十进制/十六进制数字实体；
   未知命名实体原样保留（Python `html.parser(convert_charrefs=True)` 语义）。
6. **空白折叠**：`\s+`（Unicode 空白，含 NBSP U+00A0）折叠为单个空格，首尾 trim。
   （当前 Kotlin `\s` 为 ASCII-only，NBSP 不折叠，属已知分歧，见 §4。）
7. **不做 CSS 计算**：`display:none`、`visibility` 等不影响文本提取，内容照常计入。
8. **不做 Unicode 规范化**：不执行 NFC/NFKC，字符原样保留。
9. **偏移计数**：`text_offset` 按 **UTF-16 code unit** 计数（与 DOM/JS/Kotlin
   字符串索引一致；emoji 等星形字符占 2 个 code unit）。Python 内部按码点扫描，
   对外输出（搜索等）统一换算为 UTF-16。

## 3. 用例与验证

- 用例文件：[contracts/text/text-cases.json](../contracts/text/text-cases.json)
  （`html → expected` 纯文本 + 采样点 `quote → offset`）。
- B1 起，三端（Python / Windows JS / Android Kotlin+JS）必须逐条通过；
  任一实现与 `expected` 不符即为契约漂移。

## 4. 分歧记录（B1 暴露，B2 统一）

B1 实机测试暴露的 4 项分歧已在 B2 统一：

| # | 场景 | 统一方式 |
|---|------|---------|
| 1 | `\s` 与 NBSP / Unicode 空白 | Kotlin `Text.kt` 改用 Unicode 空白类（含 NBSP、U+2000–U+3000 空白族），与 Python/JS 折叠一致 |
| 2 | HTML 命名实体（`&thinsp;` 等） | Kotlin 改用完整 HTML5 实体表（`Html5Entities.kt`，由 Python `html.entities.html5` 机械生成） |
| 3 | CDATA | Kotlin 与 Python/JS 一致：视为 bogus comment，不产生文本 |
| 4 | 星形字符偏移 | canonical = UTF-16 code unit；Python 搜索等对外输出统一换算 |

残余边缘（记录在案，暂不处理）：U+FEFF（BOM）在 JS `\s` 中按空白折叠，
Python/Kotlin 不折叠；如遇到 BOM 应在章节文本入口剔除。

> 注意：`reader-lite.js`（Android 阅读内核）与 Kotlin `TextExtractor`（搜索）
> 在以上场景均已与 canonical 对齐；新增用例必须同时覆盖两条链路。

## 5. 变更流程

- 修改规则必须同步更新本文件、`contracts/text/text-cases.json` 与
  `docs/DATA_CONTRACT.md` 的坐标说明，并在 AnkeShelf_DevLog.md 记录。
- 先改用例（红），再改实现（绿），禁止只改期望值绕过实现。
