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
4. **注释与 CDATA**：注释不产生文本也不产生分隔；CDATA 在 HTML 解析下视为
   bogus comment，不产生文本（当前 Kotlin `TextExtractor` 会输出 CDATA 内容，
   属已知分歧，见 §4）。
5. **实体解码**：按 HTML 实体规则解码——命名实体与十进制/十六进制数字实体；
   未知命名实体原样保留（Python `html.parser(convert_charrefs=True)` 语义）。
6. **空白折叠**：`\s+`（Unicode 空白，含 NBSP U+00A0）折叠为单个空格，首尾 trim。
   （当前 Kotlin `\s` 为 ASCII-only，NBSP 不折叠，属已知分歧，见 §4。）
7. **不做 CSS 计算**：`display:none`、`visibility` 等不影响文本提取，内容照常计入。
8. **不做 Unicode 规范化**：不执行 NFC/NFKC，字符原样保留。
9. **偏移计数**：默认按“字符”计；星形字符（emoji 等）在 Python 为 1 个码点、
   JS/Kotlin 为 2 个 UTF-16 code unit，**当前两端偏移不一致**（见 §4）。

## 3. 用例与验证

- 用例文件：[contracts/text/text-cases.json](../contracts/text/text-cases.json)
  （`html → expected` 纯文本 + 采样点 `quote → offset`）。
- B1 起，三端（Python / Windows JS / Android Kotlin+JS）必须逐条通过；
  任一实现与 `expected` 不符即为契约漂移。

## 4. 已知分歧（B1 暴露、B2 统一）

| # | 场景 | Python / Windows JS | Android Kotlin（Text.kt） | 计划 |
|---|------|--------------------|--------------------------|------|
| 1 | `\s` 与 NBSP | `\s` 含 NBSP，折叠 | `Regex("\\s+")` 仅 ASCII 空白，NBSP 保留 | B2 统一空白定义 |
| 2 | HTML 命名实体 | 完整 HTML 实体表 | 内置约 44 个常用实体子集，未知命名实体原样保留 | B2 统一实体表 |
| 3 | CDATA | 视为 bogus comment，无文本 | 输出 CDATA 内容 | B2 统一解析语义 |
| 4 | 星形字符偏移 | Python 按码点；JS 按 UTF-16 code unit | Kotlin 按 UTF-16 code unit | B2 统一 text_offset 计数语义 |

> 注意：`reader-lite.js`（Android 阅读内核）的 `TextPos` 遵循 JS 语义，
> 与 Kotlin `TextExtractor`（搜索）在场景 1/2/3/4 上可能也不一致；
> B1 测试会同时覆盖 Android 侧两条链路。

## 5. 变更流程

- 修改规则必须同步更新本文件、`contracts/text/text-cases.json` 与
  `docs/DATA_CONTRACT.md` 的坐标说明，并在 AnkeShelf_DevLog.md 记录。
- 先改用例（红），再改实现（绿），禁止只改期望值绕过实现。
