# 安科书架 · 安卓端

安卓端采用 Kotlin + Jetpack Compose 原生重写（外壳），阅读正文使用
`app/src/main/assets/reader/` 下安卓专用精简 WebView 渲染页（不复用桌面
`web/` 代码）。后端语义按 Windows v1.2.0 逐个移植。

> 当前里程碑：M0（工程骨架）。版本线独立：`android-vX.Y.Z`。

## 本地构建

1. 准备 JDK 17+ 与 Android SDK（本机工具链位于仓库根目录 `.tools/`，
   已加入 `.gitignore`；若使用 Android Studio 则直接用其自带 JDK/SDK）。
2. 确认 `android/local.properties` 存在且包含本机 SDK 路径
   （`sdk.dir=...`；该文件不入库）。
3. 在 `android/` 目录执行：

```powershell
..\.tools\gradle-dist\gradle-9.4.1\bin\gradle.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 测试

```powershell
gradle testDebugUnitTest          # JVM 单测（数据层/坐标/搜索/格式）
gradle connectedDebugAndroidTest  # 仪器测试（需模拟器或真机）
```

## 仓库与发布纪律

- 目录归属：`android/` 专属安卓；`app/ web/ tests/` 等只归 Windows。
- 分支：`android/<feature>`；提交前缀 `android:`。
- 标签：`android-vX.Y.Z`；资产：`AnkeShelf-vX.Y.Z-android.apk`（纯 ASCII）。
- 发布前执行 `scripts/check-release.ps1` 扫描凭据。
- 详细见 [VERSIONING.md](VERSIONING.md) 与 [docs/ANDROID_ARCHITECTURE.md](../docs/ANDROID_ARCHITECTURE.md)。
