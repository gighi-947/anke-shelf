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
    src/main/assets/fonts/    内置霞鹜文楷 + OFL.txt
  scripts/check-release.ps1   发布前凭据扫描
```

## 关键契约

- `applicationId = io.github.gighi947.ankeshelf`；minSdk 26 / targetSdk 36。
- 数据格式与桌面完全同构（JSON schema + 原生书目录结构），存
  `filesDir/AnkeShelf/`，原子写，为未来跨端导入留路。
- 阅读桥：JS 上报 `onReady/saveProgress/onPageChanged/onSelection/log`；
  Kotlin 下发 `applyTheme/applyTypography/gotoOffset/applyAnnotations/flipPage`。
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
