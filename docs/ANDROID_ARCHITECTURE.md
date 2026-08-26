# 安科书架 · 安卓端架构说明

> 与 Windows 端（Python + pywebview + `web/` 前端）并列的安卓实现。
> 安卓端代码只在 `android/` 目录，不复用桌面 `web/` 代码，避免两端互相污染。

## 总体结构

```
android/
  app/
    src/main/java/io/github/gighi947/ankeshelf/
      ui/          Compose 外壳（书架/下载/搜索/设置/统计）
      data/        EPUB/原生书/JSON 存储/搜索/设置（M1 起）
      service/     NGA 下载前台服务与导出（M3 起）
      reader/      WebView 桥（M2 起）
    src/main/assets/reader/   安卓专用精简渲染页（HTML/CSS/JS）
    assets/fonts（仓库根 canonical 源，经 Gradle 并入 APK）  内置霞鹜文楷 + OFL.txt
  scripts/check-release.ps1   发布前凭据扫描
```

## 关键契约

- `applicationId = io.github.gighi947.ankeshelf`；minSdk 26 / targetSdk 36。
- 数据格式与桌面完全同构（JSON schema + 原生书目录结构），存
  `filesDir/AnkeShelf/`，原子写，为未来跨端导入留路。
- 阅读桥：JS 上报 `onReady({bridgeVersion, capabilities}) / saveProgress /
  saveProgressNow / pageChanged / requestChapter / openImage / onScrollMoved /
  onMode / onSettled / onSelection / onHighlightTap`；Kotlin 下发
  `init / applyTheme / applyTypography / setMode / flipPage / setInsets /
  gotoTextOffset / applyHighlights / clearSelection /
  startAutoScroll / stopAutoScroll / openImageAt / onResize`。ready 握手版本见
  `ui/reader/BridgeProtocol.kt`（当前 1，能力为追加式扩展：
  `paged / scroll / scrollRatio / image / settled / annotation / assist / gululu`），
  不兼容时显式失败并记诊断。
- 标注与代码高亮注入红线：高亮以 `<mark class="hl-mark">`、代码高亮以
  `<span class="tok-*">`（父级 `.syntax`）注入正文，折叠规则把注入节点内部视为无缝
  （不产生分隔空格），因此 `text_offset` 不随注入变化；两类注入都必须在建坐标之前完成。
  跨端对照见 `contracts/tests/reader-lite-textpos.test.js`。
- 版本：`android-vX.Y.Z` 独立标签与 Release 资产。

## 必须保留的桌面语义（移植红线）

- text_offset 统一坐标（Kotlin/JS 双实现，逐字符对齐 + 对照测试）。
- 全文搜索按章限量 50 + 续取更多；大小写敏感/全词匹配；每书历史 ≤10。
- 颜色铁律：只接管默认黑/白文字，显式颜色一律保留。
- 滚动模式=一章到底不分页；分页双页补偶数列；长表格页内滚动。
- 原生书首次下载即建，热更新纯增量；进度/标注坐标稳定。
- 首启不卡顿；深色切换不白闪；内存常驻 <300MB（≥1000 楼样本）。

## 里程碑

| 版本 | 内容 |
|---|---|
| M0 | 工程骨架、CI、文档、构建通过 |
| v0.1.0 | SAF 导入、书架、阅读 MVP、进度、主题 |
| v0.2.0 | NGA 下载/热更新/导出、前台服务 |
| v0.3.0 | 全文搜索、标注、统计、设置全 Tab |
| v1.0.0 | 对齐 Windows v1.2.0 功能清单 |
| v1.1.0 | NGA 凭据傻瓜化（Cookie 粘贴 + 应用内登录）、NGA 主题自适应、深浅色下载选项移除、自定义封面与书籍管理 |
| v1.2.0 | 全量对齐 Windows v1.5.1：阅读器标注/嵌套目录/进度滑块/阅读辅助、骨碌碌全链路（导入/热更新/评论/骰点/秘密/沉浸）、NGA 页数上限与目录楼分章、数据完整性校验入口 |
| v1.3.0 | 对齐 Windows v1.6.0：标签系统、NGA 登录自动保存、阅读设置与登录配置排版优化、全文检索键入筛选、骨碌碌清除缓存后重下、折叠块摘要与骰子遮罩修复 |
| v1.3.1 | 性能优化（翻页/重排进度采样减半、空白页判定提前退出+代际缓存、内置字体 WOFF2 无损 -61%）；本地数据损坏书架横幅+IoError 写保护；修复跨章跳转被旧滚动位置覆盖 |
| v1.3.2 | 修复 Android 10 等旧 WebView 上 NGA 楼层卡片只剩蓝色左线、灰边与卡底丢失的问题；与 Windows v1.6.2 对齐 |
| v1.4.0 | 楼层导出对齐 Windows + GitHub 版本更新提醒（网络失败静默） |
| v1.4.1 | NGA 只看楼主开关（自动获取楼主 uid、更新模式锁定）+ NGA 骰子详细骰点折叠 |
