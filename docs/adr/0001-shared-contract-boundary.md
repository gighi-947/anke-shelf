# ADR-0001：双端代码隔离与共享契约边界

- 日期：2026-08-14
- 状态：Accepted

## 背景

Windows 端（Python + `web/` 前端 + pywebview）与 Android 端（Kotlin + Compose）
各自成体系，但两端要实现同一套产品语义（书架/进度/标注/搜索/原生书）。曾出现
“多套阅读器实现只有一条在跑”、跨端文本折叠语义漂移等真实问题（归档 9.57、10.3）。

## 决策

1. **代码绝不互相引用**：`app/ web/ ngapost2md-python/ tests/` 只归 Windows，
   `android/` 只归 Android；Android 构建只发生在 `android/` 内。
2. **共享契约，不共享运行时代码**：两端共同维护的只有 `README.md`、`docs/`、
   `contracts/`、`LICENSE`、`AnkeShelf_DevLog.md`、`.github/`。数据 JSON schema
   同构（shelf/progress/settings/annotations/statistics/原生书），语义一致性交给
   `contracts/` 的 golden fixtures + CI 自动验证（textpos、api-contract、DisciplineTest）。
3. **版本线与发布分离**：Windows `vX.Y.Z` + zip；Android `android-vX.Y.Z` + apk，
   互不混用；CI 触发范围按端隔离（`android.yml` 仅 `android/**`）。

## 替代方案

- 两端共享同一份运行时（复用 `web/` 或 Python 后端）：被否——移动端时序、生命周期
  与桌面差异巨大，复用反而放大耦合（归档 9.55）。
- Kotlin Multiplatform / Flutter / React Native 统一重写：被否——迁移成本与风险
  不成比例，现架构已验证。

## 后果

- 优点：边界清晰、可独立发布、防互相污染；契约漂移由机器守卫显性化。
- 代价：语义需要在两端各实现一份，靠契约测试与对照测试持续对齐；新字段必须走
  向后兼容流程（默认值 + 更新契约文档 + 对端忽略未知字段）。

关联：[AGENTS.md](../../AGENTS.md)、[DATA_CONTRACT.md](../DATA_CONTRACT.md)、
[contracts/README.md](../../contracts/README.md)。
