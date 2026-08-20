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
├── tests/             Node 契约测试（textpos / api-contract / bridge-contract /
│                       reader-lite-parts / reader-lite-textpos 跨端折叠对照）
└── fixtures/
    └── native-book/basic-nga/   最小原生书 fixture（meta/floors/chapters + 期望纯文本）
    └── nga-toc/       NGA 目录楼 fixture（原始楼层 HTML + 期望章节与 split 分章结果）
    └── gululu/        骨碌碌富文本 AST → XHTML 期望（ast-cases.json；批 5 追加助手/沉浸用例）
    └── progress/      进度事件序列夹具（滚动防抖/翻页即时/模式隔离/比例锚点/
                       换章 flush/dispose 迟到事件/连续重进；Kotlin 决策层消费）
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
5. API 清单由机器对照（`contracts/tests/api-contract.test.js` 与
   `tests/test_api_contract.py`）：后端 `app/api` 与前端 `web/js/api-client.js`
   必须一一对应；新增 API 必须同时更新 handler 与客户端。
6. 进度事件序列夹具（`contracts/fixtures/progress/`）由 Android 纯决策层
   `ProgressModel` 消费；JS 桥测试（`bridge-contract.test.js`）校验 ready 握手
   版本与能力清单。改进度语义先改用例（红），再改实现（绿）。
7. 折叠规则跨端对照（`reader-lite-textpos.test.js`）同时加载 Windows
   `web/js/textpos.js` 与 Android `assets/reader/reader-lite.js` 的 `foldItems`，
   逐项比对 `text/raw/mapRaw/ranges`。**注入节点（`.hl-mark` / `.syntax`）内部无缝**
   是标注注入不移动 `text_offset` 的前提，任何一端删除该分支即为契约漂移。
8. NGA 目录楼跨端对照（`fixtures/nga-toc/`）：Windows
   `ngapost2md/toc.py` + `app/native_book._serialize_toc` / `_group_floors_by_toc`
   与 Android `data/NgaTocParser` + `NativeBookWriter` 消费同一份夹具
   （`tests/test_contracts.py::NgaTocFixtureTest` 与 `NgaTocParserTest`）。
   无条目的折叠块两端都必须丢弃；`toc_mode=split` 的分章边界以夹具 `expected` 为准。
9. 骨碌碌 AST 跨端对照（`fixtures/gululu/ast-cases.json`）：Windows
   `app/gululu_ast.render_ast` 与 Android `data/GululuAst.render` 必须**逐字符一致**
   （`tests/test_contracts.py::GululuAstFixtureTest` 与 `GululuAstTest`）。
   两端生成同构 EPUB，章节 XHTML 决定 `text_offset`，标签/空白差异会直接让进度与标注错位。

## 版本

- 每个 schema 顶层带 `version` 或 `format` 字段；新增字段必须向后兼容。
- 本目录本身不设独立版本，随仓库 main 演进；重大契约变更必须在
  AnkeShelf_DevLog.md 记录。
