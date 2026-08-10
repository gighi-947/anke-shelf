# AnkeShelf 双端契约（contracts）

> 目的：把 Windows / Android 之间“共享语义”从文档约定升级为**机器可验证资产**。
> 配套文档：[docs/DATA_CONTRACT.md](../docs/DATA_CONTRACT.md)（JSON schema 字段表）、
> [docs/NATIVE_BOOK_FORMAT.md](../docs/NATIVE_BOOK_FORMAT.md)（原生书格式规范）、
> [docs/TEXT_NORMALIZATION_SPEC.md](../docs/TEXT_NORMALIZATION_SPEC.md)（文本规范化规范）。

## 目录结构

```text
contracts/
├── README.md
├── native-book/       meta.json / floors.json 的 JSON Schema
├── progress/          progress.json 的 JSON Schema
├── annotation/        annotations.json 的 JSON Schema
├── settings/          settings.json 的 JSON Schema
├── text/              文本规范化用例（HTML → 折叠纯文本 + text_offset 采样点）
└── fixtures/
    └── native-book/basic-nga/   最小原生书 fixture（meta/floors/chapters + 期望纯文本）
```

## 使用规则

1. `contracts/` 是双端共享目录，任何一端改动都必须按
   [AGENTS.md](../AGENTS.md) 的 Diff 影响检查核对另一端与 CI。
2. 数据契约字段的增删改遵循 [docs/DATA_CONTRACT.md](../docs/DATA_CONTRACT.md)
   的“新增字段流程”：向后兼容默认值 + 同步更新文档 + 对端忽略未知字段。
3. JSON Schema 用于结构校验（B1 起接入 CI）；fixtures 由两端测试读取同一份数据，
   保证解析/坐标/搜索语义一致，而不是各自维护一份“看起来一样”的样本。
4. 文本用例的 `expected` 是**权威期望值**（以 Windows Python/JS 现行为准，
   见 TEXT_NORMALIZATION_SPEC 的已知分歧清单）；任何一端与期望不符即为契约漂移，
   须修复实现或经评审后修订规范，不允许只改测试绕过。

## 版本

- 每个 schema 顶层带 `version` 或 `format` 字段；新增字段必须向后兼容。
- 本目录本身不设独立版本，随仓库 main 演进；重大契约变更必须在
  AnkeShelf_DevLog.md 记录。
