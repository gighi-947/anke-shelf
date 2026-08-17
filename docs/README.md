# AnkeShelf 文档索引（docs/README）

> 本文件是 `docs/` 的入口与「现役 / 历史」分类索引。
> 文档漂移检查时，以本索引的状态列为基准之一；历史归档（`docs/archive/`、`DEVLOG_ARCHIVE.md`）只读不改写。

## 现役规范（Active）

| 文档 | 职责 | 备注 |
| --- | --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Windows 端总体架构与数据流 | 现役 |
| [ANDROID_ARCHITECTURE.md](ANDROID_ARCHITECTURE.md) | Android 端总体架构与桥协议 | 现役 |
| [MAINTENANCE_GUIDE.md](MAINTENANCE_GUIDE.md) | 接手维护手册（版本线 / 测试基线 / CI / 构建发布 / 纪律） | 现役；版本线以 README 为准、测试基线以本手册 §7 为准 |
| [CODEBASE_MAP.md](CODEBASE_MAP.md) | 双端代码链路阅读地图 | 现役 |
| [DATA_CONTRACT.md](DATA_CONTRACT.md) | 双端 JSON 数据契约摘要 | 现役；原生书权威见 NATIVE_BOOK_FORMAT.md |
| [NATIVE_BOOK_FORMAT.md](NATIVE_BOOK_FORMAT.md) | 原生书 ank-native/1 格式唯一权威 | 现役 |
| [TEXT_NORMALIZATION_SPEC.md](TEXT_NORMALIZATION_SPEC.md) | text_offset 文本规范化唯一权威 | 现役 |
| [GLOSSARY.md](GLOSSARY.md) | 领域词 ↔ 代码概念术语表 | 现役 |
| [ANDROID_DESIGN_TOKENS.md](ANDROID_DESIGN_TOKENS.md) | Android UI 设计令牌（间距/圆角/颜色） | 现役 |
| [ANIMATION_STANDARDS.md](ANIMATION_STANDARDS.md) | 动效审查标准（transform/opacity、≤300ms、reduced-motion 等） | 现役；新增/修改动画必须遵守 |
| [ARCHITECTURE_ROADMAP.md](ARCHITECTURE_ROADMAP.md) | P0–P4 路线图与执行顺序 | 现役；**待办唯一基线** |
| [REVIEW_ACTION_PLAN.md](REVIEW_ACTION_PLAN.md) | 整改行动计划指针 | 现役指针；历史版见 archive/REVIEW_ACTION_PLAN.md |
| [GULULU_REFERENCE_MATRIX.md](GULULU_REFERENCE_MATRIX.md) | 骨碌碌全能助手功能对照矩阵 | 现役参考 |
| [REFERENCE_MATRIX.md](REFERENCE_MATRIX.md) | 开源参考项目研究矩阵 | 现役参考（P4） |
| [LESSONS_LEARNED.md](LESSONS_LEARNED.md) | 经验教训分类归纳 | 现役 |
| [nga-post-template.bbcode](nga-post-template.bbcode) | NGA 发帖模板 | 资产模板 |

## 日志与历史

| 文档 | 职责 | 状态 |
| --- | --- | --- |
| [AnkeShelf_DevLog.md](../AnkeShelf_DevLog.md) | 现役开发日志（当前状态 + 最近流水） | 现役，每次改动必补记 |
| [DEVLOG_ARCHIVE.md](DEVLOG_ARCHIVE.md) | 历史开发日志归档 | 只读，不改写 |
| [archive/](archive/) | 历史方案 / 审查 / 验收 / 规划快照 | 只读，不改写 |

## 决策记录（ADR）

| 文档 | 主题 |
| --- | --- |
| [adr/README.md](adr/README.md) | ADR 索引 |
| [adr/0001-shared-contract-boundary.md](adr/0001-shared-contract-boundary.md) | 双端共享契约边界 |
| [adr/0002-compose-webview-reader.md](adr/0002-compose-webview-reader.md) | Compose + WebView 阅读架构 |
| [adr/0003-text-offset-utf16.md](adr/0003-text-offset-utf16.md) | text_offset UTF-16 坐标 |
| [adr/0004-native-book-append-only.md](adr/0004-native-book-append-only.md) | 原生书只追加 |
| [adr/0005-json-authoritative-storage.md](adr/0005-json-authoritative-storage.md) | JSON 为权威存储 |

## 资产（非文档）

- [screenshots/](screenshots/)：界面截图
- [logo/](logo/)：Logo 资源

## 维护约定

- **事实源**：版本线以 [README.md](../README.md) 为文档权威（代码事实源 Windows `app/__init__.py`、Android `android/app/build.gradle.kts`）；测试基线以 [MAINTENANCE_GUIDE.md](MAINTENANCE_GUIDE.md) §7 为文档权威；待办以 [ARCHITECTURE_ROADMAP.md](ARCHITECTURE_ROADMAP.md) 为唯一基线。
- **归档纪律**：`docs/archive/` 与 `docs/DEVLOG_ARCHIVE.md` 只保留历史，不改写；新文档入 `docs/` 时同步更新本索引。
