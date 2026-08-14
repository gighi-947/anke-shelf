# ADR-0002：Compose 外壳 + WebView 渲染内核

- 日期：2026-08-14
- 状态：Accepted

## 背景

Android 阅读器先后尝试过纯 Kotlin 原生渲染（9.33–9.41），但 NGA 楼层卡片/引用/
骰子/彩色字/长表格的视觉保真反复“像素级对齐”仍达不到桌面效果；用户确认“重写
浏览器”不可行后，需要既能保留视觉、又能让 Kotlin 接管壳层交互与持久化的架构。

## 决策

- **WebView 是唯一渲染内核**：`WebViewChapterView.kt` + `assets/reader/reader-lite.js`
  负责分页几何、TextPos 映射、滚动比例、NGA 楼层样式与事件上报。
- **Compose 只做外壳**：`NativeReaderScreen.kt` 负责控制条、目录、图片查看/保存、
  主题、沉浸式与生命周期；进度持久化唯一入口 `ChapterProgressTracker`。
- **Kotlin `PagedLayout` 只作对照参考实现**，由 `ReaderPagedCrossTest` 与
  `DisciplineTest` 防止与 JS 几何漂移，不发展成第二套生产分页器。

## 替代方案

- 纯 Kotlin/Compose 原生渲染：已试并放弃（视觉保真与维护成本不划算）。
- 引入 Readium/epub.js 等第三方阅读内核：被否——保持自研解析与渲染，避免新依赖。
- 桌面 `web/` 前端整目录复用：被否——违反 ADR-0001 的代码隔离。

## 后果

- 优点：NGA 复杂 HTML/CSS 视觉保真由浏览器引擎保证，壳层逻辑可单元测试。
- 代价：跨 JS/Kotlin 边界的异步时序是主要风险区（进度十轮教训 9.43–9.59），
  改动渲染必须同步维护 JS 与 Kotlin 双实现，并校验 APK 内 `reader-lite.js` 已更新。

关联：[ANDROID_ARCHITECTURE.md](../ANDROID_ARCHITECTURE.md)、
[ARCHITECTURE_ROADMAP.md](../ARCHITECTURE_ROADMAP.md)（“明确不做”）。
