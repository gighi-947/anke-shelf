# ADR-0005：JSON 文件为权威存储

- 日期：2026-08-14
- 状态：Accepted

## 背景

两端需要同构、可跨端导入、可人工诊断的数据存储。早期进度/书架实现曾出现半写、
字段漂移问题（归档 9.53、4.4）。

## 决策

- 权威存储为 UTF-8 JSON 文件：shelf / progress / settings / annotations /
  statistics / 原生书 meta+floors；存放于端私有目录（Windows `%APPDATA%\AnkeShelf\`，
  Android `filesDir/AnkeShelf/`）。
- **原子写**：临时文件 + rename，不落中间态；读写互斥。
- schema/字段带版本，**未知字段一律忽略**；扩展字段默认值向后兼容并同步契约文档。
- 进度写入走单一入口（Windows `saveProgress` / Android `ChapterProgressTracker`）。

## 替代方案

- SQLite/数据库：延后——量级未到，且 JSON 便于双端同构与调试；仅量化瓶颈出现时评估。
- 二进制/自定义格式：被否——可读性、跨端实现与迁移成本差。

## 后果

- 优点：两端同构、可 diff、可人工修复；契约由 schema + fixtures 校验。
- 代价：无事务与索引，高频写入需节流/防抖；单文件损坏的恢复能力属 P3 待办
  （隔离 `.corrupt-*` + 保留最近有效副本 + 完整性校验）。

关联：[DATA_CONTRACT.md](../DATA_CONTRACT.md)、
[ARCHITECTURE_ROADMAP.md](../ARCHITECTURE_ROADMAP.md)（P3 存储恢复）。
