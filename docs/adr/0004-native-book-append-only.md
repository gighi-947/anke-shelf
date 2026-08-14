# ADR-0004：原生书只追加（ank-native/1）

- 日期：2026-08-14
- 状态：Accepted

## 背景

NGA 连载需要热更新：只拉新增楼层、不重下旧内容，且更新后进度/标注坐标不能漂移。
EPUB 是静态文件，无法承载增量语义。

## 决策

- NGA 下载即建“原生书”容器：`meta.json + floors.json + chapters/*.xhtml`，
  `format = "ank-native/1"`；书架/进度/标注以稳定 `book_id` 为键。
- **章节正文只追加、不重写**：热更新只在末尾追加楼层（填满最后一个普通章节或开新章），
  已有 `chapters/*.xhtml` 前缀不变——这是 `text_offset` 稳定的前提。
- 楼层按 `lou` 升序、`pid` 去重；`chapter_index` 稳定；schema 带版本，升级必须走
  `load → detect → migrate → validate → 原子写回`。

## 替代方案

- 每次全量重下并重建 EPUB：被否——浪费流量，且重排会破坏坐标。
- 立即迁移 SQLite：延后——JSON 满足当前规模，仅在量化瓶颈出现后评估（路线图 P3）。

## 后果

- 优点：纯增量、坐标稳定、导出 EPUB 时可从 `floors.json` 全量重建。
- 代价：正文与楼层数据存在冗余（chapters 是渲染缓存，floors.json 是 canonical），
  修改格式必须保持旧正文前缀不变并提供迁移。

关联：[NATIVE_BOOK_FORMAT.md](../NATIVE_BOOK_FORMAT.md)、
[DATA_CONTRACT.md](../DATA_CONTRACT.md)。
