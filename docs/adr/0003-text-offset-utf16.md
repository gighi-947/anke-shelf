# ADR-0003：text_offset 以 UTF-16 code unit 计数

- 日期：2026-08-14
- 状态：Accepted

## 背景

进度/标注/搜索共用的“章内折叠纯文本偏移”在 Python（按码点）与 JS/Kotlin（按
UTF-16 字符串索引）上不一致，emoji 等星形字符后的命中点会偏移（B1 暴露、B2 统一，
归档 10.3–10.6）。

## 决策

- `text_offset` 是章节折叠纯文本 `T` 内 0 基偏移，**按 UTF-16 code unit 计数**
  （星形字符占 2），与 DOM/JS/Kotlin 字符串索引一致。
- Python 内部按码点扫描，对外输出（搜索 `offset/text_len` 等）统一换算为 UTF-16。
- 折叠规则唯一权威见 [TEXT_NORMALIZATION_SPEC.md](../TEXT_NORMALIZATION_SPEC.md)：
  仅 body、跳过 script/style、标签边界一个空格、空白折叠 + trim、实体解码、
  不做 CSS/Unicode 规范化。

## 替代方案

- 统一改用码点：被否——与 DOM Range/JS/Kotlin 原生索引不匹配，换算面更大。
- CFI 或多锚点 Locator：暂缓——`text_offset` 足够当前需求，长期按路线图 P4 再评估
  演进为 `href + progression + text context + offset` 的 Locator。

## 后果

- 三端（Python/JS/Kotlin+reader-lite.js）由 golden fixtures 逐条对照，任一漂移即失败。
- 已知残余边缘：U+FEFF 在 JS `\s` 中被折叠、Python/Kotlin 不折叠，入口处剔除 BOM。

关联：[TEXT_NORMALIZATION_SPEC.md](../TEXT_NORMALIZATION_SPEC.md)、
[DATA_CONTRACT.md](../DATA_CONTRACT.md)。
